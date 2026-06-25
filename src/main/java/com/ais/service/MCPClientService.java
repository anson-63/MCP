package com.ais.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ais.db.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;

public class MCPClientService {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(MCPClientService.class);
    private final DatabaseManager db;

    // ══════════════════════════════════════════════════════════════
    // TOOL DISPATCH TABLE
    // Maps tool name → handler function.
    // Adding a new tool = one new entry here + one tool definition.
    // ══════════════════════════════════════════════════════════════
    private final Map<String, Function<Map<String, Object>, Object>> handlers
            = new LinkedHashMap<String, Function<Map<String, Object>, Object>>();

    public MCPClientService() {
        this.db = DatabaseManager.getInstance();
        registerHandlers();
    }

    // ── Register all tool handlers ────────────────────────────────
    private void registerHandlers() {

        // ── hardcode_query ────────────────────────────────────────
        handlers.put("hardcode_query", args -> {
            String locCd = getArg(args, "locCd", null);

            // Auto-fallback: if value doesn't look like a code → name search
            if (locCd != null && !locCd.matches("(?i)^[A-Z]{2}\\d{11}$")) {
                log.info("'{}' is not a code — falling back to name search", locCd);
                List<Map<String, Object>> results = db.searchByName(locCd, null);
                return responseOf("count", results.size(), "results", results);
            }

            return db.getFullLocationInfo(locCd);
        });

        // ── search_by_name ────────────────────────────────────────
        handlers.put("search_by_name", args -> {
            String locName  = getArg(args, "locName",  null);
            String location = getArg(args, "location", null);

            List<Map<String, Object>> results = db.searchByName(locName, location);
            return responseOf(
                "count",    results.size(),
                "results",  results,
                "location", location   // echo back for formatAsHtml
            );
        });

        // ── show_schema / refresh_schema ──────────────────────────
        handlers.put("show_schema",    args -> db.introspectSchema());
        handlers.put("refresh_schema", args -> db.introspectSchema());

        // ── check_reports ─────────────────────────────────────────
        handlers.put("check_reports", args -> {
            String reportType = getArg(args, "reportType", "BSI").toUpperCase();

            List<String> locCds = new ArrayList<String>();
            Object cdsObj = args.get("locCds");
            if (cdsObj instanceof List) {
                for (Object o : (List<?>) cdsObj) {
                    if (o != null) locCds.add(o.toString().trim().toUpperCase());
                }
            }

            log.info("check_reports: type={} count={}", reportType, locCds.size());
            return db.checkReportsForLocations(reportType, locCds);
        });

        // ── list_psms ─────────────────────────────────────────────
        handlers.put("list_psms", args -> {
            List<Map<String, Object>> psms = db.getDistinctPsms();
            int totalLocations = psms.stream()
                    .mapToInt(p -> (Integer) p.get("count")).sum();
            return responseOf(
                "totalPsms",       psms.size(),
                "totalLocations",  totalLocations,
                "psms",            psms
            );
        });

        // ── locations_by_psm ──────────────────────────────────────
        handlers.put("locations_by_psm", args -> {
            String psm      = getArg(args, "psm",      "");
            String location = getArg(args, "location", null);

            List<Map<String, Object>> results = db.getLocationsByPsm(psm, location);
            return responseOf(
                "psm",      psm,
                "count",    results.size(),
                "results",  results,
                "location", location
            );
        });

        // ── locations_by_dept ─────────────────────────────────────
        handlers.put("locations_by_dept", args -> {
            String deptCd   = getArg(args, "deptCd",   "").toUpperCase().trim();
            String location = getArg(args, "location", null);

            List<Map<String, Object>> results = db.getLocationsByDept(deptCd, location);
            return responseOf(
                "deptCd",   deptCd,
                "count",    results.size(),
                "results",  results,
                "location", location
            );
        });

        // ── search_declared_monument ──────────────────────────────
        handlers.put("search_declared_monument", args -> {
            String filter   = getArg(args, "filter",   "T").toUpperCase().trim();
            String location = getArg(args, "location", null);

            List<Map<String, Object>> results = db.getDeclaredMonuments(filter, location);
            return responseOf(
                "filter",   filter,
                "count",    results.size(),
                "results",  results,
                "location", location
            );
        });

        // ── search_historic_building ──────────────────────────────
        handlers.put("search_historic_building", args -> {
            String grade    = getArg(args, "grade",    "ALL").toUpperCase().trim();
            String location = getArg(args, "location", null);

            List<Map<String, Object>> results = db.getHistoricBuildings(grade, location);
            return responseOf(
                "grade",    grade,
                "count",    results.size(),
                "results",  results,
                "location", location
            );
        });

        // ── search_loc_cd_history ─────────────────────────────────
        handlers.put("search_loc_cd_history", args -> {
            String formerCd  = getArg(args, "formerLocCd",  "").toUpperCase().trim();
            String currentCd = getArg(args, "currentLocCd", "").toUpperCase().trim();

            List<Map<String, Object>> results =
                    db.getLocCdChangeHistory(formerCd, currentCd);

            Map<String, Object> response = new LinkedHashMap<String, Object>();
            if (!formerCd.isEmpty())  response.put("formerLocCd",  formerCd);
            if (!currentCd.isEmpty()) response.put("currentLocCd", currentCd);
            response.put("count",   results.size());
            response.put("results", results);
            return response;
        });
    }

    // ══════════════════════════════════════════════════════════════
    // MAIN DISPATCH — single entry point for all tool calls
    // ══════════════════════════════════════════════════════════════
    public String callTool(String toolName, Map<String, Object> args) {
        log.info("Calling tool: {} with args: {}", toolName, args);

        try {
            Function<Map<String, Object>, Object> handler = handlers.get(toolName);

            if (handler == null) {
                log.error("Unknown tool: {}", toolName);
                return errorJson("Unknown tool: " + toolName
                        + ". Available: " + handlers.keySet());
            }

            Object result = handler.apply(args);
            return toJson(result);

        } catch (Exception e) {
            log.error("Tool '{}' failed: {}", toolName, e.getMessage(), e);
            return errorJson(e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // TOOL DEFINITIONS
    // Each tool definition is a ToolDef record.
    // Adding a new tool = one new ToolDef + one handler entry.
    // ══════════════════════════════════════════════════════════════
    private static class ToolDef {
        final String name;
        final String description;
        final Map<String, Object> properties;
        final List<String> required;
        // UI metadata
        final String icon;
        final String samplePrompt;
        final boolean needsInput;
        final String inputHint;
        final String placeholder;

        ToolDef(String name, String description,
                Map<String, Object> properties, List<String> required,
                String icon, String samplePrompt, boolean needsInput,
                String inputHint, String placeholder) {
            this.name         = name;
            this.description  = description;
            this.properties   = properties;
            this.required     = required;
            this.icon         = icon;
            this.samplePrompt = samplePrompt;
            this.needsInput   = needsInput;
            this.inputHint    = inputHint;
            this.placeholder  = placeholder;
        }
    }

    // ── Build tool definitions list ───────────────────────────────
    private List<ToolDef> getToolDefs() {
        List<ToolDef> defs = new ArrayList<ToolDef>();

        // ── hardcode_query ────────────────────────────────────────
        defs.add(new ToolDef(
            "hardcode_query",
            "Get full location details AND list of available reports "
            + "(survey, maintenance, inspection, repair, photo) for a specific "
            + "location code (e.g., 'SB04400361000').",
            props("locCd", prop("string",
                "Location Code (format: SB followed by digits, e.g., SB04400361000)")),
            list("locCd"),
            "📋", "Get info for ", true, "location code", "e.g., SB04400361000"
        ));

        // ── search_by_name ────────────────────────────────────────
        defs.add(new ToolDef(
            "search_by_name",
            "Search for locations by name when user provides a name instead of "
            + "a code (e.g., 'Sha Tin Park', 'hospital', 'school'). Returns matching "
            + "locations with their codes. Use this when input is human-readable text.",
            props("locName", prop("string",
                    "Location name or partial name (e.g., 'Sha Tin Park', 'park')"),
                  "location", prop("string",
                    "Optional district/area filter (e.g., 'Sha Tin', 'Lo Wu')")),
            list("locName"),
            "🔍", "Search location named ", true, "location name", "e.g., Sha Tin Park"
        ));

        // ── show_schema ───────────────────────────────────────────
        defs.add(new ToolDef(
            "show_schema",
            "Show what tables and columns exist in the database",
            props(), list(),
            "🗄️", "Show database schema", false, null, null
        ));

        // ── check_reports ─────────────────────────────────────────
        Map<String, Object> reportTypeProp = prop("string",
                "Report type to check. Must be one of: BSI, CSR, KAI, EMMS, DSSR");
        reportTypeProp.put("enum", Arrays.asList("BSI", "CSR", "KAI", "EMMS", "DSSR"));

        Map<String, Object> locCdsProp = new LinkedHashMap<String, Object>();
        locCdsProp.put("type", "array");
        Map<String, Object> itemSchema = new HashMap<String, Object>();
        itemSchema.put("type", "string");
        locCdsProp.put("items", itemSchema);
        locCdsProp.put("description",
                "List of location codes to check (e.g., ['SB04400361000'])");

        defs.add(new ToolDef(
            "check_reports",
            "Check which locations from a list have a specific report available. "
            + "Returns clickable report links for locations that have them.",
            props("reportType", reportTypeProp, "locCds", locCdsProp),
            list("reportType", "locCds"),
            "📄", "Check BSI reports for ", true, "location codes",
            "e.g., SB04400361000, SC04400206005"
        ));

        // ── list_psms ─────────────────────────────────────────────
        defs.add(new ToolDef(
            "list_psms",
            "List all distinct PSM (Property Service Manager) values with the count "
            + "of locations under each.",
            props(), list(),
            "👥", "List all PSMs", false, null, null
        ));

        // ── locations_by_psm ──────────────────────────────────────
        defs.add(new ToolDef(
            "locations_by_psm",
            "Get list of locations managed by a specific PSM. "
            + "Use after list_psms when user wants to see which locations a PSM manages.",
            props("psm", prop("string", "PSM name (e.g., 'SHA TIN EAST')"),
                  "location", prop("string",
                    "Optional district filter (e.g., 'Sha Tin')")),
            list("psm"),
            "📌", "Show locations under PSM ", true, "PSM name", "e.g., SHA TIN EAST"
        ));

        // ── locations_by_dept ─────────────────────────────────────
        defs.add(new ToolDef(
            "locations_by_dept",
            "Get locations owned/used by a specific department. "
            + "Use when user asks 'which locations belong to AFCD', "
            + "'show LCSD buildings', 'list HD properties', etc.",
            props("deptCd", prop("string",
                    "Department code (e.g., 'AFCD', 'LCSD', 'HD', 'DSD')"),
                  "location", prop("string",
                    "Optional district filter (e.g., 'Sha Tin')")),
            list("deptCd"),
            "🏢", "Show locations for department ", true, "department code",
            "e.g., AFCD, LCSD, HD"
        ));

        // ── search_declared_monument ──────────────────────────────
        Map<String, Object> filterProp = prop("string",
                "Filter: 'T' for declared monuments, 'F' for non-monuments, "
                + "'ALL' for both. Default is 'T'.");
        filterProp.put("enum", Arrays.asList("T", "F", "ALL"));

        defs.add(new ToolDef(
            "search_declared_monument",
            "Search locations that are declared monuments. "
            + "Filter can be T (monuments), F (non-monuments), ALL (both). "
            + "Optionally filter by location name.",
            props("filter",   filterProp,
                  "location", prop("string",
                    "Optional district filter (e.g., 'Sha Tin', 'Lo Wu')")),
            list(),
            "🏛️", "Show declared monuments ", true, "T / F / ALL",
            "e.g., T for monuments"
        ));

        // ── search_historic_building ──────────────────────────────
        defs.add(new ToolDef(
            "search_historic_building",
            "Search locations that are graded historic buildings. "
            + "Grade can be 1, 2, 3, ALL, or NONE. "
            + "Optionally filter by location name.",
            props("grade",    prop("string",
                    "Grade of historic building: '1', '2', '3', or 'ALL'. "
                    + "Use '0' or 'NONE' for non-graded buildings."),
                  "location", prop("string",
                    "Optional district filter (e.g., 'Sha Tin')")),
            list(),
            "🏰", "Show historic buildings grade ", true, "grade (1/2/3/ALL)",
            "e.g., 1, 2, 3, ALL"
        ));

        // ── search_loc_cd_history ─────────────────────────────────
        defs.add(new ToolDef(
            "search_loc_cd_history",
            "Look up location code change history. Use when user asks "
            + "'what is the new code for UD04400253000', "
            + "'what was the old code for UC04400251000', "
            + "'location code change history', 'former code', 'previous code', etc. "
            + "Provide either formerLocCd or currentLocCd (or both).",
            props("formerLocCd",  prop("string",
                    "Former location code to look up (e.g., 'UD04400253000'). "
                    + "Returns the current location code(s)."),
                  "currentLocCd", prop("string",
                    "Current location code to look up (e.g., 'UC04400251000'). "
                    + "Returns former location code(s).")),
            list(),
            "📜", "Search location code history for ", true, "location code",
            "e.g., UD04400253000"
        ));

        return defs;
    }

    // ══════════════════════════════════════════════════════════════
    // PUBLIC API — List tools (for LLM system prompt)
    // ══════════════════════════════════════════════════════════════
    public List<Map<String, Object>> listTools() {
        List<Map<String, Object>> tools = new ArrayList<Map<String, Object>>();

        for (ToolDef def : getToolDefs()) {
            Map<String, Object> schema = new LinkedHashMap<String, Object>();
            schema.put("type",       "object");
            schema.put("properties", def.properties);
            schema.put("required",   def.required);

            Map<String, Object> function = new LinkedHashMap<String, Object>();
            function.put("name",        def.name);
            function.put("description", def.description);
            function.put("parameters",  schema);

            Map<String, Object> ui = new LinkedHashMap<String, Object>();
            ui.put("icon",         def.icon);
            ui.put("samplePrompt", def.samplePrompt);
            ui.put("needsInput",   def.needsInput);
            ui.put("inputHint",    def.inputHint);
            ui.put("placeholder",  def.placeholder);

            Map<String, Object> tool = new LinkedHashMap<String, Object>();
            tool.put("type",     "function");
            tool.put("function", function);
            tool.put("ui",       ui);

            tools.add(tool);
        }

        return tools;
    }

    // ── UI tool list ──────────────────────────────────────────────
    public List<Map<String, Object>> listToolsForUI() {
        List<Map<String, Object>> uiTools = new ArrayList<Map<String, Object>>();

        for (ToolDef def : getToolDefs()) {
            Map<String, Object> uiTool = new LinkedHashMap<String, Object>();
            uiTool.put("name",         def.name);
            uiTool.put("description",  def.description);
            uiTool.put("icon",         def.icon);
            uiTool.put("samplePrompt", def.samplePrompt);
            uiTool.put("needsInput",   def.needsInput);
            uiTool.put("inputHint",    def.inputHint);
            uiTool.put("placeholder",  def.placeholder);
            uiTools.add(uiTool);
        }

        return uiTools;
    }

    // ══════════════════════════════════════════════════════════════
    // BUILDER HELPERS
    // ══════════════════════════════════════════════════════════════

    // ── Build a single property ───────────────────────────────────
    private Map<String, Object> prop(String type, String description) {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("type",        type);
        p.put("description", description);
        return p;
    }

    // ── Build properties map from alternating key/value pairs ─────
    // props("key1", prop1, "key2", prop2, ...)
    private Map<String, Object> props(Object... keyValuePairs) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValuePairs.length - 1; i += 2) {
            map.put(keyValuePairs[i].toString(), keyValuePairs[i + 1]);
        }
        return map;
    }

    // ── Build required list ───────────────────────────────────────
    private List<String> list(String... items) {
        return new ArrayList<String>(Arrays.asList(items));
    }

    // ── Build a standard response map ────────────────────────────
    // responseOf("count", 5, "results", list, ...)
    private Map<String, Object> responseOf(Object... keyValuePairs) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValuePairs.length - 1; i += 2) {
            Object val = keyValuePairs[i + 1];
            if (val != null) {  // skip null values (e.g., location=null)
                map.put(keyValuePairs[i].toString(), val);
            }
        }
        return map;
    }

    // ── Get arg safely ────────────────────────────────────────────
    private String getArg(Map<String, Object> args, String key, String defaultVal) {
        Object val = args.get(key);
        return (val != null && !val.toString().trim().isEmpty())
                ? val.toString().trim() : defaultVal;
    }

    // ── Serialize to JSON ─────────────────────────────────────────
    private String toJson(Object obj) {
        try {
            return mapper.writerWithDefaultPrettyPrinter()
                         .writeValueAsString(obj);
        } catch (Exception e) {
            log.error("JSON serialization failed: {}", e.getMessage());
            return errorJson("JSON serialization failed: " + e.getMessage());
        }
    }

    // ── Error JSON ────────────────────────────────────────────────
    private String errorJson(String message) {
        return "{\"error\": \"" + message.replace("\"", "'") + "\"}";
    }
}