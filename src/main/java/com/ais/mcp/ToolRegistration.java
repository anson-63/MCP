package com.ais.mcp;

import com.ais.service.ToolDefinition;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ToolRegistration {
    private final ToolDefinition definition;
    private final String description;
    private final Map<String, Object> properties;
    private final Map<String, Object> ui;
    private final ToolProvider provider;

    public ToolRegistration(ToolDefinition definition, String description, Map<String, Object> properties, Map<String, Object> ui, ToolProvider provider) {
        this.definition = definition;
        this.description = description;
        this.properties = properties == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        this.ui = ui == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(ui));
        this.provider = provider;
    }

    public ToolDefinition getDefinition() { return definition; }
    public String getDescription() { return description; }
    public Map<String, Object> getProperties() { return properties; }
    public Map<String, Object> getUi() { return ui; }
    public ToolProvider getProvider() { return provider; }
}