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

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class VerificationGraphFactory {

    private static final Logger log
            = LoggerFactory.getLogger(VerificationGraphFactory.class);

    /**
     * Build the graph reading all configuration from application.properties. No
     * hardcoded Ollama URL/model.
     */
    public static AgentGraph build(OllamaService ollamaService,
            QueryPlanner queryPlanner) {
        return build(
                ollamaService,
                queryPlanner,
                getConfig("ollama.baseUrl", "http://localhost:11434"),
                getConfig("verifier.model", getConfig("ollama.model", "qwen3:4b-q4_K_M"))
        );
    }

    /**
     * Backward-compatible overload. URL/model parameters are only used as
     * fallbacks if application.properties does not override them.
     */
    public static AgentGraph build(
            OllamaService ollamaService,
            QueryPlanner queryPlanner,
            String ollamaBaseUrl,
            String verifierModel) {

        boolean verificationEnabled = getConfigBoolean("graph.verification.enabled", true);
        String effectiveModel = getConfig("verifier.model", verifierModel);

        log.info("[Manual Info]Building verification graph. verificationEnabled={}, URL={}, model={}",
                verificationEnabled, ollamaBaseUrl, effectiveModel);

        PlannerNode planner = new PlannerNode(queryPlanner);
        PrimaryLlmNode primaryLlm = new PrimaryLlmNode(ollamaService);
        FormatterNode formatter = new FormatterNode();
        FallbackNode fallback = new FallbackNode();

        AgentGraph graph = new AgentGraph()
                .addNode("planner", planner)
                .addNode("primary_llm", primaryLlm)
                .addNode("formatter", formatter)
                .addNode("fallback", fallback)
                .addEdge("planner", "primary_llm")
                .setEntryPoint("planner");

        if (verificationEnabled) {
            VerifierNode verifier = new VerifierNode(
                    ollamaService, ollamaBaseUrl, effectiveModel);
            graph.addNode("verifier", verifier)
                    .addEdge("primary_llm", "verifier")
                    .addConditionalEdge("verifier", new VerifierRouter());
        } else {
            log.info("[Manual Info]Verification disabled (graph.verification=false); "
                    + "routing primary_llm → formatter directly");
            graph.addEdge("primary_llm", "formatter");
        }

        return graph.compile();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Config helpers — read from classpath application.properties
    // ═══════════════════════════════════════════════════════════════════
    private static boolean getConfigBoolean(String key, boolean defaultValue) {
        String v = getConfig(key, null);
        if (v == null || v.trim().isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(v.trim());
    }

    private static String getConfig(String key, String defaultValue) {
        // 1. Try classpath application.properties
        try (InputStream is = VerificationGraphFactory.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                String v = props.getProperty(key);
                if (v != null && !v.trim().isEmpty()) {
                    return v.trim();
                }
            }
        } catch (IOException e) {
            log.warn("Could not load classpath application.properties", e);
        }

        // 2. Try -D system property
        String sys = System.getProperty(key);
        if (sys != null && !sys.trim().isEmpty()) {
            return sys.trim();
        }

        // 3. Fallback
        return defaultValue;
    }

    /**
     * Named static inner class instead of lambda. Avoids ClassNotFoundException
     * for anonymous $1 class in Tomcat classloader.
     */
    public static class VerifierRouter implements GraphEdge {

        private static final Logger log
                = LoggerFactory.getLogger(VerifierRouter.class);

        @Override
        public String route(GraphState state) {
            GraphState.VerificationResult result = state.getVerificationResult();
            if (result == GraphState.VerificationResult.APPROVED) {
                log.info("[Manual Info][Router] APPROVED → formatter");
                return "formatter";
            }
            if (result == GraphState.VerificationResult.RETRY) {
                if (state.canRetry()) {
                    state.incrementRetry();
                    log.info("[Manual Info][Router] RETRY → primary_llm (attempt {}/{})",
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
