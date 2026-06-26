package com.ais.service;

import java.util.List;

public class Plan {

    // Public fields — OllamaService accesses these directly
    public final List<Intent> steps;
    public final boolean      needsLlm;
    public final String       modifier;
    public final String       reason;

    public Plan(List<Intent> steps, boolean needsLlm, String modifier, String reason) {
        this.steps    = steps;
        this.needsLlm = needsLlm;
        this.modifier = modifier;
        this.reason   = reason;
    }

    // ── Convenience constructor (needsLlm defaults to false) ──────────
    public Plan(List<Intent> steps, String modifier, String reason) {
        this(steps, false, modifier, reason);
    }

    // ── Getters ───────────────────────────────────────────────────────
    public List<Intent> getSteps()    { return steps; }
    public boolean      isNeedsLlm()  { return needsLlm; }
    public String       getModifier() { return modifier; }
    public String       getReason()   { return reason; }

    @Override
    public String toString() {
        return "Plan{steps=" + steps
             + ", needsLlm=" + needsLlm
             + ", modifier=" + modifier
             + ", reason=" + reason + "}";
    }
}