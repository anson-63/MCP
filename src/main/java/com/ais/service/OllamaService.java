package com.ais.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import okhttp3.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.*;
import java.util.concurrent.TimeUnit;

import java.io.IOException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import com.ais.config.AppConfig;
import com.ais.db.DatabaseManager;
import com.ais.model.ExtractedKeywords;
import com.ais.model.IntentStep;
import com.ais.service.Intent;
import com.ais.service.IntentRole;
import com.ais.service.Plan;
import com.ais.service.PipelineExecutor;
import com.ais.model.OpenAiRequest;
import com.ais.security.AuthorizationContext;
import com.ais.service.PlanOptimizer;

public class OllamaService {

    private static final Logger log = LoggerFactory.getLogger(OllamaService.class);
    private static final int KW_CACHE_MAX = 50;
    private static final Map<String, ExtractedKeywords> kwCache
            = Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, ExtractedKeywords>(
                            KW_CACHE_MAX, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, ExtractedKeywords> eldest) {
                    return size() > KW_CACHE_MAX;
                }
            }
            );
    // ── Read from config (no more hardcoded values!) ──────────────
    private static final String OLLAMA_URL
            = AppConfig.ollamaBaseUrl() + "/api/chat";
    private static final String ollamaModel = AppConfig.ollamaModel();
    private static final Integer ollamaNumCtx = AppConfig.ollamaNumCtx();
    private static final boolean useTencent = AppConfig.useTencentCloud();
    private static final String tencentBaseUrl = AppConfig.getTencentBaseUrl();
    private static final String tencentApiKey = AppConfig.getTencentApiKey();
    private static final String tencentModel = AppConfig.getTencentModel();
    private static final Integer tencentNumCtx = AppConfig.getTenecentNumCtx();
    private static final MediaType JSON_TYPE
            = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;
    private final MCPClientService mcpClient;

    private final PlanOptimizer planOptimizer;

    private static volatile String contextPath = "";

    public static void setContextPath(String path) {
        contextPath = (path == null) ? "" : path;
    }

    // ══════════════════════════════════════════════════════════════
    // SECURITY CONSTANTS (ProjectReview P0/P1 fixes)
    // ══════════════════════════════════════════════════════════════
    /**
     * Max agent loop iterations — prevents runaway tool-call storms.
     */
    private static final int AGENT_LOOP_MAX_ITERATIONS = 10;

    /**
     * Wall-clock timeout for the entire agent loop (ms).
     */
    private static final long AGENT_LOOP_TIMEOUT_MS = 30_000;

    /**
     * Dangerous SQL keywords that must NEVER appear in LLM-generated SQL.
     * Defense-in-depth on top of the read-only DB user.
     */
    private static final Pattern SQL_DANGEROUS_PATTERN = Pattern.compile(
            "(?i)\\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|EXEC|EXECUTE"
            + "|XP_|SP_|SHUTDOWN|MERGE|BULK|OPENROWSET|OPENDATASOURCE"
            + "|DBCC|GRANT|REVOKE|DENY)\\b"
    );

    /**
     * Prompt injection patterns to strip from user input.
     */
    private static final Pattern[] PROMPT_INJECTION_PATTERNS = {
        Pattern.compile("(?i)ignore\\s+(all\\s+)?(previous|prior|above)\\s+(instructions?|prompts?|rules?)"),
        Pattern.compile("(?i)disregard\\s+(all\\s+)?(previous|prior|above)\\s+(instructions?|prompts?|rules?)"),
        Pattern.compile("(?i)forget\\s+(all\\s+)?(previous|prior|above)\\s+(instructions?|prompts?|rules?)"),
        Pattern.compile("(?i)you\\s+are\\s+now\\s+(a|an|in)\\s+"),
        Pattern.compile("(?i)new\\s+instructions?:\\s*"),
        Pattern.compile("(?i)system\\s*prompt\\s*:"),
        Pattern.compile("(?i)reveal\\s+(your|the)\\s+(system|internal)\\s+prompt"),
        Pattern.compile("(?i)return\\s+(the\\s+)?(full\\s+)?(system|internal)\\s+prompt"),
        Pattern.compile("(?i)act\\s+as\\s+(if\\s+)?(a|an)\\s+"),
        Pattern.compile("(?i)pretend\\s+(to\\s+be|you\\s+are)\\s+"),};

    // ══════════════════════════════════════════════════════════════
    // PROMPT INJECTION DEFENSE (ProjectReview P1 — Issue #9)
    // ══════════════════════════════════════════════════════════════
    /**
     * Strips known prompt-injection patterns from user input. Also caps length
     * to prevent token-exhaustion attacks.
     */
    private String sanitizeUserPrompt(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return "";
        }
        String sanitized = prompt;
        boolean injectionDetected = false;
        for (Pattern p : PROMPT_INJECTION_PATTERNS) {
            Matcher m = p.matcher(sanitized);
            if (m.find()) {
                injectionDetected = true;
                sanitized = m.replaceAll("[filtered]").trim();
            }
        }
        if (injectionDetected) {
            log.warn("[Security] Prompt injection pattern detected and stripped");
        }
        if (sanitized.length() > 2000) {
            sanitized = sanitized.substring(0, 2000);
            log.warn("[Security] User prompt truncated to 2000 chars (was {})", prompt.length());
        }
        return sanitized;
    }

    // ── System prompt for tool selection (first LLM call) ─────────
    //hardcode prompt instructions for tool selection
    private static final String SYSTEM_PROMPT
            = "You are a database assistant.\n\n"
            + "Select tools only from the tools supplied in the API request.\n"
            + "Use the tool descriptions and parameter schemas to choose tools.\n"
            + "Copy location codes exactly.\n"
            + "Use previous result codes only when the workflow requires it.\n"
            + "Do not invent database facts.\n"
            + "When enough data has been gathered, stop and answer briefly.\n"
            + "/nothink";
    // ── System prompt for summary generation (second LLM call) ────
    //hardcode summary prompt text
    private static final String SUMMARY_PROMPT
            = "You are a friendly assistant. Given a JSON data result, write ONE short "
            + "sentence (max 20 words) introducing the data to the user.\n\n"
            + "RULES:\n"
            + "1. Output ONLY the sentence - no explanations, no markdown, no thinking\n"
            + "2. Be friendly and concise\n"
            + "3. Mention the key item by name if available\n"
            + "4. Do NOT list all the data - that will be shown in a table below\n\n"
            + "Example: 'Here are the details for Sha Tin Park:'";
    private static final String KEYWORD_EXTRACT_PROMPT
            = "You are a keyword extractor for a location database system.\n\n"
            + "Extract keywords from the user's query and return ONLY valid JSON.\n\n"
            + "CRITICAL RULE FOR 'FIRST' vs LIMIT:\n"
            + "- When words like 'first' or 'top' are followed by a number (e.g., 'first 50 locations', 'top 20'), this is ONLY a row count limit (set 'limit': 50). Do NOT set modifier='FIRST', showDetails=true, or autoFetchFirst=true in this case!\n"
            + "- Only set modifier='FIRST' when the user wants info for a single #1 item (e.g., 'get info for the first location under PSM/KT').\n"
            + "JSON fields:\n"
            + "- intents: ARRAY of one or more intent strings. "
            + "Valid values: LOCATION_CODE, NAME_SEARCH, MONUMENT, HISTORIC, "
            + "DEPARTMENT, PSM, REPORT, CODE_HISTORY, SQL_QUERY, UNKNOWN. "
            + "Use multiple values when the query combines two concepts.\n"
            + "- locationCode: exact location code if mentioned (e.g. SB04400361000), else null\n"
            + "- locationName: place/district name if mentioned (e.g. Sha Tin, Lo Wu), else null\n"
            + "- reportType: report type if mentioned (BSI/KAI/DSSR/EMMS/CSR/ALL), else null\n"
            + "- department: department code if mentioned (LCSD/AFCD/HD/DSD), else null\n"
            + "- psm: PSM name if mentioned (e.g. SHA TIN EAST), else null\n"
            + "- grade: historic building grade if mentioned (1/2/3/ALL), else null\n"
            + "- filter: for monuments T=declared, F=non, ALL=both, else null\n"
            + "- excludeUndefinedField: if user asks to ignore/exclude empty, missing, undefined, or placeholder fields (e.g. 'with address not null', 'real address', 'valid name'), set to 'address', 'name', or 'department', else null\n"
            + "- modifier: for CODE_HISTORY use FETCH_CURRENT, else OLDEST, NEWEST, FIRST, LATEST, ALL, COUNT. Never invent new modifier values.\n"
            + "- rawKeywords: array of important words from the query\n"
            + "- primaryIntent: the main action intent when multiple intents are present. "
            + "Choose the most specific action (e.g., HISTORIC, MONUMENT, REPORT, LOCATION_CODE), not the filter/scope (e.g., PSM, DEPARTMENT).\n"
            + "- showDetails: boolean, true if the user asks for details, first, oldest, newest, latest, current, or otherwise wants to act on a specific result.\n"
            + "- plan: ordered array of execution steps. Add one step per intent in the query, sorted by priority (1 = run first). "
            + "Each step is {\"type\": string, \"priority\": number, \"params\": object, \"relation\": string}. "
            + "relation is one of: independent, filter_previous, enrich_previous, use_previous_codes. "
            + "filter_previous means keep only LOC_CD values that appear in BOTH this step and the previous step. "
            + "enrich_previous means merge attributes from this step into the previous result rows. "
            + "use_previous_codes means pass all previous LOC_CD values as the locCds/codes input to this step.\n\n"
            + "LOCATION/NAME PLAN RULES:\n"
            + "For NAME_SEARCH steps, put the specific subject/name to search in the step's 'locName' param (e.g., 'playground'). "
            + "Put the district/area in the step's 'location' param (e.g., 'Lo Wu'). Do not put the district in 'locName'.\n\n"
            + "INTENT RULES:\n"
            + "- LOCATION_CODE: user mentions a location code AND wants details/info about it\n"
            + "- REPORT: user provides one or more specific location codes AND asks to check reports for them (e.g. 'check all 5 reports for QA03206005000 QB03106003000').\n"
            + "- NAME_SEARCH: user searches by place name without a code\n"
            + "- MONUMENT: user asks about declared monuments\n"
            + "- HISTORIC: user asks about historic/graded buildings\n"
            + "- DEPARTMENT: user asks about a department code OR asks which department manages a location/place.\n"
            + "- PSM: user asks about a PSM OR asks to list all PSMs.\n"
            + "- CODE_HISTORY: user asks about former/current/old/new codes\n"
            + "- SQL_QUERY: user asks a cross-table question across ALL locations without providing specific location codes (e.g. 'any location code has all 5 reports?'). Do NOT use SQL_QUERY if the user provides specific location codes!\n"
            + "- UNKNOWN: cannot determine intent\n\n"
            + "IMPORTANT:\n"
            + "- If a location code is present but NO specific report type is mentioned, use LOCATION_CODE not REPORT.\n"
            + "- If the user provides specific location codes and asks to check reports, use REPORT and put all codes in locationCode.\n"
            + "- Use SQL_QUERY ONLY when asking about report combinations across ALL locations without providing specific codes.\n"
            + "- Always return a plan array. The plan must contain at least one step and must rank steps by priority. "
            + "- REDUNDANT-STEP RULE (applies to ANY list-returning step type, not just one or two examples): "
            + "NAME_SEARCH, DECLARED_MONUMENT, HISTORIC_BUILDING, PSM_LOCATIONS, DEPARTMENT_LOCATIONS, and CODE_HISTORY "
            + "all return a LIST of LOC_CD candidates. When ANY of these step types carries a modifier "
            + "(FIRST/OLDEST/NEWEST/LATEST), the backend already resolves that modifier by internally fetching the "
            + "FULL single-location detail (general info + reports) for the chosen code — the step's output already "
            + "IS the detail view, not just a narrowed list. Do NOT add a following LOCATION_CODE step with "
            + "relation=use_previous_codes in that case: it would look up the exact same code a second time and "
            + "duplicate the detail card in the final answer for no new information. Only add a LOCATION_CODE "
            + "step after a list-returning step when that step has NO modifier — i.e. it still returns multiple "
            + "candidate codes and a specific one must still be chosen and looked up.\n\n"
            + "Use relation=\"filter_previous\" when one intent should narrow the previous result (e.g., HISTORIC under PSM). "
            + "Use relation=\"enrich_previous\" when one intent adds attributes to the previous result (e.g., HISTORIC grade for monuments). "
            + "Use relation=\"use_previous_codes\" when the step needs the previous LOC_CD list as input (e.g., CHECK_REPORTS for previously found locations).\n\n"
            + "Examples:\n"
            // ── NEW: REPORT vs SQL_QUERY examples ──────────────────────────
            + "Query: 'check all 5 reports for QA03206005000 QB03106003000 QC02306006000'\n"
            + "Output: {\"intents\":[\"REPORT\"],\"locationCode\":\"QA03206005000,QB03106003000,QC02306006000\",\"locationName\":null,"
            + "\"reportType\":\"ALL\",\"department\":null,\"psm\":null,\"grade\":null,"
            + "\"filter\":null,\"modifier\":null,\"rawKeywords\":[\"check all 5 reports\",\"QA03206005000\",\"QB03106003000\",\"QC02306006000\"]}\n\n"
            + "Query: 'any location code has all 5 reports?'\n"
            + "Output: {\"intents\":[\"SQL_QUERY\"],\"locationCode\":null,\"locationName\":null,"
            + "\"reportType\":null,\"department\":null,\"psm\":null,\"grade\":null,"
            + "\"filter\":null,\"modifier\":null,\"rawKeywords\":[\"all 5 reports\",\"location code\"]}\n\n"
            // ── PSM examples ───────────────────────────────────────────────
            + "Query: 'List all PSMs'\n"
            + "Output: {\"intents\":[\"PSM\"],\"locationCode\":null,\"locationName\":null,"
            + "\"reportType\":null,\"department\":null,\"psm\":null,\"grade\":null,"
            + "\"filter\":null,\"modifier\":null,\"rawKeywords\":[\"List all PSMs\"]}\n\n"
            + "Query: 'info of first location code under PSM/KT'\n"
            + "Output: {\"intents\":[\"PSM\",\"CODE_HISTORY\"],\"locationCode\":null,\"locationName\":null,"
            + "\"reportType\":null,\"department\":null,\"psm\":\"KT\",\"grade\":null,"
            + "\"filter\":null,\"modifier\":\"FIRST\",\"rawKeywords\":[\"info\",\"first\",\"location code\",\"PSM\",\"KT\"]}\n\n"
            // ── Attribute vs Filter examples ───────────────────────────────
            + "Query: 'Show department managing UC07300217003'\n"
            + "Output: {\"intents\":[\"LOCATION_CODE\",\"DEPARTMENT\"],\"locationCode\":\"UC07300217003\",\"locationName\":null,"
            + "\"reportType\":null,\"department\":null,\"psm\":null,\"grade\":null,"
            + "\"filter\":null,\"modifier\":null,\"rawKeywords\":[\"department\",\"managing\",\"UC07300217003\"]}\n\n"
            + "Query: 'show departments for locations in Lo Wu'\n"
            + "Output: {\"intents\":[\"NAME_SEARCH\",\"DEPARTMENT\"],\"locationCode\":null,\"locationName\":\"Lo Wu\","
            + "\"reportType\":null,\"department\":null,\"psm\":null,\"grade\":null,"
            + "\"filter\":null,\"modifier\":null,\"rawKeywords\":[\"departments\",\"locations\",\"Lo Wu\"]}\n\n"
            + "Query: 'any location code has all 5 reports in Hong Kong island?'\n"
            + "Output: {\"intents\":[\"SQL_QUERY\"],\"locationCode\":null,\"locationName\":\"Hong Kong island\",\"reportType\":null,\"department\":null,\"psm\":null,\"grade\":null,\"filter\":null,\"modifier\":null,\"rawKeywords\":[\"all 5 reports\",\"Hong Kong island\"]}\n\n"
            + "Query: 'any location code has all 5 reports in central?'\n"
            + "Output: {\"intents\":[\"SQL_QUERY\"],\"locationCode\":null,\"locationName\":\"central\",\"reportType\":null,\"department\":null,\"psm\":null,\"grade\":null,\"filter\":null,\"modifier\":null,\"rawKeywords\":[\"all 5 reports\",\"central\"]}\n\n"
            + "Query: 'which locations have both BSI and KAI reports?'\n"
            + "Output: {\"intents\":[\"SQL_QUERY\"],\"locationCode\":null,\"locationName\":null,\"reportType\":\"BSI,KAI\",\"department\":null,\"psm\":null,\"grade\":null,\"filter\":null,\"modifier\":null,\"rawKeywords\":[\"BSI\",\"KAI\",\"locations\"]}\n\n"
            + "Query: 'how many LCSD locations have BSI report?'\n"
            + "Output: {\"intents\":[\"SQL_QUERY\"],\"locationCode\":null,\"locationName\":null,\"reportType\":\"BSI\",\"department\":\"LCSD\",\"psm\":null,\"grade\":null,\"filter\":null,\"modifier\":\"COUNT\",\"rawKeywords\":[\"LCSD\",\"BSI\",\"how many\"]}\n\n"
            + "Query: 'info of new code of UD04400253000'\n"
            + "Output: {\"intents\":[\"CODE_HISTORY\"],\"locationCode\":\"UD04400253000\",\"locationName\":null,\"reportType\":null,\"department\":null,\"psm\":null,\"grade\":null,\"filter\":null,\"modifier\":\"FETCH_CURRENT\",\"rawKeywords\":[\"new code\",\"info\",\"UD04400253000\"]}\n\n"
            + "Query: 'get details of current code for UD04400253000'\n"
            + "Output: {\"intents\":[\"CODE_HISTORY\"],\"locationCode\":\"UD04400253000\",\"locationName\":null,\"reportType\":null,\"department\":null,\"psm\":null,\"grade\":null,\"filter\":null,\"modifier\":\"FETCH_CURRENT\",\"rawKeywords\":[\"current code\",\"details\",\"UD04400253000\"]}\n\n"
            + "Query: 'show current location for UD04400253000'\n"
            + "Output: {\"intents\":[\"CODE_HISTORY\"],\"locationCode\":\"UD04400253000\",\"locationName\":null,\"reportType\":null,\"department\":null,\"psm\":null,\"grade\":null,\"filter\":null,\"modifier\":\"FETCH_CURRENT\",\"rawKeywords\":[\"current location\",\"UD04400253000\"]}\n\n"
            + "Query: 'Search location code history for UD04400253000'\n"
            + "Output: {\"intents\":[\"CODE_HISTORY\"],\"locationCode\":\"UD04400253000\",\"locationName\":null,\"reportType\":null,\"department\":null,\"psm\":null,\"grade\":null,\"filter\":null,\"modifier\":null,\"rawKeywords\":[\"location code history\",\"UD04400253000\"]}\n\n"
            + "Query: 'what is the new code for UD04400253000'\n"
            + "Output: {\"intents\":[\"CODE_HISTORY\"],\"locationCode\":\"UD04400253000\",\"locationName\":null,\"reportType\":null,\"department\":null,\"psm\":null,\"grade\":null,\"filter\":null,\"modifier\":null,\"rawKeywords\":[\"new code\",\"UD04400253000\"]}\n\n"
            + "Query: 'Show historic grade of declared monuments'\n"
            + "Output: {\"intents\":[\"MONUMENT\",\"HISTORIC\"],\"locationCode\":null,\"locationName\":null,\"reportType\":null,\"department\":null,\"psm\":null,\"grade\":\"ALL\",\"filter\":\"T\",\"modifier\":null,\"rawKeywords\":[\"historic grade\",\"declared monuments\"]}\n\n"
            + "Query: 'Do any monuments in Sha Tin have BSI reports?'\n"
            + "Output: {\"intents\":[\"MONUMENT\",\"REPORT\"],\"locationCode\":null,\"locationName\":\"Sha Tin\",\"reportType\":\"BSI\",\"department\":null,\"psm\":null,\"grade\":null,\"filter\":\"T\",\"modifier\":null,\"rawKeywords\":[\"monuments\",\"Sha Tin\",\"BSI\"]}\n\n"
            + "Query: 'Which LCSD monuments are historic buildings?'\n"
            + "Output: {\"intents\":[\"MONUMENT\",\"HISTORIC\",\"DEPARTMENT\"],\"locationCode\":null,\"locationName\":null,\"reportType\":null,\"department\":\"LCSD\",\"psm\":null,\"grade\":\"ALL\",\"filter\":\"T\",\"modifier\":null,\"rawKeywords\":[\"LCSD\",\"monuments\",\"historic\"]}\n\n"
            + "Query: 'Show historic buildings under PSM/SHA TIN EAST'\n"
            + "Output: {\"intents\":[\"HISTORIC\",\"PSM\"],\"locationCode\":null,\"locationName\":null,\"reportType\":null,\"department\":null,\"psm\":\"SHA TIN EAST\",\"grade\":\"ALL\",\"filter\":null,\"modifier\":null,\"rawKeywords\":[\"historic buildings\",\"SHA TIN EAST\"]}\n\n"
            + "Query: 'Which department manages oldest monument in Sha Tin?'\n"
            + "Output: {\"intents\":[\"MONUMENT\"],\"locationCode\":null,\"locationName\":\"Sha Tin\",\"reportType\":null,\"department\":null,\"psm\":null,\"grade\":null,\"filter\":\"T\",\"modifier\":\"OLDEST\",\"rawKeywords\":[\"department\",\"oldest\",\"monument\",\"Sha Tin\"]}\n\n"
            + "Query: 'Which AFCD locations have both BSI and KAI reports?'\n"
            + "Output: {\"plan\":["
            + "{\"type\":\"DEPARTMENT_LOCATIONS\",\"priority\":1,\"params\":{\"deptCd\":\"AFCD\"},\"relation\":\"independent\"},"
            + "{\"type\":\"CHECK_REPORTS\",\"priority\":2,\"params\":{\"reportType\":\"BSI\"},\"relation\":\"use_previous_codes\"},"
            + "{\"type\":\"CHECK_REPORTS\",\"priority\":3,\"params\":{\"reportType\":\"KAI\"},\"relation\":\"use_previous_codes\"}"
            + "]}\n\n"
            + "VALID PLAN STEP TYPES (use ONLY these exact strings for the \"type\" field):\n"
            + "- NAME_SEARCH: search locations by name (params: locName, location)\n"
            + "- DECLARED_MONUMENT: list declared monuments (params: filter=T/F/ALL)\n"
            + "- HISTORIC_BUILDING: list graded buildings (params: grade=1/2/3/ALL/NONE)\n"
            + "- PSM_LOCATIONS: list locations under a PSM (params: psm)\n"
            + "- DEPARTMENT_LOCATIONS: list locations for a department (params: deptCd)\n"
            + "- LOCATION_CODE: get full details for a location code (params: locCd)\n"
            + "- CHECK_REPORTS: check report availability (params: reportType, locCds)\n"
            + "- CODE_HISTORY: lookup former/current codes (params: locCd)\n"
            + "Do NOT invent types like LOCATION_NAME_SEARCH, DEPARTMENT_INFO, or MONUMENT_INFO.\n"
            + "If you need to get the department of a location, use LOCATION_CODE (which returns DEPT_CD).\n\n"
            + "PLAN EXAMPLES:\n"
            + "Query: 'Show historic buildings under PSM/KT'\n"
            + "Output: {\"intents\":[\"HISTORIC\",\"PSM\"],\"primaryIntent\":\"HISTORIC\",\"showDetails\":false," + "\"locationCode\":null,\"locationName\":null,\"reportType\":null,\"department\":null,\"psm\":\"KT\",\"grade\":\"ALL\",\"filter\":null,\"modifier\":null," + "\"rawKeywords\":[\"historic buildings\",\"PSM\",\"KT\"]," + "\"plan\":[{\"type\":\"PSM_LOCATIONS\",\"priority\":1,\"params\":{\"psm\":\"KT\"},\"relation\":\"independent\"},{\"type\":\"HISTORIC_BUILDING\",\"priority\":2,\"params\":{\"grade\":\"ALL\"},\"relation\":\"filter_previous\"}]}\n\n"
            + "Query: 'Show historic grade of declared monuments'\n"
            + "Output: {\"intents\":[\"MONUMENT\",\"HISTORIC\"],\"primaryIntent\":\"HISTORIC\",\"showDetails\":false," + "\"locationCode\":null,\"locationName\":null,\"reportType\":null,\"department\":null,\"psm\":null,\"grade\":\"ALL\",\"filter\":\"T\",\"modifier\":null," + "\"rawKeywords\":[\"historic grade\",\"declared monuments\"]," + "\"plan\":[{\"type\":\"DECLARED_MONUMENT\",\"priority\":1,\"params\":{\"filter\":\"T\"},\"relation\":\"independent\"},{\"type\":\"HISTORIC_BUILDING\",\"priority\":2,\"params\":{\"grade\":\"ALL\"},\"relation\":\"enrich_previous\"}]}\n\n"
            + "Query: 'Find the first historic location under PSM/KT and show its details'\n"
            + "Output: {\"intents\":[\"HISTORIC\",\"PSM\"],\"primaryIntent\":\"HISTORIC\",\"showDetails\":true," + "\"locationCode\":null,\"locationName\":null,\"reportType\":null,\"department\":null,\"psm\":\"KT\",\"grade\":\"ALL\",\"filter\":null,\"modifier\":\"FIRST\"," + "\"rawKeywords\":[\"first\",\"historic\",\"location\",\"PSM\",\"KT\",\"details\"]," + "\"plan\":[{\"type\":\"PSM_LOCATIONS\",\"priority\":1,\"params\":{\"psm\":\"KT\"},\"relation\":\"independent\"},{\"type\":\"HISTORIC_BUILDING\",\"priority\":2,\"params\":{\"grade\":\"ALL\",\"modifier\":\"FIRST\"},\"relation\":\"filter_previous\"}]}\n\n"
            + "Query: 'Which playground in Lo Wu has a historic status?'\n"
            + "Output: {\"intents\":[\"NAME_SEARCH\",\"HISTORIC\"],\"primaryIntent\":\"HISTORIC\",\"showDetails\":false," + "\"locationCode\":null,\"locationName\":\"Lo Wu\",\"reportType\":null,\"department\":null,\"psm\":null,\"grade\":\"ALL\",\"filter\":null,\"modifier\":null," + "\"rawKeywords\":[\"playground\",\"Lo Wu\",\"historic status\"]," + "\"plan\":[{\"type\":\"NAME_SEARCH\",\"priority\":1,\"params\":{\"locName\":\"playground\",\"location\":\"Lo Wu\"},\"relation\":\"independent\"},{\"type\":\"HISTORIC_BUILDING\",\"priority\":2,\"params\":{\"grade\":\"ALL\",\"location\":\"Lo Wu\"},\"relation\":\"filter_previous\"}]}\n\n"
            + "Query: 'Which department manages the oldest historic monument in Lo Wu?'\n"
            + "Output: {\"intents\":[\"MONUMENT\"],\"primaryIntent\":\"MONUMENT\",\"showDetails\":true,"
            + "\"locationCode\":null,\"locationName\":\"Lo Wu\",\"reportType\":null,\"department\":null,\"psm\":null,"
            + "\"grade\":null,\"filter\":\"T\",\"modifier\":\"OLDEST\",\"rawKeywords\":[\"department\",\"oldest\",\"monument\",\"Lo Wu\"],"
            + "\"plan\":["
            + "{\"type\":\"NAME_SEARCH\",\"priority\":1,\"params\":{\"locName\":\"Lo Wu\"},\"relation\":\"independent\"},"
            + "{\"type\":\"DECLARED_MONUMENT\",\"priority\":2,\"params\":{\"filter\":\"T\",\"modifier\":\"OLDEST\"},\"relation\":\"use_previous_codes\"}"
            + "]}\n\n"
            + "Return ONLY the JSON object. No explanation. No markdown. /nothink";
    private static final String SQL_GENERATE_PROMPT
            = "You are a SQL query generator for a SQL Server database.\n\n"
            + "Generate a single valid T-SQL SELECT query to answer the user's question.\n\n"
            + "AVAILABLE TABLES (copy these names EXACTLY — do not modify them):\n"
            + "- ais.A_GENERAL_INFO           columns: LOC_CD, LOC_NAME, ADDRESS, DEPT_CD, DEPT_DESC, PSM, BLDG_COMPLETION_YEAR\n"
            + "- ais.BSI_GENERAL_INFO         columns: LOC_CD, BLDG_SAFETY_INSP_REPORT_NO, CREATE_TIME\n"
            + "- ais.CS_PLAN                  columns: LOC_CD, FILE_PATH_AUTOCAD\n"
            + "- ais.KAI_RECORD_PLANS_AND_DRAWINGS  columns: LOC_CD, AUTOCAD_PATH\n"
            + "- ais.OLD_EMMS                 columns: LOC_CD, REPORT_LINK\n"
            + "- ais.DSSR_REPORT              columns: LOC_CD, REPORT_NO\n"
            + "- " + AppConfig.GISdbName() + ".sde.T_ASD_COMBINED    columns: LOC_CD, DECLR_MONUMT, GRD_HIST_BLDG, BLDG_COMP_YEAR\n"
            + "- ais.A_LOC_CD_CHANGE_HISTORY  columns: FORMER_LOC_CD, CURRENT_LOC_CD\n\n"
            + " CRITICAL TABLE NAME WARNING:\n"
            + "The KAI table is: ais.KAI_RECORD_PLANS_AND_DRAWINGS\n"
            + "NOT: KAI_RECORD_PLANS_OR_DRAWINGS\n"
            + "NOT: KAI_RECORD_PLANS_TO_DRAWINGS\n"
            + "NOT: KAI_PLANS_AND_DRAWINGS\n"
            + "Copy it EXACTLY as shown above.\n\n"
            + "CRITICAL FILTERING RULES (LOC_CD vs DEPT_CD):\n"
            + "- LOC_CD (Location Code): Always an 11 to 15 character alphanumeric code (e.g., UC07300217003, SB04400361000). If the query contains a code like this, filter by g.LOC_CD = 'CODE'.\n"
            + "- DEPT_CD (Department Code): Always a short alphabetic acronym (e.g., LCSD, AFCD, HD, DSD). Never filter g.DEPT_CD by an 11-digit location code.\n"
            + "- If the user asks for the department or PSM managing a specific location code, SELECT g.LOC_CD, g.LOC_NAME, g.ADDRESS, g.DEPT_CD, g.DEPT_DESC, g.PSM and filter WHERE g.LOC_CD = 'CODE'.\n\n"
            + "TOOL SQL PATTERN CATALOG — copy these patterns exactly. The application already uses them successfully, so the generated SQL should follow the same table aliases, joins, and filters.\n\n"
            + "--- PSM locations ---\n"
            + "SELECT TOP 200 g.LOC_CD, g.LOC_NAME, g.ADDRESS\n"
            + "FROM ais.A_GENERAL_INFO g\n"
            + "WHERE UPPER(g.PSM) LIKE '%KEYWORD%'\n"
            + "ORDER BY g.LOC_NAME\n\n"
            + "--- Department locations ---\n"
            + "SELECT TOP 200 g.LOC_CD, g.LOC_NAME, g.ADDRESS, g.DEPT_CD\n"
            + "FROM ais.A_GENERAL_INFO g\n"
            + "WHERE UPPER(LTRIM(RTRIM(g.DEPT_CD))) = 'DEPTCODE'\n"
            + "ORDER BY g.LOC_NAME\n\n"
            + "--- Search by name ---\n"
            + "SELECT TOP 10 g.LOC_CD, g.LOC_NAME, g.ADDRESS\n"
            + "FROM ais.A_GENERAL_INFO g\n"
            + "WHERE g.LOC_NAME LIKE '%KEYWORD%'\n"
            + "ORDER BY g.LOC_NAME\n\n"
            + "--- Historic buildings (use this pattern for any historic query) ---\n"
            + "SELECT TOP 200 c.LOC_CD, g.LOC_NAME, g.ADDRESS, c.GRD_HIST_BLDG\n"
            + "FROM (\n"
            + "  SELECT LOC_CD, MAX(GRD_HIST_BLDG) AS GRD_HIST_BLDG\n"
            + "  FROM " + AppConfig.GISdbName() + ".sde.T_ASD_COMBINED\n"
            + "  GROUP BY LOC_CD\n"
            + ") c\n"
            + "LEFT JOIN ais.A_GENERAL_INFO g ON c.LOC_CD = g.LOC_CD\n"
            + "WHERE c.GRD_HIST_BLDG IS NOT NULL\n"
            + "  AND LTRIM(RTRIM(c.GRD_HIST_BLDG)) <> ''\n"
            + "  AND LTRIM(RTRIM(c.GRD_HIST_BLDG)) <> '0'\n"
            + "  -- for grade='1/2/3' add: AND LTRIM(RTRIM(c.GRD_HIST_BLDG)) = 'GRADE'\n"
            + "  -- for grade='NONE' or '0' add: AND (c.GRD_HIST_BLDG IS NULL OR LTRIM(RTRIM(c.GRD_HIST_BLDG)) = '' OR LTRIM(RTRIM(c.GRD_HIST_BLDG)) = '0')\n"
            + "ORDER BY g.LOC_NAME\n\n"
            + "--- Declared monuments (use this pattern for any monument query) ---\n"
            + "SELECT TOP 200 c.LOC_CD, g.LOC_NAME, g.ADDRESS, c.DECLR_MONUMT\n"
            + "FROM (\n"
            + "  SELECT LOC_CD, MAX(DECLR_MONUMT) AS DECLR_MONUMT\n"
            + "  FROM " + AppConfig.GISdbName() + ".sde.T_ASD_COMBINED\n"
            + "  GROUP BY LOC_CD\n"
            + ") c\n"
            + "LEFT JOIN ais.A_GENERAL_INFO g ON c.LOC_CD = g.LOC_CD\n"
            + "WHERE 1=1\n"
            + "  -- for monuments add: AND UPPER(LTRIM(RTRIM(c.DECLR_MONUMT))) = 'T'\n"
            + "  -- for non-monuments add: AND (UPPER(LTRIM(RTRIM(c.DECLR_MONUMT))) = 'F' OR c.DECLR_MONUMT IS NULL)\n"
            + "ORDER BY g.LOC_NAME\n\n"
            + "--- Historic + PSM combined (e.g. first historic location under PSM/KT) ---\n"
            + "SELECT TOP 200 c.LOC_CD, g.LOC_NAME, g.ADDRESS, c.GRD_HIST_BLDG\n"
            + "FROM (\n"
            + "  SELECT LOC_CD, MAX(GRD_HIST_BLDG) AS GRD_HIST_BLDG\n"
            + "  FROM " + AppConfig.GISdbName() + ".sde.T_ASD_COMBINED\n"
            + "  GROUP BY LOC_CD\n"
            + ") c\n"
            + "LEFT JOIN ais.A_GENERAL_INFO g ON c.LOC_CD = g.LOC_CD\n"
            + "WHERE UPPER(g.PSM) LIKE '%KEYWORD%'\n"
            + "  AND c.GRD_HIST_BLDG IS NOT NULL\n"
            + "  AND LTRIM(RTRIM(c.GRD_HIST_BLDG)) <> ''\n"
            + "  AND LTRIM(RTRIM(c.GRD_HIST_BLDG)) <> '0'\n"
            + "ORDER BY g.LOC_NAME\n\n"
            + "--- Location code history ---\n"
            + "SELECT h.CURRENT_LOC_CD, h.FORMER_LOC_CD,\n"
            + "       g1.LOC_NAME AS CURRENT_LOC_NAME,\n"
            + "       g2.LOC_NAME AS FORMER_LOC_NAME\n"
            + "FROM ais.A_LOC_CD_CHANGE_HISTORY h\n"
            + "LEFT JOIN ais.A_GENERAL_INFO g1 ON h.CURRENT_LOC_CD = g1.LOC_CD\n"
            + "LEFT JOIN ais.A_GENERAL_INFO g2 ON h.FORMER_LOC_CD = g2.LOC_CD\n"
            + "WHERE UPPER(h.FORMER_LOC_CD) = 'CODE' OR UPPER(h.CURRENT_LOC_CD) = 'CODE'\n"
            + "ORDER BY h.CURRENT_LOC_CD\n\n"
            + "RULES:\n"
            + "1. Only generate SELECT statements — never INSERT, UPDATE, DELETE, DROP.\n"
            + "2. Use TOP 200 unless the user asks for a count. The tool pipeline is limited to 50 rows, so a generated SQL query should scan more rows to find answers that the tool might have missed.\n"
            + "3. For historic buildings and declared monuments ALWAYS use the subquery pattern shown in the Tool SQL Pattern Catalog (deduplicate the GIS table first, then LEFT JOIN ais.A_GENERAL_INFO). Do not join the GIS table directly to A_GENERAL_INFO without the subquery.\n"
            + "4. For PSM/department/name filters, apply them on the ais.A_GENERAL_INFO columns.\n"
            + "5. If NO location name is mentioned, do NOT add any location filter.\n"
            + "6. To check if a report exists use EXISTS subquery.\n"
            + "7. Return ONLY the raw SQL. No explanation. No markdown. No comments.\n\n"
            + "EXAMPLES:\n\n"
            + "Question: Show department managing UC07300217003\n"
            + "SQL: SELECT TOP 50 g.LOC_CD, g.LOC_NAME, g.ADDRESS, g.DEPT_CD, g.DEPT_DESC, g.PSM "
            + "FROM ais.A_GENERAL_INFO g "
            + "WHERE UPPER(g.LOC_CD) = 'UC07300217003'\n\n"
            + "Question: show managing department of lo wu\n"
            + "SQL: SELECT TOP 50 g.LOC_CD, g.LOC_NAME, g.ADDRESS, g.DEPT_CD, g.DEPT_DESC, g.PSM "
            + "FROM ais.A_GENERAL_INFO g "
            + "WHERE (UPPER(g.LOC_NAME) LIKE '%LO WU%' OR UPPER(g.ADDRESS) LIKE '%LO WU%') "
            + "ORDER BY g.LOC_NAME\n\n"
            + "Question: any location code has all 5 reports?\n"
            + "SQL: SELECT TOP 50 g.LOC_CD, g.LOC_NAME, g.ADDRESS "
            + "FROM ais.A_GENERAL_INFO g "
            + "WHERE EXISTS (SELECT 1 FROM ais.BSI_GENERAL_INFO b WHERE b.LOC_CD = g.LOC_CD) "
            + "AND EXISTS (SELECT 1 FROM ais.CS_PLAN c WHERE c.LOC_CD = g.LOC_CD) "
            + "AND EXISTS (SELECT 1 FROM ais.KAI_RECORD_PLANS_AND_DRAWINGS k WHERE k.LOC_CD = g.LOC_CD) "
            + "AND EXISTS (SELECT 1 FROM ais.OLD_EMMS e WHERE e.LOC_CD = g.LOC_CD) "
            + "AND EXISTS (SELECT 1 FROM ais.DSSR_REPORT d WHERE d.LOC_CD = g.LOC_CD) "
            + "ORDER BY g.LOC_NAME\n\n"
            + "Question: how many LCSD locations have BSI report?\n"
            + "SQL: SELECT COUNT(*) AS total "
            + "FROM ais.A_GENERAL_INFO g "
            + "WHERE UPPER(LTRIM(RTRIM(g.DEPT_CD))) = 'LCSD' "
            + "AND EXISTS (SELECT 1 FROM ais.BSI_GENERAL_INFO b WHERE b.LOC_CD = g.LOC_CD)\n\n"
            + "Question: Find the first historic location under PSM/KT\n"
            + "SQL: SELECT TOP 200 c.LOC_CD, g.LOC_NAME, g.ADDRESS, c.GRD_HIST_BLDG "
            + "FROM ( "
            + "  SELECT LOC_CD, MAX(GRD_HIST_BLDG) AS GRD_HIST_BLDG "
            + "  FROM " + AppConfig.GISdbName() + ".sde.T_ASD_COMBINED "
            + "  GROUP BY LOC_CD "
            + ") c "
            + "LEFT JOIN ais.A_GENERAL_INFO g ON c.LOC_CD = g.LOC_CD "
            + "WHERE UPPER(g.PSM) LIKE '%KT%' "
            + "AND c.GRD_HIST_BLDG IS NOT NULL "
            + "AND LTRIM(RTRIM(c.GRD_HIST_BLDG)) <> '' "
            + "AND LTRIM(RTRIM(c.GRD_HIST_BLDG)) <> '0' "
            + "ORDER BY g.LOC_NAME\n\n"
            + "/nothink";

    public OllamaService() {
        this(new MCPClientService());
    }

    public OllamaService(MCPClientService mcpClient) {
        if (mcpClient == null) {
            throw new IllegalArgumentException(
                    "mcpClient must not be null");
        }

        this.httpClient = buildHttpClient();
        this.mapper = new ObjectMapper();
        this.mcpClient = mcpClient;
        this.planner = new QueryPlanner(mcpClient);
        this.planOptimizer = new PlanOptimizer(mcpClient);

        if (useTencent) {
            log.info("Using Tencent Cloud API — model: {}",
                    tencentModel);
        } else {
            log.info("Using Ollama — model: {}", ollamaModel);
        }
    }

    private OkHttpClient buildHttpClient() {
        try {
            // Trust all certificates (bypasses broken truststore)
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain,
                            String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain,
                            String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(),
                            (X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((hostname, session) -> true)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(300, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build();
        } catch (Exception e) {
            log.error("SSL bypass failed, using plain client: {}", e.getMessage());
            return new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(300, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build();
        }
    }
    private static final Pattern LOC_CD_PATTERN
            = Pattern.compile("\\b([A-Z]{2}\\d{11})\\b", Pattern.CASE_INSENSITIVE);
    // ── Session memory: stores last results + last report check ─────
    private static final Map<String, List<String>> LAST_RESULTS
            = new java.util.concurrent.ConcurrentHashMap<String, List<String>>();

    // ══════════════════════════════════════════════════════════════
    // HELPER FUNCTION FOR MEMORY
    // ══════════════════════════════════════════════════════════════
    // ── Save codes to memory ─────────────────────────────────────────
    private void rememberLastResults(String sessionId, String toolResult) {
        try {
            JsonNode node = mapper.readTree(toolResult);
            if (node.has("results") && node.path("results").isArray()) {
                List<String> codes = new ArrayList<String>();
                for (JsonNode item : node.path("results")) {
                    String code = item.path("LOC_CD").asText("").trim();
                    if (!code.isEmpty()) {
                        codes.add(code);
                    }
                }
                if (!codes.isEmpty()) {
                    LAST_RESULTS.put(sessionId, codes);
                    log.info("[Manual Info]Remembered {} codes for session {}",
                            codes.size(), sessionId);
                }
            }
        } catch (Exception ignored) {
        }
    }

    public List<String> getLastResults(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return new ArrayList<String>();
        }
        List<String> codes = LAST_RESULTS.get(sessionId);
        return codes != null ? new ArrayList<String>(codes) : new ArrayList<String>();
    }

    // ── Clear memory when user changes topic ─────────────────────────
    private void clearMemory(String sessionId) {
        if (LAST_RESULTS.containsKey(sessionId)) {
            log.info("[Manual Info]Clearing memory for session {} (new unrelated topic)",
                    sessionId);
            LAST_RESULTS.remove(sessionId);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // HELPER FUNCTION FOR MULTI LOCATION CODE PRESENTATION
    // ══════════════════════════════════════════════════════════════
    // ── Extract ALL location codes from text (not just first one) ────
    private List<String> extractAllLocCodes(String text) {
        List<String> codes = new ArrayList<String>();
        if (text == null) {
            return codes;
        }
        Matcher m = LOC_CD_PATTERN.matcher(text);
        LinkedHashSet<String> unique = new LinkedHashSet<String>();
        while (m.find()) {
            unique.add(m.group(1).toUpperCase());
        }
        codes.addAll(unique);
        return codes;
    }

    // ── Build a simple HTML summary when user provides only codes ────
    private String buildCodeListResponse(List<String> codes) {
        StringBuilder html = new StringBuilder();
        html.append("<h3 class='data-title'><i class='fa-solid fa-map-pin'></i> ")
                .append(codes.size())
                .append(" location codes received</h3>");
        html.append("<p class='answer-summary'>")
                .append("These codes are now saved. ")
                .append("You can ask: <em>\"which have BSI report\"</em>, ")
                .append("<em>\"check KAI and DSSR\"</em>, etc.</p>");
        html.append("<table class='data-table'>");
        html.append("<tr><th>#</th><th>Location Code</th></tr>");
        int idx = 1;
        for (String code : codes) {
            html.append("<tr>")
                    .append("<td>").append(idx++).append("</td>")
                    .append("<td><code>").append(escapeHtml(code)).append("</code></td>")
                    .append("</tr>");
            //html.append(false)
        }
        html.append("</table>");
        // Show available report types as helpful chips
        html.append("<p class='data-footer'>Available report types: ");
        for (ReportTypeRegistry.ReportType rt : ReportTypeRegistry.getAll()) {
            html.append("<span class='report-type-badge' style='margin:2px;'>")
                    .append(escapeHtml(rt.shortName))
                    .append("</span> ");
        }
        html.append("</p>");
        return html.toString();
    }
    // ══════════════════════════════════════════════════════════════
    // PROMPT COMPLEXITY CLASSIFIER
    // Returns true if prompt needs LLM reasoning,
    // false if it can be handled by search_by_name directly.
    // ══════════════════════════════════════════════════════════════
    private final QueryPlanner planner;

    // ══════════════════════════════════════════════════════════════
    // MAIN ENTRY POINT
    // ══════════════════════════════════════════════════════════════
    public AgentResult invoke(
            String userPrompt, String sessionId,
            AuthorizationContext authorizationContext
    ) throws IOException {
        boolean sqlAttempted = false;
        boolean sqlTimedOut = false;
        boolean fastPathAttempted = false;
        AgentResult result = new AgentResult();

        // ── Prompt injection defense (P1 — Issue #9) ─────────────
        userPrompt = sanitizeUserPrompt(userPrompt);

        result.setPrompt(userPrompt);
        long t0 = System.currentTimeMillis();

        // ── Phase 0: Resolve referential prompts from session memory ─────
        String resolvedPrompt = resolveReferentialPrompt(userPrompt, sessionId);
        if (!resolvedPrompt.equals(userPrompt)) {
            log.info("[Manual Info]Resolved '{}' → '{}'", userPrompt, resolvedPrompt);
            userPrompt = resolvedPrompt;
            result.setPrompt(userPrompt);
        }

        // ── Phase 1: Keyword extraction ───────────────────────────────
        log.info("[Manual Info]Extracting keywords...");
        ExtractedKeywords keywords = extractKeywords(userPrompt);
        log.info("[Manual Info]Phase 1: {}ms", System.currentTimeMillis() - t0);

        Integer promptLimit = extractLimitFromPrompt(userPrompt);
        if (promptLimit != null && keywords != null && keywords.getLimit() == null) {
            keywords.setLimit(promptLimit);
            log.info("[Manual Info] Detected limit={} from prompt text, applied to keywords", promptLimit);
        }
        // ── Phase 2: Query planner ────────────────────────────────────
        Plan plan = planner.analyse(userPrompt, keywords);
        log.info("[Manual Info]Plan: needsLlm={} steps={} reason={}",
                plan.needsLlm, plan.steps, plan.reason);
        if (keywords != null && keywords.getExcludeUndefinedField() != null) {
            for (Intent step : plan.steps) {
                step.params.putIfAbsent("excludeUndefinedField", keywords.getExcludeUndefinedField());
            }
        }
        Plan optimizedPlan = planOptimizer.optimize(plan);
        // ── Phase 3: Fast-path ────────────────────────────────────────
        if (!optimizedPlan.needsLlm && !optimizedPlan.steps.isEmpty()) {
            fastPathAttempted = true;
            String answer = executePlan(optimizedPlan, result, sessionId);
            if (!isEmptyResult(answer)) {
                // ── Natural language summary (P2 — Issue #5) ─────
                String summary = generateNaturalLanguageSummary(answer, userPrompt);
                result.setAnswer(summary + answer);
                log.info("[Manual Info]Total: {}ms (fast-path)", System.currentTimeMillis() - t0);
                return result;
            }
            log.info("[Manual Info]Fast-path returned empty result → trying SQL generation");
            result.setAnswer(answer); // keep the empty answer for later fallback checks
        }
        // ── Phase 4: SQL generation fallback ───────────────────────────
        // Trigger SQL generation when:
        // 1. The query is unknown / compound / explicitly SQL_QUERY
        // 2. The fast-path tool result was empty (e.g. 50-row limit hid the match)
        boolean useSqlFallback = keywords != null && keywords.hasIntent("SQL_QUERY");
        if (!useSqlFallback && fastPathAttempted && isEmptyResult(result.getAnswer())) {
            useSqlFallback = true;
        }

        if (useSqlFallback && !sqlAttempted) {
            sqlAttempted = true;
            try {
                String sqlResult = generateAndExecuteSql(userPrompt, keywords, authorizationContext);
                if (isTimeoutResult(sqlResult)) {
                    sqlTimedOut = true;
                    log.warn("Generated SQL timed out; this strategy will not run again in this request");
                } else if (!isUselessSqlResult(sqlResult)) {
                    String html = formatSqlResultWithDetails(sqlResult, keywords, result);
                    String summary = generateNaturalLanguageSummary(html, userPrompt);
                    result.setAnswer(summary + html);
                    log.info("[Manual Info]Total: {}ms (SQL gen)", System.currentTimeMillis() - t0);
                    return result;
                }
            } catch (Exception e) {
                log.error("SQL generation failed: {}", e.getMessage());
            }
        }
        // ── Phase 5: Agent loop (last resort) ─────────────────────────
        log.info("[Manual Info]Entering agent loop: {}", plan.reason);
        AgentResult agentResult = runAgentLoop(userPrompt, keywords, result, sessionId);
        // ── Phase 6: Post-agent SQL fallback ──────────────────────────
        if (!sqlAttempted && !sqlTimedOut && isEmptyResult(agentResult.getAnswer())) {
            sqlAttempted = true;
            log.info("[Manual Info]Agent empty → one SQL fallback");
            try {
                String sqlResult = generateAndExecuteSql(userPrompt, keywords, authorizationContext);
                if (isTimeoutResult(sqlResult)) {
                    sqlTimedOut = true;
                } else if (!isUselessSqlResult(sqlResult)) {
                    String html = formatSqlResultWithDetails(sqlResult, keywords, agentResult);
                    agentResult.setAnswer(html);
                }
            } catch (Exception e) {
                log.error("SQL fallback failed: {}", e.getMessage());
            }
        }
        log.info("[Manual Info]Total: {}ms", System.currentTimeMillis() - t0);
        return agentResult;
    }

    public AgentResult invoke(String userPrompt,
            String sessionId)
            throws IOException {

        return invoke(userPrompt, sessionId, null);
    }

    // ── Check if SQL result itself is useless ────────────────────────
    private boolean isUselessSqlResult(String sqlResult) {
        if (sqlResult == null || sqlResult.trim().isEmpty()) {
            return true;
        }
        String lower = sqlResult.toLowerCase();
        // If the SQL execution itself reported an error, treat it as useless.
        if (lower.contains("error")) {
            return true;
        }
        try {
            JsonNode node = mapper.readTree(sqlResult);
            if (node.has("error") && !node.path("error").asText("").trim().isEmpty()) {
                return true;
            }
            // A successful SQL execution that returns 0 rows is a valid "no results"
            // answer, not a useless result. The formatter will render it as
            // "No matching locations found." instead of falling back to the agent loop.
            if (node.has("count") && node.path("count").isInt()) {
                int count = node.path("count").asInt(-1);
                if (count == 0) {
                    log.info("[Manual Info]SQL returned 0 rows but executed successfully — valid empty answer");
                    return false;
                }
            }
            // If the response is missing both count and results, it is malformed/useless.
            if (!node.has("results")) {
                return true;
            }
        } catch (Exception e) {
            // Not valid JSON; rely on text checks above
        }
        return false;
    }

    // ── Check if agent result is empty or just "No results found" ────
    private boolean isEmptyResult(String html) {
        if (html == null || html.trim().isEmpty()) {
            return true;
        }
        // Strip HTML tags
        String text = html.replaceAll("<[^>]+>", "").trim().toLowerCase();
        if (text.isEmpty()) {
            return true;
        }
        // ── LLM gave explanation instead of answer ────────────────────
        String[] noAnswerPatterns = {
            "cannot proceed",
            "cannot answer",
            "i need to",
            "i will use",
            "i will start",
            "i would need",
            "to answer this",
            "i need more",
            "please provide",
            "no results found",
            "no matching locations found",
            "found 0 location",
            "found 0 ",
            "not enough information",
            "i don't have",
            "i do not have",
            "would need to check",
            "i will check",
            "let me search",
            "i'll search",
            "i'll use",
            "need specific",
            "need location codes",
            "without specific"
        };
        for (String pattern : noAnswerPatterns) {
            if (text.contains(pattern)) {
                log.info("[Manual Info]Non-answer pattern '{}' → SQL generation", pattern);
                return true;
            }
        }
        // ── Result has data but all zeros — useless answer ────────────
        // e.g. "0 of 5 have BSI", "0 of 5 have CSR" repeated
        // Count how many "0 of X" patterns appear
        java.util.regex.Matcher zeroMatcher = java.util.regex.Pattern
                .compile("0\\s+of\\s+\\d+")
                .matcher(text);
        int zeroCount = 0;
        while (zeroMatcher.find()) {
            zeroCount++;
        }
        // If 3+ "0 of X" results, the agent is clearly going in circles
        if (zeroCount >= 3) {
            log.info("[Manual Info]Detected {} zero-result patterns → SQL generation", zeroCount);
            return true;
        }
        // ── No meaningful data structure ──────────────────────────────
        boolean hasTable = html.contains("<table");
        boolean hasCode = html.contains("<code");
        boolean hasSummary = html.contains("answer-summary");
        if (hasSummary && !hasTable && !hasCode) {
            log.info("[Manual Info]Summary only, no data → SQL generation");
            return true;
        }
        return false;
    }

    // Keep old signature for backward compatibility
    public AgentResult invoke(String userPrompt) throws IOException {
        return invoke(userPrompt, "default");
    }
    // ══════════════════════════════════════════════════════════════
    // LLM HTTP CALLS
    // ══════════════════════════════════════════════════════════════

    /**
     * Simple text-in / text-out call. Used by: extractKeywords,
     * generateAndExecuteSql, VerifierNode. Routes to Tencent or Ollama based on
     * config.
     */
    public String callLlmSimple(String prompt,
            double temperature,
            int maxTokens) throws IOException {
        if (useTencent) {
            return callTencentSimple(prompt, temperature, maxTokens);
        }
        return callOllamaSimple(prompt, temperature, maxTokens);
    }

    // ── Tencent: simple prompt → text ────────────────────────────
    private String callTencentSimple(String prompt,
            double temperature,
            int maxTokens) throws IOException {
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("role", "user");
        message.put("content", prompt);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", tencentModel);
        body.put("messages", new Object[]{message});
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);
        String jsonBody = mapper.writeValueAsString(body);
        log.debug("[Manual Debug] Tencent simple request → model={}", tencentModel);
        Request request = new Request.Builder()
                .url(tencentBaseUrl + "/chat/completions")
                .addHeader("Authorization", "Bearer " + tencentApiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, JSON_TYPE))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errBody = response.body() != null
                        ? response.body().string() : "(empty)";
                log.error("[Manual Error] Tencent simple error {}: {}", response.code(), errBody);
                throw new IOException("Tencent API error "
                        + response.code() + ": " + errBody);
            }
            String responseBody = response.body().string();
            log.debug("[Manual Debug] Tencent simple response received");
            return parseTencentResponse(responseBody);
        }
    }

    // ── Ollama: simple prompt → text ─────────────────────────────
    private String callOllamaSimple(String prompt,
            double temperature,
            int numCtx) throws IOException {
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("role", "user");
        message.put("content", prompt);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", ollamaModel);
        body.put("messages", new Object[]{message});
        body.put("temperature", temperature);
        body.put("num_ctx", numCtx);
        body.put("stream", false);
        String jsonBody = mapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(OLLAMA_URL)
                .post(RequestBody.create(jsonBody, JSON_TYPE))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Ollama HTTP " + response.code());
            }
            String responseBody = response.body().string();
            JsonNode root = mapper.readTree(responseBody);
            if (root.has("message") && root.get("message").has("content")) {
                return root.get("message").get("content").asText();
            }
            if (root.has("response")) {
                return root.get("response").asText();
            }
            throw new IOException("Unknown Ollama response format: " + responseBody);
        }
    }

    // ── Parse Tencent/OpenAI-compatible simple response ──────────
    private String parseTencentResponse(String responseBody) throws IOException {
        JsonNode root = mapper.readTree(responseBody);
        // { "choices": [ { "message": { "content": "..." } } ] }
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            return choices.get(0)
                    .path("message")
                    .path("content")
                    .asText("");
        }
        // { "error": { "message": "..." } }
        JsonNode error = root.path("error");
        if (!error.isMissingNode()) {
            throw new IOException("Tencent API error: "
                    + error.path("message").asText("unknown error"));
        }
        throw new IOException("Unexpected Tencent response: " + responseBody);
    }

    // ══════════════════════════════════════════════════════════════
    // AGENT LOOP CALL  (returns JsonNode so runAgentLoop can read
    // tool_calls + content — different from the simple text call)
    // ══════════════════════════════════════════════════════════════
    /**
     * Routes the agent loop LLM call to Tencent or Ollama. Signature kept as
     * (messages, tools, systemPrompt) → JsonNode so runAgentLoop() needs zero
     * changes.
     */
    private JsonNode callOllama(List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String systemPrompt) throws IOException {
        if (useTencent) {
            return callTencentAgent(messages, tools, systemPrompt);
        }
        return callOllamaAgent(messages, tools, systemPrompt);
    }

    // ── Ollama: agent loop → JsonNode message ────────────────────
    private JsonNode callOllamaAgent(List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String systemPrompt) throws IOException {
        // Build full message list with system prompt first
        List<Map<String, Object>> fullMessages
                = new ArrayList<Map<String, Object>>();
        Map<String, Object> sys = new LinkedHashMap<String, Object>();
        sys.put("role", "system");
        sys.put("content", systemPrompt);          // param, not missing var
        fullMessages.add(sys);
        fullMessages.addAll(messages);             // List<Map> — no type error
        String requestBody = buildAgentRequest(fullMessages, tools); // tools is param
        log.debug("Ollama agent request: {}", requestBody);
        Request httpReq = new Request.Builder()
                .url(OLLAMA_URL)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, JSON_TYPE))
                .build();
        Response httpResp = null;
        try {
            httpResp = httpClient.newCall(httpReq).execute();
            if (!httpResp.isSuccessful()) {
                String errorBody = httpResp.body() != null
                        ? httpResp.body().string() : "no body";
                log.error("Ollama HTTP error {}: {}", httpResp.code(), errorBody);
                throw new IOException("Ollama HTTP error: "
                        + httpResp.code() + " — " + errorBody);
            }
            String body = httpResp.body().string();
            log.debug("Ollama agent raw response: {}", body);
            JsonNode root = mapper.readTree(body);
            String rawContent = root.path("message").path("content").asText("");
            if (rawContent.contains("<think>")) {
                log.warn(" LLM thinking tags detected — stripping.");
            }
            return root.path("message");           // returns JsonNode, not String
        } finally {
            if (httpResp != null) {
                httpResp.close();
            }
        }
    }

    // ── Tencent: agent loop → JsonNode message ───────────────────
    private JsonNode callTencentAgent(List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String systemPrompt) throws IOException {
        List<Map<String, Object>> fullMessages
                = new ArrayList<Map<String, Object>>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            Map<String, Object> sys = new LinkedHashMap<String, Object>();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            fullMessages.add(sys);
        }
        fullMessages.addAll(messages);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", tencentModel);
        body.put("messages", fullMessages);
        body.put("temperature", 0.1);
        body.put("stream", false);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }
        String jsonBody = mapper.writeValueAsString(body);
        log.debug("[Manual Debug] Tencent agent request → model={}", tencentModel);
        Request request = new Request.Builder()
                .url(tencentBaseUrl + "/chat/completions")
                .addHeader("Authorization", "Bearer " + tencentApiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, JSON_TYPE))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errBody = response.body() != null
                        ? response.body().string() : "(empty)";
                log.error("[Manual Error] Tencent agent error {}: {}", response.code(), errBody);
                throw new IOException("Tencent API error "
                        + response.code() + ": " + errBody);
            }
            String responseBody = response.body().string();
            log.debug("[Manual Debug] Tencent agent response received");
            return convertToOllamaFormat(mapper.readTree(responseBody));
        }
    }

    // ── Convert OpenAI response → Ollama-style message JsonNode ──
    // So runAgentLoop() works unchanged regardless of provider
    private JsonNode convertToOllamaFormat(JsonNode openAiResponse) {
        try {
            JsonNode choices = openAiResponse.path("choices");
            if (!choices.isArray() || choices.size() == 0) {
                log.warn("[Manual Warn] Tencent returned no choices");
                return mapper.createObjectNode();
            }
            JsonNode message = choices.get(0).path("message");
            com.fasterxml.jackson.databind.node.ObjectNode result
                    = mapper.createObjectNode();
            result.put("role", message.path("role").asText("assistant"));
            result.put("content", message.path("content").asText(""));
            // Map tool_calls if present
            if (message.has("tool_calls")) {
                result.set("tool_calls", message.get("tool_calls"));
            }
            return result;
        } catch (Exception e) {
            log.error("[Manual Error] Failed to convert OpenAI→Ollama format: {}", e.getMessage());
            return mapper.createObjectNode();
        }
    }

    // ── Build JSON body for agent loop (with tools) ──────────────
    private String buildAgentRequest(List<Map<String, Object>> messages,
            List<Map<String, Object>> tools)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", ollamaModel);
        body.put("messages", messages);
        body.put("stream", false);
        body.put("temperature", 0.1);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }
        return mapper.writeValueAsString(body);
    }

    // ══════════════════════════════════════════════════════════════
    // GENERATE SHORT SUMMARY (LLM call)
    // ══════════════════════════════════════════════════════════════
    // ══════════════════════════════════════════════════════════════
    // FORMAT JSON AS HTML TABLE
    // ══════════════════════════════════════════════════════════════
    private String formatAsHtml(String jsonResult) {
        try {
            JsonNode node = mapper.readTree(jsonResult);
            if (node.has("error")) {
                return "<div class='error-box'>[Error] "
                        + escapeHtml(node.path("error").asText())
                        + "</div>";
            }
            // ── Structured found/not-found response ───────────────────
            if (node.has("found") && node.has("summary")) {
                return formatFoundResponse(node);
            }
            // ── PSM list ──────────────────────────────────────────────
            if (node.has("psms") && node.path("psms").isArray()) {
                return formatPsmList(node);
            }
            // ── Locations under a PSM ─────────────────────────────────
            if (node.has("psm") && node.has("results")) {
                return formatLocationsByPsm(node);
            }
            if (node.has("checks") && node.path("checks").isObject()) {
                return formatMultipleReportChecks(node);
            }
            // ── Bulk report check ─────────────────────────────────────
            if (node.has("withReport") && node.has("withoutReport")) {
                return formatBulkReportCheck(node);
            }
            // ── Location code change history ──────────────────────────
            if ((node.has("formerLocCd") || node.has("currentLocCd"))
                    && node.has("results")) {
                return formatLocCdHistory(node);
            }
            // ── Locations by department ───────────────────────────────
            if (node.has("deptCd") && node.has("results")) {
                return formatLocationsByDept(node);
            }
            if (node.has("results") && node.path("results").isArray()) {
                JsonNode results = node.path("results");
                // ── Declared monuments ────────────────────────────────
                // Check for DECLR_MONUMT field OR filter field = T/F/ALL
                boolean isMonument = node.has("filter")
                        || (results.size() > 0
                        && results.get(0).has("DECLR_MONUMT"));
                if (isMonument) {
                    return formatDeclaredMonuments(node);
                }
                // ── Historic buildings ────────────────────────────────
                boolean isHistoric = node.has("grade")
                        || (results.size() > 0
                        && results.get(0).has("GRD_HIST_BLDG"));
                if (isHistoric) {
                    return formatHistoricBuildings(node);
                }
                // ── Code history ──────────────────────────────────────
                boolean isHistory = results.size() > 0
                        && results.get(0).has("CURRENT_LOC_CD")
                        && results.get(0).has("FORMER_LOC_CD");
                if (isHistory) {
                    return formatLocCdHistory(node);
                }
                // ── Generic search results ────────────────────────────
                return formatSearchResults(node);
            }
            if (!node.isObject()) {
                return "<pre>" + escapeHtml(jsonResult) + "</pre>";
            }
            return formatSingleLocation(node);
        } catch (Exception e) {
            log.error("HTML format error: {}", e.getMessage());
            return "<pre>" + escapeHtml(jsonResult) + "</pre>";
        }
    }

    // ── Format found/not-found structured response ────────────────
    private String formatFoundResponse(JsonNode node) {
        boolean found = node.path("found").asBoolean(false);
        String summary = node.path("summary").asText("");
        StringBuilder html = new StringBuilder();
        if (!found) {
            html.append("<div class='answer-box'>")
                    .append("<p class='answer-summary'> ")
                    .append(escapeHtml(summary))
                    .append("</p>")
                    .append("</div>");
            return html.toString();
        }
        // found=true: show as result card
        html.append("<div class='answer-box'>");
        html.append("<table class='data-table'>");
        String[] keyFields = {"LOC_CD", "LOC_NAME", "DEPT_CD", "DEPT_DESC", "YEAR"};
        for (String field : keyFields) {
            if (node.has(field) && !node.path(field).asText("").isEmpty()) {
                html.append("<tr>")
                        .append("<th>")
                        .append(escapeHtml(formatLabel(field)))
                        .append("</th>")
                        .append("<td>")
                        .append(escapeHtml(node.path(field).asText("")))
                        .append("</td>")
                        .append("</tr>");
            }
        }
        html.append("</table>");
        html.append("</div>");
        return html.toString();
    }

    // ── Format bulk report check (the new pretty output) ─────────────
    // ── Format bulk report check as a sortable/filterable TABLE ─────────────
    private String formatBulkReportCheck(JsonNode node) {
        String reportType = node.path("reportType").asText("");
        String reportName = node.path("reportName").asText("Report");
        int total = node.path("totalChecked").asInt(0);
        int withCount = node.path("withReportCount").asInt(0);
        StringBuilder html = new StringBuilder();

        html.append("<h3 class='data-title'> ")
                .append(escapeHtml(reportName))
                .append(" Availability</h3>");
        html.append("<p class='answer-summary'>")
                .append("<strong>").append(withCount).append("</strong> of ")
                .append("<strong>").append(total).append("</strong> ")
                .append("locations have a ").append(escapeHtml(reportType))
                .append(" report available.</p>");

        // ── Available reports (Formatted as a TABLE for UI filtering/widgets) ──
        JsonNode withReport = node.path("withReport");
        if (withReport.isArray() && withReport.size() > 0) {
            html.append("<table class='data-table'>");
            html.append("<tr><th>Code</th><th>Name</th><th>Report Link</th></tr>");
            for (JsonNode item : withReport) {
                String code = item.path("LOC_CD").asText("");
                String name = item.path("LOC_NAME").asText("").trim();
                String url = item.path("url").asText("");

                html.append("<tr>")
                        .append("<td><code>").append(escapeHtml(code)).append("</code></td>")
                        .append("<td><strong>").append(escapeHtml(name)).append("</strong></td>")
                        .append("<td>");

                if (url != null && !url.isEmpty() && !"null".equals(url)) {
                    html.append("<a href='").append(escapeHtml(url)).append("'")
                            .append(" target='_blank' rel='noopener noreferrer'")
                            .append(" class='report-link'>")
                            .append("<i class='fa-solid fa-file-pdf'></i> Open ").append(escapeHtml(reportType))
                            .append(" Report</a>");
                } else {
                    html.append("<span class='report-link-unavailable'>")
                            .append("Link unavailable")
                            .append("</span>");
                }
                html.append("</td></tr>");
            }
            html.append("</table>");
        }

        // ── Locations WITHOUT this report (Collapsible Table) ─────────────
        JsonNode withoutReport = node.path("withoutReport");
        if (withoutReport.isArray() && withoutReport.size() > 0) {
            html.append("<details style='margin-top:15px;'>");
            html.append("<summary style='cursor:pointer; color:#666;'>")
                    .append("Show ").append(withoutReport.size())
                    .append(" location(s) without ").append(escapeHtml(reportType))
                    .append(" report")
                    .append("</summary>");
            html.append("<table class='data-table' style='margin-top:8px;'>");
            html.append("<tr><th>Code</th><th>Name</th></tr>");
            for (JsonNode item : withoutReport) {
                html.append("<tr>")
                        .append("<td><code>")
                        .append(escapeHtml(item.path("LOC_CD").asText("")))
                        .append("</code></td>")
                        .append("<td>")
                        .append(escapeHtml(item.path("LOC_NAME").asText("").trim()))
                        .append("</td>")
                        .append("</tr>");
            }
            html.append("</table>");
            html.append("</details>");
        }

        return html.toString();
    }

    // ── Format search results as a list ──────────────────────────────
    // ── Format search results dynamically (supports LLM SQL columns) ──
    private String formatSearchResults(JsonNode node) {
        int count = node.path("count").asInt(0);
        JsonNode results = node.path("results");
        StringBuilder html = new StringBuilder();
        html.append("<h3 class='data-title'> Found ")
                .append(count).append(" location(s)</h3>");
        if (count == 0 || !results.isArray() || results.size() == 0) {
            html.append("<p>No matching locations found.</p>");
            return html.toString();
        }
        html.append("<table class='data-table'>");
        html.append("<tr>");
        // ── 1. DYNAMICALLY DETECT COLUMNS FROM FIRST RESULT OBJECT ──
        JsonNode firstItem = results.get(0);
        List<String> columns = new ArrayList<String>();
        Iterator<String> fieldNames = firstItem.fieldNames();
        while (fieldNames.hasNext()) {
            columns.add(fieldNames.next());
        }
        // ── 2. RENDER TABLE HEADERS ──
        for (String col : columns) {
            String headerLabel = col;
            if (col.equalsIgnoreCase("LOC_CD")) {
                headerLabel = "Code";
            } else if (col.equalsIgnoreCase("LOC_NAME")) {
                headerLabel = "Name";
            } else if (col.equalsIgnoreCase("ADDRESS")) {
                headerLabel = "Address";
            } else if (col.equalsIgnoreCase("DEPT_CD")) {
                headerLabel = "Dept Code";
            } else if (col.equalsIgnoreCase("DEPT_DESC")) {
                headerLabel = "Dept Desc";
            } else if (col.equalsIgnoreCase("PSM")) {
                headerLabel = "PSM";
            } else {
                headerLabel = formatLabel(col); // Fallback to your Title Case helper
            }
            html.append("<th>").append(escapeHtml(headerLabel)).append("</th>");
        }
        html.append("</tr>");
        // ── 3. RENDER TABLE ROWS DYNAMICALLY ──
        for (JsonNode item : results) {
            html.append("<tr>");
            for (String col : columns) {
                String val = item.path(col).asText("").trim();
                html.append("<td>");
                if (col.equalsIgnoreCase("LOC_CD")) {
                    html.append("<code>").append(escapeHtml(val)).append("</code>");
                } else if (col.equalsIgnoreCase("LOC_NAME")) {
                    html.append("<strong>").append(escapeHtml(val)).append("</strong>");
                } else {
                    html.append(escapeHtml(formatValue(val))); // Use your formatting helper
                }
                html.append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</table>");
        if (firstItem.has("LOC_CD")) {
            html.append("<p class='data-footer'>")
                    .append("<i class='fa-solid fa-lightbulb'></i> Tip: Use the code (e.g., '")
                    .append(escapeHtml(firstItem.path("LOC_CD").asText()))
                    .append("') to get full details.")
                    .append("</p>");
        }
        return html.toString();
    }

    // ── Format single location detail (your existing code) ───────────
    private String formatSingleLocation(JsonNode node) {
        StringBuilder html = new StringBuilder();
        boolean isSlope = node.path("isSlope").asBoolean(false);
        boolean isMonument = node.path("isMonument").asBoolean(false);
        String grade = node.path("historicGrade").asText("");
        JsonNode general = node.has("general") ? node.path("general") : node;
        String title = general.path("LOC_NAME").asText("").trim();
        String code = general.path("LOC_CD").asText("").trim();
        // ── Title ─────────────────────────────────────────────────────────
        if (!title.isEmpty()) {
            html.append("<h3 class='data-title'>")
                    .append(isSlope ? "<i class='fa-solid fa-mountain'></i> " : isMonument ? "<i class='fa-solid fa-building-columns'></i> " : "<i class='fa-solid fa-map-pin'></i> ")
                    .append(escapeHtml(title));
            if (!code.isEmpty()) {
                html.append(" <span class='data-code'>(")
                        .append(escapeHtml(code)).append(")</span>");
            }
            html.append("</h3>");
        }
        // ── Heritage badges ───────────────────────────────────────────────
        if (isMonument || !grade.isEmpty()) {
            html.append("<div style='margin:8px 0;'>");
            if (isMonument) {
                html.append("<span class='report-type-badge' style='background:#c0392b;color:white;'>")
                        .append("Declared Monument</span> ");
            }
            if (!grade.isEmpty()) {
                html.append("<span class='report-type-badge' style='background:#8e44ad;color:white;'>")
                        .append("Historic Grade ").append(escapeHtml(grade))
                        .append("</span>");
            }
            html.append("</div>");
        }
        // ── General info table ────────────────────────────────────────────
        html.append("<table class='data-table'>");
        int rowCount = 0;
        Iterator<Map.Entry<String, JsonNode>> fields = general.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            if ("LOC_NAME".equals(key) || "LOC_CD".equals(key)) {
                continue;
            }
            JsonNode value = entry.getValue();
            if (value.isNull()) {
                continue;
            }
            String valStr = value.asText().trim();
            if (valStr.isEmpty()) {
                continue;
            }
            html.append("<tr>")
                    .append("<th>").append(escapeHtml(formatLabel(key))).append("</th>")
                    .append("<td>").append(escapeHtml(formatValue(valStr))).append("</td>")
                    .append("</tr>");
            rowCount++;
        }
        html.append("</table>");
        html.append("<p class='data-footer'>Showing ")
                .append(rowCount).append(" field(s) with data</p>");
        // ── Reports ───────────────────────────────────────────────────────
        if (isSlope) {
            html.append(formatSlopeReports(node.path("slopeReports")));
            html.append(formatTmcpForms(node.path("tmcpForms")));
        } else if (node.has("reports") && node.path("reports").isArray()) {
            html.append(formatReportLinks(node.path("reports"), code));
        }
        // ── AIS Asset Search link ─────────────────────────────────────────
        if (!code.isEmpty()) {
            String assetType = isSlope ? "Slope" : "Building";

            html.append("<div class='location-map-block'>");

            html.append("<div style='margin-bottom:10px;'>")
                    .append("<a href=\"/arcgis_server/rest/services/ASSETMap/MapServer/2/query?f=json&where=LOC_CD%20LIKE%20%27%25")
                    .append(escapeHtml(code))
                    .append("%25%27&returnGeometry=true&spatialRel=esriSpatialRelIntersects&outFields=*&outSR=102140")
                    .append("\" target=\"_blank\" class=\"ais-detail-btn\" ")
                    .append("title=\"View ").append(escapeHtml(code)).append(" in AIS Asset Search\">")
                    .append("<i class='fa-solid fa-link'></i> View in AIS Asset Search")
                    .append("</a>")
                    .append("</div>");

            //iframe map
            html.append("<h4 style='margin: 10px 0;'>Location Map</h4>");
            html.append("<iframe src='")
                    .append(contextPath)
                    .append("/plugin/plugin.html?locCd=")
                    .append(escapeHtml(code))
                    .append("' width='100%' height='450' frameborder='0' ")
                    .append("style='border:1px solid #ccc; border-radius:6px;'></iframe>");

            html.append("</div>"); // .location-map-block

        }
        return html.toString();
    }

    // ── Render Slope_Report_Info grouped by type ────────────────────
    private String formatSlopeReports(JsonNode slopeReports) {
        if (slopeReports == null || !slopeReports.isObject()
                || slopeReports.size() == 0) {
            return "";
        }
        StringBuilder html = new StringBuilder();
        html.append("<h3 class='data-title' style='margin-top:20px;'>")
                .append("<i class='fa-solid fa-mountain'> Slope Inspection Reports</h3>");
        // Tooltips for each type
        Map<String, String> typeDescriptions = new HashMap<String, String>();
        typeDescriptions.put("BWCS", "Boundary & Works Completion Survey");
        typeDescriptions.put("VMI", "Visual Maintenance Inspection");
        typeDescriptions.put("RMI", "Routine Maintenance Inspection");
        typeDescriptions.put("AMI", "Annual Maintenance Inspection");
        typeDescriptions.put("OTHER", "Other Reports");
        html.append("<div class='reports-grid'>");
        Iterator<Map.Entry<String, JsonNode>> typeIt = slopeReports.fields();
        while (typeIt.hasNext()) {
            Map.Entry<String, JsonNode> typeEntry = typeIt.next();
            String type = typeEntry.getKey();
            JsonNode reports = typeEntry.getValue();
            if (!reports.isArray() || reports.size() == 0) {
                continue;
            }
            String description = typeDescriptions.getOrDefault(type, type);
            html.append("<div class='report-card report-available'>");
            html.append("<div class='report-name'>")
                    .append("<strong>").append(escapeHtml(type)).append("</strong>")
                    .append(" <span class='report-type-badge'>")
                    .append(reports.size()).append("</span>")
                    .append("<br/><small>").append(escapeHtml(description)).append("</small>")
                    .append("</div>");
            html.append("<div class='report-details'>");
            for (JsonNode report : reports) {
                String url = report.path("url").asText("");
                String fileId = report.path("fileId").asText("Report");
                html.append("<a href='").append(escapeHtml(url)).append("'")
                        .append(" target='_blank' rel='noopener noreferrer'")
                        .append(" class='report-link' style='display:block; margin:3px 0;'>")
                        .append(" ").append(escapeHtml(fileId))
                        .append("</a>");
            }
            html.append("</div>");
            html.append("</div>");
        }
        html.append("</div>");
        return html.toString();
    }

    // ── Render TMCP Forms with date as label ────────────────────────
    // ── Render TMCP Forms grouped by Form 1 / Form 2 ────────────────
    private String formatTmcpForms(JsonNode tmcpForms) {
        if (tmcpForms == null || !tmcpForms.isObject() || tmcpForms.size() == 0) {
            return "";
        }
        StringBuilder html = new StringBuilder();
        html.append("<h3 class='data-title' style='margin-top:20px;'>")
                .append("<i class='fa-solid fa-clipboard'></i> TMCP / TMIS Forms</h3>");
        html.append("<div class='reports-grid'>");
        Iterator<Map.Entry<String, JsonNode>> groupIt = tmcpForms.fields();
        while (groupIt.hasNext()) {
            Map.Entry<String, JsonNode> groupEntry = groupIt.next();
            String formName = groupEntry.getKey();   // "Form 1" or "Form 2"
            JsonNode entries = groupEntry.getValue();
            if (!entries.isArray() || entries.size() == 0) {
                continue;
            }
            html.append("<div class='report-card report-available'>");
            // Card header
            html.append("<div class='report-name'>")
                    .append("<strong>").append(escapeHtml(formName)).append("</strong>")
                    .append(" <span class='report-type-badge'>")
                    .append(entries.size())
                    .append("</span>")
                    .append("</div>");
            // Each form entry
            html.append("<div class='report-details'>");
            for (JsonNode entry : entries) {
                String url = entry.path("url").asText("");
                String inspDate = entry.path("inspDate").asText("");
                String source = entry.path("source").asText("");
                // Build display text: "Form 1 (2019-07-06)"
                String displayText = formName;
                if (!inspDate.isEmpty()) {
                    displayText += " (" + inspDate + ")";
                }
                if (url != null && !url.isEmpty()) {
                    html.append("<a href='").append(escapeHtml(url)).append("'")
                            .append(" target='_blank' rel='noopener noreferrer'")
                            .append(" class='report-link'")
                            .append(" style='display:block; margin:4px 0;'>")
                            .append(" ").append(escapeHtml(displayText));
                    // Optional: show source badge (TMIS / TMCP)
                    if (!source.isEmpty()) {
                        String badge = source.startsWith("TMIS") ? "TMIS" : "TMCP";
                        html.append(" <small style='opacity:0.6;'>[")
                                .append(escapeHtml(badge))
                                .append("]</small>");
                    }
                    html.append("</a>");
                }
            }
            html.append("</div>");
            html.append("</div>");
        }
        html.append("</div>");
        return html.toString();
    }

    // ── Format reports section with clickable links ─────────────────
    private String formatReportLinks(JsonNode reports, String locCd) {
        StringBuilder html = new StringBuilder();
        html.append("<h3 class='data-title' style='margin-top:20px;'>")
                .append(" Available Reports</h3>");
        html.append("<div class='reports-grid'>");
        for (JsonNode report : reports) {
            String name = report.path("name").asText("Report");
            boolean exists = report.path("exists").asBoolean(false);
            String reportId = report.path("reportId").asText("");
            String reportType = report.path("reportType").asText("");
            // ── Use the pre-computed URL from DatabaseManager ──────────
            String url = report.path("url").isNull() ? null
                    : report.path("url").asText(null);
            html.append("<div class='report-card ")
                    .append(exists ? "report-available" : "report-unavailable")
                    .append("'>");
            // Report name + type badge
            html.append("<div class='report-name'>")
                    .append(escapeHtml(name));
            if (!reportType.isEmpty()) {
                html.append(" <span class='report-type-badge'>")
                        .append(escapeHtml(reportType))
                        .append("</span>");
            }
            html.append("</div>");
            if (exists) {
                if (url != null && !url.isEmpty()) {
                    // ── Proper link from database logic ────────────────
                    html.append("<a href='").append(escapeHtml(url)).append("'")
                            .append(" target='_blank'")
                            .append(" rel='noopener noreferrer'")
                            .append(" class='report-link'>")
                            .append(" Open Report</a>");
                } else {
                    // URL could not be built (e.g., DSSR date parse failed)
                    html.append("<span class='report-link-unavailable'>")
                            .append(" Report exists (ID: ")
                            .append(escapeHtml(reportId))
                            .append(") but link could not be generated.")
                            .append("</span>");
                }
                // Metadata details
                JsonNode details = report.path("details");
                if (details.isObject() && details.size() > 0) {
                    html.append("<div class='report-details'>");
                    Iterator<Map.Entry<String, JsonNode>> dFields = details.fields();
                    while (dFields.hasNext()) {
                        Map.Entry<String, JsonNode> entry = dFields.next();
                        String val = entry.getValue().asText("").trim();
                        if (!val.isEmpty()) {
                            html.append("<small>")
                                    .append(escapeHtml(formatLabel(entry.getKey())))
                                    .append(": ")
                                    .append(escapeHtml(formatValue(val)))
                                    .append("</small><br/>");
                        }
                    }
                    html.append("</div>");
                }
            } else {
                html.append("<div class='report-unavailable-text'>")
                        .append("[Error] No report available")
                        .append("</div>");
            }
            html.append("</div>"); // close report-card
        }
        html.append("</div>"); // close reports-grid
        return html.toString();
    }

    // Convert "Survey Report" → "survey"
    private String slugify(String text) {
        return text.toLowerCase()
                .replaceAll("\\s+report", "")
                .replaceAll("\\s+", "-")
                .trim();
    }

    // ══════════════════════════════════════════════════════════════
    // HELPER: Convert SNAKE_CASE to "Title Case"
    // ══════════════════════════════════════════════════════════════
    private String formatLabel(String key) {
        String[] parts = key.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            if (part.length() > 0) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    sb.append(part.substring(1));
                }
            }
        }
        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════════
    // HELPER: Format values (numbers, dates, etc.)
    // ══════════════════════════════════════════════════════════════
    private String formatValue(String val) {
        if (val == null) {
            return "";
        }
        val = val.trim();
        // Try to format trailing .00000000 numbers
        if (val.matches("^-?\\d+\\.\\d+$")) {
            try {
                double d = Double.parseDouble(val);
                if (d == Math.floor(d)) {
                    return String.valueOf((long) d);
                }
                return String.format("%.2f", d);
            } catch (NumberFormatException ignored) {
            }
        }
        // Format unix timestamps (13-digit numbers)
        if (val.matches("^\\d{13}$")) {
            try {
                long ts = Long.parseLong(val);
                java.util.Date date = new java.util.Date(ts);
                return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .format(date);
            } catch (Exception ignored) {
            }
        }
        // Convert F/T to readable text
        if (val.equals("F")) {
            return "No";
        }
        if (val.equals("T")) {
            return "Yes";
        }
        return val;
    }

    // ══════════════════════════════════════════════════════════════
    // HELPER: HTML escape
    // ══════════════════════════════════════════════════════════════
    private String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    // ══════════════════════════════════════════════════════════════
    // HELPER: HTML unescape (LLMs sometimes return escaped SQL)
    // ══════════════════════════════════════════════════════════════
    private String unescapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    // ══════════════════════════════════════════════════════════════
    // BUILD INITIAL MESSAGES
    // ══════════════════════════════════════════════════════════════
    // ── Build initial messages — inject /nothink into USER message too ──
    private List<Map<String, Object>> buildInitialMessages(
            String userPrompt,
            ExtractedKeywords keywords) {
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        Map<String, Object> userMsg = new LinkedHashMap<String, Object>();
        userMsg.put("role", "user");
        StringBuilder content = new StringBuilder();
        content.append(userPrompt);
        if (keywords != null) {
            content.append("\n\n[Extracted context: ");
            content.append("intent=").append(keywords.getIntents());
            if (keywords.getLocationName() != null) {
                content.append(", location=").append(keywords.getLocationName());
            }
            if (keywords.getLocationCode() != null) {
                content.append(", code=").append(keywords.getLocationCode());
            }
            if (keywords.getModifier() != null) {
                content.append(", modifier=").append(keywords.getModifier());
            }
            if (keywords.getDepartment() != null) {
                content.append(", dept=").append(keywords.getDepartment());
            }
            if (keywords.getGrade() != null) {
                content.append(", grade=").append(keywords.getGrade());
            }
            if (keywords.getFilter() != null) {
                content.append(", filter=").append(keywords.getFilter());
            }
            if (keywords.getReportType() != null) {
                content.append(", reportType=").append(keywords.getReportType());
            }
            if (keywords.getRawKeywords() != null
                    && !keywords.getRawKeywords().isEmpty()) {
                content.append(", keywords=")
                        .append(String.join(", ", keywords.getRawKeywords()));
            }
            content.append(", primaryIntent=").append(keywords.getPrimaryIntent());
            content.append(", showDetails=").append(keywords.isShowDetails());
            if (keywords.getPlan() != null && !keywords.getPlan().isEmpty()) {
                content.append(", plan=").append(keywords.getPlan());
            }
            content.append(". Use these to guide your tool selection.]");
        }
        content.append(" /nothink");
        userMsg.put("content", content.toString());
        messages.add(userMsg);
        return messages;
    }

    // ── Backward compat ───────────────────────────────────────────────
    private List<Map<String, Object>> buildInitialMessages(String userPrompt) {
        return buildInitialMessages(userPrompt, null);
    }

    // ══════════════════════════════════════════════════════════════
    // SERIALIZE REQUEST
    // ══════════════════════════════════════════════════════════════
    private String buildRequest(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) throws IOException {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model", ollamaModel);
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }
        body.put("stream", false);
        body.put("temperature", AppConfig.ollamaTemperature());
        body.put("num_ctx", AppConfig.ollamaNumCtx());
        // ── Disable chain-of-thought thinking (Qwen3 / Ollama) ──────
        body.put("think", false);   // Ollama API flag
        return mapper.writeValueAsString(body);
    }

    // ══════════════════════════════════════════════════════════════
    // RESULT WRAPPER CLASS
    // ══════════════════════════════════════════════════════════════
    public static class AgentResult {

        private String prompt;
        private String answer;
        private final List<ToolCallRecord> toolCalls = new ArrayList<ToolCallRecord>();

        public void addToolCall(
                String name, Map<String, Object> args, String result) {
            toolCalls.add(new ToolCallRecord(name, args, result));
        }

        public String getPrompt() {
            return prompt;
        }

        public void setPrompt(String p) {
            this.prompt = p;
        }

        public String getAnswer() {
            return answer;
        }

        public void setAnswer(String a) {
            this.answer = a;
        }

        public List<ToolCallRecord> getToolCalls() {
            return toolCalls;
        }

        public java.util.List<String> getToolNames() {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (ToolCallRecord call : toolCalls) {
                names.add(call.getToolName());
            }
            return names;
        }

        public String getToolSummary() {
            if (toolCalls.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (ToolCallRecord call : toolCalls) {
                sb.append("Tool: ").append(call.getToolName())
                        .append("\nOutput: ").append(call.getOutput())
                        .append("\n---\n");
            }
            return sb.toString();
        }

        public static class ToolCallRecord {

            private final String name;
            private final Map<String, Object> args;
            private final String result;

            public ToolCallRecord(String name,
                    Map<String, Object> args,
                    String result) {
                this.name = name;
                this.args = args;
                this.result = result;
            }

            public String getToolName() {
                return name;
            }

            public Map<String, Object> getArgs() {
                return args;
            }

            public String getOutput() {
                return result;
            }
        }

        /**
         * Returns the output of a previously recorded tool call with the same
         * tool name and equivalent arguments (case-insensitive, trimmed String
         * values, key order ignored), or null if no such call has been made yet
         * in this request. Used to skip re-executing a tool the pipeline
         * already called earlier in the SAME invoke() — whether that earlier
         * call came from the fast path (executePlan), the agent loop
         * (runAgentLoop), or anywhere else that shares this AgentResult.
         */
        public String findEquivalentCallResult(String toolName, Map<String, Object> args) {
            String targetKey = normalizeArgsKey(args);
            for (ToolCallRecord record : toolCalls) {
                if (record.getToolName().equals(toolName)
                        && normalizeArgsKey(record.getArgs()).equals(targetKey)) {
                    return record.getOutput();
                }
            }
            return null;
        }

        /**
         * Builds a normalized, order-independent, case-insensitive string key
         * from an args map, WITHOUT requiring Jackson (AgentResult is a static
         * nested class with no direct access to the outer OllamaService's
         * ObjectMapper instance). Sorted by key so {"a":1,"b":2} and
         * {"b":2,"a":1} produce the same key; String values are trimmed and
         * upper-cased so "CENTRAL" and "Central" also match.
         */
        private static String normalizeArgsKey(Map<String, Object> args) {
            if (args == null || args.isEmpty()) {
                return "{}";
            }
            java.util.TreeMap<String, String> sorted = new java.util.TreeMap<String, String>();
            for (Map.Entry<String, Object> e : args.entrySet()) {
                Object v = e.getValue();
                String vs = (v instanceof String)
                        ? ((String) v).trim().toUpperCase()
                        : String.valueOf(v);
                sorted.put(e.getKey(), vs);
            }
            return sorted.toString(); // TreeMap.toString() is deterministic given sorted keys
        }
    }

    // ── Render PSM list (collapsible) ───────────────────────────────
    private String formatPsmList(JsonNode node) {
        int totalPsms = node.path("totalPsms").asInt(0);
        int totalLocations = node.path("totalLocations").asInt(0);
        JsonNode psms = node.path("psms");
        StringBuilder html = new StringBuilder();
        html.append("<h3 class='data-title'> Property Service Managers (PSM)</h3>");
        html.append("<p class='answer-summary'>")
                .append("<strong>").append(totalPsms).append("</strong> distinct PSMs ")
                .append("managing <strong>").append(totalLocations)
                .append("</strong> locations total.</p>");
        if (psms.size() == 0) {
            html.append("<p>No PSM data found.</p>");
            return html.toString();
        }
        // ── Collapsible PSM table ────────────────────────────────────
        html.append("<details class='psm-locations-details'>");
        html.append("<summary class='psm-locations-summary'>")
                .append("<i class='fa-solid fa-clipboard'></i> Show ").append(totalPsms).append(" PSM(s)")
                .append(" <span class='psm-hint'>(click to expand)</span>")
                .append("</summary>");
        html.append("<div class='psm-locations-body'>");
        html.append("<table class='data-table'>");
        html.append("<tr><th>#</th><th>PSM</th><th>Locations</th></tr>");
        int idx = 1;
        for (JsonNode p : psms) {
            String psm = p.path("psm").asText("").trim();
            int count = p.path("count").asInt(0);
            html.append("<tr>")
                    .append("<td>").append(idx++).append("</td>")
                    .append("<td><strong>").append(escapeHtml(psm)).append("</strong></td>")
                    .append("<td><strong>").append(count).append("</strong></td>")
                    .append("</tr>");
        }
        html.append("</table>");
        html.append("</div>");
        html.append("</details>");
        html.append("<p class='data-footer'>")
                .append("<i class='fa-solid fa-lightbulb'></i> Tip: Ask <em>\"show locations under PSM/SHA TIN EAST\"</em> ")
                .append("to see locations for a specific PSM.</p>");
        return html.toString();
    }

    // ── Render locations under a specific PSM ───────────────────────
    private String formatLocationsByPsm(JsonNode node) {
        String psm = node.path("psm").asText("").trim();
        int count = node.path("count").asInt(0);
        JsonNode results = node.path("results");
        StringBuilder html = new StringBuilder();
        html.append("<h3 class='data-title'><i class='fa-solid fa-map-pin'></i> Locations under ")
                .append(escapeHtml(psm))
                .append("</h3>");
        int showing = results.size();
        html.append("Found <strong>").append(count).append("</strong> location(s)")
                .append(showing < count ? " (showing first " + showing + ")" : "")
                .append(".");
        if (count == 0) {
            html.append("<p>No locations found under this PSM.</p>");
            return html.toString();
        }
        // ── Collapsible: count visible by default, list hidden ──────
        html.append("<details class='psm-locations-details'>");
        html.append("<summary class='psm-locations-summary'>")
                .append("Show ").append(count).append(" location(s)")
                .append(" <span class='psm-hint'>(click to expand)</span>")
                .append("</summary>");
        html.append("<div class='psm-locations-body'>");
        html.append("<table class='data-table'>");
        html.append("<tr><th>Code</th><th>Name</th><th>Address</th></tr>");
        for (JsonNode item : results) {
            String code = item.path("LOC_CD").asText("");
            String name = item.path("LOC_NAME").asText("").trim();
            String address = item.path("ADDRESS").asText("").trim();
            html.append("<tr>")
                    .append("<td><code>").append(escapeHtml(code)).append("</code></td>")
                    .append("<td><strong>").append(escapeHtml(name)).append("</strong></td>")
                    .append("<td>").append(escapeHtml(address)).append("</td>")
                    .append("</tr>");
        }
        html.append("</table>");
        html.append("</div>");
        html.append("</details>");
        return html.toString();
    }

    // ── Format locations by department ──────────────────────────────
    private String formatLocationsByDept(JsonNode node) {
        String deptCd = node.path("deptCd").asText("").trim();
        int count = node.path("count").asInt(0);
        JsonNode results = node.path("results");
        StringBuilder html = new StringBuilder();
        html.append("<h3 class='data-title'>Locations for Department: ")
                .append(escapeHtml(deptCd)).append("</h3>");
        int showing = results.size();
        html.append("Found <strong>").append(count).append("</strong> location(s)")
                .append(showing < count ? " (showing first " + showing + ")" : "")
                .append(".");
        if (count == 0) {
            html.append("<p>No locations found for this department.</p>");
            return html.toString();
        }
        html.append("<details class='psm-locations-details' open>");
        html.append("<summary class='psm-locations-summary'>")
                .append("Show ").append(count).append(" location(s)")
                .append(" <span class='psm-hint'>(click to collapse)</span>")
                .append("</summary>");
        html.append("<div class='psm-locations-body'>");
        html.append("<table class='data-table'>");
        html.append("<tr><th>Code</th><th>Name</th><th>Address</th></tr>");
        for (JsonNode item : results) {
            html.append("<tr>")
                    .append("<td><code>")
                    .append(escapeHtml(item.path("LOC_CD").asText("")))
                    .append("</code></td>")
                    .append("<td><strong>")
                    .append(escapeHtml(item.path("LOC_NAME").asText("").trim()))
                    .append("</strong></td>")
                    .append("<td>")
                    .append(escapeHtml(item.path("ADDRESS").asText("").trim()))
                    .append("</td>")
                    .append("</tr>");
        }
        html.append("</table>");
        html.append("</div>");
        html.append("</details>");
        return html.toString();
    }

    // ── Format declared monuments ───────────────────────────────────
    private String formatDeclaredMonuments(JsonNode node) {
        int count = node.path("count").asInt(0);
        boolean enriched = node.path("enriched").asBoolean(false);
        JsonNode results = node.path("results");
        StringBuilder html = new StringBuilder();
        html.append("<h3 class='data-title'>Declared Monuments</h3>");
        int showing = results.size();
        html.append("Found <strong>").append(count).append("</strong> location(s)")
                .append(showing < count ? " (showing first " + showing + ")" : "")
                .append(".");
        if (count == 0) {
            html.append("<p>No matching locations found.</p>");
            return html.toString();
        }
        html.append("<table class='data-table'>");
        // ── Header ────────────────────────────────────────────────────────
        html.append("<tr>")
                .append("<th>Code</th>")
                .append("<th>Name</th>")
                .append("<th>Address</th>")
                .append("<th>Monument</th>");
        if (enriched) {
            html.append("<th>Historic Grade</th>");
        }
        html.append("</tr>");
        // ── Rows ──────────────────────────────────────────────────────────
        int shown = 0;
        for (JsonNode item : results) {
            if (shown >= 50) {
                break;
            }
            String code = item.path("LOC_CD").asText("");
            String name = item.path("LOC_NAME").asText("").trim();
            String address = item.path("ADDRESS").asText("").trim();
            String monFlag = item.path("DECLR_MONUMT").asText("");
            String grade = item.path("GRD_HIST_BLDG").asText("N/A");
            String monDisplay = "T".equals(monFlag) ? "Yes"
                    : "F".equals(monFlag) ? "[Error] No" : monFlag;
            String gradeDisplay;
            if ("N/A".equals(grade) || grade.isEmpty()) {
                gradeDisplay = "<span style='color:#999;'>—</span>";
            } else {
                gradeDisplay = "<span class='report-type-badge'>Grade "
                        + escapeHtml(grade) + "</span>";
            }
            html.append("<tr>")
                    .append("<td><code>").append(escapeHtml(code)).append("</code></td>")
                    .append("<td><strong>").append(escapeHtml(name)).append("</strong></td>")
                    .append("<td>").append(escapeHtml(address)).append("</td>")
                    .append("<td>").append(monDisplay).append("</td>");
            if (enriched) {
                html.append("<td>").append(gradeDisplay).append("</td>");
            }
            html.append("</tr>");
            shown++;
        }
        html.append("</table>");
        if (count > 50) {
            html.append("<p class='data-footer'>")
                    .append("Showing 50 of ").append(count).append(" results.")
                    .append("</p>");
        }
        return html.toString();
    }

    // ── Format historic buildings ───────────────────────────────────
    private String formatHistoricBuildings(JsonNode node) {
        String grade = node.path("grade").asText("ALL").trim();
        int count = node.path("count").asInt(0);
        JsonNode results = node.path("results");
        StringBuilder html = new StringBuilder();
        String gradeLabel = "ALL".equals(grade) ? "All Graded Historic Buildings"
                : "0".equals(grade) || "NONE".equals(grade)
                ? "Non-Graded Buildings"
                : "Grade " + grade + " Historic Buildings";
        html.append("<h3 class='data-title'>").append(escapeHtml(gradeLabel))
                .append("</h3>");
        int showing = results.size();
        html.append("Found <strong>").append(count).append("</strong> location(s)")
                .append(showing < count ? " (showing first " + showing + ")" : "")
                .append(".");
        if (count == 0) {
            html.append("<p>No matching locations found.</p>");
            return html.toString();
        }
        html.append("<details class='psm-locations-details' open>");
        html.append("<summary class='psm-locations-summary'>")
                .append("Show ").append(count).append(" location(s)")
                .append("</summary>");
        html.append("<div class='psm-locations-body'>");
        html.append("<table class='data-table'>");
        html.append("<tr><th>Code</th><th>Name</th><th>Address</th>")
                .append("<th>Grade</th></tr>");
        for (JsonNode item : results) {
            String g = item.path("GRD_HIST_BLDG").asText("").trim();
            String gradeBadge = g.isEmpty() || "0".equals(g)
                    ? "—" : "Grade " + g;
            html.append("<tr>")
                    .append("<td><code>")
                    .append(escapeHtml(item.path("LOC_CD").asText("")))
                    .append("</code></td>")
                    .append("<td><strong>")
                    .append(escapeHtml(item.path("LOC_NAME").asText("").trim()))
                    .append("</strong></td>")
                    .append("<td>")
                    .append(escapeHtml(item.path("ADDRESS").asText("").trim()))
                    .append("</td>")
                    .append("<td><strong>").append(escapeHtml(gradeBadge))
                    .append("</strong></td>")
                    .append("</tr>");
        }
        html.append("</table>");
        html.append("</div>");
        html.append("</details>");
        return html.toString();
    }

    // ── Format location code change history ─────────────────────────
    private String formatLocCdHistory(JsonNode node) {
        int count = node.path("count").asInt(0);
        JsonNode results = node.path("results");
        StringBuilder html = new StringBuilder();
        html.append("<h3 class='data-title'>Location Code Change History</h3>");
        // Show what was searched
        if (node.has("formerLocCd") && node.has("currentLocCd")
                && node.path("formerLocCd").asText("").equals(node.path("currentLocCd").asText(""))) {
            html.append("<p class='answer-summary'>")
                    .append("Searching code history for: <code>")
                    .append(escapeHtml(node.path("formerLocCd").asText("")))
                    .append("</code></p>");
        } else {
            if (node.has("formerLocCd")) {
                html.append("<p class='answer-summary'>")
                        .append("Searching for former code: <code>")
                        .append(escapeHtml(node.path("formerLocCd").asText("")))
                        .append("</code></p>");
            }
            if (node.has("currentLocCd")) {
                html.append("<p class='answer-summary'>")
                        .append("Searching for current code: <code>")
                        .append(escapeHtml(node.path("currentLocCd").asText("")))
                        .append("</code></p>");
            }
        }
        html.append("<p class='answer-summary'>")
                .append("Found <strong>").append(count)
                .append("</strong> change record(s).</p>");
        if (count == 0) {
            html.append("<p>No code change history found for this location.</p>");
            return html.toString();
        }
        html.append("<table class='data-table'>");
        html.append("<tr>")
                .append("<th>Former Code</th>")
                .append("<th>Former Name</th>")
                .append("<th>→</th>")
                .append("<th>Current Code</th>")
                .append("<th>Current Name</th>")
                .append("</tr>");
        for (JsonNode item : results) {
            html.append("<tr>")
                    .append("<td><code>")
                    .append(escapeHtml(item.path("FORMER_LOC_CD").asText("")))
                    .append("</code></td>")
                    .append("<td>")
                    .append(escapeHtml(item.path("FORMER_LOC_NAME").asText("").trim()))
                    .append("</td>")
                    .append("<td style='text-align:center; font-size:1.2em;'>→</td>")
                    .append("<td><code>")
                    .append(escapeHtml(item.path("CURRENT_LOC_CD").asText("")))
                    .append("</code></td>")
                    .append("<td><strong>")
                    .append(escapeHtml(item.path("CURRENT_LOC_NAME").asText("").trim()))
                    .append("</strong></td>")
                    .append("</tr>");
        }
        html.append("</table>");
        html.append("<p class='data-footer'>")
                .append("<i class='fa-solid fa-lightbulb'></i> Tip: Use the current code to look up full location details.")
                .append("</p>");
        return html.toString();
    }

    // ══════════════════════════════════════════════════════════════
    // NATURAL LANGUAGE SUMMARY (ProjectReview P2 — Issue #5)
    // ══════════════════════════════════════════════════════════════
    /**
     * Generate a one-sentence natural language summary of the result. Uses
     * template-based approach (zero latency).
     */
    private String generateNaturalLanguageSummary(String htmlResult, String userPrompt) {
        String templateSummary = buildTemplateSummary(htmlResult);
        if (templateSummary != null && !templateSummary.isEmpty()) {
            return "<p class='answer-summary'><i class='fa-solid fa-comment-dots'></i> "
                    + escapeHtml(templateSummary) + "</p>";
        }
        return "";
    }

    /**
     * Deterministic template-based summary — no LLM call, zero latency.
     */
    private String buildTemplateSummary(String html) {
        if (html == null || html.isEmpty()) {
            return null;
        }
        String text = html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();

        java.util.regex.Matcher countMatcher = java.util.regex.Pattern
                .compile("Found (\\d+) location\\(s\\)")
                .matcher(text);
        if (countMatcher.find()) {
            int count = Integer.parseInt(countMatcher.group(1));
            if (count == 0) {
                return "No matching locations were found for your query.";
            }
            if (count == 1) {
                return "Here are the details for the matching location:";
            }
            return "I found " + count + " matching locations for your query:";
        }

        java.util.regex.Matcher reportMatcher = java.util.regex.Pattern
                .compile("(\\d+) of (\\d+) locations have")
                .matcher(text);
        if (reportMatcher.find()) {
            return reportMatcher.group(1) + " out of " + reportMatcher.group(2)
                    + " locations have the requested report.";
        }

        if (text.contains("Property Service Managers")) {
            java.util.regex.Matcher psmMatcher = java.util.regex.Pattern
                    .compile("(\\d+) distinct PSMs")
                    .matcher(text);
            if (psmMatcher.find()) {
                return "There are " + psmMatcher.group(1) + " Property Service Managers on record.";
            }
        }

        if (text.contains("Location Code Change History")) {
            java.util.regex.Matcher histMatcher = java.util.regex.Pattern
                    .compile("Found (\\d+) change record")
                    .matcher(text);
            if (histMatcher.find()) {
                return "I found " + histMatcher.group(1) + " code change record(s) in the history.";
            }
        }

        if (text.contains("Declared Monument")) {
            return "Here are the declared monuments matching your query:";
        }

        if (text.contains("Historic Building") || text.contains("Historic Grade")) {
            return "Here are the historic buildings matching your query:";
        }

        return null;
    }

    // ══════════════════════════════════════════════════════════════
    // BUILD REASONING CHAIN BANNER (multi-step)
    // Shows a collapsible timeline of what the agent did
    // ══════════════════════════════════════════════════════════════
    private String buildReasoningBanner(List<String> steps) {
        StringBuilder html = new StringBuilder();
        html.append("<details class='agent-reasoning-chain' open>");
        html.append("<summary class='reasoning-summary'>")
                .append("<i class='fa-solid fa-robot'></i> Agent used <strong>")
                .append(steps.size())
                .append(" steps</strong> to answer your question")
                .append("</summary>");
        html.append("<div class='reasoning-steps'>");
        for (int i = 0; i < steps.size(); i++) {
            html.append("<div class='reasoning-step'>")
                    // Step number bubble
                    .append("<div class='step-number'>").append(i + 1).append("</div>")
                    // Step description
                    .append("<div class='step-text'>")
                    .append(escapeHtml(steps.get(i)))
                    .append("</div>")
                    // Connector line (not on last step)
                    .append(i < steps.size() - 1
                            ? "<div class='step-connector'>↓</div>" : "")
                    .append("</div>");
        }
        html.append("</div>"); // reasoning-steps
        html.append("</details>");
        return html.toString();
    }
    // ══════════════════════════════════════════════════════════════
    // HELPER: Strip <think>...</think> blocks from LLM output
    // Qwen3 uses chain-of-thought reasoning inside these tags.
    // We strip them before displaying or feeding back to LLM
    // to reduce token count on subsequent iterations.
    // ══════════════════════════════════════════════════════════════
    private static final Pattern THINK_TAG_PATTERN
            = Pattern.compile("(?s)<think>.*?</think>");

    // ── Strip thinking tags — also strip /nothink echoes from output ──
    private String stripThinkingTags(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        // Remove <think>...</think> blocks (Qwen3 chain-of-thought)
        String stripped = THINK_TAG_PATTERN.matcher(content).replaceAll("").trim();
        // Remove any /nothink directives the LLM echoed back
        stripped = stripped.replaceAll("(?i)/nothink", "").trim();
        // Remove <|im_thinking|>...</|im_thinking|> (alternative format)
        stripped = stripped.replaceAll("(?s)<\\|im_thinking\\|>.*?</\\|im_thinking\\|>", "").trim();
        return stripped;
    }

    // ── Build a minimal tool list for the agent loop ─────────────────
    private List<Map<String, Object>> getRelevantTools(String userPrompt, ExtractedKeywords keywords) {
        return mcpClient.listTools();
    }

    private List<Map<String, Object>> getRelevantTools(String userPrompt) {
        return getRelevantTools(userPrompt, null);
    }

    // ══════════════════════════════════════════════════════════════
    // PLAN EXECUTOR
    // Runs all steps in order. Steps can depend on previous results
    // via codesSource markers.
    // ══════════════════════════════════════════════════════════════
    private String executePlan(Plan plan, AgentResult result, String sessionId) throws IOException {
        if (plan == null || plan.steps == null || plan.steps.isEmpty()) {
            return "<p>No executable plan was produced.</p>";
        }

        StringBuilder html = new StringBuilder();
        List<String> lastCodes = new ArrayList<>();
        String lastToolResult = null;
        int answerStepIndex = findAnswerStepIndex(plan);

        if (answerStepIndex < 0) {
            for (int i = plan.steps.size() - 1; i >= 0; i--) {
                Intent intent = plan.steps.get(i);
                if (resolveToolName(intent) != null) {
                    answerStepIndex = i;
                    break;
                }
            }
        }

        if (answerStepIndex >= 0) {
            log.info("[Manual Info] Answer step: {} {}", answerStepIndex, plan.steps.get(answerStepIndex).type);
        }

        for (int i = 0; i < plan.steps.size(); i++) {
            Intent intent = plan.steps.get(i);
            if (intent == null) {
                continue;
            }

            log.info("[Manual Info] Executing plan step [{}]: {}", i, intent);

            /*
	         * Avoid rendering the same full detail card twice when a modifier
	         * already fetched the detail result.
             */
            if (isRedundantDetailStep(intent, lastToolResult, lastCodes)) {
                log.info("[Manual Info] Skipping redundant detail step [{}]", i);
                continue;
            }

            String relation = getRelation(intent);

            if (i == 0 && ("filter_previous".equals(relation) || "enrich_previous".equals(relation))) {
                log.warn("Invalid relation '{}' on first plan step; skipping step {}", relation, i);
                html.append("<p class='answer-summary'>The query plan contained an invalid first-step relation.</p>");
                continue;
            }

            Map<String, Object> args = buildArgs(intent, lastToolResult, lastCodes, sessionId);

            if (args == null) {
                log.warn("[Manual Info] Skipping step [{}] because required arguments are unavailable", i);
                html.append("<p class='answer-summary'>This step could not be executed because required input data was unavailable.</p>");
                continue;
            }

            String toolName = resolveToolName(intent);

            if (toolName == null || toolName.trim().isEmpty()) {
                String orphanModifier = intent.params == null ? null : intent.params.get("modifier");
                if (orphanModifier != null && !orphanModifier.trim().isEmpty() && answerStepIndex >= 0) {
                    Intent answerIntent = plan.steps.get(answerStepIndex);
                    if (answerIntent.params != null && answerIntent.params.get("modifier") == null) {
                        answerIntent.params.put("modifier", orphanModifier);
                    }
                }
                log.warn("[Manual Info] No tool mapped for intent {}", intent.type);
                continue;
            }

            log.info("[Manual Info] Tool: {} args: {}", toolName, args);

            String previousToolResult = lastToolResult;
            String cachedResult = result.findEquivalentCallResult(toolName, args);
            String toolResult;

            if (cachedResult != null) {
                log.info("[Manual Info] Skipping duplicate tool call [{} {}]", toolName, args);
                toolResult = cachedResult;
            } else {
                toolResult = mcpClient.callTool(toolName, args);
                result.addToolCall(toolName, args, toolResult);
            }

            String finalResult = toolResult;

            if ("filter_previous".equals(relation) && previousToolResult != null) {
                finalResult = crossFilter(previousToolResult, finalResult);
            } else if ("enrich_previous".equals(relation) && previousToolResult != null) {
                finalResult = enrichWithPrevious(previousToolResult, finalResult);
            }

            if (i == answerStepIndex) {
                finalResult = applyModifier(intent, finalResult, result);
            }

            lastToolResult = finalResult;
            lastCodes = extractCodesFromResult(finalResult);

            lastCodes = extractCodesFromResult(finalResult);

            if (isFilteringRelation(relation) && lastCodes.isEmpty()) {
                log.info("Plan intersection is empty after step {}; stopping", i);
                html.setLength(0);
                html.append(formatAsHtml(finalResult));
                break;
            }

            if (!lastCodes.isEmpty() && sessionId != null && !sessionId.trim().isEmpty()) {
                LAST_RESULTS.put(sessionId, new ArrayList<>(lastCodes));
                log.info("[Manual Info] Saved {} location codes to session {}", lastCodes.size(), sessionId);
            }

            boolean isEnrichStep = "enrich_previous".equals(relation);

            if (isEnrichStep) {
                html.setLength(0);
            }

            html.append(formatAsHtml(finalResult));
        }

        /*
	     * Optional generic intersection summary.
         */
        int chainedFilters = 0;
        StringBuilder filterDescriptions = new StringBuilder();

        for (Intent step : plan.steps) {
            if (step == null || step.params == null) {
                continue;
            }

            if ("filter_previous".equals(getRelation(step))) {
                chainedFilters++;
                if (filterDescriptions.length() > 0) {
                    filterDescriptions.append(" ∩ ");
                }
                filterDescriptions.append(describeStep(step));
            }
        }

        if (chainedFilters >= 2 && !lastCodes.isEmpty()) {
            html.append("<div style='margin-top:16px;border:2px solid #27ae60;border-radius:8px;padding:16px;'>");
            html.append("<h3 style='color:#27ae60;'>Matched locations</h3>");
            html.append("<p class='answer-summary'><strong>" + lastCodes.size() + "</strong> location(s) matched all filters: <strong>" + escapeHtml(filterDescriptions.toString()) + "</strong></p>");
            html.append("<table class='data-table'><tr><th>#</th><th>Code</th></tr>");

            int index = 1;
            for (String code : lastCodes) {
                html.append("<tr><td>" + (index++) + "</td><td><code>" + escapeHtml(code) + "</code></td></tr>");
            }
            html.append("</table></div>");
        }

        return html.length() > 0 ? html.toString() : "<p>No results found.</p>";
    }

    // ── Build a simple single-key args map ───────────────────────────
    private Map<String, Object> map(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put(k, v);
        return m;
    }

    // ── Extract first LOC_CD from a tool result ───────────────────────
    private String extractFirstCode(String toolResult) {
        try {
            JsonNode node = mapper.readTree(toolResult);
            JsonNode results = node.path("results");
            if (results.isArray() && results.size() > 0) {
                return results.get(0).path("LOC_CD").asText(null);
            }
        } catch (Exception e) {
            log.error("extractFirstCode error: {}", e.getMessage());
        }
        return null;
    }

    // ── Extract first LOC_CD from an LLM-generated SQL result ─────────
    private String extractFirstLocCdFromSqlResult(String sqlResult) {
        try {
            JsonNode node = mapper.readTree(sqlResult);
            JsonNode results = node.path("results");
            if (results.isArray() && results.size() > 0) {
                return results.get(0).path("LOC_CD").asText(null);
            }
        } catch (Exception e) {
            log.error("extractFirstLocCdFromSqlResult error: {}", e.getMessage());
        }
        return null;
    }

    // ── Format SQL result as HTML; if user wants details, fetch full info ─
    private String formatSqlResultWithDetails(String sqlResult, ExtractedKeywords keywords,
            AgentResult result) throws IOException {
        String firstCode = extractFirstLocCdFromSqlResult(sqlResult);
        int resultCount = readSqlResultCount(sqlResult);

        boolean singleSelection = resultCount == 1 || hasSingleSelectionModifier(keywords);

        boolean wantsDetails = keywords != null && keywords.isShowDetails()
                && singleSelection && firstCode != null && !firstCode.trim().isEmpty();

        if (wantsDetails) {
            String detailTool = resolveDetailToolName(firstCode);
            if (detailTool != null) {
                Map<String, Object> detailArgs = map("locCd", firstCode.trim().toUpperCase(Locale.ROOT));
                String detailResult = mcpClient.callTool(detailTool, detailArgs);
                result.addToolCall(detailTool, detailArgs, detailResult);
                return "<p class='answer-summary'>Answered using a generated database query and full details:</p>"
                        + formatAsHtml(detailResult);
            }
        }

        return "<p class='answer-summary'>Answered using a generated database query:</p>"
                + formatAsHtml(sqlResult);
    }

    private int readSqlResultCount(String sqlResult) {
        try {
            JsonNode node = mapper.readTree(sqlResult);
            return node.path("count").asInt(node.path("results").size());
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean hasSingleSelectionModifier(ExtractedKeywords keywords) {
        if (keywords == null || keywords.getModifier() == null) {
            return false;
        }
        String modifier = keywords.getModifier().trim().toUpperCase(Locale.ROOT);
        return "FIRST".equals(modifier) || "OLDEST".equals(modifier)
                || "NEWEST".equals(modifier) || "LATEST".equals(modifier);
    }

    // ── Extract all LOC_CD values from a tool result ─────────────────
    private List<String> extractCodesFromResult(String toolResult) {
        if (toolResult == null || toolResult.trim().isEmpty()) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        try {
            JsonNode node = mapper.readTree(toolResult);
            JsonNode results = node.path("results");
            collectLocationCodes(results.isArray() ? results : node, codes);
        } catch (Exception e) {
            log.warn("Failed to extract location codes: {}", e.getMessage());
        }
        return new ArrayList<>(codes);
    }

    private void collectLocationCodes(JsonNode node, Set<String> codes) {
        if (node == null || node.isNull()) {
            return;
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                collectLocationCodes(child, codes);
            }
            return;
        }

        if (!node.isObject()) {
            return;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String fieldName = entry.getKey();
            JsonNode value = entry.getValue();

            boolean isLocationCodeField = "LOC_CD".equalsIgnoreCase(fieldName) || "CURRENT_LOC_CD".equalsIgnoreCase(fieldName);
            if (isLocationCodeField && value != null && value.isValueNode()) {
                String code = value.asText("").trim().toUpperCase();
                if (!code.isEmpty()) {
                    codes.add(code);
                }
            }
            collectLocationCodes(value, codes);
        }
    }

    // ── Cross-filter: keep only results that appear in reference set ──
    private String crossFilter(String firstResult, String secondResult) {
        if (firstResult == null || secondResult == null) {
            return secondResult;
        }

        try {
            JsonNode firstNode = mapper.readTree(firstResult);
            JsonNode secondNode = mapper.readTree(secondResult);
            JsonNode firstRows = firstNode.path("results");
            JsonNode secondRows = secondNode.path("results");

            Set<String> firstCodes = new LinkedHashSet<>(extractCodesFromResult(firstResult));
            Set<String> secondCodes = new LinkedHashSet<>(extractCodesFromResult(secondResult));

            if (firstCodes.isEmpty() || secondCodes.isEmpty()) {
                Map<String, Object> emptyResponse = new LinkedHashMap<>();
                emptyResponse.put("count", 0);
                emptyResponse.put("results", new ArrayList<JsonNode>());
                copyResultMetadata(secondNode, emptyResponse);
                return mapper.writeValueAsString(emptyResponse);
            }
            /*
	         * If the second tool has rows, preserve the second tool's
	         * attributes and filter those rows using the first result.
             */
            boolean preserveSecondRows = secondRows.isArray();
            JsonNode sourceRows = preserveSecondRows ? secondRows : firstRows;
            Set<String> allowedCodes = preserveSecondRows ? firstCodes : secondCodes;

            if (!sourceRows.isArray()) {
                Map<String, Object> emptyResponse = new LinkedHashMap<>();
                emptyResponse.put("count", 0);
                emptyResponse.put("results", new ArrayList<JsonNode>());
                copyResultMetadata(secondNode, emptyResponse);
                return mapper.writeValueAsString(emptyResponse);
            }

            List<JsonNode> filteredRows = new ArrayList<>();
            for (JsonNode row : sourceRows) {
                String rowCode = getRowLocationCode(row);
                if (rowCode != null && allowedCodes.contains(rowCode.toUpperCase())) {
                    filteredRows.add(row);
                }
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("count", filteredRows.size());
            response.put("results", filteredRows);

            copyResultMetadata(firstNode, response);
            copyResultMetadata(secondNode, response);

            return mapper.writeValueAsString(response);

        } catch (Exception e) {
            log.error("crossFilter error: {}", e.getMessage());
            return emptyFilteredResult(secondResult);
        }
    }

    private String getRowLocationCode(JsonNode row) {
        if (row == null || !row.isObject()) {
            return null;
        }

        String code = row.path("LOC_CD").asText("").trim();
        if (code.isEmpty()) {
            code = row.path("CURRENT_LOC_CD").asText("").trim();
        }
        return code.isEmpty() ? null : code;
    }

    private void copyResultMetadata(JsonNode source, Map<String, Object> target) {
        if (source == null || !source.isObject()) {
            return;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();

            if ("count".equals(key) || "results".equals(key)) {
                continue;
            }
            target.put(key, entry.getValue());
        }
    }
    // ══════════════════════════════════════════════════════════════
    // AGENT LOOP  (unchanged — callOllama signature still matches)
    // ══════════════════════════════════════════════════════════════

    private AgentResult runAgentLoop(String userPrompt, ExtractedKeywords keywords, AgentResult result, String sessionId) throws IOException {
        userPrompt = sanitizeUserPrompt(userPrompt);
        List<Map<String, Object>> messages = buildInitialMessages(userPrompt, keywords);
        List<Map<String, Object>> tools = getRelevantTools(userPrompt, keywords);
        StringBuilder htmlOutput = new StringBuilder();
        int maxIterations = AGENT_LOOP_MAX_ITERATIONS;

        if (keywords != null && keywords.hasIntent("UNKNOWN")) {
            maxIterations = 2;
            log.info("[Manual Info] UNKNOWN intent; limiting loop to {} iterations", maxIterations);
        }

        long loopStartTime = System.currentTimeMillis();

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            long elapsed = System.currentTimeMillis() - loopStartTime;

            if (elapsed > AGENT_LOOP_TIMEOUT_MS) {
                log.warn("[Security] Agent loop timeout after {} ms", elapsed);
                if (htmlOutput.length() == 0) {
                    htmlOutput.append("<p>The query took too long. Please try a simpler question.</p>");
                }
                break;
            }

            log.info("[Manual Info] Agent loop iteration {}", iteration + 1);
            JsonNode llmResponse = callOllama(messages, tools, SYSTEM_PROMPT);
            JsonNode toolCalls = llmResponse.path("tool_calls");
            String content = stripThinkingTags(llmResponse.path("content").asText("").trim());

            if (toolCalls.isMissingNode() || toolCalls.isEmpty()) {
                log.info("[Manual Info] Agent loop completed after {} iteration(s)", iteration + 1);
                break;
            }

            Map<String, Object> assistantMessage = new LinkedHashMap<>();
            assistantMessage.put("role", "assistant");

            if (!content.isEmpty()) {
                assistantMessage.put("content", content);
            }

            assistantMessage.put("tool_calls", mapper.convertValue(toolCalls, mapper.getTypeFactory().constructCollectionType(List.class, Object.class)));
            messages.add(assistantMessage);

            for (JsonNode toolCall : toolCalls) {
                String toolName = toolCall.path("function").path("name").asText("");

                if (toolName.trim().isEmpty()) {
                    log.warn("[Manual Info] LLM returned an empty tool name");
                    continue;
                }

                JsonNode argumentsNode = toolCall.path("function").path("arguments");
                Map<String, Object> args;

                if (argumentsNode.isTextual()) {
                    String argumentsText = argumentsNode.asText("");
                    if (argumentsText.trim().isEmpty()) {
                        args = new LinkedHashMap<>();
                    } else {
                        args = mapper.readValue(argumentsText, mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
                    }
                } else if (argumentsNode.isObject()) {
                    args = mapper.convertValue(argumentsNode, mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
                } else {
                    args = new LinkedHashMap<>();
                }

                if (args == null) {
                    args = new LinkedHashMap<>();
                }

                log.info("[Manual Info] LLM selected tool: {}", toolName);
                log.info("[Manual Info] LLM generated args: {}", args);

                String cachedResult = result.findEquivalentCallResult(toolName, args);
                String toolResult;

                if (cachedResult != null) {
                    log.info("[Manual Info] Skipping duplicate agent tool call [{} {}]", toolName, args);
                    toolResult = cachedResult;
                } else {
                    toolResult = mcpClient.callTool(toolName, args);
                    toolResult = postProcess(toolName, args, toolResult, userPrompt, sessionId, result);
                    result.addToolCall(toolName, args, toolResult);
                }

                htmlOutput.append(formatAsHtml(toolResult));

                Map<String, Object> toolMessage = new LinkedHashMap<>();
                toolMessage.put("role", "tool");
                String toolCallId = toolCall.path("id").asText("");

                if (!toolCallId.isEmpty()) {
                    toolMessage.put("tool_call_id", toolCallId);
                }
                toolMessage.put("content", truncateToolResult(toolResult));
                messages.add(toolMessage);
            }
        }

        if (htmlOutput.length() == 0) {
            htmlOutput.append("<p>No results found.</p>");
        }

        String agentHtml = htmlOutput.toString();
        String summary = generateNaturalLanguageSummary(agentHtml, userPrompt);
        result.setAnswer(summary + agentHtml);

        return result;
    }

    // ══════════════════════════════════════════════════════════════
    // HELPER: Truncate tool result before feeding back to LLM
    // The LLM only needs a SUMMARY of results, not all 50 rows.
    // This drastically reduces token count in subsequent iterations.
    // ══════════════════════════════════════════════════════════════
    private static final int MAX_TOOL_RESULT_CHARS = 800;

    private String truncateToolResult(String toolResult) {
        if (toolResult == null) {
            return "";
        }
        try {
            JsonNode node = mapper.readTree(toolResult);
            StringBuilder summary = new StringBuilder();
            // ── Handle results array ──────────────────────────────
            if (node.has("results") && node.path("results").isArray()) {
                int count = node.path("count").asInt(
                        node.path("results").size());
                int showing = Math.min(5, node.path("results").size());
                summary.append("count=").append(count);
                summary.append(", showing first ").append(showing).append(":\n");
                int i = 0;
                for (JsonNode item : node.path("results")) {
                    if (i >= 5) {
                        break;
                    }
                    String locCd = item.path("LOC_CD").asText("");
                    String locName = item.path("LOC_NAME").asText("").trim();
                    String address = item.path("ADDRESS").asText("").trim();
                    summary.append("- ").append(locCd);
                    if (!locName.isEmpty()) {
                        summary.append(" | ").append(locName);
                    }
                    if (!address.isEmpty()) {
                        summary.append(" | ").append(address);
                    }
                    summary.append("\n");
                    i++;
                }
                if (count > 5) {
                    summary.append("... and ").append(count - 5)
                            .append(" more results not shown.");
                }
                return summary.toString();
            }
            // ── Handle code history ───────────────────────────────
            if (node.has("formerLocCd") || node.has("currentLocCd")) {
                int count = node.path("count").asInt(0);
                summary.append("code_history count=").append(count).append("\n");
                for (JsonNode item : node.path("results")) {
                    summary.append("former=")
                            .append(item.path("FORMER_LOC_CD").asText())
                            .append(" → current=")
                            .append(item.path("CURRENT_LOC_CD").asText())
                            .append(" (")
                            .append(item.path("CURRENT_LOC_NAME").asText())
                            .append(")\n");
                }
                return summary.toString();
            }
            // ── Handle PSM list ───────────────────────────────────
            if (node.has("psms")) {
                summary.append("totalPsms=")
                        .append(node.path("totalPsms").asInt())
                        .append(", totalLocations=")
                        .append(node.path("totalLocations").asInt());
                return summary.toString();
            }
            // ── Handle report check ───────────────────────────────
            if (node.has("withReport")) {
                summary.append("withReport=")
                        .append(node.path("withReportCount").asInt())
                        .append("/")
                        .append(node.path("totalChecked").asInt());
                return summary.toString();
            }
            // ── Handle single location (general info) ─────────────
            if (node.has("general")) {
                JsonNode gen = node.path("general");
                summary.append("LOC_CD=").append(gen.path("LOC_CD").asText())
                        .append(", LOC_NAME=").append(gen.path("LOC_NAME").asText())
                        .append(", DEPT_CD=").append(gen.path("DEPT_CD").asText())
                        .append(", ADDRESS=").append(gen.path("ADDRESS").asText());
                return summary.toString();
            }
            // ── Fallback: truncate raw JSON ───────────────────────
            if (toolResult.length() > MAX_TOOL_RESULT_CHARS) {
                return toolResult.substring(0, MAX_TOOL_RESULT_CHARS)
                        + "...[truncated]";
            }
            return toolResult;
        } catch (Exception e) {
            if (toolResult.length() > MAX_TOOL_RESULT_CHARS) {
                return toolResult.substring(0, MAX_TOOL_RESULT_CHARS)
                        + "...[truncated]";
            }
            return toolResult;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // HELPER: Enhance zero-result search by trying keyword splits
    // e.g., "playground in Lo Wu" returns 0 → retry with "Lo Wu"
    // ══════════════════════════════════════════════════════════════
    // ══════════════════════════════════════════════════════════════
    // POST-PROCESSOR: Java-side processing after each tool call
    // Filters, enriches, or chains results before feeding to LLM
    // ══════════════════════════════════════════════════════════════
    private String postProcess(String toolName, Map<String, Object> args, String toolResult, String userPrompt, String sessionId, AgentResult result) {
        List<String> codes = extractCodesFromResult(toolResult);

        if (sessionId != null && !sessionId.trim().isEmpty() && codes != null && !codes.isEmpty()) {
            LAST_RESULTS.put(sessionId, new ArrayList<>(codes));
            log.info("[Manual Info] Remembered {} codes for session {}", codes.size(), sessionId);
        }
        return toolResult;
    }

    private String filterResultsByLocation(String toolResult, String locationKeyword) {
        if (locationKeyword == null || locationKeyword.isEmpty()) {
            return toolResult;
        }
        try {
            JsonNode node = mapper.readTree(toolResult);
            JsonNode results = node.path("results");
            if (!results.isArray()) {
                return toolResult;
            }
            String keyword = locationKeyword.toUpperCase();
            List<JsonNode> filtered = new ArrayList<JsonNode>();
            for (JsonNode item : results) {
                String name = item.path("LOC_NAME").asText("").toUpperCase();
                String address = item.path("ADDRESS").asText("").toUpperCase();
                if (name.contains(keyword) || address.contains(keyword)) {
                    filtered.add(item);
                }
            }
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("count", filtered.size());
            response.put("results", filtered);
            response.put("locationFilter", locationKeyword);
            // Preserve grade/filter fields
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                if (!e.getKey().equals("count") && !e.getKey().equals("results")) {
                    response.put(e.getKey(), e.getValue());
                }
            }
            log.info("[Manual Info] filterResultsByLocation: '{}' → {} of {} results match",
                    locationKeyword, filtered.size(), results.size());
            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("filterResultsByLocation error: {}", e.getMessage());
            return toolResult;
        }
    }

    /**
     * Call LLM to extract structured keywords from user prompt. This is a small
     * fast call — no tools, just JSON extraction. Returns null if extraction
     * fails (caller should fallback gracefully).
     */
    public ExtractedKeywords extractKeywords(String userPrompt) {
        String cleanPrompt = stripRetryFeedback(userPrompt);
        String cacheKey = cleanPrompt.trim().toLowerCase(Locale.ROOT);

        ExtractedKeywords cached = kwCache.get(cacheKey);
        if (cached != null) {
            log.info("[Manual Info]Keyword cache hit");
            return cached;
        }

        ExtractedKeywords result = extractKeywordsFromLlm(cleanPrompt);
        if (result != null) {
            kwCache.put(cacheKey, result);
        }
        return result;
    }

    private String stripRetryFeedback(String prompt) {
        if (prompt == null) {
            return "";
        }
        return prompt.replaceAll(
                "(?s)\\s*\\[Previous answer was flagged:.*?\\]\\s*",
                " ").trim();
    }

    // ── PRIVATE: actual LLM call ──────────────────────────────────
    private ExtractedKeywords extractKeywordsFromLlm(String userPrompt) {
        try {
            // ── Build combined system + user prompt ───────────────────────
            String fullPrompt = KEYWORD_EXTRACT_PROMPT
                    + "\n\nExtract keywords from: " + userPrompt + " /nothink";
            log.debug("[Manual Debug] Keyword extract request → model={}",
                    useTencent ? tencentModel : ollamaModel);
            // Routes to Tencent or Ollama based on config
            String responseText = callLlmSimple(fullPrompt, 0.0, useTencent ? tencentNumCtx : ollamaNumCtx);
            log.debug("[Manual Debug] Keyword extract response: {}", responseText);
            // ── Strip thinking tags and markdown fences ───────────────────
            responseText = stripThinkingTags(responseText);
            responseText = stripMarkdownFences(responseText);
            log.info("[Manual Info]Extracted keywords JSON: {}", responseText);
            // ── Parse the JSON response ───────────────────────────────────
            JsonNode kw = mapper.readTree(responseText);
            ExtractedKeywords result = new ExtractedKeywords();
            List<String> intentsList = new ArrayList<String>();
            JsonNode intentsNode = kw.path("intents");
            if (intentsNode.isArray()) {
                for (JsonNode i : intentsNode) {
                    String val = i.asText("").trim().toUpperCase();
                    if (!val.isEmpty()) {
                        intentsList.add(val);
                    }
                }
            } else if (intentsNode.isTextual()) {
                intentsList.add(intentsNode.asText("UNKNOWN").trim().toUpperCase());
            }
            if (intentsList.isEmpty()) {
                String single = kw.path("intent").asText("UNKNOWN").trim().toUpperCase();
                intentsList.add(single);
            }
            result.setIntents(intentsList);
            result.setLocationCode(nullIfEmpty(kw.path("locationCode").asText("")));
            result.setLocationName(nullIfEmpty(kw.path("locationName").asText("")));
            result.setReportType(nullIfEmpty(kw.path("reportType").asText("")));
            result.setDepartment(nullIfEmpty(kw.path("department").asText("")));
            result.setPsm(nullIfEmpty(kw.path("psm").asText("")));
            result.setGrade(nullIfEmpty(kw.path("grade").asText("")));
            result.setFilter(nullIfEmpty(kw.path("filter").asText("")));
            result.setModifier(nullIfEmpty(kw.path("modifier").asText("")));
            result.setExcludeUndefinedField(nullIfEmpty(kw.path("excludeUndefinedField").asText("")));
            result.setGrade(sanitizeGrade(result.getGrade()));
            result.setPrimaryIntent(nullIfEmpty(kw.path("primaryIntent").asText("")));
            result.setShowDetails(kw.path("showDetails").asBoolean(false));
            result.setPlan(parseLlmPlan(kw.path("plan")));
            List<String> rawList = new ArrayList<String>();
            JsonNode rawKw = kw.path("rawKeywords");
            if (rawKw.isArray()) {
                for (JsonNode kNode : rawKw) {
                    rawList.add(kNode.asText());
                }
            }
            result.setRawKeywords(rawList);
            log.info("[Manual Info]Keywords: intents={} primary={} location={} modifier={} dept={} grade={} filter={} showDetails={} plan={}",
                    result.getIntents(), result.getPrimaryIntent(), result.getLocationName(), result.getModifier(),
                    result.getDepartment(), result.getGrade(), result.getFilter(), result.isShowDetails(), result.getPlan());
            return result;
        } catch (Exception e) {
            log.error("[Manual Error] Keyword extraction failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Strip ```json ... ``` markdown fences from LLM output ────────
    private List<IntentStep> parseLlmPlan(JsonNode planNode) {
        List<IntentStep> steps = new ArrayList<IntentStep>();
        if (planNode == null || !planNode.isArray()) {
            return steps;
        }
        for (JsonNode stepNode : planNode) {
            if (stepNode == null || !stepNode.isObject()) {
                continue;
            }
            IntentStep step = new IntentStep();
            step.setType(nullIfEmpty(stepNode.path("type").asText("")));
            if (step.getType() != null) {
                switch (step.getType()) {
                    case "LOCATION_NAME_SEARCH":
                    case "NAME_LOOKUP":
                    case "SEARCH_BY_NAME":
                        step.setType("NAME_SEARCH");
                        break;
                    case "DEPARTMENT_INFO":
                    case "DEPT_INFO":
                    case "GET_DEPARTMENT":
                        step.setType("LOCATION_CODE");
                        break;
                    case "MONUMENT_INFO":
                    case "MONUMENT_LOOKUP":
                        step.setType("DECLARED_MONUMENT");
                        break;
                    case "HISTORIC_INFO":
                    case "BUILDING_INFO":
                        step.setType("HISTORIC_BUILDING");
                        break;
                }
            }
            step.setPriority(stepNode.path("priority").asInt(1));
            Map<String, String> params = new LinkedHashMap<String, String>();
            JsonNode paramsNode = stepNode.path("params");
            if (paramsNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = paramsNode.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    if (e.getValue().isNull()) {
                        continue;
                    }

                    JsonNode valueNode = e.getValue();
                    if (valueNode == null || valueNode.isNull()) {
                        continue;
                    }

                    String value;
                    if (valueNode.isArray()) {
                        List<String> values = new ArrayList<>();
                        for (JsonNode item : valueNode) {
                            String itemValue = item.asText("").trim();
                            if (!itemValue.isEmpty()) {
                                values.add(itemValue);
                            }
                        }
                        value = String.join(",", values);
                    } else {
                        value = valueNode.asText("").trim();
                    }

                    if (!value.isEmpty()) {
                        params.put(e.getKey(), value);
                    }

                }
            }
            String relation = nullIfEmpty(stepNode.path("relation").asText(""));
            if (relation != null) {
                params.put("relation", relation.trim().toLowerCase(Locale.ROOT));
            }

            step.setParams(params);
            step.setRelation(relation);
            steps.add(step);
        }
        return steps;
    }

    private String stripMarkdownFences(String content) {
        if (content == null) {
            return "";
        }
        return content
                .replaceAll("(?s)```json\\s*", "")
                .replaceAll("(?s)```\\s*", "")
                .trim();
    }

    // ── Return null instead of empty string ──────────────────────────
    private String nullIfEmpty(String s) {
        return (s == null || s.trim().isEmpty() || "null".equalsIgnoreCase(s.trim()))
                ? null : s.trim();
    }

    // ── Resolve the location filter from any common param name ─────────
    private String getLocationFilter(Intent intent) {
        String location = intent.params.get("locationFilter");
        if (location == null || location.isEmpty()) {
            location = intent.params.get("location");
        }
        if (location == null || location.isEmpty()) {
            location = intent.params.get("locationName");
        }
        return location;
    }

    /**
     * Builds the args map for a tool call from intent params. Location filter
     * is injected into args for ALL tools — DB does filtering. Returns null if
     * the intent cannot proceed (e.g. no codes for report check).
     */
    private Map<String, Object> buildArgs(Intent intent, String lastToolResult, List<String> lastCodes, String sessionId) {
        if (intent == null) {
            return null;
        }

        Map<String, String> params = intent.params == null
                ? Collections.<String, String>emptyMap()
                : intent.params;
        ToolDefinition definition = mcpClient.resolveDefinition(intent.type, params);

        if (definition == null) {
            definition = mcpClient.resolveDefinition(intent.toolName, params);
        }

        if (definition == null) {
            log.warn("No tool definition for intent={} tool={}", intent.type, intent.toolName);
            return null;
        }

        Set<String> accepted = mcpClient.getAcceptedParameters(definition);

        if (accepted == null) {
            accepted = Collections.emptySet();
        }

        Map<String, Object> args = new LinkedHashMap<String, Object>();

        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (key == null
                    || value == null
                    || value.trim().isEmpty()
                    || !accepted.contains(key)) {
                continue;
            }

            if ("limit".equals(key)) {
                try {
                    args.put(key, Integer.valueOf(value.trim()));
                } catch (NumberFormatException e) {
                    log.warn("Ignoring invalid limit value: {}", value);
                }

            } else if ("locCds".equals(key)) {
                args.put(key, parseLocationCodes(value));
            } else {
                args.put(key, value.trim());
            }
        }

        String relation = getRelation(intent);

        if ("use_previous_codes".equals(relation)) {
            List<String> codes = lastCodes == null || lastCodes.isEmpty()
                    ? getLastResults(sessionId)
                    : lastCodes;
            if (codes == null || codes.isEmpty()) {
                log.warn("No previous location codes available for tool {}", definition.getToolName());
                return null;
            }

            if (accepted.contains("locCds")) {
                args.put("locCds", new ArrayList<String>(codes));
            } else if (accepted.contains("locCd")) {
                if (codes.size() != 1) {
                    log.warn("Singular tool {} requires exactly one code; received {}",
                            definition.getToolName(), codes.size());
                    return null;
                }
                args.put("locCd", codes.get(0));
            } else {
                log.warn("Tool {} accepts neither locCds nor locCd", definition.getToolName());
                return null;
            }
        }
        String codesSource = params.get("codesSource");
        if ("previous_first".equals(codesSource) && accepted.contains("locCd")) {
            String firstCode = extractFirstCode(lastToolResult);
            if (firstCode == null || firstCode.trim().isEmpty()) {
                return null;
            }
            args.put("locCd", firstCode.trim().toUpperCase());
        }
        return args;
    }

    /**
     * Maps IntentType → MCP tool name. Single source of truth for all tool name
     * bindings.
     */
    private String resolveToolName(Intent intent) {
        if (intent == null) {
            return null;
        }
        if (intent.toolName != null && !intent.toolName.trim().isEmpty()) {
            return intent.toolName.trim();
        }
        Map<String, String> params = intent.params == null
                ? Collections.<String, String>emptyMap() : intent.params;
        ToolDefinition definition = mcpClient.resolveDefinition(intent.type, params);
        return definition == null ? null : definition.getToolName();
    }

    /**
     * Applies post-call modifiers to a tool result. OLDEST / EARLIEST → find
     * location with smallest BLDG_COMPLETION_YEAR NEWEST / LATEST → find
     * location with largest BLDG_COMPLETION_YEAR FIRST → take first result only
     * COUNT → return count summary only No modifier → return result unchanged
     */
    private String applyModifier(Intent intent, String toolResult, AgentResult result) throws IOException {
        if (intent == null || intent.params == null) {
            return toolResult;
        }

        String modifier = intent.params.get("modifier");
        if (modifier == null || modifier.trim().isEmpty()) {
            return toolResult;
        }

        modifier = modifier.trim().toUpperCase();
        log.info("[Manual Info] Applying modifier {} to {} result codes", modifier, extractCodesFromResult(toolResult).size());

        switch (modifier) {
            case "OLDEST":
            case "EARLIEST": {
                List<String> codes = extractCodesFromResult(toolResult);
                if (codes.isEmpty()) {
                    return toolResult;
                }
                return findByYear(codes, false, result);
            }
            case "NEWEST":
            case "LATEST": {
                List<String> codes = extractCodesFromResult(toolResult);
                if (codes.isEmpty()) {
                    return toolResult;
                }
                return findByYear(codes, true, result);
            }
            case "FIRST": {
                String firstCode = extractFirstCode(toolResult);
                if (firstCode == null || firstCode.trim().isEmpty()) {
                    return toolResult;
                }

                String detailTool = resolveDetailToolName(firstCode);
                if (detailTool == null) {
                    log.warn("No location-detail tool is registered");
                    return toolResult;
                }

                Map<String, Object> detailArgs = map("locCd", firstCode.trim().toUpperCase());
                String detailResult = mcpClient.callTool(detailTool, detailArgs);
                result.addToolCall(detailTool, detailArgs, detailResult);
                return detailResult;
            }
            case "COUNT": {
                List<String> codes = extractCodesFromResult(toolResult);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("count", codes.size());
                response.put("summary", "Found " + codes.size() + " matching location(s).");
                response.put("found", !codes.isEmpty());
                return mapper.writeValueAsString(response);
            }
            default:
                log.warn("Unknown modifier: {}", modifier);
                return toolResult;
        }
    }

    /**
     * Finds location with min (oldest) or max (newest) BLDG_COMPLETION_YEAR.
     * Replaces the old findOldestWithDetails — now handles both directions.
     */
    private String findByYear(List<String> locCds, boolean findMax, AgentResult result) throws IOException {
        if (locCds == null || locCds.isEmpty()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("found", false);
            response.put("summary", "No candidates to compare.");
            return mapper.writeValueAsString(response);
        }

        List<String> capped = locCds.size() > 200 ? new ArrayList<>(locCds.subList(0, 200)) : new ArrayList<>(locCds);
        log.info("[Manual Info] Comparing {} location candidates by completion year", capped.size());

        List<Map<String, Object>> rows = DatabaseManager.getInstance().getGeneralInfoBatch(capped);
        String bestCode = null, bestName = null, bestDept = null, bestDeptDesc = null;
        int bestYear = findMax ? Integer.MIN_VALUE : Integer.MAX_VALUE;

        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            Object yearValue = row.get("BLDG_COMPLETION_YEAR");
            int year;

            try {
                year = yearValue == null ? 0 : (int) Double.parseDouble(yearValue.toString());
            } catch (NumberFormatException e) {
                continue;
            }

            boolean better = year > 0 && (findMax ? year > bestYear : year < bestYear);
            if (!better) {
                continue;
            }

            bestYear = year;
            Object codeValue = row.get("LOC_CD");
            Object nameValue = row.get("LOC_NAME");
            Object deptValue = row.get("DEPT_CD");
            Object deptDescValue = row.get("DEPT_DESC");

            bestCode = codeValue == null ? null : codeValue.toString();
            bestName = nameValue == null ? "" : nameValue.toString();
            bestDept = deptValue == null ? "" : deptValue.toString();
            bestDeptDesc = deptDescValue == null ? "" : deptDescValue.toString();
        }

        Map<String, Object> response = new LinkedHashMap<>();
        if (bestCode == null || bestCode.trim().isEmpty()) {
            response.put("found", false);
            response.put("summary", "Could not determine " + (findMax ? "newest" : "oldest") + " location because completion-year data was unavailable.");
            return mapper.writeValueAsString(response);
        }

        String label = findMax ? "Newest" : "Oldest";
        String managedBy = bestDept != null && !bestDept.trim().isEmpty() ? bestDept : bestDeptDesc;

        response.put("found", true);
        response.put("LOC_CD", bestCode);
        response.put("LOC_NAME", bestName);
        response.put("DEPT_CD", bestDept);
        response.put("DEPT_DESC", bestDeptDesc);
        response.put("YEAR", bestYear > 0 ? bestYear : "unknown");
        response.put("summary", label + " location: " + bestName + " (" + bestCode + ")" + (bestYear > 0 ? ", completed " + bestYear : "") + (managedBy == null || managedBy.trim().isEmpty() ? "" : ", managed by: " + managedBy));

        String detailTool = resolveDetailToolName(bestCode);
        if (detailTool == null) {
            log.warn("No location-detail tool is registered");
            return mapper.writeValueAsString(response);
        }

        Map<String, Object> detailArgs = map("locCd", bestCode);
        String detailResult = mcpClient.callTool(detailTool, detailArgs);
        result.addToolCall(detailTool, detailArgs, detailResult);

        return detailResult;
    }

    /**
     * Ask the LLM to generate a SQL query, then execute it. Used as last resort
     * when no tool matches the user's question.
     */
    private String generateAndExecuteSql(
            String userPrompt,
            ExtractedKeywords keywords,
            AuthorizationContext authorizationContext) throws IOException {
        log.info("[Manual Info]Generating SQL for: '{}'", userPrompt);
        // ── Build context clues ───────────────────────────────────────
        StringBuilder question = new StringBuilder();
        question.append("Question: ").append(userPrompt);
        if (keywords != null) {
            question.append("\n\nContext clues:");
            if (keywords.getLocationName() != null
                    && !keywords.getLocationName().isEmpty()) {
                question.append("\n- Location filter: ")
                        .append(keywords.getLocationName());
            }
            if (keywords.getDepartment() != null) {
                question.append("\n- Department: ")
                        .append(keywords.getDepartment());
            }
            if (keywords.getReportType() != null) {
                question.append("\n- Report types mentioned: ")
                        .append(keywords.getReportType());
            }
            if (keywords.getModifier() != null) {
                question.append("\n- Modifier: ")
                        .append(keywords.getModifier());
            }
            if (keywords.getGrade() != null) {
                question.append("\n- Grade: ")
                        .append(keywords.getGrade());
            }
            if (keywords.getFilter() != null) {
                question.append("\n- Monument filter: ")
                        .append(keywords.getFilter());
            }
            if (!keywords.getRawKeywords().isEmpty()) {
                question.append("\n- Raw keywords: ")
                        .append(String.join(", ", keywords.getRawKeywords()));
            }
            if (keywords.getLocationName() == null
                    && keywords.getDepartment() == null
                    && keywords.getReportType() == null
                    && keywords.getModifier() == null) {
                question.append(
                        "\n- No location filter — search across ALL locations");
            }
        }
        // ── Build combined system + user prompt ───────────────────────
        String fullPrompt = SQL_GENERATE_PROMPT
                + "\n\n"
                + question.toString()
                + " /nothink";
        log.debug("[Manual Debug] SQL generate request → model={}",
                useTencent ? tencentModel : ollamaModel);
        // Routes to Tencent or Ollama based on config
        String generatedSql = callLlmSimple(fullPrompt, 0.0, 1500);
        // ── Strip thinking tags and markdown fences ───────────────────
        generatedSql = stripThinkingTags(generatedSql);
        generatedSql = stripMarkdownFences(generatedSql);
        generatedSql = unescapeHtml(generatedSql);
        if (generatedSql == null || generatedSql.trim().isEmpty()) {
            return "{\"error\":\"LLM returned empty SQL\"}";
        }
        generatedSql = validateLlmSql(generatedSql);
        log.debug("[Security] Generated SQL length={}",
                generatedSql.length());

        // ── SQL FIREWALL ───────────────────────────────────────────────
        if (!isSqlSafe(generatedSql)) {
            log.error("[Security] SQL firewall BLOCKED: {}",
                    generatedSql.substring(0, Math.min(200, generatedSql.length())));
            return "{\"error\":\"Generated SQL blocked by security firewall\"}";
        }
        // Execute the generated SQL and measure the actual database call.
        long sqlStartMs = System.currentTimeMillis();

        Map<String, Object> queryResult
                = DatabaseManager.getInstance()
                        .executeLlmGeneratedQuery(
                                generatedSql,
                                authorizationContext
                        );

        long durationMs = System.currentTimeMillis() - sqlStartMs;

        Object countValue = queryResult.get("count");
        long rowCount = countValue instanceof Number
                ? ((Number) countValue).longValue()
                : -1L;

        log.info("LLM SQL request completed: rows={}, durationMs={}",
                rowCount, durationMs);

        return mapper.writeValueAsString(queryResult);
    }

    private String extractLocationHintFromKeywords(List<String> rawKeywords) {
        // Known non-location words to skip
        Set<String> skip = new java.util.HashSet<String>(Arrays.asList(
                "any", "location", "code", "has", "all", "5", "reports",
                "the", "a", "an", "in", "at", "near", "which", "what",
                "find", "show", "list", "get", "and", "or", "for"
        ));
        List<String> candidates = new ArrayList<String>();
        for (String kw : rawKeywords) {
            if (!skip.contains(kw.toLowerCase())) {
                candidates.add(kw);
            }
        }
        // Join remaining keywords as potential location name
        if (!candidates.isEmpty()) {
            return String.join(" ", candidates);
        }
        return null;
    }

    /**
     * Validates LLM-generated SQL before execution. Catches common
     * hallucination patterns.
     */
    private String validateLlmSql(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return "Empty SQL";
        }
        // ── Known table name hallucinations ───────────────────────────
        Map<String, String> corrections = new LinkedHashMap<String, String>();
        corrections.put("KAI_RECORD_PLANS_OR_DRAWINGS", "KAI_RECORD_PLANS_AND_DRAWINGS");
        corrections.put("KAI_RECORD_PLANS_TO_DRAWINGS", "KAI_RECORD_PLANS_AND_DRAWINGS");
        corrections.put("KAI_PLANS_AND_DRAWINGS", "KAI_RECORD_PLANS_AND_DRAWINGS");
        corrections.put("KAI_RECORD_AND_DRAWINGS", "KAI_RECORD_PLANS_AND_DRAWINGS");
        corrections.put("BLDG_SAFETY_INSPECTION_INFO", "BSI_GENERAL_INFO");
        corrections.put("BSI_INFO", "BSI_GENERAL_INFO");
        String corrected = sql;
        for (Map.Entry<String, String> entry : corrections.entrySet()) {
            if (corrected.contains(entry.getKey())) {
                log.warn(" SQL validation: correcting '{}' → '{}'",
                        entry.getKey(), entry.getValue());
                corrected = corrected.replace(entry.getKey(), entry.getValue());
            }
        }
        return corrected;
    }

    // ══════════════════════════════════════════════════════════════
    // SQL FIREWALL (ProjectReview P0 — Issue #7)
    // ══════════════════════════════════════════════════════════════
    /**
     * Returns true if the LLM-generated SQL is safe to execute. Blocks:
     * non-SELECT, DDL/DML, multi-statement, embedded comments.
     */
    private boolean isSqlSafe(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return false;
        }
        String trimmed = sql.trim();
        String upper = trimmed.toUpperCase().replaceAll("\\s+", " ").trim();

        // 1. Must start with SELECT or WITH (CTE)
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            log.warn("[Security] SQL firewall BLOCKED non-SELECT: {}",
                    trimmed.substring(0, Math.min(80, trimmed.length())));
            return false;
        }

        // 2. Block dangerous keywords
        if (SQL_DANGEROUS_PATTERN.matcher(trimmed).find()) {
            log.warn("[Security] SQL firewall BLOCKED dangerous keyword: {}",
                    trimmed.substring(0, Math.min(80, trimmed.length())));
            return false;
        }

        // 3. Block multi-statement (semicolons outside quotes)
        int semicolons = 0;
        boolean inQuote = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
            } else if (c == ';' && !inQuote) {
                semicolons++;
            }
        }
        if (semicolons > 0) {
            log.warn("[Security] SQL firewall BLOCKED multi-statement ({} semicolons)", semicolons);
            return false;
        }

        // 4. Block embedded SQL comments (can hide malicious payloads)
        String noTrailing = trimmed.replaceAll("--[^\\n]*$", "").trim();
        if (noTrailing.contains("--") || noTrailing.contains("/*")) {
            log.warn("[Security] SQL firewall BLOCKED embedded comments");
            return false;
        }

        return true;
    }

    /**
     * LEFT JOIN style merge: - Keeps ALL results from monumentResult (left) -
     * Adds GRD_HIST_BLDG from historicResult where LOC_CD matches -
     * Deduplicates by LOC_CD
     */
    private String enrichWithHistoricGrade(
            String monumentResult,
            String historicResult,
            String gradeFilter) {      // ← new param
        try {
            JsonNode monNode = mapper.readTree(monumentResult);
            JsonNode histNode = mapper.readTree(historicResult);
            JsonNode monArr = monNode.path("results");
            JsonNode histArr = histNode.path("results");
            if (!monArr.isArray()) {
                return monumentResult;
            }
            // Build grade lookup: LOC_CD → GRD_HIST_BLDG
            Map<String, String> gradeMap = new LinkedHashMap<String, String>();
            if (histArr.isArray()) {
                for (JsonNode item : histArr) {
                    String cd = item.path("LOC_CD").asText("").trim().toUpperCase();
                    String grade = item.path("GRD_HIST_BLDG").asText("").trim();
                    if (!cd.isEmpty()) {
                        gradeMap.put(cd, grade);
                    }
                }
            }
            log.info("[Manual Info]Grade lookup built: {} entries", gradeMap.size());
            // Determine filter mode
            boolean filterToGraded = "GRADED".equals(gradeFilter);
            boolean filterToSpecific = gradeFilter != null
                    && !gradeFilter.isEmpty()
                    && !"ALL".equals(gradeFilter)
                    && !"GRADED".equals(gradeFilter);
            Set<String> seen = new LinkedHashSet<String>();
            List<Map<String, Object>> merged = new ArrayList<Map<String, Object>>();
            for (JsonNode item : monArr) {
                String cd = item.path("LOC_CD").asText("").trim().toUpperCase();
                if (cd.isEmpty() || !seen.add(cd)) {
                    continue; // deduplicate
                }
                String grade = gradeMap.get(cd);  // null if not in historic table
                // ── Apply grade filter ────────────────────────────────────
                if (filterToGraded && (grade == null || grade.isEmpty())) {
                    continue; // skip ungraded
                }
                if (filterToSpecific && !gradeFilter.equals(grade)) {
                    continue; // skip wrong grade
                }
                // Convert to mutable map
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                Iterator<Map.Entry<String, JsonNode>> fields = item.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> e = fields.next();
                    row.put(e.getKey(), e.getValue().asText(""));
                }
                // Inject grade
                row.put("GRD_HIST_BLDG", grade != null && !grade.isEmpty()
                        ? grade : "N/A");
                merged.add(row);
            }
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("count", merged.size());
            response.put("results", merged);
            response.put("filter", monNode.path("filter").asText("T"));
            response.put("enriched", true);
            if (gradeFilter != null) {
                response.put("gradeFilter", gradeFilter);
            }
            log.info("[Manual Info]Enriched result: {} monuments match grade filter '{}'",
                    merged.size(), gradeFilter);
            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("enrichWithHistoricGrade error: {}", e.getMessage());
            return monumentResult;
        }
    }

    /**
     * Generic LEFT-JOIN style enrichment: merge attributes from the enrich
     * result into the base result rows, keyed by LOC_CD.
     */
    private String enrichWithPrevious(String baseResult, String enrichResult) {
        try {
            JsonNode baseNode = mapper.readTree(baseResult);
            JsonNode enrichNode = mapper.readTree(enrichResult);
            JsonNode baseArr = baseNode.path("results");
            JsonNode enrichArr = enrichNode.path("results");
            if (!baseArr.isArray()) {
                return baseResult;
            }
            Map<String, JsonNode> enrichMap = new LinkedHashMap<String, JsonNode>();
            if (enrichArr.isArray()) {
                for (JsonNode item : enrichArr) {
                    String cd = item.path("LOC_CD").asText("").trim().toUpperCase();
                    if (!cd.isEmpty()) {
                        enrichMap.put(cd, item);
                    }
                }
            }
            List<Map<String, Object>> merged = new ArrayList<Map<String, Object>>();
            for (JsonNode item : baseArr) {
                String cd = item.path("LOC_CD").asText("").trim().toUpperCase();
                JsonNode extra = cd.isEmpty() ? null : enrichMap.get(cd);
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                Iterator<Map.Entry<String, JsonNode>> fields = item.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> e = fields.next();
                    row.put(e.getKey(), e.getValue());
                }
                if (extra != null) {
                    Iterator<Map.Entry<String, JsonNode>> extraFields = extra.fields();
                    while (extraFields.hasNext()) {
                        Map.Entry<String, JsonNode> e = extraFields.next();
                        if (!row.containsKey(e.getKey())) {
                            row.put(e.getKey(), e.getValue());
                        }
                    }
                }
                merged.add(row);
            }
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("count", merged.size());
            response.put("results", merged);
            response.put("enriched", true);
            Iterator<Map.Entry<String, JsonNode>> fields = baseNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                if (!"count".equals(e.getKey()) && !"results".equals(e.getKey())) {
                    response.put(e.getKey(), e.getValue());
                }
            }
            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("enrichWithPrevious error: {}", e.getMessage());
            return baseResult;
        }
    }
    // ── Referential word patterns ─────────────────────────────────────────
    private static final Pattern REFERENTIAL_PATTERN = Pattern.compile(
            "\\b(these|those|this location|that location|them|the above|previous locations)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern LIMIT_PATTERN = Pattern.compile(
            "(?i)\\b(?:top|first)\\s+(\\d{1,4})\\b"
    );

    /**
     * If the prompt contains referential words ("that", "it", "those") AND the
     * session has remembered codes, rewrite the prompt to be specific.
     *
     * Examples: "Get info for that" → "Get info for AB04400215002" "show
     * reports for those" → "show reports for AB04400215002, CC04400144000"
     * "check BSI for them" → "check BSI for AB04400215002, CC04400144000"
     */
    private String resolveReferentialPrompt(String prompt, String sessionId) {
        if (prompt == null || prompt.trim().isEmpty()) {
            return prompt;
        }
        // ── 2. Strip verifier retry warnings so they don't trigger memory dumps ──
        String cleanPrompt = prompt.replaceAll("\\[Previous answer was flagged:.*?\\]", "").trim();
        // Only resolve if clean prompt contains a true referential pointer word
        if (!REFERENTIAL_PATTERN.matcher(cleanPrompt).find()) {
            return prompt;
        }
        // Only resolve if we have session memory
        List<String> lastCodes = getLastResults(sessionId);
        if (lastCodes.isEmpty()) {
            log.info("[Manual Info]Referential prompt but no session memory — leaving as-is");
            return prompt;
        }
        // For single code: replace referential word with the code
        // For multiple codes: append all codes
        String codeList;
        if (lastCodes.size() == 1) {
            codeList = lastCodes.get(0);
        } else {
            // Limit to first 10 to avoid extremely long prompts
            List<String> limited = lastCodes.subList(0, Math.min(10, lastCodes.size()));
            codeList = String.join(", ", limited);
        }
        // Replace the referential word(s) in the original prompt
        String resolved = REFERENTIAL_PATTERN.matcher(prompt)
                .replaceFirst(codeList);
        log.info("[Manual Info]Referential resolution: '{}' codes in memory, using: {}",
                lastCodes.size(), codeList);
        return resolved;
    }

    /**
     * Sanitize LLM-extracted grade values. LLM sometimes returns "NOT NULL",
     * "NONE", "any", etc. Only allow: null, "1", "2", "3", "ALL", "NONE"
     */
    private String sanitizeGrade(String grade) {
        if (grade == null) {
            return null;
        }
        String g = grade.trim().toUpperCase();
        switch (g) {
            case "1":
            case "2":
            case "3":
                return g;
            case "ALL":
            case "ANY":
            case "SOME":
                return "ALL";
            case "NONE":
            case "0":
            case "NULL":
            case "NO GRADE":
                return "NONE";
            case "NOT NULL":
            case "NON-NULL":
            case "GRADED":
            case "HAS GRADE":
                // User wants only graded ones — return special sentinel
                return "GRADED";
            default:
                log.warn(" Unknown grade value '{}' — defaulting to ALL", grade);
                return "ALL";
        }
    }

    public AgentResult runAgentWithTools(String prompt, String sessionId)
            throws Exception {
        return this.invoke(prompt, sessionId);
    }

    /**
     * Returns a human-readable description of a plan step for the intersection
     * summary. e.g., "BSI report", "Grade 1 historic", "Declared monument",
     * "PSM KT", etc.
     */
    private String describeStep(Intent step) {
        switch (step.type) {
            case Intent.CHECK_REPORTS:
                return step.params.getOrDefault("reportType", "report") + " report";
            case Intent.HISTORIC_BUILDING: {
                String grade = step.params.getOrDefault("grade", "ALL");
                if ("ALL".equals(grade)) {
                    return "Historic building";
                }
                if ("GRADED".equals(grade)) {
                    return "Graded historic";
                }
                return "Grade " + grade + " historic";
            }
            case Intent.DECLARED_MONUMENT: {
                String filter = step.params.getOrDefault("filter", "T");
                if ("T".equals(filter)) {
                    return "Declared monument";
                }
                if ("F".equals(filter)) {
                    return "Non-monument";
                }
                return "Monument (all)";
            }
            case Intent.PSM_LOCATIONS:
                return "PSM " + step.params.getOrDefault("psm", "");
            case Intent.DEPARTMENT_LOCATIONS:
                return "Dept " + step.params.getOrDefault("deptCd", "");
            case Intent.NAME_SEARCH:
                return "Name: " + step.params.getOrDefault("locName", "");
            case Intent.LOCATION_CODE:
                return "Location details";
            case Intent.CODE_HISTORY:
                return "Code history";
            default:
                return step.type;
        }
    }

    private String buildCallKey(String toolName, Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return toolName + "|{}";
        }
        Map<String, Object> normalized = new java.util.TreeMap<String, Object>(); // sorted by key
        for (Map.Entry<String, Object> e : args.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String) {
                normalized.put(e.getKey(), ((String) v).trim().toUpperCase());
            } else {
                normalized.put(e.getKey(), v);
            }
        }
        try {
            return toolName + "|" + mapper.writeValueAsString(normalized);
        } catch (Exception e) {
            // Fallback — still functional, just less precise about key equality
            return toolName + "|" + normalized.toString();
        }
    }

    /**
     * Extracts a "top N" / "first N" row limit from free text, if present.
     * Returns null if no such phrase is found, so callers can fall back to
     * DatabaseManager's own default. Capped to 4 digits at the regex level (max
     * 9999) as a first line of defense; DatabaseManager itself clamps to
     * MAX_PSM_LOCATIONS_LIMIT regardless, so this is defense-in-depth, not the
     * only safeguard.
     */
    private Integer extractLimitFromPrompt(String prompt) {
        if (prompt == null) {
            return null;
        }
        Matcher m = LIMIT_PATTERN.matcher(prompt);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private void putExcludeUndefinedIfPresent(Map<String, Object> args, Intent intent) {
        String field = intent.params.get("excludeUndefinedField");
        if (field != null && !field.trim().isEmpty()) {
            args.put("excludeUndefinedField", field.trim().toLowerCase());
        }
    }

    private List<String> parseLocationCodes(String value) {
        List<String> codes = new ArrayList<String>();
        if (value == null || value.trim().isEmpty()) {
            return codes;
        }
        for (String part : value.split(",")) {
            String code = part.trim().toUpperCase();
            if (!code.isEmpty()) {
                codes.add(code);
            }
        }
        return codes;
    }

    private String resolveDetailToolName() {
        return resolveDetailToolName(null);
    }

    private String resolveDetailToolName(String locationCode) {
        Map<String, String> params = new LinkedHashMap<>();

        if (locationCode != null && !locationCode.trim().isEmpty()) {
            params.put("locCd", locationCode.trim().toUpperCase(Locale.ROOT));
        }

        ToolDefinition definition = mcpClient.resolveDefinition(Intent.LOCATION_CODE, params);

        return (definition != null) ? definition.getToolName() : null;
    }

    private boolean isRedundantDetailStep(Intent intent, String lastToolResult, List<String> lastCodes) {
        if (intent == null
                || lastToolResult == null
                || lastCodes == null
                || lastCodes.size() != 1) {
            return false;
        }

        String detailTool = resolveDetailToolName();
        String currentTool = resolveToolName(intent);

        if (detailTool == null || currentTool == null || !detailTool.equals(currentTool)) {
            return false;
        }

        try {
            JsonNode previous = mapper.readTree(lastToolResult);
            String previousCode = previous.path("general").path("LOC_CD").asText("");
            if (previousCode.isEmpty()) {
                previousCode = previous.path("LOC_CD").asText("");
            }

            return !previousCode.isEmpty() && previousCode.equalsIgnoreCase(lastCodes.get(0));
        } catch (Exception e) {
            return false;
        }
    }

    private int findAnswerStepIndex(Plan plan) {
        if (plan == null || plan.steps == null || plan.steps.isEmpty()) {
            return -1;
        }

        /* Prefer the last executable step that has a modifier. This includes use_previous_codes steps. */
        for (int i = plan.steps.size() - 1; i >= 0; i--) {
            Intent intent = plan.steps.get(i);
            if (intent == null || resolveToolName(intent) == null) {
                continue;
            }

            Map<String, String> params = intent.params == null ? Collections.emptyMap() : intent.params;
            String modifier = params.get("modifier");

            if (modifier != null && !modifier.trim().isEmpty()) {
                return i;
            }
        }

        /* If no step has a modifier, the last executable step is the answer step. */
        for (int i = plan.steps.size() - 1; i >= 0; i--) {
            Intent intent = plan.steps.get(i);
            if (intent != null && resolveToolName(intent) != null) {
                return i;
            }
        }

        return -1;
    }

    private String getRelation(Intent intent) {
        if (intent == null) {
            return "independent";
        }

        Map<String, String> params = intent.params == null ? Collections.emptyMap() : intent.params;
        String relation = params.get("relation");

        if (relation == null || relation.trim().isEmpty()) {
            if ("previous".equalsIgnoreCase(params.get("crossFilterWith"))) {
                relation = "filter_previous";
            } else if ("true".equalsIgnoreCase(params.get("enrich"))) {
                relation = "enrich_previous";
            } else {
                relation = "independent";
            }
        }

        relation = relation.trim().toLowerCase(Locale.ROOT);

        switch (relation) {
            case "independent":
            case "filter_previous":
            case "enrich_previous":
            case "use_previous_codes":
                return relation;
            default:
                log.warn("Unknown planner relation '{}'; using independent", relation);
                return "independent";
        }
    }

    private String emptyFilteredResult(String sourceResult) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", 0);
        response.put("results", new ArrayList<JsonNode>());

        try {
            if (sourceResult != null && !sourceResult.trim().isEmpty()) {
                copyResultMetadata(mapper.readTree(sourceResult), response);
            }
            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"count\":0,\"results\":[]}";
        }
    }

    public MCPClientService getMcpClient() {
        return mcpClient;
    }

    private boolean isTimeoutResult(String json) {
        if (json == null) {
            return false;
        }
        try {
            JsonNode node = mapper.readTree(json);
            return "QUERY_TIMEOUT".equals(node.path("errorCode").asText());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isFilteringRelation(String relation) {
        return "filter_previous".equals(relation) || "use_previous_codes".equals(relation);
    }

    private String formatMultipleReportChecks(JsonNode node) {
        JsonNode checks = node.path("checks");

        if (!checks.isObject() || checks.size() == 0) {
            return "<p>No report checks were returned.</p>";
        }

        StringBuilder html = new StringBuilder();

        html.append("<h3 class='data-title'>Report Availability</h3>");

        html.append("<p class='answer-summary'>")
                .append("Checked ")
                .append(node.path("totalChecked").asInt(0))
                .append(" location(s) against: ")
                .append(escapeHtml(
                        node.path("reportType").asText("")
                ))
                .append(".</p>");

        Iterator<Map.Entry<String, JsonNode>> fields = checks.fields();

        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode check = entry.getValue();
            if (!check.isObject()) {
                continue;
            }
            html.append("<section class='report-check-group'>");

            // Ensure older provider responses still have a type.
            if (!check.has("reportType")
                    && check instanceof com.fasterxml.jackson.databind.node.ObjectNode) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) check).put("reportType", entry.getKey());
            }

            html.append(formatBulkReportCheck(check));
            html.append("</section>");
        }

        return html.toString();
    }

}
