package com.ais.service;
import com.ais.model.ExtractedKeywords;
import com.ais.model.IntentStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
public class QueryPlanner {
    private static final Logger log = LoggerFactory.getLogger(QueryPlanner.class);
    private final MCPClientService toolCatalog;
    public QueryPlanner() {
        this(new MCPClientService());
    }
    public QueryPlanner(MCPClientService toolCatalog) {
        if (toolCatalog == null) {
            throw new IllegalArgumentException("toolCatalog must not be null");
        }
        this.toolCatalog = toolCatalog;
    }
    public Plan plan(ExtractedKeywords keywords) {
        return analyse(null, keywords);
    }
    public Plan analyse(String userPrompt, ExtractedKeywords keywords) {
        if (keywords == null) {
            return delegatedPlan(null, "null keywords");
        }
        if (keywords.getPlan() != null && !keywords.getPlan().isEmpty()) {
            List<Intent> llmSteps = convertLlmPlan(keywords.getPlan(), keywords);
            if (!llmSteps.isEmpty()) {
                boolean needsLlm = containsType(llmSteps, Intent.SQL_QUERY) || containsType(llmSteps, Intent.UNKNOWN);
                return new Plan(llmSteps, needsLlm, keywords.getModifier(), "llm-generated-plan");
            }
            log.warn("[Planner] Invalid or incomplete LLM plan");
        }
        List<String> intents = keywords.safeIntents();
        if (intents == null || intents.isEmpty()) {
            return delegatedPlan(keywords.getModifier(), "no intent");
        }
        /*
         * Compound queries are handled only by an explicit
         * validated LLM plan or by the agent loop.
         */
        if (intents.size() != 1 || keywords.isCompound()) {
            return delegatedPlan(keywords.getModifier(), "compound query delegated to agent");
        }
        Map<String, String> candidates = buildKeywordCandidates(keywords);
        String rawIntent = intents.get(0);
        if (isInternalIntent(rawIntent)) {
            String internalType = normalizeInternalIntent(rawIntent);
            List<Intent> steps = new ArrayList<>();
            steps.add(new Intent(internalType, null, candidates));
            return new Plan(steps, true, keywords.getModifier(), "internal LLM route");
        }
        ToolDefinition definition = toolCatalog.resolveDefinition(rawIntent, candidates);
        if (definition == null) {
            return delegatedPlan(keywords.getModifier(), "tool metadata unavailable");
        }
        Map<String, String> params = adaptParameters(candidates, definition);
        params.put("relation", "independent");
        if (!isValidStep(definition, params)) {
            return delegatedPlan(keywords.getModifier(), "missing required tool parameters");
        }
        String intentType = canonicalType(definition);
        Intent step = new Intent(intentType, definition.getToolName(), params);
        List<Intent> steps = new ArrayList<>();
        steps.add(step);
        return new Plan(steps, false, keywords.getModifier(), "generic-single-tool-plan");
    }
    private List<Intent> convertLlmPlan(List<IntentStep> llmSteps, ExtractedKeywords keywords) {
        List<IntentStep> sorted = new ArrayList<>();
        if (llmSteps != null) {
            for (IntentStep step : llmSteps) {
                if (step != null) {
                    sorted.add(step);
                }
            }
        }
        Collections.sort(sorted, new Comparator<IntentStep>() {
            @Override
            public int compare(IntentStep left, IntentStep right) {
                return Integer.compare(left.getPriority(), right.getPriority());
            }
        });
        List<Intent> steps = new ArrayList<>();
        boolean fatalInvalidPlan = false;
        for (int index = 0; index < sorted.size(); index++) {
            IntentStep step = sorted.get(index);
            Map<String, String> rawParams = new LinkedHashMap<>();
            if (step.getParams() != null) rawParams.putAll(step.getParams());
            String rawRelation = hasText(step.getRelation()) ? step.getRelation() : rawParams.get("relation");
            String relation = normalizeRelation(rawRelation);
            if (relation == null) {
                fatalInvalidPlan = true;
                continue;
            }
            if (index == 0 && ("filter_previous".equals(relation) || "enrich_previous".equals(relation))) {
                log.warn("[Planner] {} cannot be used on the first step", relation);
                fatalInvalidPlan = true;
                continue;
            }
            rawParams.put("relation", relation);
            String rawType = step.getType();
            if (isInternalIntent(rawType)) {
                steps.add(new Intent(normalizeInternalIntent(rawType), null, rawParams));
                continue;
            }
            ToolDefinition definition = toolCatalog.resolveDefinition(rawType, rawParams);
            if (definition == null) {
                fatalInvalidPlan = true;
                continue;
            }
            copyKeywordFallbacks(rawParams, definition, keywords);
            Map<String, String> params = adaptParameters(rawParams, definition);
            params.put("relation", relation);
            if (keywords.isShowDetails() && !params.containsKey("showDetails")) {
                params.put("showDetails", "true");
            }
            
            if (isUnnecessarySingularProjection(relation,definition,params,steps)) {
                log.info("[Planner] Dropping unnecessary singular " + "projection after list filters");
                continue;
            }
            
            if (!isValidStep(definition, params)) {
                if (isSkippableEnrichment(relation, definition, params, steps)) {
                    log.info("[Planner] Dropping non-filtering enrichment step with unavailable previous-code input: {}", rawType);
                    continue;
                }
                log.warn("[Planner] Invalid LLM step: type={}, params={}", rawType, params);
                fatalInvalidPlan = true;
                continue;
            }
            steps.add(new Intent(canonicalType(definition), definition.getToolName(), params));
        }
        if (fatalInvalidPlan || steps.isEmpty()) {
            return new ArrayList<>();
        }
        distributeModifier(steps, keywords.getModifier());
        return steps;
    }
    private Map<String, String> buildKeywordCandidates(ExtractedKeywords keywords) {
        Map<String, String> result = new LinkedHashMap<>();
        putIfText(result, "psm", keywords.getPsm());
        putIfText(result, "deptCd", keywords.getDepartment());
        putIfText(result, "filter", keywords.getFilter());
        putIfText(result, "grade", keywords.getGrade());
        putIfText(result, "reportType", keywords.getReportType());
        putIfText(result, "locationName", keywords.getLocationName());
        putIfText(result, "modifier", keywords.getModifier());
        putIfText(result, "excludeUndefinedField", keywords.getExcludeUndefinedField());
        if (keywords.getLimit() != null) {
            result.put("limit", String.valueOf(keywords.getLimit()));
        }
        if (hasText(keywords.getLocationCode())) {
            result.put("locCd", keywords.getLocationCode());
            result.put("locCds", keywords.getLocationCode());
        }
        return result;
    }
    private Map<String, String> adaptParameters(Map<String, String> source, ToolDefinition definition) {
        Set<String> accepted = toolCatalog.getAcceptedParameters(definition);
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (accepted.contains(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        String locationName = source.get("locationName");
        if (hasText(locationName)) {
            if (accepted.contains("locName") && !result.containsKey("locName")) {
                result.put("locName", locationName);
            } else if (accepted.contains("location") && !result.containsKey("location")) {
                result.put("location", locationName);
            }
        }
        copyControl(source, result, "relation");
        copyControl(source, result, "modifier");
        copyControl(source, result, "showDetails");
        copyControl(source, result, "codesSource");
        return result;
    }
    private void copyKeywordFallbacks(Map<String, String> params, ToolDefinition definition, ExtractedKeywords keywords) {
        Set<String> accepted = toolCatalog.getAcceptedParameters(definition);
        putIfAbsent(params, accepted, "psm", keywords.getPsm());
        putIfAbsent(params, accepted, "deptCd", keywords.getDepartment());
        putIfAbsent(params, accepted, "filter", keywords.getFilter());
        putIfAbsent(params, accepted, "grade", keywords.getGrade());
        putIfAbsent(params, accepted, "reportType", keywords.getReportType());
        putIfAbsent(params, accepted, "excludeUndefinedField", keywords.getExcludeUndefinedField());
        if (keywords.getLimit() != null && accepted.contains("limit") && !params.containsKey("limit")) {
            params.put("limit", String.valueOf(keywords.getLimit()));
        }
        putIfAbsent(params, accepted, "locCd", keywords.getLocationCode());
        putIfAbsent(params, accepted, "locCds", keywords.getLocationCode());
        putIfAbsent(params, accepted, "locName", keywords.getLocationName());
        putIfAbsent(params, accepted, "location", keywords.getLocationName());
    }
    private void putIfAbsent(Map<String, String> params, Set<String> accepted, String key, String value) {
        if (hasText(value) && accepted.contains(key) && !params.containsKey(key)) {
            params.put(key, value);
        }
    }
    private void putIfText(Map<String, String> params, String key, String value) {
        if (hasText(value)) {
            params.put(key, value);
        }
    }
    private void copyControl(Map<String, String> source, Map<String, String> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }
    private boolean isSkippableEnrichment(String relation, ToolDefinition definition,
            Map<String, String> params, List<Intent> previousSteps) {
        if (!"enrich_previous".equals(relation) || definition == null
                || previousSteps == null || previousSteps.isEmpty()) {
            return false;
        }
        for (String required : definition.getRequiredParameters()) {
            if (hasText(params.get(required))) continue;
            if (!"locCd".equals(required) && !"locCds".equals(required)) return false;
        }
        Set<String> accepted = toolCatalog.getAcceptedParameters(definition);
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            if (!hasText(entry.getValue()) || "relation".equals(key) || "showDetails".equals(key)
                    || "modifier".equals(key) || "codesSource".equals(key)
                    || "locCd".equals(key) || "locCds".equals(key)) continue;
            if (accepted.contains(key)) return false;
        }
        return true;
    }
    private boolean isValidStep(ToolDefinition definition, Map<String, String> params) {
        if (definition == null) return false;
        String relation = params.get("relation");
        if (relation != null && !definition.getSupportedRelations().contains(relation)) {
            return false;
        }
        if ("use_previous_codes".equals(relation) && !definition.getConsumedFields().contains("LOC_CD")) {
            return false;
        }
        for (String required : definition.getRequiredParameters()) {
            if (hasText(params.get(required))) continue;
            if ("use_previous_codes".equals(relation) && ("locCd".equals(required) || "locCds".equals(required))) {
                continue;
            }
            return false;
        }
        return true;
    }
    private String canonicalType(ToolDefinition definition) {
        if (definition == null || definition.getIntentTypes().isEmpty()) {
            return Intent.UNKNOWN;
        }
        return definition.getIntentTypes().iterator().next();
    }
    private boolean isInternalIntent(String rawType) {
        if (rawType == null) return false;
        String normalized = rawType.trim().toUpperCase();
        return Intent.SQL_QUERY.equals(normalized) || Intent.UNKNOWN.equals(normalized);
    }
    private String normalizeInternalIntent(String rawType) {
        if (rawType == null) return Intent.UNKNOWN;
        String normalized = rawType.trim().toUpperCase();
        if (Intent.SQL_QUERY.equals(normalized)) {
            return Intent.SQL_QUERY;
        }
        return Intent.UNKNOWN;
    }
    private String normalizeRelation(String rawRelation) {
        if (rawRelation == null || rawRelation.trim().isEmpty()) {
            return "independent";
        }
        String relation = rawRelation.trim().toLowerCase();
        if ("independent".equals(relation) || "filter_previous".equals(relation) ||
            "enrich_previous".equals(relation) || "use_previous_codes".equals(relation)) {
            return relation;
        }
        return null;
    }
    private void distributeModifier(List<Intent> steps, String keywordModifier) {
        if (steps == null || steps.isEmpty()) return;
        Intent explicitTarget = null;
        String explicitModifier = null;
        /* Preserve an explicitly assigned step modifier. This includes use_previous_codes steps. */
        for (Intent step : steps) {
            if (step == null || step.params == null) continue;
            String stepModifier = step.params.get("modifier");
            if (stepModifier == null || stepModifier.trim().isEmpty()) continue;
            if (explicitTarget != null) {
                log.warn("[Planner] Multiple step modifiers found; using the last explicit modifier");
                explicitTarget.params.remove("modifier");
            }
            explicitTarget = step;
            explicitModifier = stepModifier.trim().toUpperCase(Locale.ROOT);
        }
        /* Explicit plan-level step modifiers take precedence. */
        if (explicitTarget != null) {
            explicitTarget.params.put("modifier", explicitModifier);
            return;
        }
        String modifier = hasText(keywordModifier) ? keywordModifier.trim().toUpperCase(Locale.ROOT) : null;
        if (modifier == null) return;
        /* If the LLM supplied only a top-level modifier, prefer the last step that is not merely consuming previous codes. */
        Intent target = null;
        for (int i = steps.size() - 1; i >= 0; i--) {
            Intent step = steps.get(i);
            if (step == null || step.params == null) continue;
            String relation = step.params.get("relation");
            if (!"use_previous_codes".equals(relation)) {
                target = step;
                break;
            }
        }
        if (target == null) {
            target = steps.get(steps.size() - 1);
        }
        target.params.put("modifier", modifier);
    }
    private boolean containsType(List<Intent> steps, String type) {
        for (Intent step : steps) {
            if (step != null && type.equals(step.type)) {
                return true;
            }
        }
        return false;
    }
    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
    private Plan delegatedPlan(String modifier, String reason) {
        List<Intent> steps = new ArrayList<>();
        steps.add(new Intent(Intent.UNKNOWN, null, new LinkedHashMap<>()));
        return new Plan(steps, true, modifier, reason);
    }
    
    private boolean isUnnecessarySingularProjection(
            String relation,
            ToolDefinition definition,
            Map<String, String> params,
            List<Intent> previousSteps) {
        if (definition == null
                || previousSteps == null
                || previousSteps.isEmpty()) {
            return false;
        }
        if (!"use_previous_codes".equals(relation) && !"enrich_previous".equals(relation)) {
            return false;
        }
        if (hasText(params.get("modifier"))) {
            return false;
        }
        Set<String> accepted = toolCatalog.getAcceptedParameters(definition);
        boolean singularCodeInput = accepted.contains("locCd") && !accepted.contains("locCds");
        if (!singularCodeInput) {
            return false;
        }
        for (String required : definition.getRequiredParameters()) {
            if (!"locCd".equals(required) && !"locCds".equals(required)) {
                return false;
            }
        }
        return true;
    }
}
