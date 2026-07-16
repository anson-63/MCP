package com.ais.mcp;

import com.ais.db.DatabaseManager;
import com.ais.service.ReportTypeRegistry;
import com.ais.service.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.Locale;

/**
 * Builds the in-process tool catalog.
 *
 * This class intentionally keeps the existing anonymous executors together
 * during the first refactor phase. They can later be moved into separate
 * domain providers without changing MCPClientService or OllamaService.
 */
public final class ToolRegistryFactory {

    private static final String LOCATION_CODE_REGEX = "(?i)^[A-Z]{2}\\d{11}$";

    private ToolRegistryFactory() {
    }

    public static ToolCatalog create(DatabaseManager db,ObjectMapper mapper) {
        if (db == null) {
            throw new IllegalArgumentException("db must not be null");
        }

        ToolCatalog catalog = new ToolCatalog();
        registerTools(catalog, db);
        return catalog;
    }

    private static void registerTools(ToolCatalog catalog,final DatabaseManager db) {

        registerTool(
                catalog,
                definition(
                        "hardcode_query",
                        set("LOCATION_CODE"),
                        set("HARDCODE_QUERY"),
                        set("locCd"),
                        set("independent", "use_previous_codes"),
                        set("LOC_CD", "LOC_NAME"),
                        set("LOC_CD")
                ),
                "Get full location details and available reports for a location code.",
                properties(
                        "locCd",
                        property(
                                "string",
                                "Location code such as SB04400361000"
                        )
                ),
                ui(
                        "",
                        "Get info for ",
                        true,
                        "location code",
                        "e.g. SB04400361000"
                ),
                new Function<Map<String, Object>, Object>() {
                    @Override
                    public Object apply(
                            Map<String, Object> args) {

                        String locCd = getArg(
                                args,
                                "locCd",
                                null
                        );

                        if (!hasText(locCd)) {
                            throw new IllegalArgumentException(
                                    "locCd is required"
                            );
                        }

                        if (!locCd.matches(
                                LOCATION_CODE_REGEX
                        )) {
                            List<Map<String, Object>> results =
                                    db.searchByName(
                                            locCd,
                                            null
                                    );

                            return responseOf(
                                    "count",
                                    results.size(),
                                    "results",
                                    results
                            );
                        }

                        return db.getFullLocationInfo(locCd);
                    }
                }
        );

        registerTool(
                catalog,
                definition(
                        "search_by_name",
                        set("NAME_SEARCH"),
                        set("SEARCH_BY_NAME"),
                        set("locName"),
                        set("independent", "filter_previous"),
                        set("LOC_CD", "LOC_NAME", "ADDRESS")
                ),
                "Search locations by name or partial name.",
                properties(
                        "locName",
                        property(
                                "string",
                                "Location name or partial name"
                        ),
                        "location",
                        property(
                                "string",
                                "Optional district or area"
                        ),
                        "limit",
                        limitProperty(),
                        "excludeUndefinedField",
                        excludeUndefinedProperty()
                ),
                ui(
                        "",
                        "Search location named ",
                        true,
                        "location name",
                        "e.g. Sha Tin Park"
                ),
                new Function<Map<String, Object>, Object>() {
                    @Override
                    public Object apply(
                            Map<String, Object> args) {

                        String locName = getArg(
                                args,
                                "locName",
                                null
                        );

                        if (!hasText(locName)) {
                            throw new IllegalArgumentException(
                                    "locName is required"
                            );
                        }

                        String location = getArg(
                                args,
                                "location",
                                null
                        );

                        List<Map<String, Object>> results =
                                db.searchByName(
                                        locName,
                                        location,
                                        getIntArg(args, "limit"),
                                        getArg(
                                                args,
                                                "excludeUndefinedField",
                                                null
                                        )
                                );

                        return responseOf(
                                "count",
                                results.size(),
                                "results",
                                results,
                                "location",
                                location
                        );
                    }
                }
        );

        registerTool(
                catalog,
                definition(
                        "check_reports",
                        set("CHECK_REPORTS"),
                        set("REPORT"),
                        set("reportType", "locCds"),
                        set(
                                "independent",
                                "use_previous_codes",
                                "filter_previous"
                        ),
                        set("LOC_CD"),
                        set("LOC_CD")
                ),
                "Check report availability for location codes.",
                properties(
                        "reportType",
                        reportTypeProperty(),
                        "locCds",
                        locationCodesProperty()
                ),
                ui(
                        "",
                        "Check BSI reports for ",
                        true,
                        "location codes",
                        "e.g. SB04400361000, SC04400206005"
                ),
                new Function<Map<String, Object>, Object>() {
                    @Override
                    public Object apply(
                            Map<String, Object> args) {

                        String reportType = getArg(
                                args,
                                "reportType",
                                null
                        );

                        if (!hasText(reportType)) {
                            throw new IllegalArgumentException(
                                    "reportType is required"
                            );
                        }

                        List<String> codes =
                                readLocationCodes(
                                        args.get("locCds")
                                );

                        if (codes.isEmpty()) {
                            throw new IllegalArgumentException(
                                    "locCds is required"
                            );
                        }

                        return db.checkReportsForLocations(
                                reportType.toUpperCase(Locale.ROOT),
                                codes
                        );
                    }
                }
        );

        registerTool(
                catalog,
                definition(
                        "list_psms",
                        set("PSM_LIST"),
                        set("PSM", "LIST_PSMS"),
                        set(),
                        set("independent"),
                        set("PSM")
                ),
                "List distinct Property Service Managers.",
                properties(),
                ui(
                        "",
                        "List all PSMs",
                        false,
                        null,
                        null
                ),
                new Function<Map<String, Object>, Object>() {
                    @Override
                    public Object apply(
                            Map<String, Object> args) {

                        List<Map<String, Object>> psms =
                                db.getDistinctPsms();

                        int totalLocations = 0;

                        for (Map<String, Object> row : psms) {
                            Object count = row.get("count");

                            if (count instanceof Number) {
                                totalLocations +=
                                        ((Number) count).intValue();
                            }
                        }

                        return responseOf(
                                "totalPsms",
                                psms.size(),
                                "totalLocations",
                                totalLocations,
                                "psms",
                                psms
                        );
                    }
                }
        );

        registerTool(
                catalog,
                definition(
                        "locations_by_psm",
                        set("PSM_LOCATIONS"),
                        set("PSM"),
                        set("psm"),
                        set("independent", "filter_previous"),
                        set("LOC_CD", "LOC_NAME", "ADDRESS")
                ),
                "List locations managed by a PSM.",
                properties(
                        "psm",
                        property(
                                "string",
                                "PSM name"
                        ),
                        "location",
                        property(
                                "string",
                                "Optional district or area"
                        ),
                        "limit",
                        limitProperty(),
                        "excludeUndefinedField",
                        excludeUndefinedProperty()
                ),
                ui(
                        "",
                        "Show locations under PSM ",
                        true,
                        "PSM name",
                        "e.g. SHA TIN EAST"
                ),
                new Function<Map<String, Object>, Object>() {
                    @Override
                    public Object apply(
                            Map<String, Object> args) {

                        String psm = getArg(
                                args,
                                "psm",
                                null
                        );

                        if (!hasText(psm)) {
                            throw new IllegalArgumentException(
                                    "psm is required"
                            );
                        }

                        List<Map<String, Object>> results =
                                db.getLocationsByPsm(
                                        psm,
                                        getArg(args, "location", null),
                                        getIntArg(args, "limit"),
                                        getArg(
                                                args,
                                                "excludeUndefinedField",
                                                null
                                        )
                                );

                        return responseOf(
                                "psm",
                                psm,
                                "count",
                                results.size(),
                                "results",
                                results
                        );
                    }
                }
        );

        registerTool(
                catalog,
                definition(
                        "locations_by_dept",
                        set("DEPARTMENT_LOCATIONS"),
                        set("DEPARTMENT"),
                        set("deptCd"),
                        set("independent", "filter_previous"),
                        set("LOC_CD", "LOC_NAME", "ADDRESS", "DEPT_CD")
                ),
                "List locations belonging to a department.",
                properties(
                        "deptCd",
                        property(
                                "string",
                                "Department code"
                        ),
                        "location",
                        property(
                                "string",
                                "Optional district or area"
                        ),
                        "limit",
                        limitProperty(),
                        "excludeUndefinedField",
                        excludeUndefinedProperty()
                ),
                ui(
                        "",
                        "Show locations for department ",
                        true,
                        "department code",
                        "e.g. AFCD"
                ),
                new Function<Map<String, Object>, Object>() {
                    @Override
                    public Object apply(
                            Map<String, Object> args) {

                        String deptCd = getArg(
                                args,
                                "deptCd",
                                null
                        );

                        if (!hasText(deptCd)) {
                            throw new IllegalArgumentException(
                                    "deptCd is required"
                            );
                        }

                        String normalizedDept =
                                deptCd.toUpperCase();

                        List<Map<String, Object>> results =
                                db.getLocationsByDept(
                                        normalizedDept,
                                        getArg(args, "location", null),
                                        getIntArg(args, "limit"),
                                        getArg(
                                                args,
                                                "excludeUndefinedField",
                                                null
                                        )
                                );

                        return responseOf(
                                "deptCd",
                                normalizedDept,
                                "count",
                                results.size(),
                                "results",
                                results
                        );
                    }
                }
        );

        registerTool(
                catalog,
                definition(
                        "search_declared_monument",
                        set("DECLARED_MONUMENT"),
                        set("MONUMENT"),
                        set(),
                        set(
                                "independent",
                                "filter_previous",
                                "enrich_previous"
                        ),
                        set("LOC_CD", "LOC_NAME", "ADDRESS")
                ),
                "Search declared monuments.",
                properties(
                        "filter",
                        filterProperty(),
                        "location",
                        property(
                                "string",
                                "Optional district or area"
                        ),
                        "limit",
                        limitProperty(),
                        "excludeUndefinedField",
                        excludeUndefinedProperty()
                ),
                ui(
                        "",
                        "Show declared monuments ",
                        true,
                        "T / F / ALL",
                        "e.g. T"
                ),
                new Function<Map<String, Object>, Object>() {
                    @Override
                    public Object apply(
                            Map<String, Object> args) {

                        String filter = getArg(
                                args,
                                "filter",
                                "T"
                        ).toUpperCase();

                        List<Map<String, Object>> results =
                                db.getDeclaredMonuments(
                                        filter,
                                        getArg(args, "location", null),
                                        getIntArg(args, "limit"),
                                        getArg(
                                                args,
                                                "excludeUndefinedField",
                                                null
                                        )
                                );

                        return responseOf(
                                "filter",
                                filter,
                                "count",
                                results.size(),
                                "results",
                                results
                        );
                    }
                }
        );

        registerTool(
                catalog,
                definition(
                        "search_historic_building",
                        set("HISTORIC_BUILDING"),
                        set("HISTORIC"),
                        set(),
                        set(
                                "independent",
                                "filter_previous",
                                "enrich_previous"
                        ),
                        set(
                                "LOC_CD",
                                "LOC_NAME",
                                "ADDRESS",
                                "GRD_HIST_BLDG"
                        )
                ),
                "Search historic buildings by grade.",
                properties(
                        "grade",
                        property(
                                "string",
                                "Grade 1, 2, 3, ALL, or NONE"
                        ),
                        "location",
                        property(
                                "string",
                                "Optional district or area"
                        ),
                        "limit",
                        limitProperty(),
                        "excludeUndefinedField",
                        excludeUndefinedProperty()
                ),
                ui(
                        "",
                        "Show historic buildings grade ",
                        true,
                        "grade",
                        "e.g. 1, 2, 3, ALL"
                ),
                new Function<Map<String, Object>, Object>() {
                    @Override
                    public Object apply(
                            Map<String, Object> args) {

                        String grade = getArg(
                                args,
                                "grade",
                                "ALL"
                        ).toUpperCase();

                        List<Map<String, Object>> results =
                                db.getHistoricBuildings(
                                        grade,
                                        getArg(args, "location", null),
                                        getIntArg(args, "limit"),
                                        getArg(
                                                args,
                                                "excludeUndefinedField",
                                                null
                                        )
                                );

                        return responseOf(
                                "grade",
                                grade,
                                "count",
                                results.size(),
                                "results",
                                results
                        );
                    }
                }
        );

        registerTool(
                catalog,
                definition(
                        "search_loc_cd_history",
                        set("CODE_HISTORY"),
                        set("SEARCH_LOC_CD_HISTORY"),
                        set(),
                        set("independent"),
                        set("CURRENT_LOC_CD", "FORMER_LOC_CD")
                ),
                "Search location-code history.",
                properties(
                        "formerLocCd",
                        property(
                                "string",
                                "Former location code"
                        ),
                        "currentLocCd",
                        property(
                                "string",
                                "Current location code"
                        )
                ),
                ui(
                        "",
                        "Search location code history for ",
                        true,
                        "location code",
                        "e.g. UD04400253000"
                ),
                new Function<Map<String, Object>, Object>() {
                    @Override
                    public Object apply(Map<String, Object> args) {
                        String formerCd = getArg(args,"formerLocCd","").toUpperCase();
                        String currentCd = getArg(args,"currentLocCd","").toUpperCase();

                        if (formerCd.isEmpty()&& currentCd.isEmpty()) {
                            throw new IllegalArgumentException(
                                    "formerLocCd or currentLocCd is required"
                            );
                        }

                        List<Map<String, Object>> results =
                                db.getLocCdChangeHistory(formerCd,currentCd);

                        return responseOf(
                                "formerLocCd",
                                formerCd,
                                "currentLocCd",
                                currentCd,
                                "count",
                                results.size(),
                                "results",
                                results
                        );
                    }
                }
        );

        registerTool(
                catalog,
                definition(
                        "location_query",
                        set("LOCATION_QUERY"),
                        set("QUERY_LOCATIONS"),
                        set(),
                        set("independent"),
                        set("LOC_CD", "LOC_NAME", "ADDRESS", "DEPT_CD")
                ),
                "Execute a composed location query using multiple filters.",
                properties(
                        "locName",
                        property("string", "Optional location name"),
                        "location",
                        property("string", "Optional district or area"),
                        "psm",
                        property("string", "Optional PSM"),
                        "deptCd",
                        property("string", "Optional department code"),
                        "grade",
                        property("string", "Optional historic grade"),
                        "filter",
                        filterProperty(),
                        "reportType",
                        property(
                                "string",
                                "Required report type(s), comma-separated, e.g. BSI,KAI"
                        ),
                        "locCd",
                        property("string", "Optional location code"),
                        "locCds",
                        locationCodesProperty(),
                        "limit",
                        limitProperty(),
                        "excludeUndefinedField",
                        excludeUndefinedProperty()
                ),
                ui(
                        "",
                        "Search locations with filters",
                        true,
                        "Location filters",
                        "e.g. AFCD locations with BSI"
                ),
                new Function<Map<String, Object>, Object>() {
                    @Override
                    public Object apply(Map<String, Object> args) {
                        return db.executeLocationQuery(args);
                    }
                }
        );
    }

    private static void registerTool(
            ToolCatalog catalog, ToolDefinition definition,
            String description,Map<String, Object> properties,Map<String, Object> ui,
            Function<Map<String, Object>, Object> executor) {

        ToolProvider provider =
                new ToolProvider() {
                    @Override
                    public ToolDefinition getDefinition() {
                        return definition;
                    }

                    @Override
                    public Object execute(
                            Map<String, Object> args) {
                        return executor.apply(args);
                    }
                };

        catalog.register(
                new ToolRegistration(
                        definition,
                        description,
                        properties,
                        ui,
                        provider
                )
        );
    }

    private static ToolDefinition definition(
            String toolName,
            Set<String> intentTypes,
            Set<String> aliases,
            Set<String> requiredParameters,
            Set<String> supportedRelations,
            Set<String> producedFields) {

        return definition(
                toolName,
                intentTypes,
                aliases,
                requiredParameters,
                supportedRelations,
                producedFields,
                Collections.<String>emptySet()
        );
    }

    private static ToolDefinition definition(
            String toolName,
            Set<String> intentTypes,
            Set<String> aliases,
            Set<String> requiredParameters,
            Set<String> supportedRelations,
            Set<String> producedFields,
            Set<String> consumedFields) {

        return new ToolDefinition(
                toolName,
                intentTypes,
                aliases,
                requiredParameters,
                supportedRelations,
                producedFields,
                consumedFields,
                true
        );
    }

    private static Map<String, Object> properties(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0;i + 1 < values.length;i += 2) {
            result.put(String.valueOf(values[i]),values[i + 1]);
        }
        return result;
    }

    private static Map<String, Object> property(String type,String description) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();

        result.put("type", type);
        result.put("description", description);

        return result;
    }

    private static Map<String, Object> limitProperty() {
        return property("integer","Maximum number of records");
    }

    private static Map<String, Object> excludeUndefinedProperty() {
        Map<String, Object> result = property("string","Exclude placeholder records");
        result.put("enum",Arrays.asList("address","name","department"));
        return result;
    }

    private static Map<String, Object> reportTypeProperty() {
        Map<String, Object> result = property("string","Report type");
        result.put("enum",ReportTypeRegistry.getAvailabilityQueryValues());
        return result;
    }

    private static Map<String, Object> filterProperty() {
        Map<String, Object> result = property("string","T, F, or ALL");

        result.put("enum",Arrays.asList("T","F","ALL"));
        return result;
    }

    private static Map<String, Object> locationCodesProperty() {
        Map<String, Object> item = property("string","Location code");

        Map<String, Object> result = property("array","Location codes");

        result.put("items", item);
        return result;
    }

    private static Map<String, Object> ui(
            String icon,String samplePrompt,
            boolean needsInput,String inputHint,String placeholder) {

        Map<String, Object> result = new LinkedHashMap<String, Object>();

        result.put("icon", icon);
        result.put("samplePrompt", samplePrompt);
        result.put("needsInput", needsInput);
        result.put("inputHint", inputHint);
        result.put("placeholder", placeholder);

        return result;
    }

    private static Set<String> set(String... values) {
        return new LinkedHashSet<String>(Arrays.asList(values));
    }

    private static Map<String, Object> responseOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0;i + 1 < values.length;i += 2) {
            if (values[i + 1] != null) {
                result.put(String.valueOf(values[i]),values[i + 1]);
            }
        }
        return result;
    }

    private static String getArg(Map<String, Object> args,String key,String defaultValue) {
        Object value = args.get(key);
        if (value == null) {
            return defaultValue;
        }

        String result = value.toString().trim();
        return result.isEmpty()? defaultValue: result;
    }

    private static Integer getIntArg(Map<String, Object> args,String key) {

        Object value = args.get(key);

        if (value == null) {
            return null;
        }

        if (value instanceof Number) {
            return ((Number) value).intValue();
        }

        try {
            return Integer.valueOf(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> readLocationCodes(Object value) {

        List<String> result = new ArrayList<String>();

        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item != null) {
                    String code = item.toString().trim().toUpperCase();
                    if (!code.isEmpty()) {
                        result.add(code);
                    }
                }
            }
            return result;
        }

        if (value instanceof String) {
            for (String item : value.toString().split(",")) {
                String code = item.trim().toUpperCase();
                if (!code.isEmpty()) {
                    result.add(code);
                }
            }
        }
        return result;
    }

    private static boolean hasText(String value) {
        return value != null&& !value.trim().isEmpty();
    }
}
