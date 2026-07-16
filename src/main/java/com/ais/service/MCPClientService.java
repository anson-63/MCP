package com.ais.service;

import com.ais.db.DatabaseManager;
import com.ais.mcp.ToolCatalog;
import com.ais.mcp.ToolDispatcher;
import com.ais.mcp.ToolRegistryFactory;
import com.ais.model.LocationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compatibility facade for the catalog-backed tool system.
 */
public final class MCPClientService {
    private final ToolCatalog catalog;
    private final ToolDispatcher dispatcher;

    public MCPClientService() {
        ObjectMapper mapper = new ObjectMapper();
        this.catalog = ToolRegistryFactory.create(DatabaseManager.getInstance(), mapper);
        this.dispatcher = new ToolDispatcher(catalog, mapper);
    }

    public String callTool(String toolName, Map<String, Object> args) { return dispatcher.callTool(toolName, args); }
    public List<Map<String, Object>> listTools() { return catalog.listTools(); }
    public List<Map<String, Object>> listToolsForUI() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> tool : catalog.listTools()) {
            Object functionObject = tool.get("function");
            Object uiObject = tool.get("ui");
            if (!(functionObject instanceof Map)) { continue; }
            @SuppressWarnings("unchecked") Map<String, Object> function = (Map<String, Object>) functionObject;
            Map<String, Object> ui = new LinkedHashMap<String, Object>();
            if (uiObject instanceof Map) {
                @SuppressWarnings("unchecked") Map<String, Object> suppliedUi = (Map<String, Object>) uiObject;
                ui.putAll(suppliedUi);
            }
            ui.put("name", function.get("name"));
            ui.put("description", function.get("description"));
            result.add(ui);
        }
        return result;
    }

    public ToolDefinition resolveDefinition(String intentOrAlias) { return resolveDefinition(intentOrAlias, Collections.<String, String>emptyMap()); }
    public ToolDefinition resolveDefinition(String intentOrAlias, Map<String, String> params) { return catalog.resolveDefinition(intentOrAlias, params); }
    public Set<String> getAcceptedParameters(ToolDefinition definition) { return catalog.getAcceptedParameters(definition); }
    public String runDetail(String toolName, Map<String, Object> args) { return callTool(toolName, args); }
    public List<LocationResult> runList(String toolName, Map<String, Object> args) { return parseToLocationResults(callTool(toolName, args)); }
    private List<LocationResult> parseToLocationResults(String raw) { return new ArrayList<LocationResult>(); }
}