package com.ais.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import com.ais.config.AppConfig;
import com.ais.db.DatabaseManager;
import com.ais.model.ExtractedKeywords;
import com.ais.service.Intent;
import com.ais.service.IntentRole;
import com.ais.service.Plan;
import com.ais.service.PipelineExecutor;

public class OllamaService {

    private static final Logger log = LoggerFactory.getLogger(OllamaService.class);
    
    private static final int KW_CACHE_MAX = 50;
    
    private static final Map<String, ExtractedKeywords> kwCache =
            Collections.synchronizedMap(
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
    private static final String MODEL = AppConfig.ollamaModel();

    private static final MediaType JSON_TYPE
            = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;
    private final MCPClientService mcpClient;

    // ── System prompt for tool selection (first LLM call) ─────────
    //hardcode prompt instructions for tool selection
    private static final String SYSTEM_PROMPT
            = "You are a location database assistant.\n\n"
            + "Your job is to understand the user's natural language request and decide "
            + "which tool or tools to call.\n\n"
            + "RULES:\n"
            + "1. search_by_name: Search by name or partial text. Accepts 'locName' and 'location'.\n"
            + "2. search_declared_monument: List monuments. Accepts 'filter' and 'location'.\n"
            + "3. search_historic_building: List graded buildings. Accepts 'grade' and 'location'.\n"
            + "4. locations_by_dept: List by department. Accepts 'deptCd' and 'location'.\n"
            + "5. locations_by_psm: List by PSM. Accepts 'psm' and 'location'.\n"
            + "6. If the user asks about declared monuments, use search_declared_monument.\n"
            + "7. If the user asks about historic buildings, use search_historic_building.\n"
            + "8. If the user asks about report availability, use check_reports.\n\n"
            + "MULTI-TOOL CHAINING:\n"
            + "- You may call multiple tools in sequence.\n"
            + "- If search_loc_cd_history returns CURRENT_LOC_CD, you may call hardcode_query next.\n"
            + "- If a tool returns a list of LOC_CD values, you may use them in check_reports.\n"
            + "- If a tool returns candidate locations, you may choose the best matching one and then fetch details.\n\n"
            + "IMPORTANT:\n"
            + "- Copy all location codes exactly.\n"
            + "- When you have enough data, stop calling tools and answer briefly.\n"
            + "- Do not invent database facts.\n"
            + "- /nothink";  // ← Qwen3 directive to disable thinking in prompt

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
    
    private static final String KEYWORD_EXTRACT_PROMPT =
            "You are a keyword extractor for a location database system.\n\n"
            + "Extract keywords from the user's query and return ONLY valid JSON.\n\n"
            + "JSON fields:\n"
            + "- intents: ARRAY of one or more intent strings. "
            +   "Valid values: LOCATION_CODE, NAME_SEARCH, MONUMENT, HISTORIC, "
            +   "DEPARTMENT, PSM, REPORT, CODE_HISTORY, SQL_QUERY, UNKNOWN. "
            +   "Use multiple values when the query combines two concepts.\n"
            + "- locationCode: exact location code if mentioned (e.g. SB04400361000), else null\n"
            + "- locationName: place/district name if mentioned (e.g. Sha Tin, Lo Wu), else null\n"
            + "- reportType: report type if mentioned (BSI/KAI/DSSR/EMMS/CSR/ALL), else null\n"
            + "- department: department code if mentioned (LCSD/AFCD/HD/DSD), else null\n"
            + "- psm: PSM name if mentioned (e.g. SHA TIN EAST), else null\n"
            + "- grade: historic building grade if mentioned (1/2/3/ALL), else null\n"
            + "- filter: for monuments T=declared, F=non, ALL=both, else null\n"
            + "- modifier: for CODE_HISTORY use FETCH_CURRENT, else OLDEST, NEWEST, FIRST, LATEST, ALL, COUNT. Never invent new modifier values.\n"
            + "- rawKeywords: array of important words from the query\n\n"

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
            + "- Use SQL_QUERY ONLY when asking about report combinations across ALL locations without providing specific codes.\n\n"

            + "Examples:\n"

            // ── NEW: REPORT vs SQL_QUERY examples ──────────────────────────
            + "Query: 'check all 5 reports for QA03206005000 QB03106003000 QC02306006000'\n"
            + "Output: {\"intents\":[\"REPORT\"],\"locationCode\":\"QA03206005000,QB03106003000,QC02306006000\",\"locationName\":null,"
            +   "\"reportType\":\"ALL\",\"department\":null,\"psm\":null,\"grade\":null,"
            +   "\"filter\":null,\"modifier\":null,\"rawKeywords\":[\"check all 5 reports\",\"QA03206005000\",\"QB03106003000\",\"QC02306006000\"]}\n\n"

            + "Query: 'any location code has all 5 reports?'\n"
            + "Output: {\"intents\":[\"SQL_QUERY\"],\"locationCode\":null,\"locationName\":null,"
            +   "\"reportType\":null,\"department\":null,\"psm\":null,\"grade\":null,"
            +   "\"filter\":null,\"modifier\":null,\"rawKeywords\":[\"all 5 reports\",\"location code\"]}\n\n"

            // ── PSM examples ───────────────────────────────────────────────
            + "Query: 'List all PSMs'\n"
            + "Output: {\"intents\":[\"PSM\"],\"locationCode\":null,\"locationName\":null,"
            +   "\"reportType\":null,\"department\":null,\"psm\":null,\"grade\":null,"
            +   "\"filter\":null,\"modifier\":null,\"rawKeywords\":[\"List all PSMs\"]}\n\n"

            + "Query: 'info of first location code under PSM/KT'\n"
            + "Output: {\"intents\":[\"PSM\",\"CODE_HISTORY\"],\"locationCode\":null,\"locationName\":null,"
            +   "\"reportType\":null,\"department\":null,\"psm\":\"KT\",\"grade\":null,"
            +   "\"filter\":null,\"modifier\":\"FIRST\",\"rawKeywords\":[\"info\",\"first\",\"location code\",\"PSM\",\"KT\"]}\n\n"

            // ── Attribute vs Filter examples ───────────────────────────────
            + "Query: 'Show department managing UC07300217003'\n"
            + "Output: {\"intents\":[\"LOCATION_CODE\",\"DEPARTMENT\"],\"locationCode\":\"UC07300217003\",\"locationName\":null,"
            +   "\"reportType\":null,\"department\":null,\"psm\":null,\"grade\":null,"
            +   "\"filter\":null,\"modifier\":null,\"rawKeywords\":[\"department\",\"managing\",\"UC07300217003\"]}\n\n"

            + "Query: 'show departments for locations in Lo Wu'\n"
            + "Output: {\"intents\":[\"NAME_SEARCH\",\"DEPARTMENT\"],\"locationCode\":null,\"locationName\":\"Lo Wu\","
            +   "\"reportType\":null,\"department\":null,\"psm\":null,\"grade\":null,"
            +   "\"filter\":null,\"modifier\":null,\"rawKeywords\":[\"departments\",\"locations\",\"Lo Wu\"]}\n\n"

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

            + "Return ONLY the JSON object. No explanation. No markdown. /nothink";
    
    private static final String SQL_GENERATE_PROMPT =
            "You are a SQL query generator for a SQL Server database.\n\n"
            + "Generate a single valid T-SQL SELECT query to answer the user's question.\n\n"
            + "AVAILABLE TABLES (copy these names EXACTLY — do not modify them):\n"
            + "- ais.A_GENERAL_INFO           columns: LOC_CD, LOC_NAME, ADDRESS, DEPT_CD, DEPT_DESC, PSM, BLDG_COMPLETION_YEAR\n"
            + "- ais.BSI_GENERAL_INFO         columns: LOC_CD, BLDG_SAFETY_INSP_REPORT_NO, CREATE_TIME\n"
            + "- ais.CS_PLAN                  columns: LOC_CD, FILE_PATH_AUTOCAD\n"
            + "- ais.KAI_RECORD_PLANS_AND_DRAWINGS  columns: LOC_CD, AUTOCAD_PATH\n"
            + "- ais.OLD_EMMS                 columns: LOC_CD, REPORT_LINK\n"
            + "- ais.DSSR_REPORT              columns: LOC_CD, REPORT_NO\n"
            + "- GIS_DB.sde.T_ASD_COMBINED    columns: LOC_CD, DECLR_MONUMT, GRD_HIST_BLDG, BLDG_COMP_YEAR\n"
            + "- ais.A_LOC_CD_CHANGE_HISTORY  columns: FORMER_LOC_CD, CURRENT_LOC_CD\n\n"

            + "⚠️ CRITICAL TABLE NAME WARNING:\n"
            + "The KAI table is: ais.KAI_RECORD_PLANS_AND_DRAWINGS\n"
            + "NOT: KAI_RECORD_PLANS_OR_DRAWINGS\n"
            + "NOT: KAI_RECORD_PLANS_TO_DRAWINGS\n"
            + "NOT: KAI_PLANS_AND_DRAWINGS\n"
            + "Copy it EXACTLY as shown above.\n\n"

            + "CRITICAL FILTERING RULES (LOC_CD vs DEPT_CD):\n"
            + "- LOC_CD (Location Code): Always an 11 to 15 character alphanumeric code (e.g., UC07300217003, SB04400361000). If the query contains a code like this, filter by g.LOC_CD = 'CODE'.\n"
            + "- DEPT_CD (Department Code): Always a short alphabetic acronym (e.g., LCSD, AFCD, HD, DSD). Never filter g.DEPT_CD by an 11-digit location code.\n"
            + "- If the user asks for the department or PSM managing a specific location code, SELECT g.LOC_CD, g.LOC_NAME, g.ADDRESS, g.DEPT_CD, g.DEPT_DESC, g.PSM and filter WHERE g.LOC_CD = 'CODE'.\n\n"

            + "RULES:\n"
            + "1. Only generate SELECT statements — never INSERT, UPDATE, DELETE, DROP.\n"
            + "2. Always include TOP 50 unless user asks for a count.\n"
            + "3. Start FROM ais.A_GENERAL_INFO g — this is your main table.\n"
            + "4. For location name filter: UPPER(g.LOC_NAME) LIKE '%KEYWORD%' OR UPPER(g.ADDRESS) LIKE '%KEYWORD%'\n"
            + "5. If NO location name is mentioned, do NOT add any location filter.\n"
            + "6. To check if a report exists use EXISTS subquery.\n"
            + "7. Return ONLY the raw SQL. No explanation. No markdown. No comments.\n\n"

            + "EXAMPLES:\n\n"

            + "Question: Show department managing UC07300217003\n"
            + "SQL: SELECT TOP 50 g.LOC_CD, g.LOC_NAME, g.ADDRESS, g.DEPT_CD, g.DEPT_DESC, g.PSM "
            +   "FROM ais.A_GENERAL_INFO g "
            +   "WHERE UPPER(g.LOC_CD) = 'UC07300217003'\n\n"

            + "Question: show managing department of lo wu\n"
            + "SQL: SELECT TOP 50 g.LOC_CD, g.LOC_NAME, g.ADDRESS, g.DEPT_CD, g.DEPT_DESC, g.PSM "
            +   "FROM ais.A_GENERAL_INFO g "
            +   "WHERE (UPPER(g.LOC_NAME) LIKE '%LO WU%' OR UPPER(g.ADDRESS) LIKE '%LO WU%') "
            +   "ORDER BY g.LOC_NAME\n\n"

            + "Question: any location code has all 5 reports?\n"
            + "SQL: SELECT TOP 50 g.LOC_CD, g.LOC_NAME, g.ADDRESS "
            +   "FROM ais.A_GENERAL_INFO g "
            +   "WHERE EXISTS (SELECT 1 FROM ais.BSI_GENERAL_INFO b WHERE b.LOC_CD = g.LOC_CD) "
            +   "AND EXISTS (SELECT 1 FROM ais.CS_PLAN c WHERE c.LOC_CD = g.LOC_CD) "
            +   "AND EXISTS (SELECT 1 FROM ais.KAI_RECORD_PLANS_AND_DRAWINGS k WHERE k.LOC_CD = g.LOC_CD) "
            +   "AND EXISTS (SELECT 1 FROM ais.OLD_EMMS e WHERE e.LOC_CD = g.LOC_CD) "
            +   "AND EXISTS (SELECT 1 FROM ais.DSSR_REPORT d WHERE d.LOC_CD = g.LOC_CD) "
            +   "ORDER BY g.LOC_NAME\n\n"

            + "Question: how many LCSD locations have BSI report?\n"
            + "SQL: SELECT COUNT(*) AS total "
            +   "FROM ais.A_GENERAL_INFO g "
            +   "WHERE UPPER(LTRIM(RTRIM(g.DEPT_CD))) = 'LCSD' "
            +   "AND EXISTS (SELECT 1 FROM ais.BSI_GENERAL_INFO b WHERE b.LOC_CD = g.LOC_CD)\n\n"

            + "/nothink";
    
    public OllamaService() {
        this.httpClient = buildHttpClient();
        this.mapper = new ObjectMapper();
        this.mcpClient = new MCPClientService();
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
                    log.info("💾 Remembered {} codes for session {}",
                            codes.size(), sessionId);
                }
            }
        } catch (Exception ignored) {
        }
    }

    public List<String> getLastResults(String sessionId) {
        List<String> codes = LAST_RESULTS.get(sessionId);
        return codes != null ? codes : new ArrayList<String>();
    }

    // ── Clear memory when user changes topic ─────────────────────────
    private void clearMemory(String sessionId) {
        if (LAST_RESULTS.containsKey(sessionId)) {
            log.info("🗑️ Clearing memory for session {} (new unrelated topic)",
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

        html.append("<h3 class='data-title'>📍 ")
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
    // Signals that indicate a complex/reasoning query
    private static final Pattern COMPLEX_QUERY_PATTERN = Pattern.compile(
            "(?i)\\b("
            + // Question words that imply reasoning
            "which|what|who|where|why|how|"
            + // Comparative/superlative
            "oldest|newest|latest|earliest|most|least|highest|lowest|"
            + // Logical combiners
            "and|both|also|as well|combination|combined|"
            + // Conditional / filter words
            "has|have|with|without|that|whose|managed by|belongs to|"
            + // Multi-step trigger words
            "then|after|based on|from the|using the"
            + ")\\b"
    );

    // Signals that indicate a simple name lookup
    private static final Pattern SIMPLE_NAME_PATTERN = Pattern.compile(
            "(?i)^(info of|tell me about|search for|find|show me|what is|get|lookup|"
            + "details of|details for|about|show|info)\\s+[a-zA-Z\\s]+$"
    );

    private boolean isComplexQuery(String prompt) {
        // If it looks like a simple name request → not complex
        if (SIMPLE_NAME_PATTERN.matcher(prompt.trim()).matches()) {
            log.info("📝 Simple name pattern detected: '{}'", prompt);
            return false;
        }

        // Count how many complex signals appear
        Matcher m = COMPLEX_QUERY_PATTERN.matcher(prompt);
        int signalCount = 0;
        while (m.find()) {
            signalCount++;
        }

        // If 2+ complex signals → needs LLM
        boolean complex = signalCount >= 2;
        log.info("📊 Complexity signals: {} → complex={}", signalCount, complex);
        return complex;
    }

    // ── Pattern: detect "show PSM", "list PSMs", etc. ────────────────
    private static final Pattern PSM_LIST_PATTERN = Pattern.compile(
            "(?i)\\b(list|show|all|distinct|how many)\\b.*\\bPSM[s]?\\b"
    );
    // ── Pattern: detect "PSM/something" directly ─────────────────────
    private static final Pattern PSM_DIRECT_PATTERN = Pattern.compile(
            "(?i).*\\bPSM/(.+)"
    );
    // ── Pattern: detect monument queries ─────────────────────────────
    private static final Pattern MONUMENT_PATTERN = Pattern.compile(
            "(?i)\\b(show|list|find|get|display|search)\\b.*\\b(declared?\\s*monument|monument)s?\\b"
    );

    // ── Pattern: detect historic building queries ────────────────────
    private static final Pattern HISTORIC_PATTERN = Pattern.compile(
            "(?i)\\b(show|list|find|get|display|search)\\b.*\\b(historic|graded?)\\s*(building|bldg)s?\\b"
    );

    // ── Pattern: detect department queries ───────────────────────────
    private static final Pattern DEPT_PATTERN = Pattern.compile(
            "(?i)\\b(show|list|find|get|display)\\b.*\\b(department|dept)\\s+([A-Z]{2,10})\\b"
    );

    // ── Pattern: detect location code change/history requests ─────────
    private static final Pattern CODE_HISTORY_PATTERN = Pattern.compile(
            "(?i)("
            + "location\\s+code\\s+history|"
            + "loc\\s*cd\\s+history|"
            + "code\\s+history|"
            + "change\\s+history|"
            + "code\\s+change|"
            + "former\\s+code|"
            + "previous\\s+code|"
            + "old\\s+code|"
            + "new\\s+code|"
            + "current\\s+code"
            + ")"
    );

    private final QueryPlanner planner = new QueryPlanner();

    // ══════════════════════════════════════════════════════════════
    // MAIN ENTRY POINT
    // ══════════════════════════════════════════════════════════════
    public AgentResult invoke(String userPrompt, String sessionId) throws IOException {
    	AgentResult result = new AgentResult();
        result.setPrompt(userPrompt);
        long t0 = System.currentTimeMillis();

        // ── Phase 0: Resolve referential prompts from session memory ──────
        String resolvedPrompt = resolveReferentialPrompt(userPrompt, sessionId);
        if (!resolvedPrompt.equals(userPrompt)) {
            log.info("🔗 Resolved '{}' → '{}'", userPrompt, resolvedPrompt);
            userPrompt = resolvedPrompt;
            result.setPrompt(userPrompt);
        }

        // ── Phase 1: Keyword extraction ───────────────────────────────
        log.info("🔑 Extracting keywords...");
        ExtractedKeywords keywords = extractKeywords(userPrompt);
        log.info("⏱️ Phase 1: {}ms", System.currentTimeMillis() - t0);

        // ── Phase 2: Query planner ────────────────────────────────────
        Plan plan = planner.analyse(userPrompt, keywords);
        log.info("📋 Plan: needsLlm={} steps={} reason={}",
                plan.needsLlm, plan.steps, plan.reason);

        // ── Phase 3: Fast-path ────────────────────────────────────────
        if (!plan.needsLlm && !plan.steps.isEmpty()) {
            String answer = executePlan(plan, result, sessionId);
            result.setAnswer(answer);
            log.info("⏱️ Total: {}ms (fast-path)", System.currentTimeMillis() - t0);
            return result;
        }

        // ── Phase 4: Skip agent loop for SQL-generation candidates ────
        boolean skipAgentLoop = keywords != null && (
                keywords.hasIntent("UNKNOWN")
                || keywords.hasIntent("SQL_QUERY")
                || keywords.isCompound()          // multi-intent → SQL
        );

        if (skipAgentLoop) {
            log.info("⚡ {} → generating SQL directly",
                    keywords.isCompound() ? "compound " + keywords.getIntents()
                                          : keywords.primaryIntent());
            try {
                String sqlResult = generateAndExecuteSql(userPrompt, keywords);
                if (!isUselessSqlResult(sqlResult)) {
                    String html = "<p class='answer-summary'>"
                            + "💡 Answered using a generated database query:"
                            + "</p>" + formatAsHtml(sqlResult);
                    result.setAnswer(html);
                    log.info("⏱️ Total: {}ms (SQL gen)", System.currentTimeMillis() - t0);
                    return result;
                }
                log.warn("⚠️ SQL gen empty → falling back to agent loop");
            } catch (Exception e) {
                log.error("SQL gen failed: {}", e.getMessage());
            }
        }

        // ── Phase 5: Agent loop (last resort) ─────────────────────────
        log.info("🤖 Entering agent loop: {}", plan.reason);
        AgentResult agentResult = runAgentLoop(userPrompt, keywords, result, sessionId);

        // ── Phase 6: Post-agent SQL fallback ──────────────────────────
        if (isEmptyResult(agentResult.getAnswer())) {
            log.info("🔄 Agent empty → SQL fallback");
            try {
                String sqlResult = generateAndExecuteSql(userPrompt, keywords);
                if (!isUselessSqlResult(sqlResult)) {
                    String html = "<p class='answer-summary'>"
                            + "💡 Answered using a generated database query:"
                            + "</p>" + formatAsHtml(sqlResult);
                    agentResult.setAnswer(html);
                }
            } catch (Exception e) {
                log.error("SQL fallback failed: {}", e.getMessage());
            }
        }

        log.info("⏱️ Total: {}ms", System.currentTimeMillis() - t0);
        return agentResult;
    }


    // ── Check if SQL result itself is useless ────────────────────────
    private boolean isUselessSqlResult(String sqlResult) {
        if (sqlResult == null || sqlResult.trim().isEmpty()) {
            return true;
        }
        String lower = sqlResult.toLowerCase();
        // If SQL returned 0 locations or an error, treat as useless so we fall back to the Agent Loop!
        if (lower.contains("found 0 location") || lower.contains("no matching locations found") || lower.contains("error")) {
            return true;
        }
        return false;
    }

    // ── Check if agent result is empty or just "No results found" ────
    private boolean isEmptyResult(String html) {
        if (html == null || html.trim().isEmpty()) return true;

        // Strip HTML tags
        String text = html.replaceAll("<[^>]+>", "").trim().toLowerCase();

        if (text.isEmpty()) return true;

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
                log.info("🔄 Non-answer pattern '{}' → SQL generation", pattern);
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
        while (zeroMatcher.find()) zeroCount++;

        // If 3+ "0 of X" results, the agent is clearly going in circles
        if (zeroCount >= 3) {
            log.info("🔄 Detected {} zero-result patterns → SQL generation", zeroCount);
            return true;
        }

        // ── No meaningful data structure ──────────────────────────────
        boolean hasTable   = html.contains("<table");
        boolean hasCode    = html.contains("<code");
        boolean hasSummary = html.contains("answer-summary");

        if (hasSummary && !hasTable && !hasCode) {
            log.info("🔄 Summary only, no data → SQL generation");
            return true;
        }

        return false;
    }

    // Keep old signature for backward compatibility
    public AgentResult invoke(String userPrompt) throws IOException {
        return invoke(userPrompt, "default");
    }

    // ══════════════════════════════════════════════════════════════
    // OLLAMA HTTP CALL
    // ══════════════════════════════════════════════════════════════
    // ── Agent loop — add think=false to every call ────────────────────
    private JsonNode callOllama(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String systemPrompt) throws IOException {

        List<Map<String, Object>> fullMessages = new ArrayList<Map<String, Object>>();

        // System message
        Map<String, Object> sys = new HashMap<String, Object>();
        sys.put("role", "system");
        sys.put("content", systemPrompt);
        fullMessages.add(sys);
        fullMessages.addAll(messages);

        String requestBody = buildRequest(fullMessages, tools);
        log.debug("Ollama request: {}", requestBody);

        Request httpReq = new Request.Builder()
                .url(OLLAMA_URL)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, JSON_TYPE))
                .build();

        Response httpResp = null;
        try {
            httpResp = httpClient.newCall(httpReq).execute();

            if (!httpResp.isSuccessful()) {
                String errorBody = httpResp.body() != null ? httpResp.body().string() : "no body";
                log.error("Ollama HTTP error {}: {}", httpResp.code(), errorBody);
                throw new IOException("Ollama HTTP error: " + httpResp.code() + " — " + errorBody);
            }

            String body = httpResp.body().string();
            log.debug("Ollama raw response: {}", body);

            JsonNode root = mapper.readTree(body);

            // ── Guard: log if thinking leaked through anyway ──────────
            String rawContent = root.path("message").path("content").asText("");
            if (rawContent.contains("<think>")) {
                log.warn("⚠️ LLM is still thinking despite think=false. "
                        + "Stripping tags. Consider upgrading Ollama.");
            }

            return root.path("message");

        } finally {
            if (httpResp != null) {
                httpResp.close();
            }
        }
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
                return "<div class='error-box'>❌ "
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
                    .append("<p class='answer-summary'>ℹ️ ")
                    .append(escapeHtml(summary))
                    .append("</p>")
                    .append("</div>");
            return html.toString();
        }

        // found=true: show as result card
        html.append("<div class='answer-box'>");
        html.append("<p class='answer-summary'>✅ ")
                .append(escapeHtml(summary))
                .append("</p>");

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
    private String formatBulkReportCheck(JsonNode node) {
        String reportType = node.path("reportType").asText("");
        String reportName = node.path("reportName").asText("Report");
        int total = node.path("totalChecked").asInt(0);
        int withCount = node.path("withReportCount").asInt(0);

        StringBuilder html = new StringBuilder();

        html.append("<h3 class='data-title'>📊 ")
                .append(escapeHtml(reportName))
                .append(" Availability</h3>");

        html.append("<p class='answer-summary'>")
                .append("<strong>").append(withCount).append("</strong> of ")
                .append("<strong>").append(total).append("</strong> ")
                .append("locations have a ").append(escapeHtml(reportType))
                .append(" report available.</p>");

        // ── Available reports ───────────────────────────────────────
        JsonNode withReport = node.path("withReport");
        if (withReport.isArray() && withReport.size() > 0) {
            html.append("<div class='reports-grid'>");

            for (JsonNode item : withReport) {
                String code = item.path("LOC_CD").asText("");
                String name = item.path("LOC_NAME").asText("").trim();
                String url = item.path("url").asText("");

                html.append("<div class='report-card report-available'>");
                html.append("<div class='report-name'>")
                        .append("<strong>").append(escapeHtml(name)).append("</strong>")
                        .append("<br/>")
                        .append("<code>").append(escapeHtml(code)).append("</code>")
                        .append("</div>");

                if (url != null && !url.isEmpty() && !"null".equals(url)) {
                    html.append("<a href='").append(escapeHtml(url)).append("'")
                            .append(" target='_blank' rel='noopener noreferrer'")
                            .append(" class='report-link'>")
                            .append("📄 Open ").append(escapeHtml(reportType))
                            .append(" Report</a>");
                } else {
                    html.append("<span class='report-link-unavailable'>")
                            .append("⚠️ Report exists but link unavailable")
                            .append("</span>");
                }
                html.append("</div>");
            }
            html.append("</div>");
        }

        // ── Locations WITHOUT this report (collapsible) ─────────────
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

        html.append("<h3 class='data-title'>🔍 Found ")
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
            if (col.equalsIgnoreCase("LOC_CD")) headerLabel = "Code";
            else if (col.equalsIgnoreCase("LOC_NAME")) headerLabel = "Name";
            else if (col.equalsIgnoreCase("ADDRESS")) headerLabel = "Address";
            else if (col.equalsIgnoreCase("DEPT_CD")) headerLabel = "Dept Code";
            else if (col.equalsIgnoreCase("DEPT_DESC")) headerLabel = "Dept Desc";
            else if (col.equalsIgnoreCase("PSM")) headerLabel = "PSM";
            else headerLabel = formatLabel(col); // Fallback to your Title Case helper

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
                .append("💡 Tip: Use the code (e.g., '")
                .append(escapeHtml(firstItem.path("LOC_CD").asText()))
                .append("') to get full details.")
                .append("</p>");
        }

        return html.toString();
    }

    // ── Format single location detail (your existing code) ───────────
    private String formatSingleLocation(JsonNode node) {
        StringBuilder html = new StringBuilder();

        boolean isSlope    = node.path("isSlope").asBoolean(false);
        boolean isMonument = node.path("isMonument").asBoolean(false);
        String  grade      = node.path("historicGrade").asText("");

        JsonNode general = node.has("general") ? node.path("general") : node;
        String title = general.path("LOC_NAME").asText("").trim();
        String code  = general.path("LOC_CD").asText("").trim();

        // ── Title ─────────────────────────────────────────────────────────
        if (!title.isEmpty()) {
            html.append("<h3 class='data-title'>")
                .append(isSlope ? "🏔️ " : isMonument ? "🏛️ " : "📍 ")
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
                    .append("🏛️ Declared Monument</span> ");
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
            if ("LOC_NAME".equals(key) || "LOC_CD".equals(key)) continue;
            JsonNode value = entry.getValue();
            if (value.isNull()) continue;
            String valStr = value.asText().trim();
            if (valStr.isEmpty()) continue;

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
                .append("🏔️ Slope Inspection Reports</h3>");

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
                        .append("📄 ").append(escapeHtml(fileId))
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
                .append("📋 TMCP / TMIS Forms</h3>");

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
                            .append("📄 ").append(escapeHtml(displayText));

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
                .append("📊 Available Reports</h3>");

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
                            .append("📄 Open Report</a>");
                } else {
                    // URL could not be built (e.g., DSSR date parse failed)
                    html.append("<span class='report-link-unavailable'>")
                            .append("⚠️ Report exists (ID: ")
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
                        .append("❌ No report available")
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
            if (keywords.getLocationName() != null)
                content.append(", location=").append(keywords.getLocationName());
            if (keywords.getLocationCode() != null)
                content.append(", code=").append(keywords.getLocationCode());
            if (keywords.getModifier() != null)
                content.append(", modifier=").append(keywords.getModifier());
            if (keywords.getDepartment() != null)
                content.append(", dept=").append(keywords.getDepartment());
            if (keywords.getGrade() != null)
                content.append(", grade=").append(keywords.getGrade());
            if (keywords.getFilter() != null)
                content.append(", filter=").append(keywords.getFilter());
            if (keywords.getReportType() != null)
                content.append(", reportType=").append(keywords.getReportType());
            if (keywords.getRawKeywords() != null
                    && !keywords.getRawKeywords().isEmpty())
                content.append(", keywords=")
                       .append(String.join(", ", keywords.getRawKeywords()));
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
        body.put("model", MODEL);
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
            if (toolCalls.isEmpty()) return "";
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
    }

    // ── Render PSM list (collapsible) ───────────────────────────────
    private String formatPsmList(JsonNode node) {
        int totalPsms = node.path("totalPsms").asInt(0);
        int totalLocations = node.path("totalLocations").asInt(0);
        JsonNode psms = node.path("psms");

        StringBuilder html = new StringBuilder();

        html.append("<h3 class='data-title'>👥 Property Service Managers (PSM)</h3>");

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
                .append("📋 Show ").append(totalPsms).append(" PSM(s)")
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
                .append("💡 Tip: Ask <em>\"show locations under PSM/SHA TIN EAST\"</em> ")
                .append("to see locations for a specific PSM.</p>");

        return html.toString();
    }

    // ── Render locations under a specific PSM ───────────────────────
    private String formatLocationsByPsm(JsonNode node) {
        String psm = node.path("psm").asText("").trim();
        int count = node.path("count").asInt(0);
        JsonNode results = node.path("results");

        StringBuilder html = new StringBuilder();

        html.append("<h3 class='data-title'>📍 Locations under ")
                .append(escapeHtml(psm))
                .append("</h3>");

        html.append("<p class='answer-summary'>")
                .append("Found <strong>").append(count).append("</strong> location(s)")
                .append(count >= 50 ? " (showing first 50)" : "")
                .append(".</p>");

        if (count == 0) {
            html.append("<p>No locations found under this PSM.</p>");
            return html.toString();
        }

        // ── Collapsible: count visible by default, list hidden ──────
        html.append("<details class='psm-locations-details'>");
        html.append("<summary class='psm-locations-summary'>")
                .append("📂 Show ").append(count).append(" location(s)")
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

        html.append("<h3 class='data-title'>🏢 Locations for Department: ")
                .append(escapeHtml(deptCd)).append("</h3>");

        html.append("<p class='answer-summary'>")
                .append("Found <strong>").append(count).append("</strong> location(s)")
                .append(count >= 50 ? " (showing first 50)" : "")
                .append(".</p>");

        if (count == 0) {
            html.append("<p>No locations found for this department.</p>");
            return html.toString();
        }

        html.append("<details class='psm-locations-details' open>");
        html.append("<summary class='psm-locations-summary'>")
                .append("📂 Show ").append(count).append(" location(s)")
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

        html.append("<h3 class='data-title'>🏛️ Declared Monuments</h3>");
        html.append("<p>Found ").append(count).append(" location(s)");
        if (count > 50) html.append(" (showing first 50)");
        html.append(".</p>");

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
            if (shown >= 50) break;

            String code    = item.path("LOC_CD").asText("");
            String name    = item.path("LOC_NAME").asText("").trim();
            String address = item.path("ADDRESS").asText("").trim();
            String monFlag = item.path("DECLR_MONUMT").asText("");
            String grade   = item.path("GRD_HIST_BLDG").asText("N/A");

            String monDisplay = "T".equals(monFlag) ? "✅ Yes"
                              : "F".equals(monFlag) ? "❌ No" : monFlag;

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

        html.append("<h3 class='data-title'>🏰 ").append(escapeHtml(gradeLabel))
                .append("</h3>");

        html.append("<p class='answer-summary'>")
                .append("Found <strong>").append(count).append("</strong> location(s)")
                .append(count >= 50 ? " (showing first 50)" : "")
                .append(".</p>");

        if (count == 0) {
            html.append("<p>No matching locations found.</p>");
            return html.toString();
        }

        html.append("<details class='psm-locations-details' open>");
        html.append("<summary class='psm-locations-summary'>")
                .append("📂 Show ").append(count).append(" location(s)")
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

        html.append("<h3 class='data-title'>📜 Location Code Change History</h3>");

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
                .append("💡 Tip: Use the current code to look up full location details.")
                .append("</p>");

        return html.toString();
    }

    // ── Extract CURRENT_LOC_CD values from search_loc_cd_history result ──
    private List<String> extractCurrentCodesFromHistory(String historyJson) {
        LinkedHashSet<String> codes = new LinkedHashSet<String>();

        try {
            JsonNode node = mapper.readTree(historyJson);
            JsonNode results = node.path("results");

            if (results.isArray()) {
                for (JsonNode item : results) {
                    String currentCd = item.path("CURRENT_LOC_CD").asText("").trim();
                    if (!currentCd.isEmpty()) {
                        codes.add(currentCd.toUpperCase());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to extract current codes from history result: {}", e.getMessage());
        }

        return new ArrayList<String>(codes);
    }

    // ══════════════════════════════════════════════════════════════
    // BUILD REASONING CHAIN BANNER (multi-step)
    // Shows a collapsible timeline of what the agent did
    // ══════════════════════════════════════════════════════════════
    private String buildReasoningBanner(List<String> steps) {
        StringBuilder html = new StringBuilder();

        html.append("<details class='agent-reasoning-chain' open>");
        html.append("<summary class='reasoning-summary'>")
                .append("🤖 Agent used <strong>")
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
        stripped = stripped.replaceAll("(?s)<\\|im_thinking\\|>.*?<\\|/im_thinking\\|>", "").trim();

        return stripped;
    }

    // ── Build a minimal tool list for the agent loop ─────────────────
    private List<Map<String, Object>> getRelevantTools(
            String userPrompt,
            ExtractedKeywords kw) {

        String p = userPrompt.toUpperCase();
        List<Map<String, Object>> all = mcpClient.listTools();
        List<Map<String, Object>> filtered = new ArrayList<Map<String, Object>>();

        for (Map<String, Object> tool : all) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fn = (Map<String, Object>) tool.get("function");
            String name = fn.get("name").toString();

            boolean include
                    = "hardcode_query".equals(name)
                    || "search_by_name".equals(name)
                    || "search_loc_cd_history".equals(name);

            // ── Boost from raw prompt ─────────────────────────────────
            if (p.contains("PSM")) {
                include |= "list_psms".equals(name) || "locations_by_psm".equals(name);
            }
            if (p.contains("BSI") || p.contains("CSR") || p.contains("KAI")
                    || p.contains("EMMS") || p.contains("DSSR") || p.contains("REPORT")) {
                include |= "check_reports".equals(name);
            }
            if (p.contains("HISTORIC") || p.contains("GRADE")) {
                include |= "search_historic_building".equals(name);
            }
            if (p.contains("MONUMENT")) {
                include |= "search_declared_monument".equals(name);
            }
            if (p.contains("DEPT") || p.contains("DEPARTMENT")) {
                include |= "locations_by_dept".equals(name);
            }

            // ── Boost from LLM keywords (more accurate) ───────────────
            if (kw != null) {
            	 // ── Compound or unknown → include all tools ───────────────
                if (kw.isCompound() || kw.hasIntent("UNKNOWN")) {
                    include = true;
                } else {
                    String primary = kw.primaryIntent();
                    switch (primary) {
	                    case "MONUMENT":
	                        include |= "search_declared_monument".equals(name);
	                        break;
	                    case "HISTORIC":
	                        include |= "search_historic_building".equals(name);
	                        break;
	                    case "DEPARTMENT":
	                        include |= "locations_by_dept".equals(name);
	                        break;
	                    case "PSM":
	                        include |= "list_psms".equals(name)
	                                || "locations_by_psm".equals(name);
	                        break;
	                    case "REPORT":
	                        include |= "check_reports".equals(name);
	                        break;
	                    case "CODE_HISTORY":
	                        include |= "search_loc_cd_history".equals(name);
	                        break;
	                    default:
	                        break;
	                }
                }

	            if (include) {
	                Map<String, Object> clean = new LinkedHashMap<String, Object>();
	                clean.put("type", tool.get("type"));
	                clean.put("function", tool.get("function"));
	                filtered.add(clean);
	            }
	        }
        }
        return filtered;
    }
    // ── Keep old single-param signature for backward compatibility ──
    private List<Map<String, Object>> getRelevantTools(String userPrompt) {
        return getRelevantTools(userPrompt, null);
    }

    // ══════════════════════════════════════════════════════════════
    // PLAN EXECUTOR
    // Runs all steps in order. Steps can depend on previous results
    // via codesSource markers.
    // ══════════════════════════════════════════════════════════════
    private String executePlan(Plan plan,
            AgentResult result,
            String sessionId) throws IOException {

        StringBuilder html = new StringBuilder();
        List<String> lastCodes = new ArrayList<String>();
        String lastToolResult = null;

        for (Intent intent : plan.steps) {
            log.info("▶ Executing intent: {}", intent);

            // ══════════════════════════════════════════════════════════
            // STEP 1: Build args
            // ══════════════════════════════════════════════════════════
            Map<String, Object> args = buildArgs(intent, lastToolResult, lastCodes, sessionId);
            if (args == null) {
                html.append("<p class='answer-summary'>"
                        + "⚠️ No location codes available to check reports for.</p>");
                continue;
            }

            // ══════════════════════════════════════════════════════════
            // STEP 2: Resolve tool name
            // ══════════════════════════════════════════════════════════
            String toolName = resolveToolName(intent.type);
            if (toolName == null) {
                log.warn("⚠️ No tool mapped for intent: {}", intent.type);
                continue;
            }

            // ══════════════════════════════════════════════════════════
            // STEP 3: Call tool
            // ══════════════════════════════════════════════════════════
            log.info("🔧 Tool: {} args: {}", toolName, args);
            String previousToolResult = lastToolResult;  // save before overwrite
            String r = mcpClient.callTool(toolName, args);
            result.addToolCall(toolName, args, r);
            lastToolResult = r;

            // ══════════════════════════════════════════════════════════
            // STEP 4: Post-call modifiers (OLDEST / NEWEST / FIRST)
            // ══════════════════════════════════════════════════════════
            r = applyModifier(intent, r, result);

            // ══════════════════════════════════════════════════════════
            // STEP 4.5: Cross-filter with previous step result
            // Activated by crossFilterWith=previous in intent params
            // Works for any compound intent — not hardcoded per pair
            // ══════════════════════════════════════════════════════════
            if ("true".equals(intent.params.get("enrich"))
                    && previousToolResult != null) {
                log.info("🔗 Enriching previous results with '{}' data", intent.type);
                String gradeFilter = intent.params.get("grade"); // e.g., "1", "ALL", "GRADED"
                r = enrichWithHistoricGrade(previousToolResult, r, gradeFilter);
            }

            // ══════════════════════════════════════════════════════════
            // STEP 5: Update state
            // ══════════════════════════════════════════════════════════
            lastCodes = extractCodesFromResult(r);

            // Also extract CURRENT_LOC_CD from history results
            if (lastCodes.isEmpty() && Intent.CODE_HISTORY.equals(intent.type)) {
                lastCodes = extractCurrentCodesFromHistory(r);
                log.info("🔗 Extracted {} current code(s) from history result", lastCodes.size());
            }

            if (!lastCodes.isEmpty()) {
                LAST_RESULTS.put(sessionId, lastCodes);
                log.info("💾 Saved {} codes to session memory", lastCodes.size());
            }

            // ── STEP 6: Render HTML ───────────────────────────────────────────────
            boolean isEnrichStep = "true".equals(intent.params.get("enrich"));

            if (isEnrichStep) {
                // Replace previous HTML with enriched result
                html.setLength(0);
                html.append(formatAsHtml(r));
            } else {
                html.append(formatAsHtml(r));
            }

            // ══════════════════════════════════════════════════════════
            // STEP 7: Post-step chain actions
            // ══════════════════════════════════════════════════════════
            html.append(runPostStepChains(intent, r, lastCodes, result, sessionId));
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

    // ── Extract all LOC_CD values from a tool result ─────────────────
    private List<String> extractCodesFromResult(String toolResult) {
        List<String> codes = new ArrayList<String>();
        try {
            JsonNode node = mapper.readTree(toolResult);
            JsonNode results = node.path("results");
            if (results.isArray()) {
                LinkedHashSet<String> seen = new LinkedHashSet<String>();
                for (JsonNode item : results) {
                    String cd = item.path("LOC_CD").asText("").trim();
                    if (!cd.isEmpty()) {
                        seen.add(cd.toUpperCase());
                    }
                }
                codes.addAll(seen);
            }
        } catch (Exception e) {
            log.error("extractCodesFromResult error: {}", e.getMessage());
        }
        return codes;
    }

    // ── Cross-filter: keep only results that appear in reference set ──
    private String crossFilter(String firstResult,
            String secondResult) {

        try {
            JsonNode firstNode = mapper.readTree(firstResult);
            JsonNode secondNode = mapper.readTree(secondResult);

            JsonNode firstArr = firstNode.path("results");
            JsonNode secondArr = secondNode.path("results");

            if (!firstArr.isArray() || !secondArr.isArray()) {
                return secondResult;
            }

            // Build LOC_CD set from first result
            Set<String> firstCodes = new LinkedHashSet<String>();
            for (JsonNode item : firstArr) {
                String cd = item.path("LOC_CD").asText("").trim().toUpperCase();
                if (!cd.isEmpty()) firstCodes.add(cd);
            }

            List<JsonNode> filtered = new ArrayList<JsonNode>();
            for (JsonNode item : secondArr) {
                String cd = item.path("LOC_CD").asText("").trim().toUpperCase();
                if (firstCodes.contains(cd)) {
                    filtered.add(item);
                }
            }

            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("count", filtered.size());
            response.put("results", filtered);

            // preserve useful flags from second result
            if (secondNode.has("grade")) {
                response.put("grade", secondNode.path("grade").asText(""));
            }
            if (firstNode.has("filter")) {
                response.put("filter", firstNode.path("filter").asText(""));
            }

            return mapper.writeValueAsString(response);

        } catch (Exception e) {
            log.error("crossFilter error: {}", e.getMessage());
            return secondResult;
        }
    }

    private AgentResult runAgentLoop(
            String userPrompt,
            ExtractedKeywords keywords, // ← ADD THIS
            AgentResult result,
            String sessionId) throws IOException {

        List<Map<String, Object>> messages = buildInitialMessages(userPrompt, keywords);

        // ── Use keywords to boost tool selection ─────────────────────
        List<Map<String, Object>> tools = getRelevantTools(userPrompt, keywords);

        StringBuilder htmlOutput = new StringBuilder();
        List<String> reasoningSteps = new ArrayList<String>();
        int maxIterations = 5;
        
        if (keywords != null && keywords.hasIntent("UNKNOWN")) {
            maxIterations = 2;
            log.info("⚠️ UNKNOWN intent — limiting agent loop to {} iterations",
                    maxIterations);
        }
        
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            log.info("🤖 Agent loop iteration {}", iteration + 1);

            JsonNode llmResponse = callOllama(messages, tools, SYSTEM_PROMPT);
            JsonNode toolCalls = llmResponse.path("tool_calls");
            String content = stripThinkingTags(
                    llmResponse.path("content").asText("").trim());

            if (toolCalls.isMissingNode() || toolCalls.isEmpty()) {
                log.info("🏁 Agent loop done after {} iteration(s)", iteration + 1);
                if (!content.isEmpty()) {
                    htmlOutput.insert(0,
                            "<p class='answer-summary'>" + escapeHtml(content) + "</p>");
                }
                break;
            }

            if (!content.isEmpty()) {
                reasoningSteps.add(content);
            }

            Map<String, Object> assistantMsg = new LinkedHashMap<String, Object>();
            assistantMsg.put("role", "assistant");
            if (!content.isEmpty()) {
                assistantMsg.put("content", content);
            }
            assistantMsg.put("tool_calls", mapper.convertValue(toolCalls,
                    mapper.getTypeFactory().constructCollectionType(
                            List.class, Object.class)));
            messages.add(assistantMsg);

            for (JsonNode toolCall : toolCalls) {
                String toolName = toolCall.path("function").path("name").asText();
                JsonNode argsNode = toolCall.path("function").path("arguments");
                Map<String, Object> args = mapper.convertValue(argsNode,
                        mapper.getTypeFactory().constructMapType(
                                Map.class, String.class, Object.class));

                log.info("🎯 LLM SELECTED TOOL  : [{}]", toolName);
                log.info("📥 LLM GENERATED ARGS : {}", args);

                String toolResult = mcpClient.callTool(toolName, args);

                toolResult = postProcess(
                        toolName, args, toolResult, userPrompt, sessionId, result);

                result.addToolCall(toolName, args, toolResult);
                htmlOutput.append(formatAsHtml(toolResult));

                Map<String, Object> toolMsg = new LinkedHashMap<String, Object>();
                toolMsg.put("role", "tool");
                toolMsg.put("content", truncateToolResult(toolResult));
                messages.add(toolMsg);
            }
        }

        if (htmlOutput.length() == 0) {
            htmlOutput.append("<p>No results found.</p>");
        }
        result.setAnswer(htmlOutput.toString());
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
    private String enhanceSearchResult(String toolName,
            Map<String, Object> args,
            String toolResult,
            String sessionId) {
        try {
            JsonNode node = mapper.readTree(toolResult);
            int count = node.path("count").asInt(0);

            // Only enhance if zero results and this was a name search
            if (count > 0 || !"search_by_name".equals(toolName)) {
                return toolResult;
            }

            String locName = args.get("locName") != null
                    ? args.get("locName").toString() : "";

            if (locName.isEmpty()) {
                return toolResult;
            }

            // Strip common prepositions and try the last meaningful word(s)
            String cleaned = locName
                    .replaceAll("(?i)\\b(in|at|near|of|for|the|a|an|which|"
                            + "has|have|with|that|whose|is|are)\\b", " ")
                    .replaceAll("\\s+", " ").trim();

            String[] words = cleaned.split("\\s+");

            // Try progressively shorter suffixes (last 2 words, last 1 word)
            for (int len = Math.min(words.length - 1, 2); len >= 1; len--) {
                String[] slice = Arrays.copyOfRange(
                        words, words.length - len, words.length);
                String candidate = String.join(" ", slice).trim();

                if (candidate.isEmpty()
                        || candidate.equalsIgnoreCase(locName)) {
                    continue;
                }

                log.info("🔄 Zero results for '{}', retrying with '{}'",
                        locName, candidate);

                Map<String, Object> retryArgs = new LinkedHashMap<String, Object>();
                retryArgs.put("locName", candidate);
                String retryResult = mcpClient.callTool("search_by_name", retryArgs);

                JsonNode retryNode = mapper.readTree(retryResult);
                if (retryNode.path("count").asInt(0) > 0) {
                    log.info("✅ Found {} results with '{}'",
                            retryNode.path("count").asInt(0), candidate);
                    return retryResult;
                }
            }

        } catch (Exception e) {
            log.error("enhanceSearchResult error: {}", e.getMessage());
        }

        return toolResult;
    }

    // ══════════════════════════════════════════════════════════════
    // POST-PROCESSOR: Java-side processing after each tool call
    // Filters, enriches, or chains results before feeding to LLM
    // ══════════════════════════════════════════════════════════════
    private String postProcess(String toolName,
            Map<String, Object> args,
            String toolResult,
            String userPrompt,
            String sessionId,
            AgentResult result) {

        String promptUpper = userPrompt.toUpperCase();

        try {
            // ── 1. search_declared_monument or search_historic_building ──
            // If user mentioned a place name, filter results to that place
            if ("search_declared_monument".equals(toolName)
                    || "search_historic_building".equals(toolName)) {

                String locationFilter = extractLocationFromPrompt(userPrompt);
                if (locationFilter != null) {
                    String filtered = filterResultsByLocation(toolResult, locationFilter);
                    log.info("📍 Post-filtered {} results to match '{}'",
                            toolName, locationFilter);

                    // If "oldest" is mentioned, find oldest and fetch details
                    if (promptUpper.contains("OLDEST")
                            || promptUpper.contains("EARLIEST")) {
                        List<String> codes = extractCodesFromResult(filtered);
                        if (!codes.isEmpty()) {
                            log.info("🔍 Finding oldest among {} filtered results", codes.size());
                            return findOldestWithDetails(codes, result);
                        }
                    }
                    return filtered;
                }

                // No location filter but "oldest" is mentioned
                if (promptUpper.contains("OLDEST") || promptUpper.contains("EARLIEST")) {
                    List<String> codes = extractCodesFromResult(toolResult);
                    if (!codes.isEmpty()) {
                        log.info("🔍 Finding oldest among {} results (no location filter)",
                                codes.size());
                        return findOldestWithDetails(codes, result);
                    }
                }
            }

            // ── 2. search_by_name ─────────────────────────────────────
            if ("search_by_name".equals(toolName)) {
                toolResult = enhanceSearchResult(toolName, args, toolResult, sessionId);
                rememberLastResults(sessionId, toolResult);
            }

        } catch (Exception e) {
            log.error("postProcess error for {}: {}", toolName, e.getMessage());
        }

        return toolResult;
    }

    // ── Extract location name from prompt ────────────────────────────
    // e.g., "oldest monument in Lo Wu" → "Lo Wu"
    // e.g., "playground in Sha Tin"    → "Sha Tin"
    private String extractLocationFromPrompt(String prompt) {
        Matcher m = Pattern.compile(
                "(?i)\\b(?:in|at|near|located in|within)\\s+([A-Z][a-zA-Z\\s]{2,25}?)(?:\\s*\\?|,|\\.|$)",
                Pattern.CASE_INSENSITIVE
        ).matcher(prompt);

        if (m.find()) {
            String loc = m.group(1).trim();
            // Remove trailing noise words
            loc = loc.replaceAll("(?i)\\s+(has|have|with|that|which|is|are).*$", "").trim();
            if (!loc.isEmpty()) {
                log.info("📍 Extracted location filter: '{}'", loc);
                return loc;
            }
        }
        return null;
    }

    // ── Find oldest location by BLDG_COMPLETION_YEAR + fetch details ─
    private String findOldestWithDetails(List<String> locCds,
            AgentResult result) throws IOException {

        String oldestCode = null;
        String oldestName = null;
        String oldestDept = null;
        String oldestDeptDesc = null;
        int oldestYear = Integer.MAX_VALUE;

        // Fetch details for each candidate
        for (String locCd : locCds) {
            Map<String, Object> args = map("locCd", locCd);
            String infoResult = mcpClient.callTool("hardcode_query", args);
            result.addToolCall("hardcode_query", args, infoResult);

            try {
                JsonNode info = mapper.readTree(infoResult);
                JsonNode general = info.path("general");

                double yearRaw = general.path("BLDG_COMPLETION_YEAR").asDouble(0);
                int year = (int) yearRaw;
                String name = general.path("LOC_NAME").asText("").trim();
                String dept = general.path("DEPT_CD").asText("").trim();
                String deptDesc = general.path("DEPT_DESC").asText("").trim();

                log.info("  Checking {}: name='{}' year={} dept={}",
                        locCd, name, year, dept);

                if (year > 0 && year < oldestYear) {
                    oldestYear = year;
                    oldestCode = locCd;
                    oldestName = name;
                    oldestDept = dept;
                    oldestDeptDesc = deptDesc;
                }

            } catch (Exception e) {
                log.error("findOldestWithDetails error for {}: {}", locCd, e.getMessage());
            }
        }

        // ── Build response JSON that the LLM can understand ───────────
        Map<String, Object> response = new LinkedHashMap<String, Object>();

        if (oldestCode != null) {
            response.put("found", true);
            response.put("LOC_CD", oldestCode);
            response.put("LOC_NAME", oldestName);
            response.put("DEPT_CD", oldestDept);
            response.put("DEPT_DESC", oldestDeptDesc);
            response.put("YEAR", oldestYear > 0 ? oldestYear : "unknown");
            response.put("summary",
                    "Oldest location: " + oldestName
                    + " (" + oldestCode + ")"
                    + (oldestYear > 0 ? ", completed " + oldestYear : "")
                    + ", managed by: " + (oldestDept.isEmpty() ? oldestDeptDesc : oldestDept));

            log.info("✅ Oldest found: {} | {} | dept={} | year={}",
                    oldestCode, oldestName, oldestDept, oldestYear);
        } else {
            response.put("found", false);
            response.put("summary", "Could not determine oldest — "
                    + "BLDG_COMPLETION_YEAR not available for candidates.");
        }

        return mapper.writeValueAsString(response);
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

            log.info("🔍 filterResultsByLocation: '{}' → {} of {} results match",
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
        boolean isRetry = userPrompt.contains("[Previous answer was flagged");
        String cacheKey = userPrompt.trim().toLowerCase();

        if (!isRetry) {
            // ── 1. CHECK REGEX TEMPLATE GATEWAY (0ms Fast Path) ──
            ExtractedKeywords templateMatch = matchExactTemplatePrompt(userPrompt);
            if (templateMatch != null) {
                return templateMatch;
            }

            // ── 2. CHECK KEYWORD CACHE ──
            ExtractedKeywords cached = kwCache.get(cacheKey);
            if (cached != null) {
                log.info("⚡ Keyword cache hit");
                return cached;
            }
        }

        // ── 3. FALL BACK TO OLLAMA LLM EXTRACTION ──
        ExtractedKeywords result = extractKeywordsFromLlm(userPrompt);
        if (result != null && !isRetry) {
            kwCache.put(cacheKey, result);
        }
        return result;
    }

    // ── PRIVATE: actual LLM call ──────────────────────────────────
    private ExtractedKeywords extractKeywordsFromLlm(String userPrompt) {
        try {
            List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();

            Map<String, Object> userMsg = new LinkedHashMap<String, Object>();
            userMsg.put("role", "user");
            userMsg.put("content", "Extract keywords from: " + userPrompt + " /nothink");
            messages.add(userMsg);

            List<Map<String, Object>> fullMessages = new ArrayList<Map<String, Object>>();
            Map<String, Object> sysMsg = new LinkedHashMap<String, Object>();
            sysMsg.put("role", "system");
            sysMsg.put("content", KEYWORD_EXTRACT_PROMPT);
            fullMessages.add(sysMsg);
            fullMessages.addAll(messages);

            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("model", MODEL);
            body.put("messages", fullMessages);
            body.put("stream", false);
            body.put("temperature", 0.0);
            body.put("num_ctx", 1024);
            body.put("think", false);

            String requestBody = mapper.writeValueAsString(body);
            log.debug("🔑 Keyword extract request: {}", requestBody);

            Request httpReq = new Request.Builder()
                    .url(OLLAMA_URL)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, JSON_TYPE))
                    .build();

            Response httpResp = null;
            try {
                httpResp = httpClient.newCall(httpReq).execute();
                if (!httpResp.isSuccessful()) {
                    log.warn("⚠️ Keyword extract HTTP error: {}", httpResp.code());
                    return null;
                }

                String responseBody = httpResp.body().string();
                log.debug("🔑 Keyword extract response: {}", responseBody);

                JsonNode root = mapper.readTree(responseBody);
                String content = root.path("message").path("content").asText("").trim();
                content = stripThinkingTags(content);
                content = stripMarkdownFences(content);

                log.info("🔑 Extracted keywords JSON: {}", content);

                JsonNode kw = mapper.readTree(content);
                ExtractedKeywords result = new ExtractedKeywords();

                List<String> intentsList = new ArrayList<String>();

                JsonNode intentsNode = kw.path("intents");
                if (intentsNode.isArray()) {
                    for (JsonNode i : intentsNode) {
                        String val = i.asText("").trim().toUpperCase();
                        if (!val.isEmpty()) intentsList.add(val);
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
                
                result.setGrade(sanitizeGrade(result.getGrade()));
                
                List<String> rawList = new ArrayList<String>();
                JsonNode rawKw = kw.path("rawKeywords");
                if (rawKw.isArray()) {
                    for (JsonNode kNode : rawKw) rawList.add(kNode.asText());
                }
                result.setRawKeywords(rawList);

                log.info("✅ Keywords: intents={} location={} modifier={} dept={} grade={} filter={}",
                        result.getIntents(), result.getLocationName(), result.getModifier(),
                        result.getDepartment(), result.getGrade(), result.getFilter());

                return result;

            } finally {
                if (httpResp != null) httpResp.close();
            }

        } catch (Exception e) {
            log.error("❌ Keyword extraction failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Strip ```json ... ``` markdown fences from LLM output ────────
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

    /**
     * Builds the args map for a tool call from intent params. Location filter
     * is injected into args for ALL tools — DB does filtering. Returns null if
     * the intent cannot proceed (e.g. no codes for report check).
     */
    private Map<String, Object> buildArgs(
            Intent intent,
            String lastToolResult,
            List<String> lastCodes,
            String sessionId) {

        Map<String, Object> args = new LinkedHashMap<String, Object>();

        switch (intent.type) {

            case Intent.LOCATION_CODE: {
                String codesSource = intent.params.get("codesSource");

                if ("history".equals(codesSource)) {
                    args.put("_multiSource", "history");
                    args.put("_codes", extractCurrentCodesFromHistory(lastToolResult));

                } else if ("psm_first".equals(codesSource)
                        || "previous_first".equals(codesSource)) {
                    // Pick first code from previous tool result
                    String firstCode = extractFirstCode(lastToolResult);
                    if (firstCode == null) {
                        log.warn("⚠️ codesSource=psm_first but no code in previous result");
                        return null;
                    }
                    args.put("locCd", firstCode);

                } else {
                    // Direct code from params
                    String locCd  = intent.params.get("locCd");
                    String locCds = intent.params.get("locCds");
                    if (locCd  != null) args.put("locCd",      locCd);
                    if (locCds != null) args.put("_multiCodes", locCds);
                }
                break;
            }

            case Intent.NAME_SEARCH: {
                String locName = intent.params.get("locName");
                if (locName != null) args.put("locName", locName);
                String location = intent.params.get("locationFilter");
                if (location != null) args.put("location", location);
                break;
            }

            case Intent.PSM_LIST: {
                // No args needed — lists all PSMs
                break;
            }

            case Intent.PSM_LOCATIONS: {
                String psm = intent.params.get("psm");
                if (psm != null) args.put("psm", psm);
                String location = intent.params.get("locationFilter");
                if (location != null) args.put("location", location);
                break;
            }

            case Intent.CODE_HISTORY: {
                // ── Primary lookup keys ───────────────────────────────────
                String locCd       = intent.params.get("locCd");
                String formerLocCd = intent.params.get("formerLocCd");
                String currentLocCd= intent.params.get("currentLocCd");

                if (locCd != null) {
                    // Search by either former or current — DB handles both
                    args.put("formerLocCd",  locCd);
                    args.put("currentLocCd", locCd);
                }
                if (formerLocCd  != null) args.put("formerLocCd",  formerLocCd);
                if (currentLocCd != null) args.put("currentLocCd", currentLocCd);
                break;
            }

            case Intent.CHECK_REPORTS: {
                String reportType  = intent.params.get("reportType");
                String codesSource = intent.params.get("codesSource");
                String inlineCds   = intent.params.get("locCds");

                List<String> codesToCheck;
                if (codesSource != null && !lastCodes.isEmpty()) {
                    codesToCheck = lastCodes;
                } else if (codesSource != null) {
                    codesToCheck = getLastResults(sessionId);
                } else if (inlineCds != null) {
                    codesToCheck = Arrays.asList(inlineCds.split(","));
                } else {
                    codesToCheck = getLastResults(sessionId);
                }

                if (codesToCheck.isEmpty()) return null;

                if (reportType != null) args.put("reportType", reportType);
                args.put("locCds", codesToCheck);
                break;
            }

            case Intent.DECLARED_MONUMENT: {
                String filter = intent.params.getOrDefault("filter", "T");
                args.put("filter", filter);
                String location = intent.params.get("locationFilter");
                if (location != null) args.put("location", location);
                break;
            }

            case Intent.HISTORIC_BUILDING: {
                String grade = intent.params.getOrDefault("grade", "ALL");
                args.put("grade", grade);
                String location = intent.params.get("locationFilter");
                if (location != null) args.put("location", location);
                break;
            }

            case Intent.DEPARTMENT_LOCATIONS: {
                String deptCd = intent.params.get("deptCd");
                if (deptCd != null) args.put("deptCd", deptCd);
                String location = intent.params.get("locationFilter");
                if (location != null) args.put("location", location);
                break;
            }

            default:
                log.warn("⚠️ Unknown intent type in buildArgs: {}", intent.type);
                break;
        }

        return args;
    }

    /**
     * Maps IntentType → MCP tool name. Single source of truth for all tool name
     * bindings.
     */
    private String resolveToolName(String type) {
    	return Intent.resolveToolName(type);
    }

    /**
     * Applies post-call modifiers to a tool result. OLDEST / EARLIEST → find
     * location with smallest BLDG_COMPLETION_YEAR NEWEST / LATEST → find
     * location with largest BLDG_COMPLETION_YEAR FIRST → take first result only
     * COUNT → return count summary only No modifier → return result unchanged
     */
    private String applyModifier(Intent intent,
            String toolResult,
            AgentResult result) throws IOException {

        String modifier = intent.params.get("modifier");
        if (modifier == null || modifier.isEmpty()) {
            return toolResult; // Nothing to do
        }

        modifier = modifier.toUpperCase();
        log.info("🔀 Applying modifier: {} to {} results",
                modifier, extractCodesFromResult(toolResult).size());

        switch (modifier) {

            case "OLDEST":
            case "EARLIEST": {
                List<String> codes = extractCodesFromResult(toolResult);
                if (codes.isEmpty()) {
                    log.warn("⚠️ OLDEST modifier: no codes to compare");
                    return toolResult;
                }
                log.info("⏳ Finding OLDEST among {} locations", codes.size());
                return findByYear(codes, false, result); // false = find minimum year
            }

            case "NEWEST":
            case "LATEST": {
                List<String> codes = extractCodesFromResult(toolResult);
                if (codes.isEmpty()) {
                    log.warn("⚠️ NEWEST modifier: no codes to compare");
                    return toolResult;
                }
                log.info("⏳ Finding NEWEST among {} locations", codes.size());
                return findByYear(codes, true, result); // true = find maximum year
            }

            case "FIRST": {
                String firstCode = extractFirstCode(toolResult);
                if (firstCode == null) {
                    return toolResult;
                }
                log.info("1️⃣ Taking FIRST result: {}", firstCode);
                Map<String, Object> args = map("locCd", firstCode);
                String r = mcpClient.callTool("hardcode_query", args);
                result.addToolCall("hardcode_query", args, r);
                return r;
            }

            case "COUNT": {
                // Wrap in a count-only response
                List<String> codes = extractCodesFromResult(toolResult);
                log.info("🔢 COUNT modifier: {} results", codes.size());
                try {
                    Map<String, Object> response = new LinkedHashMap<String, Object>();
                    response.put("count", codes.size());
                    response.put("summary", "Found " + codes.size() + " matching location(s).");
                    response.put("found", codes.size() > 0);
                    return mapper.writeValueAsString(response);
                } catch (Exception e) {
                    return toolResult;
                }
            }

            default:
                log.warn("⚠️ Unknown modifier: {}", modifier);
                return toolResult;
        }
    }

    /**
     * Finds location with min (oldest) or max (newest) BLDG_COMPLETION_YEAR.
     * Replaces the old findOldestWithDetails — now handles both directions.
     */
    private String findByYear(List<String> locCds,
            boolean findMax,
            AgentResult result) throws IOException {

        String bestCode = null;
        String bestName = null;
        String bestDept = null;
        String bestDeptDesc = null;
        int bestYear = findMax ? Integer.MIN_VALUE : Integer.MAX_VALUE;

        for (String locCd : locCds) {
            Map<String, Object> args = map("locCd", locCd);
            String infoResult = mcpClient.callTool("hardcode_query", args);
            result.addToolCall("hardcode_query", args, infoResult);

            try {
                JsonNode info = mapper.readTree(infoResult);
                JsonNode general = info.path("general");

                int year = (int) general.path("BLDG_COMPLETION_YEAR").asDouble(0);
                String name = general.path("LOC_NAME").asText("").trim();
                String dept = general.path("DEPT_CD").asText("").trim();
                String deptDesc = general.path("DEPT_DESC").asText("").trim();

                log.info("  {} → name='{}' year={} dept={}", locCd, name, year, dept);

                boolean isBetter = year > 0
                        && (findMax ? year > bestYear : year < bestYear);

                if (isBetter) {
                    bestYear = year;
                    bestCode = locCd;
                    bestName = name;
                    bestDept = dept;
                    bestDeptDesc = deptDesc;
                }

            } catch (Exception e) {
                log.error("findByYear error for {}: {}", locCd, e.getMessage());
            }
        }

        // ── Build structured response ──────────────────────────────────
        Map<String, Object> response = new LinkedHashMap<String, Object>();

        if (bestCode != null) {
            String label = findMax ? "Newest" : "Oldest";
            String managedBy = (bestDept != null && !bestDept.isEmpty())
                    ? bestDept : bestDeptDesc;

            response.put("found", true);
            response.put("LOC_CD", bestCode);
            response.put("LOC_NAME", bestName);
            response.put("DEPT_CD", bestDept);
            response.put("DEPT_DESC", bestDeptDesc);
            response.put("YEAR", bestYear > 0 ? bestYear : "unknown");
            response.put("summary",
                    label + " location: " + bestName
                    + " (" + bestCode + ")"
                    + (bestYear > 0 ? ", completed " + bestYear : "")
                    + ", managed by: " + managedBy);

            log.info("✅ {} found: {} | {} | dept={} | year={}",
                    label, bestCode, bestName, bestDept, bestYear);
        } else {
            response.put("found", false);
            response.put("summary", "Could not determine "
                    + (findMax ? "newest" : "oldest")
                    + " — BLDG_COMPLETION_YEAR not available for candidates.");
        }

        return mapper.writeValueAsString(response);
    }
    
    /**
     * Ask the LLM to generate a SQL query, then execute it.
     * Used as last resort when no tool matches the user's question.
     */
    private String generateAndExecuteSql(String userPrompt,
            ExtractedKeywords keywords) throws IOException {

        log.info("🤖 Generating SQL for: '{}'", userPrompt);

        StringBuilder question = new StringBuilder();
        question.append("Question: ").append(userPrompt);

        if (keywords != null) {
            question.append("\n\nContext clues:");

            // ── Only use locationName if explicitly extracted ──────────
            // Do NOT fall back to raw keywords for location
            // Raw keywords like "all 5 reports" are NOT locations
            if (keywords.getLocationName() != null && !keywords.getLocationName().isEmpty()) {
                question.append("\n- Location filter: ").append(keywords.getLocationName());
            }

            if (keywords.getDepartment() != null)
                question.append("\n- Department: ").append(keywords.getDepartment());
            if (keywords.getReportType() != null)
                question.append("\n- Report types mentioned: ").append(keywords.getReportType());
            if (keywords.getModifier() != null)
                question.append("\n- Modifier: ").append(keywords.getModifier());
            if (keywords.getGrade() != null)
                question.append("\n- Grade: ").append(keywords.getGrade());
            if (keywords.getFilter() != null)
                question.append("\n- Monument filter: ").append(keywords.getFilter());
            if (!keywords.getRawKeywords().isEmpty())
                question.append("\n- Raw keywords: ")
                        .append(String.join(", ", keywords.getRawKeywords()));

            // ── If no context clues at all, say so explicitly ──────────
            if (keywords.getLocationName() == null
                    && keywords.getDepartment() == null
                    && keywords.getReportType() == null
                    && keywords.getModifier() == null) {
                question.append("\n- No location filter — search across ALL locations");
            }
        }

        // ── Call LLM for SQL generation ───────────────────────────────
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        Map<String, Object> userMsg = new LinkedHashMap<String, Object>();
        userMsg.put("role", "user");
        userMsg.put("content", question.toString() + " /nothink");
        messages.add(userMsg);

        // Build request — no tools needed, just text generation
        List<Map<String, Object>> fullMessages = new ArrayList<Map<String, Object>>();
        Map<String, Object> sysMsg = new LinkedHashMap<String, Object>();
        sysMsg.put("role",    "system");
        sysMsg.put("content", SQL_GENERATE_PROMPT);
        fullMessages.add(sysMsg);
        fullMessages.addAll(messages);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("model",       MODEL);
        body.put("messages",    fullMessages);
        body.put("stream",      false);
        body.put("temperature", 0.0);
        body.put("num_ctx",     1500);
        body.put("think",       false);

        String requestBody = mapper.writeValueAsString(body);
        log.debug("🤖 SQL generate request: {}", requestBody);

        Request httpReq = new Request.Builder()
                .url(OLLAMA_URL)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, JSON_TYPE))
                .build();

        Response httpResp = null;
        String generatedSql = null;

        try {
            httpResp = httpClient.newCall(httpReq).execute();
            if (!httpResp.isSuccessful()) {
                log.error("SQL generate HTTP error: {}", httpResp.code());
                return "{\"error\":\"SQL generation failed: HTTP " + httpResp.code() + "\"}";
            }

            String responseBody = httpResp.body().string();
            JsonNode root = mapper.readTree(responseBody);
            generatedSql = root.path("message").path("content").asText("").trim();
            generatedSql = stripThinkingTags(generatedSql);
            generatedSql = stripMarkdownFences(generatedSql);

            log.info("🤖 LLM generated SQL: {}", generatedSql);

        } finally {
            if (httpResp != null) httpResp.close();
        }

        if (generatedSql == null || generatedSql.isEmpty()) {
            return "{\"error\":\"LLM returned empty SQL\"}";
        }
        
        generatedSql = validateLlmSql(generatedSql);
        
        log.info("🤖 LLM generated SQL: {}", generatedSql);

        // ── Execute the generated SQL ─────────────────────────────────
        Map<String, Object> queryResult =
                DatabaseManager.getInstance().executeLlmGeneratedQuery(generatedSql);

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
     * Runs post-step chain actions after a tool call completes.
     * Each chain action is self-contained and only fires when its
     * trigger condition is met.
     *
     * Adding a new chain = add one entry to the chains list.
     * No changes needed to executePlan itself.
     */
    private String runPostStepChains(
            Intent intent,
            String toolResult,
            List<String> lastCodes,
            AgentResult result,
            String sessionId) throws IOException {

        StringBuilder html = new StringBuilder();

        // ── Registered chain actions ──────────────────────────────────
        // Each chain is a pair of: (trigger condition, action)
        // They run in order — first match wins, or all can run.

        // ── Chain 1: CODE_HISTORY + autoFetchCurrent ──────────────────
        // Trigger: CODE_HISTORY intent with autoFetchCurrent=true
        // Action:  fetch full details for each current code found
        if (intent.type == Intent.CODE_HISTORY) {

            String searchedCode = intent.params.get("locCd");
            if (searchedCode == null || searchedCode.trim().isEmpty()) {
                searchedCode = intent.params.get("formerLocCd");
            }
            if (searchedCode == null || searchedCode.trim().isEmpty()) {
                searchedCode = intent.params.get("currentLocCd");
            }

            final String searched = searchedCode != null
                    ? searchedCode.trim().toUpperCase()
                    : null;

            List<String> currentCodes = extractCurrentCodesFromHistory(toolResult);

            log.info("🔗 CODE_HISTORY searched={}, currentCodes={}", searched, currentCodes);

            // ── Check if searched code is NOT in currentCodes ──────────
            // If not current → it is former → auto-trigger hardcode_query
            boolean searchedIsCurrent = false;
            if (searched != null) {
                for (String c : currentCodes) {
                    if (c != null && searched.equalsIgnoreCase(c.trim())) {
                        searchedIsCurrent = true;
                        break;
                    }
                }
            }

            boolean shouldAutoFetch = searched != null
                    && !currentCodes.isEmpty()
                    && !searchedIsCurrent;

            log.info("🔗 searchedIsCurrent={}, shouldAutoFetch={}", 
                    searchedIsCurrent, shouldAutoFetch);

            if (shouldAutoFetch) {
                log.info("🔗 '{}' is former code → auto-trigger hardcode_query for {}",
                        searched, currentCodes);

                html.append("<p class='answer-summary' style='color:#f0a500;'>")
                    .append("🔄 <strong>").append(escapeHtml(searched))
                    .append("</strong> is a former code. Showing current code details below:")
                    .append("</p>");

                for (String currentCd : currentCodes) {
                    Map<String, Object> detailArgs = map("locCd", currentCd);
                    String detailResult = mcpClient.callTool("hardcode_query", detailArgs);
                    result.addToolCall("hardcode_query", detailArgs, detailResult);

                    html.append("<div style='margin-top:16px;border-top:2px solid #f0a500;padding-top:16px;'>")
                        .append("<p class='answer-summary'>📍 Current code: <code>")
                        .append(escapeHtml(currentCd))
                        .append("</code></p>")
                        .append(formatAsHtml(detailResult))
                        .append("</div>");
                }
            }
        }

        // ── Chain 2: PSM_LOCATIONS + autoFetchFirst ───────────────────
        // Trigger: PSM_LOCATIONS intent with autoFetchFirst=true
        // Action:  fetch full details for first location in results
        if (intent.type == Intent.PSM_LOCATIONS
                && "true".equals(intent.params.get("autoFetchFirst"))
                && !lastCodes.isEmpty()) {

            String firstCode = lastCodes.get(0);
            log.info("🔗 Chain: PSM_LOCATIONS → auto-fetch first location: {}", firstCode);

            Map<String, Object> detailArgs = map("locCd", firstCode);
            String detailResult = mcpClient.callTool("hardcode_query", detailArgs);
            result.addToolCall("hardcode_query", detailArgs, detailResult);

            html.append("<div style='margin-top:16px;"
                    + "border-top:2px solid #0f3460;"
                    + "padding-top:16px;'>");
            html.append(formatAsHtml(detailResult));
            html.append("</div>");
        }

        // ── Chain 3: Any intent + autoCheckReports ────────────────────
        // Trigger: any intent with autoCheckReports=BSI,KAI,DSSR etc.
        // Action:  run check_reports for each report type over lastCodes
        String autoCheckReports = intent.params.get("autoCheckReports");
        if (autoCheckReports != null
                && !autoCheckReports.isEmpty()
                && !lastCodes.isEmpty()) {

            log.info("🔗 Chain: auto-check reports {} for {} codes",
                    autoCheckReports, lastCodes.size());

            for (String rType : autoCheckReports.split(",")) {
                rType = rType.trim();
                Map<String, Object> reportArgs = new LinkedHashMap<String, Object>();
                reportArgs.put("reportType", rType);
                reportArgs.put("locCds", lastCodes);

                String reportResult = mcpClient.callTool("check_reports", reportArgs);
                result.addToolCall("check_reports", reportArgs, reportResult);
                html.append(formatAsHtml(reportResult));
            }
        }

        // ── Add more chains here as needed ────────────────────────────
        // Pattern: if (intent.type == X && intent.params.containsKey("Y")) { ... }

        return html.toString();
    }
    
    /**
     * Validates LLM-generated SQL before execution.
     * Catches common hallucination patterns.
     */
    private String validateLlmSql(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return "Empty SQL";
        }

        // ── Known table name hallucinations ───────────────────────────
        Map<String, String> corrections = new LinkedHashMap<String, String>();
        corrections.put("KAI_RECORD_PLANS_OR_DRAWINGS",  "KAI_RECORD_PLANS_AND_DRAWINGS");
        corrections.put("KAI_RECORD_PLANS_TO_DRAWINGS",  "KAI_RECORD_PLANS_AND_DRAWINGS");
        corrections.put("KAI_PLANS_AND_DRAWINGS",        "KAI_RECORD_PLANS_AND_DRAWINGS");
        corrections.put("KAI_RECORD_AND_DRAWINGS",       "KAI_RECORD_PLANS_AND_DRAWINGS");
        corrections.put("BLDG_SAFETY_INSPECTION_INFO",   "BSI_GENERAL_INFO");
        corrections.put("BSI_INFO",                      "BSI_GENERAL_INFO");

        String corrected = sql;
        for (Map.Entry<String, String> entry : corrections.entrySet()) {
            if (corrected.contains(entry.getKey())) {
                log.warn("⚠️ SQL validation: correcting '{}' → '{}'",
                        entry.getKey(), entry.getValue());
                corrected = corrected.replace(entry.getKey(), entry.getValue());
            }
        }

        return corrected;
    }
    
    /**
     * LEFT JOIN style merge:
     * - Keeps ALL results from monumentResult (left)
     * - Adds GRD_HIST_BLDG from historicResult where LOC_CD matches
     * - Deduplicates by LOC_CD
     */
    private String enrichWithHistoricGrade(
        String monumentResult,
        String historicResult,
        String gradeFilter) {      // ← new param

	    try {
	        JsonNode monNode  = mapper.readTree(monumentResult);
	        JsonNode histNode = mapper.readTree(historicResult);
	
	        JsonNode monArr  = monNode.path("results");
	        JsonNode histArr = histNode.path("results");
	
	        if (!monArr.isArray()) return monumentResult;
	
	        // Build grade lookup: LOC_CD → GRD_HIST_BLDG
	        Map<String, String> gradeMap = new LinkedHashMap<String, String>();
	        if (histArr.isArray()) {
	            for (JsonNode item : histArr) {
	                String cd    = item.path("LOC_CD").asText("").trim().toUpperCase();
	                String grade = item.path("GRD_HIST_BLDG").asText("").trim();
	                if (!cd.isEmpty()) gradeMap.put(cd, grade);
	            }
	        }
	        log.info("🔗 Grade lookup built: {} entries", gradeMap.size());
	
	        // Determine filter mode
	        boolean filterToGraded    = "GRADED".equals(gradeFilter);
	        boolean filterToSpecific  = gradeFilter != null
	                && !gradeFilter.isEmpty()
	                && !"ALL".equals(gradeFilter)
	                && !"GRADED".equals(gradeFilter);
	
	        Set<String>              seen   = new LinkedHashSet<String>();
	        List<Map<String,Object>> merged = new ArrayList<Map<String,Object>>();
	
	        for (JsonNode item : monArr) {
	            String cd = item.path("LOC_CD").asText("").trim().toUpperCase();
	            if (cd.isEmpty() || !seen.add(cd)) continue; // deduplicate
	
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
	        response.put("count",    merged.size());
	        response.put("results",  merged);
	        response.put("filter",   monNode.path("filter").asText("T"));
	        response.put("enriched", true);
	        if (gradeFilter != null) response.put("gradeFilter", gradeFilter);
	
	        log.info("🔗 Enriched result: {} monuments match grade filter '{}'",
	                merged.size(), gradeFilter);
	
	        return mapper.writeValueAsString(response);
	
	    } catch (Exception e) {
	        log.error("enrichWithHistoricGrade error: {}", e.getMessage());
	        return monumentResult;
	    }
	}
 // ── Referential word patterns ─────────────────────────────────────────
    private static final Pattern REFERENTIAL_PATTERN = Pattern.compile(
            "\\b(these|those|this location|that location|them|the above|previous locations)\\b",
            Pattern.CASE_INSENSITIVE
    );

    // ── Prompt patterns that want info/details ────────────────────────────
    private static final Pattern INFO_REQUEST_PATTERN = Pattern.compile(
        "(?i)^(get|show|find|fetch|display|tell me|give me|what is|info|"
        + "details|information|retrieve)\\b"
    );

    /**
     * If the prompt contains referential words ("that", "it", "those")
     * AND the session has remembered codes, rewrite the prompt to be specific.
     *
     * Examples:
     *   "Get info for that"        → "Get info for AB04400215002"
     *   "show reports for those"   → "show reports for AB04400215002, CC04400144000"
     *   "check BSI for them"       → "check BSI for AB04400215002, CC04400144000"
     */
    private String resolveReferentialPrompt(String prompt, String sessionId) {
        if (prompt == null || prompt.trim().isEmpty()) return prompt;

        // ── 2. Strip verifier retry warnings so they don't trigger memory dumps ──
        String cleanPrompt = prompt.replaceAll("\\[Previous answer was flagged:.*?\\]", "").trim();

        // Only resolve if clean prompt contains a true referential pointer word
        if (!REFERENTIAL_PATTERN.matcher(cleanPrompt).find()) {
            return prompt;
        }

        // Only resolve if we have session memory
        List<String> lastCodes = getLastResults(sessionId);
        if (lastCodes.isEmpty()) {
            log.info("🔗 Referential prompt but no session memory — leaving as-is");
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

        log.info("🔗 Referential resolution: '{}' codes in memory, using: {}",
                lastCodes.size(), codeList);

        return resolved;
    }
    
    /**
     * Sanitize LLM-extracted grade values.
     * LLM sometimes returns "NOT NULL", "NONE", "any", etc.
     * Only allow: null, "1", "2", "3", "ALL", "NONE"
     */
    private String sanitizeGrade(String grade) {
        if (grade == null) return null;

        String g = grade.trim().toUpperCase();

        switch (g) {
            case "1": case "2": case "3":
                return g;
            case "ALL": case "ANY": case "SOME":
                return "ALL";
            case "NONE": case "0": case "NULL": case "NO GRADE":
                return "NONE";
            case "NOT NULL": case "NON-NULL": case "GRADED": case "HAS GRADE":
                // User wants only graded ones — return special sentinel
                return "GRADED";
            default:
                log.warn("⚠️ Unknown grade value '{}' — defaulting to ALL", grade);
                return "ALL";
        }
    }

    public AgentResult runAgentWithTools(String prompt, String sessionId)
            throws Exception {
        return this.invoke(prompt, sessionId);
    }
    
    private List<String> extractFormerCodesFromHistory(String historyResult) {
        List<String> codes = new ArrayList<>();
        if (historyResult == null || historyResult.isEmpty()) return codes;

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
            String jsonStr = historyResult.trim();

            if (jsonStr.startsWith("{") && jsonStr.contains("\"rows\"")) {
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(jsonStr);
                if (root.has("rows")) jsonStr = root.get("rows").toString();
            }

            if (jsonStr.startsWith("[")) {
                for (com.fasterxml.jackson.databind.JsonNode row : mapper.readTree(jsonStr)) {
                    if (row.has("FORMER_LOC_CD") && !row.get("FORMER_LOC_CD").isNull()) {
                        String cd = row.get("FORMER_LOC_CD").asText().trim().toUpperCase();
                        if (!cd.isEmpty() && !codes.contains(cd)) codes.add(cd);
                    }
                }
            }
        } catch (Exception e) {
            // Fallback: regex
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("FORMER_LOC_CD[\":\\s]+([A-Z]{2}\\d{11})", 
                         java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(historyResult);
            while (m.find()) {
                String cd = m.group(1).toUpperCase();
                if (!codes.contains(cd)) codes.add(cd);
            }
        }
        return codes;
    }

    private ExtractedKeywords matchExactTemplatePrompt(String prompt) {
        if (prompt == null) return null;
        String clean = prompt.trim();

        // Template 1: "Get info for [LOC_CD]"
        Matcher mInfo = Pattern.compile("(?i)^Get info for ([A-Z0-9]{11,15})$").matcher(clean);
        if (mInfo.matches()) {
            ExtractedKeywords kw = new ExtractedKeywords();
            kw.setIntents(Collections.singletonList("LOCATION_CODE"));
            kw.setLocationCode(mInfo.group(1).toUpperCase());
            log.info("🎯 Exact Template Match: Get info for {}", kw.getLocationCode());
            return kw;
        }

        // Template 2: "Show locations for department [DEPT]"
        Matcher mDept = Pattern.compile("(?i)^Show locations for department ([A-Z]{2,6})$").matcher(clean);
        if (mDept.matches()) {
            ExtractedKeywords kw = new ExtractedKeywords();
            kw.setIntents(Collections.singletonList("DEPARTMENT"));
            kw.setDepartment(mDept.group(1).toUpperCase());
            log.info("🎯 Exact Template Match: Show locations for department {}", kw.getDepartment());
            return kw;
        }

        // Template 3: "Show locations under PSM [PSM_NAME]"
        Matcher mPsm = Pattern.compile("(?i)^Show locations under PSM/?([A-Z0-9 .&()_-]+)$").matcher(clean);
        if (mPsm.matches()) {
            ExtractedKeywords kw = new ExtractedKeywords();
            kw.setIntents(Collections.singletonList("PSM"));
            kw.setPsm(mPsm.group(1).toUpperCase());
            log.info("🎯 Exact Template Match: Show locations under PSM {}", kw.getPsm());
            return kw;
        }

        // Template 4: "List all PSMs"
        if (clean.equalsIgnoreCase("List all PSMs") || clean.equalsIgnoreCase("show PSMs")) {
            ExtractedKeywords kw = new ExtractedKeywords();
            kw.setIntents(Collections.singletonList("PSM"));
            log.info("🎯 Exact Template Match: List all PSMs");
            return kw;
        }

        // Template 5: "Search location code history for [LOC_CD]"
        Matcher mHist = Pattern.compile("(?i)^Search location code history for ([A-Z0-9]{11,15})$").matcher(clean);
        if (mHist.matches()) {
            ExtractedKeywords kw = new ExtractedKeywords();
            kw.setIntents(Collections.singletonList("CODE_HISTORY"));
            kw.setLocationCode(mHist.group(1).toUpperCase());
            log.info("🎯 Exact Template Match: Search location code history for {}", kw.getLocationCode());
            return kw;
        }

        return null; // No exact match -> Fall back to Ollama LLM Extraction!
    }
}
