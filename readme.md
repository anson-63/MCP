# AIS Assistant Web

A Java web application that lets users ask questions about location data through a chat interface. The app uses a Servlet/JSP frontend, a SQL Server database, an Ollama-based LLM agent that can call tools to search location records, and a **LangGraph-style verification graph** that validates every LLM response before it reaches the user.

## Table of contents

- [Overview](#overview)
- [Tech stack](#tech-stack)
- [User guide](#user-guide)
  - [Features](#features)
  - [Prerequisites](#prerequisites)
  - [Run locally](#run-locally)
  - [Using the app](#using-the-app)
  - [Sample prompts](#sample-prompts)
  - [Sample interactions](#sample-interactions)
- [Developer guide](#developer-guide)
  - [System Workflow & File Lifecycle Architecture](#system-workflow--file-lifecycle-architecture)
  - [Regex Template Gateway (Zero-LLM Fast Path)](#regex-template-gateway-zero-llm-fast-path)
  - [Registered Ollama tools](#registered-ollama-tools)
  - [Dynamic quick prompt buttons](#dynamic-quick-prompt-buttons)
  - [Configuration & environment](#configuration--environment)
  - [Build and run](#build-and-run)
  - [Production deployment](#production-deployment)
  - [Extending the project](#extending-the-project)
  - [Logging, errors & troubleshooting](#logging-errors--troubleshooting)
- [Verification graph](#verification-graph)
  - [What it is](#what-it-is)
  - [Why it exists](#why-it-exists)
  - [Node descriptions](#node-descriptions)
  - [Edge routing](#edge-routing)
  - [GraphState fields](#graphstate-fields)
  - [LangGraph concept mapping](#langgraph-concept-mapping)
  - [Verifier behavior](#verifier-behavior)
  - [Verifier fail-open design](#verifier-fail-open-design)
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
- deterministic query planning with keyword extraction, pre-validation checks, multi-report expansion, and fast paths,
- **a Regex Template Gateway** for 0ms zero-LLM fast-path execution on standard UI button clicks,
- a tool dispatch table plus UI metadata for dynamic quick prompt generation,
- LLM-driven tool calls via Ollama when needed,
- dynamic T-SQL query generation for cross-table and attribute queries with fully dynamic HTML table rendering,
- **a LangGraph-style multi-node verification graph that validates every LLM response before it is shown to the user**,
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
| Agent graph | Custom Java LangGraph-style state machine |
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
- Session memory for multi-code follow-up queries such as "which have BSI report" and "how about KAI?".
- Deterministic query planning for PSM, department, monument, historic building, report checks, and code-history requests.
- Search by department, declared monument status, historic building grade, or code history.
- Optional `location` filter support for department, PSM, monument, and historic building queries.
- Check report availability for multiple location codes or names with grouped `withReport` / `withoutReport` output. Includes dynamic splitting for multi-report requests (e.g., `"ALL"` or `"BSI,KAI"`).
- Display results as formatted HTML tables in the chat view, featuring dynamic column headers for custom LLM queries. Preserves decommissioned/dummy records (`REC_STATUS: D`, `***`) for complete audit compliance.
- Show available report cards with links to open report details.
- Database schema inspection endpoint, including schema refresh support.
- Ollama tool-based responses, including TMCP/TMIS, PSM usage, and SQL query generation for complex questions.
- **LangGraph-style response verification: every answer is checked by a second LLM call before being shown to the user, with automatic retry on failure.**

### Prerequisites

- Java 8 (`maven.compiler.source` and `maven.compiler.target` set to `1.8`).
- Apache Maven 3.8+.
- Apache Tomcat 9 (Servlet API 4.0, JSP API 2.3).
- SQL Server database accessible from your machine.
- Ollama installed and reachable at the URL configured in `application.properties`.
- A compatible Ollama model (default: `qwen3:4b-q4_K_M`).

### Run locally

1. Build the project:

```bash
mvn clean package
```

2. Copy `target/ais_ai.war` to your Tomcat 9 `webapps/` directory.

3. Open the web UI at:

```
http://localhost:8090/ais_ai/
```

### Using the app

1. Open the web UI.
2. Enter a location code such as `SB04400361000`, a comma-separated list like `SB04400361000,SB04400362000`, or a location name like `Sha Tin Park`.
3. The assistant will:
   - run the query through the verification graph,
   - decide which tool to call or whether to generate custom SQL,
   - query the database,
   - verify the response with a second LLM call,
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
| `check all 5 reports for QA03206005000 QB03106003000` | detect `ALL` reports + extract codes → `check_reports(BSI)` + `check_reports(CSR)` + `check_reports(KAI)` + `check_reports(EMMS)` + `check_reports(DSSR)` |

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
| `Get info for first location code under PSM/KT` | `locations_by_psm(psm="PSM/KT")` → take first `LOC_CD` from results (including decommissioned entries) → `hardcode_query(locCd=firstCode)` |
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
<summary><strong>Department multi-step & attribute prompts</strong></summary>

| Sample prompt | Procedure |
|---|---|
| `Which AFCD locations have BSI report?` | `locations_by_dept(deptCd=AFCD)` → extract all `LOC_CD` → `check_reports(reportType=BSI, locCds=[...])` |
| `Show LCSD locations with KAI report` | `locations_by_dept(deptCd=LCSD)` → extract all `LOC_CD` → `check_reports(reportType=KAI, locCds=[...])` |
| `Show department managing UC07300217003` | Pre-validation detects `deptCd` is null → `needsLlm=true` → Agent loop / SQL Gen → `hardcode_query(UC07300217003)` / SELECT DEPT_CD |
| `show managing department of lo wu` | Pre-validation detects `deptCd` is null → `needsLlm=true` → SQL Gen → SELECT LOC_CD, LOC_NAME, DEPT_CD WHERE LOC_NAME LIKE '%LO WU%' → Dynamic Table |

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
| `Search location code history for UD04400253000` | `search_loc_cd_history(formerLocCd=UD04400253000, currentLocCd=UD04400253000)` → searched code matches former → auto-trigger `hardcode_query(locCd=currentCode)` |
| `what is the new code for UD04400253000` | `search_loc_cd_history(...)` → searched code matches former → auto-trigger `hardcode_query(locCd=currentCode)` |
| `what was the old code for UC04400251000` | `search_loc_cd_history(...)` → searched code matches current → show history only, no auto-trigger |
| `former code UD04400253000` | `search_loc_cd_history(...)` → searched code matches former → auto-trigger `hardcode_query(locCd=currentCode)` |

</details>

---

<details>
<summary><strong>Location code history + report multi-step prompts</strong></summary>

| Sample prompt | Procedure |
|---|---|
| `Find former code of UD04400253000 and check if it has BSI report` | `search_loc_cd_history(...)` → extract current code(s) → `check_reports(reportType=BSI, locCds=[currentCodes])` |
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
| `Search location code history for …` | `UD04400253000` | `Search location code history for UD04400253000` | `search_loc_cd_history(...)` → searched code matches former → auto-trigger `hardcode_query(...)` |
| `Check BSI reports for …` | `SB04400361000,SC04400206005` | `Check BSI reports for SB04400361000,SC04400206005` | `check_reports(reportType=BSI, locCds=[...])` |

</details>

---

### Sample interactions

<details>
<summary><strong>Sample Interactions</strong> (click to expand)</summary>

#### Check report availability for multiple locations

User prompt:

```
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

```
how about KAI report
```

The assistant may reuse the same location list and call `check_reports` with `reportType` = `KAI`.

Example response summary:

- 4 of 5 locations have a KAI report available.
- The app returns report URLs for each available KAI report.

#### Filter department locations and check reports

User prompt:

```
show AFCD locations with BSI report
```

The assistant may execute `locations_by_dept` followed by `check_reports` using the returned AFCD codes.

Example response summary:

- A subset of AFCD locations with BSI reports is returned.
- Location codes without BSI reports are shown separately.

#### Search by name then check report availability

User prompt:

```
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

```
which of these has DSSR report
```

Example result summary:

- 0 of 7 locations have a DSSR report available.
- The response includes all 7 locations as without a DSSR report.

And asking again for KAI:

```
how about KAI report
```

Example result summary:

- 2 of 7 locations have a KAI report available.
- The response shows the two locations with available KAI report links.

</details>

---

## Developer guide

### System Workflow & File Lifecycle Architecture

```
                 ┌─────────────────────────────────────────────────────────┐
                 │                  BROWSER / CLIENT UI                    │
                 │   index.jsp (Chat UI, Quick Prompts) | chat.jsp (Core)  │
                 └────────────────────────────┬────────────────────────────┘
                                              │  POST /api/chat { prompt }
                                              ▼
                 ┌─────────────────────────────────────────────────────────┐
                 │                   CONTROLLER LAYER                      │
                 │   ChatServlet.java | ToolsServlet | LocationServlet     │
                 └────────────────────────────┬────────────────────────────┘
                                              │  Builds GraphState & calls invoke()
                                              ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                             LANGGRAPH VERIFICATION STATE MACHINE                            │
│           AgentGraph.java | GraphNode | GraphEdge | VerificationGraphFactory                │
│                                                                                             │
│  ┌───────────────┐     ┌──────────────────┐     ┌───────────────┐     ┌──────────────────┐  │
│  │  PlannerNode  │────►│  PrimaryLlmNode  │────►│ VerifierNode  │────►│  FormatterNode   │  │
│  └───────────────┘     └────────┬─────────┘     └───────┬───────┘     └──────────────────┘  │
│         │                       │                       │                       ▲           │
│         ▼                       ▼                       ▼                       │           │
│   Detects Intent       OllamaService.invoke()     Ollama Verification           │           │
│   (Intent.java)        (Hybrid Pipeline Map)      (APPROVED / RETRY)            │           │
│                                                         │                       │           │
│                                                         ├─► APPROVED ───────────┤           │
│                                                         ├─► RETRY (Attempts <3)─┼─► Loop    │
│                                                         └─► RETRY (Attempts =3)─┼─► Best    │
│                                                             (VerifierRouter)    │   Effort  │
│                                                                                 │           │
│                                                         [FallbackNode] ─────────┘           │
└─────────────────────────────────────────────┬───────────────────────────────────────────────┘
                                              │ Delegates to Services & DB
                                              ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                           7-PHASE SERVICE & EXECUTION PIPELINE                              │
│                                                                                             │
│  [Phase 0: Memory]  ──► resolveReferentialPrompt() (Excludes verifier feedback)             │
│  [Phase 1: Extract] ──► OllamaService.extractKeywords() → ExtractedKeywords.java            │
│  [Phase 2: Plan]    ──► QueryPlanner.analyse() → Plan.java / IntentRole.java                │
│  [Phase 3: FastPath]──► PipelineExecutor.java → MCPClientService (ToolDef)                  │
│  [Phase 4: SQL Gen] ──► generateAndExecuteSql() → DatabaseManager.java (Dynamic Table)      │
│  [Phase 5: Agent]   ──► runAgentLoop() → MCPTool.java / OllamaRequest.java                  │
│  [Phase 6: Fallback]──► isUselessSqlResult() fallback → ExecutionResult / AgentResult       │
└─────────────────────────────────────────────┬───────────────────────────────────────────────┘
                                              │ Communicates via TCP / JDBC
                       ┌──────────────────────┴──────────────────────┐
                       ▼                                             ▼
        ┌──────────────────────────────┐              ┌──────────────────────────────┐
        │       OLLAMA AI SERVER       │              │    MICROSOFT SQL SERVER      │
        │   (OkHttp 4.x / Kotlin /     │              │    (HikariCP Pool / MSSQL    │
        │    Jackson ObjectMapper)     │              │     JDBC / LocationQuery)    │
        └──────────────────────────────┘              └──────────────────────────────┘
```

---

### 📂 File-by-File Architectural Responsibility Matrix

Every file in the project structure serves a precise role in ensuring lightning-fast static lookups, dynamic LLM reasoning, and zero-trust verification.

#### 1. Configuration & Dependency Layer
* `pom.xml`: Maven build configuration. Manages dependencies, compiler targets (Java 8), and WAR packaging plugins (`pom.properties`).
* `src/main/resources/application.properties` / `bin/application.properties`: Centralized configuration defining `ollama.base_url`, active models (`qwen3:4b-q4_K_M`), HikariCP database credentials, and verification retry limits. Includes `graph.verification.enabled=false` for instant development bypass.
* `src/main/java/com/ais/config/AppConfig.java`: Configuration resolver. Employs a 3-tier fallback hierarchy: environment variables (`APP_CONFIG_PATH`) → classpath properties → hardcoded defaults.

#### 2. Presentation Layer (`src/main/webapp/`)
* `WEB-INF/web.xml`: Deployment descriptor for Apache Tomcat 9. Binds URL patterns (`/api/chat`, `/api/tools`, `/api/location/*`, `/report/view`) to their respective servlets.
* `jsp/index.jsp`: The primary single-page application frontend. Combines Vanilla JavaScript and HTML to manage the chat interface, dynamically render Quick Prompt buttons, and format interactive tool accordions.
* `jsp/chat.jsp`: Dedicated presentation views and modular UI components for chat rendering.

#### 3. Controller Layer (`src/main/java/com/ais/controller/`)
* `ChatServlet.java`: The primary API ingress (`POST /api/chat`). Initializes the LangGraph state machine, constructs `GraphState`, orchestrates the execution graph, and returns structured execution metrics (time elapsed, retries, tool calls) in JSON. Supports direct pass-through when `graph.verification.enabled=false`.
* `ToolsServlet.java`: Serves `GET /api/tools`. Exposes dynamic UI metadata generated by `MCPClientService` to auto-render Quick Prompt buttons in `index.jsp`.
* `LocationServlet.java`: Handles direct location lookups (`/api/location/general-info`) and live database schema introspection (`/api/location/schema`).
* `ReportServlet.java`: Serves `/report/view`. Handles direct rendering and linking for specialized asset reports (`BSI`, `CSR`, `KAI`, `EMMS`, `DSSR`, and `Slope`).

#### 4. LangGraph Verification Layer (`src/main/java/com/ais/graph/`)
* `AgentGraph.java`: The core state machine engine. Compiles the node/edge execution graph and maintains the `invoke()` loop across all verifications.
* `GraphState.java`: The shared blackboard state object passed between nodes. Contains the user query, session ID, detected intent, tool outputs, retry counters, and final HTML response. Includes inner classes `GraphState$NodeStatus` and `GraphState$VerificationResult`.
* `GraphNode.java`: Interface defining the contract for all state machine nodes.
* `GraphEdge.java`: Functional interface defining conditional routing logic between nodes.
* `VerificationGraphFactory.java`: The wiring harness executed during servlet init. Connects all nodes into the 5-step lifecycle (`planner` → `primary_llm` → `verifier` → `formatter` / `fallback`).
* `VerificationGraphFactory$VerifierRouter.class`: The conditional router that inspects `VerifierNode` results. Directs flow to `formatter` on `APPROVED`, loops back to `primary_llm` on `RETRY`, or forces a best-effort render after exhausting `MAX_RETRIES` (3).

#### 5. Graph Node Implementations (`src/main/java/com/ais/graph/nodes/`)
* `PlannerNode.java` *(Step 1)*: Ingress node. Scans raw query text to detect preliminary operational intents (`GENERAL`, `NAME_SEARCH`, `LOCATION_CODE`) and stores them in `GraphState`.
* `PrimaryLlmNode.java` *(Step 2 / Retry Loops)*: The primary action node. Invokes `OllamaService.invoke(...)` to execute keyword extraction, fast-path tool execution, or dynamic SQL generation. Stores the initial HTML response in `state.primaryResponse`.
* `VerifierNode.java` *(Step 3)*: The zero-trust validation node. Fires an independent second LLM call asking Ollama to evaluate if `primaryResponse` accurately answers the prompt. Features a fail-open design (auto-approves if Ollama disconnects or `graph.verification.enabled=false`) and short-circuits known failure patterns (`0 results found`) to `RETRY`.
* `FormatterNode.java` *(Step 4)*: The success handler. Receives `APPROVED` or best-effort retry results, applies final HTML table stylings, and marks `success=true`.
* `FallbackNode.java` *(Step 5)*: The failsafe handler. Replaces broken or completely unresolvable executions with a safe, standardized error message (`success=false`).

#### 6. Service Layer (`src/main/java/com/ais/service/`)
* `OllamaService.java`: The central orchestration hub. Hosts the 7-Phase execution pipeline. Manages referential session memory (`resolveReferentialPrompt`), interacts with Ollama, generates custom T-SQL (`SQL_GENERATE_PROMPT`), and formats dynamic HTML tables. Houses the **Regex Template Gateway** for zero-LLM fast paths.
* `OllamaService$AgentResult.class`: Encapsulates the final output, execution path, and metadata returned to `PrimaryLlmNode`.
* `OllamaService$AgentResult$ToolCallRecord.class`: Audit record tracking exact tool names, JSON arguments, and raw outputs for the UI accordion.
* `QueryPlanner.java`: The deterministic planning gateway. Inspects extracted keywords and pre-validates parameters. Binds both singular (`locCd`) and plural (`locCds`) arguments. Automatically splits multi-report requests (`"ALL"`) into chained execution steps. If explicit parameters exist, it schedules fast-path tools (`needsLlm=false`). If parameters are missing, it hands execution over to the LLM agent (`needsLlm=true`).
* `Plan.java`: Data model representing the execution path decided by `QueryPlanner`.
* `Intent.java` & `IntentRole.java`: Enum and mapping definitions for operational intents (`LOCATION_CODE`, `DEPARTMENT`, `PSM`, `SQL_QUERY`).
* `PipelineExecutor.java`: Executes deterministic multi-step tool plans (e.g., `locations_by_psm` → `check_reports`).
* `MCPClientService.java`: The tool dispatch registry. Binds tool intents to actual database queries and returns metadata for UI buttons.
* `MCPClientService$ToolDef.class`: UI metadata wrapper defining button labels, tooltips, and parameter requirements for Quick Prompts.
* `LocationService.java`: Business logic abstraction wrapping location details and historical code shifts.
* `ReportTypeRegistry.java` & `ReportTypeRegistry$ReportType.class`: Centralized registry defining valid report types (`BSI`, `KAI`, `DSSR`, etc.), their full display names, and target SQL tables.

#### 7. Data Model Layer (`src/main/java/com/ais/model/`)
* `ExtractedKeywords.java`: Strongly typed POJO representing the JSON output of Ollama's keyword extraction phase (`locationCode`, `locationName`, `department`, `modifier`, `intents`).
* `ChatMessage.java`: Represents individual chat turns within the HTTP session.
* `OllamaRequest.java`: Serialization model for constructing outbound JSON prompts to Ollama (`model`, `messages`, `temperature`, `num_ctx`, `/nothink`).
* `MCPTool.java`: Represents available tool function schemas passed to Ollama during agent loop reasoning.
* `ExecutionResult.java` & `LocationResult.java`: Modular wrappers for handling generic tool execution statuses and database query payloads.

#### 8. Database Layer (`src/main/java/com/ais/db/`)
* `DatabaseManager.java`: The robust, pooled SQL Server execution engine. Houses all T-SQL queries, report discovery checks, slope classification helpers, and dynamic `ResultSetMetaData` parsing for custom LLM queries. Features automatic former-to-current code redirects in `getGeneralInfo` and robust `UPPER(PSM) LIKE ?` query matching. Preserves decommissioned (`REC_STATUS: D`) and dummy (`***`) records as true audit data.
* `DatabaseManager$LocationQuery.class`: Internal query wrapper managing parameterized statements for exact location and department filtering.
* `DatabaseManager$1.class`: Anonymous inner class implementations for background resource cleanup and connection handling.

#### 9. Production Libraries (`WEB-INF/lib/`)
* `javax.servlet-api-4.0.1.jar` / `javax.servlet.jsp-api-2.3.3.jar` / `jstl-1.2.jar`: Core Java EE API specifications powering the Tomcat 9 servlet container and JSP rendering.
* `HikariCP-4.0.3.jar`: High-performance JDBC connection pooling mechanism ensuring low-latency access to SQL Server.
* `mssql-jdbc-9.4.1.jre8.jar`: Microsoft SQL Server JDBC Driver (Java 8 compliant).
* `okhttp-4.12.0.jar` / `okio-3.6.0.jar`: The robust HTTP client layer handling TCP communication with the Ollama server. Includes production SSL trust-all bypass logic.
* `kotlin-stdlib-*.jar`: Kotlin standard libraries required by OkHttp 4.x's modern execution engine.
* `jackson-core-2.17.0.jar` / `jackson-databind-2.17.0.jar` / `jackson-annotations-2.17.0.jar`: High-performance JSON parsing ecosystem. Serializes Ollama prompts, unpacks JSON tool outputs, and dynamically iterates over SQL `JsonNode` results.
* `slf4j-api-*.jar` / `logback-classic-1.2.13.jar` / `logback-core-1.2.13.jar`: The centralized logging framework outputting detailed execution paths, error traces, and status emojis (🎯, 🔑, ⚡, 🤖).
* `lombok-1.18.32.jar`: Compile-time annotation processor reducing boilerplate code across data models and services.
* `java-dotenv-5.2.2.jar`: Loads environment variables from `.env` files for seamless local development configuration.
* `byte-buddy-1.14.9.jar` / `annotations-13.0.jar`: Runtime bytecode generation and nullability annotations supporting modern serialization and reflection.

#### 10. Build & Target Artifacts (`target/`)
* `ais_ai.war`: The fully assembled, production-ready Web Archive deployed directly to Tomcat 9's `webapps/` directory.
* `ais-web-1.0-SNAPSHOT/`: Unpacked exploded staging directory utilized by Maven during compilation and asset copying.
* `classes/`: Compiled bytecode hierarchy housing all `.class` files and resource bundles prior to WAR archiving.
* `maven-status/` / `generated-sources/`: Internal Maven compilation cache tracking incremental file modifications.

---

### Regex Template Gateway (Zero-LLM Fast Path)

To achieve 0ms zero-LLM fast-path execution on standard UI button clicks, `OllamaService` utilizes a Regex Template Gateway. If an incoming prompt matches an exact button click template, it manually builds the `ExtractedKeywords` object in Java and returns it instantly, completely bypassing the Ollama extraction phase.

```java
// Inside OllamaService.java

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
```

The primary `extractKeywords` method checks this gateway prior to testing the keyword cache or invoking Ollama:

```java
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
```

---

### Registered Ollama tools

All search tools now accept an optional `location` parameter for DB-side filtering.

| Tool | Args | Description |
|---|---|---|
| `hardcode_query` | `locCd` | Full details + reports for one location code |
| `search_by_name` | `locName`, `location?` | Partial match by name, optional district filter |
| `check_reports` | `reportType`, `locCds[]` | Bulk availability check across locations |
| `list_psms` | *(none)* | All distinct PSMs with counts |
| `locations_by_psm` | `psm`, `location?` | Locations under a specific PSM |
| `locations_by_dept` | `deptCd`, `location?` | Locations owned/managed by a department |
| `search_declared_monument` | `filter`, `location?` | Declared monument lookup (T/F/ALL) |
| `search_historic_building` | `grade`, `location?` | Historic building lookup by grade |
| `search_loc_cd_history` | `formerLocCd`, `currentLocCd` | Location code history lookup |
| `show_schema` | *(none)* | Database schema introspection |

### Dynamic quick prompt buttons

Quick prompt buttons in `index.jsp` are fetched from `GET /api/tools` on page load and rendered from tool UI metadata defined in `MCPClientService.getToolDefs()`. To add a new button, add a new `ToolDef` in `MCPClientService.getToolDefs()`. No frontend changes are needed.

### Configuration & environment

#### Common config keys

| Key | Description |
|---|---|
| `ollama.base_url` | Ollama server URL (e.g. `http://<ollama-ip>:11434`) |
| `ollama.model` | Primary model name (e.g. `qwen3:4b-q4_K_M`) |
| `ollama.verifier.model` | Verifier model name — can be the same or a lighter model |
| `ollama.num_ctx` | Context window size |
| `ollama.temperature` | LLM temperature |
| `ollama.timeout_seconds` | HTTP read timeout for Ollama calls |
| `db.user` | SQL Server username |
| `db.password` | SQL Server password |
| `db.server` | SQL Server host |
| `db.name` | Database name |
| `db.pool.*` | HikariCP pool settings |
| `graph.max.retries` | Max verification retries before best-effort fallback (default: 3) |
| `graph.verification.enabled` | Enable or disable the verifier node (`false` for instant bypass) |
| `graph.verification.timeout.seconds` | Timeout for the verifier Ollama call |

#### Config resolution order

`AppConfig` resolves each key in this order:

1. External file via system property `-Dapp.config` or environment variable `APP_CONFIG_PATH`
2. `application.properties` on the classpath
3. Hardcoded default values inside `AppConfig`

### Build and run

```bash
mvn clean package
```

Deploy `target/ais_ai.war` to Tomcat 9 `webapps/`.

### Production deployment

```bash
sudo systemctl stop tomcat
sudo rm -rf /opt/tomcat/webapps/ais_ai*
sudo cp ais_ai.war /opt/tomcat/webapps/

sudo mkdir -p /opt/ais_ai/config
sudo nano /opt/ais_ai/config/application.properties
sudo chmod 600 /opt/ais_ai/config/application.properties

export APP_CONFIG_PATH=/opt/ais_ai/config/application.properties
sudo systemctl start tomcat
```

Smoke test:

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Tell me about SB04400361000"}' \
  http://localhost:8090/ais_ai/api/chat
```

### Extending the project

#### Add or modify report types

1. Register the new type in `ReportTypeRegistry`.
2. Update `DatabaseManager.getReports(...)` with report metadata.
3. Add SQL queries for the new report type.
4. Extend `buildReportUrl(...)` to construct the right view URL.

#### Add a new tool and quick prompt button

1. Add a new `ToolDef` in `MCPClientService.getToolDefs()` with UI metadata fields.
2. Implement execution logic inside `MCPClientService.callTool(...)`.
3. Add required database queries to `DatabaseManager`.
4. The button appears automatically in `index.jsp` — no frontend changes needed.

#### Add a new graph node

1. Create a class in `com/ais/graph/nodes/` that implements `GraphNode`.
2. Register it in `VerificationGraphFactory.build()` with `graph.addNode("name", node)`.
3. Connect it with `graph.addEdge(...)` or `graph.addConditionalEdge(...)`.

### Logging, errors & troubleshooting

#### Common issues

| Symptom | Likely cause | Fix |
|---|---|---|
| Empty prompt in logs | Frontend Content-Type mismatch | Ensure `Content-Type: application/json` |
| `Invalid column name MOD_TIME` | Table missing timestamp column | `DatabaseManager.getOrderColumn()` auto-detects |
| LLM never responds | Ollama unreachable | Run `curl http://<ollama-ip>:11434/api/tags` |
| Verifier always auto-approves | Wrong `ollama.base_url` (wrong host/port) | Check `ollama.base_url` matches your Ollama server |
| Verifier rejects valid answers | Verifier prompt too strict | `VerifierNode` remaps REJECTED → RETRY; check logs |
| Graph loops indefinitely | `MAX_RETRIES` too high or router bug | Check `GraphState.MAX_RETRIES` and router logic |
| `500` on production only | OkHttp SSL truststore error | See [Known issues & fixes](#known-issues--fixes) |
| `/api/tools` returns 404 | `ToolsServlet` not compiled | Verify class exists in `target/classes/` |
| Infinite retry on failed tool | Keyword cache trapping retries | `extractKeywords()` checks `isRetry` to bypass cache |
| Broken tool calls (`args: {}`) | `QueryPlanner` missing parameters | Pre-validation checks ensure fallback to `needsLlm=true` |
| Empty table on custom SQL | Hardcoded `formatSearchResults` | Ensure `OllamaService` uses dynamic `JsonNode` table builder |

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

#### Graph-specific log patterns

```
→ Executing node: planner (step 1)        node started
  Edge → primary_llm                      unconditional edge taken
→ Executing node: verifier (step 3)       verifier running
[VerifierNode] Result=APPROVED            verification passed
[Router] RETRY → primary_llm (attempt 2) retrying after soft rejection
=== Graph Execution Complete [10863ms, 4 steps] ===
Execution path: [planner [0ms], primary_llm [10ms], verifier [6807ms], formatter [10863ms]]
```

---

## Verification graph

### What it is

The verification graph is a **LangGraph-style state machine** implemented in pure Java. Every chat request passes through a fixed sequence of nodes. Each node reads from and writes to a shared `GraphState` object. Conditional edges decide which node runs next based on the current state.

This pattern is equivalent to the Python LangGraph library but built from scratch to run inside a Java EE servlet container with no additional dependencies.

### Why it exists

Without verification, whatever the primary LLM returns goes directly to the user. With the graph:

- A second LLM call independently checks whether the response actually answers the question.
- If the response is incomplete, the graph retries automatically (up to `MAX_RETRIES` times).
- If all retries fail, the graph falls back to a safe message rather than showing a bad answer.
- Every response shown to the user has been verified by a second model call.

### Node descriptions

| Node | Class | What it does |
|---|---|---|
| `planner` | `PlannerNode` | Detects intent from the query text (LOCATION_CODE, PSM, DEPARTMENT, etc.) and stores it in state |
| `primary_llm` | `PrimaryLlmNode` | Calls `OllamaService.invoke(prompt, sessionId)` which runs keyword extraction, query planning, tool calls, and the agent loop. Stores the answer in `state.primaryResponse` |
| `verifier` | `VerifierNode` | Makes a second Ollama call asking whether the primary response answers the question. Parses the JSON verdict and stores APPROVED / RETRY in state |
| `formatter` | `FormatterNode` | Copies `primaryResponse` to `finalResponse`, sets `success=true` |
| `fallback` | `FallbackNode` | Sets a safe error message as `finalResponse`, sets `success=false` |

### Edge routing

```
planner ──────────────────────────────► primary_llm
primary_llm ──────────────────────────► verifier
verifier (APPROVED) ──────────────────► formatter ──► END
verifier (RETRY, canRetry) ───────────► primary_llm   (increments retryCount)
verifier (RETRY, maxRetries reached) ─► formatter ──► END  (best-effort answer)
```

`REJECTED` is remapped to `RETRY` inside `VerifierNode` so the graph always retries before giving up. Only after `MAX_RETRIES` is exhausted does the router send to `formatter` with the best available answer.

### GraphState fields

| Field | Type | Description |
|---|---|---|
| `userQuery` | `String` | Original user prompt |
| `sessionId` | `String` | HTTP session ID for memory lookup |
| `detectedIntent` | `String` | Intent detected by PlannerNode |
| `primaryResponse` | `String` | HTML answer from PrimaryLlmNode |
| `toolCallsMade` | `List<String>` | Tool names called during primary LLM run |
| `rawToolOutput` | `String` | Text summary of tool outputs |
| `verificationResult` | `enum` | APPROVED / RETRY / REJECTED / SKIPPED |
| `verificationReason` | `String` | Verifier's explanation |
| `retryCount` | `int` | Number of retries so far |
| `finalResponse` | `String` | Response shown to user |
| `success` | `boolean` | Whether the graph completed successfully |
| `executionPath` | `List<String>` | Audit trail of nodes with timestamps |

### LangGraph concept mapping

| LangGraph (Python) | This project (Java) |
|---|---|
| `StateGraph(State)` | `AgentGraph` + `GraphState` |
| `graph.add_node("name", fn)` | `graph.addNode("name", node)` |
| `graph.add_edge("a", "b")` | `graph.addEdge("a", "b")` |
| `graph.add_conditional_edges(...)` | `graph.addConditionalEdge(...)` |
| `graph.set_entry_point(...)` | `graph.setEntryPoint(...)` |
| `graph.compile()` | `graph.compile()` |
| `graph.invoke(state)` | `graph.invoke(initialState)` |
| `TypedDict` state | `GraphState` POJO |
| Node lambda functions | `GraphNode` interface implementations |
| Conditional routing function | `GraphEdge` functional interface |

### Verifier behavior

The verifier node sends a short prompt to Ollama asking for a JSON verdict:

```json
{ "verdict": "APPROVED", "confidence": 0.9, "reason": "Response contains location data" }
```

Rules applied after parsing the verdict:

- `APPROVED` with confidence below 0.4 is treated as `RETRY`.
- `REJECTED` is remapped to `RETRY` (the graph always retries before giving up).
- If Ollama is unreachable or `graph.verification.enabled=false`, the verifier fails open (auto-approves) so the user still gets an answer.
- Responses shorter than 100 characters are auto-approved without calling the verifier.
- Responses matching known failure patterns (`no results found`, `0 results`, etc.) are sent directly to `RETRY` without an Ollama call.

### Verifier fail-open design

The verifier is designed to **never block the user** if the verification service is unavailable:

```
Verifier Ollama unreachable  → APPROVED (auto) → formatter → user sees answer
Verifier response unparseable → APPROVED (auto) → formatter → user sees answer
Verifier times out            → APPROVED (auto) → formatter → user sees answer
```

This means the graph degrades gracefully to the same behavior as before verification was added.

---

## API endpoints

| Method | URL | Description |
|---|---|---|
| `POST` | `/api/chat` | Send a prompt, get a verified AI-generated answer |
| `GET` | `/api/chat?prompt=...` | Browser-friendly prompt testing |
| `GET` | `/api/tools` | Returns tool UI metadata for quick prompt buttons |
| `POST` | `/api/location/general-info` | Get a single location row by `locCd` |
| `GET/POST` | `/api/location/schema` | Database schema introspection |
| `GET` | `/report/view?type=<type>&locCd=<locCd>&reportId=<reportId>` | Render a specific report detail page |

### Chat endpoint

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Tell me about SB04400361000"}' \
  http://localhost:8090/ais_ai/api/chat
```

Example response:

```json
{
  "answer": "<html formatted answer>",
  "toolCalls": [
    {
      "name": "hardcode_query",
      "args": "{}",
      "result": "..."
    }
  ],
  "elapsedMs": 10863,
  "verified": true,
  "verificationResult": "APPROVED",
  "retries": 0
}
```

### Tools endpoint

```bash
curl http://localhost:8090/ais_ai/api/tools
```

### Location schema endpoint

```bash
curl http://localhost:8090/ais_ai/api/location/schema
```

### Location info endpoint

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -d '{"locCd":"SB04400361000"}' \
  http://localhost:8090/ais_ai/api/location/general-info
```

---

## SQL manual inspect

If you want to inspect the database manually before using the app, connect with `sqlcmd` and query schema information directly.

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

---

## New capabilities

### LangGraph-style verification graph

Every chat response is now validated by a second LLM call before it reaches the user. The graph runs five nodes in sequence: intent planner, primary LLM, verifier, formatter, and fallback. The verifier independently checks whether the primary response actually answers the question and triggers automatic retries on failure.

See [Verification graph](#verification-graph) for full details.

### Scalable Hybrid Query Planning & Pre-Validation

The app includes an advanced `QueryPlanner` that pre-validates extracted parameters before dispatching tools.

- **Fast Path (`needsLlm=false`):** Used when the user provides explicit, exact parameters for a tool (e.g., `deptCd=AFCD`), saving LLM compute. Populates both singular (`locCd`) and plural (`locCds`) arguments to ensure dynamic tool execution.
- **Autonomous Path (`needsLlm=true`):** If an intent lacks its required parameter (e.g., asking *about* a department rather than filtering *by* one), `QueryPlanner` aborts the fast-path tool and routes to the LLM Agent Loop or SQL Generator to dynamically solve the request.
- **Dynamic Multi-Report Chaining:** Requests for multiple report types (e.g., `"ALL"` or `"BSI,KAI"`) are automatically expanded and split by `QueryPlanner` into individual `CHECK_REPORTS` tool execution steps in sequence.
- Multi-step queries such as PSM report checks, department report checks, and code-history lookups are handled end-to-end.
- The planner can chain tools like `locations_by_psm` → `hardcode_query`, or `search_loc_cd_history` → `check_reports`.

### Dynamic T-SQL Generation & Table Formatting

For complex cross-table questions or specific attribute inquiries outside tool origins, `OllamaService` generates custom T-SQL `SELECT` statements matching explicit table schemas.

- **Structural Safeguards:** The LLM is explicitly trained on `LOC_CD` (11–15 char codes) vs `DEPT_CD` (short acronyms) to ensure highly accurate T-SQL queries.
- **Dynamic Table Formatting:** `OllamaService` uses Jackson (`JsonNode`) to dynamically inspect `ResultSet` maps. It automatically renders matching table headers and cells for any extra columns the LLM queries (e.g., `DEPT_CD`, `DEPT_DESC`, `PSM`).
- **Audit Compliance:** Database queries preserve decommissioned (`REC_STATUS: D`) and dummy (`***`) records as genuine historical audit data.

### Dynamic quick prompt buttons

Quick prompt buttons are auto-generated from tool definitions in `MCPClientService`. Adding a new tool automatically creates a matching button in the UI.

### Keyword extraction and hybrid routing

`OllamaService` extracts normalized keywords from the user prompt and passes them to `QueryPlanner`, enabling deterministic plans for common phrases while still allowing the LLM to handle ambiguous queries. To prevent verification loops, `extractKeywords()` intelligently bypasses the keyword cache when a verifier retry prompt is detected.

### SQL-driven fallback and intent detection

The app detects `SQL_QUERY` intents and attempts direct SQL generation before entering the full agent loop. If a generated query returns 0 rows or an error, `isUselessSqlResult()` detects the empty table and falls back to the Agent Loop to find the correct answer.

### Session memory and follow-up queries

Session memory is stored per HTTP session so users can provide a list of codes once and ask follow-ups like "which have BSI report" without retyping all codes. `resolveReferentialPrompt()` preserves session integrity by operating independently of verifier feedback notes.

### Slope and specialized report handling

The location detail flow detects slope locations and returns slope-specific report groups plus TMCP/TMIS data.

### Expanded tool coverage

- `locations_by_dept` — lookup locations by department code.
- `search_declared_monument` — find declared monuments, non-monuments, or both.
- `search_historic_building` — find historic buildings by grade.
- `search_loc_cd_history` — lookup former/current location code history.

### Automatic former-code detection and redirect

When searching location code history, the app automatically detects whether the searched code is a former (old) code or the current code by comparing the searched code against the `CURRENT_LOC_CD` values returned from the database.

- If the searched code is **not** in the current codes list → it is a former code → `hardcode_query` is auto-triggered for the current code and full location details are appended below the history table.
- If the searched code **is** the current code → history is shown as-is with no auto-trigger.
- No extra prompt or flag is needed. The detection is automatic on every `CODE_HISTORY` query.

Example:

```
User: "Search location code history for UD04400253000"
  → DB returns: FORMER=UD04400253000, CURRENT=UC04400251000
  → UD04400253000 not in currentCodes → auto-trigger hardcode_query(UC04400251000)
  → Shows history table + full details of UC04400251000
```

---

## Known issues & fixes

### OkHttp SSL truststore error on production (500 on init)

**Symptom:**

```
java.security.KeyStoreException: problem accessing trust store
okhttp3.OkHttpClient.<init>
com.ais.service.OllamaService.<init>
```

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
        log.error("SSL bypass failed: {}", e.getMessage());
        return new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build();
    }
}
```

### Verifier always auto-approves

**Symptom:** Logs show `Verification service unavailable - auto-approved` on every request.

**Cause:** `ollama.base_url` in `application.properties` points to the wrong host or port.

**Fix:** Confirm Ollama is reachable:

```bash
curl http://<ollama-ip>:11434/api/tags
```

Then set the correct URL in `application.properties`:

```properties
ollama.base_url=http://<ollama-ip>:11434
```

### Verifier adds latency

**Symptom:** Each request takes 4–8 seconds longer than before.

**Cause:** The verifier makes a second Ollama call for every response.

**Options:**

- Use a smaller/faster model for verification: `ollama.verifier.model=phi3:mini`
- Reduce verifier context: `num_ctx=512` (already set in `VerifierNode`)
- Disable verification for development: set `graph.verification.enabled=false`

### Duplicate location code / private owner confusion

`LOC_CD` can appear multiple times in historical data. Queries may return the current row by default.

### Department and private-owner data gaps

`locations_by_dept` supports common codes (`AFCD`, `LCSD`, `HD`, `DSD`). If a department has no rows the planner may fall back to broader searches.

---

## Notes

- The project expects location data in the `ais` schema of SQL Server.
- The assistant relies on both the SQL Server data source and the Ollama service.
- The verification graph is compiled once at `ChatServlet.init()` and reused for all requests.
- If `ollama.verifier.model` is not set, it defaults to the same model as `ollama.model`.
- Update `src/main/resources/application.properties` before running in a new environment.
