package com.ais.graph.nodes;

import com.ais.graph.GraphNode;
import com.ais.graph.GraphState;
import com.ais.graph.VerifierFeedback;
import com.ais.service.OllamaService;
import com.ais.service.MCPClientService;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import java.util.Iterator;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
/**
 * Verifies the primary answer and emits bounded structured repair feedback.
 *
 * The verifier never invokes a tool directly. Suggested tool names and
 * arguments are untrusted model output; PatchNode resolves and validates them
 * through the catalog/dispatcher before invocation.
 */
public class VerifierNode implements GraphNode {
    private static final Logger log = LoggerFactory.getLogger(VerifierNode.class);
    private final OllamaService ollamaService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final MCPClientService mcpClient;
    
    @Deprecated
    public VerifierNode(OllamaService ollamaService, String ollamaBaseUrl, String verifierModel) {
        this(ollamaService, ollamaBaseUrl, verifierModel, new MCPClientService());
    }
    public VerifierNode(OllamaService ollamaService,
                        String ollamaBaseUrl,
                        String verifierModel,
                        MCPClientService mcpClient) {
        this.ollamaService = ollamaService;
        // Assuming ollamaBaseUrl and verifierModel are stored if they are instance variables
        // this.ollamaBaseUrl = ollamaBaseUrl;
        // this.verifierModel = verifierModel;
        this.mcpClient = mcpClient;
    }
    @Override
    public GraphState process(GraphState state) {
        log.info("[Manual Info][VerifierNode] Verifying. Intent={}, ResponseLen={}",
                state.getDetectedIntent(),
                state.getPrimaryResponse() == null ? 0 : state.getPrimaryResponse().length());
        state.setVerifierFeedback(new VerifierFeedback());
        state.setRepairAttempted(false);
        state.setRepairSucceeded(false);
        String primaryResponse = state.getPrimaryResponse();
        if (primaryResponse == null || primaryResponse.trim().isEmpty()) {
            setRetry(state, "Empty primary response - regenerate the response");
            return state;
        }
        if (isKnownFailureResponse(primaryResponse)) {
            log.info("[Manual Info][VerifierNode] Detected failure pattern in response → RETRY");
            setRetry(state, "Primary response contains no usable data - regenerate with a different approach");
            return state;
        }
        try {
            String response = callLlm(buildPrompt(state));
            parseResponse(response, state);
        } catch (Exception e) {
            log.warn("[VerifierNode] Verification error ({}): {} → auto-approving",
                    e.getClass().getSimpleName(), e.getMessage());
            state.setVerificationResult(GraphState.VerificationResult.APPROVED);
            state.setVerificationReason("Verifier error: " + safeMessage(e) + " → auto-approved");
        }
        log.info("[Manual Info][VerifierNode] Result={}, Reason={}, Repair={}",
                state.getVerificationResult(),
                state.getVerificationReason(),
                state.getVerifierFeedback().getSuggestedAction());
        return state;
    }
    private void setRetry(GraphState state, String reason) {
        VerifierFeedback feedback = new VerifierFeedback();
        feedback.setSuggestedAction(VerifierFeedback.ACTION_REGENERATE);
        feedback.setReason(reason);
        state.setVerifierFeedback(feedback);
        state.setVerificationResult(GraphState.VerificationResult.RETRY);
        state.setVerificationReason(reason);
    }
    private String callLlm(String prompt) throws IOException {
        return ollamaService.callLlmSimple(prompt, 0.0, 512);
    }
    private String buildPrompt(GraphState state) {
        String responseText = stripHtml(state.getPrimaryResponse());
        return "You are a quality checker for an AI database assistant.\n"
                + "The assistant queries a location database through a validated tool catalog.\n"
                + "Evaluate whether the response reasonably answers the question.\n"
                + "Return ONLY valid JSON, with no markdown or explanation.\n\n"
                + "QUESTION: " + safeText(state.getUserQuery()) + "\n\n"
                + "KNOWN PLANNED TOOLS: " + state.getPlannedTools() + "\n\n"
                + "EXECUTED TOOL EVIDENCE:\n" + toolEvidence(state) + "\n\n"
                + "RESPONSE:\n" + truncateForVerification(responseText, 900) + "\n\n"
                + "AVAILABLE CATALOG TOOLS: " + availableToolNames() + "\n\n"
                + "IMPORTANT RULES:\n"
                + "- APPROVED means the response contains relevant data or a valid, clearly explained answer.\n"
                + "- RETRY means the response is incomplete or misses a required database check.\n"
                + "- Compare every requested check with EXECUTED TOOL EVIDENCE.\n"
                + "- Use RETRY + REINVOKE_TOOL when a specific missing or failed catalog call can repair the answer.\n"
                + "- Use an exact known catalog tool name in missingTools; do not invent tool names.\n"
                + "- Put only arguments justified by the question or response in toolArgs.\n"
                + "- A valid no-results answer is not automatically an error.\n\n"
                + "Return exactly this shape:\n"
                + "{\"verdict\":\"APPROVED\",\"confidence\":0.9,\"reason\":\"brief reason\",\"suggestedAction\":\"NONE\",\"missingTools\":[],\"toolArgs\":{}}";
    }
    private String toolEvidence(GraphState state) {
        List<Map<String, Object>> details = state.getToolCallDetails();
        if (details == null || details.isEmpty()) return "No tool calls were recorded.";

        List<Map<String, Object>> evidence = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> detail : details) {
            if (detail == null) continue;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", detail.get("name"));
            item.put("args", detail.get("args"));
            Object output = detail.get("result");
            item.put("result", truncateForVerification(output == null ? "" : output.toString(), 1200));
            evidence.add(item);
        }

        try {
            return truncateForVerification(mapper.writeValueAsString(evidence), 4000);
        } catch (Exception e) {
            return truncateForVerification(evidence.toString(), 4000);
        }
    }
    
    private void parseResponse(String raw, GraphState state) {
        try {
            JsonNode json = mapper.readTree(cleanResponse(raw));
            String verdict = json.has("verdict") ? json.get("verdict").asText().toUpperCase().trim() : "APPROVED";
            String reason = json.has("reason") ? json.get("reason").asText() : "Verification complete";
            double confidence = json.has("confidence") ? json.get("confidence").asDouble(1.0) : 1.0;
            VerifierFeedback feedback = parseFeedback(json, reason, confidence);
            if ("REJECTED".equals(verdict)) {
                log.info("[Manual Info][VerifierNode] Remapping REJECTED → RETRY");
                verdict = "RETRY";
                reason = "[Will retry] " + reason;
            }
            if (confidence < 0.5 && "APPROVED".equals(verdict)) {
                verdict = "RETRY";
                reason = "Low confidence (" + confidence + "): " + reason;
            }
            if (feedback.requestsToolRepair()) {
                verdict = "RETRY";
                reason += " Targeted repair requested for: " + feedback.getMissingTools();
            }
            state.setVerificationResult("RETRY".equals(verdict)
                    ? GraphState.VerificationResult.RETRY : GraphState.VerificationResult.APPROVED);
            feedback.setReason(reason);
            state.setVerifierFeedback(feedback);
            state.setVerificationReason(reason);
        } catch (Exception e) {
            log.warn("[VerifierNode] JSON parse failed: {}", e.getMessage());
            state.setVerificationResult(GraphState.VerificationResult.APPROVED);
            state.setVerificationReason("Verifier response could not be parsed - auto-approved");
            state.setVerifierFeedback(new VerifierFeedback());
        }
    }
    private VerifierFeedback parseFeedback(JsonNode json, String reason, double confidence) {
        VerifierFeedback feedback = new VerifierFeedback();
        feedback.setReason(reason);
        feedback.setConfidence(confidence);
        if (json.has("suggestedAction")) {
            feedback.setSuggestedAction(json.get("suggestedAction").asText().trim().toUpperCase(Locale.ROOT));
        }
        JsonNode missingTools = json.get("missingTools");
        if (missingTools != null && missingTools.isArray()) {
            for (JsonNode item : missingTools) feedback.addMissingTool(item.asText());
        } else if (missingTools != null && missingTools.isTextual()) {
            feedback.addMissingTool(missingTools.asText());
        }
        parseToolArguments(json.get("toolArgs"), feedback);
        if (!feedback.getMissingTools().isEmpty() && VerifierFeedback.ACTION_NONE.equals(feedback.getSuggestedAction())) {
            feedback.setSuggestedAction(VerifierFeedback.ACTION_REINVOKE_TOOL);
        }
        return feedback;
    }
    private boolean isKnownFailureResponse(String response) {
        if (response == null || response.isEmpty()) {
            return true;
        }
        // Only inspect responses that are likely to be error summaries (short and descriptive)
        if (response.length() >= 300) {
            return false;
        }
        String lower = response.toLowerCase(Locale.ROOT);
        return lower.contains("error")
            || lower.contains("exception")
            || lower.contains("stack trace");
    }
    private String cleanResponse(String text) {
        if (text == null) return "{}";
        String cleaned = text.replaceAll("(?s)<think>.*?</think>", "")
                             .replaceAll("(?s)```json\\s*", "")
                             .replaceAll("(?s)```\\s*", "")
                             .trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        return (start >= 0 && end > start) ? cleaned.substring(start, end + 1) : "{}";
    }
    private String stripHtml(String html) {
        return (html == null) ? "" : html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
    private String truncateForVerification(String text, int max) {
        if (text == null || text.length() <= max) return text == null ? "" : text;
        return text.substring(0, max / 2) + " ... [middle omitted] ... " + text.substring(text.length() - (max / 2));
    }
    private String safeText(String text) { return text == null ? "" : text; }
    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
    private String availableToolNames() {
        List<Map<String, Object>> tools = mcpClient.listTools();
        List<String> names = new ArrayList<String>();
        for (Map<String, Object> tool : tools) {
            Object function = tool.get("function");
            if (function instanceof Map) {
                Object name = ((Map<?, ?>) function).get("name");
                if (name != null) {
                    names.add(name.toString());
                }
            }
        }
        return String.join(", ", names);
    }
    
    private void parseToolArguments(JsonNode toolArgs,VerifierFeedback feedback) {
        if (toolArgs == null || !toolArgs.isObject()) return;

        boolean nested = false;
        Iterator<Map.Entry<String, JsonNode>> fields = toolArgs.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getValue().isObject()) continue;
            nested = true;
            feedback.putToolArguments(field.getKey(),
                    mapper.convertValue(field.getValue(),
                            new TypeReference<Map<String, Object>>() {}));
        }

        if (!nested && feedback.getMissingTools().size() == 1) {
            String tool = feedback.getMissingTools().get(0);
            feedback.putToolArguments(tool,
                    mapper.convertValue(toolArgs,
                            new TypeReference<Map<String, Object>>() {}));
        }
    }
}
