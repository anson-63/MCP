package com.ais.controller;

import com.ais.graph.AgentGraph;
import com.ais.graph.GraphState;
import com.ais.graph.VerificationGraphFactory;
import com.ais.service.OllamaService;
import com.ais.service.QueryPlanner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@WebServlet("/api/chat")
public class ChatServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(ChatServlet.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private AgentGraph    verificationGraph;
    private OllamaService ollamaService;
    private QueryPlanner  queryPlanner;

    @Override
    public void init() throws ServletException {
        try {
            Properties props = loadProperties();

            // ── Use correct property keys (underscore not dot) ─
            String ollamaUrl     = props.getProperty("ollama.base_url",
                                       "http://<ollama-ip>:11434");
            String verifierModel = props.getProperty("ollama.verifier.model",
                                       props.getProperty("ollama.model",
                                           "qwen3:4b-q4_K_M"));

            log.info("Ollama URL: {}", ollamaUrl);
            log.info("Verifier model: {}", verifierModel);

            this.ollamaService = new OllamaService();
            this.queryPlanner  = new QueryPlanner();

            this.verificationGraph = VerificationGraphFactory.build(
                ollamaService,
                queryPlanner,
                ollamaUrl,
                verifierModel
            );

            log.info("ChatServlet initialized. Graph ready.");

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
        log.info("Chat request: session={}, prompt={}",
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
    private String buildResponse(GraphState state) {
        StringBuilder json = new StringBuilder();
        json.append("{");

        // data.answer
        String answer = state.getFinalResponse() != null
            ? state.getFinalResponse()
            : "I was unable to process your request.";
        json.append("\"answer\":").append(toJsonString(answer)).append(",");

        // data.toolCalls
        json.append("\"toolCalls\":")
            .append(buildToolCallsJson(state)).append(",");

        // data.elapsedMs
        json.append("\"elapsedMs\":").append(state.getElapsedMs()).append(",");

        // extra verification info
        json.append("\"verified\":").append(state.isSuccess()).append(",");
        json.append("\"verificationResult\":\"")
            .append(state.getVerificationResult()).append("\",");
        json.append("\"retries\":").append(state.getRetryCount());

        json.append("}");
        return json.toString();
    }

    // index.jsp renderToolCall() expects: { name, args, result }
    private String buildToolCallsJson(GraphState state) {
        List<String> toolNames = state.getToolCallsMade();
        String toolOutput      = state.getRawToolOutput();

        if (toolNames == null || toolNames.isEmpty()) {
            return "[]";
        }

        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < toolNames.size(); i++) {
            if (i > 0) arr.append(",");
            arr.append("{");
            arr.append("\"name\":").append(toJsonString(toolNames.get(i))).append(",");
            arr.append("\"args\":\"{}\",");
            arr.append("\"result\":")
               .append(toJsonString(toolOutput != null ? toolOutput : ""));
            arr.append("}");
        }
        arr.append("]");
        return arr.toString();
    }

    private String toJsonString(String value) {
        if (value == null) return "\"\"";
        return "\"" + escapeJson(value) + "\"";
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private Properties loadProperties() throws IOException {
        Properties props = new Properties();
        try (java.io.InputStream is = getClass().getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (is != null) {
                props.load(is);
                log.info("Loaded {} properties", props.size());
            } else {
                log.warn("application.properties not found in classpath");
            }
        }
        return props;
    }
}