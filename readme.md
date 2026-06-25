# AIS Assistant Web

A Java web application that lets users ask questions about location data through a chat interface. The app uses a Servlet/JSP frontend, a SQL Server database, and an Ollama-based LLM agent that can call tools to search location records.

## Table of contents

- [Overview](#overview)
- [Tech stack](#tech-stack)
- [User guide](#user-guide)
- [Sample prompts](#sample-prompts)
- [Developer guide](#developer-guide)
- [API endpoints](#api-endpoints)
- [SQL manual inspect](#sql-manual-inspect)
- [New capabilities](#new-capabilities)
- [Known issues & fixes](#known-issues--fixes)
- [Notes](#notes)

---

## Overview

A Java 8 webapp packaged as a WAR and deployed to Tomcat 9. It provides:

- a chat UI for location lookup,
- fast prompt buttons auto-generated from registered Ollama tools,
- exact location code search with fallback name lookup,
- partial location and district name search,
- natural language prompt detection for conversational and multi-step queries,
- optional location/district filtering for department, monument, historic building, and PSM searches,
- report availability checks across multiple locations with clickable links,
- direct report viewing links for BSI/CSR/KAI/EMMS/DSSR and slope-specific report collections,
- department/monument/historic-building/code-history lookup support,
- session memory for follow-up queries such as "which have BSI report",
- deterministic query planning with keyword extraction and fast paths,
- a tool dispatch table plus UI metadata for dynamic quick prompt generation,
- LLM-driven tool calls via Ollama when needed,
- database schema inspection and refresh support.

---

## Tech stack

| Layer | Technology |
|---|---|
| Language | Java 8 |
| Build | Apache Maven 3.8+ |
| Web container | Apache Tomcat 9 |
| Servlet API | Java EE Servlet 4.0 / JSP 2.3 |
| Frontend | JSP + Vanilla JavaScript (no framework) |
| Database | Microsoft SQL Server (via JDBC) |
| Connection pool | HikariCP 4.x |
| HTTP client | OkHttp 4.x (used to call Ollama) |
| AI / LLM | Ollama (local, tool-calling mode) |
| JSON | Jackson (ObjectMapper) |
| Logging | SLF4J + Logback |

> No Spring, no Hibernate, no frontend framework. This is a plain Java EE / vanilla web app.

---

## User guide

### Features

- Chat-based location and report query interface.
- Search by exact location code (for example, `SB04400361000`) with auto-fallback to name search when input is not a valid code.
- Search by location name or partial text.
- Fast prompt buttons automatically generated from registered Ollama tool definitions.
- Natural language prompt detection for conversational or multi-step queries.
- Session memory for multi-code follow-up queries such as "which have BSI report" and "how about KAI?."
- Deterministic query planning for PSM, department, monument, historic building, report checks, and code-history requests.
- Search by department, declared monument status, historic building grade, or code history.
- Optional `location` filter support for department, PSM, monument, and historic building queries.
- Check report availability for multiple location codes or names with grouped `withReport` / `withoutReport` output.
- Display results as formatted HTML tables in the chat view.
- Show available report cards with links to open report details.
- Database schema inspection endpoint, including schema refresh support.
- Ollama tool-based responses, including TMCP/TMIS, PSM usage, and SQL query generation for complex questions.

### Prerequisites

- Java 8 (`maven.compiler.source` and `maven.compiler.target` set to `1.8`).
- Apache Maven 3.8+.
- Apache Tomcat 9 (Servlet API 4.0, JSP API 2.3).
- SQL Server database accessible from your machine.
- Ollama installed and reachable.
- A compatible Ollama model (default: `qwen3:4b-q4_K_M`).

### Run locally

1. Build the project:

```bash
mvn clean package
```

2. Copy `target/ais_ai.war` to your Tomcat 9 `webapps/` directory.

3. Open the web UI at:

```text
http://localhost:8090/ais_ai/
```

### Using the app

1. Open the web UI.
2. Enter a location code such as `SB04400361000`, a comma-separated list like `SB04400361000,SB04400362000`, or a location name like `Sha Tin Park`.
3. The assistant will:
   - decide which tool to call,
   - query the database,
   - display a formatted result table.
4. Use the fast prompt buttons above the chat input to run common queries instantly or prefill the input for custom searches.
5. If related reports exist, it will show report cards with links.

### Sample prompts

<details>
<summary><strong>Direct location lookup</strong></summary>

| Sample prompt | Procedure |
|---|---|
| `SB04400361000` | `hardcode_query(locCd=SB04400361000)` |
| `Get info for SB04400361000` | detect single location code → `hardcode_query(locCd=SB04400361000)` |
| `Tell me about UC04400251000` | detect single location code → `hardcode_query(locCd=UC04400251000)` |

</details>

---

<details>
<summary><strong>Search by location name</strong></summary>

| Sample prompt | Procedure |
|---|---|
| `Sha Tin Park` | simple name search → `search_by_name(locName="Sha Tin Park")` |
| `info of Sha Tin Park` | clean filler words → `search_by_name(locName="Sha Tin Park")` |
| `search for hospital` | clean filler words → `search_by_name(locName="hospital")` |
| `find school` | clean filler words → `search_by_name(locName="school")` |

</details>

---

<details>
<summary><strong>Multi-code memory + report follow-up</strong></summary>

| Sample prompt | Procedure |
|---|---|
| `SB04400361000 SC04400206005` | detect multiple location codes → save codes to memory → show code list |
| `AA00200081000 BB04400174001 RB01800059000` | detect multiple location codes → save codes to memory → show code list |
| `which have BSI report` | use saved codes from memory → `check_reports(reportType=BSI, locCds=memory)` |
| `check KAI and DSSR` | use saved codes from memory → `check_reports(reportType=KAI, locCds=memory)` + `check_reports(reportType=DSSR, locCds=memory)` |
| `which of these has CSR report` | use saved codes from memory → `check_reports(reportType=CSR, locCds=memory)` |

</details>

---

<details>
<summary><strong>Inline report check with codes in same prompt</strong></summary>

| Sample prompt | Procedure |
|---|---|
| `which of these have BSI report: AA00200081000 BB04400174001 RB01800059000` | detect report type + extract codes → `check_reports(reportType=BSI, locCds=[...])` |
| `show KAI report for SB04400361000 SC04400206005` | detect report type + extract codes → `check_reports(reportType=KAI, locCds=[...])` |
| `check DSSR for SB04400361000, SC04400206005` | detect report type + extract codes → `check_reports(reportType=DSSR, locCds=[...])` |

</details>

---

<details>
<summary><strong>PSM queries</strong></summary>

| Sample prompt | Procedure |
|---|---|
| `list all PSMs` | `list_psms()` |
| `show PSMs` | `list_psms()` |
| `how many PSMs are there` | `list_psms()` |
| `show locations under PSM/KT` | `locations_by_psm(psm="PSM/KT")` |
| `PSM/SHA TIN EAST` | detect direct PSM value → `locations_by_psm(psm="PSM/SHA TIN EAST")` |
| `which locations belong to PSM/YAU MA TEI` | extract PSM → `locations_by_psm(psm="PSM/YAU MA TEI")` |

</details>

---

<details>
<summary><strong>PSM multi-step prompts</strong></summary>

| Sample prompt | Procedure |
|---|---|
| `Get info for first location code under PSM/KT` | `locations_by_psm(psm="PSM/KT")` → take first `LOC_CD` from results → `hardcode_query(locCd=firstCode)` |
| `Which locations under PSM/KT have BSI report?` | `locations_by_psm(psm="PSM/KT")` → extract all `LOC_CD` → `check_reports(reportType=BSI, locCds=[...])` |
| `Show first location under PSM/SHEUNG SHUI` | `locations_by_psm(psm="PSM/SHEUNG SHUI")` → take first `LOC_CD` from results → `hardcode_query(locCd=firstCode)` |

</details>

---

<details>
<summary><strong>Department queries</strong></summary>

| Sample prompt | Procedure |
|---|---|
| `show department AFCD` | `locations_by_dept(deptCd=AFCD)` |
| `show locations for department LCSD` | `locations_by_dept(deptCd=LCSD)` |
| `list HD properties` | `locations_by_dept(deptCd=HD)` |
| `show FEHD buildings` | `locations_by_dept(deptCd=FEHD)` |

</details>

---

<details>
<summary><strong>Department multi-step prompts</strong></summary>

| Sample prompt | Procedure |
|---|---|
| `Which AFCD locations have BSI report?` | `locations_by_dept(deptCd=AFCD)` → extract all `LOC_CD` → `check_reports(reportType=BSI, locCds=[...])` |
| `Show LCSD locations with KAI report` | `locations_by_dept(deptCd=LCSD)` → extract all `LOC_CD` → `check_reports(reportType=KAI, locCds=[...])` |

</details>

---

<details>
<summary><strong>Declared monument queries</strong></summary>

| Sample prompt | Procedure |
|---|---|
| `show declared monuments` | `search_declared_monument(filter=T)` |
| `show declared monuments T` | `search_declared_monument(filter=T)` |
| `show declared monuments F` | `search_declared_monument(filter=F)` |
| `show declared monuments ALL` | `search_declared_monument(filter=ALL)` |
| `list monument buildings` | `search_declared_monument(filter=T)` |
| `show non-monument locations` | `search_declared_monument(filter=F)` |

</details>

---

<details>
<summary><strong>Historic building queries</strong></summary>

| Sample prompt | Procedure |
|---|---|
| `show historic buildings` | `search_historic_building(grade=ALL)` |
| `show historic buildings grade 1` | `search_historic_building(grade=1)` |
| `show historic buildings grade 2` | `search_historic_building(grade=2)` |
| `show historic buildings grade 3` | `search_historic_building(grade=3)` |
| `show historic buildings grade NONE` | `search_historic_building(grade=NONE)` |
| `list graded buildings` | `search_historic_building(grade=ALL)` |

</details>

---

<details>
<summary><strong>Location code history queries</strong></summary>

| Sample prompt | Procedure |
|---|---|
| `Search location code history for UD04400253000` | `search_loc_cd_history(formerLocCd=UD04400253000, currentLocCd=UD04400253000)` → if current code found → `hardcode_query(locCd=currentCode)` |
| `what is the new code for UD04400253000` | `search_loc_cd_history(formerLocCd=UD04400253000, currentLocCd=UD04400253000)` → if current code found → `hardcode_query(locCd=currentCode)` |
| `what was the old code for UC04400251000` | `search_loc_cd_history(formerLocCd=UC04400251000, currentLocCd=UC04400251000)` |
| `former code UD04400253000` | `search_loc_cd_history(formerLocCd=UD04400253000, currentLocCd=UD04400253000)` |

</details>

---

<details>
<summary><strong>Location code history + report multi-step prompts</strong></summary>

| Sample prompt | Procedure |
|---|---|
| `Find former code of UD04400253000 and check if it has BSI report` | `search_loc_cd_history(formerLocCd=UD04400253000, currentLocCd=UD04400253000)` → extract current code(s) → `check_reports(reportType=BSI, locCds=[currentCodes])` |
| `Search location code history for UD04400253000 and check KAI report` | `search_loc_cd_history(...)` → extract current code(s) → `check_reports(reportType=KAI, locCds=[currentCodes])` |
| `Get current code for UD04400253000 and show DSSR report` | `search_loc_cd_history(...)` → extract current code(s) → `check_reports(reportType=DSSR, locCds=[currentCodes])` |

</details>

---

<details>
<summary><strong>Schema queries</strong></summary>

| Sample prompt | Procedure |
|---|---|
| `show schema` | `show_schema()` |
| `show database schema` | `show_schema()` |
| `what tables and columns exist` | `show_schema()` |

</details>

---

<details>
<summary><strong>Complex / ambiguous prompts (routed to LLM)</strong></summary>

| Sample prompt | Procedure |
|---|---|
| `Which playground in Lo Wu has a historic status?` | planner detects complex query → LLM/agent planning → likely `search_historic_building(...)` + `search_by_name("Lo Wu")` + result filtering/comparison |
| `Which department manages the oldest historic monument in Lo Wu?` | planner detects complex comparative query → LLM/agent planning → likely `search_declared_monument(...)` + `search_historic_building(...)` + location/name filtering + department inference |
| `Find the first historic location under PSM/KT and show its details` | planner detects multi-intent → `locations_by_psm(...)` + historic filtering + take first result + `hardcode_query(...)` |
| `Which LCSD location in Sha Tin has BSI and KAI reports?` | planner detects multi-intent → likely `locations_by_dept(...)` + location/name filtering + `check_reports(BSI, ...)` + `check_reports(KAI, ...)` + intersection |

</details>

---

<details>
<summary><strong>Quick prompt button examples</strong></summary>

| Button text | User completes with | Final prompt | Procedure |
|---|---|---|---|
| `Get info for …` | `SB04400361000` | `Get info for SB04400361000` | `hardcode_query(locCd=SB04400361000)` |
| `Search location named …` | `Sha Tin Park` | `Search location named Sha Tin Park` | `search_by_name(locName="Sha Tin Park")` |
| `Show locations under PSM …` | `PSM/KT` | `Show locations under PSM PSM/KT` | `locations_by_psm(psm="PSM/KT")` |
| `Show locations for department …` | `AFCD` | `Show locations for department AFCD` | `locations_by_dept(deptCd=AFCD)` |
| `Show declared monuments …` | `T` / `F` / `ALL` | `Show declared monuments T` | `search_declared_monument(filter=T)` |
| `Show historic buildings grade …` | `1` / `2` / `3` / `ALL` / `NONE` | `Show historic buildings grade 2` | `search_historic_building(grade=2)` |
| `Search location code history for …` | `UD04400253000` | `Search location code history for UD04400253000` | `search_loc_cd_history(...)` → if current code found → `hardcode_query(...)` |
| `Check BSI reports for …` | `SB04400361000,SC04400206005` | `Check BSI reports for SB04400361000,SC04400206005` | `check_reports(reportType=BSI, locCds=[...])` |

</details>

---

### Sample interactions

<details>
<summary><strong>Sample Interactions</strong> (click to expand)</summary>

#### Check report availability for multiple locations

User prompt:

```text
which of these have BSI report: AA00200081000 BB04400174001 RB01800059000 SB03206014369 AB01900272000
```

The assistant may call the `check_reports` tool with:

```json
{
  "reportType": "BSI",
  "locCds": ["AA00200081000","BB04400174001","RB01800059000","SB03206014369","AB01900272000"]
}
```

Example response summary:

- 2 of 5 locations have a BSI report available.
- Available reports:
  - `AA00200081000` — Connaught Road Subway
  - `AB01900272000` — CENTRE FOR FOOD SAFETY AT NO. 4 HOSPITAL ROAD
- The other 3 locations are listed without BSI reports.

#### Ask for a different report type

User prompt:

```text
how about KAI report
```

The assistant may reuse the same location list and call `check_reports` with `reportType` = `KAI`.

Example response summary:

- 4 of 5 locations have a KAI report available.
- The app returns report URLs for each available KAI report.

#### Filter department locations and check reports

User prompt:

```text
show AFCD locations with BSI report
```

The assistant may execute `locations_by_dept` followed by `check_reports` using the returned AFCD codes.

Example response summary:

- A subset of AFCD locations with BSI reports is returned.
- Location codes without BSI reports are shown separately.

#### Search by name then check report availability

User prompt:

```text
info of sha tin park
```

This can return multiple matching locations, for example:

- `WA04400000023`
- `SC04400206005`
- `SB04400361000`
- `SB04400361001`
- `SC04400206006`
- `SC04407010001`
- `SB04407024001`

Then ask:

```text
which of these has DSSR report
```

Example result summary:

- 0 of 7 locations have a DSSR report available.
- The response includes all 7 locations as without a DSSR report.

And asking again for KAI:

```text
how about KAI report
```

Example result summary:

- 2 of 7 locations have a KAI report available.
- The response shows the two locations with available KAI report links.

</details>

---

## Developer guide

### Project structure

```text
project-root/
├── pom.xml
├── README.md                          ← not included in WAR
└── src/
    ├── main/
    │   ├── java/com/ais/
    │   │   ├── config/
    │   │   │   └── AppConfig.java
    │   │   ├── controller/
    │   │   │   ├── ChatServlet.java
    │   │   │   ├── LocationServlet.java
    │   │   │   ├── ReportServlet.java
    │   │   │   └── ToolsServlet.java
    │   │   ├── db/
    │   │   │   └── DatabaseManager.java
    │   │   ├── model/
    │   │   │   ├── ChatMessage.java
    │   │   │   ├── LocationInfo.java
    │   │   │   ├── MCPTool.java
    │   │   │   └── OllamaRequest.java
    │   │   └── service/
    │   │       ├── LocationService.java
    │   │       ├── MCPClientService.java
    │   │       ├── OllamaService.java
    │   │       ├── QueryPlanner.java
    │   │       └── ReportTypeRegistry.java
    │   ├── resources/
    │   │   └── application.properties
    │   └── webapp/
    │       ├── jsp/
    │       │   ├── chat.jsp
    │       │   └── index.jsp
    │       ├── META-INF/
    │       │   └── MANIFEST.MF
    │       └── WEB-INF/
    │           ├── web.xml
    │           └── lib/
    └── test/
        └── java/
```

> `README.md`, `pom.xml`, `.classpath`, `.project`, `.settings/`, and `bin/` are **not** included in the
generated WAR file. Only `src/main/webapp/` and compiled classes from `src/main/java/` and
`src/main/resources/` are packaged.
>
> Compiled inner classes now include:
> - `OllamaService$ExtractedKeywords.class`
> - `MCPClientService$ToolDef.class`
> - `DatabaseManager$LocationQuery.class`

### Architecture

- **Presentation layer:** JSP pages under `src/main/webapp/jsp` and servlets in `src/main/java/com/ais/controller`.
- **Controller layer:** `ChatServlet` handles `/api/chat`, `ToolsServlet` serves `/api/tools`, `LocationServlet` serves `/api/location/schema` and `/api/location/general-info`, and `ReportServlet` renders report view pages.
- **Service layer:** `OllamaService`, `MCPClientService`, `QueryPlanner` in `src/main/java/com/ais/service`.
- **Data layer:** `DatabaseManager` in `src/main/java/com/ais/db/DatabaseManager.java` handles SQL, report discovery, slope/TMCP/TMIS helpers, and URL construction.
- **Configuration:** centralized in `src/main/resources/application.properties`, loaded via `src/main/java/com/ais/config/AppConfig.java` with optional external override via `APP_CONFIG_PATH` or `-Dapp.config`.
- **LLM keyword extraction:** `OllamaService.extractKeywords()` runs first and returns `OllamaService.ExtractedKeywords`.
- **Natural language prompt detection:** `QueryPlanner` and `OllamaService` work together to choose fast-path tool calls for conversational queries whenever possible.
- **DB-side location filtering:** common search tools accept an optional `location` argument and apply filtering in SQL rather than Java.
- **Modifier support:** `QueryPlanner` and `executePlan()` support `OLDEST`, `NEWEST`, `FIRST`, and `COUNT`.

### Request flow

```text
Browser
  │
  ▼
index.jsp (JSP + Vanilla JS)
  │  POST /api/chat
  ▼
ChatServlet.java
  │
  ▼
OllamaService.invoke()
  │
  ├─► extractKeywords()          ← Phase 1: always runs (KEYWORD_EXTRACT_PROMPT, 1024 ctx)
  │     Returns: ExtractedKeywords{intent, locationCode, locationName, reportType, department, psm, grade, filter, modifier, rawKeywords}
  │
  ├─► QueryPlanner.analyse(prompt, keywords)
  │     Returns: Plan{steps[], needsLlm}
  │
  ├─── needsLlm=false ──► executePlan()
  │                          buildArgs() → resolveToolName() → callTool() → applyModifier()
  │
  └─── needsLlm=true  ──► runAgentLoop()   ← Phase 2: only for complex queries
  │                            SYSTEM_PROMPT + injected keyword context
  │                            → Ollama LLM selects tools
  │
  ▼
MCPClientService.callTool()     ← dispatch table (handlers map)
  │
  ▼
DatabaseManager                  ← universal query builder + DB-side location filter
  │
  ▼
SQL Server (ais schema + GIS schema)
```

### Registered Ollama tools

All search tools now accept an optional `location` parameter for DB-side filtering. The location filter is applied in SQL and returns only the matching subset of rows.

Tools are defined in `MCPClientService.getToolDefs()` and exposed to the UI via `MCPClientService.listToolsForUI()`. Each tool includes UI metadata that is automatically
rendered as a quick prompt button in `index.jsp` via `GET /api/tools`.

| Tool | Args | Description |
|---|---|---|
| `hardcode_query` | `locCd` | Full details + reports for one location code |
| `search_by_name` | `locName`, `location?` | Partial match by name, optional district filter |
| `check_reports` | `reportType`, `locCds[]` | Bulk availability check across locations |
| `list_psms` | _(none)_ | All distinct PSMs with counts |
| `locations_by_psm` | `psm`, `location?` | Locations under a specific PSM, optional district filter |
| `locations_by_dept` | `deptCd`, `location?` | Locations owned/managed by a department, optional district filter |
| `search_declared_monument` | `filter`, `location?` | Declared monument lookup (T/F/ALL), optional district filter |
| `search_historic_building` | `grade`, `location?` | Historic building lookup by grade, optional district filter |
| `search_loc_cd_history` | `formerLocCd`, `currentLocCd` | Location code history lookup |
| `show_schema` | _(none)_ | Database schema introspection |

### Dynamic quick prompt buttons

Quick prompt buttons in `index.jsp` are **not hardcoded**. They are fetched from `GET /api/tools` on page
load and rendered automatically from tool UI metadata defined in `MCPClientService.getToolDefs()`.

Each tool definition includes:

| Field | Description |
|---|---|
| `icon` | Emoji icon shown on the button |
| `samplePrompt` | Text prefilled into the input box |
| `needsInput` | If `true`, button prefills input and waits; if `false`, button auto-sends |
| `inputHint` | Badge label shown on the button (e.g. `+ location code`) |
| `placeholder` | Input placeholder hint shown after prefill |

To add a new quick prompt button, simply add a new `ToolDef` in `MCPClientService.getToolDefs()` with UI metadata.
No frontend changes are needed.

### Smart routing & memory

`OllamaService.invoke()` now uses `QueryPlanner` to analyze the user prompt and execute a deterministic plan when possible.

- The first phase always runs `extractKeywords()` and populates `OllamaService.ExtractedKeywords`.
- `QueryPlanner.analyse(prompt, keywords)` uses extracted keywords to build a plan or decide whether the agent loop is needed.
- Intent analysis detects PSM, department, historic building, monument, code-history, report checks, and direct code queries.
- Exact `^[A-Z]{2}\d{11}$` input → direct single `hardcode_query` lookup.
- Multiple location codes → codes are saved in session memory and rendered as a code list for follow-up queries.
- Simple name searches may bypass LLM and use `search_by_name` directly.
- `hardcode_query` auto-falls back to `search_by_name` when input is not a valid code.
- If extracted keywords indicate `UNKNOWN` or `SQL_QUERY`, the service first attempts direct SQL generation before the agent loop; if that result is not useful it falls back to LLM-driven tool planning, then to SQL fallback again.
- Modifier support is applied after tool calls: `OLDEST`, `NEWEST`, `FIRST`, and `COUNT`.
- `PSM/xxx` inputs can trigger `locations_by_psm` or `check_reports` over PSM locations.
- Department queries can trigger `locations_by_dept` and optional report checks.
- Code-history requests (`former code`, `old code`, `current code`) use `search_loc_cd_history` and can chain to location lookups or report checks.
- `FETCH_CURRENT` is supported for code-history intents to automatically fetch details for the current LOC_CD if the planner decides it is needed.
- Fallback → Ollama agent loop with registered tools when the prompt is too complex for deterministic planning.

Session memory is stored in `OllamaService.LAST_RESULTS` as a `ConcurrentHashMap` keyed by `HttpSession.getId()`.

- Saved when: `search_by_name`, `locations_by_psm`, `locations_by_dept`, or `search_loc_cd_history` returns results, or when multiple codes are provided.
- Cleared when: a single code lookup occurs or a new unrelated topic starts.
- Lifespan: memory persists until Tomcat restart.

### Slope location support

The app detects slope locations when `A_GENERAL_INFO.LOC_NAME` contains `Slope` (case-insensitive).
For slope locations, standard reports (`BSI`, `CSR`, `KAI`, `EMMS`, `DSSR`) are skipped.

Slope reports come from `ais.Slope_Report_Info`, grouped by URL pattern:

- `BWCS` — Boundary & Works Completion Survey
- `VMI` — Visual Maintenance Inspection
- `RMI` — Routine Maintenance Inspection
- `AMI` — Annual Maintenance Inspection

TMCP/TMIS forms are loaded from six tables and grouped into Form 1 / Form 2:

- `TMCP_FORM_ONE_LINK`, `TMCP_FORM_ONE_NEW_LINK` → Form 1
- `TMCP_FORM_TWO_LINK`, `TMCP_FORM_TWO_NEW_LINK` → Form 2
- `TMIS_FORM_ONE_LINK` → Form 1
- `TMIS_FORM_TWO_LINK` → Form 2

TMCP uses `INSP_DATE`; TMIS uses `APPROVED_DATE`.

### Key files and responsibilities

| File | Responsibility |
|---|---|
| `controller/ChatServlet.java` | Chat API endpoint, forwards prompts to `OllamaService` |
| `controller/ToolsServlet.java` | `GET /api/tools` — returns tool UI metadata for dynamic quick prompt buttons |
| `controller/LocationServlet.java` | Location lookup endpoints and schema introspection |
| `controller/ReportServlet.java` | Report viewing endpoint and rendering logic |
| `service/OllamaService.java` | Keyword extraction, fast-path routing, tool orchestration, and LLM agent loop |
| `service/QueryPlanner.java` | Deterministic prompt analysis and multi-step tool planning; `analyse(prompt, keywords)` accepts extracted keywords |
| `service/MCPClientService.java` | Tool definitions with UI metadata, dispatch-table tool handlers, and centralized tool execution |
| `db/DatabaseManager.java` | All SQL access, report metadata, `buildReportUrl(...)`, slope helpers |
| `service/ReportTypeRegistry.java` | Registry of report type keys and display names |
| `config/AppConfig.java` | Centralized config loader (properties file → env var → system property) |
| `webapp/jsp/index.jsp` | Chat UI, dynamic quick prompt buttons rendered from `/api/tools` |

### Configuration & environment

Runtime configuration is handled through `src/main/resources/application.properties`.

#### Configuration setup

1. Copy `src/main/resources/application.properties.example` to `src/main/resources/application.properties`.
2. Fill in real values for DB credentials, Ollama URL, and other settings.
3. `application.properties` should be gitignored — never commit secrets.

#### Config resolution order

`AppConfig` resolves each key in this order:

1. External file via system property `-Dapp.config` or environment variable `APP_CONFIG_PATH`
2. `application.properties` on the classpath
3. Hardcoded default values inside `AppConfig`

#### Common config keys

| Key | Description |
|---|---|
| `ollama.base_url` | Ollama server URL (e.g. `http://localhost:11434`) |
| `ollama.model` | Model name (e.g. `qwen3:4b-q4_K_M`) |
| `ollama.num_ctx` | Context window size |
| `ollama.temperature` | LLM temperature |
| `ollama.timeout_seconds` | HTTP read timeout for Ollama calls |
| `db.user` | SQL Server username |
| `db.password` | SQL Server password |
| `db.server` | SQL Server host |
| `db.name` | Database name |
| `db.pool.*` | HikariCP pool settings |

Avoid committing secrets. Use CI/CD or local override files for sensitive values.

### Build and run

Build the WAR:

```bash
mvn clean package
```

Deploy the WAR to Tomcat 9 by copying `target/ais_ai.war` to `webapps/`.

### Production deployment

```bash
# On production server
sudo systemctl stop tomcat
sudo rm -rf /opt/tomcat/webapps/ais_ai*
sudo cp ais_ai.war /opt/tomcat/webapps/

# Place external config (NOT in WAR)
sudo mkdir -p /opt/ais_ai/config
sudo nano /opt/ais_ai/config/application.properties
sudo chmod 600 /opt/ais_ai/config/application.properties

export APP_CONFIG_PATH=/opt/ais_ai/config/application.properties
sudo systemctl start tomcat
```

Smoke test the chat API:

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Tell me about SB04400361000"}' \
  http://localhost:8090/ais_ai/api/chat
```

### Extending the project

#### Add or modify report types

1. Register the new type in `ReportTypeRegistry` with a unique key and display label.
2. Update `DatabaseManager.getReports(...)` with the report metadata: schema.table, id column, filename/path column.
3. Add or update SQL queries to fetch records for the new report type.
4. Extend `buildReportUrl(...)` to construct the right view URL.
5. Update UI/JSPs or JSON response fields if the front-end needs the new report data.

#### Add a new tool (and quick prompt button)

1. Add a new `ToolDef` in `MCPClientService.getToolDefs()` with all UI metadata fields
   (`icon`, `samplePrompt`, `needsInput`, `inputHint`, `placeholder`).
2. Implement the tool execution logic inside `MCPClientService.callTool(...)`.
3. Add any required database queries to `DatabaseManager`.
4. The button will appear automatically in `index.jsp` on next page load — no frontend changes needed.
5. Add unit/integration tests to validate the returned JSON structures.

#### PSM support

- PSM exploration is supported via the chat agent using `list_psms` and `locations_by_psm` tool behavior.
- The database queries are implemented in `DatabaseManager.getDistinctPsms()` and
  `DatabaseManager.getLocationsByPsm(String psm)`.
- To extend PSM support, add new tools and/or a REST endpoint if you want a direct HTTP API.

### Testing and verification

- Build and package with Maven.
- Deploy to Tomcat and verify the web UI loads.
- Check quick prompt buttons are rendered (fetched from `GET /api/tools`).
- Verify schema introspection with `GET /api/location/schema`.
- Test API endpoints directly with `curl`.
- Validate tool behavior and JSON output in unit/integration tests.

### Logging, errors & troubleshooting

Logging uses SLF4J with Logback. Check Tomcat logs (`logs/catalina.out`) for errors and stack traces.

#### Common issues

| Symptom | Likely cause | Fix |
|---|---|---|
| Empty prompt in logs | Frontend Content-Type mismatch (JSON vs form-urlencoded) | Ensure `Content-Type: application/json` header |
| `Invalid column name MOD_TIME` | Table missing expected timestamp column | `DatabaseManager.getOrderColumn()` auto-detects; check logs |
| LLM never responds | Ollama unreachable | Run `curl http://<ollama-ip>:11434/api/tags` |
| Slope reports showing `OTHER` | URL pattern not matched by `detectSlopeReportType()` | Check logs for unknown URL pattern |
| `reportId` is ignored in `/report/view` | `ReportServlet` only filters by `LOC_CD` | Fix `getReportData()` to use `reportId` in SQL WHERE clause |
| `500` on production only | OkHttp SSL truststore error | See [Known issues & fixes](#known-issues--fixes) |
| `/api/tools` returns 404 | `ToolsServlet` not compiled or wrong package | Verify `ToolsServlet.class` exists in `target/classes/com/ais/controller/` |

#### Log emoji legend

| Emoji | Meaning |
|---|---|
| 🎯 | Fast path triggered (no LLM call) |
| 🔑 | Keyword extraction phase started |
| ⚡ | SQL-only intent or direct SQL generation path |
| 💡 | SQL result used to answer the prompt |
| 🔄 | Fallback from agent answer to SQL generation |
| 🤖 | Entering the agent loop for complex reasoning |
| 💾 | Session memory saved |
| 🗑️ | Session memory cleared |
| 🧠 | LLM keywords extracted |
| 🔧 | Query planner dispatched a tool chain |
| 🏔️ | Slope location detected |
| 📋 | Slope URL classified |

### Coding conventions

- Follow existing project style for spacing and naming.
- Annotate literal values that cannot be moved to configuration with `//hardcode`.
- Prefer adding configuration keys to `application.properties` for values that may change across environments.

### Contribution workflow

1. Fork and create a feature branch.
2. Run tests and build: `mvn clean package`.
3. Open a PR describing your changes and any database migrations or new config keys.

### Where to look next in code

Follow the request path:

```text
ChatServlet.java → OllamaService → MCPClientService → DatabaseManager
```

---

## API endpoints

| Method | URL | Description |
|---|---|---|
| `POST` | `/api/chat` | Send a prompt, get an AI-generated answer with tool call results |
| `GET` | `/api/chat?prompt=...` | Browser-friendly prompt testing for quick checks |
| `GET` | `/api/tools` | Returns tool UI metadata for rendering quick prompt buttons |
| `POST` | `/api/location/general-info` | Get a single location row by `locCd` |
| `GET/POST` | `/api/location/schema` | Database schema introspection |
| `GET` | `/report/view?type=<type>&locCd=<locCd>&reportId=<reportId>` | Render a specific report detail page in HTML |

### Chat endpoint

The chat servlet accepts both JSON POST and browser-friendly GET.

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Tell me about SB04400361000"}' \
  __domain__/ais_ai/api/chat
```

```bash
curl "__domain__/ais_ai/api/chat?prompt=Tell+me+about+SB04400361000"
```

Example response:

```json
{
  "answer": "<html formatted answer>",
  "toolCalls": [
    {
      "name": "hardcode_query",
      "args": { "locCd": "SB04400361000" },
      "result": "{ ... }"
    }
  ],
  "elapsedMs": 3241
}
```

### Tools endpoint

```bash
curl __domain__/ais_ai/api/tools
```

Example response:

```json
[
  {
    "name": "hardcode_query",
    "description": "Get full location details...",
    "icon": "📋",
    "samplePrompt": "Get info for ",
    "needsInput": true,
    "inputHint": "location code",
    "placeholder": "e.g., SB04400361000"
  }
]
```

### Location schema endpoint

```bash
curl __domain__/ais_ai/api/location/schema
```

### Location info endpoint

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -d '{"locCd":"SB04400361000"}' \
  __domain__/ais_ai/api/location/general-info
```

This endpoint is backed by `LocationServlet` and returns the raw row from `ais.A_GENERAL_INFO` for the requested `locCd`.

Example response:

```json
{
  "LOC_CD": "SB04400361000",
  "LOC_NAME": "Example Location",
  "DEPT_CD": "AFCD",
  "ADDRESS": "123 Example Road"
}
```
```

---

## SQL manual inspect

If you want to inspect the database manually before using the app, connect with `sqlcmd` and query schema
information directly.

### 1. Connect to SQL Server

```bash
sqlcmd -S <server_name_or_ip> -d <database_name> -U <username> -P <password>
```

### 2. List schemas and tables

```sql
SELECT TABLE_SCHEMA, TABLE_NAME
FROM INFORMATION_SCHEMA.TABLES
WHERE TABLE_TYPE = 'BASE TABLE'
ORDER BY TABLE_SCHEMA, TABLE_NAME;
```

### 3. List columns for a specific table

```sql
SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'ais'
  AND TABLE_NAME = 'A_GENERAL_INFO'
ORDER BY ORDINAL_POSITION;
```

### 4. Show all columns for all tables in the `ais` schema

```sql
SELECT
    TABLE_SCHEMA,
    TABLE_NAME,
    COLUMN_NAME,
    DATA_TYPE,
    IS_NULLABLE
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'ais'
ORDER BY TABLE_NAME, ORDINAL_POSITION;
```

### 5. Check a specific row example

```sql
SELECT TOP 10 *
FROM [ais].[A_GENERAL_INFO];
```

> This is useful when you want to confirm exact table names, column names, and sample values before
> updating the app or prompts.

---

## New capabilities
### Deterministic query planning

The app now includes a `QueryPlanner` that analyzes prompts and routes them to the right tool chain before using the LLM.

- Multi-step queries such as PSM report checks, department report checks, and code-history lookups are handled end-to-end.
- The planner can chain tools like `locations_by_psm` → `hardcode_query`, or `search_loc_cd_history` → `check_reports`.
- It preserves simplicity for direct code lookups and simple name searches while still supporting more complex flows.

### Dynamic quick prompt buttons

Quick prompt buttons are auto-generated from tool definitions in `MCPClientService`. Adding a new tool
automatically creates a matching button in the UI with no frontend code changes required.

### Keyword extraction and hybrid routing

`OllamaService` now extracts normalized keywords from the user prompt and passes them to `QueryPlanner`.
This enables deterministic plans for common phrases, tools, and locations while still allowing the LLM to
handle ambiguous or conversational queries.

### SQL-driven fallback and intent detection

The app can detect `SQL_QUERY` intents and attempt direct SQL generation before entering the full agent loop.
This gives a faster path for exact database questions, with a robust fallback to tool-based reasoning if needed.

### Session memory and follow-up queries

Session memory is stored per HTTP session so users can provide a list of codes once and ask follow-ups like
"which have BSI report" or "how about KAI?" without retyping all codes.

### Slope and specialized report handling

The location detail flow now detects slope locations and returns slope-specific report groups plus TMCP/TMIS data,
while non-slope lookups still return BSI/CSR/KAI/EMMS/DSSR report links.

### Expanded tool coverage

New Ollama tools now support:

- `locations_by_dept` — lookup locations by department code (`AFCD`, `LCSD`, `HD`, `DSD`, etc.).
- `search_declared_monument` — find declared monuments, non-monuments, or both.
- `search_historic_building` — find historic buildings by grade.
- `search_loc_cd_history` — lookup former/current location code history.

These tools extend the assistant beyond pure location search and report discovery.

### TMCP / TMIS reports (slope monitoring)

The app recognizes TMCP and TMIS links for slope locations and returns them as clickable URLs in agent
responses. These are surfaced alongside existing report types (BSI, CSR, KAI, DSSR, EMMS).

### PSM lookup and location-by-PSM support

The assistant can list distinct PSM values and return locations under a specific PSM using Ollama tool calls.

### Quick usage examples

Natural-language prompts:

```
show TMCP reports for SB04400361000
```

```
show all PSMs
```

```
which locations belong to PSM/SHA TIN EAST
```

```
show locations for department AFCD
```

```
find declared monuments
```

Chat endpoint example:

```json
{ "prompt": "Which locations have TMIS reports for Sha Tin Park?" }
```

Additional example prompts:

- `list all slopes for department CLP from 2019 to 2024`
- `show locations for department AFCD`
- `find declared monuments`

> Note: there is currently no dedicated `/api/location/search?psm=...` REST endpoint. PSM exploration is
> supported via the chat agent using `list_psms` and `locations_by_psm` tool behavior.

---

## Known issues & fixes

### OkHttp SSL truststore error on production (500 on init)

**Symptom:**

```text
java.security.KeyStoreException: problem accessing trust store
okhttp3.OkHttpClient.<init>
com.ais.service.OllamaService.<init>
```

**Cause:** The production server's JVM truststore (`cacerts`) is corrupted, locked, or has a non-default
password. OkHttp tries to load it during `OkHttpClient` construction.

**Fix:** Build `OkHttpClient` with a trust-all SSL context in `OllamaService`:

```java
private OkHttpClient buildHttpClient() {
    try {
        TrustManager[] trustAllCerts = new TrustManager[]{
            new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
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
```

This is safe because Ollama runs on `localhost` and does not require certificate validation.

---

### Duplicate location code / private owner confusion

- `LOC_CD` can appear multiple times in historical data.
- Queries that use `locCd` may return the current row by default, while private-owner or historical records
  may require additional filtering in the prompt.
- The app uses `SearchScreen` style text matching and schema inspection to choose the most likely row.

### Department and private-owner data gaps

- `locations_by_dept` supports common department codes like `AFCD`, `LCSD`, `HD`, and `DSD`.
- If a department has no rows in the source schema, the planner may fall back to broader category searches.
- Private owner records are only available when present in the `A_GENERAL_INFO` and related location tables.

### Building year and report metadata mismatch

- Some locations have `BUILD_YEAR` or report dates that do not match the physical inspection year.
- The planner preserves the source data and reports this discrepancy in the generated answer when detected.

---

## Notes

- The project expects the database schema to contain location data in the `ais` schema.
- The assistant relies on both the SQL Server data source and the Ollama service to produce answers.
- If your environment differs, update `src/main/resources/application.properties` and the Ollama settings
  before running the app.
