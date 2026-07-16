package com.ais.service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class ToolDefinition {
    private final String toolName;
    private final Set<String> intentTypes;
    private final Set<String> aliases;
    private final Set<String> requiredParameters;
    private final Set<String> supportedRelations;
    private final Set<String> producedFields;
    private final Set<String> consumedFields;
    private final boolean plannerEnabled;

    public ToolDefinition(
            String toolName,
            Set<String> intentTypes,
            Set<String> aliases,
            Set<String> requiredParameters,
            Set<String> supportedRelations,
            Set<String> producedFields,
            Set<String> consumedFields,
            boolean plannerEnabled) {

        this.toolName = toolName;
        this.intentTypes = immutable(intentTypes);
        this.aliases = immutable(aliases);
        this.requiredParameters = immutable(requiredParameters);
        this.supportedRelations = immutable(supportedRelations);
        this.producedFields = immutable(producedFields);
        this.consumedFields = immutable(consumedFields);
        this.plannerEnabled = plannerEnabled;
    }

    /*
     * Backward-compatible constructor.
     */
    public ToolDefinition(
            String toolName,
            Set<String> intentTypes,
            Set<String> aliases,
            Set<String> requiredParameters,
            Set<String> supportedRelations,
            Set<String> producedFields,
            boolean plannerEnabled) {

        this(
                toolName,
                intentTypes,
                aliases,
                requiredParameters,
                supportedRelations,
                producedFields,
                Collections.<String>emptySet(),
                plannerEnabled
        );
    }
    
    private ToolDefinition definition(
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

    private static Set<String> immutable(Set<String> values) {
        if (values == null) {
            values = Collections.emptySet();
        }

        return Collections.unmodifiableSet(
                new LinkedHashSet<String>(values)
        );
    }

    public String getToolName() {
        return toolName;
    }

    public Set<String> getIntentTypes() {
        return intentTypes;
    }

    public Set<String> getAliases() {
        return aliases;
    }

    public Set<String> getRequiredParameters() {
        return requiredParameters;
    }

    public Set<String> getSupportedRelations() {
        return supportedRelations;
    }

    public Set<String> getProducedFields() {
        return producedFields;
    }

    public Set<String> getConsumedFields() {
        return consumedFields;
    }

    public boolean isPlannerEnabled() {
        return plannerEnabled;
    }
}