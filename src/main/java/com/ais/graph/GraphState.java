package com.ais.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents the shared state passed between all nodes in the graph.
 * Equivalent to LangGraph's StateGraph state object.
 */
public class GraphState {

    public enum NodeStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }

    public enum VerificationResult {
        APPROVED,   // Response is accurate and safe
        RETRY,      // Response needs regeneration
        REJECTED,   // Response is fundamentally wrong
        SKIPPED     // Verification was skipped
    }

    // ── Input ──────────────────────────────────────────────────
    private String userQuery;
    private String sessionId;
    private Map<String, Object> context = new HashMap<>();

    // ── Planning ───────────────────────────────────────────────
    private String detectedIntent;
    private List<String> plannedTools = new ArrayList<>();

    // ── Primary LLM Output ────────────────────────────────────
    private String primaryResponse;
    private List<String> toolCallsMade = new ArrayList<>();
    private String rawToolOutput;
    private List<Map<String, Object>> toolCallDetails = new ArrayList<>();

    // ── Verification ──────────────────────────────────────────
    private VerificationResult verificationResult = VerificationResult.SKIPPED;
    private String verificationReason;
    private int retryCount = 0;
    public static final int MAX_RETRIES = 3;

    // ── Final Output ──────────────────────────────────────────
    private String finalResponse;
    private boolean success = false;
    private List<String> executionPath = new ArrayList<>(); // Audit trail
    private long startTimeMs = System.currentTimeMillis();

    // ── Node tracking ─────────────────────────────────────────
    private String currentNode;
    private String errorMessage;

    // ── Helper Methods ────────────────────────────────────────
    public void addExecutionStep(String nodeName) {
        executionPath.add(nodeName + " [" + 
            (System.currentTimeMillis() - startTimeMs) + "ms]");
        this.currentNode = nodeName;
    }

    public boolean canRetry() {
        return retryCount < MAX_RETRIES;
    }

    public void incrementRetry() {
        retryCount++;
    }

    public long getElapsedMs() {
        return System.currentTimeMillis() - startTimeMs;
    }

    // ── Getters & Setters ─────────────────────────────────────
    public String getUserQuery() { return userQuery; }
    public void setUserQuery(String userQuery) { this.userQuery = userQuery; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public Map<String, Object> getContext() { return context; }
    public void setContext(Map<String, Object> context) { this.context = context; }

    public String getDetectedIntent() { return detectedIntent; }
    public void setDetectedIntent(String detectedIntent) { 
        this.detectedIntent = detectedIntent; 
    }

    public List<String> getPlannedTools() { return plannedTools; }
    public void setPlannedTools(List<String> plannedTools) { 
        this.plannedTools = plannedTools; 
    }

    public String getPrimaryResponse() { return primaryResponse; }
    public void setPrimaryResponse(String primaryResponse) { 
        this.primaryResponse = primaryResponse; 
    }

    public List<String> getToolCallsMade() { return toolCallsMade; }
    public void setToolCallsMade(List<String> toolCallsMade) { 
        this.toolCallsMade = toolCallsMade; 
    }

    public String getRawToolOutput() { return rawToolOutput; }
    public void setRawToolOutput(String rawToolOutput) { 
        this.rawToolOutput = rawToolOutput; 
    }
    
    public List<Map<String, Object>> getToolCallDetails() {
        return toolCallDetails;
    }

    public void setToolCallDetails(List<Map<String, Object>> toolCallDetails) {
        this.toolCallDetails = toolCallDetails;
    }

    public VerificationResult getVerificationResult() { return verificationResult; }
    public void setVerificationResult(VerificationResult verificationResult) { 
        this.verificationResult = verificationResult; 
    }

    public String getVerificationReason() { return verificationReason; }
    public void setVerificationReason(String verificationReason) { 
        this.verificationReason = verificationReason; 
    }

    public int getRetryCount() { return retryCount; }

    public String getFinalResponse() { return finalResponse; }
    public void setFinalResponse(String finalResponse) { 
        this.finalResponse = finalResponse; 
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public List<String> getExecutionPath() { return executionPath; }

    public String getCurrentNode() { return currentNode; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { 
        this.errorMessage = errorMessage; 
    }
    
    
}