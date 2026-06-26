package com.ais.graph;

import com.ais.graph.nodes.FallbackNode;
import com.ais.graph.nodes.FormatterNode;
import com.ais.graph.nodes.PlannerNode;
import com.ais.graph.nodes.PrimaryLlmNode;
import com.ais.graph.nodes.VerifierNode;
import com.ais.service.OllamaService;
import com.ais.service.QueryPlanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VerificationGraphFactory {

    private static final Logger log =
        LoggerFactory.getLogger(VerificationGraphFactory.class);

    public static AgentGraph build(
            OllamaService ollamaService,
            QueryPlanner  queryPlanner,
            String        ollamaBaseUrl,
            String        verifierModel) {

        log.info("Building verification graph. URL={}, Model={}",
            ollamaBaseUrl, verifierModel);

        PlannerNode    planner    = new PlannerNode(queryPlanner);
        PrimaryLlmNode primaryLlm = new PrimaryLlmNode(ollamaService);
        VerifierNode   verifier   = new VerifierNode(
            ollamaService, ollamaBaseUrl, verifierModel);
        FormatterNode  formatter  = new FormatterNode();
        FallbackNode   fallback   = new FallbackNode();

        GraphEdge verifierRouter = state -> {
            GraphState.VerificationResult result = state.getVerificationResult();

            switch (result) {

                case APPROVED:
                    // ── Clean pass ─────────────────────────────────
                    log.info("[Router] APPROVED → formatter");
                    return "formatter";

                case RETRY:
                    if (state.canRetry()) {
                        state.incrementRetry();
                        log.info("[Router] RETRY → primary_llm (attempt {}/{})",
                            state.getRetryCount() + 1, GraphState.MAX_RETRIES);
                        return "primary_llm";
                    }
                    // Max retries: show answer + warning banner
                    log.warn("[Router] Max retries exhausted → formatter (with warning)");
                    return "formatter";

                case REJECTED:
                    // Show answer in collapsible "unverified" section
                    log.warn("[Router] REJECTED → fallback (shows answer with warning)");
                    return "fallback";

                default:
                    log.warn("[Router] Unknown result {} → formatter", result);
                    return "formatter";
            }
        };

        return new AgentGraph()
            .addNode("planner",     planner)
            .addNode("primary_llm", primaryLlm)
            .addNode("verifier",    verifier)
            .addNode("formatter",   formatter)
            .addNode("fallback",    fallback)
            .addEdge("planner",     "primary_llm")
            .addEdge("primary_llm", "verifier")
            .addConditionalEdge("verifier", verifierRouter)
            .setEntryPoint("planner")
            .compile();
    }
}