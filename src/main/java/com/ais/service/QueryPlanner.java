package com.ais.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.*;

/**
 * Analyses a user prompt and produces an ordered execution plan of tool calls
 * to satisfy all detected intents.
 *
 * Examples: "Get info for first location under PSM/KT" →
 * [locations_by_psm(PSM/KT), hardcode_query(first result)]
 *
 * "Find former code of UD04400253000 and check BSI report" →
 * [search_loc_cd_history(UD04400253000), check_reports(BSI, current code)]
 *
 * "Which playground in Lo Wu has historic status?" →
 * [search_historic_building(ALL), search_by_name(Lo Wu)] → filter in Java
 */
public class QueryPlanner {

    private static final Logger log
            = LoggerFactory.getLogger(QueryPlanner.class);

    // ── Intent types ──────────────────────────────────────────────
    public enum IntentType {
        LOCATION_CODE, // direct LOC_CD lookup
        NAME_SEARCH, // search by name/keyword
        PSM_LIST, // list all PSMs
        PSM_LOCATIONS, // locations under a PSM
        CODE_HISTORY, // former/current code lookup
        CHECK_REPORTS, // report availability check
        HISTORIC_BUILDING, // graded historic buildings
        DECLARED_MONUMENT, // declared monuments
        DEPARTMENT_LOCATIONS, // locations by department
        FIRST_OF_RESULTS, // "get first result from previous"
        UNKNOWN                 // needs LLM
    }

    // ── A single detected intent ──────────────────────────────────
    public static class Intent {

        public final IntentType type;
        public final Map<String, String> params;

        public Intent(IntentType type, Map<String, String> params) {
            this.type = type;
            this.params = params != null ? params : new LinkedHashMap<String, String>();
        }

        public Intent(IntentType type) {
            this(type, new LinkedHashMap<String, String>());
        }

        @Override
        public String toString() {
            return type + params.toString();
        }
    }

    // ── Execution plan = ordered list of intents ──────────────────
    public static class Plan {

        public final List<Intent> steps;
        public final boolean needsLlm;
        public final String reason;

        public Plan(List<Intent> steps, boolean needsLlm, String reason) {
            this.steps = steps;
            this.needsLlm = needsLlm;
            this.reason = reason;
        }

        public static Plan llmFallback(String reason) {
            return new Plan(Collections.emptyList(), true, reason);
        }

        public static Plan of(Intent... intents) {
            return new Plan(Arrays.asList(intents), false, "fast-path");
        }

        public static Plan of(List<Intent> intents) {
            return new Plan(intents, false, "fast-path");
        }
    }

    // ── Patterns ──────────────────────────────────────────────────
    private static final Pattern LOC_CD
            = Pattern.compile("\\b([A-Z]{2}\\d{11})\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern PSM_VALUE
            = Pattern.compile("\\bPSM/([A-Z0-9 /]+?)(?:\\s|$|,|\\.|;)",
                    Pattern.CASE_INSENSITIVE);

    // ── Only match when explicitly prefixed with dept/department keyword ──
    private static final Pattern DEPT_CODE
            = Pattern.compile(
                    "\\b(?:dept(?:artment)?)[\\s:]+([A-Z]{2,10})\\b",
                    Pattern.CASE_INSENSITIVE
            );

    private static final Pattern GRADE
            = Pattern.compile("\\bgrade\\s*([123])\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern REPORT_TYPES
            = Pattern.compile("\\b(BSI|CSR|KAI|EMMS|DSSR)\\b",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern CODE_HISTORY_KW
            = Pattern.compile("(?i)\\b(former|previous|old|current|new)\\s+"
                    + "(code|loc.?cd)|code\\s+(history|change)|change\\s+history");

    private static final Pattern HISTORIC_KW
            = Pattern.compile("(?i)\\b(historic|graded?)\\s*(building|bldg)?");

    private static final Pattern MONUMENT_KW
            = Pattern.compile("(?i)\\bdeclared?\\s*monument");

    private static final Pattern PSM_KW
            = Pattern.compile("(?i)\\bPSM/");

    private static final Pattern PSM_LIST_KW
            = Pattern.compile("(?i)\\b(list|show|all|how many)\\b.{0,20}\\bPSMs?\\b");

    private static final Pattern FIRST_KW
            = Pattern.compile("(?i)\\b(first|top|1st)\\b.{0,30}"
                    + "\\b(location|result|code|entry|one)\\b");

    private static final Pattern INFO_KW
            = Pattern.compile("(?i)\\b(info|details?|about|get|show|tell me)\\b");

    private static final Pattern AND_KW
            = Pattern.compile("(?i)\\b(and|also|then|as well|\\+)\\b");

    // ── Names to strip from search queries ───────────────────────
    private static final Pattern NOISE_WORDS
            = Pattern.compile("(?i)\\b(which|what|where|find|get|show|list|"
                    + "tell me|info of|details of|about|search for|"
                    + "look up|lookup|has|have|with|that|whose|the|a|an|"
                    + "is|are|in|at|near|of|for|under|managed by|"
                    + "belongs? to|located in|location|building|site|"
                    + "playground|park|school|hospital|centre|center)\\b");

    private boolean isNaturalLanguageComplex(String p) {
        String s = p.toLowerCase();

        return s.contains("which")
                || s.contains("what")
                || s.contains("who")
                || s.contains("where")
                || s.contains("oldest")
                || s.contains("newest")
                || s.contains("first")
                || s.contains("has")
                || s.contains("have")
                || s.contains("with")
                || s.contains("and")
                || s.contains("then")
                || s.contains("manages")
                || s.contains("belong")
                || s.contains("status");
    }

    // ─────────────────────────────────────────────────────────────
    // MAIN: Analyse prompt → produce Plan
    // ─────────────────────────────────────────────────────────────
    public Plan analyse(String prompt, OllamaService.ExtractedKeywords kw) {

        // ── Use LLM keywords if available ─────────────────────────────
        if (kw != null) {
            log.info("🧠 QueryPlanner using LLM keywords: {}", kw);

            List<Intent> steps = new ArrayList<Intent>();

            switch (kw.intent) {

                case "MONUMENT": {
                    Intent mi = new Intent(IntentType.DECLARED_MONUMENT);
                    mi.params.put("filter", kw.filter != null ? kw.filter : "T");
                    if (kw.locationName != null) {
                        mi.params.put("locationFilter", kw.locationName);
                    }
                    if (kw.modifier != null) {
                        mi.params.put("modifier", kw.modifier);
                    }
                    steps.add(mi);
                    return Plan.of(steps);   // ← use static factory, not new Plan()
                }

                case "HISTORIC": {
                    Intent hi = new Intent(IntentType.HISTORIC_BUILDING);
                    hi.params.put("grade", kw.grade != null ? kw.grade : "ALL");
                    if (kw.locationName != null) {
                        hi.params.put("locationFilter", kw.locationName);
                    }
                    if (kw.modifier != null) {
                        hi.params.put("modifier", kw.modifier);
                    }
                    steps.add(hi);
                    return Plan.of(steps);
                }

                case "LOCATION_CODE": {
                    Intent li = new Intent(IntentType.LOCATION_CODE);
                    li.params.put("locCd", kw.locationCode);
                    steps.add(li);
                    return Plan.of(steps);
                }

                case "NAME_SEARCH": {
                    Intent ni = new Intent(IntentType.NAME_SEARCH);
                    ni.params.put("locName",
                            kw.locationName != null ? kw.locationName : prompt);
                    steps.add(ni);
                    return Plan.of(steps);
                }

                case "DEPARTMENT": {
                    Intent di = new Intent(IntentType.DEPARTMENT_LOCATIONS);
                    di.params.put("deptCd", kw.department);
                    steps.add(di);
                    return Plan.of(steps);
                }

                case "PSM": {
                    if (kw.psm != null) {
                        Intent pi = new Intent(IntentType.PSM_LOCATIONS);
                        pi.params.put("psm", kw.psm);
                        steps.add(pi);
                    } else {
                        steps.add(new Intent(IntentType.PSM_LIST));
                    }
                    return Plan.of(steps);
                }

                case "REPORT": {
                    Intent ri = new Intent(IntentType.CHECK_REPORTS);
                    ri.params.put("reportType", kw.reportType);
                    if (kw.locationCode != null) {
                        ri.params.put("locCds", kw.locationCode);
                    }
                    steps.add(ri);
                    return Plan.of(steps);
                }

                case "CODE_HISTORY": {
                    Intent chi = new Intent(IntentType.CODE_HISTORY);
                    if (kw.locationCode != null) {
                        chi.params.put("formerLocCd", kw.locationCode);
                        chi.params.put("currentLocCd", kw.locationCode);
                    }

                    // ── Only honour known modifiers ───────────────────────────────
                    // LLM may invent values like "SEARCH_HISTORY" — sanitise here
                    Set<String> validCodeHistoryModifiers = new java.util.HashSet<>(
                            Arrays.asList("FETCH_CURRENT"));

                    String modifier = kw.modifier;
                    if (modifier != null && !validCodeHistoryModifiers.contains(modifier)) {
                        log.warn("⚠️ Unknown CODE_HISTORY modifier '{}' — ignoring", modifier);
                        modifier = null;
                    }

                    if ("FETCH_CURRENT".equals(modifier)) {
                        chi.params.put("autoFetchCurrent", "true");
                        log.info("🔗 CODE_HISTORY will auto-fetch current code details");
                    }

                    steps.add(chi);
                    return new Plan(steps, false, "fast-path code history");
                }
                
                case "SQL_QUERY": {
                    // Always go to SQL generation — skip agent loop entirely
                    log.info("🔍 SQL_QUERY intent → direct SQL generation");
                    return Plan.llmFallback("SQL_QUERY intent — direct SQL generation");
                }

                default:
                    // UNKNOWN or unhandled — fall through to LLM
                    return Plan.llmFallback("complex / unknown intent from keywords");
            }
        }

        // ── Fallback: original single-param analyse ───────────────────
        log.warn("⚠️ No keywords available, using original planner logic");
        return analyse(prompt);   // ← calls the original single-param method
    }

    // ─────────────────────────────────────────────────────────────
    // ORIGINAL single-param analyse — kept for fallback
    // Called when keyword extraction fails or returns null
    // ─────────────────────────────────────────────────────────────
    public Plan analyse(String prompt) {
        // ... your original regex-based logic unchanged ...
        log.info("🧠 QueryPlanner analysing: '{}'", prompt);

        List<String> codes = extractLocCodes(prompt);
        List<String> reports = extractReportTypes(prompt);
        String psm = extractPsm(prompt);
        String dept = extractDept(prompt);
        String grade = extractGrade(prompt);
        boolean history = CODE_HISTORY_KW.matcher(prompt).find();
        boolean historic = HISTORIC_KW.matcher(prompt).find();
        boolean monument = MONUMENT_KW.matcher(prompt).find();
        boolean psmKw = PSM_KW.matcher(prompt).find();
        boolean psmList = PSM_LIST_KW.matcher(prompt).find();
        boolean and = AND_KW.matcher(prompt).find();
        boolean first = FIRST_KW.matcher(prompt).find();

        log.info("  Signals → codes:{} reports:{} psm:{} dept:{} grade:{} "
                + "history:{} historic:{} monument:{} psmKw:{} psmList:{} and:{} first:{}",
                codes, reports, psm, dept, grade,
                history, historic, monument, psmKw, psmList, and, first);

        // ── Complex natural language → LLM fallback ───────────────────
        if (isNaturalLanguageComplex(prompt) && codes.isEmpty() && psm == null) {
            log.info("  → Plan: LLM fallback (complex natural language)");
            return Plan.llmFallback("complex natural language");
        }

        List<Intent> steps = new ArrayList<Intent>();

        // ── Direct location code(s) ───────────────────────────────────
        if (!codes.isEmpty() && !history) {
            if (codes.size() == 1 && reports.isEmpty()) {
                Map<String, String> p = new LinkedHashMap<String, String>();
                p.put("locCd", codes.get(0));
                steps.add(new Intent(IntentType.LOCATION_CODE, p));
                log.info("  → Plan: single location code lookup");
                return Plan.of(steps);
            }
            if (codes.size() > 1 || !reports.isEmpty()) {
                Map<String, String> p = new LinkedHashMap<String, String>();
                p.put("locCds", String.join(",", codes));
                steps.add(new Intent(IntentType.LOCATION_CODE, p));
                if (!reports.isEmpty()) {
                    Map<String, String> rp = new LinkedHashMap<String, String>();
                    rp.put("reportType", String.join(",", reports));
                    rp.put("codesSource", "inline");
                    steps.add(new Intent(IntentType.CHECK_REPORTS, rp));
                }
                log.info("  → Plan: multiple codes + reports");
                return Plan.of(steps);
            }
        }

        // ── Code history ──────────────────────────────────────────────
        if (history && !codes.isEmpty()) {
            Map<String, String> p = new LinkedHashMap<String, String>();

            // Always search both directions
            p.put("formerLocCd", codes.get(0));
            p.put("currentLocCd", codes.get(0));

            steps.add(new Intent(IntentType.CODE_HISTORY, p));

            if (!reports.isEmpty()) {
                Map<String, String> rp = new LinkedHashMap<String, String>();
                rp.put("reportType", String.join(",", reports));
                rp.put("codesSource", "history");
                steps.add(new Intent(IntentType.CHECK_REPORTS, rp));
            }

            log.info("  → Plan: code history (both directions)");
            return Plan.of(steps);
        }

        // ── PSM list ──────────────────────────────────────────────────
        if (psmList && psm == null) {
            steps.add(new Intent(IntentType.PSM_LIST));
            log.info("  → Plan: list all PSMs");
            return Plan.of(steps);
        }

        // ── PSM locations ─────────────────────────────────────────────
        if (psm != null) {
            Map<String, String> p = new LinkedHashMap<String, String>();
            p.put("psm", psm);
            steps.add(new Intent(IntentType.PSM_LOCATIONS, p));
            if (first) {
                Map<String, String> fp = new LinkedHashMap<String, String>();
                fp.put("codesSource", "psm_first");
                steps.add(new Intent(IntentType.LOCATION_CODE, fp));
            }
            if (!reports.isEmpty()) {
                Map<String, String> rp = new LinkedHashMap<String, String>();
                rp.put("reportType", String.join(",", reports));
                rp.put("codesSource", "psm_all");
                steps.add(new Intent(IntentType.CHECK_REPORTS, rp));
            }
            log.info("  → Plan: PSM locations for '{}'", psm);
            return Plan.of(steps);
        }

        // ── Monument ──────────────────────────────────────────────────
        if (monument) {
            Map<String, String> p = new LinkedHashMap<String, String>();
            p.put("filter", "T");
            steps.add(new Intent(IntentType.DECLARED_MONUMENT, p));
            log.info("  → Plan: declared monuments");
            return Plan.of(steps);
        }

        // ── Historic building ─────────────────────────────────────────
        if (historic) {
            Map<String, String> p = new LinkedHashMap<String, String>();
            p.put("grade", grade != null ? grade : "ALL");
            steps.add(new Intent(IntentType.HISTORIC_BUILDING, p));
            log.info("  → Plan: historic buildings grade={}", grade);
            return Plan.of(steps);
        }

        // ── Department locations ──────────────────────────────────────
        if (dept != null) {
            Map<String, String> p = new LinkedHashMap<String, String>();
            p.put("deptCd", dept);
            steps.add(new Intent(IntentType.DEPARTMENT_LOCATIONS, p));
            log.info("  → Plan: department locations for '{}'", dept);
            return Plan.of(steps);
        }

        // ── Simple name search ────────────────────────────────────────
        String nameQuery = cleanNameQuery(prompt);
        if (!nameQuery.isEmpty()) {
            Map<String, String> p = new LinkedHashMap<String, String>();
            p.put("locName", nameQuery);
            steps.add(new Intent(IntentType.NAME_SEARCH, p));
            log.info("  → Plan: simple name search '{}'", nameQuery);
            return Plan.of(steps);
        }

        // ── Last resort: LLM ─────────────────────────────────────────
        log.info("  → Plan: LLM fallback (no pattern matched)");
        return Plan.llmFallback("no pattern matched");
    }
    // ─────────────────────────────────────────────────────────────
    // EXTRACTORS
    // ─────────────────────────────────────────────────────────────

    private List<String> extractLocCodes(String p) {
        List<String> codes = new ArrayList<String>();
        Matcher m = LOC_CD.matcher(p);
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        while (m.find()) {
            seen.add(m.group(1).toUpperCase());
        }
        codes.addAll(seen);
        return codes;
    }

    private List<String> extractReportTypes(String p) {
        List<String> types = new ArrayList<String>();
        Matcher m = REPORT_TYPES.matcher(p);
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        while (m.find()) {
            seen.add(m.group(1).toUpperCase());
        }
        types.addAll(seen);
        return types;
    }

    private String extractPsm(String p) {
        Matcher m = PSM_VALUE.matcher(p + " "); // append space to help boundary match
        if (m.find()) {
            return m.group(1).trim();
        }
        // Also try: anything after "PSM/"
        int idx = p.toUpperCase().indexOf("PSM/");
        if (idx >= 0) {
            String rest = p.substring(idx + 4).trim();
            // Take until space or end of meaningful word
            String[] parts = rest.split("\\s+", 2);
            return parts[0].trim();
        }
        return null;
    }

    private String extractDept(String p) {
        // ── Priority 1: explicit "dept XXX" or "department XXX" prefix ──
        Matcher m = DEPT_CODE.matcher(p);
        if (m.find()) {
            return m.group(1).toUpperCase();
        }

        // ── Priority 2: known department codes as standalone words ────────
        // Only match exact known codes — no guessing from sentence structure
        String[] knownDepts = {
            "AFCD", "LCSD", "HD", "DSD", "FEHD", "EMSD", "HYD",
            "ASD", "GLD", "ARCHSD", "CSD", "CEDD", "EPD", "FSD",
            "HKPF", "ISD", "IMMD", "OFCA", "PCPD", "SWD", "TD",
            "HAD", "PLANSD", "DEVB", "THB", "ENB", "FSTB", "CSDB"
        };

        String upper = p.toUpperCase();
        for (String dept : knownDepts) {
            if (upper.matches(".*\\b" + dept + "\\b.*")) {
                log.info("  Matched known dept code: {}", dept);
                return dept;
            }
        }

        // ── Priority 3: ALL-CAPS word (as written in original prompt) ─────
        // ONLY match if the word appears in ALL CAPS in the ORIGINAL prompt
        // This ensures "manages" (lowercase) is never matched
        // e.g., "show LCSD locations" → LCSD is all-caps in original
        Matcher m3 = Pattern.compile(
                "\\b([A-Z]{2,6})\\s+"
                + "(?:locations?|buildings?|properties|sites?|offices?)"
        ).matcher(p); // ← NO CASE_INSENSITIVE flag — must be uppercase in original

        if (m3.find()) {
            String candidate = m3.group(1);
            Set<String> rejectWords = new HashSet<String>(Arrays.asList(
                    "ALL", "THE", "OLD", "NEW", "BIG", "HIS", "HER",
                    "ITS", "ARE", "WAS", "HAS", "GET", "TOP", "FOR",
                    "ANY", "MY", "NO", "SO", "DO", "IN", "AT",
                    "BY", "TO", "OF", "OR", "AND", "BUT", "NOT"
            ));
            if (!rejectWords.contains(candidate)) {
                log.info("  Matched ALL-CAPS dept word: {}", candidate);
                return candidate;
            }
        }

        // ── No dept found ─────────────────────────────────────────────────
        log.info("  No dept code detected");
        return null;
    }

    private String extractGrade(String p) {
        Matcher m = GRADE.matcher(p);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * Extracts a location name filter from a complex query. e.g., "Which
     * playground in Lo Wu has historic status?" → "Lo Wu"
     */
    private String extractNameFilter(String p) {
        // Look for: "in <Name>", "at <Name>", "near <Name>"
        Matcher m = Pattern.compile(
                "(?i)\\b(in|at|near|located in|within)\\s+([A-Z][a-zA-Z\\s]{2,30}?)(?:\\s+has|\\s+with|\\s+that|,|\\.|$)",
                Pattern.CASE_INSENSITIVE).matcher(p);
        if (m.find()) {
            return m.group(2).trim();
        }
        return null;
    }

    /**
     * Cleans noise words from a simple name query.
     */
    private String cleanNameQuery(String p) {
        String cleaned = NOISE_WORDS.matcher(p).replaceAll(" ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        // Remove trailing punctuation
        cleaned = cleaned.replaceAll("[?!.,;]+$", "").trim();
        return cleaned;
    }

    /**
     * Returns true if the prompt has multiple intents or reasoning
     * requirements.
     */
    private boolean isComplexPrompt(String p) {
        int signals = 0;
        if (AND_KW.matcher(p).find()) {
            signals++;
        }
        if (FIRST_KW.matcher(p).find()) {
            signals++;
        }
        if (p.toLowerCase().contains("oldest")) {
            signals++;
        }
        if (p.toLowerCase().contains("newest")) {
            signals++;
        }
        if (p.toLowerCase().contains("which")) {
            signals++;
        }
        if (p.toLowerCase().contains("manage")) {
            signals++;
        }
        return signals >= 2;
    }
}
