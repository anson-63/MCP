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
import com.ais.model.IntentStep;
import com.ais.service.Intent;
import com.ais.service.IntentRole;
import com.ais.service.Plan;
import com.ais.service.PipelineExecutor;
import com.ais.model.OpenAiRequest;

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

    private static volatile String contextPath = "";

    public static void setContextPath(String path) {
        contextPath = (path == null) ? "" : path;
    }

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
            + "- When the user asks for a department in a specific area (e.g. 'LCSD in Sha Tin'), ALWAYS pass the area into the tool's 'location' parameter: locations_by_dept(deptCd='LCSD', location='Sha Tin').\n"
            + "IMPORTANT:\n"
            + "- Copy all location codes exactly.\n"
            + "- When you have enough data, stop calling tools and answer briefly.\n"
            + "- Do not invent database facts.\n"
            + "If the user requests to exclude empty, missing, placeholder, or undefined fields"
            + " (e.g., 'with address not null', 'real address', 'valid name', 'ignore empty departments')"
            + ", set excludeUndefinedField to 'address', 'name', or 'department' in your JSON output.\n"
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
            + "{\"type\":\"DEPARTMENT_LOCATIONS\",\"priority\":1,\"params\":{\"deptCd\":\"AFCD\"}},"
            + "{\"type\":\"CHECK_REPORTS\",\"priority\":2,\"params\":{\"reportType\":\"BSI\"}},"
            + "{\"type\":\"CHECK_REPORTS\",\"priority\":3,\"params\":{\"reportType\":\"KAI\"}}"
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
        this.httpClient = buildHttpClient();
        this.mapper = new ObjectMapper();
        this.mcpClient = new MCPClientService();
        if (useTencent) {
            log.info("[Manual Info]Using Tencent Cloud API — model: {}", tencentModel);
        } else {
            log.info("[Manual Info]Using Ollama — model: {}", ollamaModel);
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
        List<String> codes = LAST_RESULTS.get(sessionId);
        return codes != null ? codes : new ArrayList<String>();
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
    // Signals that indicate a complex/reasoning query
    
    // Signals that indicate a simple name lookup
    private static final Pattern SIMPLE_NAME_PATTERN = Pattern.compile(
            "(?i)^(info of|tell me about|search for|find|show me|what is|get|lookup|"
            + "details of|details for|about|show|info)\\s+[a-zA-Z\\s]+$"
    );
    
    private final QueryPlanner planner = new QueryPlanner();

    // ══════════════════════════════════════════════════════════════
    // MAIN ENTRY POINT
    // ══════════════════════════════════════════════════════════════
    public AgentResult invoke(String userPrompt, String sessionId) throws IOException {
    	AgentResult result = new AgentResult();
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
        // ── Phase 3: Fast-path ────────────────────────────────────────
        if (!plan.needsLlm && !plan.steps.isEmpty()) {
            String answer = executePlan(plan, result, sessionId);
            if (!isEmptyResult(answer)) {
                result.setAnswer(answer);
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
        boolean useSqlFallback = keywords != null && (keywords.hasIntent("UNKNOWN")
                || keywords.hasIntent("SQL_QUERY")
                || keywords.isCompound() // multi-intent → SQL
                );
        if (!useSqlFallback && isEmptyResult(result.getAnswer())) {
            log.info("[Manual Info]Empty tool result → SQL generation");
            useSqlFallback = true;
        }
        if (useSqlFallback) {
            log.info("[Manual Info]{} → generating SQL directly",
                    keywords.isCompound() ? "compound " + keywords.getIntents()
                    : keywords.primaryIntent());
            try {
                String sqlResult = generateAndExecuteSql(userPrompt, keywords);
                if (!isUselessSqlResult(sqlResult)) {
                    String html = formatSqlResultWithDetails(sqlResult, keywords, result);
                    result.setAnswer(html);
                    log.info("[Manual Info]Total: {}ms (SQL gen)", System.currentTimeMillis() - t0);
                    return result;
                }
                log.warn(" SQL gen empty → falling back to agent loop");
            } catch (Exception e) {
                log.error("SQL gen failed: {}", e.getMessage());
            }
        }
        // ── Phase 5: Agent loop (last resort) ─────────────────────────
        log.info("[Manual Info]Entering agent loop: {}", plan.reason);
        AgentResult agentResult = runAgentLoop(userPrompt, keywords, result, sessionId);
        // ── Phase 6: Post-agent SQL fallback ──────────────────────────
        if (isEmptyResult(agentResult.getAnswer())) {
            log.info("[Manual Info]Agent empty → SQL fallback");
            try {
                String sqlResult = generateAndExecuteSql(userPrompt, keywords);
                if (!isUselessSqlResult(sqlResult)) {
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
        sys.put("content", systemPrompt);          // ✅ param, not missing var
        fullMessages.add(sys);
        fullMessages.addAll(messages);             // ✅ List<Map> — no type error
        String requestBody = buildAgentRequest(fullMessages, tools); // ✅ tools is param
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
            return root.path("message");           // ✅ returns JsonNode, not String
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
         * values, key order ignored), or null if no such call has been made
         * yet in this request. Used to skip re-executing a tool the pipeline
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
        html.append("<h3 class='data-title'>🏢 Locations for Department: ")
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
            String monDisplay = "T".equals(monFlag) ? "✅ Yes"
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
        html.append("<h3 class='data-title'>🏰 ").append(escapeHtml(gradeLabel))
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
                .append("<i class='fa-solid fa-lightbulb'></i> Tip: Use the current code to look up full location details.")
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

        // Determine which step is the "answer step" — the one whose result is
        // returned to the user. Modifiers (FIRST/OLDEST/etc.) should only be
        // applied to this step. The answer step is the last step that is NOT
        // a "use_previous_codes" consumer. If all steps consume previous codes,
        // the first step is the answer.
        int answerStepIndex = -1;
        for (int i = plan.steps.size() - 1; i >= 0; i--) {
            Intent intent = plan.steps.get(i);
            String rel = intent.params.get("relation");
            if (!"use_previous_codes".equals(rel)) {
                // Validate this step has a tool mapping
                String toolName = resolveToolName(intent.type);
                if (toolName != null) {
                    answerStepIndex = i;
                    break;
                } else {
                    log.warn(" Answer step candidate {} ({}) has no tool mapping — skipping for modifier target",
                            i, intent.type);
                }
            }
        }
        if (answerStepIndex < 0 && !plan.steps.isEmpty()) {
            // Fallback: find ANY step with a tool mapping
            for (int i = plan.steps.size() - 1; i >= 0; i--) {
                String toolName = resolveToolName(plan.steps.get(i).type);
                if (toolName != null) {
                    answerStepIndex = i;
                    break;
                }
            }
        }
        if (answerStepIndex >= 0) {
            log.info("[Manual Info]Answer step (modifier target): {} {}",
                    answerStepIndex, plan.steps.get(answerStepIndex).type);
        }

        for (int i = 0; i < plan.steps.size(); i++) {
            Intent intent = plan.steps.get(i);
            log.info("▶ Executing intent [{}]: {}", i, intent);

            // ── Skip redundant LOCATION_CODE step ──────────────────
            // If this step just re-fetches the exact same location the
            // previous step's modifier (FIRST/OLDEST/NEWEST) already
            // resolved via hardcode_query, there is nothing new to do —
            // executing it again would duplicate the DB call and, worse,
            // duplicate the rendered detail card (map included) in the
            // final HTML.
            if (Intent.LOCATION_CODE.equals(intent.type)
                    && "use_previous_codes".equals(intent.params.get("relation"))
                    && lastToolResult != null
                    && lastCodes.size() == 1) {
                try {
                    JsonNode prevNode = mapper.readTree(lastToolResult);
                    if (prevNode.has("general")) {
                        String prevCode = prevNode.path("general").path("LOC_CD").asText("");
                        if (!prevCode.isEmpty() && prevCode.equalsIgnoreCase(lastCodes.get(0))) {
                            log.info("⏭ Skipping redundant LOCATION_CODE step [{}] — "
                                    + "previous step already resolved full details for {}",
                                    i, prevCode);
                            continue; // previous step's rendered HTML already shows this location
                        }
                    }
                } catch (Exception ignored) {
                    // Malformed/unexpected lastToolResult — fall through and
                    // execute the step normally rather than risk skipping
                    // something that was actually needed.
                }
            }

            // ══════════════════════════════════════════════════════════
            // STEP 1: Build args
            // ══════════════════════════════════════════════════════════
            Map<String, Object> args = buildArgs(intent, lastToolResult, lastCodes, sessionId);
            if (args == null) {
                html.append("<p class='answer-summary'>"
                        + " No location codes available to check reports for.</p>");
                continue;
            }
            // ══════════════════════════════════════════════════════════
            // STEP 2: Resolve tool name
            // ══════════════════════════════════════════════════════════
            String toolName = resolveToolName(intent.type);
            if (toolName == null) {
                // If this skipped step had a modifier, transfer it to the answer step
                String orphanModifier = intent.params.get("modifier");
                if (orphanModifier != null && !orphanModifier.isEmpty() && answerStepIndex >= 0) {
                    Intent answerIntent = plan.steps.get(answerStepIndex);
                    if (answerIntent.params.get("modifier") == null) {
                        answerIntent.params.put("modifier", orphanModifier);
                        log.info("[Manual Info]Transferred orphan modifier '{}' from skipped step {} ({}) to answer step {} ({})",
                                orphanModifier, i, intent.type, answerStepIndex, answerIntent.type);
                    }
                }
                log.warn(" No tool mapped for intent: {}", intent.type);
                continue;
            }
            // ══════════════════════════════════════════════════════════
            // STEP 3: Call tool
            // ══════════════════════════════════════════════════════════
            log.info("[Manual Info]Tool: {} args: {}", toolName, args);
            String previousToolResult = lastToolResult;
            String r;
            
            String cachedR = result.findEquivalentCallResult(toolName, args);
            if (cachedR != null) {
                log.info("[Manual Info] executePlan: skipping duplicate tool call [{} {}] — "
                        + "identical/equivalent call already made earlier in this request",
                        toolName, args);
                r = cachedR;
            } else {
	            // Handle CHECK_REPORTS with ALL or comma-separated types
	            if ("check_reports".equals(toolName)) {
	                String reportType = intent.params.get("reportType");
	                @SuppressWarnings("unchecked")
	                List<String> codesToCheck = (List<String>) args.get("locCds");
	                if (reportType != null && ("ALL".equalsIgnoreCase(reportType) || reportType.contains(","))) {
	                    log.info("[Manual Info] Expanding check_reports: {} → individual types", reportType);
	                    r = callCheckReports(reportType, codesToCheck, result);
	                } else {
	                    r = mcpClient.callTool(toolName, args);
	                    result.addToolCall(toolName, args, r);
	                }
	            } else {
	                r = mcpClient.callTool(toolName, args);
	                result.addToolCall(toolName, args, r);
	            }
            }
            lastToolResult = r;
            // ══════════════════════════════════════════════════════════
            // STEP 4: Apply relation to previous step result
            // ══════════════════════════════════════════════════════════
            String relation = intent.params.get("relation");
            if (relation == null) {
                if ("previous".equals(intent.params.get("crossFilterWith"))) {
                    relation = "filter_previous";
                } else if ("true".equals(intent.params.get("enrich"))) {
                    relation = "enrich_previous";
                } else {
                    relation = "independent";
                }
            }
            if ("filter_previous".equals(relation) && previousToolResult != null) {
                log.info("[Manual Info]Filtering previous results with '{}' data", intent.type);
                r = crossFilter(previousToolResult, r);
            } else if ("enrich_previous".equals(relation) && previousToolResult != null) {
                log.info("[Manual Info]Enriching previous results with '{}' data", intent.type);
                if (Intent.HISTORIC_BUILDING.equals(intent.type)) {
                    String gradeFilter = intent.params.get("grade"); // e.g., "1", "ALL", "GRADED"
                    r = enrichWithHistoricGrade(previousToolResult, r, gradeFilter);
                } else {
                    r = enrichWithPrevious(previousToolResult, r);
                }
            }
            // ══════════════════════════════════════════════════════════
            // STEP 5: Post-call modifiers (OLDEST / NEWEST / FIRST)
            // Only apply modifiers to the answer step. This prevents a modifier
            // from being applied to an intermediate independent step (e.g.
            // PSM_LOCATIONS) and collapsing the result set before a later
            // filter_previous/enrich_previous step can run correctly.
            // ══════════════════════════════════════════════════════════
            if (i == answerStepIndex) {
                r = applyModifier(intent, r, result);
            } else {
                log.info("[Manual Info]Skipping modifier on intermediate step {} ({}) — not the answer step",
                        i, intent.type);
            }
            // ══════════════════════════════════════════════════════════
            // STEP 6: Update state
            // ══════════════════════════════════════════════════════════
            // ══════════════════════════════════════════════════════════
            lastCodes = extractCodesFromResult(r);
            // Also extract CURRENT_LOC_CD from history results
            if (lastCodes.isEmpty() && Intent.CODE_HISTORY.equals(intent.type)) {
                lastCodes = extractCurrentCodesFromHistory(r);
                log.info("[Manual Info]Extracted {} current code(s) from history result", lastCodes.size());
            }
            if (!lastCodes.isEmpty()) {
                LAST_RESULTS.put(sessionId, lastCodes);
                log.info("[Manual Info]Saved {} codes to session memory", lastCodes.size());
            }
            // ── STEP 6: Render HTML ───────────────────────────────────────────────
            boolean isEnrichStep = "enrich_previous".equals(relation) || "true".equals(intent.params.get("enrich"));
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

        // ══════════════════════════════════════════════════════════
        // GENERALIZED INTERSECTION SUMMARY
        // Shows which codes survived all filter_previous steps,
        // regardless of tool type (reports, historic, monuments, etc.)
        // ══════════════════════════════════════════════════════════
        int chainedFilters = 0;
        StringBuilder filterDescriptions = new StringBuilder();
        for (Intent step : plan.steps) {
            if ("filter_previous".equals(step.params.get("relation"))) {
                chainedFilters++;
                if (filterDescriptions.length() > 0) {
                    filterDescriptions.append(" ∩ ");
                }
                filterDescriptions.append(describeStep(step));
            }
        }

        if (chainedFilters >= 2 && !lastCodes.isEmpty()) {
            log.info("[Manual Info] Intersection: {} codes survived {} filter(s): {}",
                    lastCodes.size(), chainedFilters, filterDescriptions);

            StringBuilder s = new StringBuilder();
            s.append("<div style='margin-top:16px;border:2px solid #27ae60;")
                    .append("border-radius:8px;padding:16px;'>");
            s.append("<h3 style='color:#27ae60;'>✅ Matched locations</h3>");
            s.append("<p class='answer-summary'><strong>")
                    .append(lastCodes.size())
                    .append("</strong> location(s) matched all filters: <strong>")
                    .append(escapeHtml(filterDescriptions.toString()))
                    .append("</strong></p>");
            s.append("<table class='data-table'>")
                    .append("<tr><th>#</th><th>Code</th></tr>");
            int idx = 1;
            for (String code : lastCodes) {
                s.append("<tr><td>").append(idx++).append("</td>")
                        .append("<td><code>").append(escapeHtml(code))
                        .append("</code></td></tr>");
            }
            s.append("</table></div>");
            html.append(s);
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
    private String formatSqlResultWithDetails(String sqlResult,
            ExtractedKeywords keywords,
            AgentResult result) throws IOException {
        String firstCode = extractFirstLocCdFromSqlResult(sqlResult);
        if (keywords != null && keywords.isShowDetails() && firstCode != null) {
            log.info("[Manual Info]SQL result + showDetails=true → fetching full details for {}", firstCode);
            Map<String, Object> detailArgs = map("locCd", firstCode);
            String detailResult = mcpClient.callTool("hardcode_query", detailArgs);
            result.addToolCall("hardcode_query", detailArgs, detailResult);
            return "<p class='answer-summary'>"
                    + "<i class='fa-solid fa-lightbulb'></i> Answered using a generated database query and full details:"
                    + "</p>" + formatAsHtml(detailResult);
        }
        return "<p class='answer-summary'>"
                + "<i class='fa-solid fa-lightbulb'></i> Answered using a generated database query:"
                + "</p>" + formatAsHtml(sqlResult);
    }

    // ── Extract all LOC_CD values from a tool result ─────────────────
    private List<String> extractCodesFromResult(String toolResult) {
        List<String> codes = new ArrayList<String>();
        if (toolResult == null || toolResult.trim().isEmpty()) {
            return codes;
        }
        try {
            JsonNode node = mapper.readTree(toolResult);
            LinkedHashSet<String> seen = new LinkedHashSet<String>();
            // 1. Array-style results (most tool responses)
            JsonNode results = node.path("results");
            if (results.isArray()) {
                for (JsonNode item : results) {
                    String cd = item.path("LOC_CD").asText("").trim();
                    if (!cd.isEmpty()) {
                        seen.add(cd.toUpperCase());
                    }
                }
            }
            // 2. Single-location detail object: { "general": { "LOC_CD": "..." } }
            if (seen.isEmpty()) {
                JsonNode general = node.path("general");
                String cd = general.path("LOC_CD").asText("").trim();
                if (!cd.isEmpty()) {
                    seen.add(cd.toUpperCase());
                }
            }
            // 3. Direct top-level LOC_CD (e.g., from structured find responses)
            if (seen.isEmpty()) {
                String cd = node.path("LOC_CD").asText("").trim();
                if (!cd.isEmpty()) {
                    seen.add(cd.toUpperCase());
                }
            }
            // 4. check_reports format: extract from withReport array
            if (seen.isEmpty()) {
                JsonNode withReport = node.path("withReport");
                if (withReport.isArray()) {
                    for (JsonNode item : withReport) {
                        String cd = item.path("LOC_CD").asText("").trim();
                        if (!cd.isEmpty()) {
                            seen.add(cd.toUpperCase());
                        }
                    }
                    if (!seen.isEmpty()) {
                        log.info("[Manual Info]Extracted {} codes from withReport array", seen.size());
                    }
                }
            }
            codes.addAll(seen);
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
            if (!secondArr.isArray() && secondNode.has("withReport")) {
                JsonNode withReport = secondNode.path("withReport");
                Set<String> reportCodes = new LinkedHashSet<String>();
                if (withReport.isArray()) {
                    for (JsonNode item : withReport) {
                        String cd = item.path("LOC_CD").asText("").trim().toUpperCase();
                        if (!cd.isEmpty()) {
                            reportCodes.add(cd);
                        }
                    }
                }
                log.info("[Manual Info]crossFilter: check_reports has {} codes with report", reportCodes.size());

                if (firstArr.isArray()) {
                    List<JsonNode> filtered = new ArrayList<JsonNode>();
                    for (JsonNode item : firstArr) {
                        String cd = item.path("LOC_CD").asText("").trim().toUpperCase();
                        if (reportCodes.contains(cd)) {
                            filtered.add(item);
                        }
                    }
                    Map<String, Object> response = new LinkedHashMap<String, Object>();
                    response.put("count", filtered.size());
                    response.put("results", filtered);
                    response.put("reportType", secondNode.path("reportType").asText(""));
                    return mapper.writeValueAsString(response);
                }
                return secondResult;
            }
            if (!firstArr.isArray() || !secondArr.isArray()) {
                return secondResult;
            }
            // Build LOC_CD set from first result
            Set<String> firstCodes = new LinkedHashSet<String>();
            for (JsonNode item : firstArr) {
                String cd = item.path("LOC_CD").asText("").trim().toUpperCase();
                if (!cd.isEmpty()) {
                    firstCodes.add(cd);
                }
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
    // ══════════════════════════════════════════════════════════════
    // AGENT LOOP  (unchanged — callOllama signature still matches)
    // ══════════════════════════════════════════════════════════════

    private AgentResult runAgentLoop(
            String userPrompt,
            ExtractedKeywords keywords,
            AgentResult result,
            String sessionId) throws IOException {
        List<Map<String, Object>> messages
                = buildInitialMessages(userPrompt, keywords);
        List<Map<String, Object>> tools
                = getRelevantTools(userPrompt, keywords);
        StringBuilder htmlOutput = new StringBuilder();
        List<String> reasoningSteps = new ArrayList<String>();
        
        int maxIterations = 5;
        if (keywords != null && keywords.hasIntent("UNKNOWN")) {
            maxIterations = 2;
            log.info("[Manual Info] UNKNOWN intent — limiting agent loop to {} iterations",
                    maxIterations);
        }
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            log.info("[Manual Info]Agent loop iteration {}", iteration + 1);
            //callOllama(List<Map>, List<Map>, String) → JsonNode
            JsonNode llmResponse = callOllama(messages, tools, SYSTEM_PROMPT);
            JsonNode toolCalls = llmResponse.path("tool_calls");
            String content = stripThinkingTags(
                    llmResponse.path("content").asText("").trim());
            if (toolCalls.isMissingNode() || toolCalls.isEmpty()) {
                log.info("[Manual Info]Agent loop done after {} iteration(s)", iteration + 1);
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
                Map<String, Object> args;
                // Tencent Cloud sometimes returns tool-call arguments as a JSON string
                // instead of an object. Parse the string when that happens.
                if (argsNode.isTextual()) {
                    String argsText = argsNode.asText();
                    if (argsText == null || argsText.trim().isEmpty()) {
                        args = new LinkedHashMap<String, Object>();
                    } else {
                        args = mapper.readValue(argsText,
                                mapper.getTypeFactory().constructMapType(
                                        Map.class, String.class, Object.class));
                    }
                } else {
                    args = mapper.convertValue(argsNode,
                            mapper.getTypeFactory().constructMapType(
                                    Map.class, String.class, Object.class));
                }
                if (args == null) {
                    args = new LinkedHashMap<String, Object>();
                }
                log.info("[Manual Info]LLM SELECTED TOOL  : [{}]", toolName);
                log.info("[Manual Info]LLM GENERATED ARGS : {}", args);

                String cachedAgentR = result.findEquivalentCallResult(toolName, args);
                String toolResult;
                if (cachedAgentR != null) {
                    log.info("[Manual Info] Agent loop: skipping duplicate tool call [{} {}] — "
                            + "identical/equivalent call already made earlier in this request "
                            + "(possibly by the fast path before the agent loop started)",
                            toolName, args);
                    toolResult = cachedAgentR;
                } else {
                	if ("check_reports".equals(toolName)) {
                        String reportType = args.get("reportType") != null ? args.get("reportType").toString() : null;
                        @SuppressWarnings("unchecked")
                        List<String> locCds = (List<String>) args.get("locCds");
                        if (reportType != null && ("ALL".equalsIgnoreCase(reportType) || reportType.contains(","))) {
                            log.info("[Manual Info] Agent loop: expanding check_reports: {} → individual types", reportType);
                            toolResult = callCheckReports(reportType, locCds, result);
                            // NOTE: callCheckReports() already calls result.addToolCall() internally
                            // for each expanded sub-call (see callCheckReports() method) — do NOT
                            // add another record here, or the expanded calls get double-recorded too.
                        } else {
                            toolResult = mcpClient.callTool(toolName, args);
                            // no addToolCall here — recorded once below after postProcess
                        }
                    } else {
                        toolResult = mcpClient.callTool(toolName, args);
                        // no addToolCall here — recorded once below after postProcess
                    }
                	
                	toolResult = postProcess(toolName, args, toolResult, userPrompt, sessionId, result);

                    // Single record per real tool invocation. Skip if callCheckReports() path
                    // already recorded its own sub-calls (reportType ALL/comma-separated).
                    boolean alreadyRecordedByExpansion = "check_reports".equals(toolName)
                            && args.get("reportType") != null
                            && (args.get("reportType").toString().equalsIgnoreCase("ALL")
                                || args.get("reportType").toString().contains(","));
                    if (!alreadyRecordedByExpansion) {
                        result.addToolCall(toolName, args, toolResult);
                    }
                }
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
                log.info("[Manual Info]Zero results for '{}', retrying with '{}'",
                        locName, candidate);
                Map<String, Object> retryArgs = new LinkedHashMap<String, Object>();
                retryArgs.put("locName", candidate);
                String retryResult = mcpClient.callTool("search_by_name", retryArgs);
                JsonNode retryNode = mapper.readTree(retryResult);
                if (retryNode.path("count").asInt(0) > 0) {
                    log.info("[Manual Info]✅ Found {} results with '{}'",
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
                    log.info("[Manual Info] Post-filtered {} results to match '{}'",
                            toolName, locationFilter);
                    // If "oldest" is mentioned, find oldest and fetch details
                    if (promptUpper.contains("OLDEST")
                            || promptUpper.contains("EARLIEST")) {
                        List<String> codes = extractCodesFromResult(filtered);
                        if (!codes.isEmpty()) {
                            log.info("[Manual Info] Finding oldest among {} filtered results", codes.size());
                            return findOldestWithDetails(codes, result);
                        }
                    }
                    return filtered;
                }
                // No location filter but "oldest" is mentioned
                if (promptUpper.contains("OLDEST") || promptUpper.contains("EARLIEST")) {
                    List<String> codes = extractCodesFromResult(toolResult);
                    if (!codes.isEmpty()) {
                        log.info("[Manual Info] Finding oldest among {} results (no location filter)",
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
                log.info("[Manual Info] Extracted location filter: '{}'", loc);
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
                log.info("[Manual Info]  Checking {}: name='{}' year={} dept={}",
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
            log.info("[Manual Info]✅ Oldest found: {} | {} | dept={} | year={}",
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
        boolean isRetry = userPrompt.contains("Previous answer was flagged");
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
                log.info("[Manual Info]Keyword cache hit");
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
            // ── Build combined system + user prompt ───────────────────────
            String fullPrompt = KEYWORD_EXTRACT_PROMPT
                    + "\n\nExtract keywords from: " + userPrompt + " /nothink";
            log.debug("[Manual Debug] Keyword extract request → model={}",
                    useTencent ? tencentModel : ollamaModel);
            // ✅ Routes to Tencent or Ollama based on config
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
            log.info("[Manual Info]✅ Keywords: intents={} primary={} location={} modifier={} dept={} grade={} filter={} showDetails={} plan={}",
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
                    String v = e.getValue().asText("").trim();
                    if (!v.isEmpty()) {
                        params.put(e.getKey(), v);
                    }
                }
            }
            step.setParams(params);
            step.setRelation(nullIfEmpty(stepNode.path("relation").asText("")));
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
                        log.warn(" codesSource=psm_first but no code in previous result");
                        return null;
                    }
                    args.put("locCd", firstCode);
                } else if ("use_previous_codes".equals(intent.params.get("relation"))) {
                    // Use the previous step's LOC_CD list as input; pick the first one for hardcode_query
                    List<String> codes = lastCodes.isEmpty() ? getLastResults(sessionId) : lastCodes;
                    if (codes.isEmpty()) {
                        return null;
                    }
                    args.put("locCd", codes.get(0));
                } else {
                    // Direct code from params
                    String locCd = intent.params.get("locCd");
                    String locCds = intent.params.get("locCds");
                    if (locCd != null) {
                        args.put("locCd", locCd);
                    }
                    if (locCds != null) {
                        args.put("_multiCodes", locCds);
                    }
                }
                break;
            }
            case Intent.NAME_SEARCH: {
                String locName = intent.params.get("locName");
                if (locName == null || locName.isEmpty()) {
                    locName = intent.params.get("locationName");
                }
                if (locName != null && !locName.isEmpty()) {
                    args.put("locName", locName);
                }
                String location = getLocationFilter(intent);
                if (location != null && !location.isEmpty()) {
                    args.put("location", location);
                }
                String limitParam = intent.params.get("limit");
                if (limitParam != null) {
                    try {
                        args.put("limit", Integer.parseInt(limitParam));
                    } catch (NumberFormatException ignored) {
                        // ignore malformed limit, let DatabaseManager use its default
                    }
                }
                putExcludeUndefinedIfPresent(args, intent);
                break;
            }
            case Intent.PSM_LIST: {
                // No args needed — lists all PSMs
                break;
            }
            case Intent.PSM_LOCATIONS: {
                String psm = intent.params.get("psm");
                if (psm != null) {
                    args.put("psm", psm);
                }
                String location = getLocationFilter(intent);
                if (location != null && !location.isEmpty()) {
                    args.put("location", location);
                }
                //pass through a limit if one was captured either in the
                // step's own params (LLM plan) or extracted from the raw prompt.
                String limitParam = intent.params.get("limit");
                if (limitParam != null) {
                    try {
                        args.put("limit", Integer.parseInt(limitParam));
                    } catch (NumberFormatException ignored) {
                        // ignore malformed limit, let DatabaseManager use its default
                    }
                }
                putExcludeUndefinedIfPresent(args, intent);
                break;
            }
            case Intent.CODE_HISTORY: {
                // ── Primary lookup keys ───────────────────────────────────
                String locCd = intent.params.get("locCd");
                String formerLocCd = intent.params.get("formerLocCd");
                String currentLocCd = intent.params.get("currentLocCd");
                if (locCd != null) {
                    // Search by either former or current — DB handles both
                    args.put("formerLocCd", locCd);
                    args.put("currentLocCd", locCd);
                }
                if (formerLocCd != null) {
                    args.put("formerLocCd", formerLocCd);
                }
                if (currentLocCd != null) {
                    args.put("currentLocCd", currentLocCd);
                }
                break;
            }
            case Intent.CHECK_REPORTS: {
                String reportType = intent.params.get("reportType");
                String codesSource = intent.params.get("codesSource");
                String inlineCds = intent.params.get("locCds");
                String relation = intent.params.get("relation");
                List<String> codesToCheck;
                if ("use_previous_codes".equals(relation)) {
                    codesToCheck = lastCodes.isEmpty() ? getLastResults(sessionId) : lastCodes;
                } else if (codesSource != null && !lastCodes.isEmpty()) {
                    codesToCheck = lastCodes;
                } else if (codesSource != null) {
                    codesToCheck = getLastResults(sessionId);
                } else if (inlineCds != null) {
                    codesToCheck = Arrays.asList(inlineCds.split(","));
                } else {
                    codesToCheck = getLastResults(sessionId);
                }
                if (codesToCheck == null || codesToCheck.isEmpty()) {
                    return null;
                }
                if (reportType != null) {
                    args.put("reportType", reportType);
                }
                args.put("locCds", codesToCheck);
                break;
            }
            case Intent.DECLARED_MONUMENT: {
                String filter = intent.params.getOrDefault("filter", "T");
                args.put("filter", filter);
                String location = getLocationFilter(intent);
                if (location != null && !location.isEmpty()) {
                    args.put("location", location);
                }
                putExcludeUndefinedIfPresent(args, intent);
                break;
            }
            case Intent.HISTORIC_BUILDING: {
                String grade = intent.params.getOrDefault("grade", "ALL");
                args.put("grade", grade);
                String location = getLocationFilter(intent);
                if (location != null && !location.isEmpty()) {
                    args.put("location", location);
                }
                String limitParam = intent.params.get("limit");
                if (limitParam != null) {
                    try {
                        args.put("limit", Integer.parseInt(limitParam));
                    } catch (NumberFormatException ignored) {
                        // ignore malformed limit, let DatabaseManager use its default
                    }
                }
                putExcludeUndefinedIfPresent(args, intent);
                break;
            }
            case Intent.DEPARTMENT_LOCATIONS: {
                String deptCd = intent.params.get("deptCd");
                if (deptCd != null) {
                    args.put("deptCd", deptCd);
                }
                String location = getLocationFilter(intent);
                if (location != null && !location.isEmpty()) {
                    args.put("location", location);
                }
                String limitParam = intent.params.get("limit");
                if (limitParam != null) {
                    try {
                        args.put("limit", Integer.parseInt(limitParam));
                    } catch (NumberFormatException ignored) {
                        // ignore malformed limit, let DatabaseManager use its default
                    }
                }
                putExcludeUndefinedIfPresent(args, intent);
                break;
            }
            default:
                log.warn(" Unknown intent type in buildArgs: {}", intent.type);
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
        log.info("[Manual Info]Applying modifier: {} to {} results",
                modifier, extractCodesFromResult(toolResult).size());
        switch (modifier) {
            case "OLDEST":
            case "EARLIEST": {
                List<String> codes = extractCodesFromResult(toolResult);
                if (codes.isEmpty()) {
                    log.warn(" OLDEST modifier: no codes to compare");
                    return toolResult;
                }
                log.info("[Manual Info] Finding OLDEST among {} locations", codes.size());
                return findByYear(codes, false, result); // false = find minimum year
            }
            case "NEWEST":
            case "LATEST": {
                List<String> codes = extractCodesFromResult(toolResult);
                if (codes.isEmpty()) {
                    log.warn(" NEWEST modifier: no codes to compare");
                    return toolResult;
                }
                log.info("[Manual Info] Finding NEWEST among {} locations", codes.size());
                return findByYear(codes, true, result); // true = find maximum year
            }
            case "FIRST": {
                String firstCode = extractFirstCode(toolResult);
                if (firstCode == null) {
                    return toolResult;
                }
                log.info("[Manual Info]1️⃣ Taking FIRST result: {}", firstCode);
                Map<String, Object> args = map("locCd", firstCode);
                String r = mcpClient.callTool("hardcode_query", args);
                result.addToolCall("hardcode_query", args, r);
                return r;
            }
            case "COUNT": {
                // Wrap in a count-only response
                List<String> codes = extractCodesFromResult(toolResult);
                log.info("[Manual Info]🔢 COUNT modifier: {} results", codes.size());
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
                log.warn(" Unknown modifier: {}", modifier);
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

        if (locCds == null || locCds.isEmpty()) {
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("found", false);
            response.put("summary", "No candidates to compare.");
            return mapper.writeValueAsString(response);
        }

        // Cap defensively — even with batching, comparing thousands of rows
        // for one user question is excessive; 200 matches the TOP 200 already
        // used everywhere else in this codebase.
        List<String> capped = locCds.size() > 200 ? locCds.subList(0, 200) : locCds;

        log.info("[Manual Info] findByYear: batch-fetching {} candidates in ONE query "
                + "(was: {} individual hardcode_query calls)", capped.size(), capped.size());

        List<Map<String, Object>> rows = DatabaseManager.getInstance().getGeneralInfoBatch(capped);

        String bestCode = null;
        String bestName = null;
        String bestDept = null;
        String bestDeptDesc = null;
        int bestYear = findMax ? Integer.MIN_VALUE : Integer.MAX_VALUE;

        for (Map<String, Object> row : rows) {
            Object yearObj = row.get("BLDG_COMPLETION_YEAR");
            int year;
            try {
                year = yearObj == null ? 0 : (int) Double.parseDouble(yearObj.toString());
            } catch (NumberFormatException nfe) {
                continue;
            }
            boolean isBetter = year > 0 && (findMax ? year > bestYear : year < bestYear);
            if (isBetter) {
                bestYear = year;
                bestCode = (String) row.get("LOC_CD");
                bestName = (String) row.get("LOC_NAME");
                bestDept = (String) row.get("DEPT_CD");
                bestDeptDesc = (String) row.get("DEPT_DESC");
            }
        }

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        if (bestCode != null) {
            String label = findMax ? "Newest" : "Oldest";
            String managedBy = (bestDept != null && !bestDept.isEmpty()) ? bestDept : bestDeptDesc;
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
            log.info("[Manual Info]✅ {} found: {} | {} | dept={} | year={} (1 batch query + 0 extra lookups)",
                    label, bestCode, bestName, bestDept, bestYear);

            // Only ONE hardcode_query call, for the actual winner, so the
            // formatter can render its full detail card (map, reports, etc.)
            // exactly like the old code did for every candidate — just once now.
            Map<String, Object> detailArgs = map("locCd", bestCode);
            String detailResult = mcpClient.callTool("hardcode_query", detailArgs);
            result.addToolCall("hardcode_query", detailArgs, detailResult);
            return detailResult;
        } else {
            response.put("found", false);
            response.put("summary", "Could not determine "
                    + (findMax ? "newest" : "oldest")
                    + " — BLDG_COMPLETION_YEAR not available for candidates.");
            return mapper.writeValueAsString(response);
        }
    }

    /**
     * Ask the LLM to generate a SQL query, then execute it. Used as last resort
     * when no tool matches the user's question.
     */
    private String generateAndExecuteSql(String userPrompt,
            ExtractedKeywords keywords) throws IOException {
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
        // ✅ Routes to Tencent or Ollama based on config
        String generatedSql = callLlmSimple(fullPrompt, 0.0, 1500);
        // ── Strip thinking tags and markdown fences ───────────────────
        generatedSql = stripThinkingTags(generatedSql);
        generatedSql = stripMarkdownFences(generatedSql);
        generatedSql = unescapeHtml(generatedSql);
        if (generatedSql == null || generatedSql.trim().isEmpty()) {
            return "{\"error\":\"LLM returned empty SQL\"}";
        }
        generatedSql = validateLlmSql(generatedSql);
        log.info("[Manual Info]LLM generated SQL: {}", generatedSql);
        // ── Execute the generated SQL ─────────────────────────────────
        Map<String, Object> queryResult
                = DatabaseManager.getInstance().executeLlmGeneratedQuery(generatedSql);
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
     * Runs post-step chain actions after a tool call completes. Each chain
     * action is self-contained and only fires when its trigger condition is
     * met.
     *
     * Adding a new chain = add one entry to the chains list. No changes needed
     * to executePlan itself.
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
            log.info("[Manual Info]CODE_HISTORY searched={}, currentCodes={}", searched, currentCodes);
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
            log.info("[Manual Info] searchedIsCurrent={}, shouldAutoFetch={}",
                    searchedIsCurrent, shouldAutoFetch);
            if (shouldAutoFetch) {
                log.info("[Manual Info]'{}' is former code → auto-trigger hardcode_query for {}",
                        searched, currentCodes);
                for (String currentCd : currentCodes) {
                    Map<String, Object> detailArgs = map("locCd", currentCd);
                    String detailResult = mcpClient.callTool("hardcode_query", detailArgs);
                    result.addToolCall("hardcode_query", detailArgs, detailResult);
                    html.append("<div style='margin-top:16px;border-top:2px solid #f0a500;padding-top:16px;'>")
                            .append("<p class='answer-summary'><i class='fa-solid fa-map-pin'></i> Current code: <code>")
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
            log.info("[Manual Info]Chain: PSM_LOCATIONS → auto-fetch first location: {}", firstCode);
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
            log.info("[Manual Info]Chain: auto-check reports {} for {} codes",
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
    // ── Prompt patterns that want info/details ────────────────────────────
    private static final Pattern INFO_REQUEST_PATTERN = Pattern.compile(
            "(?i)^(get|show|find|fetch|display|tell me|give me|what is|info|"
            + "details|information|retrieve)\\b"
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

    private List<String> extractFormerCodesFromHistory(String historyResult) {
        List<String> codes = new ArrayList<>();
        if (historyResult == null || historyResult.isEmpty()) {
            return codes;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper
                    = new com.fasterxml.jackson.databind.ObjectMapper();
            String jsonStr = historyResult.trim();
            if (jsonStr.startsWith("{") && jsonStr.contains("\"rows\"")) {
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(jsonStr);
                if (root.has("rows")) {
                    jsonStr = root.get("rows").toString();
                }
            }
            if (jsonStr.startsWith("[")) {
                for (com.fasterxml.jackson.databind.JsonNode row : mapper.readTree(jsonStr)) {
                    if (row.has("FORMER_LOC_CD") && !row.get("FORMER_LOC_CD").isNull()) {
                        String cd = row.get("FORMER_LOC_CD").asText().trim().toUpperCase();
                        if (!cd.isEmpty() && !codes.contains(cd)) {
                            codes.add(cd);
                        }
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
                if (!codes.contains(cd)) {
                    codes.add(cd);
                }
            }
        }
        return codes;
    }

    private ExtractedKeywords matchExactTemplatePrompt(String prompt) {
        if (prompt == null) {
            return null;
        }
        String clean = prompt.trim();
        
        // ── SCALABILITY GUARD: If the prompt contains natural language filter words, ──
        // ── bypass dumb regex templates and let the LLM handle semantic reasoning! ──
        if (clean.matches("(?i).*\\b(with|without|not|non|has|have|having|excluding|except|where|in|at|near)\\b.*")) {
            log.info("[Manual Info] Natural language filter detected in prompt — bypassing regex template gateway to let LLM reason.");
            return null;
        }
        
        // Template 1: "Get info for [LOC_CD]"
        Matcher mInfo = Pattern.compile("(?i)^Get info for ([A-Z0-9]{11,15})$").matcher(clean);
        if (mInfo.matches()) {
            ExtractedKeywords kw = new ExtractedKeywords();
            kw.setIntents(Collections.singletonList("LOCATION_CODE"));
            kw.setLocationCode(mInfo.group(1).toUpperCase());
            log.info("[Manual Info]Exact Template Match: Get info for {}", kw.getLocationCode());
            return kw;
        }
        // Template 2: "Show locations for department [DEPT]"
        Matcher mDept = Pattern.compile("(?i)^Show locations for department ([A-Z]{2,6})$").matcher(clean);
        if (mDept.matches()) {
            ExtractedKeywords kw = new ExtractedKeywords();
            kw.setIntents(Collections.singletonList("DEPARTMENT"));
            kw.setDepartment(mDept.group(1).toUpperCase());
            log.info("[Manual Info]Exact Template Match: Show locations for department {}", kw.getDepartment());
            return kw;
        }
        // Template 3: "Show locations under PSM [PSM_NAME]"
        Matcher mPsm = Pattern.compile(
                "(?i)^Show (?:(?:top|first)\\s+(\\d{1,4})\\s+)?locations under PSM/?([A-Z0-9 .&()_-]+)$"
        ).matcher(clean);
        if (mPsm.matches()) {
            ExtractedKeywords kw = new ExtractedKeywords();
            kw.setIntents(Collections.singletonList("PSM"));
            kw.setPsm(mPsm.group(2).trim().toUpperCase());
            if (mPsm.group(1) != null) {
                try {
                    kw.setLimit(Integer.parseInt(mPsm.group(1)));
                } catch (NumberFormatException ignored) {
                    // leave limit unset -> DatabaseManager default applies
                }
            }
            log.info("[Manual Info]Exact Template Match: Show locations under PSM {} (limit={})",
                    kw.getPsm(), kw.getLimit());
            return kw;
        }
        // Template 4: "List all PSMs"
        if (clean.equalsIgnoreCase("List all PSMs") || clean.equalsIgnoreCase("show PSMs")) {
            ExtractedKeywords kw = new ExtractedKeywords();
            kw.setIntents(Collections.singletonList("PSM"));
            log.info("[Manual Info]Exact Template Match: List all PSMs");
            return kw;
        }
        // Template 5: "Search location code history for [LOC_CD]"
        Matcher mHist = Pattern.compile("(?i)^Search location code history for ([A-Z0-9]{11,15})$").matcher(clean);
        if (mHist.matches()) {
            ExtractedKeywords kw = new ExtractedKeywords();
            kw.setIntents(Collections.singletonList("CODE_HISTORY"));
            kw.setLocationCode(mHist.group(1).toUpperCase());
            log.info("[Manual Info]Exact Template Match: Search location code history for {}", kw.getLocationCode());
            return kw;
        }
        return null; // No exact match -> Fall back to Ollama LLM Extraction!
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

    /**
     * Wrapper for check_reports that handles "ALL" and comma-separated report
     * types. Expands into individual calls and returns combined HTML.
     */
    private String callCheckReports(String reportType, List<String> locCds, AgentResult result) throws IOException {
        // Handle "ALL" — expand to all standard report types
        if ("ALL".equalsIgnoreCase(reportType)) {
            return callCheckReports("BSI,CSR,KAI,EMMS,DSSR", locCds, result);
        }

        // Handle comma-separated types (e.g., "BSI,KAI")
        if (reportType != null && reportType.contains(",")) {
            StringBuilder combinedHtml = new StringBuilder();

            for (String type : reportType.split(",")) {
                type = type.trim();
                if (type.isEmpty()) {
                    continue;
                }

                Map<String, Object> singleArgs = new LinkedHashMap<String, Object>();
                singleArgs.put("reportType", type);
                singleArgs.put("locCds", locCds);

                log.info("[Manual Info]Tool: check_reports (expanded) args: {}", singleArgs);
                String singleResult = mcpClient.callTool("check_reports", singleArgs);
                result.addToolCall("check_reports", singleArgs, singleResult);
                combinedHtml.append(formatAsHtml(singleResult));
            }

            return combinedHtml.toString();
        }

        // Single type — call directly
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("reportType", reportType);
        args.put("locCds", locCds);

        return mcpClient.callTool("check_reports", args);
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
     * DatabaseManager's own default. Capped to 4 digits at the regex level
     * (max 9999) as a first line of defense; DatabaseManager itself clamps
     * to MAX_PSM_LOCATIONS_LIMIT regardless, so this is defense-in-depth,
     * not the only safeguard.
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
}
