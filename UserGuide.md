# User Guide — AIS Assistant Web

This guide covers everything an end user or evaluator needs: available features, prerequisites, how to run the app locally, how to use the chat interface, and sample prompts/interactions.

For project overview, tech stack, and architecture, see **[README.md](README.md)**. For internals, API endpoints, and troubleshooting, see **[DevGuide.md](DevGuide.md)**.

## Table of contents

- [Features](#features)
- [Prerequisites](#prerequisites)
- [Run locally](#run-locally)
- [Using the app](#using-the-app)
- [Sample prompts](#sample-prompts)
- [Sample interactions](#sample-interactions)

---

## Features

- Chat-based location and report query interface.
- Search by exact location code (for example, `SB04400361000`) with auto-fallback to name search when input is not a valid code.
- Search by location name or partial text.
- **`/skill` tools list**: quick-access tools are hidden by default and reveal a ranked, numbered list above the chat input the moment the user types `/skill` (optionally followed by a search term, e.g. `/skill historic`). Navigate with `↑`/`↓`, apply the highlighted tool with `Tab`/`Enter`, or click a row directly.
- Natural language prompt detection for conversational or multi-step queries.
- Session memory for multi-code follow-up queries such as "which have BSI report" and "how about KAI?".
- Deterministic query planning for PSM, department, monument, historic building, report checks, and code-history requests.
- **Scalable plan execution**: the LLM returns an ordered `plan` array with priorities and generic relations; `OllamaService.executePlan()` applies `filter_previous`, `enrich_previous`, and `use_previous_codes` generically without hardcoding every intent combination.
- Search by department, declared monument status, historic building grade, or code history.
- **Area/district filtering**: Optional `location` filter support for department, PSM, monument, and historic building queries (e.g., *"Which LCSD location in Sha Tin has BSI and KAI reports?"*), automatically scoping queries before bulk checking reports.
- **Sortable & filterable report lists**: Check report availability for multiple location codes or names with grouped `withReport` / `withoutReport` output rendered as clean HTML tables (`<table class='data-table'>`), enabling UI frontend table widgets (sorting, instant search/filtering, and pagination). Includes dynamic splitting for multi-report requests (e.g., `"ALL"` or `"BSI,KAI"`).
- Display results as formatted HTML tables in the chat view, featuring dynamic column headers for custom LLM queries. Preserves decommissioned/dummy records (`REC_STATUS: D`, `***`) for complete audit compliance.
- Show available report cards with links to open report details.
- Clickable location code links in result tables that open the AIS Asset Search detail page (`/AIS/AssetSearch/index.jsp`), automatically passing the correct `assetType` (`Building` or `Slope`).
- **Sticky location map**: on a single-location detail card, the map `<iframe>` renders as a separate div positioned to the right of the message bubble (not nested inside it) and stays pinned near the top of the viewport while the user scrolls the result content — but never above the message's own top or past its bottom. Collapses to a stacked layout on narrow screens.
- Database schema inspection endpoint, including schema refresh support.
- LLM-driven tool-based responses via Tencent Cloud or Ollama, including TMCP/TMIS, PSM usage, and SQL query generation for complex questions.
- **LangGraph-style response verification: every answer is checked by a second LLM call before being shown to the user, with automatic retry on failure.**
- "Oldest" / "newest" comparison questions (e.g. *"What is the oldest historic building on record?"*) are answered with a single batched database lookup across all candidates rather than fetching each candidate's full detail one at a time, so these questions return in a few seconds even when there are 100+ candidates to compare.
- **Row-limit control**: add `top N` or `first N` to any PSM, department, monument, historic building, or name-search prompt (e.g. *"Show top 50 locations under PSM/KT"*) to cap the number of results returned, instead of always fetching the default page size.
- **Scalable LLM-driven placeholder exclusion**: add any natural language filter like *"with address not null"*, *"with a real address"*, *"valid name"*, or *"with address not undefined"* to exclude decommissioned/placeholder records whose address, name, or department is missing, blank, `-`, or literally the text "UNDEFINED". The LLM semantic engine automatically detects and applies this filter across all search tools without relying on rigid prompt-trapping patterns.

## Prerequisites

- Java 8 (`maven.compiler.source` and `maven.compiler.target` set to `1.8`).
- Apache Maven 3.8+.
- Apache Tomcat 9 (Servlet API 4.0, JSP API 2.3).
- SQL Server database accessible from your machine.
- **Either** a Tencent Cloud API key (`tencent.api.key` in `application.properties`) **or** Ollama installed and reachable at the URL configured in `application.properties`.
- If using Tencent Cloud: a valid model name such as `deepseek-v4-flash`, `deepseek-v3`, or `deepseek-r1`.
- If using Ollama: a compatible model (default: `qwen3:4b-q4_K_M`).

## Run locally

1. Build the project:

```bash
mvn clean package
```

2. Copy `target/ais_ai.war` to your Tomcat 9 `webapps/` directory.

3. Open the web UI at:

```
http://localhost:8090/ais_ai/
```

## Using the app

1. Open the web UI.
2. Enter a location code such as `SB04400361000`, a comma-separated list like `SB04400361000,SB04400362000`, or a location name like `Sha Tin Park`.
3. The assistant will:
   - run the query through the verification graph,
   - decide which tool to call or whether to generate custom SQL,
   - query the database,
   - verify the response with a second LLM call (Tencent Cloud or Ollama),
   - display a formatted result table.
4. Type `/skill` in the chat input to reveal the ranked tools list, then click a row (or navigate with `↑`/`↓` and press `Tab`/`Enter`) to insert that tool's full sample prompt for editing before sending.
5. Click any location code in the result table to open the AIS Asset Search detail page for that location.
6. If related reports exist, it will show report cards with links.
7. When viewing a single location's details, the map appears in a sticky panel to the right of the message so it stays visible while you scroll through the result data.

## Sample prompts

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

**Note on PSM name matching:** a PSM search matches PSMs whose name *starts with* the given term, right after the `PSM/` prefix (e.g. searching `CENTRAL` matches both `PSM/CENTRAL EAST` and `PSM/CENTRAL WEST`, since both start with `CENTRAL`, but will not match a PSM where "central" only appears mid-name). If your search returns 0 results, try `list all PSMs` to see the exact PSM names available. If a search term matches more than one PSM, results from all matching PSMs are combined into a single list.

| Sample prompt | Procedure |
|---|---|
| `Show top 50 locations under PSM/KT` | `locations_by_psm(psm="PSM/KT", limit=50)` — caps the query itself to 50 rows instead of fetching the default and trimming display |
| `Show locations under PSM/KT with address not undefined` | `locations_by_psm(psm="PSM/KT", excludeUndefinedField="address")` — excludes decommissioned/placeholder records with no real address |
| `Show top 20 locations under PSM/KT with address not undefined` | `locations_by_psm(psm="PSM/KT", limit=20, excludeUndefinedField="address")` — both filters combine |
| `Show first 50 locations under PSM central with address not null` | `locations_by_psm(psm="CENTRAL", limit=50, excludeUndefinedField="address")` — LLM natively extracts limit and filter without rigid prompt trapping, propagating across all plan steps |

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
| `Which LCSD location in Sha Tin has BSI and KAI reports?` | `locations_by_dept(deptCd="LCSD", location="Sha Tin")` → extract matched codes → `check_reports(reportType=BSI)` + `check_reports(reportType=KAI)` |
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
| `show graded buildings only` | `search_historic_building(grade=ALL)` + enrich_previous with `gradeFilter=GRADED` |
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
| `Find the first historic location under PSM/KT and show its details` | LLM plan: `PSM_LOCATIONS` (priority 1, `independent`) → `HISTORIC_BUILDING` (priority 2, `filter_previous`, `modifier=FIRST`) → `hardcode_query(firstMatch)` |
| `Which LCSD location in Sha Tin has BSI and KAI reports?` | planner detects multi-intent → SQL generation → SELECT with DEPT_CD + location + EXISTS subqueries for both report types |

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
| `Show historic buildings grade …` | `1` / `2` / `3` / `ALL` / `NONE` / `GRADED` | `Show historic buildings grade 2` | `search_historic_building(grade=2)` |
| `Search location code history for …` | `UD04400253000` | `Search location code history for UD04400253000` | `search_loc_cd_history(...)` → searched code matches former → auto-trigger `hardcode_query(...)` |
| `Check BSI reports for …` | `SB04400361000,SC04400206005` | `Check BSI reports for SB04400361000,SC04400206005` | `check_reports(reportType=BSI, locCds=[...])` |

</details>

---

## Sample interactions

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
