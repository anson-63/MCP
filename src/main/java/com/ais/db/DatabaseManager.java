package com.ais.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.*;
import java.util.*;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ais.service.ReportTypeRegistry;
import com.ais.config.AppConfig;
import com.ais.security.AuthorizationContext;
import com.ais.mcp.query.QueryDimensionRegistry;
import com.ais.mcp.query.QueryPredicate;
import com.ais.mcp.query.LocationQuerySpec;
import com.ais.mcp.query.QueryDimensionRegistry.QueryDimension;

public class DatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);
    private static DatabaseManager instance;
    private final HikariDataSource dataSource;
    private final QueryDimensionRegistry queryDimensionRegistry;
    // ══════════════════════════════════════════════════════════════
    // SHARED LIMIT / UNDEFINED-FILTER CONSTANTS & HELPERS
    // ══════════════════════════════════════════════════════════════
    private static final int DEFAULT_LIST_LIMIT = 200;
    private static final int DEFAULT_NAME_SEARCH_LIMIT = 10;
    private static final int MAX_LIST_LIMIT = 500;
    private static final int MAX_LLM_RESULT_ROWS = 200;
    private static final Pattern LLM_TABLE_REFERENCE
            = Pattern.compile(
                    "(?i)\\b(?:FROM|JOIN)\\s+"
                    + "((?:\\[[^\\]]+\\]|[A-Za-z0-9_]+)"
                    + "(?:\\.(?:\\[[^\\]]+\\]|[A-Za-z0-9_]+)){0,2})"
            );
    private static final Pattern TOP_PATTERN
            = Pattern.compile("(?i)\\bTOP\\s*\\(?\\s*(\\d+)");
    private static final Set<String> BASE_ALLOWED_LLM_TABLES
            = Collections.unmodifiableSet(
                    new HashSet<String>(Arrays.asList(
                            "AIS.A_GENERAL_INFO",
                            "AIS.BSI_GENERAL_INFO",
                            "AIS.CS_PLAN",
                            "AIS.KAI_RECORD_PLANS_AND_DRAWINGS",
                            "AIS.OLD_EMMS",
                            "AIS.DSSR_REPORT",
                            "AIS.A_LOC_CD_CHANGE_HISTORY"
                    ))
            );

    /**
     * Clamps a caller/LLM/prompt-supplied limit into a safe range. Returns
     * defaultVal if limit is null. Never returns less than 1 or more than
     * MAX_LIST_LIMIT, so a hallucinated "top 999999999" (or a negative/zero
     * value) can never force a runaway query.
     */
    private int clampLimit(Integer limit, int defaultVal) {
        if (limit == null) {
            return defaultVal;
        }
        int v = limit;
        if (v < 1) {
            v = 1;
        }
        if (v > MAX_LIST_LIMIT) {
            log.warn("[Manual Info] Requested limit {} exceeds max {} — clamping", v, MAX_LIST_LIMIT);
            v = MAX_LIST_LIMIT;
        }
        return v;
    }
    /**
     * Maps a user-facing field name (as it might appear in a prompt, e.g.
     * "address", "name", "department") to the actual, validated database column
     * name. Column names can never be bound SQL parameters (JDBC only binds
     * VALUES, not identifiers), so any field the caller wants checked MUST be
     * resolved through this whitelist before being concatenated into SQL text —
     * never interpolate raw user/LLM text directly into a query.
     */
    private static final Map<String, String> UNDEFINED_CHECK_COLUMN_ALIASES;

    static {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("ADDRESS", "ADDRESS");
        m.put("ADDR", "ADDRESS");
        m.put("NAME", "LOC_NAME");
        m.put("LOCNAME", "LOC_NAME");
        m.put("LOC_NAME", "LOC_NAME");
        m.put("DEPARTMENT", "DEPT_CD");
        m.put("DEPT", "DEPT_CD");
        m.put("DEPTCD", "DEPT_CD");
        m.put("DEPT_CD", "DEPT_CD");
        m.put("DEPARTMENTDESC", "DEPT_DESC");
        m.put("DEPTDESC", "DEPT_DESC");
        m.put("DEPT_DESC", "DEPT_DESC");
        m.put("PSM", "PSM");
        UNDEFINED_CHECK_COLUMN_ALIASES = Collections.unmodifiableMap(m);
    }

    /**
     * Resolves a free-text field name to a real, whitelisted column name.
     * Returns null if the input doesn't match any known/allowed column —
     * callers must skip the filter entirely in that case.
     */
    private String resolveUndefinedCheckColumn(String rawField) {
        if (rawField == null || rawField.trim().isEmpty()) {
            return null;
        }
        String key = rawField.trim().toUpperCase().replace(" ", "");
        return UNDEFINED_CHECK_COLUMN_ALIASES.get(key);
    }

    /**
     * Appends a WHERE-clause condition that excludes rows where the given
     * (already-validated) column is undefined in any of these senses: - NULL -
     * blank / whitespace-only - exactly "-" (a common placeholder token seen in
     * this dataset) - contains the literal word "UNDEFINED" (case-insensitive)
     *
     * @param column MUST be a value already returned by
     * resolveUndefinedCheckColumn() — never pass raw user input directly.
     */
    private void appendExcludeUndefinedFilter(StringBuilder sql, String alias, String column) {
        String col = alias + "." + column;
        sql.append(" AND ").append(col).append(" IS NOT NULL ")
                .append("AND LTRIM(RTRIM(").append(col).append(")) <> '' ")
                .append("AND LTRIM(RTRIM(").append(col).append(")) <> '-' ")
                .append("AND UPPER(").append(col).append(") NOT LIKE '%UNDEFINED%' ");
    }

    private DatabaseManager() {
        String user = AppConfig.dbUser();
        String password = AppConfig.dbPassword();
        String server = AppConfig.dbServer();
        String database = AppConfig.dbName();
        log.info("[Manual Info]Connecting to: {} / {}", server, database);
        // Force load JDBC driver
        try {
            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            log.info("[Manual Info]SQL Server JDBC driver loaded");
        } catch (ClassNotFoundException e) {
            log.error("[Manual Error] SQL Server JDBC driver NOT FOUND in classpath!");
            throw new RuntimeException("JDBC Driver not found", e);
        }
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        config.setJdbcUrl(
                "jdbc:sqlserver://" + server + ";databaseName=" + database
                + ";encrypt=true;trustServerCertificate=true"
                + ";loginTimeout=10"
        );
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(AppConfig.dbPoolMaxSize());
        config.setMinimumIdle(AppConfig.dbPoolMinIdle());
        config.setConnectionTimeout(AppConfig.dbConnectionTimeout());
        this.dataSource = new HikariDataSource(config);
        this.queryDimensionRegistry = buildQueryDimensionRegistry();
        log.info("[Manual Info]Database connection pool initialized");
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
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (locCd == null || locCd.trim().isEmpty()) {
            return result;
        }
        locCd = locCd.trim().toUpperCase();
        Connection conn = null;
        PreparedStatement psHistory = null;
        PreparedStatement psMain = null;
        ResultSet rsHistory = null;
        ResultSet rsMain = null;
        try {
            conn = getConnection();
            // ── 1. AUTOMATIC FORMER-TO-CURRENT CODE REDIRECT ──
            String checkHistorySql = "SELECT TOP 1 CURRENT_LOC_CD FROM ais.A_LOC_CD_CHANGE_HISTORY "
                    + "WHERE UPPER(FORMER_LOC_CD) = ? AND UPPER(CURRENT_LOC_CD) != UPPER(FORMER_LOC_CD) "
                    + "ORDER BY CURRENT_LOC_CD DESC";
            psHistory = conn.prepareStatement(checkHistorySql);
            psHistory.setQueryTimeout(AppConfig.dbQueryTimeout());
            psHistory.setString(1, locCd);
            rsHistory = psHistory.executeQuery();
            if (rsHistory.next()) {
                String currentLocCd = rsHistory.getString("CURRENT_LOC_CD").trim().toUpperCase();
                if (!currentLocCd.isEmpty()) {
                    log.info("[Manual Info]Auto-Redirect: Former code '{}' detected. Querying active CURRENT_LOC_CD: '{}'", locCd, currentLocCd);
                    locCd = currentLocCd;
                }
            }
            closeQuietly(rsHistory, psHistory, null);
            // ── 2. EXECUTE MAIN GENERAL INFO QUERY USING THE ACTIVE CODE ──
            String mainSql = "SELECT * FROM ais.A_GENERAL_INFO WHERE UPPER(LOC_CD) = ?";
            psMain = conn.prepareStatement(mainSql);
            psMain.setQueryTimeout(AppConfig.dbQueryTimeout());
            psMain.setString(1, locCd);
            rsMain = psMain.executeQuery();
            ResultSetMetaData meta = rsMain.getMetaData();
            int cols = meta.getColumnCount();
            if (rsMain.next()) {
                for (int i = 1; i <= cols; i++) {
                    result.put(meta.getColumnName(i), rsMain.getObject(i));
                }
            }
        } catch (SQLException e) {
            log.error("Error executing getGeneralInfo for {}: {}", locCd, e.getMessage());
        } finally {
            closeQuietly(rsMain, psMain, null);
            closeQuietly(rsHistory, psHistory, conn);
        }
        return result;
    }

    // ── Introspect schema ────────────────────────────────────────────
    public Map<String, List<String>> introspectSchema() {
        Map<String, List<String>> schema = new LinkedHashMap<String, List<String>>();
        String sql = "SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, DATA_TYPE "
                + "FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA = 'ais' "
                + "ORDER BY TABLE_NAME, ORDINAL_POSITION";
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        try {
            conn = getConnection();
            stmt = conn.createStatement();
            rs = stmt.executeQuery(sql);
            while (rs.next()) {
                String table = rs.getString("TABLE_SCHEMA") + "." + rs.getString("TABLE_NAME");
                String col = rs.getString("COLUMN_NAME") + " (" + rs.getString("DATA_TYPE") + ")";
                if (!schema.containsKey(table)) {
                    schema.put(table, new ArrayList<String>());
                }
                schema.get(table).add(col);
            }
        } catch (SQLException e) {
            log.error("Schema introspect error: {}", e.getMessage());
        } finally {
            closeQuietly(rs, stmt, conn);
        }
        return schema;
    }

    // ══════════════════════════════════════════════════════════════
    // SEARCH BY NAME — with limit + excludeUndefinedField support
    // ══════════════════════════════════════════════════════════════
    public List<Map<String, Object>> searchByName(String locName) {
        return searchByName(locName, null, null, null);
    }

    public List<Map<String, Object>> searchByName(String locName, String location) {
        return searchByName(locName, location, null, null);
    }

    public List<Map<String, Object>> searchByName(String locName, String location, Integer limit) {
        return searchByName(locName, location, limit, null);
    }

    public List<Map<String, Object>> searchByName(
            String locName, String location, Integer limit, String excludeUndefinedField) {
        log.info("[Manual Info]Search by name: locName={} location={} limit={} excludeUndefinedField={}",
                locName, location, limit, excludeUndefinedField);
        return executeLocationQuery(
                new LocationQuery()
                        .table("ais.A_GENERAL_INFO", "g")
                        .select("g.LOC_CD", "g.LOC_NAME", "g.ADDRESS")
                        .where("g.LOC_NAME LIKE ?", "%" + locName + "%")
                        .location(location)
                        .orderBy("LOC_NAME")
                        .limit(clampLimit(limit, DEFAULT_NAME_SEARCH_LIMIT))
                        .excludeUndefinedField(excludeUndefinedField)
        );
    }

    // ── Get all related reports for a location ────────────────────
    public Map<String, Object> getFullLocationInfo(String locCd) {
        Map<String, Object> fullInfo = new LinkedHashMap<String, Object>();
        Map<String, Object> general = getGeneralInfo(locCd);
        fullInfo.put("general", general);
        if (general.containsKey("error")) {
            return fullInfo;
        }
        // ── Heritage info — always fetch for any location ─────────────────
        String historicGrade = getHistoricGradeForCode(locCd);
        String monumentStatus = getDeclaredMonumentStatus(locCd);
        fullInfo.put("historicGrade", historicGrade);
        fullInfo.put("isMonument", "T".equals(monumentStatus));
        // ── Slope detection ───────────────────────────────────────────────
        String locName = general.get("LOC_NAME") != null ? general.get("LOC_NAME").toString() : "";
        boolean isSlope = isSlopeLocation(locName);
        fullInfo.put("isSlope", isSlope);
        if (isSlope) {
            Map<String, Object> slopeData = getSlopeReports(locCd);
            fullInfo.put("slopeReports", slopeData.get("slopeReports"));
            fullInfo.put("tmcpForms", slopeData.get("tmcpForms"));
        } else {
            List<Map<String, Object>> reports
                    = new ArrayList<Map<String, Object>>();
            for (ReportTypeRegistry.ReportType reportType
                    : ReportTypeRegistry.getAvailabilityReports()) {
                reports.add(getReport(
                        locCd,
                        reportType.fullName,
                        reportType.getTableName(),
                        reportType.getIdColumn(),
                        reportType.shortName
                ));
            }
            fullInfo.put("reports", reports);
        }
        return fullInfo;
    }

    // ── Build report URL based on report type ───────────────────────
    public String buildReportUrl(String reportType, String locCd, String reportId) {
        if (reportId == null || reportId.trim().isEmpty()) {
            return null;
        }
        switch (reportType) {
            case "BSI": {
                String serverUrl = "https://domain/";
                return serverUrl + "AIS/BSI/bsiReport.jsp?reportno=" + reportId.trim();
            }
            case "CSR": {
                String serverUrl = "https://domain/";
                return serverUrl + "AIS_SP/SPServlet?name=downloadFile&path=attachments/CS" + reportId.trim();
            }
            case "KAI": {
                String serverUrl = "https://domain/";
                return serverUrl + "ReportGetFileServlet"
                        + "?reportType=KAI"
                        + "&mode=attachment"
                        + "&locCd=" + locCd.trim()
                        + "&filename=" + reportId.trim();
            }
            case "EMMS": {
                String link = reportId.trim();
                if (link.startsWith("http://") || link.startsWith("https://")) {
                    return link;
                }
                return link;
            }
            case "DSSR": {
                String serverUrl = "http://domain/asdiis/sebiis/2k/application/dssr/reportmain.aspx?locationcode=";
                long code = computeDssrCode(reportId);
                if (code < 0) {
                    return serverUrl + locCd.trim();
                }
                return serverUrl + locCd.trim() + "AAA" + code;
            }
            default:
                log.warn("Unknown report type for URL building: {}", reportType);
                return null;
        }
    }

    // ── DSSR code formula: yyyy  mm  dd * 3 + 123456 ──────────────
    private long computeDssrCode(String reportNo) {
        try {
            String digits = reportNo.replaceAll("[^0-9]", "");
            if (digits.length() >= 8) {
                String datePart = digits.substring(0, 8);
                int yyyy = Integer.parseInt(datePart.substring(0, 4));
                int mm = Integer.parseInt(datePart.substring(4, 6));
                int dd = Integer.parseInt(datePart.substring(6, 8));
                if (yyyy < 1900 || yyyy > 2100) {
                    return -1;
                }
                if (mm < 1 || mm > 12) {
                    return -1;
                }
                if (dd < 1 || dd > 31) {
                    return -1;
                }
                long code = (long) yyyy * mm * dd * 3 + 123456;
                log.info("[Manual Info]DSSR code computed: {}  *{}*  {} * 3 + 123456 = {}", yyyy, mm, dd, code);
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
            String tableName, String idColumn, String reportType) {
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("name", reportName);
        report.put("table", tableName);
        report.put("reportType", reportType);
        String sql = "SELECT TOP 1 " + idColumn + ", * FROM " + tableName + " WHERE LOC_CD = ?";
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = getConnection();
            ps = conn.prepareStatement(sql);
            ps.setQueryTimeout(AppConfig.dbQueryTimeout());
            ps.setString(1, locCd);
            rs = ps.executeQuery();
            if (rs.next()) {
                String reportId = rs.getString(idColumn);
                report.put("exists", true);
                report.put("reportId", reportId);
                String url = buildReportUrl(reportType, locCd, reportId);
                report.put("url", url);
                Map<String, Object> details = new LinkedHashMap<String, Object>();
                ResultSetMetaData meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                for (int i = 1; i <= cols; i++) {
                    String colName = meta.getColumnName(i);
                    if (colName.equals("MOD_TIME") || colName.equals("CREATE_TIME")
                            || colName.equals("STATUS") || colName.equals("TITLE")) {
                        Object value = rs.getObject(i);
                        if (value != null) {
                            details.put(colName, value);
                        }
                    }
                }
                report.put("details", details);
            } else {
                report.put("exists", false);
                report.put("reportId", null);
                report.put("url", null);
            }
        } catch (SQLException e) {
            log.error("Report fetch error for {}: {}", tableName, e.getMessage());
            report.put("exists", false);
            report.put("error", e.getMessage());
            report.put("url", null);
        } finally {
            closeQuietly(rs, ps, conn);
        }
        return report;
    }
    private final Map<String, String> orderColumnCache = new HashMap<String, String>();

    // ── Find an ordering column that actually exists in the table ────
    private synchronized String getOrderColumn(String tableName) {
        if (orderColumnCache.containsKey(tableName)) {
            return orderColumnCache.get(tableName);
        }
        String result = findOrderColumn(tableName);
        orderColumnCache.put(tableName, result);
        return result;
    }

    private String findOrderColumn(String tableName) {
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
            String schema = "ais";
            String table = tableName;
            if (tableName.contains(".")) {
                String[] parts = tableName.split("\\.", 2);
                schema = parts[0];
                table = parts[1];
            }
            ps = conn.prepareStatement(
                    "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                    + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?"
            );
            ps.setQueryTimeout(AppConfig.dbQueryTimeout());
            ps.setString(1, schema);
            ps.setString(2, table);
            rs = ps.executeQuery();
            Set<String> existingCols = new HashSet<String>();
            while (rs.next()) {
                existingCols.add(rs.getString("COLUMN_NAME").toUpperCase());
            }
            for (String candidate : candidates) {
                if (existingCols.contains(candidate)) {
                    log.info("[Manual Info]Table {} → using ORDER BY {}", tableName, candidate);
                    return candidate;
                }
            }
            log.warn("Table {} has no recognized timestamp column. Columns: {}", tableName, existingCols);
            return null;
        } catch (SQLException e) {
            log.error("findOrderColumn error for {}: {}", tableName, e.getMessage());
            return null;
        } finally {
            closeQuietly(rs, ps, conn);
        }
    }

    /**
     * Public entry point used by ToolRegistryFactory.check_reports. Supports
     * one report, comma-separated reports, and ALL.
     */
    public Map<String, Object> checkReportsForLocations(
            String reportType,
            List<String> locCds) {
        List<String> reportTypes;
        try {
            reportTypes = ReportTypeRegistry.normalizeReportTypes(
                    reportType
            );
        } catch (IllegalArgumentException e) {
            Map<String, Object> error
                    = new LinkedHashMap<String, Object>();
            error.put("reportType", reportType);
            error.put("error", e.getMessage());
            error.put(
                    "supportedReportTypes",
                    ReportTypeRegistry.getAvailabilityShortNames()
            );
            return error;
        }
        if (reportTypes.isEmpty()) {
            Map<String, Object> error
                    = new LinkedHashMap<String, Object>();
            error.put("reportType", reportType);
            error.put("error", "No report type provided");
            return error;
        }
        if (reportTypes.size() == 1) {
            return checkSingleReportForLocations(
                    reportTypes.get(0),
                    locCds
            );
        }
        return checkMultipleReportsForLocations(
                reportTypes,
                locCds
        );
    }

    /**
     * Executes one report availability query.
     *
     * This is the existing SQL body moved under a distinct method name so the
     * public multi-report dispatcher does not recursively call itself.
     */
    private Map<String, Object> checkSingleReportForLocations(String reportType, List<String> locCds) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        String normalizedType = reportType == null
                ? ""
                : reportType.trim().toUpperCase(Locale.ROOT);

        ReportTypeRegistry.ReportType report = ReportTypeRegistry.getByShortName(normalizedType);
        List<String> codes = normalizeLocationCodes(locCds);
        result.put("reportType", normalizedType);
        result.put("totalChecked", codes.size());
        if (report == null || !report.hasAvailabilityTable()) {
            result.put(
                    "error",
                    "Unsupported availability report type: "
                    + normalizedType
            );
            result.put(
                    "supportedReportTypes",
                    ReportTypeRegistry.getAvailabilityShortNames()
            );
            return result;
        }
        if (codes.isEmpty()) {
            result.put("error", "No location codes provided");
            return result;
        }
        String tableName = report.getTableName();
        String idColumn = report.getIdColumn();
        result.put("reportName", report.fullName);
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < codes.size(); i++) {
            if (i > 0) {
                placeholders.append(",");
            }
            placeholders.append("?");
        }
        String orderColumn = getOrderColumn(tableName);
        String sql;
        if (orderColumn != null) {
            sql = "SELECT t.LOC_CD, t." + idColumn + " "
                    + "FROM " + tableName + " t "
                    + "INNER JOIN ("
                    + " SELECT LOC_CD, MAX(" + orderColumn
                    + ") AS max_time"
                    + " FROM " + tableName
                    + " WHERE LOC_CD IN (" + placeholders + ")"
                    + " GROUP BY LOC_CD"
                    + ") latest"
                    + " ON t.LOC_CD = latest.LOC_CD"
                    + " AND t." + orderColumn
                    + " = latest.max_time";
        } else {
            sql = "SELECT LOC_CD, " + idColumn + " AS rid "
                    + "FROM ("
                    + " SELECT LOC_CD, " + idColumn + ","
                    + " ROW_NUMBER() OVER ("
                    + " PARTITION BY LOC_CD ORDER BY LOC_CD"
                    + " ) AS rn"
                    + " FROM " + tableName
                    + " WHERE LOC_CD IN (" + placeholders + ")"
                    + ") sub WHERE rn = 1";
        }
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        Map<String, String> foundReports
                = new LinkedHashMap<String, String>();
        try {
            conn = getConnection();
            ps = conn.prepareStatement(sql);
            ps.setQueryTimeout(AppConfig.dbQueryTimeout());
            for (int i = 0; i < codes.size(); i++) {
                ps.setString(i + 1, codes.get(i));
            }
            rs = ps.executeQuery();
            while (rs.next()) {
                String code = rs.getString("LOC_CD");
                String reportId = rs.getString(2);
                if (code != null && reportId != null) {
                    foundReports.put(
                            code.trim().toUpperCase(Locale.ROOT),
                            reportId.trim()
                    );
                }
            }
        } catch (SQLException e) {
            log.error(
                    "Bulk report check failed for {}: {}",
                    tableName,
                    e.getMessage()
            );
            result.put(
                    "error",
                    "Report availability query failed"
            );
            return result;
        } finally {
            closeQuietly(rs, ps, conn);
        }
        Map<String, String> locationNames = getLocationNames(codes);
        List<Map<String, Object>> withReport = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> withoutReport = new ArrayList<Map<String, Object>>();
        for (String code : codes) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("LOC_CD", code);
            entry.put(
                    "LOC_NAME",
                    locationNames.containsKey(code)
                    ? locationNames.get(code)
                    : ""
            );
            String reportId = foundReports.get(code);
            if (reportId != null) {
                entry.put("reportId", reportId);
                entry.put(
                        "url",
                        buildReportUrl(
                                normalizedType,
                                code,
                                reportId
                        )
                );
                withReport.add(entry);
            } else {
                withoutReport.add(entry);
            }
        }
        result.put("count", withReport.size());
        result.put("results", withReport);
        result.put("withReport", withReport);
        result.put("withoutReport", withoutReport);
        result.put("withReportCount", withReport.size());
        log.info(
                "[Manual Info]Report check: {} of {} locations have {} report",
                withReport.size(),
                codes.size(),
                normalizedType
        );
        return result;
    }

    /**
     * Executes multiple registered report checks without hardcoding report
     * names. The per-report responses remain available under checks[type].
     */
    private Map<String, Object> checkMultipleReportsForLocations(
            List<String> reportTypes,
            List<String> locCds) {
        List<String> codes
                = normalizeLocationCodes(locCds);
        Map<String, Object> result
                = new LinkedHashMap<String, Object>();
        Map<String, Object> checks
                = new LinkedHashMap<String, Object>();
        for (String type : reportTypes) {
            checks.put(
                    type,
                    checkSingleReportForLocations(
                            type,
                            codes
                    )
            );
        }
        result.put(
                "reportType",
                String.join(",", reportTypes)
        );
        result.put("reportTypes", reportTypes);
        result.put("totalChecked", codes.size());
        result.put("checks", checks);
        return result;
    }

    /**
     * Normalizes and deduplicates caller-provided location codes.
     */
    private List<String> normalizeLocationCodes(
            List<String> locCds) {
        LinkedHashSet<String> unique
                = new LinkedHashSet<String>();
        if (locCds != null) {
            for (String code : locCds) {
                if (code != null && !code.trim().isEmpty()) {
                    unique.add(
                            code.trim().toUpperCase(Locale.ROOT)
                    );
                }
            }
        }
        return new ArrayList<String>(unique);
    }

    // ── Helper: Get LOC_NAME for a batch of codes ──────────────────
    private Map<String, String> getLocationNames(List<String> locCds) {
        Map<String, String> names = new HashMap<String, String>();
        if (locCds == null || locCds.isEmpty()) {
            return names;
        }
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < locCds.size(); i++) {
            if (i > 0) {
                placeholders.append(",");
            }
            placeholders.append("?");
        }
        String sql = "SELECT LOC_CD, LOC_NAME FROM ais.A_GENERAL_INFO WHERE LOC_CD IN (" + placeholders + ")";
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = getConnection();
            ps = conn.prepareStatement(sql);
            ps.setQueryTimeout(AppConfig.dbQueryTimeout());
            for (int i = 0; i < locCds.size(); i++) {
                ps.setString(i + 1, locCds.get(i));
            }
            rs = ps.executeQuery();
            while (rs.next()) {
                names.put(rs.getString("LOC_CD").trim().toUpperCase(Locale.ROOT), rs.getString("LOC_NAME"));
            }
        } catch (SQLException e) {
            log.error("getLocationNames error: {}", e.getMessage());
        } finally {
            closeQuietly(rs, ps, conn);
        }
        return names;
    }

    // ── Batch general-info fetch for multi-candidate comparisons (e.g.
    //    OLDEST/NEWEST modifiers) — ONE query instead of one-per-candidate ──
    public List<Map<String, Object>> getGeneralInfoBatch(List<String> locCds) {
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        if (locCds == null || locCds.isEmpty()) {
            return results;
        }
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < locCds.size(); i++) {
            if (i > 0) {
                placeholders.append(",");
            }
            placeholders.append("?");
        }
        String sql = "SELECT LOC_CD, LOC_NAME, DEPT_CD, DEPT_DESC, BLDG_COMPLETION_YEAR "
                + "FROM ais.A_GENERAL_INFO WHERE LOC_CD IN (" + placeholders + ")";
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = getConnection();
            logSql(sql, locCds.toArray());
            ps = conn.prepareStatement(sql);
            ps.setQueryTimeout(AppConfig.dbQueryTimeout());
            for (int i = 0; i < locCds.size(); i++) {
                ps.setString(i + 1, locCds.get(i));
            }
            rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("LOC_CD", rs.getString("LOC_CD"));
                row.put("LOC_NAME", rs.getString("LOC_NAME"));
                row.put("DEPT_CD", rs.getString("DEPT_CD"));
                row.put("DEPT_DESC", rs.getString("DEPT_DESC"));
                row.put("BLDG_COMPLETION_YEAR", rs.getObject("BLDG_COMPLETION_YEAR"));
                results.add(row);
            }
            log.info("[Manual Info]getGeneralInfoBatch: fetched {} of {} requested codes in ONE query",
                    results.size(), locCds.size());
        } catch (SQLException e) {
            log.error("Error executing getGeneralInfoBatch: {}", e.getMessage());
        } finally {
            closeQuietly(rs, ps, conn);
        }
        return results;
    }

    // HELPER FOR SLOPES
    public boolean isSlopeLocation(String locName) {
        return locName != null && locName.toUpperCase().contains("SLOPE");
    }

    public Map<String, Object> getSlopeReports(String locCd) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("slopeReports", getSlopeReportInfo(locCd));
        result.put("tmcpForms", getTmcpForms(locCd));
        return result;
    }

    private Map<String, List<Map<String, String>>> getSlopeReportInfo(String locCd) {
        Map<String, List<Map<String, String>>> grouped = new LinkedHashMap<String, List<Map<String, String>>>();
        grouped.put("BWCS", new ArrayList<Map<String, String>>());
        grouped.put("VMI", new ArrayList<Map<String, String>>());
        grouped.put("RMI", new ArrayList<Map<String, String>>());
        grouped.put("AMI", new ArrayList<Map<String, String>>());
        grouped.put("OTHER", new ArrayList<Map<String, String>>());
        String sql = "SELECT REPORT_LINK FROM ais.Slope_Report_Info WHERE LOC_CD = ?";
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = getConnection();
            ps = conn.prepareStatement(sql);
            ps.setQueryTimeout(AppConfig.dbQueryTimeout());
            ps.setString(1, locCd);
            rs = ps.executeQuery();
            while (rs.next()) {
                String link = rs.getString("REPORT_LINK");
                if (link == null || link.trim().isEmpty()) {
                    continue;
                }
                link = link.trim();
                String type = detectSlopeReportType(link);
                String fileId = extractFileId(link);
                Map<String, String> entry = new LinkedHashMap<String, String>();
                entry.put("url", link);
                entry.put("fileId", fileId);
                entry.put("label", fileId != null ? fileId : "Report");
                grouped.get(type).add(entry);
            }
        } catch (SQLException e) {
            log.error("Slope_Report_Info query error: {}", e.getMessage());
        } finally {
            closeQuietly(rs, ps, conn);
        }
        Iterator<Map.Entry<String, List<Map<String, String>>>> it = grouped.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isEmpty()) {
                it.remove();
            }
        }
        return grouped;
    }

    private String detectSlopeReportType(String url) {
        if (url == null) {
            return "OTHER";
        }
        String upper = url.toUpperCase();
        if (upper.contains("BWCS")) {
            return "BWCS";
        }
        if (upper.contains("VMI")) {
            return "VMI";
        }
        if (upper.contains("RMI")) {
            return "RMI";
        }
        if (upper.contains("AMI")) {
            return "AMI";
        }
        return "OTHER";
    }

    private String extractFileId(String url) {
        if (url == null) {
            return null;
        }
        int idx = url.toLowerCase().indexOf("fileid=");
        if (idx < 0) {
            return null;
        }
        String fileId = url.substring(idx + 7);
        int amp = fileId.indexOf('&');
        if (amp > 0) {
            fileId = fileId.substring(0, amp);
        }
        return fileId.trim();
    }

    private Map<String, List<Map<String, Object>>> getTmcpForms(String locCd) {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<String, List<Map<String, Object>>>();
        grouped.put("Form 1", new ArrayList<Map<String, Object>>());
        grouped.put("Form 2", new ArrayList<Map<String, Object>>());
        String[][] tableConfigs = {
            {"ais.TMCP_FORM_ONE_LINK", "Form 1", "Form1", "INSP_DATE", "TMCP_OLD"},
            {"ais.TMCP_FORM_ONE_NEW_LINK", "Form 1", "Form1", "INSP_DATE", "TMCP_NEW"},
            {"ais.TMCP_FORM_TWO_LINK", "Form 2", "Form2", "INSP_DATE", "TMCP_OLD"},
            {"ais.TMIS_FORM_ONE_LINK", "Form 1", "Form1", "APPROVED_DATE", "TMIS"},
            {"ais.TMIS_FORM_TWO_LINK", "Form 2", "Form2", "APPROVED_DATE", "TMIS"}
        };
        for (String[] cfg : tableConfigs) {
            String tableName = cfg[0];
            String formGroup = cfg[1];
            String formType = cfg[2];
            String dateColumn = cfg[3];
            String urlSource = cfg[4];
            List<Map<String, Object>> rows = fetchFormTable(tableName, dateColumn, locCd, formType, urlSource);
            grouped.get(formGroup).addAll(rows);
        }
        for (List<Map<String, Object>> list : grouped.values()) {
            list.sort(new Comparator<Map<String, Object>>() {
                @Override
                public int compare(Map<String, Object> a, Map<String, Object> b) {
                    String dateA = (String) a.get("inspDate");
                    String dateB = (String) b.get("inspDate");
                    if (dateA == null) {
                        dateA = "";
                    }
                    if (dateB == null) {
                        dateB = "";
                    }
                    return dateB.compareTo(dateA);
                }
            });
        }
        Iterator<Map.Entry<String, List<Map<String, Object>>>> it = grouped.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isEmpty()) {
                it.remove();
            }
        }
        return grouped;
    }

    private List<Map<String, Object>> fetchFormTable(
            String tableName, String dateColumn, String locCd, String formType, String urlSource) {
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
            ps.setQueryTimeout(AppConfig.dbQueryTimeout());
            ps.setString(1, locCd);
            rs = ps.executeQuery();
            while (rs.next()) {
                String rawLink = rs.getString("REPORT_LINK");
                Object dateObj = rs.getObject("form_date");
                if (rawLink == null || rawLink.trim().isEmpty()) {
                    continue;
                }
                String url = buildFormUrl(rawLink.trim(), formType, urlSource);
                if (url == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("url", url);
                row.put("inspDate", formatInspDate(dateObj));
                row.put("rawLink", rawLink.trim());
                row.put("source", urlSource);
                rows.add(row);
            }
        } catch (SQLException e) {
            log.error("Form query error for {}: {}", tableName, e.getMessage());
        } finally {
            closeQuietly(rs, ps, conn);
        }
        return rows;
    }

    private String buildFormUrl(String rawLink, String formType, String urlSource) {
        if (rawLink == null || rawLink.trim().isEmpty()) {
            return null;
        }
        rawLink = rawLink.trim();
        if (rawLink.startsWith("http://") || rawLink.startsWith("https://")) {
            return rawLink;
        }
        switch (urlSource) {
            case "TMIS": {
                String filename = extractTmisFilename(rawLink);
                if (filename == null) {
                    log.warn("Cannot extract filename from TMIS link: {}", rawLink);
                    return null;
                }
                return "https://domain/AIS/ReportGetFileServlet"
                        + "?reportType=" + formType
                        + "&filename=" + filename;
            }
            case "TMCP_OLD": {
                String filename = extractTmisFilename(rawLink);
                if (filename != null) {
                    return "https://domain/AIS/ReportGetFileServlet"
                            + "?reportType=" + formType
                            + "&filename=" + filename;
                }
                return "https://domain/AIS/ReportGetFileServlet"
                        + "?reportType=" + formType
                        + "&filename=" + rawLink;
            }
            case "TMCP_NEW": {
                return "http://domain/ASD_Slope/AIS/downloadreport.aspx"
                        + "?Worktype=" + formType
                        + "&id=" + rawLink;
            }
            default:
                log.warn("Unknown urlSource: {}", urlSource);
                return null;
        }
    }

    private String extractTmisFilename(String rawLink) {
        if (rawLink == null) {
            return null;
        }
        String s = rawLink.replace("\\", "/").trim();
        int lastSlash = s.lastIndexOf('/');
        if (lastSlash >= 0) {
            s = s.substring(lastSlash + 1);
        }
        if (s.toLowerCase().endsWith(".pdf")) {
            s = s.substring(0, s.length() - 4);
        }
        return s.isEmpty() ? null : s;
    }

    private String formatInspDate(Object dateObj) {
        if (dateObj == null) {
            return "";
        }
        try {
            if (dateObj instanceof java.sql.Date) {
                return new java.text.SimpleDateFormat("yyyy-MM-dd").format((java.sql.Date) dateObj);
            }
            if (dateObj instanceof java.sql.Timestamp) {
                return new java.text.SimpleDateFormat("yyyy-MM-dd").format((java.sql.Timestamp) dateObj);
            }
            if (dateObj instanceof java.util.Date) {
                return new java.text.SimpleDateFormat("yyyy-MM-dd").format((java.util.Date) dateObj);
            }
        } catch (Exception ignored) {
        }
        return dateObj.toString();
    }

    // UNIQUE PSM
    public List<Map<String, Object>> getDistinctPsms() {
        List<Map<String, Object>> psmList = new ArrayList<Map<String, Object>>();
        String sql = "SELECT LTRIM(RTRIM(PSM)) AS psm_name, COUNT(*) AS location_count "
                + "FROM ais.A_GENERAL_INFO "
                + "WHERE PSM IS NOT NULL AND LTRIM(RTRIM(PSM)) <> '' "
                + "GROUP BY LTRIM(RTRIM(PSM)) "
                + "ORDER BY location_count DESC, psm_name ASC";
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = getConnection();
            ps = conn.prepareStatement(sql);
            ps.setQueryTimeout(AppConfig.dbQueryTimeout());
            rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("psm", rs.getString("psm_name"));
                row.put("count", rs.getInt("location_count"));
                psmList.add(row);
            }
            log.info("[Manual Info]Loaded {} distinct PSM values", psmList.size());
        } catch (SQLException e) {
            log.error("Distinct PSM query error: {}", e.getMessage());
        } finally {
            closeQuietly(rs, ps, conn);
        }
        return psmList;
    }

    // ══════════════════════════════════════════════════════════════
    // getLocationsByPsm — with limit + excludeUndefinedField support.
    // Restricted to "PSM/" prefix only (MS/HERITAGE, SE/TIM, etc. are a
    // different business category and must never be returned here).
    // Matches on the NAME PORTION after the slash so "CENTRAL" correctly
    // matches "PSM/CENTRAL EAST"/"PSM/CENTRAL WEST" without false-matching
    // something like "PSM/KLN. CITY CENTRAL (1)".
    // ══════════════════════════════════════════════════════════════
    public List<Map<String, Object>> getLocationsByPsm(String psm) {
        return getLocationsByPsm(psm, null, null, null);
    }

    public List<Map<String, Object>> getLocationsByPsm(String psm, String location) {
        return getLocationsByPsm(psm, location, null, null);
    }

    public List<Map<String, Object>> getLocationsByPsm(String psm, String location, Integer limit) {
        return getLocationsByPsm(psm, location, limit, null);
    }

    /**
     * @param excludeUndefinedField free-text field name from the user's prompt
     * (e.g. "address", "name", "department"), or null/empty for no filter.
     * Resolved against a whitelist internally — if the requested field isn't
     * recognized, the filter is silently skipped (logged as a warning) rather
     * than guessing.
     */
    public List<Map<String, Object>> getLocationsByPsm(
            String psm, String location, Integer limit, String excludeUndefinedField) {
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        if (psm == null || psm.trim().isEmpty()) {
            return results;
        }
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = getConnection();
            boolean hasLocation = location != null && !location.trim().isEmpty();
            int safeLimit = clampLimit(limit, DEFAULT_LIST_LIMIT);
            String undefinedColumn = resolveUndefinedCheckColumn(excludeUndefinedField);
            if (excludeUndefinedField != null && !excludeUndefinedField.trim().isEmpty() && undefinedColumn == null) {
                log.warn("[Manual Info] Unrecognized field '{}' for exclude-undefined filter — skipping filter",
                        excludeUndefinedField);
            }
            // Restricted to prefix "PSM/" only, by design — the
            // "PREFIX/NAME" column also contains non-PSM categories such
            // as "MS/HERITAGE 1" and "SE/TIM".
            StringBuilder sql = new StringBuilder(
                    "SELECT TOP " + safeLimit
                    + " g.LOC_CD, g.LOC_NAME, g.ADDRESS "
                    + "FROM ais.A_GENERAL_INFO g "
                    + "WHERE UPPER(RTRIM(g.PSM)) "
                    + "LIKE ? ESCAPE '\\' "
            );
            if (hasLocation) {
                sql.append("AND (UPPER(g.LOC_NAME) LIKE ? OR UPPER(g.ADDRESS) LIKE ?) ");
            }
            if (undefinedColumn != null) {
                appendExcludeUndefinedFilter(sql, "g", undefinedColumn);
            }
            sql.append("ORDER BY g.LOC_NAME");
            // "%/" + escaped(nameTerm) + "%" — matches only PSMs whose name
            // portion (after the slash) starts with the search term.
            // An ambiguous term matching multiple real PSMs (e.g. "CENTRAL"
            // matching both PSM/CENTRAL EAST and PSM/CENTRAL WEST)
            // intentionally returns the MERGED location set — deliberate,
            // not a bug.
            String namePart = extractPsmNameTerm(psm);
            String psmParam = "PSM/" + escapeLikeWildcards(namePart) + "%";
            String locParam = hasLocation ? "%" + escapeLikeWildcards(location.trim().toUpperCase()) + "%" : null;
            if (hasLocation) {
                logSql(sql.toString(), psmParam, locParam, locParam);
            } else {
                logSql(sql.toString(), psmParam);
            }
            ps = conn.prepareStatement(sql.toString());
            ps.setString(1, psmParam);
            ps.setQueryTimeout(AppConfig.dbQueryTimeout());
            if (hasLocation) {
                ps.setString(2, locParam);
                ps.setString(3, locParam);
            }
            rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("LOC_CD", rs.getString("LOC_CD"));
                row.put("LOC_NAME", rs.getString("LOC_NAME"));
                row.put("ADDRESS", rs.getString("ADDRESS"));
                results.add(row);
            }
            log.info("[Manual Info]Found {} locations for PSM: {} (limit={}, excludeUndefinedField={})",
                    results.size(), psm, safeLimit, undefinedColumn);
        } catch (SQLException e) {
            log.error("Error executing getLocationsByPsm for {}: {}", psm, e.getMessage());
        } finally {
            closeQuietly(rs, ps, conn);
        }
        return results;
    }

    /**
     * Normalizes a user/LLM-supplied PSM search term down to just the bare name
     * portion, stripping any prefix the caller may or may not have included.
     * Handles: "CENTRAL WEST", "PSM/CENTRAL WEST", "psm/central west", "PSM
     * CENTRAL WEST", " Central West " — all the same way.
     */
    private String extractPsmNameTerm(String psm) {
        String trimmed = psm.trim().toUpperCase();
        int slashIdx = trimmed.indexOf('/');
        if (slashIdx >= 0) {
            return trimmed.substring(slashIdx + 1).trim();
        }
        String[] knownPrefixes = {"PSM ", "MS ", "SE "};
        for (String prefix : knownPrefixes) {
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return trimmed;
    }

    /**
     * Escapes SQL LIKE wildcard characters (% and _) so they are treated as
     * literal characters instead of pattern wildcards if they ever appear in a
     * PSM name or an LLM-hallucinated search term.
     */
    private String escapeLikeWildcards(String input) {
        if (input == null) {
            return "";
        }
        return input
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    // ══════════════════════════════════════════════════════════════
    // LOCATIONS BY DEPARTMENT — with limit + excludeUndefinedField
    // ══════════════════════════════════════════════════════════════
    public List<Map<String, Object>> getLocationsByDept(String deptCd) {
        return getLocationsByDept(deptCd, null, null, null);
    }

    public List<Map<String, Object>> getLocationsByDept(String deptCd, String location) {
        return getLocationsByDept(deptCd, location, null, null);
    }

    public List<Map<String, Object>> getLocationsByDept(String deptCd, String location, Integer limit) {
        return getLocationsByDept(deptCd, location, limit, null);
    }

    public List<Map<String, Object>> getLocationsByDept(
            String deptCd, String location, Integer limit, String excludeUndefinedField) {
        log.info("[Manual Info]Locations by dept: deptCd={} location={} limit={} excludeUndefinedField={}",
                deptCd, location, limit, excludeUndefinedField);
        return executeLocationQuery(
                new LocationQuery()
                        .table("ais.A_GENERAL_INFO", "g")
                        .select("g.LOC_CD", "g.LOC_NAME", "g.ADDRESS", "g.DEPT_CD")
                        .where("UPPER(LTRIM(RTRIM(g.DEPT_CD))) = ?", deptCd.toUpperCase().trim())
                        .location(location)
                        .orderBy("LOC_NAME")
                        .limit(clampLimit(limit, DEFAULT_LIST_LIMIT))
                        .excludeUndefinedField(excludeUndefinedField)
        );
    }

    // ══════════════════════════════════════════════════════════════
    // DECLARED MONUMENTS — with limit + excludeUndefinedField
    // ══════════════════════════════════════════════════════════════
    public List<Map<String, Object>> getDeclaredMonuments(String filter) {
        return getDeclaredMonuments(filter, null, null, null);
    }

    public List<Map<String, Object>> getDeclaredMonuments(String filter, String location) {
        return getDeclaredMonuments(filter, location, null, null);
    }

    public List<Map<String, Object>> getDeclaredMonuments(String filter, String location, Integer limit) {
        return getDeclaredMonuments(filter, location, limit, null);
    }

    public List<Map<String, Object>> getDeclaredMonuments(
            String filter, String location, Integer limit, String excludeUndefinedField) {
        String gisDb = AppConfig.GISdbName();
        if (gisDb.isEmpty()) {
            log.error("GIS DB NAME not configured");
            return new ArrayList<Map<String, Object>>();
        }
        log.info("[Manual Info]Declared monuments: filter={} location={} limit={} excludeUndefinedField={}",
                filter, location, limit, excludeUndefinedField);
        return executeDeclaredMonumentQuery(
                filter, location, gisDb, clampLimit(limit, DEFAULT_LIST_LIMIT), excludeUndefinedField);
    }

    // ══════════════════════════════════════════════════════════════
    // HISTORIC BUILDINGS — with limit + excludeUndefinedField
    // ══════════════════════════════════════════════════════════════
    public List<Map<String, Object>> getHistoricBuildings(String grade) {
        return getHistoricBuildings(grade, null, null, null);
    }

    public List<Map<String, Object>> getHistoricBuildings(String grade, String location) {
        return getHistoricBuildings(grade, location, null, null);
    }

    public List<Map<String, Object>> getHistoricBuildings(String grade, String location, Integer limit) {
        return getHistoricBuildings(grade, location, limit, null);
    }

    public List<Map<String, Object>> getHistoricBuildings(
            String grade, String location, Integer limit, String excludeUndefinedField) {
        String gisDb = AppConfig.GISdbName();
        if (gisDb.isEmpty()) {
            log.error("GIS DB NAME not configured");
            return new ArrayList<Map<String, Object>>();
        }
        String safeGrade = sanitizeGradeForDb(grade);
        log.info("[Manual Info]Historic buildings: grade={} (raw={}) location={} limit={} excludeUndefinedField={}",
                safeGrade, grade, location, limit, excludeUndefinedField);
        return executeHistoricBuildingQuery(
                safeGrade, location, gisDb, clampLimit(limit, DEFAULT_LIST_LIMIT), excludeUndefinedField);
    }

    /**
     * DB-level grade sanitizer — last line of defense. Converts any
     * LLM-hallucinated value to a safe DB-query value.
     */
    private String sanitizeGradeForDb(String grade) {
        if (grade == null || grade.trim().isEmpty()) {
            return "ALL";
        }
        String g = grade.trim().toUpperCase();
        switch (g) {
            case "1":
            case "2":
            case "3":
            case "ALL":
            case "NONE":
            case "0":
            case "GRADED":
                return g;
            default:
                log.warn(" DB sanitizer: invalid grade '{}' → ALL", grade);
                return "ALL";
        }
    }

    // ══════════════════════════════════════════════════════════════
    // LOCATION CODE CHANGE HISTORY
    // ══════════════════════════════════════════════════════════════
    public List<Map<String, Object>> getLocCdChangeHistory(String formerCd, String currentCd) {
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
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
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append(conditions.get(i));
        }
        sql.append(" ORDER BY h.CURRENT_LOC_CD");
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = getConnection();
            ps = conn.prepareStatement(sql.toString());
            ps.setQueryTimeout(AppConfig.dbQueryTimeout());
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("CURRENT_LOC_CD", rs.getString("CURRENT_LOC_CD"));
                row.put("CURRENT_LOC_NAME", rs.getString("CURRENT_LOC_NAME"));
                row.put("FORMER_LOC_CD", rs.getString("FORMER_LOC_CD"));
                row.put("FORMER_LOC_NAME", rs.getString("FORMER_LOC_NAME"));
                results.add(row);
            }
            log.info("[Manual Info]Found {} code change history entries (former={}, current={})",
                    results.size(), formerCd, currentCd);
        } catch (SQLException e) {
            log.error("Loc CD change history error: {}", e.getMessage());
        } finally {
            closeQuietly(rs, ps, conn);
        }
        return results;
    }

    // ══════════════════════════════════════════════════════════════
    // UNIVERSAL QUERY BUILDER
    // ══════════════════════════════════════════════════════════════
    public static class LocationQuery {

        public String baseTable;
        public String tableAlias;
        public String gisTable;
        public String gisAlias;
        public String gisJoinColumn;
        public List<String> selectColumns = new ArrayList<String>();
        public List<String> conditions = new ArrayList<String>();
        public List<Object> params = new ArrayList<Object>();
        public String location;
        public String orderBy = "LOC_NAME";
        public int limit = 200;
        // NEW: free-text field name (resolved+validated inside
        // executeLocationQuery() via resolveUndefinedCheckColumn())
        public String excludeUndefinedField;

        public LocationQuery table(String t, String alias) {
            this.baseTable = t;
            this.tableAlias = alias;
            return this;
        }

        public LocationQuery gisJoin(String gisTable, String alias) {
            this.gisTable = gisTable;
            this.gisAlias = alias;
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

        public LocationQuery excludeUndefinedField(String field) {
            this.excludeUndefinedField = (field != null && !field.trim().isEmpty()) ? field.trim() : null;
            return this;
        }
    }

    /**
     * Executes a LocationQuery and returns rows as List<Map>. This is the
     * SINGLE SQL execution point for all list queries.
     */
    private List<Map<String, Object>> executeLocationQuery(LocationQuery q) {
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT TOP ").append(clampLimit(q.limit, q.limit)).append(" ");
        if (q.selectColumns.isEmpty()) {
            sql.append(q.tableAlias).append(".*");
        } else {
            sql.append(String.join(", ", q.selectColumns));
        }
        sql.append(" FROM ").append(q.baseTable).append(" ").append(q.tableAlias);
        if (q.gisTable != null) {
            sql.append(" LEFT JOIN ").append(q.gisTable)
                    .append(" ").append(q.gisAlias)
                    .append(" ON ").append(q.tableAlias).append(".").append(q.gisJoinColumn)
                    .append(" = ").append(q.gisAlias).append(".").append(q.gisJoinColumn);
        }
        List<Object> allParams = new ArrayList<Object>(q.params);
        if (!q.conditions.isEmpty()) {
            sql.append(" WHERE ");
            sql.append(String.join(" AND ", q.conditions));
        }
        if (q.location != null) {
            String locPattern = "%" + q.location.toUpperCase() + "%";
            if (q.conditions.isEmpty()) {
                sql.append(" WHERE ");
            } else {
                sql.append(" AND ");
            }
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
            log.info("[Manual Info] Location filter applied: '{}' (also trying '{}')",
                    q.location, q.location.replace(" ", ""));
        }
        // NEW: apply exclude-undefined filter if a valid field was requested
        String undefinedColumn = resolveUndefinedCheckColumn(q.excludeUndefinedField);
        if (q.excludeUndefinedField != null && undefinedColumn == null) {
            log.warn("[Manual Info] Unrecognized field '{}' for exclude-undefined filter — skipping filter",
                    q.excludeUndefinedField);
        }
        if (undefinedColumn != null) {
            if (q.conditions.isEmpty() && q.location == null) {
                sql.append(" WHERE 1=1");
            }
            appendExcludeUndefinedFilter(sql, q.tableAlias, undefinedColumn);
        }
        if (q.orderBy != null) {
            sql.append(" ORDER BY ").append(q.tableAlias).append(".").append(q.orderBy);
        }
        log.debug("[Error] SQL: {}", sql);
        log.debug("[Error] Params: {}", allParams);
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = getConnection();
            ps = conn.prepareStatement(sql.toString());
            ps.setQueryTimeout(AppConfig.dbQueryTimeout());
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
        } catch (SQLTimeoutException e) {
            throw new DatabaseQueryTimeoutException("Location query timed out", e);
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
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ── Declared monuments (GIS-primary join) ────────────────────────
    private List<Map<String, Object>> executeDeclaredMonumentQuery(
            String filter, String location, String gisDb, int safeLimit, String excludeUndefinedField) {
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<Object>();
        sql.append("SELECT TOP ").append(safeLimit).append(" ")
                .append("c.LOC_CD, g.LOC_NAME, g.ADDRESS, c.DECLR_MONUMT ")
                .append("FROM ( ")
                .append("  SELECT LOC_CD, MAX(DECLR_MONUMT) AS DECLR_MONUMT ")
                .append("  FROM ").append(gisDb).append(".sde.T_ASD_COMBINED ")
                .append("  GROUP BY LOC_CD ")
                .append(") c ")
                .append("LEFT JOIN ais.A_GENERAL_INFO g ON c.LOC_CD = g.LOC_CD ")
                .append("WHERE 1=1 ");
        if ("T".equals(filter)) {
            sql.append("AND UPPER(LTRIM(RTRIM(c.DECLR_MONUMT))) = 'T' ");
        } else if ("F".equals(filter)) {
            sql.append("AND (UPPER(LTRIM(RTRIM(c.DECLR_MONUMT))) = 'F' ")
                    .append("OR c.DECLR_MONUMT IS NULL) ");
        }
        String undefinedColumn = resolveUndefinedCheckColumn(excludeUndefinedField);
        if (excludeUndefinedField != null && undefinedColumn == null) {
            log.warn("[Manual Info] Unrecognized field '{}' for exclude-undefined filter — skipping filter",
                    excludeUndefinedField);
        }
        if (undefinedColumn != null) {
            appendExcludeUndefinedFilter(sql, "g", undefinedColumn);
        }
        appendLocationFilter(sql, params, "g", location);
        sql.append("ORDER BY g.LOC_NAME");
        List<Map<String, Object>> results = executeRawQuery(sql.toString(), params);
        log.info("[Manual Info]Found {} declared monuments (filter={}, location={}, limit={}, excludeUndefinedField={})",
                results.size(), filter, location, safeLimit, undefinedColumn);
        return results;
    }

    // ── Historic buildings (GIS-primary join) ────────────────────────
    private List<Map<String, Object>> executeHistoricBuildingQuery(
            String grade, String location, String gisDb, int safeLimit, String excludeUndefinedField) {
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<Object>();
        sql.append("SELECT TOP ").append(safeLimit).append(" ")
                .append("c.LOC_CD, g.LOC_NAME, g.ADDRESS, c.GRD_HIST_BLDG ")
                .append("FROM ( ")
                .append("  SELECT LOC_CD, MAX(GRD_HIST_BLDG) AS GRD_HIST_BLDG ")
                .append("  FROM ").append(gisDb).append(".sde.T_ASD_COMBINED ")
                .append("  GROUP BY LOC_CD ")
                .append(") c ")
                .append("LEFT JOIN ais.A_GENERAL_INFO g ON c.LOC_CD = g.LOC_CD ")
                .append("WHERE 1=1 ");
        appendGradeFilter(sql, params, grade);
        String undefinedColumn = resolveUndefinedCheckColumn(excludeUndefinedField);
        if (excludeUndefinedField != null && undefinedColumn == null) {
            log.warn("[Manual Info] Unrecognized field '{}' for exclude-undefined filter — skipping filter",
                    excludeUndefinedField);
        }
        if (undefinedColumn != null) {
            appendExcludeUndefinedFilter(sql, "g", undefinedColumn);
        }
        appendLocationFilter(sql, params, "g", location);
        sql.append("ORDER BY g.LOC_NAME");
        List<Map<String, Object>> results = executeRawQuery(sql.toString(), params);
        log.info("[Manual Info]Found {} historic buildings (grade={}, location={}, limit={}, excludeUndefinedField={})",
                results.size(), grade, location, safeLimit, undefinedColumn);
        return results;
    }

    // ── Append location filter to any SQL ────────────────────────────
    private void appendLocationFilter(StringBuilder sql, List<Object> params, String alias, String location) {
        if (location == null || location.trim().isEmpty()) {
            return;
        }
        String locPattern = "%" + location.trim().toUpperCase() + "%";
        String locNoSpace = "%" + location.trim().toUpperCase().replace(" ", "") + "%";
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
        log.info("[Manual Info] DB location filter: '{}' + no-space variant '{}'",
                location, location.replace(" ", ""));
    }

    // ── Raw query executor (for GIS join queries) ────────────────────
    private List<Map<String, Object>> executeRawQuery(String sql, List<Object> params) {
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = getConnection();
            ps = conn.prepareStatement(sql);
            ps.setQueryTimeout(AppConfig.dbQueryTimeout());
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
        } catch (SQLTimeoutException e) {
            throw new DatabaseQueryTimeoutException("Database query timed out", e);
        } catch (SQLException e) {
            throw new DatabaseQueryException("Database query failed", e);
        } finally {
            closeQuietly(rs, ps, conn);
        }
        return results;
    }

    /**
     * Execute a raw SELECT query generated by the LLM. SAFETY: only SELECT
     * statements are allowed. Returns results as List<Map> or an error map.
     */
    public Map<String, Object> executeLlmGeneratedQuery(String sql, AuthorizationContext authorizationContext) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        if (authorizationContext == null) {
            response.put("error", "Authorization context required");
            return response;
        }
        if (sql == null || sql.trim().isEmpty()) {
            response.put("error", "Empty SQL query");
            return response;
        }
        if (!isAllowedLlmSql(sql)) {
            response.put("error",
                    "Query rejected by database security policy");
            return response;
        }
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
                log.error("[Error]Blocked LLM SQL (contains {}): {}", keyword, sql);
                response.put("error", "Only SELECT queries are allowed. "
                        + "Blocked keyword: " + keyword);
                return response;
            }
        }
        if (!trimmed.startsWith("SELECT")) {
            response.put("error", "Query must start with SELECT.");
            return response;
        }
        if (!trimmed.contains("TOP ") && !trimmed.contains("FETCH FIRST")) {
            sql = sql.trim().replaceFirst("(?i)^SELECT", "SELECT TOP 200");
            log.info("[Manual Info] Added TOP 200 to LLM query for safety");
        }
        log.debug("Executing approved LLM-generated SQL");
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        try {
            conn = getConnection();
            ps = conn.prepareStatement(sql);
            ps.setQueryTimeout(AppConfig.dbQueryTimeout());
            ps.setMaxRows(MAX_LLM_RESULT_ROWS);
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
            response.put("count", results.size());
            response.put("results", results);
            log.info("[Manual Info]LLM SQL returned {} rows", results.size());
        } catch (SQLTimeoutException e) {
            log.warn("Database query timed out after {} seconds", AppConfig.dbQueryTimeoutSeconds());
            response.put("error", "Database query timed out");
            response.put("errorCode", "QUERY_TIMEOUT");
            response.put("retryable", false);
        } catch (SQLException e) {
            log.error("[Manual Error] LLM SQL execution error: {}", e.getMessage());
            response.put("error", "Database query could not be completed");
        } finally {
            closeQuietly(rs, ps, conn);
        }
        return response;
    }

    /**
     * Appends a grade filter clause to any SQL builder.
     */
    private void appendGradeFilter(StringBuilder sql, List<Object> params, String grade) {
        if (grade == null || grade.trim().isEmpty()) {
            return;
        }
        String g = grade.trim().toUpperCase();
        switch (g) {
            case "ALL":
                sql.append("AND c.GRD_HIST_BLDG IS NOT NULL ")
                        .append("AND LTRIM(RTRIM(c.GRD_HIST_BLDG)) <> '' ")
                        .append("AND LTRIM(RTRIM(c.GRD_HIST_BLDG)) <> '0' ");
                break;
            case "GRADED":
                sql.append("AND c.GRD_HIST_BLDG IS NOT NULL ")
                        .append("AND LTRIM(RTRIM(c.GRD_HIST_BLDG)) <> '' ")
                        .append("AND LTRIM(RTRIM(c.GRD_HIST_BLDG)) <> '0' ");
                break;
            case "0":
            case "NONE":
                sql.append("AND (c.GRD_HIST_BLDG IS NULL ")
                        .append("OR LTRIM(RTRIM(c.GRD_HIST_BLDG)) = '' ")
                        .append("OR LTRIM(RTRIM(c.GRD_HIST_BLDG)) = '0') ");
                break;
            case "1":
            case "2":
            case "3":
                sql.append("AND LTRIM(RTRIM(c.GRD_HIST_BLDG)) = ? ");
                params.add(g);
                break;
            default:
                log.warn(" Unknown grade filter '{}' — returning all graded", grade);
                sql.append("AND c.GRD_HIST_BLDG IS NOT NULL ")
                        .append("AND LTRIM(RTRIM(c.GRD_HIST_BLDG)) <> '' ");
                break;
        }
    }

    /**
     * Get historic grade for a specific location code.
     */
    public String getHistoricGradeForCode(String locCd) {
        String gisDb = AppConfig.GISdbName();
        if (gisDb.isEmpty()) {
            log.error("GIS DB NAME not configured");
            return null;
        }
        String sql = "SELECT TOP 1 GRD_HIST_BLDG "
                + "FROM " + gisDb + ".sde.T_ASD_COMBINED "
                + "WHERE LOC_CD = ? "
                + "AND GRD_HIST_BLDG IS NOT NULL "
                + "AND LTRIM(RTRIM(GRD_HIST_BLDG)) <> '' "
                + "AND LTRIM(RTRIM(GRD_HIST_BLDG)) <> '0' "
                + "ORDER BY GRD_HIST_BLDG";
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = getConnection();
            ps = conn.prepareStatement(sql);
            ps.setQueryTimeout(AppConfig.dbQueryTimeout());
            ps.setString(1, locCd.trim().toUpperCase());
            rs = ps.executeQuery();
            if (rs.next()) {
                String grade = rs.getString("GRD_HIST_BLDG");
                log.info("[Manual Info]Historic grade for {}: {}", locCd, grade);
                return grade != null ? grade.trim() : null;
            }
            log.info("[Manual Info]No historic grade found for {}", locCd);
            return null;
        } catch (SQLException e) {
            log.error("getHistoricGradeForCode error for {}: {}", locCd, e.getMessage());
            return null;
        } finally {
            closeQuietly(rs, ps, conn);
        }
    }

    /**
     * Get declared monument status for a specific location code.
     */
    public String getDeclaredMonumentStatus(String locCd) {
        String gisDb = AppConfig.GISdbName();
        if (gisDb.isEmpty()) {
            return null;
        }
        String sql = "SELECT TOP 1 DECLR_MONUMT "
                + "FROM " + gisDb + ".sde.T_ASD_COMBINED "
                + "WHERE LOC_CD = ?";
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = getConnection();
            ps = conn.prepareStatement(sql);
            ps.setQueryTimeout(AppConfig.dbQueryTimeout());
            ps.setString(1, locCd.trim().toUpperCase());
            rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("DECLR_MONUMT");
            }
            return null;
        } catch (SQLException e) {
            log.error("getDeclaredMonumentStatus error: {}", e.getMessage());
            return null;
        } finally {
            closeQuietly(rs, ps, conn);
        }
    }

    // ── Debug helper: logs the resolved SQL + bound params. Microsoft's
    //    JDBC driver does NOT override PreparedStatement.toString(), so
    //    this is necessary to see the actual query that ran. Call this
    //    immediately before executeQuery() in any method that builds SQL
    //    dynamically. Gated on DEBUG level — set
    //    com.ais.db.DatabaseManager=DEBUG in logback config to see output.
    private void logSql(String sql, Object... params) {
        if (log.isDebugEnabled()) {
            StringBuilder resolved = new StringBuilder(sql);
            for (Object p : params) {
                int idx = resolved.indexOf("?");
                if (idx < 0) {
                    break;
                }
                String val = (p == null) ? "NULL" : "'" + p.toString().replace("'", "''") + "'";
                resolved.replace(idx, idx + 1, val);
            }
            log.debug("[SQL] {}", resolved);
            log.debug("[SQL-PARAMS] {}", java.util.Arrays.toString(params));
        }
    }

    private boolean isAllowedLlmSql(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return false;
        }
        String upper = sql.toUpperCase(Locale.ROOT);
        if (!upper.trim().startsWith("SELECT")) {
            return false;
        }
        // Reject multi-statements, comments, unions, CTEs, and unsafe SQL features.
        if (upper.contains(";")
                || upper.contains("--")
                || upper.contains("/*")
                || upper.contains("*/")
                || upper.matches("(?s).*\\bUNION\\b.*")
                || upper.matches("(?s).*\\bWITH\\b.*")
                || upper.matches("(?s).*\\bINFORMATION_SCHEMA\\b.*")
                || upper.matches("(?s).*\\bSYS\\.")
                || upper.matches("(?s).*\\bOPENROWSET\\b.*")
                || upper.matches("(?s).*\\bOPENDATASOURCE\\b.*")
                || upper.matches("(?s).*\\bWAITFOR\\b.*")
                || upper.matches("(?s).*\\bFOR\\s+XML\\b.*")
                || upper.matches("(?s).*\\bFOR\\s+JSON\\b.*")) {
            return false;
        }
        Matcher topMatcher = TOP_PATTERN.matcher(sql);
        if (topMatcher.find()) {
            int requestedRows;
            try {
                requestedRows = Integer.parseInt(topMatcher.group(1));
            } catch (NumberFormatException e) {
                return false;
            }
            if (requestedRows < 1 || requestedRows > MAX_LLM_RESULT_ROWS) {
                return false;
            }
        }
        Set<String> allowedTables
                = new HashSet<String>(BASE_ALLOWED_LLM_TABLES);
        String gisDb = AppConfig.GISdbName();
        if (gisDb != null && !gisDb.trim().isEmpty()) {
            allowedTables.add(
                    (gisDb + ".SDE.T_ASD_COMBINED")
                            .toUpperCase(Locale.ROOT)
            );
        }
        Matcher tableMatcher = LLM_TABLE_REFERENCE.matcher(sql);
        boolean foundTable = false;
        while (tableMatcher.find()) {
            foundTable = true;
            String tableName = tableMatcher.group(1)
                    .replace("[", "")
                    .replace("]", "")
                    .toUpperCase(Locale.ROOT);
            if (!allowedTables.contains(tableName)) {
                log.warn("[Security] LLM SQL rejected: table not allowlisted");
                return false;
            }
        }
        return foundTable;
    }

    public Map<String, Object> executeLocationQuery(LocationQuerySpec spec) {
        if (spec == null) {
            return emptyQueryResponse();
        }
        LocationQuery query = new LocationQuery()
                .table("ais.A_GENERAL_INFO", "g")
                .select("g.LOC_CD", "g.LOC_NAME", "g.ADDRESS", "g.DEPT_CD")
                .orderBy("LOC_NAME")
                .limit(clampLimit(spec.getLimit(), DEFAULT_LIST_LIMIT))
                .excludeUndefinedField(spec.getExcludeUndefinedField());
        for (QueryPredicate p : spec.getPredicates()) {
            if (p != null) {
                query.whereRaw(p.getSqlFragment());
                query.params.addAll(p.getParameters());
            }
        }
        List<Map<String, Object>> rows = executeLocationQuery(query);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", rows.size());
        response.put("results", rows);
        return response;
    }

    public Map<String, Object> executeLocationQuery(Map<String, Object> filters) {
        Map<String, Object> f = (filters == null) ? Collections.emptyMap() : filters;
        LocationQuerySpec spec = new LocationQuerySpec();
        for (Map.Entry<String, Object> e : f.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            String key = e.getKey();
            Object val = e.getValue();
            if ("limit".equals(key)) {
                spec.setLimit(parseQueryLimit(val));
            } else if ("excludeUndefinedField".equals(key)) {
                spec.setExcludeUndefinedField(val.toString());
            } else {
                queryDimensionRegistry.apply(spec, key, normalizeQueryValue(val));
            }
        }
        return executeLocationQuery(spec);
    }

    private String normalizeQueryValue(Object value) {
        if (value instanceof List) {
            List<String> list = new ArrayList<>();
            for (Object item : (List<?>) value) {
                if (item != null && !item.toString().trim().isEmpty()) {
                    list.add(item.toString().trim());
                }
            }
            return String.join(",", list);
        }
        return value.toString().trim();
    }

    private Integer parseQueryLimit(Object value) {
        try {
            int parsed = (value instanceof Number)
                    ? ((Number) value).intValue()
                    : Integer.parseInt(value.toString().trim());
            return clampLimit(parsed, DEFAULT_LIST_LIMIT);
        } catch (Exception e) {
            return DEFAULT_LIST_LIMIT;
        }
    }

    private List<String> readLocationCodesForQuery(String value) {
        List<String> list = new ArrayList<>();
        if (hasText(value)) {
            for (String item : value.split(",")) {
                String code = item.trim().toUpperCase();
                if (!code.isEmpty()) {
                    list.add(code);
                }
            }
        }
        return list;
    }

    private Map<String, Object> emptyQueryResponse() {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("count", 0);
        res.put("results", new ArrayList<>());
        return res;
    }

    private QueryDimensionRegistry buildQueryDimensionRegistry() {
        QueryDimensionRegistry registry = new QueryDimensionRegistry();
        String gisDb = AppConfig.GISdbName();
        registry.register(new QueryDimension("locName", (q, v)
                -> q.addPredicate(new QueryPredicate("UPPER(g.LOC_NAME) LIKE ?", Collections.singletonList("%" + v.toUpperCase() + "%")))));
        registry.register(new QueryDimension("location", (q, v) -> {
            String p = "%" + v.toUpperCase() + "%";
            q.addPredicate(new QueryPredicate("(UPPER(g.LOC_NAME) LIKE ? OR UPPER(g.ADDRESS) LIKE ?)", Arrays.asList(p, p)));
        }));
        registry.register(new QueryDimension("psm", (q, v) -> {
            if (v == null || v.trim().isEmpty()) {
                return;
            }
            // Normalize: Treat "PSM/123" or "123" as "123"
            String term = v.trim().toUpperCase(Locale.ROOT);
            int slashIndex = term.indexOf('/');
            if (slashIndex >= 0) {
                term = term.substring(slashIndex + 1).trim();
            }
            // Build parameterized pattern
            String pattern = "PSM/" + escapeLikeWildcards(term) + "%";
            q.addPredicate(new QueryPredicate(
                    "UPPER(RTRIM(g.PSM)) LIKE ? ESCAPE '\\'",
                    Collections.singletonList(pattern)
            ));
        }));
        registry.register(new QueryDimension("deptCd", (q, v)
                -> q.addPredicate(new QueryPredicate("UPPER(LTRIM(RTRIM(g.DEPT_CD))) = ?", Collections.singletonList(v.toUpperCase())))));
        registry.register(new QueryDimension("locCd", (q, v)
                -> q.addPredicate(new QueryPredicate("g.LOC_CD = ?", Collections.singletonList(v.toUpperCase())))));
        registry.register(new QueryDimension("locCds", (q, v) -> {
            List<String> codes = readLocationCodesForQuery(v);
            if (codes.isEmpty()) {
                return;
            }
            String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
            q.addPredicate(new QueryPredicate("g.LOC_CD IN (" + placeholders + ")", new ArrayList<>(codes)));
        }));
        registry.register(new QueryDimension("grade", (q, v) -> {
            if (!hasText(gisDb) || !hasText(v)) {
                q.addPredicate(new QueryPredicate("1 = 0", Collections.emptyList()));
                return;
            }

            String table = gisDb + ".sde.T_ASD_COMBINED";
            String grade = v.trim().toUpperCase(Locale.ROOT);

            if ("ALL".equals(grade) || "GRADED".equals(grade)) {
                q.addPredicate(new QueryPredicate(
                        "EXISTS (SELECT 1 FROM " + table + " h "
                        + "WHERE h.LOC_CD = g.LOC_CD "
                        + "AND h.GRD_HIST_BLDG IS NOT NULL "
                        + "AND LTRIM(RTRIM(h.GRD_HIST_BLDG)) NOT IN ('', '0'))",
                        Collections.emptyList()));
            } else if (Arrays.asList("1", "2", "3").contains(grade)) {
                q.addPredicate(new QueryPredicate(
                        "EXISTS (SELECT 1 FROM " + table + " h "
                        + "WHERE h.LOC_CD = g.LOC_CD "
                        + "AND LTRIM(RTRIM(h.GRD_HIST_BLDG)) = ?)",
                        Collections.<Object>singletonList(grade)));
            } else if ("NONE".equals(grade) || "0".equals(grade)) {
                q.addPredicate(new QueryPredicate(
                        "NOT EXISTS (SELECT 1 FROM " + table + " h "
                        + "WHERE h.LOC_CD = g.LOC_CD "
                        + "AND h.GRD_HIST_BLDG IS NOT NULL "
                        + "AND LTRIM(RTRIM(h.GRD_HIST_BLDG)) NOT IN ('', '0'))",
                        Collections.emptyList()));
            } else {
                q.addPredicate(new QueryPredicate("1 = 0", Collections.emptyList()));
            }
        }));

        registry.register(new QueryDimension("filter", (q, v) -> {
            if (!hasText(gisDb) || !hasText(v)) {
                q.addPredicate(new QueryPredicate("1 = 0", Collections.emptyList()));
                return;
            }

            String table = gisDb + ".sde.T_ASD_COMBINED";
            String filter = v.trim().toUpperCase(Locale.ROOT);
            String monumentExists
                    = "EXISTS (SELECT 1 FROM " + table + " h "
                    + "WHERE h.LOC_CD = g.LOC_CD "
                    + "AND UPPER(LTRIM(RTRIM(h.DECLR_MONUMT))) = 'T')";

            if ("T".equals(filter)) {
                q.addPredicate(new QueryPredicate(monumentExists, Collections.emptyList()));
            } else if ("F".equals(filter)) {
                q.addPredicate(new QueryPredicate("NOT " + monumentExists, Collections.emptyList()));
            } else if (!"ALL".equals(filter)) {
                q.addPredicate(new QueryPredicate("1 = 0", Collections.emptyList()));
            }
        }));
        QueryDimension reportDimension = new QueryDimension("reportType", (q, v) -> {
            if (!hasText(v)) {
                return;
            }
            List<String> reportTypes;
            try {
                reportTypes = ReportTypeRegistry.normalizeReportTypes(v);
            } catch (IllegalArgumentException e) {
                q.addPredicate(new QueryPredicate("1 = 0", Collections.emptyList()));
                return;
            }
            for (String type : reportTypes) {
                ReportTypeRegistry.ReportType report = ReportTypeRegistry.getByShortName(type);
                if (report == null || !report.hasAvailabilityTable()) {
                    q.addPredicate(new QueryPredicate("1 = 0", Collections.emptyList()));
                    return;
                }
                String sql = String.format(
                        "EXISTS (SELECT 1 FROM %s r WHERE r.LOC_CD = g.LOC_CD)",
                        report.getTableName());
                q.addPredicate(new QueryPredicate(sql, Collections.emptyList()));
            }
        });
        registry.register(reportDimension);
        // Re-use the logic for the secondary dimension
        registry.register(new QueryDimension("requiredReports", (q, v) -> reportDimension.apply(q, v)));
        return registry;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
