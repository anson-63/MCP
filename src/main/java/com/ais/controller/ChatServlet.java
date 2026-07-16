package com.ais.controller;

import com.ais.graph.*;
import com.ais.config.AppConfig;
import com.ais.service.*;
import com.ais.security.AuthorizationContext;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.slf4j.*;
import org.owasp.html.*;
import javax.servlet.*;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

@WebServlet("/api/chat")
public class ChatServlet extends HttpServlet {
    private static final Logger log = LoggerFactory.getLogger(ChatServlet.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    
    private AgentGraph primaryGraph;
    private AgentGraph fallbackGraph;
    private QueryPlanner queryPlanner;
    private OllamaService ollamaService;

    private static final PolicyFactory ANSWER_HTML_POLICY = new HtmlPolicyBuilder()
            .allowElements("p", "br", "strong", "em", "b", "i", "ul", "ol", "li", "div", "span", "table", "thead", "tbody", "tr", "th", "td", "a", "iframe")
            .allowAttributes("class").globally()
            .allowAttributes("href", "target", "rel").onElements("a")
            .allowAttributes("src", "title", "width", "height", "loading").onElements("iframe")
            .allowAttributes("colspan", "rowspan").onElements("th", "td")
            .allowUrlProtocols("http", "https").toFactory();

    @Override
    public void init() throws ServletException {
        try {
            OllamaService.setContextPath(getServletContext().getContextPath());
            this.ollamaService = new OllamaService();
            this.queryPlanner = new QueryPlanner();

            // 1. Primary Graph (Uses Tencent if API key exists, else Ollama)
            this.primaryGraph = VerificationGraphFactory.build(ollamaService, queryPlanner, 
                    AppConfig.ollamaBaseUrl(), AppConfig.verifierModel());

            // 2. Fallback Graph (Always forced to local Ollama)
            this.fallbackGraph = VerificationGraphFactory.build(ollamaService, queryPlanner, 
                    AppConfig.ollamaBaseUrl(), AppConfig.ollamaModel());

            log.info("ChatServlet initialized. Primary and Fallback graphs ready.");
        } catch (Exception e) {
            log.error("Init failed", e);
            throw new ServletException("Init failed", e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        String body = req.getReader().lines().collect(Collectors.joining());
        JsonNode json = body.isEmpty() ? null : mapper.readTree(body);
        String msg = (json != null && json.has("prompt")) ? json.get("prompt").asText() : req.getParameter("prompt");

        if (msg == null || msg.trim().isEmpty()) {
            writeErrorResponse(resp, HttpServletResponse.SC_BAD_REQUEST, "Empty prompt");
            return;
        }

        Object auth = req.getAttribute(AuthorizationContext.REQUEST_ATTRIBUTE);
        if (!(auth instanceof AuthorizationContext)) {
            writeErrorResponse(resp, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
            return;
        }

        GraphState state = new GraphState();
        state.setUserQuery(msg.trim());
        state.setSessionId(req.getSession().getId());
        state.setAuthorizationContext((AuthorizationContext) auth);

        try {
            resp.getWriter().write(buildResponse(primaryGraph.invoke(state)));
        } catch (Exception e) {
            if (!AppConfig.useTencentCloud()) {
                log.error("Local AI execution failed", e);
                writeErrorResponse(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal AI error.");
                return;
            }
            log.warn("Primary AI failed: {}. Falling back...", e.getMessage());
            try {
                GraphState f = fallbackGraph.invoke(state);
                String r = f.getFinalResponse() == null ? "No response from local AI." : f.getFinalResponse();
                f.setFinalResponse("[Fallback: Cloud AI unavailable, using local model]\n\n" + r);
                resp.getWriter().write(buildResponse(f));
            } catch (Exception fatal) {
                log.error("Both AI pipelines failed", fatal);
                writeErrorResponse(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "AI services unavailable.");
            }
        }
    }

    private String buildResponse(GraphState state) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        String rawAnswer = state.getFinalResponse() != null ? state.getFinalResponse() : "Unable to process request.";
        root.put("answer", ANSWER_HTML_POLICY.sanitize(rawAnswer));
        root.set("toolCalls", buildToolCallsJson(state));
        root.put("elapsedMs", state.getElapsedMs());
        root.put("verified", state.getVerificationResult() == GraphState.VerificationResult.APPROVED);
        root.put("verificationResult", String.valueOf(state.getVerificationResult()));
        root.put("regenerations", state.getRegenerationCount());
        root.put("repairAttempts", state.getRepairAttemptCount());
        return mapper.writeValueAsString(root);
    }

    private ArrayNode buildToolCallsJson(GraphState state) {
        ArrayNode arr = mapper.createArrayNode();
        List<Map<String, Object>> details = state.getToolCallDetails();
        if (details != null) {
            for (Map<String, Object> entry : details) {
                ObjectNode node = arr.addObject();
                node.put("name", String.valueOf(entry.get("name")));
                node.set("args", mapper.valueToTree(entry.get("args")));
                node.put("result", String.valueOf(entry.getOrDefault("result", "")));
            }
        }
        return arr;
    }

    private void writeErrorResponse(HttpServletResponse resp, int status, String msg) throws IOException {
        resp.setStatus(status);
        resp.getWriter().write(mapper.writeValueAsString(mapper.createObjectNode().put("error", msg)));
    }
}