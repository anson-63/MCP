package com.ais.controller;

import com.ais.db.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Map;

@WebServlet("/report/view")
public class ReportServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(ReportServlet.class);
    private DatabaseManager db;

    @Override
    public void init() {
        db = DatabaseManager.getInstance();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        String type     = req.getParameter("type");
        String locCd    = req.getParameter("locCd");
        String reportId = req.getParameter("reportId");

        log.info("Report request: type={}, locCd={}, reportId={}", 
                 type, locCd, reportId);

        // Map type to table name
        String tableName = getTableName(type);
        if (tableName == null) {
            resp.sendError(404, "Unknown report type: " + type);
            return;
        }

        // Get report data
        Map<String, Object> data = getReportData(tableName, locCd, reportId);

        // Render HTML
        resp.setContentType("text/html;charset=UTF-8");
        PrintWriter out = resp.getWriter();
        out.write(renderReportHtml(type, locCd, reportId, data));
    }

    private String getTableName(String type) {
        switch (type) {
            case "survey":     return "ais.A_SURVEY_REPORT";
            case "maintenance": return "ais.A_MAINT_REPORT";
            case "inspection": return "ais.A_INSPECT_REPORT";
            case "repair":     return "ais.A_REPAIR_REPORT";
            case "photo":      return "ais.A_PHOTO_REPORT";
            default:           return null;
        }
    }

    private Map<String, Object> getReportData(String tableName, 
                                               String locCd, String reportId) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();

        String sql = "SELECT * FROM " + tableName + " WHERE LOC_CD = ?";

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            conn = db.getConnection();
            ps = conn.prepareStatement(sql);
            ps.setString(1, locCd);
            rs = ps.executeQuery();

            if (rs.next()) {
                ResultSetMetaData meta = rs.getMetaData();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    data.put(meta.getColumnName(i), rs.getObject(i));
                }
            }
        } catch (SQLException e) {
            log.error("Report query error: {}", e.getMessage());
            data.put("error", e.getMessage());
        } finally {
            try { if (rs   != null) rs.close();   } catch (Exception ignored) {}
            try { if (ps   != null) ps.close();   } catch (Exception ignored) {}
            try { if (conn != null) conn.close(); } catch (Exception ignored) {}
        }

        return data;
    }

    private String renderReportHtml(String type, String locCd, 
                                     String reportId, Map<String, Object> data) {
        StringBuilder html = new StringBuilder();
        
        html.append("<!DOCTYPE html><html><head>");
        html.append("<meta charset='UTF-8'>");
        html.append("<title>").append(type.toUpperCase()).append(" Report - ").append(locCd).append("</title>");
        html.append("<style>");
        html.append("body { font-family: 'Segoe UI', sans-serif; background:#1a1a2e; color:#eee; padding:30px; }");
        html.append("h1 { color:#e94560; border-bottom:2px solid #0f3460; padding-bottom:10px; }");
        html.append(".info-bar { background:#16213e; padding:12px; border-radius:8px; margin-bottom:20px; }");
        html.append(".info-bar span { margin-right:20px; }");
        html.append(".info-bar strong { color:#e94560; }");
        html.append("table { width:100%; border-collapse:collapse; background:#16213e; }");
        html.append("th { background:#0f3460; color:#e94560; padding:10px; text-align:left; width:30%; }");
        html.append("td { padding:10px; border-top:1px solid #1a3d6e; }");
        html.append("tr:nth-child(even) td, tr:nth-child(even) th { background:rgba(15,52,96,0.3); }");
        html.append(".back-link { display:inline-block; margin-bottom:20px; color:#e94560; text-decoration:none; }");
        html.append(".back-link:hover { text-decoration:underline; }");
        html.append("</style>");
        html.append("</head><body>");
        
        html.append("<a href='javascript:window.close()' class='back-link'>← Close</a>");
        html.append("<h1>📄 ").append(type.toUpperCase()).append(" Report</h1>");
        
        html.append("<div class='info-bar'>");
        html.append("<span><strong>Location:</strong> ").append(escapeHtml(locCd)).append("</span>");
        html.append("<span><strong>Report ID:</strong> ").append(escapeHtml(reportId)).append("</span>");
        html.append("</div>");

        if (data.isEmpty() || data.containsKey("error")) {
            html.append("<p>No data available.</p>");
        } else {
            html.append("<table>");
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                Object value = entry.getValue();
                if (value == null) continue;
                String valStr = value.toString().trim();
                if (valStr.isEmpty()) continue;
                
                html.append("<tr>");
                html.append("<th>").append(escapeHtml(formatLabel(entry.getKey()))).append("</th>");
                html.append("<td>").append(escapeHtml(valStr)).append("</td>");
                html.append("</tr>");
            }
            html.append("</table>");
        }

        html.append("</body></html>");
        return html.toString();
    }

    private String formatLabel(String key) {
        String[] parts = key.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(" ");
            if (part.length() > 0) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;")
                .replace(">","&gt;").replace("\"","&quot;");
    }
}