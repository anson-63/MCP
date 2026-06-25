package com.ais.controller;

import com.ais.service.OllamaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;

@WebServlet("/api/chat")
public class ChatServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(ChatServlet.class);

    private OllamaService ollamaService;
    private ObjectMapper  mapper;

    @Override
    public void init() {
        ollamaService = new OllamaService();
        mapper        = new ObjectMapper();
        log.info("ChatServlet initialized");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        // For easy browser testing: /api/chat?prompt=...
        String prompt = req.getParameter("prompt");
        if (prompt == null) prompt = "";
        handle(req, resp, prompt);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        req.setCharacterEncoding("UTF-8");

        String prompt = "";
        String contentType = req.getContentType();
        log.info("Content-Type: {}", contentType);

        // ── Handle JSON body (frontend uses this) ────────────────
        if (contentType != null && contentType.contains("application/json")) {
            try {
                JsonNode body = mapper.readTree(req.getInputStream());
                prompt = body.path("prompt").asText("").trim();
                log.info("Read JSON body, prompt: '{}'", prompt);
            } catch (Exception e) {
                log.error("Failed to parse JSON body: {}", e.getMessage());
            }
        }
        // ── Handle form data (fallback) ──────────────────────────
        else {
            prompt = req.getParameter("prompt");
            if (prompt == null) prompt = "";
            log.info("Read form param, prompt: '{}'", prompt);
        }

        handle(req, resp, prompt);
    }

    // ── Shared handler used by both GET and POST ─────────────────
    private void handle(HttpServletRequest req, HttpServletResponse resp,
                        String prompt) throws IOException {

        resp.setContentType("application/json;charset=UTF-8");
        PrintWriter out = resp.getWriter();

        if (prompt == null || prompt.trim().isEmpty()) {
            log.warn("Empty prompt received");
            resp.setStatus(400);
            out.write("{\"error\":\"Empty prompt\"}");
            return;
        }

        log.info("User prompt: {}", prompt);
        long startTime = System.currentTimeMillis();

        try {
            // Use HTTP session ID for conversation memory
            String sessionId = req.getSession(true).getId();

            // Invoke agent (passing sessionId for "which of these" follow-ups)
            OllamaService.AgentResult result =
                ollamaService.invoke(prompt, sessionId);

            long elapsed = System.currentTimeMillis() - startTime;

            // Build response (same format as your old servlet)
            ObjectNode response = mapper.createObjectNode();
            response.put("answer",    result.getAnswer());
            response.put("elapsedMs", elapsed);

            ArrayNode toolCallsArray = response.putArray("toolCalls");
            for (OllamaService.AgentResult.ToolCallRecord tc : result.getToolCalls()) {
                ObjectNode tcNode = toolCallsArray.addObject();
                tcNode.put("name",   tc.name());
                tcNode.put("args",   mapper.writeValueAsString(tc.args()));
                tcNode.put("result", tc.result());
            }

            out.write(mapper.writeValueAsString(response));

        } catch (Exception e) {
            log.error("Chat error: {}", e.getMessage(), e);
            resp.setStatus(500);
            out.write("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }
}