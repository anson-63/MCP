package com.ais.graph;

import com.ais.security.AuthorizationContext;

import java.util.*;

/**
 * Represents the shared state passed between all nodes in the graph.
 * Equivalent to LangGraph's StateGraph state object.
 */
public class GraphState {

    private AuthorizationContext authorizationContext;

    public enum NodeStatus { PENDING, RUNNING, COMPLETED, FAILED }
    public enum VerificationResult { APPROVED, RETRY, REJECTED, SKIPPED }

    // Input
    private String userQuery;
    private String sessionId;
    private Map<String, Object> context = new HashMap<>();

    // Planning
    private String detectedIntent;
    private List<String> plannedTools = new ArrayList<>();

    // Primary LLM output
    private String primaryResponse;
    private List<String> toolCallsMade = new ArrayList<>();
    private String rawToolOutput;
    private List<Map<String, Object>> toolCallDetails = new ArrayList<>();

    // Verification
    private VerificationResult verificationResult = VerificationResult.SKIPPED;
    private String verificationReason;
    private VerifierFeedback verifierFeedback = new VerifierFeedback();
    private int retryCount;
    public static final int MAX_RETRIES = 3;
    
    private int regenerationCount;
    private int repairAttemptCount;

    public static final int MAX_REGENERATIONS = 1;
    public static final int MAX_REPAIR_ATTEMPTS = 1;

    // Targeted repair tracking
    private boolean repairAttempted;
    private boolean repairSucceeded;

    // Final output
    private String finalResponse;
    private boolean success;
    private List<String> executionPath = new ArrayList<>();

    // Audit and node tracking
    private final long startTimeMs = System.currentTimeMillis();
    private String currentNode;
    private String errorMessage;
    
    private boolean infrastructureFailure;
    private String infrastructureFailureReason;

    // --- Audit & Lifecycle ---

    public void addExecutionStep(String nodeName) {
        long elapsed = System.currentTimeMillis() - startTimeMs;
        executionPath.add(String.format("%s [%dms]", nodeName, elapsed));
        this.currentNode = nodeName;
    }

    public boolean canRetry() { return retryCount < MAX_RETRIES; }
    public void incrementRetry() { this.retryCount++; }
    public long getElapsedMs() { return System.currentTimeMillis() - startTimeMs; }

    // --- Getters & Setters ---

    public AuthorizationContext getAuthorizationContext() { return authorizationContext; }
    public void setAuthorizationContext(AuthorizationContext ctx) { this.authorizationContext = ctx; }

    public String getUserQuery() { return userQuery; }
    public void setUserQuery(String userQuery) { this.userQuery = userQuery; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public Map<String, Object> getContext() { return context; }
    public void setContext(Map<String, Object> context) { this.context = (context != null) ? context : new HashMap<>(); }

    public String getDetectedIntent() { return detectedIntent; }
    public void setDetectedIntent(String intent) { this.detectedIntent = intent; }

    public List<String> getPlannedTools() { return plannedTools; }
    public void setPlannedTools(List<String> tools) { this.plannedTools = (tools != null) ? tools : new ArrayList<>(); }

    public String getPrimaryResponse() { return primaryResponse; }
    public void setPrimaryResponse(String response) { this.primaryResponse = response; }

    public List<String> getToolCallsMade() { return toolCallsMade; }
    public void setToolCallsMade(List<String> calls) { this.toolCallsMade = (calls != null) ? calls : new ArrayList<>(); }

    public String getRawToolOutput() { return rawToolOutput; }
    public void setRawToolOutput(String output) { this.rawToolOutput = output; }

    public List<Map<String, Object>> getToolCallDetails() { return toolCallDetails; }
    public void setToolCallDetails(List<Map<String, Object>> details) { 
        this.toolCallDetails = (details != null) ? details : new ArrayList<>(); 
    }

    /**
     * Appends a record of a tool execution. Defensive copy of arguments is used.
     */
    public void addToolCallDetail(String toolName, Map<String, Object> arguments, String output) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", toolName);
        detail.put("args", (arguments != null) ? new LinkedHashMap<>(arguments) : new LinkedHashMap<>());
        detail.put("result", (output != null) ? output : "");

        toolCallDetails.add(detail);

        if (toolName != null && !toolName.isEmpty()) {
            toolCallsMade.add(toolName);
        }
    }

    public VerificationResult getVerificationResult() { return verificationResult; }
    public void setVerificationResult(VerificationResult result) { this.verificationResult = result; }

    public String getVerificationReason() { return verificationReason; }
    public void setVerificationReason(String reason) { this.verificationReason = reason; }

    public VerifierFeedback getVerifierFeedback() { return verifierFeedback; }
    public void setVerifierFeedback(VerifierFeedback feedback) { 
        this.verifierFeedback = (feedback != null) ? feedback : new VerifierFeedback(); 
    }
    
    @Deprecated
    public int getRetryCount() { return retryCount; }
    
    public boolean isRepairAttempted() { return repairAttempted; }
    public void setRepairAttempted(boolean attempted) { this.repairAttempted = attempted; }

    public boolean isRepairSucceeded() { return repairSucceeded; }
    public void setRepairSucceeded(boolean succeeded) { this.repairSucceeded = succeeded; }

    public String getFinalResponse() { return finalResponse; }
    public void setFinalResponse(String response) { this.finalResponse = response; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public List<String> getExecutionPath() { return executionPath; }
    public String getCurrentNode() { return currentNode; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String msg) { this.errorMessage = msg; }
    
    public int getRegenerationCount() {
        return regenerationCount;
    }

    public void incrementRegeneration() {
        regenerationCount++;
    }

    public boolean canRegenerate() {
        return regenerationCount < MAX_REGENERATIONS;
    }

    public int getRepairAttemptCount() {
        return repairAttemptCount;
    }

    public void incrementRepairAttempt() {
        repairAttemptCount++;
    }

    public boolean canAttemptRepair() {
        return repairAttemptCount < MAX_REPAIR_ATTEMPTS;
    }
    
    public boolean hasInfrastructureFailure() {
        return infrastructureFailure;
    }

    public void setInfrastructureFailure(String reason) {
        this.infrastructureFailure = true;
        this.infrastructureFailureReason = reason;
    }

    public String getInfrastructureFailureReason() {
        return infrastructureFailureReason;
    }

    public void clearInfrastructureFailure() {
        infrastructureFailure = false;
        infrastructureFailureReason = null;
    }
}