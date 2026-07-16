package com.ais.graph.nodes;
import com.ais.graph.GraphNode;
import com.ais.graph.GraphState;
import com.ais.graph.VerifierFeedback;
import com.ais.service.MCPClientService;
import com.ais.service.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
/**
 * Performs a bounded, catalog-validated repair requested by VerifierNode.
 *
 * This node deliberately contains no switch over tool names. The verifier's
 * suggestions are untrusted and are resolved through MCPClientService before
 * dispatch. Arguments are filtered to the selected tool's accepted schema,
 * and the dispatcher performs the final validation.
 */
public class PatchNode implements GraphNode {
    private static final Logger log = LoggerFactory.getLogger(PatchNode.class);
    private static final int MAX_REPAIR_CALLS = 3;
    private static final int MAX_REPAIR_OUTPUT_CHARS = 12000;
    private final MCPClientService mcpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    @Deprecated
    public PatchNode() {
        this(new MCPClientService());
    }
    public PatchNode(MCPClientService mcpClient) {
        if (mcpClient == null) {
            throw new IllegalArgumentException("mcpClient must not be null");
        }
        this.mcpClient = mcpClient;
    }
    @Override
    public GraphState process(GraphState state) {
        state.setRepairAttempted(true);
        state.setRepairSucceeded(false);
        VerifierFeedback feedback = state.getVerifierFeedback();
        if (feedback == null || !feedback.requestsToolRepair()) {
            state.setVerificationReason("No concrete catalog tool was supplied for targeted repair");
            return state;
        }
        int executed = 0;
        List<String> failures = new ArrayList<>();
        for (String suggestedTool : feedback.getMissingTools()) {
            if (executed >= MAX_REPAIR_CALLS) {
                failures.add("repair-call limit reached");
                break;
            }
            if (!isSafeToolName(suggestedTool)) {
                failures.add("invalid tool name: " + suggestedTool);
                continue;
            }
            ToolDefinition definition = mcpClient.resolveDefinition(suggestedTool);
            if (definition == null) {
                failures.add("unknown catalog tool: " + suggestedTool);
                continue;
            }
            Set<String> accepted = mcpClient.getAcceptedParameters(definition);
            if (accepted == null) {
                accepted = Collections.emptySet();
            }
            String toolName = definition.getToolName();
            Map<String, Object> arguments = buildArguments(suggestedTool, toolName,
                    feedback, accepted, state);
            String output;
            try {
                output = mcpClient.callTool(toolName, arguments);
            } catch (Exception e) {
                failures.add(toolName + ": " + safeMessage(e));
                continue;
            }
            if (!isSuccessfulToolOutput(output)) {
                failures.add(toolName + ": dispatcher rejected the repair call");
                continue;
            }
            appendToolCall(state, toolName, arguments, output);
            appendRepairOutput(state, toolName, output);
            executed++;
        }
        if (executed > 0) {
            state.setRepairSucceeded(true);
            state.setVerificationReason("Targeted tool repair completed; response will be verified again");
            log.info("[Manual Info][PatchNode] Executed {} targeted repair tool call(s)", executed);
        } else {
            String detail = failures.isEmpty() ? "no repair calls were executable" : join(failures);
            state.setVerificationReason("Targeted repair failed: " + detail);
            log.warn("[PatchNode] No targeted repair executed: {}", detail);
        }
        return state;
    }
    private Map<String, Object> buildArguments(String suggestedTool, String toolName,
            VerifierFeedback feedback, Set<String> accepted, GraphState state) {
        Map<String, Object> suggested = feedback.getToolArguments().get(suggestedTool);
        if (suggested == null && !toolName.equals(suggestedTool)) {
            suggested = feedback.getToolArguments().get(toolName);
        }
        if (suggested == null) {
            suggested = findPreviousArguments(toolName, state.getToolCallDetails());
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (suggested != null) {
            for (Map.Entry<String, Object> entry : suggested.entrySet()) {
                if (accepted.contains(entry.getKey())) {
                    arguments.put(entry.getKey(), entry.getValue());
                }
            }
        }
        addPreviousCodesIfAccepted(arguments, accepted, extractPreviousLocationCodes(state));
        return arguments;
    }
    private Map<String, Object> findPreviousArguments(String toolName, List<Map<String, Object>> details) {
        if (details == null) return Collections.emptyMap();
        for (int i = details.size() - 1; i >= 0; i--) {
            Map<String, Object> detail = details.get(i);
            if (detail == null) continue;
            Object name = detail.get("name");
            if (name == null || !toolName.equalsIgnoreCase(name.toString())) continue;
            Object args = detail.get("args");
            if (args instanceof Map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) args).entrySet()) {
                    if (entry.getKey() != null) {
                        copy.put(entry.getKey().toString(), entry.getValue());
                    }
                }
                return copy;
            }
        }
        return Collections.emptyMap();
    }
    private void addPreviousCodesIfAccepted(Map<String, Object> arguments, Set<String> accepted, List<String> codes) {
        if (codes == null || codes.isEmpty()) return;
        if (accepted.contains("locCds") && !arguments.containsKey("locCds")) {
            arguments.put("locCds", new ArrayList<>(codes));
        } else if (accepted.contains("locCd") && !arguments.containsKey("locCd")) {
            arguments.put("locCd", codes.get(0));
        }
    }
    private List<String> extractPreviousLocationCodes(GraphState state) {
        List<Map<String, Object>> details = state.getToolCallDetails();
        if (details != null) {
            for (int i = details.size() - 1; i >= 0; i--) {
                Map<String, Object> detail = details.get(i);
                if (detail == null) continue;
                Set<String> latest = new LinkedHashSet<String>();
                collectCodes(detail.get("result"), latest);
                if (!latest.isEmpty()) return new ArrayList<String>(latest);
            }
        }
        Set<String> contextCodes = new LinkedHashSet<String>();
        collectCodes(state.getContext(), contextCodes);
        return new ArrayList<String>(contextCodes);
    }
    private void collectCodes(Object value, Set<String> codes) {
        if (value == null) return;
        try {
            JsonNode node = (value instanceof String) ? mapper.readTree((String) value) : mapper.valueToTree(value);
            if (node != null) collectCodes(node, codes);
        } catch (Exception ignored) { }
    }
    private void collectCodes(JsonNode node, Set<String> codes) {
        if (node == null) return;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (("LOC_CD".equalsIgnoreCase(field.getKey())
                        || "CURRENT_LOC_CD".equalsIgnoreCase(field.getKey()))
                        && field.getValue().isValueNode()) {
                    String code = field.getValue().asText();
                    if (code != null && !code.trim().isEmpty()) codes.add(code.trim().toUpperCase());
                }
                collectCodes(field.getValue(), codes);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) collectCodes(child, codes);
        }
    }
    private void appendToolCall(GraphState state, String toolName, Map<String, Object> arguments, String output) {
        state.addToolCallDetail(toolName, arguments, output);
        String oldOutput = state.getRawToolOutput();
        String addition = "Tool: " + toolName + "\nArgs: " + arguments + "\nOutput: " + output;
        state.setRawToolOutput(oldOutput == null || oldOutput.isEmpty() ? addition : oldOutput + "\n---\n" + addition);
    }
    private void appendRepairOutput(GraphState state, String toolName, String output) {
        String safeTool = escapeHtml(toolName);
        String formattedOutput = formatOutputAsHtml(output);
        
        String section = "<details class='verification-repair-details' style='margin-top:16px; border-top:1px dashed #555; padding-top:12px; outline:none;'>"
                + "<summary style='color:#4fc3f7; font-weight:bold; margin-bottom:8px; outline:none; cursor:pointer; font-size:0.9rem;'>"
                + "Show additional verified data from " + safeTool
                + "</summary>"
                + "<div style='margin-top:12px;'>"
                + formattedOutput
                + "</div>"
                + "</details>";
        String existing = state.getPrimaryResponse();
        state.setPrimaryResponse(existing == null || existing.trim().isEmpty() ? section : existing + section);
    }

    private String formatOutputAsHtml(String output) {
        if (output == null || output.trim().isEmpty()) {
            return "<p style='color:#aaa; font-style:italic;'>No data returned.</p>";
        }
        try {
            JsonNode root = mapper.readTree(output);
            JsonNode results = root.path("results");
            
            if (results.isArray() && !results.isEmpty()) {
                return buildHtmlTable(results);
            }
            if (root.isArray() && !root.isEmpty()) {
                return buildHtmlTable(root);
            }
            if (root.isObject() && !root.isEmpty()) {
                return buildKeyValueTable(root);
            }
        } catch (Exception e) {
            log.warn("Failed to parse repair output for HTML formatting: {}", e.getMessage());
        }
        return "<pre style='background:#222; padding:10px; border-radius:4px; font-family:monospace; font-size:0.85rem; overflow-x:auto; color:#ddd;'>" 
               + escapeHtml(truncate(output, MAX_REPAIR_OUTPUT_CHARS)) 
               + "</pre>";
    }

    private String buildHtmlTable(JsonNode arrayNode) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='overflow-x:auto; margin-bottom:10px;'>");
        sb.append("<table class='data-table' style='width:100%; border-collapse:collapse; font-size:0.85rem;'>");
        
        JsonNode first = arrayNode.get(0);
        List<String> headers = new ArrayList<>();
        if (first.isObject()) {
            Iterator<String> fieldNames = first.fieldNames();
            while (fieldNames.hasNext()) {
                headers.add(fieldNames.next());
            }
        }
        
        if (headers.isEmpty()) {
            sb.append("<tbody><tr><td>No structured columns found.</td></tr></tbody></table></div>");
            return sb.toString();
        }
        
        sb.append("<thead><tr style='background:#333; text-align:left;'>");
        for (String h : headers) {
            sb.append("<th style='padding:8px; border:1px solid #444; font-weight:bold; color:#4fc3f7;'>")
              .append(escapeHtml(h))
              .append("</th>");
        }
        sb.append("</tr></thead>");
        
        sb.append("<tbody>");
        int rowCount = 0;
        for (JsonNode row : arrayNode) {
            if (rowCount >= 15) {
                break;
            }
            sb.append("<tr style='background:").append(rowCount % 2 == 0 ? "#1e1e1e" : "#252526").append(";'>");
            for (String h : headers) {
                JsonNode cell = row.path(h);
                String val = cell.isNull() ? "" : cell.asText();
                sb.append("<td style='padding:8px; border:1px solid #444;'>")
                  .append(escapeHtml(val))
                  .append("</td>");
            }
            sb.append("</tr>");
            rowCount++;
        }
        sb.append("</tbody></table></div>");
        
        if (arrayNode.size() > 15) {
            sb.append("<p style='color:#aaa; font-size:0.8rem; margin-top:4px; font-style:italic;'>")
              .append("Showing first 15 of ").append(arrayNode.size()).append(" rows (data truncated for readability).")
              .append("</p>");
        }
        
        return sb.toString();
    }

    private String buildKeyValueTable(JsonNode objectNode) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='overflow-x:auto; margin-bottom:10px;'>");
        sb.append("<table class='data-table' style='width:100%; border-collapse:collapse; font-size:0.85rem;'>");
        sb.append("<tbody>");
        
        Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
        int rowCount = 0;
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            JsonNode valNode = entry.getValue();
            if (valNode.isContainerNode()) {
                continue;
            }
            String val = valNode.isNull() ? "" : valNode.asText();
            sb.append("<tr style='background:").append(rowCount % 2 == 0 ? "#1e1e1e" : "#252526").append(";'>");
            sb.append("<td style='padding:8px; border:1px solid #444; font-weight:bold; color:#aaa; width:30%;'>")
              .append(escapeHtml(key))
              .append("</td>");
            sb.append("<td style='padding:8px; border:1px solid #444;'>")
              .append(escapeHtml(val))
              .append("</td>");
            sb.append("</tr>");
            rowCount++;
        }
        sb.append("</tbody></table></div>");
        return sb.toString();
    }
    private boolean isSuccessfulToolOutput(String output) {
        if (output == null || output.trim().isEmpty()) {
            return false;
        }
        try {
            JsonNode json = mapper.readTree(output);
            if (json != null && json.isObject()) {
                if (json.has("error")
                        && !json.path("error")
                                .asText("")
                                .trim()
                                .isEmpty()) {
                    return false;
                }
                if (json.has("errorCode")) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    private boolean isSafeToolName(String toolName) {
        return toolName != null && toolName.matches("[A-Za-z0-9_]+") && toolName.length() <= 80;
    }
    private String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
    private String truncate(String value, int max) {
        return (value == null || value.length() <= max) ? (value == null ? "" : value) : value.substring(0, max) + "...";
    }
    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
    private String join(List<String> values) {
        return String.join("; ", values);
    }
}
