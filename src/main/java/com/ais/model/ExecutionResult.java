package com.ais.model;

import java.util.List;

public class ExecutionResult {

    private final List<LocationResult> results;
    private final String               detailOutput; // for ACTION steps

    public ExecutionResult(List<LocationResult> results, String detailOutput) {
        this.results      = results;
        this.detailOutput = detailOutput;
    }

    public List<LocationResult> getResults()      { return results; }
    public String               getDetailOutput() { return detailOutput; }
    public boolean              hasDetail()       { return detailOutput != null; }
}