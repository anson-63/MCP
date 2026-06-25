package com.ais.controller;

import com.ais.service.MCPClientService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@WebServlet("/api/tools")
public class ToolsServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(ToolsServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final MCPClientService mcpService = new MCPClientService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) 
            throws IOException {

        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");

        try {
            List<Map<String, Object>> tools = mcpService.listToolsForUI();
            
            String json = mapper.writeValueAsString(tools);
            resp.getWriter().write(json);

            log.info("Returned {} tools for UI", tools.size());

        } catch (Exception e) {
            log.error("Error fetching tools: {}", e.getMessage());
            resp.setStatus(500);
            resp.getWriter().write("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
}