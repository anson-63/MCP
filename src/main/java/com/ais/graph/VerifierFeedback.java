package com.ais.graph;

import java.util.*;

/**
 * Structured, bounded feedback produced by VerifierNode.
 *
 * Tool names and arguments are suggestions only. PatchNode must resolve the
 * name through the catalog and let ToolDispatcher validate the arguments
 * before anything is executed.
 */
public final class VerifierFeedback {

    public static final String ACTION_NONE = "NONE";
    public static final String ACTION_REGENERATE = "REGENERATE";
    public static final String ACTION_REINVOKE_TOOL = "REINVOKE_TOOL";

    private String suggestedAction = ACTION_NONE;
    private String reason = "";
    private double confidence = 1.0d;

    private final List<String> missingTools = new ArrayList<>();
    private final Map<String, Map<String, Object>> toolArguments = new LinkedHashMap<>();

    // --- Getters & Setters ---

    public String getSuggestedAction() { return suggestedAction; }

    public void setSuggestedAction(String action) {
        if (action == null || action.isEmpty()) {
            this.suggestedAction = ACTION_NONE;
            return;
        }

        String value = action.trim().toUpperCase(Locale.ROOT);

        // Normalize common variations of action strings
        if (value.matches("REINVOKE|REINVOKE_MCP|REPAIR")) {
            this.suggestedAction = ACTION_REINVOKE_TOOL;
        } else if (value.matches("RETRY|REGENERATE_RESPONSE")) {
            this.suggestedAction = ACTION_REGENERATE;
        } else {
            this.suggestedAction = value;
        }
    }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = (reason == null) ? "" : reason; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public List<String> getMissingTools() { return Collections.unmodifiableList(missingTools); }

    public Map<String, Map<String, Object>> getToolArguments() { 
        return Collections.unmodifiableMap(toolArguments); 
    }

    // --- Logic Methods ---

    public void addMissingTool(String toolName) {
        if (toolName != null && !toolName.isEmpty()) {
            String normalized = toolName.trim();
            if (!missingTools.contains(normalized)) {
                missingTools.add(normalized);
            }
        }
    }

    /**
     * Stores arguments for a specific tool. Performs a defensive copy to maintain
     * internal state consistency.
     */
    public void putToolArguments(String toolName, Map<String, Object> arguments) {
        if (toolName != null && !toolName.isEmpty()) {
            toolArguments.put(toolName.trim(), (arguments != null) ? new LinkedHashMap<>(arguments) : new LinkedHashMap<>());
        }
    }

    public boolean requestsToolRepair() {
        return ACTION_REINVOKE_TOOL.equals(suggestedAction) && !missingTools.isEmpty();
    }
}