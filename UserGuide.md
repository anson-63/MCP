# User Guide — AIS Assistant Web

This guide covers everything an end user or evaluator needs: available features, prerequisites, how to run the app locally, how to use the chat interface, and sample prompts/interactions.

For project overview, tech stack, and architecture, see **[README.md](README.md)**. For internals, API endpoints, and troubleshooting, see **[DevGuide.md](DevGuide.md)**.

## Table of contents

- [Features](#features)
- [Prerequisites](#prerequisites)
- [Run locally](#run-locally)
- [Signing in and roles](#signing-in-and-roles)
- [Using the app](#using-the-app)
- [Sample prompts](#sample-prompts)
- [Sample interactions](#sample-interactions)

---

## Features

- Authenticated chat-based location and report query interface. API and report routes require an `AIS_USER` or `AIS_ADMIN` identity; database-schema access is restricted to `AIS_ADMIN`.
- Search by exact location code (for example, `SB04400361000`) with auto-fallback to name search when input is not a valid code.
- Search by location name or partial text.
- **`/skill` tools list**: quick-access tools are hidden by default and reveal a ranked, numbered list above the chat input the moment the user types `/skill` (optionally followed by a search term, e.g. `/skill historic`). Navigate with `↑`/`↓`, apply the highlighted tool with `Tab`/`Enter`, or click a row directly.
- Natural language prompt detection for conversational or multi-step queries.
- Session memory for multi-code follow-up queries such as "which have BSI report" and "how about KAI?".
- Deterministic query planning for PSM, department, monument, historic building, report checks, and code-history requests.
- **Scalable plan execution**: the LLM returns an ordered `plan` array with priorities and generic relations; `QueryPlanner` validates the catalog-supported relations and `OllamaService.executePlan()` applies `filter_previous`, `enrich_previous`, and `use_previous_codes` generically without hardcoding every intent combination.
- **Catalog-driven tools**: registered tools expose their descriptions, parameters, supported relations, and UI prompts through one shared catalog. New standard tools automatically become available to the agent and `/skill` list without frontend changes.
- **Composed location queries**: compatible filters such as department, PSM, location/area, historic grade, monument status, location codes, and required reports can be executed through the database-side `location_query` path instead of fetching separate result sets and intersecting limited pages in Java. Non-compatible requests still use the generic relation fallback.
- Search by department, declared monument status, historic building grade, or code history.
- **Area/district filtering**: Optional `location` filter support for department, PSM, monument, historic building, and composed location queries (e.g., *"Which LCSD location in Sha Tin has BSI and KAI reports?"*), allowing the database to scope compatible filters before returning results.
- **Sortable & filterable report lists**: Check report availability for multiple location codes or names with grouped `withReport` / `withoutReport` output rendered as clean HTML tables (`<table class='data-table'>`), enabling UI frontend table widgets (sorting, instant search/filtering, and pagination). `check_reports` accepts registered availability types (`BSI`, `CSR`, `KAI`, `EMMS`, `DSSR`), comma-separated values such as `BSI,KAI`, and the virtual `ALL` aggregate. Multi-report responses are grouped under `checks`; compatible location filters use `location_query`.
- Display results as formatted HTML tables in the chat view, featuring dynamic column headers for custom LLM queries. Preserves decommissioned/dummy records (`REC_STATUS: D`, `***`) for complete audit compliance.
- Show available report cards with links to open report details.
- Clickable location code links in result tables that open the AIS Asset Search detail page (`/AIS/AssetSearch/index.jsp`), automatically passing the correct `assetType` (`Building` or `Slope`).
- **Sticky location map**: on a single-location detail card, the map `<iframe>` renders as a separate div positioned to the right of the message bubble (not nested inside it) and stays pinned near the top of the viewport while the user scrolls the result content — but never above the message's own top or past its bottom. Collapses to a stacked layout on narrow screens.
- Administrator-only database schema inspection endpoint (`AIS_ADMIN` required). Both GET and POST schema requests are checked by the servlet filter and by `LocationServlet` itself.
- Final assistant HTML is sanitized before it is returned to the browser, so unsupported tags, scripts, event handlers, and unsafe URL schemes are removed.
- LLM-driven tool-based responses via Tencent Cloud or Ollama, including TMCP/TMIS, PSM usage, and SQL query generation for complex questions.
- **LangGraph-style response verification: every answer is checked by a second LLM call before being shown to the user.** When the verifier identifies a specific missing database check, the system can perform a bounded catalog-validated repair and verify the patched response again; otherwise it regenerates the answer normally. Repaired data is dynamically formatted into beautiful HTML tables and collapsed under a "📋 Show additional verified data" accordion by default to keep the chat interface clean and compact.
- "Oldest" / "newest" comparison questions (e.g. *"What is the oldest historic building on record?"*) are answered with a single batched database lookup across all candidates rather than fetching each candidate's full detail one at a time, so these questions return in a few seconds even when there are 100+ candidates to compare.
- **Row-limit control**: add `top N` or `first N` to any PSM, department, monument, historic building, or name-search prompt (e.g. *"Show top 50 locations under PSM/KT"*) to cap the number of results returned, instead of always fetching the default page size.
- **Scalable LLM-driven placeholder exclusion**: add a natural-language request such as *"with address not null"*, *"with a real address"*, *"valid name"*, or *"with address not undefined"*. The structured keyword result carries the canonical field, and the database whitelist applies it without adding a prompt-regex rule for each tool.


## Recent behavior and map/report notes

- Security enforcement is enabled by default (`security.enabled=true`). All endpoints (chat, tools, location, reports, and schema) are protected fail-closed under `AuthenticationFilter` and servlet RBAC checks.
- Compound location filters now normally execute as one composed database query. Results include only rows satisfying every requested canonical condition.
- A multi-report request such as `Check BSI,KAI reports for AB01900272000 and AA04400176009` displays separate BSI and KAI availability sections. Each available report includes its own Open Report link.
- Multiple location details use one map panel with a tab for each location code. Selecting a tab changes the active map.
- The map panel always displays the location code it received. If the ArcGIS service is unreachable, unauthenticated, or has no matching feature, the blank iframe is replaced by a readable status message naming the code.
- Internal ArcGIS services may require corporate network/VPN and integrated authentication. Users must not enter ArcGIS credentials into chat.
- A successful zero-row result is a valid answer and should not trigger unrelated retries.

## Prerequisites

- Java 8 (`maven.compiler.source` and `maven.compiler.target` set to `1.8`).
- Apache Maven 3.8+.
- Apache Tomcat 9 (Servlet API 4.0, JSP API 2.3), with a configured Tomcat Realm/user account for local authentication. The active Realm must provide `AIS_USER` and `AIS_ADMIN` roles.
- SQL Server database accessible from your machine.
- **Either** a Tencent Cloud API key (`tencent.api.key` in `application.properties`) **or** Ollama installed and reachable at the URL configured in `application.properties`.
- If using Tencent Cloud: a valid model name such as `deepseek-v4-flash`, `deepseek-v3`, or `deepseek-r1`.
- If using Ollama: a compatible model (default: `qwen3:4b-q4_K_M`).

## Run locally

1. Build the project. The Java 8-compatible OWASP HTML Sanitizer dependency is resolved automatically by Maven:

```bash
mvn clean package
```

A successful build must include the sanitizer dependency in the generated WAR.

2. Copy `target/ais_ai.war` to your Tomcat 9 `webapps/` directory.

3. Configure local Tomcat users in the active `$CATALINA_BASE/conf/tomcat-users.xml` (Windows: `%CATALINA_BASE%\conf\tomcat-users.xml`). Eclipse may use a separate workspace server configuration; use the `Using CATALINA_BASE` path shown in the Tomcat console.

4. Fully restart Tomcat after changing users/roles. Confirm an `AIS_USER` receives `403` for `/api/location/schema` and an `AIS_ADMIN` receives `200`, then open:

```
http://localhost:8090/ais_ai/
```

## Signing in and roles

Local development uses the browser's HTTP BASIC authentication prompt. Production should use the organization's SSO/LDAP/OIDC login instead.

Tomcat reads users from its active server configuration—not from a file inside the application project. A local administrator can merge entries like these into `$CATALINA_BASE/conf/tomcat-users.xml`, replace the passwords, and fully restart Tomcat:

```xml
<role rolename="AIS_USER"/>
<role rolename="AIS_ADMIN"/>
<user username="ais-user"
      password="REPLACE_WITH_A_STRONG_LOCAL_PASSWORD"
      roles="AIS_USER"/>
<user username="ais-admin"
      password="REPLACE_WITH_A_DIFFERENT_STRONG_PASSWORD"
      roles="AIS_USER,AIS_ADMIN"/>
```

| Role | Access |
|---|---|
| `AIS_USER` | Chat, tools list, general location information, and report views |
| `AIS_ADMIN` | All normal user access plus `/api/location/schema` |

The schema restriction is enforced twice: the application authentication filter protects the route, and `LocationServlet` checks `AIS_ADMIN` again before calling `introspectSchema()`. A normal user receives HTTP `403`; an administrator receives the schema response.

The main JSP can load before its protected API requests. If no login dialog appears, open this URL directly once and enter your local Tomcat credentials:

```
http://localhost:8090/ais_ai/api/tools
```

Then return to the main page. For command-line testing:

```bash
curl -i -u 'ais-user:YOUR_LOCAL_PASSWORD' \
  http://localhost:8090/ais_ai/api/tools
```

Expected statuses:

- `200` — login and role succeeded.
- `401` — Tomcat rejected the username/password. Check the active `CATALINA_BASE`, exact password, `tomcat-users.xml`, and restart Tomcat.
- `403` — login succeeded, but the account lacks the route's required role.

A repeating login prompt plus `curl -u` returning `401` is a Tomcat credential/Realm problem, not a chat application problem. After repeated failures, restart Tomcat to clear a possible temporary `LockOutRealm` lock and use a private browser window to avoid cached failed BASIC credentials.

Never use local BASIC credentials over plain HTTP outside a development machine, and never commit real passwords.

## Using the app

1. Open the web UI.
2. Sign in with an account assigned `AIS_USER` or `AIS_ADMIN`. If the browser does not prompt, open `/ais_ai/api/tools` directly first as described above.
3. Enter a location code such as `SB04400361000`, a comma-separated list like `SB04400361000,SB04400362000`, or a location name like `Sha Tin Park`.
4. The assistant will:
   - run the query through the verification graph,
   - decide which tool to call or whether to generate custom SQL,
   - query the database,
   - verify the response with a second LLM call (Tencent Cloud or Ollama),
   - display a formatted result table.
5. Type `/skill` in the chat input to reveal the ranked tools list, then click a row (or navigate with `↑`/`↓` and press `Tab`/`Enter`) to insert that tool's full sample prompt for editing before sending.
6. Click any location code in the result table to open the AIS Asset Search detail page for that location.
7. If related reports exist, it will show report cards with links.
8. When viewing a single location's details, the map appears in a sticky panel to the right of the message so it stays visible while you scroll through the result data.

### Tool discovery

The assistant discovers available tools from the server catalog. Type `/skill` to search the current tool list; registered tools provide their own descriptions and sample prompts. The tool list may grow as the application is extended, so the visible options can differ between deployments.

For multi-step questions, the assistant uses generic relationships between results. For example, it can first find department locations, pass their location codes to a report check, and then narrow or enrich the result without requiring a separate user action.

## Sample prompts

### Composed-query and report-link examples

```text
Find all historic buildings under PSM/CENTRAL that have both BSI and KAI reports. Return only locations satisfying every condition.
```

Expected: one composed query and a location list; no automatic full-detail lookup for the first row.

```text
Check BSI,KAI reports for AB01900272000 and AA04400176009. Show each report link separately.
```

Expected: separate BSI and KAI sections with available links.

```text
Show details and map options for AB01900272000 and AA04400176009.
```

Expected: both detail sections and one tabbed map panel.


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
| `Check BSI and KAI reports for SC04400168007` | extract the code → `check_reports(reportType=BSI,KAI, locCds=[SC04400168007])` → grouped per-report checks |
| `check all 5 reports for QA03206005000 QB03106003000` | extract codes → `check_reports(reportType=ALL, locCds=[...])` → registry expands `ALL` into the registered availability checks under `checks` |

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
| `Show first 50 locations under PSM central with address not null` | `locations_by_psm(psm="CENTRAL", limit=50, excludeUndefinedField="address")` — structured keyword extraction supplies the canonical limit and field; the database clamps and validates both |

</details>

---

<details>
<summary><strong>PSM multi-step prompts</strong></summary>

| Sample prompt | Procedure |
|---|---|
| `Get info for first location code under PSM/KT` | `locations_by_psm(psm="PSM/KT")` → take first `LOC_CD` from results (including decommissioned entries) → `hardcode_query(locCd=firstCode)` |
| `Which locations under PSM/KT have BSI report?` | Prefer `location_query(psm="PSM/KT", reportType="BSI")`; use `locations_by_psm` plus `check_reports` when the planner keeps the request as a generic relation chain. |
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
| `Which AFCD locations have BSI report?` | Prefer `location_query(deptCd=AFCD, reportType="BSI")`; the generic relation chain remains available for non-collapsible plans. |
| `Show LCSD locations with KAI report` | Prefer `location_query(deptCd=LCSD, reportType="KAI")`; the generic relation chain remains available for non-collapsible plans. |
| `Which LCSD location in Sha Tin has BSI and KAI reports?` | Prefer one composed `location_query(deptCd="LCSD", location="Sha Tin", reportType="BSI,KAI")`; the generic relation path remains available when the plan is not collapsible. |
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
| `Show historic buildings under PSM/KT` | Prefer `location_query(psm="KT", grade="ALL")`; if the requested semantics are not represented by the canonical dimensions, use a generic `filter_previous` relation. |

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
<summary><strong>Schema queries (AIS_ADMIN only)</strong></summary>

> Schema introspection is restricted to authenticated administrators. An `AIS_USER` receives HTTP `403`.

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
| `Find the first historic location under PSM/KT and show its details` | Prefer `location_query(psm="KT", grade="ALL")`, then apply the explicit `FIRST` modifier to the resulting codes and fetch the selected detail; use the generic relation path if the plan cannot be collapsed. |
| `Which LCSD location in Sha Tin has BSI and KAI reports?` | Prefer `location_query(deptCd="LCSD", location="Sha Tin", reportType="BSI,KAI")`, which uses trusted `EXISTS` predicates for both report types; SQL generation remains a fallback for unsupported semantics. |

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
