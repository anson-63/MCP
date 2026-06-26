package com.ais.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents a single resolved intent with its parameters.
 * Used by QueryPlanner to build a Plan, and by executePlan to run steps.
 */
public class Intent {

    // ── Intent type constants (replaces old IntentType enum) ─────────
    public static final String LOCATION_CODE       = "LOCATION_CODE";
    public static final String NAME_SEARCH         = "NAME_SEARCH";
    public static final String PSM_LIST            = "PSM_LIST";
    public static final String PSM_LOCATIONS       = "PSM_LOCATIONS";
    public static final String CODE_HISTORY        = "CODE_HISTORY";
    public static final String CHECK_REPORTS       = "CHECK_REPORTS";
    public static final String DECLARED_MONUMENT   = "DECLARED_MONUMENT";
    public static final String HISTORIC_BUILDING   = "HISTORIC_BUILDING";
    public static final String DEPARTMENT_LOCATIONS= "DEPARTMENT_LOCATIONS";
    public static final String SQL_QUERY           = "SQL_QUERY";
    public static final String UNKNOWN             = "UNKNOWN";

    // ── Instance fields (used by executePlan) ─────────────────────────
    public final String              type;   // one of the constants above
    public final Map<String, String> params; // e.g. {filter=T, grade=ALL}
    public final IntentRole          role;   // PRIMARY / SECONDARY / ACTION etc.

    // ── Tool name mapping ─────────────────────────────────────────────
    public final String toolName;

    public Intent(String type, Map<String, String> params) {
        this.type     = type;
        this.params   = params != null ? params : new LinkedHashMap<String, String>();
        this.role     = resolveRole(type);
        this.toolName = resolveToolName(type);
    }

    public Intent(String type) {
        this(type, new LinkedHashMap<String, String>());
    }

    // ── Factory with fluent params ────────────────────────────────────
    public Intent withParam(String key, String value) {
        Map<String, String> newParams = new LinkedHashMap<String, String>(this.params);
        newParams.put(key, value);
        return new Intent(this.type, newParams);
    }

    // ── Parse from string (safe) ──────────────────────────────────────
    public static Intent of(String typeString) {
        if (typeString == null) return new Intent(UNKNOWN);
        String upper = typeString.trim().toUpperCase();

        switch (upper) {
            case "LOCATION_CODE":        return new Intent(LOCATION_CODE);
            case "NAME_SEARCH":          return new Intent(NAME_SEARCH);
            case "PSM":
            case "PSM_LIST":             return new Intent(PSM_LIST);
            case "PSM_LOCATIONS":        return new Intent(PSM_LOCATIONS);
            case "CODE_HISTORY":         return new Intent(CODE_HISTORY);
            case "REPORT":
            case "CHECK_REPORTS":        return new Intent(CHECK_REPORTS);
            case "MONUMENT":
            case "DECLARED_MONUMENT":    return new Intent(DECLARED_MONUMENT);
            case "HISTORIC":
            case "HISTORIC_BUILDING":
            case "GRADE":                // ← LLM sometimes uses GRADE for historic
                return new Intent(HISTORIC_BUILDING);
            case "DEPARTMENT":
            case "DEPARTMENT_LOCATIONS": return new Intent(DEPARTMENT_LOCATIONS);
            case "SQL_QUERY":            return new Intent(SQL_QUERY);
            case "UNKNOWN":
            default:					 return new Intent(UNKNOWN);
        }
    }

    // ── Resolve role from type ────────────────────────────────────────
    private static IntentRole resolveRole(String type) {
        if (type == null) return IntentRole.MODIFIER;
        switch (type) {
            case PSM_LIST:
            case PSM_LOCATIONS:
            case NAME_SEARCH:
            case DECLARED_MONUMENT:
            case DEPARTMENT_LOCATIONS:   return IntentRole.PRIMARY;

            case HISTORIC_BUILDING:      return IntentRole.SECONDARY;

            case LOCATION_CODE:
            case CHECK_REPORTS:          return IntentRole.ACTION;

            case CODE_HISTORY:           return IntentRole.ACTION_ON_RESULT;

            default:                     return IntentRole.MODIFIER;
        }
    }

    // ── Resolve tool name from type ───────────────────────────────────
    public static String resolveToolName(String type) {
        if (type == null) return null;
        switch (type) {
            case LOCATION_CODE:          return "hardcode_query";
            case NAME_SEARCH:            return "search_by_name";
            case PSM_LIST:               return "list_psms";
            case PSM_LOCATIONS:          return "locations_by_psm";
            case CODE_HISTORY:           return "search_loc_cd_history";
            case CHECK_REPORTS:          return "check_reports";
            case DECLARED_MONUMENT:      return "search_declared_monument";
            case HISTORIC_BUILDING:      return "search_historic_building";
            case DEPARTMENT_LOCATIONS:   return "locations_by_dept";
            default:                     return null;
        }
    }

    @Override
    public String toString() {
        return type + (params.isEmpty() ? "" : params.toString());
    }
}