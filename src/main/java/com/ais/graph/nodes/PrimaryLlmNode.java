package com.ais.graph.nodes;

import com.ais.graph.GraphNode;
import com.ais.graph.GraphState;
import com.ais.service.OllamaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

public class PrimaryLlmNode implements GraphNode {

    private static final Logger log = LoggerFactory.getLogger(PrimaryLlmNode.class);
    private final OllamaService ollamaService;

    public PrimaryLlmNode(OllamaService ollamaService) {
        this.ollamaService = ollamaService;
    }

    @Override
    public GraphState process(GraphState state) {
        int attempt = state.getRetryCount() + 1;
        log.info("[PrimaryLlmNode] Generating response (attempt {})", attempt);

        try {
            String prompt = buildPrompt(state);

            // ── Use invoke() which is the real method ──────────
            OllamaService.AgentResult result =
                ollamaService.invoke(prompt, state.getSessionId());

            // ── Extract answer ─────────────────────────────────
            String responseText = result.getAnswer();
            if (responseText == null || responseText.isEmpty()) {
                responseText = "No response generated.";
            }
            state.setPrimaryResponse(responseText);

            // ── Extract tool metadata ──────────────────────────
            try {
                state.setToolCallsMade(result.getToolNames());
            } catch (Exception e) {
                log.warn("[PrimaryLlmNode] Could not get tool names: {}", e.getMessage());
                state.setToolCallsMade(new ArrayList<>());
            }

            try {
                state.setRawToolOutput(result.getToolSummary());
            } catch (Exception e) {
                log.warn("[PrimaryLlmNode] Could not get tool summary: {}", e.getMessage());
                state.setRawToolOutput("");
            }

            log.info("[PrimaryLlmNode] Got response ({} chars)", responseText.length());

        } catch (Exception e) {
            log.error("[PrimaryLlmNode] LLM call failed: {}", e.getMessage(), e);
            state.setPrimaryResponse("I encountered an error processing your request.");
            state.setErrorMessage(e.getMessage());
        }

        return state;
    }

    private String buildPrompt(GraphState state) {
        if (state.getRetryCount() == 0) {
            return state.getUserQuery();
        }
        // On retry, tell the LLM what was wrong
        return state.getUserQuery()
            + "\n\n[Previous answer was flagged: "
            + state.getVerificationReason()
            + ". Please provide a more accurate response.]";
    }
}