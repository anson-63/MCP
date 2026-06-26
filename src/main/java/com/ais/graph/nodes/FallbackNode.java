package com.ais.graph.nodes;

import com.ais.graph.GraphNode;
import com.ais.graph.GraphState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FallbackNode implements GraphNode {

    private static final Logger log = LoggerFactory.getLogger(FallbackNode.class);

    @Override
    public GraphState process(GraphState state) {
        log.warn("[FallbackNode] Building response with difference. Reason: {}",
            state.getVerificationReason());

        state.setFinalResponse(buildResponseWithDifference(state));
        state.setSuccess(false);
        return state;
    }

    private String buildResponseWithDifference(GraphState state) {
        StringBuilder html = new StringBuilder();

        // ── Red warning banner ─────────────────────────────────
        html.append("<div style='")
            .append("background:#2a0a0a;")
            .append("border-left:4px solid #ef5350;")
            .append("border-radius:6px;")
            .append("padding:12px 16px;")
            .append("margin-bottom:14px;")
            .append("font-size:0.88rem;")
            .append("'>");

        html.append("<div style='color:#ef5350;font-weight:bold;margin-bottom:6px;'>")
            .append("❌ Verification Failed")
            .append("</div>");

        html.append("<div style='color:#ddd;line-height:1.5;'>");

        if (state.getVerificationReason() != null
                && !state.getVerificationReason().isEmpty()) {
            html.append(escapeHtml(state.getVerificationReason()));
        } else {
            html.append("The verifier could not confirm this response is accurate.");
        }
        html.append("</div>");

        if (state.getRetryCount() > 0) {
            html.append("<div style='color:#aaa;margin-top:6px;font-size:0.82rem;'>")
                .append("ℹ️ Attempted ")
                .append(state.getRetryCount())
                .append(state.getRetryCount() == 1 ? " retry" : " retries")
                .append(" — none passed verification.")
                .append("</div>");
        }

        html.append("</div>"); // close banner

        // ── Show primary answer anyway ─────────────────────────
        if (state.getPrimaryResponse() != null
                && !state.getPrimaryResponse().trim().isEmpty()) {

            html.append("<details style='margin-top:8px;'>");
            html.append("<summary style='")
                .append("cursor:pointer;")
                .append("color:#aaa;")
                .append("font-size:0.85rem;")
                .append("padding:6px 0;")
                .append("user-select:none;")
                .append("'>")
                .append("🔍 Show unverified answer anyway")
                .append("</summary>");

            html.append("<div style='")
                .append("border-left:4px solid #ef5350;")
                .append("padding-left:12px;")
                .append("margin-top:8px;")
                .append("opacity:0.85;")
                .append("'>");

            html.append("<div style='")
                .append("color:#ef5350;")
                .append("font-size:0.8rem;")
                .append("margin-bottom:8px;")
                .append("'>")
                .append("⚠️ This answer did not pass verification — use with caution:")
                .append("</div>");

            html.append(state.getPrimaryResponse());

            html.append("</div>"); // close answer
            html.append("</details>");

        } else {
            html.append("<p style='color:#aaa;margin-top:8px;'>")
                .append("No answer was generated for this query.")
                .append("</p>");
        }

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