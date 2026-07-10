package com.ais.model;

import java.util.List;
import java.util.ArrayList;

public class ExtractedKeywords {

    private List<String> intents;
    private String primaryIntent;
    private List<IntentStep> plan;
    private String locationCode;
    private String locationName;
    private String reportType;
    private String department;
    private String psm;
    private String grade;
    private String filter;
    private String modifier;
    private boolean showDetails;
    private List<String> rawKeywords;
    private Integer limit;
    private String excludeUndefinedField;
    // ── Getters ───────────────────────────────────────────────────────────

    public List<String> getIntents()      	 { return intents; }
    public String getPrimaryIntent()      	 { return primaryIntent; }
    public List<IntentStep> getPlan()     	 { return plan; }
    public String getLocationCode()       	 { return locationCode; }
    public String getLocationName()       	 { return locationName; }
    public String getReportType()         	 { return reportType; }
    public String getDepartment()         	 { return department; }
    public String getPsm()                	 { return psm; }
    public String getGrade()              	 { return grade; }
    public String getFilter()             	 { return filter; }
    public String getModifier()           	 { return modifier; }
    public boolean isShowDetails()        	 { return showDetails; }
    public List<String> getRawKeywords()  	 { return rawKeywords; }
    public Integer getLimit()			  	 { return limit; }
    public String getExcludeUndefinedField() { return excludeUndefinedField; }
    // ── Setters ───────────────────────────────────────────────────────────

    public void setIntents(List<String> v)     { this.intents = v; }
    public void setPrimaryIntent(String v)     { this.primaryIntent = v; }
    public void setPlan(List<IntentStep> v)     { this.plan = v; }
    public void setLocationCode(String v)       { this.locationCode = v; }
    public void setLocationName(String v)       { this.locationName = v; }
    public void setReportType(String v)         { this.reportType = v; }
    public void setDepartment(String v)         { this.department = v; }
    public void setPsm(String v)                { this.psm = v; }
    public void setGrade(String v)              { this.grade = v; }
    public void setFilter(String v)             { this.filter = v; }
    public void setModifier(String v)           { this.modifier = v; }
    public void setShowDetails(boolean v)       { this.showDetails = v; }
    public void setRawKeywords(List<String> v)  { this.rawKeywords = v; }
    public void setLimit(Integer limit)		{ this.limit = limit; }
    public void setExcludeUndefinedField(String excludeUndefinedField) { this.excludeUndefinedField = excludeUndefinedField; }
    // ── Helper methods (used by OllamaService) ────────────────────────────

    /** Check if a specific intent string is present */
    public boolean hasIntent(String intent) {
        if (intents == null || intent == null) return false;
        for (String i : intents) {
            if (intent.equalsIgnoreCase(i)) return true;
        }
        return false;
    }

    /** True if more than one intent is present */
    public boolean isCompound() {
        return intents != null && intents.size() > 1;
    }

    /**
     * Return the primary intent if the LLM designated one, otherwise the first intent.
     * The LLM should set primaryIntent to the main action (e.g. HISTORIC) when
     * other intents (e.g. PSM) are filters.
     */
    public String primaryIntent() {
        if (primaryIntent != null && !primaryIntent.trim().isEmpty()) {
            return primaryIntent.trim().toUpperCase();
        }
        if (intents == null || intents.isEmpty()) return null;
        return intents.get(0);
    }

    /** Return all intents as a safe non-null list */
    public List<String> safeIntents() {
        return intents != null ? intents : new ArrayList<>();
    }

    @Override
    public String toString() {
        return "ExtractedKeywords{intents=" + intents
            + ", primaryIntent=" + primaryIntent
            + ", plan=" + plan
            + ", location=" + locationName
            + ", code=" + locationCode
            + ", modifier=" + modifier
            + ", showDetails=" + showDetails
            + ", dept=" + department
            + ", grade=" + grade
            + ", filter=" + filter
            + ", psm=" + psm
            + ", report=" + reportType + "}";
    }
}
