package com.ais.controller;

import com.ais.graph.AgentGraph;
import com.ais.graph.GraphState;
import com.ais.graph.VerificationGraphFactory;
import com.ais.config.AppConfig;
import com.ais.service.OllamaService;
import com.ais.service.QueryPlanner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.Map;

@WebServlet("/api/chat")
public class ChatServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(ChatServlet.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private AgentGraph verificationGraph;
    private OllamaService ollamaService;
    private QueryPlanner queryPlanner;

    @Override
    public void init() throws ServletException {
        try {
            OllamaService.setContextPath(getServletContext().getContextPath());

            // ── DELEGATE TO AppConfig (Single source of truth!) ──────────
            boolean useTencent = AppConfig.useTencentCloud();
            String ollamaUrl = AppConfig.ollamaBaseUrl();
            String verifierModel = AppConfig.verifierModel();
            // ─────────────────────────────────────────────────────────────

            log.info("[Manual Info]Active Provider: {}", useTencent ? "Tencent Cloud" : "Ollama");
            log.info("[Manual Info]Ollama URL: {}", ollamaUrl);
            log.info("[Manual Info]Verifier model: {}", verifierModel);

            this.ollamaService = new OllamaService();
            this.queryPlanner = new QueryPlanner();

            this.verificationGraph = VerificationGraphFactory.build(
                    ollamaService,
                    queryPlanner,
                    ollamaUrl,
                    verifierModel
            );

            log.info("[Manual Info]ChatServlet initialized. Graph ready.");

        } catch (Exception e) {
            throw new ServletException("Failed to initialize ChatServlet", e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Access-Control-Allow-Origin", "*");
        PrintWriter out = resp.getWriter();

        // ── Read prompt from JSON body ─────────────────────────
        // index.jsp sends: { "prompt": "..." }
        String userMessage = null;
        try {
            String body = req.getReader()
                    .lines()
                    .collect(Collectors.joining());

            if (body != null && !body.isEmpty()) {
                JsonNode json = mapper.readTree(body);
                if (json.has("prompt")) {
                    userMessage = json.get("prompt").asText();
                } else if (json.has("message")) {
                    userMessage = json.get("message").asText();
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse JSON body: {}", e.getMessage());
        }

        // ── Fallback to form parameters ────────────────────────
        if (userMessage == null || userMessage.trim().isEmpty()) {
            userMessage = req.getParameter("prompt");
        }
        if (userMessage == null || userMessage.trim().isEmpty()) {
            userMessage = req.getParameter("message");
        }

        if (userMessage == null || userMessage.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.write("{\"error\":\"Empty prompt\"}");
            return;
        }

        String sessionId = req.getSession().getId();
        log.info("[Manual Info]Chat request: session={}, prompt={}",
                sessionId, userMessage);

        try {
            GraphState initialState = new GraphState();
            initialState.setUserQuery(userMessage.trim());
            initialState.setSessionId(sessionId);
            initialState.getContext().put("debug", false);

            GraphState finalState = verificationGraph.invoke(initialState);

            out.write(buildResponse(finalState));

        } catch (Exception e) {
            log.error("Chat error: {}", e.getMessage(), e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.write("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json");
        resp.getWriter().write("{\"status\":\"ChatServlet running\"}");
    }

    // ── Build response matching index.jsp expectations ────────────
    // index.jsp reads: data.answer, data.toolCalls, data.elapsedMs, data.error
    private String buildResponse(GraphState state) throws IOException {
        ObjectNode root = mapper.createObjectNode();

        String answer = state.getFinalResponse() != null
                ? state.getFinalResponse()
                : "I was unable to process your request.";
        root.put("answer", answer);

        root.set("toolCalls", buildToolCallsJson(state));
        root.put("elapsedMs", state.getElapsedMs());
        root.put("verified", state.isSuccess());
        root.put("verificationResult", String.valueOf(state.getVerificationResult()));
        root.put("retries", state.getRetryCount());

        return mapper.writeValueAsString(root);
    }

    // index.jsp renderToolCall() expects: { name, args, result }
    private ArrayNode buildToolCallsJson(GraphState state) {
        ArrayNode arr = mapper.createArrayNode();

        List<Map<String, Object>> details = state.getToolCallDetails();
        if (details != null && !details.isEmpty()) {
            // NEW path: real per-call name/args/result, each independent
            for (Map<String, Object> entry : details) {
                ObjectNode node = mapper.createObjectNode();
                node.put("name", String.valueOf(entry.get("name")));
                // args is a real Map -> let Jackson serialize it properly,
                // instead of hand-building/hardcoding a JSON string
                node.set("args", mapper.valueToTree(entry.get("args")));
                Object result = entry.get("result");
                node.put("result", result != null ? String.valueOf(result) : "");
                arr.add(node);
            }
            return arr;
        }

        // OLD fallback path (kept only for safety during migration — should
        // not normally trigger once PrimaryLlmNode's toolCallDetails patch
        // is applied). Still better than before: uses Jackson instead of
        // string concatenation, but args/result are still not per-call here.
        List<String> toolNames = state.getToolCallsMade();
        String toolOutput = state.getRawToolOutput();
        if (toolNames != null) {
            for (String name : toolNames) {
                ObjectNode node = mapper.createObjectNode();
                node.put("name", name);
                node.set("args", mapper.createObjectNode());
                node.put("result", toolOutput != null ? toolOutput : "");
                arr.add(node);
            }
        }
        return arr;
    }

    private String toJsonString(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + escapeJson(value) + "\"";
    }

    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

}
