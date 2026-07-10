package com.ais.controller;

import com.ais.db.DatabaseManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

@WebServlet("/api/location/*")
public class LocationServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(LocationServlet.class);

    private DatabaseManager db;
    private ObjectMapper mapper;

    @Override
    public void init() {
        db = DatabaseManager.getInstance();
        mapper = new ObjectMapper();
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        resp.setContentType("application/json;charset=UTF-8");
        PrintWriter out = resp.getWriter();

        String path = req.getPathInfo();

        if ("/general-info".equals(path)) {
            JsonNode body = mapper.readTree(req.getInputStream());
            String locCd = body.path("locCd").asText().trim();

            log.info("[Manual Info]locCd: {}", locCd);
            Map<String, Object> result = db.getGeneralInfo(locCd);
            out.write(mapper.writeValueAsString(result));

        } else if ("/schema".equals(path)) {
            Map<String, List<String>> schema = db.introspectSchema();
            out.write(mapper.writeValueAsString(schema));

        } else {
            resp.setStatus(404);
            out.write("{\"error\":\"Unknown endpoint\"}");
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        resp.setContentType("application/json;charset=UTF-8");
        String path = req.getPathInfo();

        if ("/schema".equals(path)) {
            Map<String, List<String>> schema = db.introspectSchema();
            resp.getWriter().write(mapper.writeValueAsString(schema));
        } else {
            resp.setStatus(404);
            resp.getWriter().write("{\"error\":\"Not found\"}");
        }
    }
}
