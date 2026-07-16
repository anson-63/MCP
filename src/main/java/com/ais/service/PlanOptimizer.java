package com.ais.service;
import java.util.*;
public final class PlanOptimizer {
    private static final Set<String> CANONICAL_PARAMETERS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "locName", "location", "psm", "deptCd", "grade", "filter", "reportType", "locCd", "locCds", "limit", "excludeUndefinedField", "modifier")));
    private final MCPClientService catalog;
    public PlanOptimizer(MCPClientService catalog) {
        if (catalog == null) throw new IllegalArgumentException("catalog must not be null");
        this.catalog = catalog;
    }
    public Plan optimize(Plan original) {
        if (original == null || original.steps == null || original.steps.size() < 2) return original;

        Map<String, String> merged = new LinkedHashMap<String, String>();
        LinkedHashSet<String> reports = new LinkedHashSet<String>();
        int composedSteps = 0;

        for (int i = 0; i < original.steps.size(); i++) {
            Intent step = original.steps.get(i);
            if (step == null || step.params == null) return original;

            String relation = normalizeRelation(step.params.get("relation"));
            if ((i == 0 && !"independent".equals(relation))
                    || (i > 0 && "independent".equals(relation))) return original;

            ToolDefinition definition = catalog.resolveDefinition(step.type, step.params);
            Set<String> accepted = catalog.getAcceptedParameters(definition);

            if (isIgnorableSingularProjection(step, definition, accepted, relation, i)) continue;
            if (!isComposableTool(definition, accepted, relation)
                    || !mergeCanonicalParameters(merged, reports, step.params, accepted)) return original;
            composedSteps++;
        }

        if (composedSteps < 2) return original;
        if (!hasText(merged.get("modifier")) && hasText(original.modifier)) {
            merged.put("modifier", original.modifier.trim().toUpperCase(Locale.ROOT));
        }
        if (!reports.isEmpty()) merged.put("reportType", String.join(",", reports));
        if (merged.isEmpty()) return original;

        ToolDefinition composedDefinition = catalog.resolveDefinition("LOCATION_QUERY", merged);
        if (composedDefinition == null) return original;

        String composedType = composedDefinition.getIntentTypes().isEmpty()
                ? "LOCATION_QUERY" : composedDefinition.getIntentTypes().iterator().next();
        Intent composed = new Intent(composedType, composedDefinition.getToolName(), merged);
        return new Plan(Collections.singletonList(composed), false,
                hasText(original.modifier) ? original.modifier : null,
                "database-composed-query");
    }

    private boolean isIgnorableSingularProjection(Intent step, ToolDefinition definition,
            Set<String> accepted, String relation, int index) {
        if (index == 0 || definition == null || accepted == null) return false;
        if (!"use_previous_codes".equals(relation) && !"enrich_previous".equals(relation)) return false;
        if (hasText(step.params.get("modifier")) || hasText(step.params.get("locCd"))) return false;
        if (!accepted.contains("locCd") || accepted.contains("locCds")) return false;

        for (String required : definition.getRequiredParameters()) {
            if (!"locCd".equals(required) && !"locCds".equals(required)) return false;
        }
        return true;
    }
    private boolean isComposableTool(ToolDefinition def, Set<String> accepted, String relation) {
        if (def == null || accepted == null || accepted.isEmpty()
        		|| !def.getProducedFields().contains("LOC_CD")
                || def.getRequiredParameters().contains("locCd")
                || !def.getSupportedRelations().contains(relation))
        	return false;
        if (accepted.contains("reportType"))
        	return true;
        for (String p : accepted)
        	if (CANONICAL_PARAMETERS.contains(p)
        			&& !"locCd".equals(p)
        			&& !"locCds".equals(p))
        		return true;
        return false;
    }
    private boolean mergeCanonicalParameters(Map<String, String> target, Set<String> reports,
    		Map<String, String> source, Set<String> accepted) {
        String[] keys = {"locName", "location", "psm", "deptCd", "grade", "filter",
                "limit", "excludeUndefinedField"};
        boolean areaOnly = "filter_previous".equals(normalizeRelation(source.get("relation")))
                && hasText(source.get("locName")) && hasText(source.get("location"))
                && source.get("locName").trim().equalsIgnoreCase(source.get("location").trim());
        for (String key : keys) {
            if (areaOnly && "locName".equals(key)) continue;
            if (!copyIfAccepted(target, source, accepted, key)) return false;
        }
        String rt = source.get("reportType");
        if (hasText(rt))
        	for (String v : rt.split(","))
        		if (!v.trim().isEmpty())
        			reports.add(v.trim().toUpperCase(Locale.ROOT));
        if (accepted.contains("locCd"))
        	copyIfAccepted(target, source, accepted, "locCd");
        if (accepted.contains("locCds"))
        	copyIfAccepted(target, source, accepted, "locCds");
        return copyIfAccepted(target, source, accepted, "modifier");
    }
    private boolean copyIfAccepted(
    		Map<String, String> target, Map<String, String> source,
    		Set<String> accepted, String key) {
        String val = source.get(key);
        if (!accepted.contains(key) || !hasText(val))
        	return true;
        String existing = target.get(key);
        if (existing != null && !existing.equalsIgnoreCase(val.trim()))
        	return false;
        target.put(key, val.trim());
        return true;
    }
    private String normalizeRelation(String relation) {
        if (!hasText(relation))
        	return "independent";
        String n = relation.trim().toLowerCase(Locale.ROOT);
        return Arrays.asList("independent", "filter_previous", "enrich_previous", "use_previous_codes")
        		.contains(n) ? n : "invalid";
    }
    private boolean hasText(String value) {
    	return value != null && !value.trim().isEmpty();
    }
}
