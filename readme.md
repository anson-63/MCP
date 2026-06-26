# AIS Assistant Web

A Java web application that lets users ask questions about location data through a chat interface. The app uses a Servlet/JSP frontend, a SQL Server database, an Ollama-based LLM agent that can call tools to search location records, and a **LangGraph-style verification graph** that validates every LLM response before it reaches the user.

## Table of contents

- [Overview](#overview)
- [Tech stack](#tech-stack)
- [User guide](#user-guide)
- [Sample prompts](#sample-prompts)
- [Developer guide](#developer-guide)
- [Verification graph](#verification-graph)
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
- Session memory for multi-code follow-up queries such as "which have BSI report" and "how about KAI?."
- Deterministic query planning for PSM, department, monument, historic building, report checks, and code-history requests.
- Search by department, declared monument status, historic building grade, or code history.
- Optional `location` filter support for department, PSM, monument, and historic building queries.
- Check report availability for multiple location codes or names with grouped `withReport` / `withoutReport` output.
- Display results as formatted HTML tables in the chat view.
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
   - decide which tool to call,
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

### Project structure

```
project-root/
├── pom.xml
├── README.md
└── src/
    ├── main/
    │   ├── java/com/ais/
    │   │   ├── config/
    │   │   │   └── AppConfig.java
    │   │   ├── controller/
    │   │   │   ├── ChatServlet.java         ← entry point, runs verification graph
    │   │   │   ├── LocationServlet.java
    │   │   │   ├── ReportServlet.java
    │   │   │   └── ToolsServlet.java
    │   │   ├── db/
    │   │   │   └── DatabaseManager.java
    │   │   ├── graph/                       ← LangGraph-style verification layer
    │   │   │   ├── AgentGraph.java          ← graph engine (compile + invoke)
    │   │   │   ├── GraphEdge.java           ← conditional routing interface
    │   │   │   ├── GraphNode.java           ← node interface
    │   │   │   ├── GraphState.java          ← shared state passed between nodes
    │   │   │   ├── VerificationGraphFactory.java ← wires all nodes together
    │   │   │   └── nodes/
    │   │   │       ├── PlannerNode.java     ← Node 1: intent detection
    │   │   │       ├── PrimaryLlmNode.java  ← Node 2: calls OllamaService
    │   │   │       ├── VerifierNode.java    ← Node 3: second LLM verification
    │   │   │       ├── FormatterNode.java   ← Node 4: formats approved response
    │   │   │       └── FallbackNode.java    ← Node 5: handles rejected responses
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

### Architecture

- **Presentation layer:** JSP pages under `src/main/webapp/jsp` and servlets in `src/main/java/com/ais/controller`.
- **Controller layer:** `ChatServlet` handles `/api/chat` and runs every prompt through the verification graph. `ToolsServlet` serves `/api/tools`. `LocationServlet` serves `/api/location/schema` and `/api/location/general-info`. `ReportServlet` renders report view pages.
- **Verification graph layer:** `com/ais/graph/` contains the LangGraph-style agent graph. Every chat request passes through five nodes: planner → primary LLM → verifier → formatter (or fallback). See [Verification graph](#verification-graph).
- **Service layer:** `OllamaService`, `MCPClientService`, `QueryPlanner` in `src/main/java/com/ais/service`.
- **Data layer:** `DatabaseManager` in `src/main/java/com/ais/db/DatabaseManager.java` handles SQL, report discovery, slope/TMCP/TMIS helpers, and URL construction.
- **Configuration:** centralized in `src/main/resources/application.properties`, loaded via `src/main/java/com/ais/config/AppConfig.java` with optional external override via `APP_CONFIG_PATH` or `-Dapp.config`.
- **LLM keyword extraction:** `OllamaService.extractKeywords()` runs first and returns `OllamaService.ExtractedKeywords`.
- **Natural language prompt detection:** `QueryPlanner` and `OllamaService` work together to choose fast-path tool calls for conversational queries whenever possible.
- **DB-side location filtering:** common search tools accept an optional `location` argument and apply filtering in SQL rather than Java.
- **Modifier support:** `QueryPlanner` and `executePlan()` support `OLDEST`, `NEWEST`, `FIRST`, and `COUNT`.

### Request flow

```
Browser
  │
  ▼
index.jsp (JSP + Vanilla JS)
  │  POST /api/chat  { "prompt": "..." }
  ▼
ChatServlet.java
  │  builds GraphState, calls verificationGraph.invoke(state)
  ▼
┌─────────────────────────────────────────────────────────────┐
│                   VERIFICATION GRAPH                         │
│                                                             │
│  [PlannerNode]                                              │
│      detect intent from query text                          │
│      ↓ (unconditional edge)                                 │
│  [PrimaryLlmNode]                                           │
│      OllamaService.invoke(prompt, sessionId)                │
│        ├─ extractKeywords()    Phase 1 (always)             │
│        ├─ QueryPlanner.analyse()                            │
│        ├─ executePlan()        fast path                    │
│        └─ runAgentLoop()       LLM path if needed           │
│      ↓ (unconditional edge)                                 │
│  [VerifierNode]                                             │
│      second Ollama call with verification prompt            │
│      returns APPROVED / RETRY / REJECTED                    │
│      ↓ (conditional edge)                                   │
│      ├── APPROVED ──────────► [FormatterNode] → finalResponse
│      ├── RETRY (canRetry) ──► back to PrimaryLlmNode        │
│      └── max retries done ──► [FormatterNode] best effort   │
└─────────────────────────────────────────────────────────────┘
  │
  ▼
ChatServlet builds JSON response:
  { answer, toolCalls, elapsedMs, verified, verificationResult, retries }
  │
  ▼
index.jsp renders answer + tool call accordion in chat UI
```

### Registered Ollama tools

All search tools now accept an optional `location` parameter for DB-side filtering.

| Tool | Args | Description |
|---|---|---|
| `hardcode_query` | `locCd` | Full details + reports for one location code |
| `search_by_name` | `locName`, `location?` | Partial match by name, optional district filter |
| `check_reports` | `reportType`, `locCds[]` | Bulk availability check across locations |
| `list_psms` | _(none)_ | All distinct PSMs with counts |
| `locations_by_psm` | `psm`, `location?` | Locations under a specific PSM |
| `locations_by_dept` | `deptCd`, `location?` | Locations owned/managed by a department |
| `search_declared_monument` | `filter`, `location?` | Declared monument lookup (T/F/ALL) |
| `search_historic_building` | `grade`, `location?` | Historic building lookup by grade |
| `search_loc_cd_history` | `formerLocCd`, `currentLocCd` | Location code history lookup |
| `show_schema` | _(none)_ | Database schema introspection |

### Dynamic quick prompt buttons

Quick prompt buttons in `index.jsp` are fetched from `GET /api/tools` on page load and rendered from tool UI metadata defined in `MCPClientService.getToolDefs()`. To add a new button, add a new `ToolDef` in `MCPClientService.getToolDefs()`. No frontend changes are needed.

### Key files and responsibilities

| File | Responsibility |
|---|---|
| `controller/ChatServlet.java` | Chat API endpoint, builds `GraphState`, invokes verification graph, returns JSON |
| `controller/ToolsServlet.java` | `GET /api/tools` — returns tool UI metadata |
| `controller/LocationServlet.java` | Location lookup endpoints and schema introspection |
| `controller/ReportServlet.java` | Report viewing endpoint and rendering logic |
| `graph/AgentGraph.java` | Graph engine: node registry, edge registry, `compile()`, `invoke()` loop |
| `graph/GraphState.java` | Shared state whiteboard passed between all nodes |
| `graph/VerificationGraphFactory.java` | Wires the five nodes and edges into a compiled graph at startup |
| `graph/nodes/PlannerNode.java` | Node 1: detects intent from query text |
| `graph/nodes/PrimaryLlmNode.java` | Node 2: calls `OllamaService.invoke()`, stores answer in state |
| `graph/nodes/VerifierNode.java` | Node 3: second Ollama call to verify the primary response |
| `graph/nodes/FormatterNode.java` | Node 4: sets `finalResponse` and `success=true` |
| `graph/nodes/FallbackNode.java` | Node 5: returns a safe message when verification fails |
| `service/OllamaService.java` | Keyword extraction, fast-path routing, tool orchestration, agent loop |
| `service/QueryPlanner.java` | Deterministic prompt analysis and multi-step tool planning |
| `service/MCPClientService.java` | Tool definitions, dispatch-table handlers, centralized tool execution |
| `db/DatabaseManager.java` | All SQL access, report metadata, slope helpers |
| `service/ReportTypeRegistry.java` | Registry of report type keys and display names |
| `config/AppConfig.java` | Centralized config loader |
| `webapp/jsp/index.jsp` | Chat UI, dynamic quick prompt buttons |

### Configuration & environment

#### Common config keys

| Key | Description |
|---|---|
| `ollama.base_url` | Ollama server URL (e.g. `http://192.168.1.234:11434`) |
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
| `graph.verification.enabled` | Enable or disable the verifier node |
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
- If Ollama is unreachable, the verifier fails open (auto-approves) so the user still gets an answer.
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

### Deterministic query planning

The app includes a `QueryPlanner` that analyzes prompts and routes them to the right tool chain before using the LLM.

- Multi-step queries such as PSM report checks, department report checks, and code-history lookups are handled end-to-end.
- The planner can chain tools like `locations_by_psm` → `hardcode_query`, or `search_loc_cd_history` → `check_reports`.

### Dynamic quick prompt buttons

Quick prompt buttons are auto-generated from tool definitions in `MCPClientService`. Adding a new tool automatically creates a matching button in the UI.

### Keyword extraction and hybrid routing

`OllamaService` extracts normalized keywords from the user prompt and passes them to `QueryPlanner`, enabling deterministic plans for common phrases while still allowing the LLM to handle ambiguous queries.

### SQL-driven fallback and intent detection

The app detects `SQL_QUERY` intents and attempts direct SQL generation before entering the full agent loop.

### Session memory and follow-up queries

Session memory is stored per HTTP session so users can provide a list of codes once and ask follow-ups like "which have BSI report" without retyping all codes.

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
ollama.base_url=http://192.168.1.234:11434
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
