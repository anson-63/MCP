package com.ais.service;

import java.util.LinkedHashMap;
import java.util.Map;

public class Intent {
    public static final String LOCATION_CODE = "LOCATION_CODE";
    public static final String NAME_SEARCH = "NAME_SEARCH";
    public static final String PSM_LIST = "PSM_LIST";
    public static final String PSM_LOCATIONS = "PSM_LOCATIONS";
    public static final String CODE_HISTORY = "CODE_HISTORY";
    public static final String CHECK_REPORTS = "CHECK_REPORTS";
    public static final String DECLARED_MONUMENT = "DECLARED_MONUMENT";
    public static final String HISTORIC_BUILDING = "HISTORIC_BUILDING";
    public static final String DEPARTMENT_LOCATIONS = "DEPARTMENT_LOCATIONS";
    public static final String SQL_QUERY = "SQL_QUERY";
    public static final String UNKNOWN = "UNKNOWN";

    public final String type;
    public final String toolName;
    public final Map<String, String> params;
    public final IntentRole role;

    public Intent(
            String type,
            String toolName,
            Map<String, String> params) {

        this.type = type;
        this.toolName = toolName;
        this.params = params != null
                ? params
                : new LinkedHashMap<String, String>();
        this.role = resolveRole(type);
    }

    public Intent(
            String type,
            Map<String, String> params) {

        this(type, null, params);
    }

    public Intent(String type) {
        this(type, null, new LinkedHashMap<String, String>());
    }

    public Intent withParam(
            String key,
            String value) {

        Map<String, String> copied =
                new LinkedHashMap<String, String>(params);

        copied.put(key, value);

        return new Intent(
                type,
                toolName,
                copied
        );
    }

    /*
     * This method is retained only for compatibility with older
     * callers. New planner code must resolve aliases through
     * MCPClientService and ToolDefinition.
     */
    public static Intent of(String value) {
        if (value == null) {
            return new Intent(UNKNOWN);
        }

        String normalized =
                value.trim().toUpperCase();

        if (LOCATION_CODE.equals(normalized)
                || "HARDCODE_QUERY".equals(normalized)) {
            return new Intent(LOCATION_CODE);
        }

        if (NAME_SEARCH.equals(normalized)
                || "SEARCH_BY_NAME".equals(normalized)) {
            return new Intent(NAME_SEARCH);
        }

        if (PSM_LIST.equals(normalized)
                || "LIST_PSMS".equals(normalized)) {
            return new Intent(PSM_LIST);
        }

        if (PSM_LOCATIONS.equals(normalized)) {
            return new Intent(PSM_LOCATIONS);
        }

        if (CODE_HISTORY.equals(normalized)
                || "SEARCH_LOC_CD_HISTORY".equals(normalized)) {
            return new Intent(CODE_HISTORY);
        }

        if (CHECK_REPORTS.equals(normalized)
                || "REPORT".equals(normalized)) {
            return new Intent(CHECK_REPORTS);
        }

        if (DECLARED_MONUMENT.equals(normalized)
                || "MONUMENT".equals(normalized)) {
            return new Intent(DECLARED_MONUMENT);
        }

        if (HISTORIC_BUILDING.equals(normalized)
                || "HISTORIC".equals(normalized)
                || "GRADE".equals(normalized)) {
            return new Intent(HISTORIC_BUILDING);
        }

        if (DEPARTMENT_LOCATIONS.equals(normalized)
                || "DEPARTMENT".equals(normalized)) {
            return new Intent(DEPARTMENT_LOCATIONS);
        }

        if (SQL_QUERY.equals(normalized)) {
            return new Intent(SQL_QUERY);
        }

        return new Intent(UNKNOWN);
    }

    private static IntentRole resolveRole(String type) {
        if (type == null) {
            return IntentRole.MODIFIER;
        }

        if (PSM_LIST.equals(type)
                || PSM_LOCATIONS.equals(type)
                || NAME_SEARCH.equals(type)
                || DECLARED_MONUMENT.equals(type)
                || DEPARTMENT_LOCATIONS.equals(type)) {
            return IntentRole.PRIMARY;
        }

        if (HISTORIC_BUILDING.equals(type)) {
            return IntentRole.SECONDARY;
        }

        if (LOCATION_CODE.equals(type)
                || CHECK_REPORTS.equals(type)) {
            return IntentRole.ACTION;
        }

        if (CODE_HISTORY.equals(type)) {
            return IntentRole.ACTION_ON_RESULT;
        }

        return IntentRole.MODIFIER;
    }

    @Override
    public String toString() {
        return type + (params.isEmpty() ? "" : params.toString());
    }
}