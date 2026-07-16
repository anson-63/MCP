package com.ais.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.ais.service.ToolDefinition;
import com.ais.db.DatabaseQueryException;
import com.ais.db.DatabaseQueryTimeoutException;
import com.ais.mcp.ToolRegistration;
import java.util.*;

public final class ToolDispatcher {
    private static final int MAX_LOCATION_CODES = 200;
    private static final String LOCATION_CODE_REGEX = "(?i)^[A-Z]{2}\\d{11}$";
    private final ToolCatalog catalog;
    private final ObjectMapper mapper;

    public ToolDispatcher(ToolCatalog catalog, ObjectMapper mapper) {
        this.catalog = catalog;
        this.mapper = (mapper == null) ? new ObjectMapper() : mapper;
    }

    public String callTool(String toolName, Map<String, Object> args) {
        ToolRegistration registration = catalog.get(toolName);
        if (registration == null) return errorJson("UNKNOWN_TOOL", "Unknown tool", false);

        Map<String, Object> safeArgs = args == null
                ? Collections.<String, Object>emptyMap() : args;
        String validationError = validateArguments(registration.getDefinition(), safeArgs);
        if (validationError != null) return errorJson("INVALID_ARGUMENT", validationError, false);
        Object oneCode = args.get("locCd");
        if (oneCode != null && !oneCode.toString().trim().matches(LOCATION_CODE_REGEX)) {
            return errorJson("INVALID_ARGUMENT", "Invalid location code", false);
        }
        try {
            Object output = registration.getProvider().execute(safeArgs);
            return output instanceof String ? (String) output : mapper.writeValueAsString(output);
        } catch (DatabaseQueryTimeoutException e) {
            return errorJson("QUERY_TIMEOUT", "Database query timed out", false);
        } catch (DatabaseQueryException e) {
            return errorJson("DATABASE_ERROR", "Database query could not be completed", false);
        } catch (IllegalArgumentException e) {
            return errorJson("INVALID_ARGUMENT", e.getMessage(), false);
        } catch (Exception e) {
            return errorJson("TOOL_EXECUTION_ERROR", "Tool execution failed", false);
        }
    }

    private String validateArguments(ToolDefinition def, Map<String, Object> args) {
        for (String req : def.getRequiredParameters()) {
            Object val = args.get(req);
            if (val == null || (val instanceof String && ((String) val).trim().isEmpty())) {
                return "Missing required tool argument: " + req;
            }
        }

        Object codes = args.get("locCds");
        if (codes != null) {
            List<String> values = readLocationCodes(codes);
            if (values.isEmpty() || values.size() > MAX_LOCATION_CODES) return "Invalid location-code list";
            for (String c : values) if (!c.matches(LOCATION_CODE_REGEX)) return "Invalid location code";
        }
        return null;
    }

    private List<String> readLocationCodes(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item != null && !item.toString().trim().isEmpty()) result.add(item.toString().trim().toUpperCase());
            }
        } else if (value instanceof String) {
            for (String item : value.toString().split(",")) {
                if (!item.trim().isEmpty()) result.add(item.trim().toUpperCase());
            }
        }
        return result;
    }

    private String errorJson(String code, String message, boolean retryable) {
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("error", message);
        error.put("errorCode", code);
        error.put("retryable", retryable);
        try {
            return mapper.writeValueAsString(error);
        } catch (Exception e) {
            return "{\"error\":\"Tool execution failed\",\"errorCode\":\"SERIALIZATION_ERROR\",\"retryable\":false}";
        }
    }

    private String errorJson(String message) {
        return errorJson("TOOL_ERROR", message, false);
    }
}