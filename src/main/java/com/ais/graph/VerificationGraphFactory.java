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

        // ── Use named class instead of lambda to avoid $1 classloading issue ──
        GraphEdge verifierRouter = new VerifierRouter();

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

    /**
     * Named static inner class instead of lambda.
     * Avoids ClassNotFoundException for anonymous $1 class in Tomcat classloader.
     */
    public static class VerifierRouter implements GraphEdge {

        private static final Logger log =
            LoggerFactory.getLogger(VerifierRouter.class);

        @Override
        public String route(GraphState state) {
            GraphState.VerificationResult result = state.getVerificationResult();

            if (result == GraphState.VerificationResult.APPROVED) {
                log.info("[Router] APPROVED → formatter");
                return "formatter";
            }

            if (result == GraphState.VerificationResult.RETRY) {
                if (state.canRetry()) {
                    state.incrementRetry();
                    log.info("[Router] RETRY → primary_llm (attempt {}/{})",
                        state.getRetryCount() + 1,
                        GraphState.MAX_RETRIES);
                    return "primary_llm";
                }
                log.warn("[Router] Max retries ({}) exhausted → formatter (best effort)",
                    state.getRetryCount());
                return "formatter";
            }

            if (result == GraphState.VerificationResult.REJECTED) {
                log.warn("[Router] REJECTED → fallback. Reason={}",
                    state.getVerificationReason());
                return "fallback";
            }

            log.warn("[Router] Unknown result {} → formatter", result);
            return "formatter";
        }
    }
}