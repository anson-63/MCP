package com.ais.mcp;

import com.ais.service.ToolDefinition;
import java.util.*;

public final class ToolCatalog {
    private final Map<String, ToolRegistration> registrations = new LinkedHashMap<>();

    public void register(ToolRegistration registration) {
        if (registration == null || registration.getDefinition() == null) throw new IllegalArgumentException("Tool registration must contain a definition");
        String name = registration.getDefinition().getToolName();
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("Tool name must not be empty");
        if (registrations.containsKey(name)) throw new IllegalStateException("Duplicate tool registration: " + name);
        registrations.put(name, registration);
    }

    public ToolRegistration get(String toolName) { return registrations.get(toolName); }

    public ToolDefinition resolveDefinition(String intentOrAlias, Map<String, String> params) {
        if (intentOrAlias == null) return null;
        
        // Verifier and agent calls normally supply the exact runtime tool name.
        ToolRegistration direct = registrations.get(intentOrAlias);
        if (direct != null) {
            return direct.getDefinition();
        }

        // Optional case-insensitive fallback.
        for (Map.Entry<String, ToolRegistration> entry
                : registrations.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(intentOrAlias)) {
                return entry.getValue().getDefinition();
            }
        }
        
        ToolDefinition best = null;
        int bestScore = Integer.MIN_VALUE;

        for (ToolRegistration registration : registrations.values()) {
            ToolDefinition definition = registration.getDefinition();
            boolean exact = definition.getIntentTypes().contains(intentOrAlias);
            boolean alias = definition.getAliases().contains(intentOrAlias);
            if ((!exact && !alias) || !parametersSatisfy(definition, params)) continue;

            int score = (exact ? 10000 : 0) + definition.getRequiredParameters().size();
            if (score > bestScore) {
                bestScore = score;
                best = definition;
            }
        }
        return best;
    }

    public Set<String> getAcceptedParameters(ToolDefinition definition) {
        if (definition == null) return Collections.emptySet();
        ToolRegistration registration = get(definition.getToolName());
        return registration == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(registration.getProperties().keySet()));
    }

    public List<Map<String, Object>> listTools() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ToolRegistration registration : registrations.values()) {
            ToolDefinition def = registration.getDefinition();
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("type", "object");
            parameters.put("properties", registration.getProperties());
            parameters.put("required", new ArrayList<>(def.getRequiredParameters()));

            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", def.getToolName());
            function.put("description", registration.getDescription());
            function.put("parameters", parameters);

            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", function);
            tool.put("ui", registration.getUi());
            result.add(tool);
        }
        return result;
    }

    private boolean parametersSatisfy(ToolDefinition definition, Map<String, String> params) {
        Map<String, String> p = (params == null) ? Collections.emptyMap() : params;
        for (String required : definition.getRequiredParameters()) {
            String val = p.get(required);
            if (val != null && !val.trim().isEmpty()) continue;
            if (("locCd".equals(required) || "locCds".equals(required)) && "use_previous_codes".equals(p.get("relation"))) continue;
            return false;
        }
        return true;
    }
    
    private boolean isComposableTool(ToolDefinition definition, Set<String> acceptedParameters) {
        if (definition == null || acceptedParameters == null) return false;

        // Must produce location codes
        if (!definition.getProducedFields().contains("LOC_CD")) 
        	return false;

        // Detail tools (required locCd) cannot be collapsed
        if (acceptedParameters.contains("locCd") 
        		&& definition.getRequiredParameters().contains("locCd")) 
        	return false;

        return true;
    }
}