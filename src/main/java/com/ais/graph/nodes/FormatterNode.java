package com.ais.graph.nodes;

import com.ais.graph.GraphNode;
import com.ais.graph.GraphState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FormatterNode implements GraphNode {

    private static final Logger log = LoggerFactory.getLogger(FormatterNode.class);

    @Override
    public GraphState process(GraphState state) {
        log.info("[FormatterNode] Formatting response. Verified={}",
            state.getVerificationResult());

        String finalResponse;

        if (state.getVerificationResult() == GraphState.VerificationResult.APPROVED) {
            // ── Clean pass: show response as-is ───────────────
            finalResponse = state.getPrimaryResponse();

        } else {
            // ── Verification flagged issues: show response
            //    AND the verifier's concerns side by side ──────
            finalResponse = buildResponseWithDifference(state);
        }

        state.setFinalResponse(finalResponse);
        state.setSuccess(true); // always true — user always gets output

        log.info("[FormatterNode] Final response ready ({} chars)", 
            finalResponse.length());
        return state;
    }

    /**
     * Shows the primary LLM answer plus a highlighted panel
     * explaining what the verifier found different/missing.
     */
    private String buildResponseWithDifference(GraphState state) {
        StringBuilder html = new StringBuilder();

        // ── Verifier warning banner ────────────────────────────
        html.append("<div style='")
            .append("background:#2a1f00;")
            .append("border-left:4px solid #f0a500;")
            .append("border-radius:6px;")
            .append("padding:12px 16px;")
            .append("margin-bottom:14px;")
            .append("font-size:0.88rem;")
            .append("'>");

        html.append("<div style='color:#f0a500;font-weight:bold;margin-bottom:6px;'>")
            .append("⚠️ Verification Note")
            .append("</div>");

        html.append("<div style='color:#ddd;line-height:1.5;'>");

        // Show verifier reason
        if (state.getVerificationReason() != null
                && !state.getVerificationReason().isEmpty()) {
            html.append(escapeHtml(state.getVerificationReason()));
        } else {
            html.append("The verifier flagged this response as potentially incomplete.");
        }
        html.append("</div>");

        // Show retry count if retried
        if (state.getRetryCount() > 0) {
            html.append("<div style='color:#aaa;margin-top:6px;font-size:0.82rem;'>")
                .append("ℹ️ This answer was regenerated ")
                .append(state.getRetryCount())
                .append(state.getRetryCount() == 1 ? " time" : " times")
                .append(" before being shown.")
                .append("</div>");
        }

        html.append("</div>"); // close banner

        // ── Primary LLM answer ─────────────────────────────────
        html.append("<div style='")
            .append("border-left:4px solid #4fc3f7;")
            .append("padding-left:12px;")
            .append("margin-top:4px;")
            .append("'>");

        html.append("<div style='")
            .append("color:#4fc3f7;")
            .append("font-size:0.8rem;")
            .append("margin-bottom:8px;")
            .append("font-weight:bold;")
            .append("'>")
            .append("📋 Best available answer:")
            .append("</div>");

        html.append(state.getPrimaryResponse() != null
            ? state.getPrimaryResponse()
            : "<p style='color:#aaa;'>No response was generated.</p>");

        html.append("</div>"); // close answer section

        return html.toString();
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }
}