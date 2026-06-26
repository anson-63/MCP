package com.ais.service;

import com.ais.model.ExtractedKeywords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class QueryPlanner {

    private static final Logger log = LoggerFactory.getLogger(QueryPlanner.class);

    // ── New-style entry point ─────────────────────────────────────────
    public Plan plan(ExtractedKeywords kw) {
        return analyse(null, kw);
    }

    // ── Old-style entry point (called by OllamaService) ───────────────
    public Plan analyse(String userPrompt, ExtractedKeywords kw) {

        if (kw == null) {
            log.warn("⚠️ QueryPlanner: null keywords");
            List<Intent> steps = new ArrayList<Intent>();
            steps.add(new Intent(Intent.UNKNOWN));
            return new Plan(steps, true, null, "null keywords");
        }

        List<String> intentStrings = kw.safeIntents();
        log.info("🧠 QueryPlanner intents={} modifier={} psm={} dept={} filter={} grade={}",
                intentStrings, kw.getModifier(), kw.getPsm(),
                kw.getDepartment(), kw.getFilter(), kw.getGrade());

        List<Intent> steps    = new ArrayList<Intent>();
        String       modifier = kw.getModifier();
        boolean      needsLlm = false;

        // ── Detect compound patterns BEFORE individual processing ─────────
        boolean hasPsm         = intentStrings.contains("PSM");
        boolean hasCodeHistory = intentStrings.contains("CODE_HISTORY");
        boolean hasLocCode     = intentStrings.contains("LOCATION_CODE");
        boolean hasMonument    = intentStrings.contains("MONUMENT");
        boolean hasHistoric    = intentStrings.contains("HISTORIC");

        // ══════════════════════════════════════════════════════════════════
        // COMPOUND PATTERN 1: PSM + LOCATION_CODE or PSM + CODE_HISTORY
        // "info of first location in PSM/KT"
        // "code history of first location in PSM/KT"
        // → Step 1: PSM_LOCATIONS (get locations in PSM)
        // → Step 2: LOCATION_CODE or CODE_HISTORY (act on first result)
        // ══════════════════════════════════════════════════════════════════
        if (hasPsm && (hasLocCode || hasCodeHistory)
                && kw.getPsm() != null) {

            // Step 1: Get locations in PSM
            Map<String, String> psmParams = new LinkedHashMap<String, String>();
            psmParams.put("psm", kw.getPsm());
            if (kw.getModifier() != null) psmParams.put("modifier", kw.getModifier());
            steps.add(new Intent(Intent.PSM_LOCATIONS, psmParams));
            log.info("  ➕ COMPOUND PSM+CODE step 1: PSM_LOCATIONS psm={}", kw.getPsm());

            // Step 2: Act on first result
            String actionType = hasCodeHistory
                    ? Intent.CODE_HISTORY : Intent.LOCATION_CODE;
            Map<String, String> actionParams = new LinkedHashMap<String, String>();
            actionParams.put("codesSource", "previous_first");
            actionParams.put("modifier",    modifier != null ? modifier : "FIRST");
            steps.add(new Intent(actionType, actionParams));
            log.info("  ➕ COMPOUND PSM+CODE step 2: {} on first result", actionType);

            Plan plan = new Plan(steps, false, modifier, "compound: PSM+" + actionType);
            log.info("📋 Plan: {}", plan);
            return plan;
        }

        // ══════════════════════════════════════════════════════════════════
        // COMPOUND PATTERN 2: MONUMENT + HISTORIC
        // "Show historic grade of declared monuments"
        // → Step 1: DECLARED_MONUMENT (get monuments)
        // → Step 2: HISTORIC_BUILDING with enrich=true (add grade to each)
        // ══════════════════════════════════════════════════════════════════
        if (hasMonument && hasHistoric) {

            // Step 1: Get declared monuments
            Map<String, String> monParams = new LinkedHashMap<String, String>();
            monParams.put("filter", kw.getFilter() != null ? kw.getFilter() : "T");
            if (kw.getLocationName() != null)
                monParams.put("locationFilter", kw.getLocationName());
            steps.add(new Intent(Intent.DECLARED_MONUMENT, monParams));
            log.info("  ➕ COMPOUND MONUMENT+HISTORIC step 1: DECLARED_MONUMENT");

            // Step 2: Enrich with historic grade (LEFT JOIN style — not cross-filter)
            Map<String, String> histParams = new LinkedHashMap<String, String>();
            histParams.put("grade",   kw.getGrade() != null ? kw.getGrade() : "ALL");
            histParams.put("enrich",  "true");   // ← signals LEFT JOIN, not cross-filter
            steps.add(new Intent(Intent.HISTORIC_BUILDING, histParams));
            log.info("  ➕ COMPOUND MONUMENT+HISTORIC step 2: HISTORIC_BUILDING (enrich)");

            Plan plan = new Plan(steps, false, modifier, "compound: MONUMENT+HISTORIC");
            log.info("📋 Plan: {}", plan);
            return plan;
        }
        
        if (hasHistoric && hasLocCode && kw.getLocationCode() != null) {
            Map<String, String> params = new LinkedHashMap<String, String>();
            params.put("locCd",           kw.getLocationCode());
            params.put("includeHistoric", "true"); // signals renderer to show grade

            steps.add(new Intent(Intent.LOCATION_CODE, params));
            log.info("  ➕ COMPOUND HISTORIC+LOCATION_CODE → single hardcode_query for {}",
                    kw.getLocationCode());

            Plan plan = new Plan(steps, false, modifier, "compound: HISTORIC+LOCATION_CODE");
            log.info("📋 Plan: {}", plan);
            return plan;
        }
        
        // ══════════════════════════════════════════════════════════════════
        // SINGLE / OTHER INTENTS — process each individually
        // ══════════════════════════════════════════════════════════════════
        for (String intentStr : intentStrings) {
            Intent base = Intent.of(intentStr);
            String targetIntentType = base.type;

            if ("DEPARTMENT".equalsIgnoreCase(intentStr) && (kw.getDepartment() == null || kw.getDepartment().trim().isEmpty())) {
                log.info("  ⚠️ Intent DEPARTMENT lacks deptCd param — delegating to LLM");
                needsLlm = true;
                continue;
            }
            if ("PSM".equalsIgnoreCase(intentStr) && (kw.getPsm() == null || kw.getPsm().trim().isEmpty())) {
                log.info("  🎯 Intent PSM has null psm param — routing to LIST_PSMS tool");
                targetIntentType = Intent.PSM_LIST; 
            }
            if ("LOCATION_CODE".equalsIgnoreCase(intentStr) && (kw.getLocationCode() == null || kw.getLocationCode().trim().isEmpty())) {
                log.info("  ⚠️ Intent LOCATION_CODE lacks locCd param — delegating to LLM");
                needsLlm = true;
                continue;
            }

            // ── 1. DYNAMIC MULTI-REPORT SPLITTING ("ALL" or "BSI,KAI") ──
            if ("REPORT".equalsIgnoreCase(intentStr) || Intent.CHECK_REPORTS.equalsIgnoreCase(targetIntentType)) {
                String repTypeStr = kw.getReportType();
                if (repTypeStr == null || repTypeStr.trim().isEmpty()) {
                    log.info("  ⚠️ Intent REPORT lacks reportType param — delegating to LLM");
                    needsLlm = true;
                    continue;
                }

                // Expand "ALL" into the 5 core report types
                if ("ALL".equalsIgnoreCase(repTypeStr.trim())) {
                    repTypeStr = "BSI,CSR,KAI,EMMS,DSSR";
                }

                // Split comma-separated report types and schedule a CHECK_REPORTS step for EACH!
                String[] repTypes = repTypeStr.split(",");
                for (String singleRep : repTypes) {
                    singleRep = singleRep.trim();
                    if (!singleRep.isEmpty()) {
                        Map<String, String> repParams = new LinkedHashMap<String, String>();
                        if (kw.getLocationCode() != null) {
                            repParams.put("locCd",  kw.getLocationCode());
                            repParams.put("locCds", kw.getLocationCode());
                        }
                        repParams.put("reportType", singleRep);
                        if (kw.getModifier() != null) repParams.put("modifier", kw.getModifier());

                        steps.add(new Intent(targetIntentType, repParams));
                        log.info("  ➕ MULTI-REPORT step: {} params={}", targetIntentType, repParams);
                    }
                }
                continue; // We successfully scheduled all report steps, skip the default single insertion!
            }

            Map<String, String> params = new LinkedHashMap<String, String>();

            // ── Inject all relevant keyword fields into params ────────────
            if (kw.getPsm()           != null) params.put("psm",          kw.getPsm());
            if (kw.getDepartment()    != null) params.put("deptCd",       kw.getDepartment());
            if (kw.getFilter()        != null) params.put("filter",       kw.getFilter());
            if (kw.getGrade()         != null) params.put("grade",        kw.getGrade());
            if (kw.getLocationName()  != null) params.put("locName",      kw.getLocationName());
            if (kw.getReportType()    != null) params.put("reportType",   kw.getReportType());
            if (kw.getModifier()      != null) params.put("modifier",     kw.getModifier());

            if (kw.getLocationCode()  != null) {
                params.put("locCd",  kw.getLocationCode()); 
                params.put("locCds", kw.getLocationCode()); 
            }

            steps.add(new Intent(targetIntentType, params));
            log.info("  ➕ step: {} params={}", targetIntentType, params);
        }

        // ── Fallback ──────────────────────────────────────────────────────
        if (steps.isEmpty()) {
            log.warn("  ⚠️ No steps — needsLlm=true");
            steps.add(new Intent(Intent.UNKNOWN));
            needsLlm = true;
        }

        if (containsType(steps, Intent.UNKNOWN)
                || containsType(steps, Intent.SQL_QUERY)) {
            needsLlm = true;
        }

        Plan plan = new Plan(steps, needsLlm, modifier, "generic-pipeline");
        log.info("📋 Plan: {}", plan);
        return plan;
    }

    // ── Helper ────────────────────────────────────────────────────────
    private boolean containsType(List<Intent> steps, String type) {
        for (Intent s : steps) {
            if (type.equals(s.type)) return true;
        }
        return false;
    }
}