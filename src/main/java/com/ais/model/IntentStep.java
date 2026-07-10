package com.ais.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One step in an LLM-generated execution plan.
 * The LLM decides the priority and the relation to the previous step,
 * making the pipeline generic and scalable for any tool combination.
 */
public class IntentStep {

    /** Intent type, e.g. PSM_LOCATIONS, HISTORIC_BUILDING, CHECK_REPORTS */
    private String type;

    /** Lower priority runs first (1, 2, 3, ...). */
    private int priority = 1;

    /** Parameters for the tool call. */
    private Map<String, String> params = new LinkedHashMap<>();

    /**
     * How this step relates to the result of the previous step:
     * - independent: do not use previous result
     * - filter_previous: keep only LOC_CD values that appear in both results
     * - enrich_previous: merge attributes from this result into previous rows
     * - use_previous_codes: pass all previous LOC_CD values as locCds/codes arg
     */
    private String relation = "independent";

    public IntentStep() {
    }

    public IntentStep(String type, int priority, Map<String, String> params, String relation) {
        this.type = type;
        this.priority = priority;
        this.params = params != null ? params : new LinkedHashMap<>();
        this.relation = relation != null ? relation : "independent";
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public Map<String, String> getParams() {
        return params;
    }

    public void setParams(Map<String, String> params) {
        this.params = params != null ? params : new LinkedHashMap<>();
    }

    public String getRelation() {
        return relation;
    }

    public void setRelation(String relation) {
        this.relation = relation != null ? relation : "independent";
    }

    @Override
    public String toString() {
        return "IntentStep{type=" + type
                + ", priority=" + priority
                + ", params=" + params
                + ", relation=" + relation + "}";
    }
}
