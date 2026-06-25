package com.ais.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import com.ais.service.ReportTypeRegistry;

import com.ais.config.AppConfig;
public class DatabaseManager {
	private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private static DatabaseManager instance;
    private final HikariDataSource dataSource;

    private DatabaseManager() {
        String user     = AppConfig.dbUser();
        String password = AppConfig.dbPassword();
        String server   = AppConfig.dbServer();
        String database = AppConfig.dbName();

        log.info("Connecting to: {} / {}", server, database);

        // Force load JDBC driver
        try {
            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver"); //hardcode JDBC driver class
            log.info("✅ SQL Server JDBC driver loaded");
        } catch (ClassNotFoundException e) {
            log.error("❌ SQL Server JDBC driver NOT FOUND in classpath!");
            throw new RuntimeException("JDBC Driver not found", e);
        }

        HikariConfig config = new HikariConfig();
        config.setDriverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver"); //hardcode JDBC driver class
        config.setJdbcUrl(
            "jdbc:sqlserver://" + server + ";databaseName=" + database
            + ";encrypt=true;trustServerCertificate=true" //hardcode connection options
            + ";loginTimeout=10" //hardcode login timeout
        );
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(AppConfig.dbPoolMaxSize());
        config.setMinimumIdle(AppConfig.dbPoolMinIdle());
        config.setConnectionTimeout(AppConfig.dbConnectionTimeout());

        this.dataSource = new HikariDataSource(config);
        log.info("Database connection pool initialized");
    }

    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    // ── Location query ──────────────────────────────────────────────
    public Map<String, Object> getGeneralInfo(String locCd) {
        String sql = "SELECT * FROM ais.A_GENERAL_INFO WHERE LOC_CD = ?";

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        
        try {
            conn = getConnection();
            ps = conn.prepareStatement(sql);
            ps.setString(1, locCd);
            rs = ps.executeQuery();
            
            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();

            if (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                for (int i = 1; i <= cols; i++) {
                    row.put(meta.getColumnName(i), rs.getObject(i));
                }
                return row;
            }

            Map<String, Object> notFound = new HashMap<String, Object>();
            notFound.put("error", "Not found");
            return notFound;

        } catch (SQLException e) {
            log.error("DB error: {}", e.getMessage());
            Map<String, Object> error = new HashMap<String, Object>();
            error.put("error", e.getMessage());
            return error;
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignored) {}
            try { if (ps != null) ps.close(); } catch (Exception ignored) {}
            try { if (conn != null) conn.close(); } catch (Exception ignored) {}
        }
    }

    // ── Introspect schema ────────────────────────────────────────────
    public Map<String, List<String>> introspectSchema() {
        Map<String, List<String>> schema = new LinkedHashMap<String, List<String>>();
        
        String sql = "SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, DATA_TYPE " +
                     "FROM INFORMATION_SCHEMA.COLUMNS " +
                     "WHERE TABLE_SCHEMA = 'ais' " +
                     "ORDER BY TABLE_NAME, ORDINAL_POSITION";

        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;

        try {
            conn = getConnection();
            stmt = conn.createStatement();
            rs = stmt.executeQuery(sql);

            while (rs.next()) {
                String table = rs.getString("TABLE_SCHEMA") 
                               + "." + rs.getString("TABLE_NAME");
                String col   = rs.getString("COLUMN_NAME") 
                               + " (" + rs.getString("DATA_TYPE") + ")";

                if (!schema.containsKey(table)) {
                    schema.put(table, new ArrayList<String>());
                }
                schema.get(table).add(col);
            }

        } catch (SQLException e) {
            log.error("Schema introspect error: {}", e.getMessage());
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignored) {}
            try { if (stmt != null) stmt.close(); } catch (Exception ignored) {}
            try { if (conn != null) conn.close(); } catch (Exception ignored) {}
        }

        return schema;
    }
    
 // ── Search by name ───────────────────────────────────────────────
    public List<Map<String, Object>> searchByName(String locName, String location) {
        log.info("Search by name: locName={} location={}", locName, location);

        return executeLocationQuery(
            new LocationQuery()
                .table("ais.A_GENERAL_INFO", "g")
                .select("g.LOC_CD", "g.LOC_NAME", "g.ADDRESS")
                .where("g.LOC_NAME LIKE ?", "%" + locName + "%")
                .location(location)
                .orderBy("LOC_NAME")
                .limit(10)
        );
    }

    // Keep old signature for backward compatibility
    public List<Map<String, Object>> searchByName(String locName) {
        return searchByName(locName, null);
    }
    
    // ── Get all related reports for a location ────────────────────
    public Map<String, Object> getFullLocationInfo(String locCd) {
        Map<String, Object> fullInfo = new LinkedHashMap<String, Object>();

        Map<String, Object> general = getGeneralInfo(locCd);
        fullInfo.put("general", general);

        if (general.containsKey("error")) {
            return fullInfo;
        }
        
        // ── Detect slope location ────────────────────────────────────
        String locName = general.get("LOC_NAME") != null 
                       ? general.get("LOC_NAME").toString() : "";
        boolean isSlope = isSlopeLocation(locName);
        fullInfo.put("isSlope", isSlope);
        
        if (isSlope) {
            // ── SLOPE: only slope-specific reports ───────────────────
            log.info("🏔️ Slope location detected: {} ({})", locName, locCd);
            
            Map<String, Object> slopeData = getSlopeReports(locCd);
            fullInfo.put("slopeReports", slopeData.get("slopeReports"));
            fullInfo.put("tmcpForms",    slopeData.get("tmcpForms"));
            
            // ⚠️ NO standard reports for slopes
            
        } else {
            // ── NON-SLOPE: standard reports only ─────────────────────
            List<Map<String, Object>> reports = new ArrayList<Map<String, Object>>();
            reports.add(getReport(locCd, "Building Safety Inspection (BSIR)",      "ais.BSI_GENERAL_INFO",            "BLDG_SAFETY_INSP_REPORT_NO", "BSI"));
            reports.add(getReport(locCd, "Condition Survey by Contractor (CSR)",   "ais.CS_PLAN",                     "FILE_PATH_AUTOCAD",          "CSR"));
            reports.add(getReport(locCd, "Key Asset Information (KAI)",            "ais.KAI_RECORD_PLANS_AND_DRAWINGS","AUTOCAD_PATH",              "KAI"));
            reports.add(getReport(locCd, "EMMS",                                   "ais.OLD_EMMS",                    "REPORT_LINK",                "EMMS"));
            reports.add(getReport(locCd, "Detailed Survey Summary (DSSR)",         "ais.DSSR_REPORT",                 "REPORT_NO",                  "DSSR"));
            
            fullInfo.put("reports", reports);
        }
        
        return fullInfo;
    }
    
 // ── Build report URL based on report type ───────────────────────
    public String buildReportUrl(String reportType, String locCd, String reportId) {
        if (reportId == null || reportId.trim().isEmpty()) return null;
        
        switch (reportType) {
            
            // BSI: "AIS/BSI/bsiReport.jsp?reportno=" + BLDGSAFETY_INSP_REPORT_NO
            case "BSI": {
                String serverUrl = "domain"; //hardcode report server base URL
                return serverUrl + "AIS/BSI/bsiReport.jsp?reportno=" + reportId.trim();
            }
            
            // CSR: "AIS_SP/SPServlet?name=downloadFile&path=attachments/CS" + FILE_PATH_AUTOCAD
            case "CSR": {
                String serverUrl = "domain"; //hardcode report server base URL
                return serverUrl + "AIS_SP/SPServlet?name=downloadFile&path=attachments/CS"
                       + reportId.trim();
            }
            
            // KAI: Server_URL + "ReportGetFileServlet?reportType=KAI&mode=attachment
            //      &locCd=" + locCd + "&filename=" + AUTOCAD_PATH
            case "KAI": {
                String serverUrl = "domain"; //hardcode report server base URL
                return serverUrl + "ReportGetFileServlet"
                       + "?reportType=KAI"
                       + "&mode=attachment"
                       + "&locCd=" + locCd.trim()
                       + "&filename=" + reportId.trim();
            }
            
            // EMMS: REPORT_LINK is the full URL itself
            case "EMMS": {
                // The field IS the link - return as-is (but validate it's a URL)
                String link = reportId.trim();
                if (link.startsWith("http://") || link.startsWith("https://")) {
                    return link;
                }
                // If it's a relative path, prepend a base (adjust as needed)
                return link;
            }
            
            // DSSR: server_URL + locCd + "AAA" + code
            //       code = yyyy * mm * dd * 3 + 123456
            //       REPORT_NO format assumed: YYYYMMDD embedded or separate date field
            case "DSSR": {
                String serverUrl = "http://domain/asdiis/sebiis/2k/application/dssr/reportmain.aspx"
                                 + "?locationcode="; //hardcode DSSR report URL base
                long code = computeDssrCode(reportId);
                if (code < 0) {
                    // Could not compute - return base URL with just locCd
                    return serverUrl + locCd.trim();
                }
                return serverUrl + locCd.trim() + "AAA" + code;
            }
            
            default:
                log.warn("Unknown report type for URL building: {}", reportType);
                return null;
        }
    }

    // ── DSSR code formula: yyyy * mm * dd * 3 + 123456 ──────────────
    // REPORT_NO is assumed to contain or be a date string YYYYMMDD
    // Adjust parsing if your REPORT_NO has a different format
    private long computeDssrCode(String reportNo) {
        try {
            // Try: reportNo itself is or contains YYYYMMDD
            // e.g., "20230815" or "DSSR-20230815-001" → extract 8 digits
            String digits = reportNo.replaceAll("[^0-9]", "");
            
            if (digits.length() >= 8) {
                // Take first 8 digits as YYYYMMDD
                String datePart = digits.substring(0, 8);
                int yyyy = Integer.parseInt(datePart.substring(0, 4));
                int mm   = Integer.parseInt(datePart.substring(4, 6));
                int dd   = Integer.parseInt(datePart.substring(6, 8));
                
                // Validate ranges
                if (yyyy < 1900 || yyyy > 2100) return -1;
                if (mm   < 1    || mm   > 12)   return -1;
                if (dd   < 1    || dd   > 31)   return -1;
                
                long code = (long) yyyy * mm * dd * 3 + 123456;
                log.info("DSSR code computed: {} * {} * {} * 3 + 123456 = {}",
                         yyyy, mm, dd, code);
                return code;
            }
            
            log.warn("Cannot extract date from REPORT_NO: {}", reportNo);
            return -1;
            
        } catch (Exception e) {
            log.error("DSSR code compute error for '{}': {}", reportNo, e.getMessage());
            return -1;
        }
    }
    
 // ── Generic helper to fetch any single report ───────────────────
    private Map<String, Object> getReport(String locCd, String reportName,
                                           String tableName, String idColumn,
                                           String reportType) {   // ← add reportType
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("name",       reportName);
        report.put("table",      tableName);
        report.put("reportType", reportType);

        // Try MOD_TIME for ordering; some tables may not have it
        String sql = "SELECT TOP 1 " + idColumn + ", * FROM " + tableName
                   + " WHERE LOC_CD = ?";

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            conn = getConnection();
            ps   = conn.prepareStatement(sql);
            ps.setString(1, locCd);
            rs = ps.executeQuery();

            if (rs.next()) {
                String reportId = rs.getString(idColumn);
                report.put("exists",   true);
                report.put("reportId", reportId);

                // ── Build the URL right here in Java ──────────────────
                String url = buildReportUrl(reportType, locCd, reportId);
                report.put("url", url);   // null if not computable

                // Key metadata fields
                Map<String, Object> details = new LinkedHashMap<String, Object>();
                ResultSetMetaData meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                for (int i = 1; i <= cols; i++) {
                    String colName = meta.getColumnName(i);
                    if (colName.equals("MOD_TIME")    ||
                        colName.equals("CREATE_TIME")  ||
                        colName.equals("STATUS")       ||
                        colName.equals("TITLE")) {
                        Object value = rs.getObject(i);
                        if (value != null) {
                            details.put(colName, value);
                        }
                    }
                }
                report.put("details", details);

            } else {
                report.put("exists",   false);
                report.put("reportId", null);
                report.put("url",      null);
            }

        } catch (SQLException e) {
            log.error("Report fetch error for {}: {}", tableName, e.getMessage());
            report.put("exists", false);
            report.put("error",  e.getMessage());
            report.put("url",    null);
        } finally {
            try { if (rs   != null) rs.close();   } catch (Exception ignored) {}
            try { if (ps   != null) ps.close();   } catch (Exception ignored) {}
            try { if (conn != null) conn.close(); } catch (Exception ignored) {}
        }

        return report;
    }
    
    private final Map<String, String> orderColumnCache = 
    	    new HashMap<String, String>();

    	// ── Find an ordering column that actually exists in the table ────
    	private synchronized String getOrderColumn(String tableName) {
    	    // Return cached value if we've already looked this up
    	    if (orderColumnCache.containsKey(tableName)) {
    	        return orderColumnCache.get(tableName);
    	    }
    	    
    	    String result = findOrderColumn(tableName);
    	    orderColumnCache.put(tableName, result);
    	    return result;
    	}

    	private String findOrderColumn(String tableName) {
    	    // Try these in order of preference
    	    String[] candidates = {
    	        "MOD_TIME", "MODIFY_TIME", "MODIFIED_TIME", "LAST_MODIFIED",
    	        "UPDATE_TIME", "UPDATED_AT", "MOD_DATE",
    	        "CREATE_TIME", "CREATED_TIME", "CREATE_DATE", "CREATED_AT",
    	        "REPORT_DATE", "INSP_DATE", "DATE_CREATED"
    	    };

    	    Connection conn = null;
    	    PreparedStatement ps = null;
    	    ResultSet rs = null;

    	    try {
    	        conn = getConnection();
    	        
    	        // Parse "ais.BSI_GENERAL_INFO" → schema=ais, table=BSI_GENERAL_INFO
    	        String schema = "ais";
    	        String table  = tableName;
    	        if (tableName.contains(".")) {
    	            String[] parts = tableName.split("\\.", 2);
    	            schema = parts[0];
    	            table  = parts[1];
    	        }

    	        ps = conn.prepareStatement(
    	            "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
    	            "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?"
    	        );
    	        ps.setString(1, schema);
    	        ps.setString(2, table);
    	        rs = ps.executeQuery();

    	        Set<String> existingCols = new HashSet<String>();
    	        while (rs.next()) {
    	            existingCols.add(rs.getString("COLUMN_NAME").toUpperCase());
    	        }

    	        for (String candidate : candidates) {
    	            if (existingCols.contains(candidate)) {
    	                log.info("Table {} → using ORDER BY {}", tableName, candidate);
    	                return candidate;
    	            }
    	        }

    	        log.warn("Table {} has no recognized timestamp column. Columns: {}",
    	                 tableName, existingCols);
    	        return null;

    	    } catch (SQLException e) {
    	        log.error("findOrderColumn error for {}: {}", tableName, e.getMessage());
    	        return null;
    	    } finally {
    	        try { if (rs   != null) rs.close();   } catch (Exception ignored) {}
    	        try { if (ps   != null) ps.close();   } catch (Exception ignored) {}
    	        try { if (conn != null) conn.close(); } catch (Exception ignored) {}
    	    }
    	}
	 // ── Check which locations have a specific report type ──────────
	 // reportType: "BSI", "CSR", "KAI", "EMMS", or "DSSR"
	 // locCds: list of location codes to check
    	public Map<String, Object> checkReportsForLocations(
    	        String reportType, List<String> locCds) {
    	    
    	    Map<String, Object> result = new LinkedHashMap<String, Object>();
    	    result.put("reportType", reportType);
    	    result.put("totalChecked", locCds.size());
    	    
    	    // ── Verify report type via registry ────────────────────────
    	    ReportTypeRegistry.ReportType rt = 
    	        ReportTypeRegistry.getByShortName(reportType);
    	    if (rt == null) {
    	        result.put("error", "Unknown report type: " + reportType
    	            + ". Use: " + ReportTypeRegistry.getAllShortNames());
    	        return result;
    	    }
    	    result.put("reportName", rt.fullName);
    	    
    	    // ── Map report type to table + column ──────────────────────
    	    String tableName;
    	    String idColumn;
    	    
    	    switch (reportType.toUpperCase()) {
    	        case "BSI":
    	            tableName = "ais.BSI_GENERAL_INFO";
    	            idColumn  = "BLDG_SAFETY_INSP_REPORT_NO";
    	            break;
    	        case "CSR":
    	            tableName = "ais.CS_PLAN";
    	            idColumn  = "FILE_PATH_AUTOCAD";
    	            break;
    	        case "KAI":
    	            tableName = "ais.KAI_RECORD_PLANS_AND_DRAWINGS";
    	            idColumn  = "AUTOCAD_PATH";
    	            break;
    	        case "EMMS":
    	            tableName = "ais.OLD_EMMS";
    	            idColumn  = "REPORT_LINK";
    	            break;
    	        case "DSSR":
    	            tableName = "ais.DSSR_REPORT";
    	            idColumn  = "REPORT_NO";
    	            break;
    	        default:
    	            result.put("error", "Table mapping missing for: " + reportType);
    	            return result;
    	    }
	     
	     
	     if (locCds == null || locCds.isEmpty()) {
	         result.put("error", "No location codes provided");
	         return result;
	     }
	     
	     // ── Build IN clause with placeholders ──────────────────────
	     StringBuilder placeholders = new StringBuilder();
	     for (int i = 0; i < locCds.size(); i++) {
	         if (i > 0) placeholders.append(",");
	         placeholders.append("?");
	     }
	     
	     // Get one row per LOC_CD that has the report
	     String orderCol = getOrderColumn(tableName);
	     String sql;
	     if (orderCol != null) {
	         // Latest report per location
	         sql = "SELECT t.LOC_CD, t." + idColumn + " " +
	               "FROM " + tableName + " t " +
	               "INNER JOIN (" +
	               "  SELECT LOC_CD, MAX(" + orderCol + ") AS max_time " +
	               "  FROM " + tableName + " " +
	               "  WHERE LOC_CD IN (" + placeholders + ") " +
	               "  GROUP BY LOC_CD" +
	               ") latest " +
	               "ON t.LOC_CD = latest.LOC_CD " +
	               "AND t." + orderCol + " = latest.max_time";
	     } else {
	         // No timestamp column → just pick any matching row
	         sql = "SELECT LOC_CD, " + idColumn + " AS rid " +
	               "FROM (" +
	               "  SELECT LOC_CD, " + idColumn + ", " +
	               "    ROW_NUMBER() OVER (PARTITION BY LOC_CD ORDER BY LOC_CD) AS rn " +
	               "  FROM " + tableName + " " +
	               "  WHERE LOC_CD IN (" + placeholders + ")" +
	               ") sub WHERE rn = 1";
	     }
	     
	     Connection conn = null;
	     PreparedStatement ps = null;
	     ResultSet rs = null;
	     
	     // Track which codes have reports
	     Map<String, String> foundReports = new LinkedHashMap<String, String>();
	     
	     try {
	         conn = getConnection();
	         ps = conn.prepareStatement(sql);
	         for (int i = 0; i < locCds.size(); i++) {
	             ps.setString(i + 1, locCds.get(i));
	         }
	         rs = ps.executeQuery();
	         
	         while (rs.next()) {
	             String code   = rs.getString("LOC_CD");
	             String repId  = rs.getString(2);  // second column = the ID
	             if (code != null && repId != null) {
	                 foundReports.put(code.trim(), repId.trim());
	             }
	         }
	         
	     } catch (SQLException e) {
	         log.error("Bulk report check failed for {}: {}", 
	                   tableName, e.getMessage());
	         result.put("error", e.getMessage());
	         return result;
	     } finally {
	         try { if (rs   != null) rs.close();   } catch (Exception ignored) {}
	         try { if (ps   != null) ps.close();   } catch (Exception ignored) {}
	         try { if (conn != null) conn.close(); } catch (Exception ignored) {}
	     }
	     
	     // ── Get location names for nice display ────────────────────
	     Map<String, String> locNames = getLocationNames(locCds);
	     
	     // ── Build response ─────────────────────────────────────────
	     List<Map<String, Object>> withReport    = new ArrayList<Map<String, Object>>();
	     List<Map<String, Object>> withoutReport = new ArrayList<Map<String, Object>>();
	     
	     for (String code : locCds) {
	         Map<String, Object> entry = new LinkedHashMap<String, Object>();
	         entry.put("LOC_CD",   code);
	         entry.put("LOC_NAME", locNames.getOrDefault(code, ""));
	         
	         if (foundReports.containsKey(code)) {
	             String reportId = foundReports.get(code);
	             String url      = buildReportUrl(reportType, code, reportId);
	             entry.put("reportId", reportId);
	             entry.put("url",      url);
	             withReport.add(entry);
	         } else {
	             withoutReport.add(entry);
	         }
	     }
	     
	     result.put("withReport",       withReport);
	     result.put("withoutReport",    withoutReport);
	     result.put("withReportCount",  withReport.size());
	     
	     log.info("Report check: {} of {} locations have {} report", 
	              withReport.size(), locCds.size(), reportType);
	     
	     return result;
	 }
	
	 // ── Helper: Get LOC_NAME for a batch of codes ──────────────────
	 private Map<String, String> getLocationNames(List<String> locCds) {
	     Map<String, String> names = new HashMap<String, String>();
	     if (locCds == null || locCds.isEmpty()) return names;
	     
	     StringBuilder placeholders = new StringBuilder();
	     for (int i = 0; i < locCds.size(); i++) {
	         if (i > 0) placeholders.append(",");
	         placeholders.append("?");
	     }
	     
	     String sql = "SELECT LOC_CD, LOC_NAME FROM ais.A_GENERAL_INFO " +
	                  "WHERE LOC_CD IN (" + placeholders + ")";
	     
	     Connection conn = null;
	     PreparedStatement ps = null;
	     ResultSet rs = null;
	     
	     try {
	         conn = getConnection();
	         ps = conn.prepareStatement(sql);
	         for (int i = 0; i < locCds.size(); i++) {
	             ps.setString(i + 1, locCds.get(i));
	         }
	         rs = ps.executeQuery();
	         while (rs.next()) {
	             names.put(rs.getString("LOC_CD").trim(), 
	                       rs.getString("LOC_NAME"));
	         }
	     } catch (SQLException e) {
	         log.error("getLocationNames error: {}", e.getMessage());
	     } finally {
	         try { if (rs   != null) rs.close();   } catch (Exception ignored) {}
	         try { if (ps   != null) ps.close();   } catch (Exception ignored) {}
	         try { if (conn != null) conn.close(); } catch (Exception ignored) {}
	     }
	     return names;
	 }
	 
	 //HELPER FOR SLOPES
	// ── Check if location is a slope (by name) ──────────────────────
	 public boolean isSlopeLocation(String locName) {
	     return locName != null && locName.toUpperCase().contains("SLOPE");
	 }

	 // ── Get all slope reports for a location ────────────────────────
	 public Map<String, Object> getSlopeReports(String locCd) {
	     Map<String, Object> result = new LinkedHashMap<String, Object>();
	     
	     // Section 1: Slope_Report_Info (grouped by URL pattern)
	     result.put("slopeReports", getSlopeReportInfo(locCd));
	     
	     // Section 2: TMCP Forms (4 tables)
	     result.put("tmcpForms", getTmcpForms(locCd));
	     
	     return result;
	 }

	 // ── Slope_Report_Info: detect type from URL FileID ──────────────
	 private Map<String, List<Map<String, String>>> getSlopeReportInfo(String locCd) {
	     
	     // Initialize all known types with empty lists
	     Map<String, List<Map<String, String>>> grouped = 
	         new LinkedHashMap<String, List<Map<String, String>>>();
	     grouped.put("BWCS", new ArrayList<Map<String, String>>());
	     grouped.put("VMI",  new ArrayList<Map<String, String>>());
	     grouped.put("RMI",  new ArrayList<Map<String, String>>());
	     grouped.put("AMI",  new ArrayList<Map<String, String>>());
	     grouped.put("OTHER", new ArrayList<Map<String, String>>());
	     
	     String sql = "SELECT REPORT_LINK FROM ais.Slope_Report_Info WHERE LOC_CD = ?";
	     
	     Connection conn = null;
	     PreparedStatement ps = null;
	     ResultSet rs = null;
	     
	     try {
	         conn = getConnection();
	         ps = conn.prepareStatement(sql);
	         ps.setString(1, locCd);
	         rs = ps.executeQuery();
	         
	         while (rs.next()) {
	             String link = rs.getString("REPORT_LINK");
	             if (link == null || link.trim().isEmpty()) continue;
	             link = link.trim();
	             
	             String type    = detectSlopeReportType(link);
	             String fileId  = extractFileId(link);
	             
	             Map<String, String> entry = new LinkedHashMap<String, String>();
	             entry.put("url",    link);
	             entry.put("fileId", fileId);
	             entry.put("label",  fileId != null ? fileId : "Report");
	             
	             grouped.get(type).add(entry);
	         }
	         
	     } catch (SQLException e) {
	         log.error("Slope_Report_Info query error: {}", e.getMessage());
	     } finally {
	         try { if (rs   != null) rs.close();   } catch (Exception ignored) {}
	         try { if (ps   != null) ps.close();   } catch (Exception ignored) {}
	         try { if (conn != null) conn.close(); } catch (Exception ignored) {}
	     }
	     
	     // Remove empty categories (clean output)
	     Iterator<Map.Entry<String, List<Map<String, String>>>> it = 
	         grouped.entrySet().iterator();
	     while (it.hasNext()) {
	         if (it.next().getValue().isEmpty()) it.remove();
	     }
	     
	     return grouped;
	 }

	 // ── Detect type from FileID pattern: TCB929-BWCS-... → "BWCS" ───
	 private String detectSlopeReportType(String url) {
	     if (url == null) return "OTHER";
	     String upper = url.toUpperCase();
	     
	     // Look for "-TYPE-" pattern in URL
	     if (upper.contains("BWCS")) return "BWCS";
	     if (upper.contains("VMI"))  return "VMI";
	     if (upper.contains("RMI"))  return "RMI";
	     if (upper.contains("AMI"))  return "AMI";
	     
	     return "OTHER";
	 }

	 // ── Extract FileID from URL ──────────────────────────────────────
	 private String extractFileId(String url) {
	     if (url == null) return null;
	     int idx = url.toLowerCase().indexOf("fileid=");
	     if (idx < 0) return null;
	     String fileId = url.substring(idx + 7);
	     // Strip any trailing query params
	     int amp = fileId.indexOf('&');
	     if (amp > 0) fileId = fileId.substring(0, amp);
	     return fileId.trim();
	 }

	 // ── Get TMCP Form data from all 4 tables ────────────────────────
	// ── Get TMCP Forms grouped by Form 1 / Form 2 ───────────────────
	// ── Get all TMCP + TMIS forms grouped by Form 1 / Form 2 ────────
	 private Map<String, List<Map<String, Object>>> getTmcpForms(String locCd) {
	     Map<String, List<Map<String, Object>>> grouped = 
	         new LinkedHashMap<String, List<Map<String, Object>>>();
	     
	     grouped.put("Form 1", new ArrayList<Map<String, Object>>());
	     grouped.put("Form 2", new ArrayList<Map<String, Object>>());
	     
	     // ── Table configuration ─────────────────────────────────────
	     // {tableName, formGroup, formType, dateColumn, urlBuilder}
	     String[][] tableConfigs = {
	         // TMCP tables (use INSP_DATE)
	         {"ais.TMCP_FORM_ONE_LINK",     "Form 1", "Form1", "INSP_DATE",     "TMCP_OLD"},
	         {"ais.TMCP_FORM_ONE_NEW_LINK", "Form 1", "Form1", "INSP_DATE",     "TMCP_NEW"},
	         {"ais.TMCP_FORM_TWO_LINK",     "Form 2", "Form2", "INSP_DATE",     "TMCP_OLD"},
	         // TMIS tables (use APPROVED_DATE)
	         {"ais.TMIS_FORM_ONE_LINK",     "Form 1", "Form1", "APPROVED_DATE", "TMIS"},
	         {"ais.TMIS_FORM_TWO_LINK",     "Form 2", "Form2", "APPROVED_DATE", "TMIS"}
	     };
	     
	     for (String[] cfg : tableConfigs) {
	         String tableName  = cfg[0];
	         String formGroup  = cfg[1];
	         String formType   = cfg[2];
	         String dateColumn = cfg[3];
	         String urlSource  = cfg[4];
	         
	         List<Map<String, Object>> rows = fetchFormTable(
	             tableName, dateColumn, locCd, formType, urlSource
	         );
	         grouped.get(formGroup).addAll(rows);
	     }
	     
	     // Sort each group by date DESC (newest first)
	     for (List<Map<String, Object>> list : grouped.values()) {
	         list.sort(new Comparator<Map<String, Object>>() {
	             @Override
	             public int compare(Map<String, Object> a, Map<String, Object> b) {
	                 String dateA = (String) a.get("inspDate");
	                 String dateB = (String) b.get("inspDate");
	                 if (dateA == null) dateA = "";
	                 if (dateB == null) dateB = "";
	                 return dateB.compareTo(dateA);
	             }
	         });
	     }
	     
	     // Remove empty groups
	     Iterator<Map.Entry<String, List<Map<String, Object>>>> it = 
	         grouped.entrySet().iterator();
	     while (it.hasNext()) {
	         if (it.next().getValue().isEmpty()) it.remove();
	     }
	     
	     return grouped;
	 }

	 // ── Generic fetcher for one form table ──────────────────────────
	 private List<Map<String, Object>> fetchFormTable(
	         String tableName, String dateColumn, String locCd,
	         String formType, String urlSource) {
	     
	     List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
	     
	     String sql = "SELECT REPORT_LINK, " + dateColumn + " AS form_date "
	                + "FROM " + tableName + " WHERE LOC_CD = ? "
	                + "ORDER BY " + dateColumn + " DESC";
	     
	     Connection conn = null;
	     PreparedStatement ps = null;
	     ResultSet rs = null;
	     
	     try {
	         conn = getConnection();
	         ps = conn.prepareStatement(sql);
	         ps.setString(1, locCd);
	         rs = ps.executeQuery();
	         
	         while (rs.next()) {
	             String rawLink = rs.getString("REPORT_LINK");
	             Object dateObj = rs.getObject("form_date");
	             
	             if (rawLink == null || rawLink.trim().isEmpty()) continue;
	             
	             // ── Build the actual clickable URL ──────────────────
	             String url = buildFormUrl(rawLink.trim(), formType, urlSource);
	             if (url == null) continue;
	             
	             Map<String, Object> row = new LinkedHashMap<String, Object>();
	             row.put("url",       url);
	             row.put("inspDate",  formatInspDate(dateObj));
	             row.put("rawLink",   rawLink.trim());
	             row.put("source",    urlSource);  // for debugging
	             rows.add(row);
	         }
	         
	     } catch (SQLException e) {
	         log.error("Form query error for {}: {}", tableName, e.getMessage());
	     } finally {
	         try { if (rs   != null) rs.close();   } catch (Exception ignored) {}
	         try { if (ps   != null) ps.close();   } catch (Exception ignored) {}
	         try { if (conn != null) conn.close(); } catch (Exception ignored) {}
	     }
	     
	     return rows;
	 }

	 // ── Build form URL based on source table type ───────────────────
	 private String buildFormUrl(String rawLink, String formType, String urlSource) {
	     if (rawLink == null || rawLink.trim().isEmpty()) return null;
	     rawLink = rawLink.trim();
	     
	     // If it's already a full URL, return as-is
	     if (rawLink.startsWith("http://") || rawLink.startsWith("https://")) {
	         return rawLink;
	     }
	     
	     switch (urlSource) {
	         
	         // ── TMIS: \attachment\Form1\SA0852_20190722.pdf ─────────
	         //   → "domain"/AIS/ReportGetFileServlet
	         //     ?reportType=Form1&filename=SA0852_20190722
	         case "TMIS": {
	             String filename = extractTmisFilename(rawLink);
	             if (filename == null) {
	                 log.warn("Cannot extract filename from TMIS link: {}", rawLink);
	                 return null;
	             }
	             return "domain/AIS/ReportGetFileServlet"
	                  + "?reportType=" + formType
	                  + "&filename=" + filename;
	         }
	         
	         // ── TMCP_OLD: similar AIS format ────────────────────────
	         case "TMCP_OLD": {
	             // If stored as a filename, use AIS servlet
	             String filename = extractTmisFilename(rawLink);
	             if (filename != null) {
	                 return "domain/AIS/ReportGetFileServlet"
	                      + "?reportType=" + formType
	                      + "&filename=" + filename;
	             }
	             // Fallback: treat as raw filename
	             return "domain/AIS/ReportGetFileServlet"
	                  + "?reportType=" + formType
	                  + "&filename=" + rawLink;
	         }
	         
	         // ── TMCP_NEW: GUID-style id ─────────────────────────────
	         //   → http://www.tcb929.com/ASD_Slope/AIS/downloadreport.aspx
	         //     ?Worktype=Form1&id=7842BA8F-568F-4520-980F-FA5E7A950509
	         case "TMCP_NEW": {
	             return "http://www.tcb929.com/ASD_Slope/AIS/downloadreport.aspx"
	                  + "?Worktype=" + formType
	                  + "&id=" + rawLink;
	         }
	         
	         default:
	             log.warn("Unknown urlSource: {}", urlSource);
	             return null;
	     }
	 }

	 // ── Extract filename from path: ──────────────────────────────────
	 //   "\attachment\Form1\SA0852_20190722.pdf" → "SA0852_20190722"
	 //   "\attachment\Form2\SA0852_TS002_20190816.pdf" → "SA0852_TS002_20190816"
	 private String extractTmisFilename(String rawLink) {
	     if (rawLink == null) return null;
	     
	     String s = rawLink.replace("\\", "/").trim();
	     
	     // Get the last segment after / 
	     int lastSlash = s.lastIndexOf('/');
	     if (lastSlash >= 0) {
	         s = s.substring(lastSlash + 1);
	     }
	     
	     // Strip .pdf extension (case-insensitive)
	     if (s.toLowerCase().endsWith(".pdf")) {
	         s = s.substring(0, s.length() - 4);
	     }
	     
	     return s.isEmpty() ? null : s;
	 }

	 // ── Format INSP_DATE nicely ─────────────────────────────────────
	 private String formatInspDate(Object dateObj) {
	     if (dateObj == null) return "";
	     
	     try {
	         if (dateObj instanceof java.sql.Date) {
	             return new java.text.SimpleDateFormat("yyyy-MM-dd")
	                 .format((java.sql.Date) dateObj);
	         }
	         if (dateObj instanceof java.sql.Timestamp) {
	             return new java.text.SimpleDateFormat("yyyy-MM-dd")
	                 .format((java.sql.Timestamp) dateObj);
	         }
	         if (dateObj instanceof java.util.Date) {
	             return new java.text.SimpleDateFormat("yyyy-MM-dd")
	                 .format((java.util.Date) dateObj);
	         }
	     } catch (Exception ignored) {}
	     
	     return dateObj.toString();
	 }
	 
	 //UNIQUE PSM
	// ── Get list of distinct PSMs with their location counts ─────────
	 public List<Map<String, Object>> getDistinctPsms() {
	     List<Map<String, Object>> psmList = new ArrayList<Map<String, Object>>();
	     
	     String sql = 
	         "SELECT LTRIM(RTRIM(PSM)) AS psm_name, COUNT(*) AS location_count " +
	         "FROM ais.A_GENERAL_INFO " +
	         "WHERE PSM IS NOT NULL AND LTRIM(RTRIM(PSM)) <> '' " +
	         "GROUP BY LTRIM(RTRIM(PSM)) " +
	         "ORDER BY location_count DESC, psm_name ASC";
	     
	     Connection conn = null;
	     PreparedStatement ps = null;
	     ResultSet rs = null;
	     
	     try {
	         conn = getConnection();
	         ps = conn.prepareStatement(sql);
	         rs = ps.executeQuery();
	         
	         while (rs.next()) {
	             Map<String, Object> row = new LinkedHashMap<String, Object>();
	             row.put("psm",   rs.getString("psm_name"));
	             row.put("count", rs.getInt("location_count"));
	             psmList.add(row);
	         }
	         
	         log.info("Loaded {} distinct PSM values", psmList.size());
	         
	     } catch (SQLException e) {
	         log.error("Distinct PSM query error: {}", e.getMessage());
	     } finally {
	         try { if (rs   != null) rs.close();   } catch (Exception ignored) {}
	         try { if (ps   != null) ps.close();   } catch (Exception ignored) {}
	         try { if (conn != null) conn.close(); } catch (Exception ignored) {}
	     }
	     
	     return psmList;
	 }

	 // ── Get locations under a specific PSM ──────────────────────────
	 public List<Map<String, Object>> getLocationsByPsm(String psm, String location) {
		    log.info("Locations by PSM: psm={} location={}", psm, location);

		    return executeLocationQuery(
		        new LocationQuery()
		            .table("ais.A_GENERAL_INFO", "g")
		            .select("g.LOC_CD", "g.LOC_NAME", "g.ADDRESS")
		            .where("LTRIM(RTRIM(g.PSM)) = LTRIM(RTRIM(?))", psm)
		            .location(location)
		            .orderBy("LOC_NAME")
		            .limit(50)
		    );
		}

		// Keep old signature
		public List<Map<String, Object>> getLocationsByPsm(String psm) {
		    return getLocationsByPsm(psm, null);
		}
	// ══════════════════════════════════════════════════════════════
	// LOCATIONS BY DEPARTMENT
	// ══════════════════════════════════════════════════════════════

	/**
	 * Get locations owned/used by a specific department code.
	 * Table: ais.A_GENERAL_INFO, column: DEPT_CD
	 */
		public List<Map<String, Object>> getLocationsByDept(String deptCd, String location) {
		    log.info("Locations by dept: deptCd={} location={}", deptCd, location);

		    return executeLocationQuery(
		        new LocationQuery()
		            .table("ais.A_GENERAL_INFO", "g")
		            .select("g.LOC_CD", "g.LOC_NAME", "g.ADDRESS", "g.DEPT_CD")
		            .where("UPPER(LTRIM(RTRIM(g.DEPT_CD))) = ?",
		                   deptCd.toUpperCase().trim())
		            .location(location)
		            .orderBy("LOC_NAME")
		            .limit(50)
		    );
		}

		// Keep old signature
		public List<Map<String, Object>> getLocationsByDept(String deptCd) {
		    return getLocationsByDept(deptCd, null);
		}
	// ══════════════════════════════════════════════════════════════
	// DECLARED MONUMENTS
	// ══════════════════════════════════════════════════════════════

	/**
	 * Search locations that are (or are not) declared monuments.
	 * Table: sde.T_ASD_COMBINED, column: DECLR_MONUMT
	 * @param filter "T" for monuments, "F" for non-monuments, "ALL" for both
	 */
	 public List<Map<String, Object>> getDeclaredMonuments(String filter, String location) {
		    String gisDb = AppConfig.GISdbName();
		    if (gisDb.isEmpty()) {
		        log.error("GIS DB NAME not configured");
		        return new ArrayList<Map<String, Object>>();
		    }

		    log.info("Declared monuments: filter={} location={}", filter, location);

		    LocationQuery q = new LocationQuery()
		        .table("ais.A_GENERAL_INFO", "g")
		        .gisJoin(gisDb + ".sde.T_ASD_COMBINED", "c")
		        .select("g.LOC_CD", "g.LOC_NAME", "g.ADDRESS", "c.DECLR_MONUMT")
		        .location(location)
		        .orderBy("LOC_NAME")
		        .limit(50);

		    // Override join — GIS is primary, general is secondary
		    // Swap: GIS table is the "base", general is the join
		    // Rebuild manually for this specific join direction
		    return executeDeclaredMonumentQuery(filter, location, gisDb);
		}

		// Keep old signature
		public List<Map<String, Object>> getDeclaredMonuments(String filter) {
		    return getDeclaredMonuments(filter, null);
		}
	// ══════════════════════════════════════════════════════════════
	// HISTORIC BUILDINGS
	// ══════════════════════════════════════════════════════════════

	/**
	 * Search locations that are graded historic buildings.
	 * Table: sde.T_ASD_COMBINED, column: GRD_HIST_BLDG
	 * @param grade "1", "2", "3", "ALL" for any grade, "0" or "NONE" for ungraded
	 */
		public List<Map<String, Object>> getHistoricBuildings(String grade, String location) {
		    String gisDb = AppConfig.GISdbName();
		    if (gisDb.isEmpty()) {
		        log.error("GIS DB NAME not configured");
		        return new ArrayList<Map<String, Object>>();
		    }

		    log.info("Historic buildings: grade={} location={}", grade, location);

		    return executeHistoricBuildingQuery(grade, location, gisDb);
		}

		// Keep old signature
		public List<Map<String, Object>> getHistoricBuildings(String grade) {
		    return getHistoricBuildings(grade, null);
		}
	// ══════════════════════════════════════════════════════════════
	// LOCATION CODE CHANGE HISTORY
	// ══════════════════════════════════════════════════════════════

	/**
	 * Look up location code change history.
	 * Table: ais.A_LOC_CD_CHANGE_HISTORY
	 * Columns: CURRENT_LOC_CD, FORMER_LOC_CD
	 *
	 * @param formerCd  former location code to search (find its current code)
	 * @param currentCd current location code to search (find its former codes)
	 */
	public List<Map<String, Object>> getLocCdChangeHistory(
	        String formerCd, String currentCd) {

	    List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();

	    // Build query based on which params are provided
	    StringBuilder sql = new StringBuilder();
	    sql.append("SELECT h.CURRENT_LOC_CD, h.FORMER_LOC_CD, ")
	       .append("g1.LOC_NAME AS CURRENT_LOC_NAME, ")
	       .append("g2.LOC_NAME AS FORMER_LOC_NAME ")
	       .append("FROM ais.A_LOC_CD_CHANGE_HISTORY h ")
	       .append("LEFT JOIN ais.A_GENERAL_INFO g1 ")
	       .append("  ON h.CURRENT_LOC_CD = g1.LOC_CD ")
	       .append("LEFT JOIN ais.A_GENERAL_INFO g2 ")
	       .append("  ON h.FORMER_LOC_CD = g2.LOC_CD ");

	    List<String> conditions = new ArrayList<String>();
	    List<String> params = new ArrayList<String>();

	    if (formerCd != null && !formerCd.isEmpty()) {
	        conditions.add("UPPER(h.FORMER_LOC_CD) = ?");
	        params.add(formerCd.toUpperCase().trim());
	    }
	    if (currentCd != null && !currentCd.isEmpty()) {
	        conditions.add("UPPER(h.CURRENT_LOC_CD) = ?");
	        params.add(currentCd.toUpperCase().trim());
	    }

	    if (conditions.isEmpty()) {
	        log.warn("getLocCdChangeHistory called with no parameters");
	        return results;
	    }

	    sql.append("WHERE ");
	    for (int i = 0; i < conditions.size(); i++) {
	        if (i > 0) sql.append(" OR ");
	        sql.append(conditions.get(i));
	    }
	    sql.append(" ORDER BY h.CURRENT_LOC_CD");

	    Connection conn = null;
	    PreparedStatement ps = null;
	    ResultSet rs = null;

	    try {
	        conn = getConnection();
	        ps = conn.prepareStatement(sql.toString());
	        for (int i = 0; i < params.size(); i++) {
	            ps.setString(i + 1, params.get(i));
	        }
	        rs = ps.executeQuery();

	        while (rs.next()) {
	            Map<String, Object> row = new LinkedHashMap<String, Object>();
	            row.put("CURRENT_LOC_CD",   rs.getString("CURRENT_LOC_CD"));
	            row.put("CURRENT_LOC_NAME", rs.getString("CURRENT_LOC_NAME"));
	            row.put("FORMER_LOC_CD",    rs.getString("FORMER_LOC_CD"));
	            row.put("FORMER_LOC_NAME",  rs.getString("FORMER_LOC_NAME"));
	            results.add(row);
	        }

	        log.info("Found {} code change history entries (former={}, current={})",
	                 results.size(), formerCd, currentCd);

	    } catch (SQLException e) {
	        log.error("Loc CD change history error: {}", e.getMessage());
	    } finally {
	        try { if (rs   != null) rs.close();   } catch (Exception ignored) {}
	        try { if (ps   != null) ps.close();   } catch (Exception ignored) {}
	        try { if (conn != null) conn.close(); } catch (Exception ignored) {}
	    }

	    return results;
	}
	
	// ══════════════════════════════════════════════════════════════
	// UNIVERSAL QUERY BUILDER
	// Centralises SQL construction for all location-based queries.
	// Eliminates copy-paste SQL across methods.
	// ══════════════════════════════════════════════════════════════

	/**
	 * Describes a search query — what table, what filter, what location.
	 * Builder pattern so callers only set what they need.
	 */
	public static class LocationQuery {

	    // ── Required ──────────────────────────────────────────────
	    public String baseTable;        // e.g., "ais.A_GENERAL_INFO"
	    public String tableAlias;       // e.g., "g"

	    // ── Optional: GIS join ────────────────────────────────────
	    public String gisTable;         // e.g., "GIS_DB.sde.T_ASD_COMBINED"
	    public String gisAlias;         // e.g., "c"
	    public String gisJoinColumn;    // e.g., "LOC_CD"

	    // ── SELECT columns ────────────────────────────────────────
	    public List<String> selectColumns = new ArrayList<String>();

	    // ── WHERE conditions (raw SQL fragment + param value) ─────
	    public List<String> conditions    = new ArrayList<String>();
	    public List<Object> params        = new ArrayList<Object>();

	    // ── Location filter (applied to LOC_NAME + ADDRESS) ───────
	    public String location;          // e.g., "Sha Tin" — null = no filter

	    // ── ORDER BY ──────────────────────────────────────────────
	    public String orderBy = "LOC_NAME";

	    // ── Pagination ────────────────────────────────────────────
	    public int limit = 50;

	    // ── Builder helpers ───────────────────────────────────────
	    public LocationQuery table(String t, String alias) {
	        this.baseTable  = t;
	        this.tableAlias = alias;
	        return this;
	    }

	    public LocationQuery gisJoin(String gisTable, String alias) {
	        this.gisTable      = gisTable;
	        this.gisAlias      = alias;
	        this.gisJoinColumn = "LOC_CD";
	        return this;
	    }

	    public LocationQuery select(String... cols) {
	        selectColumns.addAll(Arrays.asList(cols));
	        return this;
	    }

	    public LocationQuery where(String condition, Object param) {
	        conditions.add(condition);
	        params.add(param);
	        return this;
	    }

	    public LocationQuery whereRaw(String condition) {
	        conditions.add(condition);
	        return this;
	    }

	    public LocationQuery location(String loc) {
	        this.location = (loc != null && !loc.trim().isEmpty()) ? loc.trim() : null;
	        return this;
	    }

	    public LocationQuery orderBy(String col) {
	        this.orderBy = col;
	        return this;
	    }

	    public LocationQuery limit(int n) {
	        this.limit = n;
	        return this;
	    }
	}

	/**
	 * Executes a LocationQuery and returns rows as List<Map>.
	 * This is the SINGLE SQL execution point for all list queries.
	 */
	private List<Map<String, Object>> executeLocationQuery(LocationQuery q) {
	    List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();

	    // ── BUILD SELECT ──────────────────────────────────────────────
	    StringBuilder sql = new StringBuilder();
	    sql.append("SELECT TOP ").append(q.limit).append(" ");

	    if (q.selectColumns.isEmpty()) {
	        sql.append(q.tableAlias).append(".*");
	    } else {
	        sql.append(String.join(", ", q.selectColumns));
	    }

	    // ── FROM ──────────────────────────────────────────────────────
	    sql.append(" FROM ").append(q.baseTable)
	       .append(" ").append(q.tableAlias);

	    // ── GIS JOIN (optional) ───────────────────────────────────────
	    if (q.gisTable != null) {
	        sql.append(" LEFT JOIN ").append(q.gisTable)
	           .append(" ").append(q.gisAlias)
	           .append(" ON ").append(q.tableAlias).append(".").append(q.gisJoinColumn)
	           .append(" = ").append(q.gisAlias).append(".").append(q.gisJoinColumn);
	    }

	    // ── WHERE ─────────────────────────────────────────────────────
	    List<Object> allParams = new ArrayList<Object>(q.params);

	    // Static conditions
	    if (!q.conditions.isEmpty()) {
	        sql.append(" WHERE ");
	        sql.append(String.join(" AND ", q.conditions));
	    }

	    // ── Universal location filter ─────────────────────────────────
	    // Applied to BOTH LOC_NAME and ADDRESS — DB does the work
	    if (q.location != null) {
	        String locPattern = "%" + q.location.toUpperCase() + "%";
	        if (q.conditions.isEmpty()) {
	            sql.append(" WHERE ");
	        } else {
	            sql.append(" AND ");
	        }

	        // Try both with and without spaces: "SHA TIN" and "SHATIN"
	        String locNoSpace = "%" + q.location.toUpperCase().replace(" ", "") + "%";

	        sql.append("(")
	           .append("UPPER(").append(q.tableAlias).append(".LOC_NAME) LIKE ?")
	           .append(" OR UPPER(").append(q.tableAlias).append(".ADDRESS) LIKE ?")
	           .append(" OR UPPER(").append(q.tableAlias).append(".LOC_NAME) LIKE ?")
	           .append(" OR UPPER(").append(q.tableAlias).append(".ADDRESS) LIKE ?")
	           .append(")");

	        allParams.add(locPattern);
	        allParams.add(locPattern);
	        allParams.add(locNoSpace);
	        allParams.add(locNoSpace);

	        log.info("📍 Location filter applied: '{}' (also trying '{}')",
	                q.location, q.location.replace(" ", ""));
	    }

	    // ── ORDER BY ──────────────────────────────────────────────────
	    if (q.orderBy != null) {
	        sql.append(" ORDER BY ").append(q.tableAlias).append(".").append(q.orderBy);
	    }

	    log.debug("🔍 SQL: {}", sql);
	    log.debug("🔍 Params: {}", allParams);

	    // ── EXECUTE ───────────────────────────────────────────────────
	    Connection conn = null;
	    PreparedStatement ps = null;
	    ResultSet rs = null;

	    try {
	        conn = getConnection();
	        ps = conn.prepareStatement(sql.toString());

	        for (int i = 0; i < allParams.size(); i++) {
	            Object p = allParams.get(i);
	            if (p instanceof String) {
	                ps.setString(i + 1, (String) p);
	            } else if (p instanceof Integer) {
	                ps.setInt(i + 1, (Integer) p);
	            } else {
	                ps.setObject(i + 1, p);
	            }
	        }

	        rs = ps.executeQuery();
	        ResultSetMetaData meta = rs.getMetaData();
	        int cols = meta.getColumnCount();

	        while (rs.next()) {
	            Map<String, Object> row = new LinkedHashMap<String, Object>();
	            for (int i = 1; i <= cols; i++) {
	                row.put(meta.getColumnName(i), rs.getObject(i));
	            }
	            results.add(row);
	        }

	    } catch (SQLException e) {
	        log.error("executeLocationQuery error: {}", e.getMessage());
	        log.error("SQL was: {}", sql);
	    } finally {
	        closeQuietly(rs, ps, conn);
	    }

	    return results;
	}
	
	// ── Single close helper — eliminates 3-line finally blocks ───────
	private void closeQuietly(AutoCloseable... resources) {
	    for (AutoCloseable r : resources) {
	        if (r != null) {
	            try { r.close(); } catch (Exception ignored) {}
	        }
	    }
	}
	
	// ── Declared monuments (GIS-primary join) ────────────────────────
	private List<Map<String, Object>> executeDeclaredMonumentQuery(
	        String filter, String location, String gisDb) {

	    StringBuilder sql = new StringBuilder();
	    List<Object> params = new ArrayList<Object>();

	    sql.append("SELECT TOP 50 ")
	       .append("c.LOC_CD, g.LOC_NAME, g.ADDRESS, c.DECLR_MONUMT ")
	       .append("FROM ").append(gisDb).append(".sde.T_ASD_COMBINED c ")
	       .append("LEFT JOIN ais.A_GENERAL_INFO g ON c.LOC_CD = g.LOC_CD ")
	       .append("WHERE 1=1 ");

	    // Monument filter
	    if ("T".equals(filter)) {
	        sql.append("AND UPPER(c.DECLR_MONUMT) = 'T' ");
	    } else if ("F".equals(filter)) {
	        sql.append("AND (UPPER(c.DECLR_MONUMT) = 'F' OR c.DECLR_MONUMT IS NULL) ");
	    }

	    // Universal location filter
	    appendLocationFilter(sql, params, "g", location);

	    sql.append("ORDER BY g.LOC_NAME");

	    log.info("Found X declared monuments (filter={}, location={})", filter, location);

	    return executeRawQuery(sql.toString(), params);
	}

	// ── Historic buildings (GIS-primary join) ────────────────────────
	private List<Map<String, Object>> executeHistoricBuildingQuery(
	        String grade, String location, String gisDb) {

	    StringBuilder sql = new StringBuilder();
	    List<Object> params = new ArrayList<Object>();

	    sql.append("SELECT TOP 50 ")
	       .append("c.LOC_CD, g.LOC_NAME, g.ADDRESS, c.GRD_HIST_BLDG ")
	       .append("FROM ").append(gisDb).append(".sde.T_ASD_COMBINED c ")
	       .append("LEFT JOIN ais.A_GENERAL_INFO g ON c.LOC_CD = g.LOC_CD ")
	       .append("WHERE 1=1 ");

	    // Grade filter
	    if ("0".equals(grade) || "NONE".equals(grade)) {
	        sql.append("AND (c.GRD_HIST_BLDG = '0' OR c.GRD_HIST_BLDG IS NULL) ");
	    } else if ("ALL".equals(grade)) {
	        sql.append("AND c.GRD_HIST_BLDG IS NOT NULL ")
	           .append("AND c.GRD_HIST_BLDG <> '0' ")
	           .append("AND LTRIM(RTRIM(c.GRD_HIST_BLDG)) <> '' ");
	    } else if ("1".equals(grade) || "2".equals(grade) || "3".equals(grade)) {
	        sql.append("AND c.GRD_HIST_BLDG = ? ");
	        params.add(grade);
	    }

	    // Universal location filter
	    appendLocationFilter(sql, params, "g", location);

	    sql.append("ORDER BY g.LOC_NAME");

	    log.info("Found X historic buildings (grade={}, location={})", grade, location);

	    return executeRawQuery(sql.toString(), params);
	}

	// ── Append location filter to any SQL ────────────────────────────
	// Reusable for any query that has g.LOC_NAME and g.ADDRESS
	private void appendLocationFilter(StringBuilder sql,
	        List<Object> params,
	        String alias,
	        String location) {

	    if (location == null || location.trim().isEmpty()) return;

	    String locPattern  = "%" + location.trim().toUpperCase() + "%";
	    String locNoSpace  = "%" + location.trim().toUpperCase().replace(" ", "") + "%";

	    sql.append("AND (")
	       .append("UPPER(").append(alias).append(".LOC_NAME) LIKE ? ")
	       .append("OR UPPER(").append(alias).append(".ADDRESS) LIKE ? ")
	       .append("OR UPPER(").append(alias).append(".LOC_NAME) LIKE ? ")
	       .append("OR UPPER(").append(alias).append(".ADDRESS) LIKE ? ")
	       .append(") ");

	    params.add(locPattern);
	    params.add(locPattern);
	    params.add(locNoSpace);
	    params.add(locNoSpace);

	    log.info("📍 DB location filter: '{}' + no-space variant '{}'",
	            location, location.replace(" ", ""));
	}

	// ── Raw query executor (for GIS join queries) ────────────────────
	private List<Map<String, Object>> executeRawQuery(
	        String sql, List<Object> params) {

	    List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();

	    Connection conn = null;
	    PreparedStatement ps = null;
	    ResultSet rs = null;

	    try {
	        conn = getConnection();
	        ps = conn.prepareStatement(sql);

	        for (int i = 0; i < params.size(); i++) {
	            Object p = params.get(i);
	            if (p instanceof String) {
	                ps.setString(i + 1, (String) p);
	            } else {
	                ps.setObject(i + 1, p);
	            }
	        }

	        rs = ps.executeQuery();
	        ResultSetMetaData meta = rs.getMetaData();
	        int cols = meta.getColumnCount();

	        while (rs.next()) {
	            Map<String, Object> row = new LinkedHashMap<String, Object>();
	            for (int i = 1; i <= cols; i++) {
	                row.put(meta.getColumnName(i), rs.getObject(i));
	            }
	            results.add(row);
	        }

	    } catch (SQLException e) {
	        log.error("executeRawQuery error: {}", e.getMessage());
	        log.error("SQL: {}", sql);
	    } finally {
	        closeQuietly(rs, ps, conn);
	    }

	    return results;
	}
	
	/**
	 * Execute a raw SELECT query generated by the LLM.
	 * SAFETY: only SELECT statements are allowed.
	 * Returns results as List<Map> or an error map.
	 */
	public Map<String, Object> executeLlmGeneratedQuery(String sql) {
	    Map<String, Object> response = new LinkedHashMap<String, Object>();

	    if (sql == null || sql.trim().isEmpty()) {
	        response.put("error", "Empty SQL query");
	        return response;
	    }

	    // ── Safety check: only allow SELECT ──────────────────────────
	    String trimmed = sql.trim().toUpperCase();
	    String[] forbidden = {
	        "INSERT", "UPDATE", "DELETE", "DROP",
	        "TRUNCATE", "ALTER", "CREATE", "EXEC",
	        "EXECUTE", "MERGE", "GRANT", "REVOKE"
	    };
	    for (String keyword : forbidden) {
	        if (trimmed.startsWith(keyword)
	                || trimmed.contains(" " + keyword + " ")
	                || trimmed.contains(";" + keyword)) {
	            log.error("🚫 Blocked LLM SQL (contains {}): {}", keyword, sql);
	            response.put("error", "Only SELECT queries are allowed. "
	                    + "Blocked keyword: " + keyword);
	            return response;
	        }
	    }

	    if (!trimmed.startsWith("SELECT")) {
	        response.put("error", "Query must start with SELECT.");
	        return response;
	    }

	    // ── Add TOP limit if not present ─────────────────────────────
	    // Prevent runaway full-table scans
	    if (!trimmed.contains("TOP ") && !trimmed.contains("FETCH FIRST")) {
	        sql = sql.trim().replaceFirst("(?i)^SELECT",
	                "SELECT TOP 50");
	        log.info("⚠️ Added TOP 50 to LLM query for safety");
	    }

	    log.info("🤖 Executing LLM-generated SQL: {}", sql);

	    // ── Execute ───────────────────────────────────────────────────
	    Connection conn = null;
	    PreparedStatement ps = null;
	    ResultSet rs = null;

	    List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();

	    try {
	        conn = getConnection();
	        ps = conn.prepareStatement(sql);
	        ps.setQueryTimeout(10); // 10 second max — no runaway queries

	        rs = ps.executeQuery();
	        ResultSetMetaData meta = rs.getMetaData();
	        int cols = meta.getColumnCount();

	        while (rs.next()) {
	            Map<String, Object> row = new LinkedHashMap<String, Object>();
	            for (int i = 1; i <= cols; i++) {
	                row.put(meta.getColumnName(i), rs.getObject(i));
	            }
	            results.add(row);
	        }

	        response.put("count",   results.size());
	        response.put("results", results);
	        response.put("sql",     sql); // echo back for transparency
	        log.info("✅ LLM SQL returned {} rows", results.size());

	    } catch (SQLException e) {
	        log.error("❌ LLM SQL execution error: {}", e.getMessage());
	        response.put("error",  e.getMessage());
	        response.put("sql",    sql);
	    } finally {
	        closeQuietly(rs, ps, conn);
	    }

	    return response;
	}
}