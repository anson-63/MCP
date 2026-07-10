package com.ais.service;

import com.ais.model.ExtractedKeywords;
import com.ais.model.IntentStep;

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
            log.warn(" QueryPlanner: null keywords");
            List<Intent> steps = new ArrayList<Intent>();
            steps.add(new Intent(Intent.UNKNOWN));
            return new Plan(steps, true, null, "null keywords");
        }

        // ═════════════════════════════════════════════════════════════════
        // LLM-GENERATED PLAN (new scalable path)
        // If the LLM returned an ordered plan with priorities and relations,
        // use it directly and skip the hardcoded rule engine.
        // ═════════════════════════════════════════════════════════════════
        if (kw.getPlan() != null && !kw.getPlan().isEmpty()) {
            List<Intent> steps = convertLlmPlan(kw.getPlan(), kw);
            if (!steps.isEmpty()) {
                Plan plan = new Plan(steps, false, kw.getModifier(), "llm-generated-plan");
                log.info("[Manual Info]LLM-generated plan: {}", plan);
                return plan;
            }
        }

        List<String> intentStrings = kw.safeIntents();
        String primaryIntent = kw.primaryIntent();

        log.info("[Manual Info]QueryPlanner intents={} primary={} modifier={} psm={} dept={} filter={} grade={}",
                intentStrings, primaryIntent, kw.getModifier(), kw.getPsm(),
                kw.getDepartment(), kw.getFilter(), kw.getGrade());

        List<Intent> steps = new ArrayList<Intent>();
        String modifier = kw.getModifier();
        boolean needsLlm = false;

        // ── Detect compound patterns BEFORE individual processing ─────────
        boolean hasPsm = intentStrings.contains("PSM");
        boolean hasCodeHistory = intentStrings.contains("CODE_HISTORY");
        boolean hasLocCode = intentStrings.contains("LOCATION_CODE");
        boolean hasMonument = intentStrings.contains("MONUMENT");
        boolean hasHistoric = intentStrings.contains("HISTORIC");
        boolean hasDepartment = intentStrings.contains("DEPARTMENT");
        boolean hasReport = intentStrings.contains("REPORT");

        // Respect LLM-designated primary intent when it differs from first intent
        if (primaryIntent != null && !primaryIntent.isEmpty()) {
            log.info("[Manual Info] LLM designated primaryIntent={}", primaryIntent);
        }

        // ══════════════════════════════════════════════════════════════════
        // COMPOUND PATTERN: HISTORIC + PSM
        // "Show historic buildings under PSM/KT"
        // "Find the first historic location under PSM/KT"
        // Step 1: Get locations under PSM/KT
        // Step 2: Cross-filter with historic buildings (keep only codes in both)
        // Modifier FIRST (or showDetails) triggers hardcode_query on the first match
        // ══════════════════════════════════════════════════════════════════
        if (hasHistoric && hasPsm && kw.getPsm() != null) {
            Map<String, String> psmParams = new LinkedHashMap<String, String>();
            psmParams.put("psm", kw.getPsm());
            steps.add(new Intent(Intent.PSM_LOCATIONS, psmParams));
            log.info("[Manual Info]   HISTORIC+PSM step 1: PSM_LOCATIONS psm={}", kw.getPsm());

            Map<String, String> histParams = new LinkedHashMap<String, String>();
            histParams.put("grade", kw.getGrade() != null ? kw.getGrade() : "ALL");
            histParams.put("crossFilterWith", "previous");
            if (modifier != null) {
                histParams.put("modifier", modifier);
            }
            if (kw.isShowDetails()) {
                histParams.put("showDetails", "true");
            }
            steps.add(new Intent(Intent.HISTORIC_BUILDING, histParams));
            log.info("[Manual Info]   HISTORIC+PSM step 2: HISTORIC_BUILDING crossFilterWith=previous");

            Plan plan = new Plan(steps, false, modifier, "compound: HISTORIC+PSM");
            log.info("[Manual Info]Plan: {}", plan);
            return plan;
        }

        // ══════════════════════════════════════════════════════════════════
        // COMPOUND PATTERN: HISTORIC + DEPARTMENT
        // "Which AFCD locations are historic buildings?"
        // Step 1: Get locations by department
        // Step 2: Cross-filter with historic buildings
        // ══════════════════════════════════════════════════════════════════
        if (hasHistoric && hasDepartment && kw.getDepartment() != null) {
            Map<String, String> deptParams = new LinkedHashMap<String, String>();
            deptParams.put("deptCd", kw.getDepartment());
            steps.add(new Intent(Intent.DEPARTMENT_LOCATIONS, deptParams));
            log.info("[Manual Info]   HISTORIC+DEPARTMENT step 1: DEPARTMENT_LOCATIONS deptCd={}", kw.getDepartment());

            Map<String, String> histParams = new LinkedHashMap<String, String>();
            histParams.put("grade", kw.getGrade() != null ? kw.getGrade() : "ALL");
            histParams.put("crossFilterWith", "previous");
            if (modifier != null) {
                histParams.put("modifier", modifier);
            }
            if (kw.isShowDetails()) {
                histParams.put("showDetails", "true");
            }
            steps.add(new Intent(Intent.HISTORIC_BUILDING, histParams));
            log.info("[Manual Info]   HISTORIC+DEPARTMENT step 2: HISTORIC_BUILDING crossFilterWith=previous");

            Plan plan = new Plan(steps, false, modifier, "compound: HISTORIC+DEPARTMENT");
            log.info("[Manual Info]Plan: {}", plan);
            return plan;
        }

        // ══════════════════════════════════════════════════════════════════
        // COMPOUND PATTERN: MONUMENT + PSM
        // "Show declared monuments under PSM/KT"
        // ══════════════════════════════════════════════════════════════════
        if (hasMonument && hasPsm && kw.getPsm() != null) {
            Map<String, String> psmParams = new LinkedHashMap<String, String>();
            psmParams.put("psm", kw.getPsm());
            steps.add(new Intent(Intent.PSM_LOCATIONS, psmParams));
            log.info("[Manual Info]   MONUMENT+PSM step 1: PSM_LOCATIONS psm={}", kw.getPsm());

            Map<String, String> monParams = new LinkedHashMap<String, String>();
            monParams.put("filter", kw.getFilter() != null ? kw.getFilter() : "T");
            monParams.put("crossFilterWith", "previous");
            if (modifier != null) {
                monParams.put("modifier", modifier);
            }
            if (kw.isShowDetails()) {
                monParams.put("showDetails", "true");
            }
            steps.add(new Intent(Intent.DECLARED_MONUMENT, monParams));
            log.info("[Manual Info]   MONUMENT+PSM step 2: DECLARED_MONUMENT crossFilterWith=previous");

            Plan plan = new Plan(steps, false, modifier, "compound: MONUMENT+PSM");
            log.info("[Manual Info]Plan: {}", plan);
            return plan;
        }

        // ══════════════════════════════════════════════════════════════════
        // COMPOUND PATTERN: MONUMENT + DEPARTMENT
        // "Which LCSD monuments are historic buildings?" (handled by MONUMENT+HISTORIC)
        // "Show declared monuments for department LCSD"
        // ══════════════════════════════════════════════════════════════════
        if (hasMonument && hasDepartment && kw.getDepartment() != null) {
            Map<String, String> deptParams = new LinkedHashMap<String, String>();
            deptParams.put("deptCd", kw.getDepartment());
            steps.add(new Intent(Intent.DEPARTMENT_LOCATIONS, deptParams));
            log.info("[Manual Info]   MONUMENT+DEPARTMENT step 1: DEPARTMENT_LOCATIONS deptCd={}", kw.getDepartment());

            Map<String, String> monParams = new LinkedHashMap<String, String>();
            monParams.put("filter", kw.getFilter() != null ? kw.getFilter() : "T");
            monParams.put("crossFilterWith", "previous");
            if (modifier != null) {
                monParams.put("modifier", modifier);
            }
            if (kw.isShowDetails()) {
                monParams.put("showDetails", "true");
            }
            steps.add(new Intent(Intent.DECLARED_MONUMENT, monParams));
            log.info("[Manual Info]   MONUMENT+DEPARTMENT step 2: DECLARED_MONUMENT crossFilterWith=previous");

            Plan plan = new Plan(steps, false, modifier, "compound: MONUMENT+DEPARTMENT");
            log.info("[Manual Info]Plan: {}", plan);
            return plan;
        }

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
            if (kw.getModifier() != null) {
                psmParams.put("modifier", kw.getModifier());
            }
            steps.add(new Intent(Intent.PSM_LOCATIONS, psmParams));
            log.info("[Manual Info]   COMPOUND PSM+CODE step 1: PSM_LOCATIONS psm={}", kw.getPsm());

            // Step 2: Act on first result
            String actionType = hasCodeHistory
                    ? Intent.CODE_HISTORY : Intent.LOCATION_CODE;
            Map<String, String> actionParams = new LinkedHashMap<String, String>();
            actionParams.put("codesSource", "previous_first");
            actionParams.put("modifier", modifier != null ? modifier : "FIRST");
            steps.add(new Intent(actionType, actionParams));
            log.info("[Manual Info]   COMPOUND PSM+CODE step 2: {} on first result", actionType);

            Plan plan = new Plan(steps, false, modifier, "compound: PSM+" + actionType);
            log.info("[Manual Info]Plan: {}", plan);
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
            if (kw.getLocationName() != null) {
                monParams.put("locationFilter", kw.getLocationName());
            }
            steps.add(new Intent(Intent.DECLARED_MONUMENT, monParams));
            log.info("[Manual Info]   COMPOUND MONUMENT+HISTORIC step 1: DECLARED_MONUMENT");

            // Step 2: Enrich with historic grade (LEFT JOIN style — not cross-filter)
            Map<String, String> histParams = new LinkedHashMap<String, String>();
            histParams.put("grade", kw.getGrade() != null ? kw.getGrade() : "ALL");
            histParams.put("enrich", "true");   // ← signals LEFT JOIN, not cross-filter
            steps.add(new Intent(Intent.HISTORIC_BUILDING, histParams));
            log.info("[Manual Info]   COMPOUND MONUMENT+HISTORIC step 2: HISTORIC_BUILDING (enrich)");

            Plan plan = new Plan(steps, false, modifier, "compound: MONUMENT+HISTORIC");
            log.info("[Manual Info]Plan: {}", plan);
            return plan;
        }

        if (hasHistoric && hasLocCode && kw.getLocationCode() != null) {
            Map<String, String> params = new LinkedHashMap<String, String>();
            params.put("locCd", kw.getLocationCode());
            params.put("includeHistoric", "true"); // signals renderer to show grade
            steps.add(new Intent(Intent.LOCATION_CODE, params));
            log.info("[Manual Info]   COMPOUND HISTORIC+LOCATION_CODE → single hardcode_query for {}",
                    kw.getLocationCode());

            Plan plan = new Plan(steps, false, modifier, "compound: HISTORIC+LOCATION_CODE");
            log.info("[Manual Info]Plan: {}", plan);
            return plan;
        }

        // ══════════════════════════════════════════════════════════════════
        // SINGLE / OTHER INTENTS — process each individually
        // ══════════════════════════════════════════════════════════════════
        for (String intentStr : intentStrings) {
            Intent base = Intent.of(intentStr);
            String targetIntentType = base.type;

            if ("DEPARTMENT".equalsIgnoreCase(intentStr) && (kw.getDepartment() == null || kw.getDepartment().trim().isEmpty())) {
                log.info("[Manual Info]   Intent DEPARTMENT lacks deptCd param — delegating to LLM");
                needsLlm = true;
                continue;
            }

            if ("PSM".equalsIgnoreCase(intentStr)) {
                if (kw.getPsm() == null || kw.getPsm().trim().isEmpty()) {
                    log.info("[Manual Info] Intent PSM has null psm param — routing to LIST_PSMS tool");
                    targetIntentType = Intent.PSM_LIST;
                } else {
                    // A specific PSM was named — always route to PSM_LOCATIONS regardless
                    // of what Intent.of("PSM") maps to by default.
                    log.info("[Manual Info] Intent PSM has psm={} — routing to PSM_LOCATIONS tool", kw.getPsm().trim());
                    targetIntentType = Intent.PSM_LOCATIONS;
                }
            }

            if ("LOCATION_CODE".equalsIgnoreCase(intentStr) && (kw.getLocationCode() == null || kw.getLocationCode().trim().isEmpty())) {
                log.info("[Manual Info]   Intent LOCATION_CODE lacks locCd param — delegating to LLM");
                needsLlm = true;
                continue;
            }

            // ── DYNAMIC MULTI-REPORT SPLITTING ("ALL" or "BSI,KAI") ──
            if ("REPORT".equalsIgnoreCase(intentStr) || Intent.CHECK_REPORTS.equalsIgnoreCase(targetIntentType)) {
                String repTypeStr = kw.getReportType();
                if (repTypeStr == null || repTypeStr.trim().isEmpty()) {
                    log.info("[Manual Info]   Intent REPORT lacks reportType param — delegating to LLM");
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
                            repParams.put("locCd", kw.getLocationCode());
                            repParams.put("locCds", kw.getLocationCode());
                        }
                        repParams.put("reportType", singleRep);
                        if (kw.getModifier() != null) {
                            repParams.put("modifier", kw.getModifier());
                        }
                        steps.add(new Intent(targetIntentType, repParams));
                        log.info("[Manual Info]   MULTI-REPORT step: {} params={}", targetIntentType, repParams);
                    }
                }
                continue; // We successfully scheduled all report steps, skip the default single insertion!
            }

            Map<String, String> params = new LinkedHashMap<String, String>();

            // ── Inject all relevant keyword fields into params ────────────
            if (kw.getPsm() != null) {
                params.put("psm", kw.getPsm().trim());
            }
            if (kw.getDepartment() != null) {
                params.put("deptCd", kw.getDepartment());
            }
            if (kw.getFilter() != null) {
                params.put("filter", kw.getFilter());
            }
            if (kw.getGrade() != null) {
                params.put("grade", kw.getGrade());
            }
            if (kw.getLocationName() != null) {
                params.put("locName", kw.getLocationName());
            }
            if (kw.getReportType() != null) {
                params.put("reportType", kw.getReportType());
            }
            if (kw.getModifier() != null) {
                params.put("modifier", kw.getModifier());
            }
            if (kw.getLocationCode() != null) {
                params.put("locCd", kw.getLocationCode());
                params.put("locCds", kw.getLocationCode());
            }
            //carry a "top N"/"first N" limit through to buildArgs()
            if (kw.getLimit() != null) {
                params.put("limit", String.valueOf(kw.getLimit()));
            }
            if (kw.getExcludeUndefinedField() != null && !kw.getExcludeUndefinedField().trim().isEmpty()) {
                params.put("excludeUndefinedField", kw.getExcludeUndefinedField().trim());
            }
            steps.add(new Intent(targetIntentType, params));
            log.info("[Manual Info]   step: {} params={}", targetIntentType, params);
        }

        // ── Fallback ──────────────────────────────────────────────────────
        if (steps.isEmpty()) {
            log.warn("   No steps — needsLlm=true");
            steps.add(new Intent(Intent.UNKNOWN));
            needsLlm = true;
        }

        if (containsType(steps, Intent.UNKNOWN)
                || containsType(steps, Intent.SQL_QUERY)) {
            needsLlm = true;
        }

        Plan plan = new Plan(steps, needsLlm, modifier, "generic-pipeline");
        log.info("[Manual Info]Plan: {}", plan);
        return plan;
    }

    // ═════════════════════════════════════════════════════════════════
    // CONVERT LLM-GENERATED PLAN TO INTERNAL INTENT LIST
    // ═════════════════════════════════════════════════════════════════
    private List<Intent> convertLlmPlan(List<IntentStep> llmSteps, ExtractedKeywords kw) {
        List<Intent> steps = new ArrayList<Intent>();
        // Sort by priority ascending (1 = first)
        List<IntentStep> sorted = new ArrayList<IntentStep>(llmSteps);
        sorted.sort((a, b) -> Integer.compare(a.getPriority(), b.getPriority()));
        for (IntentStep step : sorted) {
            if (step == null || step.getType() == null || step.getType().trim().isEmpty()) {
                log.warn(" Skipping invalid LLM plan step: {}", step);
                continue;
            }
            String type = normalizeIntentType(step.getType());
            Map<String, String> params = new LinkedHashMap<String, String>();
            if (step.getParams() != null) {
                params.putAll(step.getParams());
            }
            // Carry forward common keyword fields if the LLM did not provide them in the step params
            if (!params.containsKey("psm") && kw.getPsm() != null) {
                params.put("psm", kw.getPsm());
            }
            if (!params.containsKey("deptCd") && kw.getDepartment() != null) {
                params.put("deptCd", kw.getDepartment());
            }
            if (!params.containsKey("grade") && kw.getGrade() != null) {
                params.put("grade", kw.getGrade());
            }
            if (!params.containsKey("filter") && kw.getFilter() != null) {
                params.put("filter", kw.getFilter());
            }
            if (!params.containsKey("locCd") && kw.getLocationCode() != null) {
                params.put("locCd", kw.getLocationCode());
                params.put("locCds", kw.getLocationCode());
            }
            if (!params.containsKey("locName") && kw.getLocationName() != null) {
                params.put("locName", kw.getLocationName());
            }
            if (!params.containsKey("reportType") && kw.getReportType() != null) {
                params.put("reportType", kw.getReportType());
            }
            //carry a "top N"/"first N" limit through to buildArgs(),
            // same as the single-intent path above. Only applied if the
            // LLM's own plan step didn't already specify its own limit.
            if (!params.containsKey("limit") && kw.getLimit() != null) {
                params.put("limit", String.valueOf(kw.getLimit()));
            }
            
            if (!params.containsKey("excludeUndefinedField") && kw.getExcludeUndefinedField() != null) {
                params.put("excludeUndefinedField", String.valueOf(kw.getExcludeUndefinedField()));
            }
            // Store the relation for the generic executor to read
            String relation = step.getRelation();
            // Preserve showDetails from keywords if the step does not override it
            if (kw.isShowDetails() && !params.containsKey("showDetails")) {
                params.put("showDetails", "true");
            }
            log.info("[Manual Info]   LLM step: {} priority={} relation={} params={}",
                    type, step.getPriority(), relation, params);
            steps.add(new Intent(type, params));
        }

        // ═════════════════════════════════════════════════════════════════
        // Distribute the modifier to EXACTLY ONE step
        // ═════════════════════════════════════════════════════════════════
        // The modifier (FIRST/OLDEST/NEWEST/etc.) must act on the result that
        // is returned to the user. In a chain:
        //   - filter_previous / enrich_previous: the final filtering step is the answer
        //   - use_previous_codes: the PREVIOUS step produced the codes and is the answer
        // We first strip any modifier the LLM may have placed on intermediate
        // steps, then attach it to the correct target step.
        String modifier = (kw.getModifier() != null && !kw.getModifier().trim().isEmpty())
                ? kw.getModifier().trim().toUpperCase() : null;
        // Also collect any modifier the LLM placed on a specific step (e.g. it
        // might put "FIRST" on HISTORIC_BUILDING). We still honor it, but we
        // move it to the correct target if it is on the wrong step.
        for (Intent step : steps) {
            if (step.params.containsKey("modifier")) {
                String stepMod = step.params.get("modifier").trim().toUpperCase();
                if (modifier == null) {
                    modifier = stepMod;
                }
                step.params.remove("modifier");
            }
        }
        if (modifier != null && !steps.isEmpty()) {
            Intent target = null;
            // Target = the last step that is NOT "use_previous_codes".
            // For HISTORIC+PSM the last step is HISTORIC_BUILDING (filter_previous).
            // For PSM+REPORT the last step is CHECK_REPORTS (use_previous_codes),
            // so the target becomes the PSM_LOCATIONS step before it.
            for (int i = steps.size() - 1; i >= 0; i--) {
                Intent step = steps.get(i);
                String rel = step.params.get("relation");
                if (!"use_previous_codes".equals(rel)) {
                    target = step;
                    break;
                }
            }
            if (target == null) {
                target = steps.get(steps.size() - 1);
            }
            target.params.put("modifier", modifier);
            log.info("[Manual Info]   LLM plan: applying modifier '{}' to target step {} (relation={}, primary={})",
                    modifier, target.type, target.params.get("relation"), kw.primaryIntent());
        }

        return steps;
    }

    private String normalizeIntentType(String llmType) {
        if (llmType == null) {
            return null;
        }
        String t = llmType.trim().toUpperCase();
        switch (t) {
            case "HISTORIC":
            case "HISTORIC_BUILDING":
                return Intent.HISTORIC_BUILDING;
            case "MONUMENT":
            case "DECLARED_MONUMENT":
                return Intent.DECLARED_MONUMENT;
            case "DEPARTMENT":
            case "DEPARTMENT_LOCATIONS":
                return Intent.DEPARTMENT_LOCATIONS;
            case "PSM":
            case "PSM_LOCATIONS":
                return Intent.PSM_LOCATIONS;
            case "PSM_LIST":
            case "LIST_PSMS":
                return Intent.PSM_LIST;
            case "REPORT":
            case "CHECK_REPORTS":
                return Intent.CHECK_REPORTS;
            case "NAME_SEARCH":
            case "SEARCH_BY_NAME":
                return Intent.NAME_SEARCH;
            case "LOCATION_CODE":
            case "HARDCODE_QUERY":
                return Intent.LOCATION_CODE;
            case "CODE_HISTORY":
            case "SEARCH_LOC_CD_HISTORY":
                return Intent.CODE_HISTORY;
            case "SQL_QUERY":
                return Intent.SQL_QUERY;
            case "UNKNOWN":
                return Intent.UNKNOWN;
            default:
                return t;
        }
    }

    // ── Helper ────────────────────────────────────────────────────────
    private boolean containsType(List<Intent> steps, String type) {
        for (Intent s : steps) {
            if (type.equals(s.type)) {
                return true;
            }
        }
        return false;
    }
}
