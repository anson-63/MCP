package com.ais.model;

import java.util.List;
import java.util.ArrayList;

public class ExtractedKeywords {

    private List<String> intents;
    private String locationCode;
    private String locationName;
    private String reportType;
    private String department;
    private String psm;
    private String grade;
    private String filter;
    private String modifier;
    private List<String> rawKeywords;

    // ── Getters ───────────────────────────────────────────────────────────
    public List<String> getIntents()      { return intents; }
    public String getLocationCode()       { return locationCode; }
    public String getLocationName()       { return locationName; }
    public String getReportType()         { return reportType; }
    public String getDepartment()         { return department; }
    public String getPsm()                { return psm; }
    public String getGrade()              { return grade; }
    public String getFilter()             { return filter; }
    public String getModifier()           { return modifier; }
    public List<String> getRawKeywords()  { return rawKeywords; }

    // ── Setters ───────────────────────────────────────────────────────────
    public void setIntents(List<String> v)     { this.intents = v; }
    public void setLocationCode(String v)       { this.locationCode = v; }
    public void setLocationName(String v)       { this.locationName = v; }
    public void setReportType(String v)         { this.reportType = v; }
    public void setDepartment(String v)         { this.department = v; }
    public void setPsm(String v)                { this.psm = v; }
    public void setGrade(String v)              { this.grade = v; }
    public void setFilter(String v)             { this.filter = v; }
    public void setModifier(String v)           { this.modifier = v; }
    public void setRawKeywords(List<String> v)  { this.rawKeywords = v; }

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

    /** Return the first intent string, or null */
    public String primaryIntent() {
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
            + ", location=" + locationName
            + ", code=" + locationCode
            + ", modifier=" + modifier
            + ", dept=" + department
            + ", grade=" + grade
            + ", filter=" + filter
            + ", psm=" + psm
            + ", report=" + reportType + "}";
    }
}