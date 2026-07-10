package com.ais.service;

import com.ais.model.ExecutionResult;
import com.ais.model.LocationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class PipelineExecutor {

    private static final Logger log = LoggerFactory.getLogger(PipelineExecutor.class);

    private final MCPClientService toolRunner;

    public PipelineExecutor(MCPClientService toolRunner) {
        this.toolRunner = toolRunner;
    }

    public ExecutionResult execute(Plan plan) {

        List<LocationResult> resultSet = new ArrayList<LocationResult>();
        String detailOutput = null;

        for (Intent intent : plan.getSteps()) {
            log.info("[Manual Info]▶ Executing intent: {} role={}", intent.type, intent.role);

            IntentRole role = intent.role;
            String toolName = intent.toolName;

            // Build args from intent params
            Map<String, Object> args = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, String> e : intent.params.entrySet()) {
                args.put(e.getKey(), e.getValue());
            }

            if (role == IntentRole.PRIMARY) {
                if (toolName == null) {
                    log.warn("  → No tool for PRIMARY intent: {}", intent.type);
                    continue;
                }
                resultSet = toolRunner.runList(toolName, args);
                log.info("[Manual Info]  → {} results", resultSet.size());

            } else if (role == IntentRole.SECONDARY) {
                if (resultSet.isEmpty()) {
                    log.info("[Manual Info]  → Skipped SECONDARY (empty result set)");
                    continue;
                }
                Set<String> codes = new LinkedHashSet<String>();
                for (LocationResult r : resultSet) {
                    if (r.getLocationCode() != null) {
                        codes.add(r.getLocationCode());
                    }
                }
                args.put("codes", codes);
                List<LocationResult> enriched = toolRunner.runList(toolName, args);
                resultSet = mergeEnrichment(resultSet, enriched);
                log.info("[Manual Info]  → Merged enrichment, {} results", resultSet.size());

            } else if (role == IntentRole.ACTION) {
                detailOutput = toolRunner.runDetail(toolName, args);
                log.info("[Manual Info]  → Detail fetched");

            } else if (role == IntentRole.ACTION_ON_RESULT) {
                List<LocationResult> targets = applyModifier(resultSet, plan.getModifier());
                if (!targets.isEmpty()) {
                    String code = targets.get(0).getLocationCode();
                    log.info("[Manual Info]  → ACTION_ON_RESULT using code: {}", code);
                    args.put("code", code);
                    detailOutput = toolRunner.runDetail(toolName, args);
                } else {
                    log.warn("  → No target found for ACTION_ON_RESULT");
                }

            } else {
                log.warn("   MODIFIER/UNKNOWN role — skipping tool call: {}", intent.type);
            }
        }

        // Apply modifier to result set if not consumed by an action
        if (detailOutput == null && plan.getModifier() != null) {
            resultSet = applyModifier(resultSet, plan.getModifier());
        }

        return new ExecutionResult(resultSet, detailOutput);
    }

    // ── Modifier ──────────────────────────────────────────────────────
    private List<LocationResult> applyModifier(
            List<LocationResult> results, String modifier) {

        if (modifier == null || results.isEmpty()) {
            return results;
        }

        String mod = modifier.toUpperCase();

        if ("FIRST".equals(mod)) {
            return results.subList(0, 1);

        } else if ("LATEST".equals(mod) || "NEWEST".equals(mod)) {
            return results.subList(results.size() - 1, results.size());

        } else if ("OLDEST".equals(mod)) {
            LocationResult oldest = null;
            for (LocationResult r : results) {
                if (r.getCreatedDate() != null) {
                    if (oldest == null
                            || r.getCreatedDate().isBefore(oldest.getCreatedDate())) {
                        oldest = r;
                    }
                }
            }
            if (oldest != null) {
                List<LocationResult> one = new ArrayList<LocationResult>();
                one.add(oldest);
                return one;
            }
            // No dates — return first
            return results.subList(0, 1);

        } else if ("COUNT".equals(mod)) {
            List<LocationResult> one = new ArrayList<LocationResult>();
            one.add(LocationResult.countResult(results.size()));
            return one;

        } else {
            return results;
        }
    }

    // ── Enrichment merge ──────────────────────────────────────────────
    private List<LocationResult> mergeEnrichment(
            List<LocationResult> base,
            List<LocationResult> enriched) {

        Map<String, LocationResult> enrichMap = new HashMap<String, LocationResult>();
        for (LocationResult r : enriched) {
            if (r.getLocationCode() != null
                    && !enrichMap.containsKey(r.getLocationCode())) {
                enrichMap.put(r.getLocationCode(), r);
            }
        }

        Set<String> seen = new LinkedHashSet<String>();
        List<LocationResult> merged = new ArrayList<LocationResult>();

        for (LocationResult r : base) {
            if (r.getLocationCode() == null) {
                continue;
            }
            if (!seen.add(r.getLocationCode())) {
                continue; // deduplicate
            }
            LocationResult e = enrichMap.get(r.getLocationCode());
            if (e != null) {
                r.mergeFrom(e);
            }
            merged.add(r);
        }

        return merged;
    }
}
