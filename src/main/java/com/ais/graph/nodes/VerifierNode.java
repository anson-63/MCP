package com.ais.graph.nodes;

import com.ais.graph.GraphNode;
import com.ais.graph.GraphState;
import com.ais.service.OllamaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Verifier node for the agent graph.
 *
 * All LLM routing (Ollama or Tencent Cloud) is delegated to
 * {@link OllamaService#callLlmSimple(String, double, int)}. The constructor
 * keeps the old signature (OllamaService, url, model) so that
 * {@code VerificationGraphFactory} needs no changes.
 */
public class VerifierNode implements GraphNode {

    private static final Logger log
            = LoggerFactory.getLogger(VerifierNode.class);

    private final OllamaService ollamaService;
    private final ObjectMapper mapper = new ObjectMapper();

    public VerifierNode(OllamaService ollamaService,
            String ollamaBaseUrl, // kept for compat — no longer used
            String verifierModel) { // kept for compat — no longer used
        this.ollamaService = ollamaService;
        // OllamaService already owns Tencent/Ollama routing based on AppConfig.
        log.info("[Manual Info][VerifierNode] Initialized — routing through OllamaService (Tencent={})",
                com.ais.config.AppConfig.useTencentCloud());
    }

    @Override
    public GraphState process(GraphState state) {
        log.info("[Manual Info][VerifierNode] Verifying. Intent={}, ResponseLen={}",
                state.getDetectedIntent(),
                state.getPrimaryResponse() != null
                ? state.getPrimaryResponse().length() : 0);

        // Guard: nothing to verify
        if (state.getPrimaryResponse() == null
                || state.getPrimaryResponse().trim().isEmpty()) {
            state.setVerificationResult(GraphState.VerificationResult.RETRY);
            state.setVerificationReason("Empty primary response - will retry");
            return state;
        }

        // Pre-check: detect known failure patterns
        if (isKnownFailureResponse(state.getPrimaryResponse())) {
            log.info("[Manual Info][VerifierNode] Detected failure pattern in response → RETRY");
            state.setVerificationResult(GraphState.VerificationResult.RETRY);
            state.setVerificationReason(
                    "Primary LLM returned no data - retrying with different approach");
            return state;
        }

        // Guard: very short responses → RETRY
        if (state.getPrimaryResponse().length() < 100) {
            log.info("[Manual Info][VerifierNode] Very short response ({} chars) → RETRY",
                    state.getPrimaryResponse().length());
            state.setVerificationResult(GraphState.VerificationResult.RETRY);
            state.setVerificationReason("Response too short - retrying");
            return state;
        }

        try {
            String prompt = buildPrompt(state);
            String response = callLlm(prompt); // delegates to OllamaService (Tencent or Ollama)
            parseResponse(response, state);
        } catch (Exception e) {
            log.warn("[VerifierNode] Verification error ({}): {} → auto-approving",
                    e.getClass().getSimpleName(), e.getMessage());
            state.setVerificationResult(GraphState.VerificationResult.APPROVED);
            state.setVerificationReason(
                    "Verifier error: " + e.getMessage() + " → auto-approved");
        }

        log.info("[Manual Info][VerifierNode] Result={}, Reason={}",
                state.getVerificationResult(),
                state.getVerificationReason());

        return state;
    }

    /**
     * Calls the LLM for verification. OllamaService.callLlmSimple() handles
     * Tencent vs Ollama routing automatically based on application.properties
     * config.
     *
     * temperature=0.0 → deterministic verdict maxTokens=512 → short response,
     * just need the JSON verdict
     */
    private String callLlm(String prompt) throws IOException {
        return ollamaService.callLlmSimple(prompt, 0.0, 512);
    }

    private String buildPrompt(GraphState state) {
        return "You are a quality checker for an AI database assistant.\n"
                + "The AI queries a location database and returns results.\n"
                + "Evaluate if the response reasonably answers the question.\n"
                + "Return ONLY valid JSON, no other text.\n\n"
                + "QUESTION: " + state.getUserQuery() + "\n\n"
                + "RESPONSE (first 500 chars):\n"
                + truncate(stripHtml(state.getPrimaryResponse()), 500) + "\n\n"
                + "IMPORTANT RULES:\n"
                + "- Use APPROVED if response contains relevant data/information\n"
                + "- Use RETRY if response seems incomplete but not wrong\n"
                + "- Use REJECTED ONLY if response contains harmful or completely "
                + "  made-up information\n"
                + "- Database 'no results' or empty responses = RETRY not REJECTED\n"
                + "- HTML tables with data = APPROVED\n\n"
                + "Return exactly this JSON:\n"
                + "{\"verdict\":\"APPROVED\",\"confidence\":0.9,"
                + "\"reason\":\"brief reason\"}\n\n"
                + "verdict = APPROVED | RETRY | REJECTED";
    }

    private void parseResponse(String raw, GraphState state) {
        try {
            String cleaned = cleanResponse(raw);
            JsonNode json = mapper.readTree(cleaned);

            String verdict = json.has("verdict")
                    ? json.get("verdict").asText().toUpperCase().trim()
                    : "APPROVED";

            String reason = json.has("reason")
                    ? json.get("reason").asText()
                    : "Verification complete";

            double confidence = json.has("confidence")
                    ? json.get("confidence").asDouble(1.0)
                    : 1.0;

            // Remap REJECTED → RETRY; never hard-reject on first attempt.
            if ("REJECTED".equals(verdict)) {
                log.info("[Manual Info][VerifierNode] Remapping REJECTED → RETRY (will retry before giving up)");
                verdict = "RETRY";
                reason = "[Will retry] " + reason;
            }

            // Low confidence → retry
            if (confidence < 0.5 && "APPROVED".equals(verdict)) {
                verdict = "RETRY";
                reason = "Low confidence (" + confidence + "): " + reason;
            }

            switch (verdict) {
                case "RETRY":
                    state.setVerificationResult(GraphState.VerificationResult.RETRY);
                    break;
                case "APPROVED":
                default:
                    state.setVerificationResult(GraphState.VerificationResult.APPROVED);
                    break;
            }
            state.setVerificationReason(reason);

        } catch (Exception e) {
            log.warn("[VerifierNode] JSON parse failed '{}': {}",
                    raw.substring(0, Math.min(100, raw.length())), e.getMessage());
            state.setVerificationResult(GraphState.VerificationResult.APPROVED);
            state.setVerificationReason("Parse failed - auto-approved");
        }
    }

    private boolean isKnownFailureResponse(String response) {
        if (response == null) {
            return true;
        }
        String lower = response.toLowerCase();
        return lower.contains("no results found")
                || lower.contains("no data found")
                || lower.contains("no locations found")
                || lower.contains("no history found")
                || lower.contains("could not find")
                || lower.contains("unable to find")
                || lower.contains("0 results")
                || lower.contains("no records")
                || (lower.contains("error") && response.length() < 200);
    }

    private String cleanResponse(String text) {
        if (text == null) {
            return "{}";
        }
        // Strip thinking tags (Qwen3 / DeepSeek)
        text = text.replaceAll("(?s)<think>.*?</think>", "").trim();
        // Strip markdown code fences
        text = text.replaceAll("(?s)```json\\s*", "")
                .replaceAll("(?s)```\\s*", "")
                .trim();
        // Extract first JSON object
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return "{}";
    }

    private String stripHtml(String html) {
        if (html == null) {
            return "";
        }
        return html.replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }
}
