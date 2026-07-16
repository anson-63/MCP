package com.ais.graph;

import com.ais.graph.nodes.*;
import com.ais.service.OllamaService;
import com.ais.service.QueryPlanner;
import com.ais.service.MCPClientService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Builds the verification graph and wires the targeted repair path.
 */
public class VerificationGraphFactory {

    private static final Logger log = LoggerFactory.getLogger(VerificationGraphFactory.class);

    public static AgentGraph build(OllamaService ollamaService, QueryPlanner queryPlanner) {
        return build(
                ollamaService,
                queryPlanner,
                getConfig("ollama.baseUrl", "http://localhost:11434"),
                getConfig("verifier.model", getConfig("ollama.model", "qwen3:4b-q4_K_M"))
        );
    }

    public static AgentGraph build(
            OllamaService ollamaService,
            QueryPlanner queryPlanner,
            String ollamaBaseUrl,
            String verifierModel) {

        boolean verificationEnabled = getConfigBoolean("graph.verification.enabled", true);
        String effectiveModel = getConfig("verifier.model", verifierModel);

        log.info("[Manual Info] Building verification graph. verificationEnabled={}, URL={}, model={}",
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
            MCPClientService mcpClient = ollamaService.getMcpClient();
            VerifierNode verifier = new VerifierNode(
                    ollamaService,ollamaBaseUrl,
                    effectiveModel,mcpClient
            );
            PatchNode patch = new PatchNode(mcpClient);
            graph.addNode("verifier", verifier)
                 .addNode("patch", patch)
                 .addEdge("primary_llm", "verifier")
                 .addConditionalEdge("verifier",new VerifierRouter())
                 .addConditionalEdge("patch",new PatchRouter());
        } else {
            log.info("[Manual Info] Verification disabled; routing primary_llm → formatter directly");
            graph.addEdge("primary_llm", "formatter");
        }

        return graph.compile();
    }

    private static boolean getConfigBoolean(String key, boolean defaultValue) {
        String value = getConfig(key, null);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static String getConfig(String key, String defaultValue) {
        try (InputStream input = VerificationGraphFactory.class
                .getClassLoader()
                .getResourceAsStream("application.properties")) {

            if (input != null) {
                Properties properties = new Properties();
                properties.load(input);
                String value = properties.getProperty(key);
                if (value != null && !value.trim().isEmpty()) {
                    return value.trim();
                }
            }
        } catch (IOException e) {
            log.warn("Could not load classpath application.properties", e);
        }

        String systemValue = System.getProperty(key);
        if (systemValue != null && !systemValue.trim().isEmpty()) {
            return systemValue.trim();
        }

        return defaultValue;
    }

    /**
     * Routes verifier outcomes. The verifier increments the bounded retry
     * counter before either a targeted patch or a full regeneration.
     */
    public static class VerifierRouter implements GraphEdge {
        private static final Logger log = LoggerFactory.getLogger(VerifierRouter.class);

        @Override
        public String route(GraphState state) {
            GraphState.VerificationResult result = state.getVerificationResult();

            if (result == GraphState.VerificationResult.APPROVED) {
                return "formatter";
            }

            if (state.hasInfrastructureFailure()) {
                log.warn(
                    "[Router] Infrastructure failure → formatter: {}",
                    state.getInfrastructureFailureReason()
                );
                return "formatter";
            }

            if (result == GraphState.VerificationResult.RETRY) {
                VerifierFeedback feedback = state.getVerifierFeedback();
                if (feedback != null
                        && feedback.requestsToolRepair()
                        && state.canAttemptRepair()) {
                    state.incrementRepairAttempt();
                    log.info(
                        "[Router] RETRY → patch ({}/{})",
                        state.getRepairAttemptCount(),
                        GraphState.MAX_REPAIR_ATTEMPTS
                    );
                    return "patch";
                }
                if (state.canRegenerate()) {
                    state.incrementRegeneration();
                    log.info(
                        "[Router] RETRY → primary_llm ({}/{})",
                        state.getRegenerationCount(),
                        GraphState.MAX_REGENERATIONS
                    );
                    return "primary_llm";
                }
                return "formatter";
            }
            if (result == GraphState.VerificationResult.REJECTED) {
                return "fallback";
            }
            return "formatter";
        }
    }

    /**
     * A successful patch is sent back through verification. A failed patch
     * falls back to the ordinary bounded primary-LLM retry path.
     */
    public static class PatchRouter implements GraphEdge {
        private static final Logger log = LoggerFactory.getLogger(PatchRouter.class);

        @Override
        public String route(GraphState state) {
            if (state.isRepairSucceeded()) {
                return "verifier";
            }
            if (state.hasInfrastructureFailure()) {
                return "formatter";
            }
            if (state.canRegenerate()) {
                state.incrementRegeneration();
                return "primary_llm";
            }
            return "formatter";
        }
    }
}