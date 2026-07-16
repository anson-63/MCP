package com.ais.graph.nodes;

import com.ais.graph.GraphNode;
import com.ais.graph.GraphState;
import com.ais.service.OllamaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Runs the primary LLM pipeline and preserves per-request tool-call audit
 * records across targeted repairs and bounded full retries.
 */
public class PrimaryLlmNode implements GraphNode {

    private static final Logger log = LoggerFactory.getLogger(PrimaryLlmNode.class);
    private final OllamaService ollamaService;

    public PrimaryLlmNode(OllamaService ollamaService) {
        this.ollamaService = ollamaService;
    }

    @Override
    public GraphState process(GraphState state) {
        int attempt = state.getRegenerationCount() + 1;
        log.info("[PrimaryLlmNode] Generating response (attempt {})", attempt);

        try {
            state.setToolCallDetails(new ArrayList<Map<String, Object>>());
            state.setRawToolOutput("");

            OllamaService.AgentResult result = ollamaService.invoke(
                    state.getUserQuery(),
                    state.getSessionId(),
                    state.getAuthorizationContext());

            String responseText = result.getAnswer() != null && !result.getAnswer().isEmpty()
                    ? result.getAnswer() : "No response generated.";
            state.setPrimaryResponse(responseText);
            updateToolState(state, result);
            log.info("[PrimaryLlmNode] Got response ({} chars)", responseText.length());
        } catch (Exception e) {
            log.error("[PrimaryLlmNode] LLM call failed: {}", e.getMessage(), e);
            state.setPrimaryResponse("I encountered an error processing your request.");
            state.setErrorMessage(e.getMessage());
        }
        return state;
    }

    private void updateToolState(GraphState state, OllamaService.AgentResult result) {
        // Update tool names list
        if (result.getToolNames() != null) {
            state.getToolCallsMade().addAll(result.getToolNames());
        }

        // Update raw tool output summary
        if (result.getToolSummary() != null && !result.getToolSummary().isEmpty()) {
            String old = state.getRawToolOutput();
            state.setRawToolOutput((old == null || old.isEmpty()) ? result.getToolSummary() : old + "\n---\n" + result.getToolSummary());
        }

        // Append detailed records
        List<Map<String, Object>> details = state.getToolCallDetails();
        for (OllamaService.AgentResult.ToolCallRecord record : result.getToolCalls()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", record.getToolName());
            entry.put("args", record.getArgs() != null ? record.getArgs() : Collections.emptyMap());
            entry.put("result", record.getOutput() != null ? record.getOutput() : "");
            details.add(entry);
        }

        log.info("[Manual Info][PrimaryLlmNode] Captured {} tool-call record(s)", result.getToolCalls().size());
    }

    private String buildPrompt(GraphState state) {
        if (state.getRetryCount() == 0) return state.getUserQuery();

        return String.format("%s\n\n[Previous answer was flagged: %s. Please provide a more accurate response.]",
                state.getUserQuery(), state.getVerificationReason());
    }
}