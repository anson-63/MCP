package com.ais.controller;

import com.ais.db.DatabaseManager;
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
import java.util.Map;
import java.util.Collections;

@WebServlet("/api/location/*")
public class LocationServlet extends HttpServlet {

    private static final Logger log =
            LoggerFactory.getLogger(LocationServlet.class);

    private static final String ADMIN_ROLE = "AIS_ADMIN";

    private DatabaseManager db;
    private ObjectMapper mapper;

    @Override
    public void init() throws ServletException {
        db = DatabaseManager.getInstance();
        mapper = new ObjectMapper();
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        prepareJsonResponse(resp);

        String path = req.getPathInfo();

        if ("/general-info".equals(path)) {
            handleGeneralInfo(req, resp);
        } else if ("/schema".equals(path)) {
            if (!requireAdmin(req, resp)) {
                return;
            }

            writeSchema(resp);
        } else {
            sendJsonError(resp, HttpServletResponse.SC_NOT_FOUND,
                    "Unknown endpoint");
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        prepareJsonResponse(resp);

        String path = req.getPathInfo();

        if ("/schema".equals(path)) {
            if (!requireAdmin(req, resp)) {
                return;
            }

            writeSchema(resp);
        } else {
            sendJsonError(resp, HttpServletResponse.SC_NOT_FOUND,
                    "Not found");
        }
    }

    private void handleGeneralInfo(HttpServletRequest req,
                                   HttpServletResponse resp)
            throws IOException {

        JsonNode body = mapper.readTree(req.getInputStream());

        if (body == null || !body.hasNonNull("locCd")) {
            sendJsonError(resp, HttpServletResponse.SC_BAD_REQUEST,
                    "locCd is required");
            return;
        }

        String locCd = body.get("locCd").asText().trim();

        if (locCd.isEmpty() || !locCd.matches("[A-Za-z0-9_-]{1,32}")) {
            sendJsonError(resp, HttpServletResponse.SC_BAD_REQUEST,
                    "Invalid locCd");
            return;
        }

        log.info("[Manual Info] locCd: {}", locCd);

        Map<String, Object> result = db.getGeneralInfo(locCd);
        resp.getWriter().write(mapper.writeValueAsString(result));
    }

    private void writeSchema(HttpServletResponse resp) throws IOException {
        log.info("[Schema] Admin schema request");

        Map<String, List<String>> schema = db.introspectSchema();
        resp.getWriter().write(mapper.writeValueAsString(schema));
    }

    private boolean requireAdmin(HttpServletRequest req,
                                 HttpServletResponse resp)
            throws IOException {

        if (req.isUserInRole(ADMIN_ROLE)) {
            return true;
        }

        log.warn("[Security] Non-admin schema access denied. user={}",
                req.getRemoteUser());

        sendJsonError(resp, HttpServletResponse.SC_FORBIDDEN,
                "Administrator role required");
        return false;
    }

    private void prepareJsonResponse(HttpServletResponse resp) {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("X-Content-Type-Options", "nosniff");
    }

    private void sendJsonError(HttpServletResponse resp,
                               int status,
                               String message)
            throws IOException {

        resp.setStatus(status);

        PrintWriter out = resp.getWriter();
        out.write(mapper.writeValueAsString(
        		Collections.singletonMap("error", message)
        ));
    }
}