# Developer Guide — AIS Assistant Web

This guide covers implementation internals: the file-by-file architecture, the LangGraph-style verification graph, API endpoints, manual SQL inspection, the capability changelog, and known issues with their fixes.

For project overview, tech stack, and high-level architecture, see **[README.md](README.md)**. For end-user features and sample prompts, see **[UserGuide.md](UserGuide.md)**.

## Table of contents

- [Developer guide](#developer-guide)
  - [System Workflow & File Lifecycle Architecture](#system-workflow--file-lifecycle-architecture)
  - [Structured keyword extraction and catalog routing](#structured-keyword-extraction-and-catalog-routing)
  - [LLM provider routing](#llm-provider-routing)
  - [Registered tools](#registered-tools)
  - [Dynamic quick prompt buttons / `/skill` tools list](#dynamic-quick-prompt-buttons--skill-tools-list)
  - [Authentication and endpoint RBAC](#authentication-and-endpoint-rbac)
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
- [Debugging & testing tools](#debugging--testing-tools)
- [Known issues & fixes](#known-issues--fixes)
- [Notes](#notes)

---

## Developer guide

## July 2026 implementation update

### Canonical composition and performance

`QueryPlanner` and `PlanOptimizer` now preserve a valid canonical filtering prefix when an LLM adds a non-filtering singular detail projection. Compatible steps collapse to one catalog-resolved `location_query`; ordinary tools are still resolved from metadata rather than an orchestration switch. A live `PSM/CENTRAL + grade=ALL + BSI,KAI` run returned eight locations with one tool call and no retry. The database query completed in approximately 306 ms; keyword extraction and verification remained the dominant latency.

The GIS grade and monument predicates use correlated `EXISTS` checks rather than grouping the complete GIS table for each composed query. Production still requires indexes on PSM/LOC_CD, report LOC_CD columns, and GIS LOC_CD/heritage columns.

### Timeout and fallback semantics

JDBC query timeout values are seconds and are configured separately from Hikari connection-acquisition timeouts (milliseconds). A timeout returns a structured `QUERY_TIMEOUT` tool error and must never be converted to an ordinary empty result. The same generated-SQL strategy runs at most once per request. A successful query with zero rows remains a valid answer.

`check_reports` exposes matching rows through top-level `results` while retaining `withReport`, `withoutReport`, and grouped `checks`. Relation chaining consumes `results` only, so locations without the first report do not leak into the next report check.

### Verification graph accounting

`GraphState` tracks full regenerations and targeted repairs separately. `VerifierRouter` prefers one bounded catalog repair when concrete `REINVOKE_TOOL` feedback exists; `PatchRouter` returns a successful repair to the verifier. Infrastructure failures are terminal/bounded rather than repeatedly regenerated. API output exposes `regenerations` and `repairAttempts`; `verified=true` means the final verifier result is `APPROVED`, not merely that formatting succeeded.

### Grouped reports and maps

`OllamaService.formatAsHtml()` recognizes grouped `checks` before generic object formatting and renders each registered report response through the shared bulk-report formatter. Report URLs are stored with raw query delimiters and escaped once at the HTML boundary.

Multi-location details use one sticky map wrapper with location-code tabs. `plugin.html` validates and displays `locCd`, supports point/polyline/polygon geometry, catches both layer-load and query failures, and notifies the parent page with `ok`, `no-feature`, or `error`. The parent replaces unavailable iframes with a code-specific message.

Current infrastructure finding: `domain` resolves internally; HTTP port 80 is reachable but ArcGIS returns `401`, while HTTPS port 443 is unavailable. Use approved Windows/integrated authentication and credentialed CORS, or preferably a constrained same-origin reverse proxy. Never embed usernames, passwords, or long-lived ArcGIS tokens in `plugin.html`/`chat.js`.

### Deployment security warning

Security has been enabled by default (`security.enabled=true`) and fully validated. Endpoint RBAC tests for anonymous (`401`), ordinary user (`200` / allowed), user schema (`403` / forbidden), and admin schema access (`200` / allowed) have been successfully run and verified.


### System Workflow & File Lifecycle Architecture

```
                 ┌─────────────────────────────────────────────────────────┐
                 │                  BROWSER / CLIENT UI                    │
                 │          index.jsp (Chat UI, Quick Prompts)             │
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
│   Detects Intent       OllamaService.invoke()     LLM Verification              │           │
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
│  [Phase 2: Plan]    ──► QueryPlanner.analyse() → Plan.java / IntentStep.java (LLM plan)                │
│  [Phase 3: FastPath]──► OllamaService.executePlan() → ToolDispatcher / location_query              │
│  [Phase 4: SQL Gen] ──► generateAndExecuteSql() → DatabaseManager.java (Dynamic Table)      │
│  [Phase 5: Agent]   ──► runAgentLoop() (Dynamic tool-call schema maps)                      │
│  [Phase 6: Fallback]──► isUselessSqlResult() fallback → ExecutionResult / AgentResult       │
└─────────────────────────────────────────────┬───────────────────────────────────────────────┘
                                              │ Communicates via TCP / JDBC
                  ┌───────────────────────────┼───────────────────────────┐
                  ▼                           ▼                           ▼
   ┌──────────────────────────┐ ┌──────────────────────────┐ ┌──────────────────────────┐
   │   TENCENT CLOUD API      │ │     OLLAMA AI SERVER     │ │   MICROSOFT SQL SERVER   │
   │  (lkeap, OpenAI-compat.) │ │  (local, tool-calling)   │ │  (HikariCP / MSSQL JDBC) │
   │  Used when API key set   │ │  Used when no API key    │ │                          │
   └──────────────────────────┘ └──────────────────────────┘ └──────────────────────────┘
```

> **Security ingress:** Before any controller shown above executes, `AuthenticationFilter` intercepts `/api/*` and `/report/*`. It returns `401` when Tomcat cannot establish a trusted principal and `403` when the principal lacks the route role. Only an authorized request reaches `ChatServlet`, the graph, LLM, tools, or database.

---

### 📂 File-by-File Architectural Responsibility Matrix

Every file in the project structure serves a precise role in ensuring lightning-fast static lookups, dynamic LLM reasoning, and zero-trust verification.

#### 1. Configuration & Dependency Layer
* `pom.xml`: Maven build configuration. Manages dependencies, compiler targets (Java 8), and WAR packaging plugins.
* `src/main/resources/application.properties` / `bin/application.properties`: Centralized configuration defining `ollama.base_url`, `tencent.api.*` keys, active models, HikariCP database credentials, and verification retry limits. Includes `graph.verification.enabled=false` for instant development bypass.
* `src/main/java/com/ais/config/AppConfig.java`: Centralized configuration resolver and single source of truth. Employs a 3-tier fallback hierarchy: environment variables (`APP_CONFIG_PATH` / `-Dapp.config`) → classpath properties → hardcoded defaults. Exposes `useTencentCloud()`, `getTencentApiKey()`, `getTencentBaseUrl()`, `getTencentModel()`, and `verifierModel()` (supporting model overrides across both Tencent Cloud and Ollama modes).

#### 2. Presentation Layer (`src/main/webapp/`)
* `WEB-INF/web.xml`: Deployment descriptor for Apache Tomcat 9. Registers `AuthenticationFilter` for `/api/*` and `/report/*`, declares `AIS_USER`/`AIS_ADMIN`, configures the local BASIC login mechanism, and binds URL patterns (`/api/chat`, `/api/tools`, `/api/location/*`, `/report/view`) to their respective servlets.
* `jsp/index.jsp`: The primary single-page application frontend (HTML + CSS only, plus one tiny inline bootstrap `<script>`). Declares `isELIgnored="true"` so JS template literals like `${...}` inside `js/chat.js`-style code are never misread as JSP Expression Language. Exposes the deployment context path as `window.APP_CONTEXT_PATH` (via `<%= request.getContextPath() %>`) for the external script to read, since plain `.js` files are served statically and never see JSP scriptlets. Contains the `.ais-detail-btn`, `.location-map-sticky`, `.message-row`, and `#quick-prompts` CSS used by location code links, the sticky map layout, and the `/skill` tools list.
* `jsp/js/chat.js`: All chat UI logic, extracted from `index.jsp` for a smaller JSP and normal JS tooling/linting support. Handles sending messages, rendering tool-call accordions, the `/skill` tools list (ranking, rendering, `↑`/`↓` navigation, `Tab`/`Enter`/click-to-apply), and `extractMapBlock()` — which detaches the server-rendered `.location-map-block` from a location detail message and re-parents it as a true DOM sibling (`.location-map-sticky`) instead of leaving it nested inside the message bubble. Must be served from `src/main/webapp/js/chat.js` (see [Known issues & fixes](#known-issues--fixes) for the WAR-packaging rule that applies to any static asset under `src/main/webapp/`).

#### 3. Controller Layer (`src/main/java/com/ais/controller/`)
* `ChatServlet.java`: The primary API ingress (`POST /api/chat`). It executes only after `AuthenticationFilter` establishes an authenticated `AuthorizationContext`. Delegates property resolution entirely to `AppConfig.java` during initialization (ensuring external Linux configuration overrides like `APP_CONFIG_PATH` are respected across the app). Initializes the LangGraph state machine, constructs `GraphState`, orchestrates the execution graph, and returns structured execution metrics (time elapsed, retries, tool calls) in JSON. Sanitizes the final LLM-generated HTML answer with an OWASP Java HTML Sanitizer allowlist immediately before JSON serialization, returns safe JSON error objects, disables wildcard CORS, and sets no-store/nosniff response headers. Supports direct pass-through when `graph.verification.enabled=false`.
* `ToolsServlet.java`: Serves `GET /api/tools`. Exposes dynamic UI metadata generated by `MCPClientService` to auto-render Quick Prompt buttons in `index.jsp`.
* `LocationServlet.java`: Handles direct location lookups (`/api/location/general-info`) and database schema introspection (`/api/location/schema`). Both GET and POST schema branches require `AIS_ADMIN` before calling `DatabaseManager.introspectSchema()`, in addition to the global authentication filter.
* `ReportServlet.java`: Serves `/report/view`. Handles direct rendering and linking for specialized asset reports (`BSI`, `CSR`, `KAI`, `EMMS`, `DSSR`, and `Slope`).

#### Security Layer (`src/main/java/com/ais/security/`)
* `AuthenticationFilter.java`: Fail-closed servlet filter mapped to `/api/*` and `/report/*`. Calls the Tomcat/container authentication mechanism, returns `401` for missing/invalid identity and `403` for insufficient role, applies `AIS_ADMIN` to `/api/location/schema`, and sets baseline security headers.
* `AuthorizationContext.java`: Immutable request-scoped identity/role object derived only from the trusted container principal. Its department allowlist starts empty and must never be interpreted as unrestricted data access.
* `SecurityGuards.java`: Defense-in-depth helper used at the start of the schema handler so a future filter-mapping regression cannot expose schema introspection.

#### 4. LangGraph Verification Layer (`src/main/java/com/ais/graph/`)
* `AgentGraph.java`: The core state machine engine. Compiles the node/edge execution graph and maintains the `invoke()` loop across all verifications.
* `GraphState.java`: The shared blackboard state object passed between nodes. Contains the user query, session ID, detected intent, tool outputs, retry counters, structured `VerifierFeedback`, targeted-repair status, and final HTML response.
* `VerifierFeedback.java`: Bounded verifier output model containing the suggested action, missing catalog tool names, suggested arguments, reason, and confidence. Its tool suggestions are untrusted until `PatchNode` validates them.
* `GraphNode.java`: Interface defining the contract for all state machine nodes.
* `GraphEdge.java`: Functional interface defining conditional routing logic between nodes.
* `VerificationGraphFactory.java`: The wiring harness executed during servlet init. Reads `graph.verification.enabled`, `ollama.baseUrl`, `ollama.model`, and `verifier.model` from `application.properties`. Connects all nodes into the 5-step lifecycle (`planner` → `primary_llm` → `verifier` → `formatter` / `fallback`). When verification is disabled, the verifier node is skipped and `primary_llm` routes directly to `formatter`.
* `VerificationGraphFactory$VerifierRouter.class`: The conditional router that inspects `VerifierNode` results. Directs flow to `formatter` on `APPROVED`, routes concrete `REINVOKE_TOOL` feedback to `PatchNode`, routes ordinary `RETRY` feedback to `primary_llm`, and forces a best-effort render after the separate regeneration/repair budgets are exhausted. `PatchRouter` sends successful repairs back through verification.

#### 5. Graph Node Implementations (`src/main/java/com/ais/graph/nodes/`)
* `PlannerNode.java` *(Step 1)*: Ingress node. Scans raw query text to detect preliminary operational intents and stores them in `GraphState`.
* `PrimaryLlmNode.java` *(Step 2 / Retry Loops)*: The primary action node. Invokes `OllamaService.invoke(...)` to execute keyword extraction, fast-path tool execution, or dynamic SQL generation. Stores the initial HTML response in `state.primaryResponse`.
* `VerifierNode.java` *(Step 3)*: The zero-trust validation node. Delegates verification to `OllamaService.callLlmSimple()` which routes to Tencent Cloud or Ollama based on config. The model used is `verifier.model` (or `ollama.model` as fallback) read from `application.properties`. It emits structured `VerifierFeedback` for concrete missing-tool repairs, fail-opens if the LLM service is unreachable, and short-circuits only empty/technical failure summaries to `RETRY`; a successful zero-row database result remains a valid answer. Does **not** manage its own HTTP client — all routing is handled by `OllamaService`.
* `PatchNode.java` *(targeted recovery)*: Validates verifier-suggested tool names and arguments through the catalog/dispatcher, executes only the configured bounded repair calls, appends dynamically formatted, collapsible HTML details and native tables (instead of raw JSON dumps) of the repair output to the primary response, and routes successful repairs back to `VerifierNode`.
* `FormatterNode.java` *(Step 4)*: The success handler. Receives `APPROVED`, `null` (verification disabled), or best-effort retry results, applies final HTML table stylings, and marks `success=true`.
* `FallbackNode.java` *(Step 5)*: The failsafe handler. Replaces broken or completely unresolvable executions with a safe, standardized error message (`success=false`).

#### 6. Service Layer (`src/main/java/com/ais/service/`)
* `OllamaService.java`: The central orchestration hub. Hosts the 7-phase execution pipeline, referential session memory, LLM provider routing, keyword extraction, SQL generation, agent loop, and HTML formatting. Its `executePlan()` consumes catalog-validated `Intent` steps and applies the generic relations `filter_previous`, `enrich_previous`, and `use_previous_codes`; it does not contain a case for each tool. Detail-tool resolution, argument conversion, code extraction, intersection, and enrichment are driven by standardized result contracts and `ToolDefinition` metadata. It also contains `findByYear()` for the domain-specific OLDEST/NEWEST comparison and the `AgentResult` audit wrapper.
* `OllamaService$AgentResult.class`: Encapsulates the final output and per-call metadata returned to `PrimaryLlmNode`. `addToolCall(name, args, result)` is recorded exactly once per real tool invocation.
* `OllamaService$AgentResult$ToolCallRecord.class`: Audit record tracking the exact tool name, JSON arguments, and raw output for one invocation. `PrimaryLlmNode` copies these records into `GraphState.toolCallDetails`.
* `QueryPlanner.java`: The planning gateway. It sorts LLM plan steps by priority, normalizes relations, resolves types and aliases through `MCPClientService`, validates supported relations and consumed fields, permits previous-code inputs for `use_previous_codes`, and preserves explicit modifiers on their intended step. Invalid first-step filtering/enrichment relations and unsupported plans are rejected.
* `Plan.java`: Data model representing the execution path decided by `QueryPlanner`.
* `Intent.java` & `IntentRole.java`: Lightweight execution models. Catalog intent types may be represented as strings; built-in constants remain for compatibility and display roles.
* `PipelineExecutor.java`: Helper for executing deterministic multi-step tool plans. Currently imported by `OllamaService` for compatibility; the active plan execution path is `OllamaService.executePlan()`.
* `MCPClientService.java`: Compatibility facade over the catalog and dispatcher. Existing callers use `listTools()`, `listToolsForUI()`, `resolveDefinition()`, `getAcceptedParameters()`, and `callTool()` without knowing where tools are registered.
* `ToolRegistryFactory.java`: Builds the in-process `ToolCatalog`. It currently contains the existing anonymous database-backed executors and registration helpers; these may later be split into domain-specific provider classes.
* `ToolCatalog.java`: Stores `ToolRegistration` objects, resolves intent types/aliases, exposes accepted parameters, and produces function-calling schemas.
* `ToolDispatcher.java`: Validates arguments and invokes the selected `ToolProvider`; it does not register tools or contain planner logic.
* `ToolRegistration.java`: Combines `ToolDefinition`, description, JSON properties, UI metadata, and a `ToolProvider` executor.
* `ToolDefinition.java`: Declarative metadata for a tool: name, intent types, aliases, required parameters, supported relations, produced fields, consumed fields, and planner enablement.
* `ToolProvider.java`: Provider abstraction used by the catalog. A provider exposes its `ToolDefinition` and executes a validated argument map.
* `ReportTypeRegistry.java`: Centralized registry defining valid report types, display names, aliases, availability table names, trusted identifier columns, Java 8-compatible report normalization, and the virtual `ALL` aggregate. Availability types are `BSI`, `CSR`, `KAI`, `EMMS`, and `DSSR`; slope-only display types do not expose availability-table metadata.
* `QueryDimensionRegistry.java`: Database-dimension registry used by the composed `location_query` path. It maps canonical parameters to trusted `QueryPredicate` builders and is independent of tool names.

#### 7. Data Model Layer (`src/main/java/com/ais/model/`)
* `ExtractedKeywords.java`: Strongly typed POJO representing the JSON output of the keyword extraction phase. Includes `intents`, `primaryIntent`, `showDetails`, and the ordered `plan` array.
* `IntentStep.java`: One entry in the LLM-generated `plan` array. Contains `type`, `priority`, `params`, and `relation` (`independent`, `filter_previous`, `enrich_previous`, `use_previous_codes`).
* `OpenAiRequest.java`: Serialization model for constructing outbound JSON prompts to Tencent Cloud (OpenAI-compatible format). Contains inner `Message` class with `role` and `content` fields.
* `ExecutionResult.java` & `LocationResult.java`: Modular wrappers for handling generic tool execution statuses and database query payloads.

#### 8. Database Layer (`src/main/java/com/ais/db/`)
* `DatabaseManager.java`: The robust, pooled SQL Server execution engine. Houses all T-SQL queries, report discovery checks, slope classification helpers, and dynamic `ResultSetMetaData` parsing for custom LLM queries. Features automatic former-to-current code redirects and robust `UPPER(RTRIM(PSM)) LIKE ?` trailing-wildcard query matching (see [Known issues & fixes](#known-issues--fixes) for why the wildcard direction matters). Preserves decommissioned and dummy records as true audit data. Includes a `logSql(sql, params...)` debug-logging helper (gated on `log.isDebugEnabled()`) that every query-building method should call immediately before `executeQuery()`, and `getGeneralInfoBatch(locCds)`, which fetches `LOC_NAME`/`DEPT_CD`/`DEPT_DESC`/`BLDG_COMPLETION_YEAR` for up to 200 codes in **one** batched `IN (...)` query — used by `OllamaService.findByYear()` to avoid one-hardcode_query-per-candidate lookups.

#### 9. Production Libraries (`WEB-INF/lib/`)
* `javax.servlet-api-4.0.1.jar` / `javax.servlet.jsp-api-2.3.3.jar` / `jstl-1.2.jar`: Core Java EE API specifications.
* `HikariCP-4.0.3.jar`: High-performance JDBC connection pooling.
* `mssql-jdbc-9.4.1.jre8.jar`: Microsoft SQL Server JDBC Driver (Java 8 compliant).
* `okhttp-4.12.0.jar` / `okio-3.6.0.jar`: HTTP client layer used for both Tencent Cloud API calls and Ollama calls. Includes production SSL trust-all bypass logic.
* `kotlin-stdlib-*.jar`: Kotlin standard libraries required by OkHttp 4.x.
* `jackson-core-2.17.0.jar` / `jackson-databind-2.17.0.jar` / `jackson-annotations-2.17.0.jar`: JSON parsing ecosystem.
* `slf4j-api-*.jar` / `logback-classic-1.2.13.jar` / `logback-core-1.2.13.jar`: Centralized logging framework.
* `lombok-1.18.32.jar`: Compile-time annotation processor.
* `java-dotenv-5.2.2.jar`: Loads environment variables from `.env` files.
* `byte-buddy-1.14.9.jar` / `annotations-13.0.jar`: Runtime bytecode generation and nullability annotations.
* `owasp-java-html-sanitizer`: Java 8-compatible HTML allowlist sanitizer used by `ChatServlet` to remove unsafe LLM-generated markup before it reaches the browser.

#### 10. Build & Target Artifacts (`target/`)
* `ais_ai.war`: The fully assembled, production-ready Web Archive.
* `ais-web-1.0-SNAPSHOT/`: Unpacked exploded staging directory.
* `classes/`: Compiled bytecode hierarchy.
* `maven-status/` / `generated-sources/`: Internal Maven compilation cache.

---

### Structured keyword extraction and catalog routing

The current request path uses structured keyword extraction and catalog metadata rather than a growing list of semantic prompt-regex templates. `OllamaService.extractKeywords()` uses the keyword cache when appropriate and otherwise asks the configured LLM for an `ExtractedKeywords` JSON object containing canonical fields, an ordered plan, and generic relations.

The catalog remains the source of truth for tool names, accepted parameters, supported relations, produced fields, and UI metadata. `QueryPlanner` validates the plan, `PlanOptimizer` merges compatible canonical dimensions into `location_query`, and `ToolDispatcher` validates the final invocation.

Security and structural validation are separate concerns. Prompt-injection filtering, location-code recognition, SQL safety checks, and row-limit clamping may use bounded patterns, but new business tools must not be selected by adding another prompt regex or another orchestrator switch.

For Java 8 compatibility, registry and query code uses ordinary loops and `Collections.unmodifiableList(...)`; it must not use `String.isBlank()`, `List.copyOf(...)`, `Map.of(...)`, `Stream.toList()`, or `Collectors.toUnmodifiableList()`.

### LLM provider routing

`OllamaService` supports two LLM backends. The active provider is selected automatically at startup based on whether `tencent.api.key` is set in `application.properties`.

```
tencent.api.key is set
        │
        ├── YES → All LLM calls route to Tencent Cloud API
        │         URL:   tencent.api.base_url + /chat/completions
        │         Model: tencent.api.model (e.g. deepseek-v4-flash)
        │         Auth:  Authorization: Bearer <key>
        │
        └── NO  → All LLM calls route to Ollama
                  URL:   ollama.base_url + /api/chat
                  Model: ollama.model (e.g. qwen3:4b-q4_K_M)
                  Auth:  none
```

All three call sites use the same unified method:

| Call site | Method used | Purpose |
|---|---|---|
| Keyword extraction | `callLlmSimple(prompt, 0.0, 1024)` | Extract intent + parameters from user prompt |
| SQL generation | `callLlmSimple(prompt, 0.0, 1500)` | Generate T-SQL SELECT from user question |
| Agent loop | `callOllama(messages, tools, systemPrompt)` | Multi-turn tool-calling loop |
| Verifier | `callLlmSimple(prompt, 0.0, 512)` | Verify primary response quality |

`VerifierNode` delegates entirely to `OllamaService.callLlmSimple()` and does not manage its own HTTP client or model selection.

---

### Registered tools

Tools are registered in `ToolRegistryFactory.registerTools()` and exposed through the shared catalog. The same metadata is used for:

```text
agent tools/list → QueryPlanner → buildArgs() → callTool() → UI /api/tools
```

A registration contains:

| Metadata | Purpose |
|---|---|
| `toolName` | Runtime function name and unique registry key |
| `intentTypes` / `aliases` | Planner resolution and LLM plan type matching |
| `requiredParameters` | Planner and runtime validation |
| `supportedRelations` | Allowed `independent`, `filter_previous`, `enrich_previous`, or `use_previous_codes` relations |
| `producedFields` | Fields made available to later steps |
| `consumedFields` | Fields required from a previous step; `use_previous_codes` tools must declare `LOC_CD` |
| JSON properties | Function-calling schema sent to the LLM |
| UI metadata | `/api/tools` and `/skill` prompt generation |
| executor | Deterministic Java/database implementation |

Tools that consume previous location codes must accept either `locCds` or `locCd`, declare `LOC_CD` in `consumedFields`, and return a standard code-bearing result where possible. List tools should return:

```json
{
  "count": 2,
  "results": [
    {
      "LOC_CD": "AA12345678901",
      "LOC_NAME": "Example Location"
    }
  ]
}
```

This lets `OllamaService` perform generic code extraction, filtering, enrichment, session memory, and rendering without adding a new execution branch.

#### Current registered tools

All applicable search tools accept an optional `location` parameter for database-side filtering. The five list-returning tools additionally accept `limit` and `excludeUndefinedField`. `check_reports` accepts one registered availability type, a comma-separated list, or the virtual `ALL` aggregate; the database expands these through `ReportTypeRegistry`. Compatible multi-filter plans use the `location_query` registration and the database-side `QueryDimensionRegistry`; they do not require a separate hardcoded tool-combination branch.

| Tool | Args | Description |
|---|---|---|
| `hardcode_query` | `locCd` | Full details + reports for one location code |
| `search_by_name` | `locName`, `location?`, `limit?`, `excludeUndefinedField?` | Partial match by name |
| `check_reports` | `reportType`, `locCds[]` | Bulk report availability |
| `list_psms` | *(none)* | Distinct PSMs with counts |
| `locations_by_psm` | `psm`, `location?`, `limit?`, `excludeUndefinedField?` | Locations under a PSM |
| `locations_by_dept` | `deptCd`, `location?`, `limit?`, `excludeUndefinedField?` | Locations managed by a department |
| `search_declared_monument` | `filter`, `location?`, `limit?`, `excludeUndefinedField?` | Declared monument lookup |
| `search_historic_building` | `grade`, `location?`, `limit?`, `excludeUndefinedField?` | Historic building lookup |
| `search_loc_cd_history` | `formerLocCd`, `currentLocCd` | Location-code history lookup |
| `location_query` | `locName?`, `location?`, `psm?`, `deptCd?`, `grade?`, `filter?`, `reportType?`, `locCd?`, `locCds?`, `limit?`, `excludeUndefinedField?` | Composed database-side location query |

#### Composed location-query path

`location_query` is the generic database-backed composition path. The flow is:

```text
PlanOptimizer
    → canonical parameter map
    → ToolDispatcher.callTool("location_query", args)
    → DatabaseManager.executeLocationQuery(Map)
    → QueryDimensionRegistry
    → QueryPredicate list
    → LocationQuery SQL builder
```

The registry currently maps canonical database dimensions such as `locName`, `location`, `psm`, `deptCd`, `grade`, `filter`, `locCd`, `locCds`, and report requirements. `ReportTypeRegistry.normalizeReportTypes(...)` expands `ALL` and comma-separated report values through registered availability metadata. `reportType` is the canonical report filter key; `requiredReports` may remain as a compatibility alias at the database-dimension boundary. Report table and identifier-column lookup comes from `ReportTypeRegistry`, not from a tool switch or `ReportServlet`.

The availability metadata is centralized as follows:

| Type | Table | Identifier column |
|---|---|---|
| `BSI` | `ais.BSI_GENERAL_INFO` | `BLDG_SAFETY_INSP_REPORT_NO` |
| `CSR` | `ais.CS_PLAN` | `FILE_PATH_AUTOCAD` |
| `KAI` | `ais.KAI_RECORD_PLANS_AND_DRAWINGS` | `AUTOCAD_PATH` |
| `EMMS` | `ais.OLD_EMMS` | `REPORT_LINK` |
| `DSSR` | `ais.DSSR_REPORT` | `REPORT_NO` |

Slope-only types such as `BWCS`, `VMI`, `RMI`, `AMI`, and `TMCP` remain valid display/report types but do not participate in availability-table queries. `ReportType` exposes `getTableName()`, `getIdColumn()`, and `hasAvailabilityTable()` for the database layer.

Invalid required-report values fail closed instead of silently removing the filter. Unknown planner metadata such as `modifier` is not interpolated into SQL. The existing generic relation executor remains the fallback for plans that cannot be expressed by these canonical dimensions.

A multi-report `check_reports` response is grouped under `checks`, with one standard availability response per registered type. Formatters should render this structure generically rather than adding one branch per report type.

#### Adding a new tool

For a standard new tool, changes are normally limited to `ToolRegistryFactory` plus its database/service implementation:

1. Add the deterministic database/service method.
2. Add a `registerTool(...)` entry in `ToolRegistryFactory.registerTools()`.
3. Define accepted and required parameters in the JSON schema.
4. Declare supported relations.
5. Declare produced fields.
6. If it consumes previous location codes, declare `consumedFields` containing `LOC_CD` and accept `locCds` or `locCd`.
7. Return a standard JSON result with `count` and `results` where applicable.
8. Add unit/integration tests for direct execution and every supported relation.

No `OllamaService` switch statement is required for a standard tool. No frontend change is required: `listTools()` supplies agent schemas and `listToolsForUI()` supplies `/api/tools` metadata.

If the tool must be selected by the keyword-extraction fast path, its aliases and semantics must also be added to the catalog-driven keyword prompt or the fixed `KEYWORD_EXTRACT_PROMPT`. Agent-loop discovery alone is automatic; fast-path semantic extraction is not fully dynamic yet.

### Dynamic quick prompt buttons / `/skill` tools list

Tool definitions are fetched once from `GET /api/tools` on page load (`js/chat.js`'s `loadTools()`), but the list stays hidden until the user types `/skill` in the chat input — `updateSkillTrigger()` toggles the `#quick-prompts` panel's `visible` class on every keystroke. Typing a search term after `/skill` (e.g. `/skill historic`) re-ranks the list live via `rankTools()` / `scoreTool()`:

| Score | Match type |
|---|---|
| 3 | Exact tool name match |
| 2 | Tool name starts with the query |
| 1 | Tool name contains the query |
| 0.5 | Sample prompt or description contains the query |
| 0 | No query typed — show all tools, unranked (registration order) |

The panel renders as a numbered list (`1. hardcode_query: get general info`, `2. ...`), not pill buttons. The highlighted (`.active`) row can be moved with `↑`/`↓` (`moveSkillSelection()`), and is applied to the input — filled with that tool's full `samplePrompt` — via `Tab`, `Enter`, or a click (all three funnel into `applyToolPrompt()`). Repeated `Tab` presses cycle forward through the ranked matches only when the highlighted row hasn't changed since the last apply (tracked via `appliedIndex`), so an `↑`/`↓` selection followed by `Tab` always applies exactly the highlighted row instead of skipping past it.

To add a new tool button/list entry, register a new `ToolDefinition`/`ToolProvider` through `ToolRegistryFactory.registerTools()`. No frontend changes are needed — `js/chat.js` renders whatever `GET /api/tools` returns.

### Authentication and endpoint RBAC

#### Security package

The authentication and authorization layer is implemented under:

```text
src/main/java/com/ais/security/
├── AuthenticationFilter.java
├── AuthorizationContext.java
└── SecurityGuards.java
```

`AuthenticationFilter` is registered in `WEB-INF/web.xml`, not with `@WebFilter`, to avoid double execution. It maps to `/api/*` and `/report/*`, invokes `HttpServletRequest.authenticate(response)` when no trusted principal exists, and checks roles before the request can reach a servlet, LLM, MCP tool, or database query.

Authentication and authorization are deliberately distinct:

- Missing or invalid identity → HTTP `401`.
- Valid identity without the required role → HTTP `403`.
- Valid role → attach an immutable `AuthorizationContext` request attribute and continue.

| Route | Required role |
|---|---|
| `/api/chat`, `/api/tools` | `AIS_USER` or `AIS_ADMIN` |
| `/api/location/general-info` | `AIS_USER` or `AIS_ADMIN` |
| `/api/location/schema` | `AIS_ADMIN` only |
| Other `/api/*` routes | `AIS_USER` or `AIS_ADMIN` |
| `/report/*` | `AIS_USER` or `AIS_ADMIN` |

Administrators are treated as users for ordinary endpoints even if the container exposes only `AIS_ADMIN`. `LocationServlet` performs a direct `isUserInRole("AIS_ADMIN")` check in both GET and POST schema branches before schema introspection, providing defense in depth if a filter mapping changes.

The active Tomcat Realm is operational. Keep the anonymous `401`, user `403`, admin `200`, and allowed-route smoke tests in the deployment pipeline.
#### `web.xml` registration

The minimal descriptor contains these security elements inside the existing `<web-app>` root:

```xml
<filter>
    <filter-name>authenticationFilter</filter-name>
    <filter-class>com.ais.security.AuthenticationFilter</filter-class>
    <async-supported>true</async-supported>
</filter>
<filter-mapping>
    <filter-name>authenticationFilter</filter-name>
    <url-pattern>/api/*</url-pattern>
    <dispatcher>REQUEST</dispatcher>
</filter-mapping>
<filter-mapping>
    <filter-name>authenticationFilter</filter-name>
    <url-pattern>/report/*</url-pattern>
    <dispatcher>REQUEST</dispatcher>
</filter-mapping>

<login-config>
    <auth-method>BASIC</auth-method>
    <realm-name>AIS Assistant</realm-name>
</login-config>

<security-role><role-name>AIS_USER</role-name></security-role>
<security-role><role-name>AIS_ADMIN</role-name></security-role>
```

The real XML must contain raw `<`/`>` characters and plain namespace URLs. HTML entities such as `&lt;web-app&gt;` or Markdown links such as `[http://...](http://...)` are not valid content for the deployed `web.xml`.

BASIC authentication is the dependency-free local development mechanism. Production must use HTTPS and should replace it with a corporate Tomcat Realm/LDAP/OIDC/SSO integration. The filter remains unchanged as long as the container supplies authoritative values through `getUserPrincipal()` and `isUserInRole()`.

#### Local `tomcat-users.xml`

Tomcat does not read a `tomcat-users.xml` placed inside the application project or WAR. Merge local roles/users into the active server file:

```text
Linux/macOS: $CATALINA_BASE/conf/tomcat-users.xml
Windows:     %CATALINA_BASE%\conf\tomcat-users.xml
```

Eclipse WTP often uses a separate configuration under the workspace (for example `.metadata/.plugins/org.eclipse.wst.server.core/tmpN/conf/`). Read the `Using CATALINA_BASE` line in the startup console and edit that configuration, not an assumed Tomcat installation path.

Known-good local-only entries:

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

Role names are case-sensitive. Do not commit real credentials. Fully stop and restart Tomcat after changing the user database; an Eclipse incremental republish is insufficient. Repeated failed attempts may trigger `LockOutRealm`, so correct the credentials and restart before retesting.

The active `server.xml` normally includes both the `UserDatabase` global resource (with `pathname="conf/tomcat-users.xml"`) and a `UserDatabaseRealm` nested under `LockOutRealm`. If either is absent, the container cannot validate these users.

#### Authentication diagnostics

```bash
BASE=http://localhost:8090/ais_ai

# Anonymous: 401, with no LLM/tool/DB work
curl -i "$BASE/api/tools"

# Normal user: 200 on ordinary route
curl -i -u 'ais-user:YOUR_LOCAL_PASSWORD' "$BASE/api/tools"

# Normal user: 403 on admin schema route
curl -i -u 'ais-user:YOUR_LOCAL_PASSWORD' \
  "$BASE/api/location/schema"

# Admin: passes authentication/RBAC for schema route
curl -i -u 'ais-admin:YOUR_ADMIN_PASSWORD' \
  "$BASE/api/location/schema"
```

A repeating browser prompt plus `curl -u` returning `401` proves the failure is in Tomcat identity validation—not AIS role logic. Check the active `CATALINA_BASE`, exact password, `tomcat-users.xml` filename, `UserDatabaseRealm`, any configured credential-digest handler, and perform a full restart. A `403` instead proves authentication succeeded and the role assignment is wrong.

The public JSP is not covered by the current filter mappings, so it can load before its protected API fetch. Some browsers do not show a BASIC login dialog for `fetch`; open `/api/tools` directly once or use `curl -u` for local diagnosis. Production SSO/form login should provide the real user experience.

#### Security completion criteria

The authentication change is not operational merely because the classes compile. Before marking Issue #8 fixed:

- Tomcat must accept a trusted identity in the deployed environment.
- Anonymous calls to every protected route must return `401` before any application work.
- `AIS_USER` must receive `403` from schema access.
- `AIS_ADMIN` must pass the schema role check.
- Direct `/report/view` navigation must not bypass authentication.
- Automated `401`, `403`, and successful-route tests must pass.
- Cookie/session or browser-cached credential deployments must add CSRF protection to state-changing requests.

### Output HTML sanitization

`ChatServlet` receives HTML assembled by `OllamaService` and returns it as the JSON `answer` field. Because the browser renders the answer as HTML, the final answer is sanitized immediately before JSON serialization.

The policy is implemented with OWASP Java HTML Sanitizer and allows only the elements needed by the application: tables, table cells, basic text formatting, links, report cards, and the location map iframe. Attributes are allowlisted (`class`, table spans, link attributes, and iframe dimensions/source), and only `http`/`https` URL schemes are accepted. Scripts, event-handler attributes such as `onerror`, unsafe URLs, styles, and unsupported elements are removed. The Java 8 dependency is resolved and compilation is clean; browser regression tests remain part of release validation.

The dependency must be declared in `pom.xml` and packaged into `WEB-INF/lib`:

```xml
<dependency>
    <groupId>com.googlecode.owasp-java-html-sanitizer</groupId>
    <artifactId>owasp-java-html-sanitizer</artifactId>
    <version>20220608.1</version>
</dependency>
```

Do not replace the sanitizer with regular expressions. The Java 8 dependency is resolved and the source compiles cleanly; keep the dependency in the WAR and run browser XSS regression tests. Database-derived values are escaped once at the HTML field boundary; do not escape an already-generated HTML document a second time, or values such as `&amp;` become `&amp;amp;`.

### Configuration & environment

#### Common config keys

| Key | Description |
|---|---|
| `ollama.base_url` | Ollama server URL (e.g. `http://<ollama-ip>:11434`) — used when no Tencent key is set |
| `ollama.model` | Ollama model name (e.g. `qwen3:4b-q4_K_M`) — used when no Tencent key is set |
| `tencent.api.key` | Tencent Cloud API key — if set, all LLM calls route to Tencent instead of Ollama |
| `tencent.api.base_url` | Tencent Cloud API base URL (default: `https://api.lkeap.cloud.tencent.com/v1`) |
| `tencent.api.model` | Tencent model name (e.g. `deepseek-v4-flash`, `deepseek-v3`, `deepseek-r1`, `hunyuan-pro`) |
| `verifier.model` | Verifier model override. If unset, falls back to `ollama.model` / `tencent.api.model` depending on provider |
| `ollama.num_ctx` | Context window size (Ollama only) |
| `ollama.temperature` | LLM temperature |
| `ollama.timeout_seconds` | HTTP read timeout for LLM calls |
| `db.user` | SQL Server username |
| `db.password` | SQL Server password |
| `db.server` | SQL Server host |
| `db.name` | Database name |
| `db.pool.*` | HikariCP pool settings |
| `graph.max_regenerations` | Maximum full primary-pipeline regenerations (recommended default: 1) |
| `graph.max_repair_attempts` | Maximum targeted verifier repair rounds (recommended default: 1) |
| `db.query_timeout_seconds` | JDBC statement timeout in seconds; separate from `db.connection_timeout` in milliseconds |
| `agent.loop.max_iterations` | Maximum tool-calling iterations in one primary attempt |
| `agent.loop.timeout_ms` | Agent-loop wall-clock limit in milliseconds |
| `graph.verification.enabled` | Enable or disable the verifier node (`false` for instant bypass) |
| `graph.verification.timeout.seconds` | Timeout for the verifier LLM call |

#### Tencent Cloud model names

| Model | `tencent.api.model` value |
|---|---|
| DeepSeek V4 Flash | `deepseek-v4-flash` |
| DeepSeek V3 | `deepseek-v3` |
| DeepSeek R1 | `deepseek-r1` |
| Hunyuan Lite | `hunyuan-lite` |
| Hunyuan Standard | `hunyuan-standard` |
| Hunyuan Pro | `hunyuan-pro` |

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

# Configure the production Tomcat Realm/enterprise SSO before starting.
# Do not deploy the local tomcat-users.xml example or BASIC auth over HTTP.
sudo systemctl start tomcat
```

The production identity provider must expose `AIS_USER` and `AIS_ADMIN` through `request.isUserInRole(...)`. Terminate TLS before credentials reach the application, restrict direct backend access when a trusted reverse proxy supplies identity, and never trust caller-provided identity headers.

Minimum `application.properties` for Tencent Cloud mode:

```properties
tencent.api.key=sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
tencent.api.base_url=https://api.lkeap.cloud.tencent.com/v1
tencent.api.model=deepseek-v4-flash

db.server=your-sql-server
db.name=your-database
db.user=your-user
db.password=your-password
```

Authenticated smoke test (after configuring the active Tomcat Realm/user database):

```bash
curl -u 'ais-user:YOUR_LOCAL_PASSWORD' -X POST \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Tell me about SB04400361000"}' \
  http://localhost:8090/ais_ai/api/chat
```

### Extending the project

#### Add or modify report types

1. Register the new type in `ReportTypeRegistry`.
2. For an availability report, provide its trusted table and identifier-column metadata there; `DatabaseManager` consumes the registry and should not gain a report-type switch.
3. Add SQL/query behavior only when the new report has semantics that are not expressible through the existing registry dimensions.
4. Extend `buildReportUrl(...)` to construct the right view URL when the report needs a new URL strategy.

`ReportServlet` has a separate controller-level mapping for report-view types such as `survey` and `maintenance`; do not use that private mapping as the availability-table registry.

Implementation invariants:

- Keep the availability-table/id-column metadata in `ReportTypeRegistry`; do not add another report-type switch to `DatabaseManager`.
- Keep the registry constructors unambiguous for Java 8. The metadata form uses an explicit alias collection; the alias-only form may use varargs.
- `QueryDimensionRegistry.QueryDimension` must be a concrete lambda-compatible class accepting `BiConsumer<LocationQuerySpec, String>`.
- `QueryDimensionRegistry` maps database dimensions, not individual tools. Do not register a separate query contributor for every tool.

#### Add a new tool and quick prompt entry

1. Add the deterministic database/service method.
2. Register the tool in `ToolRegistryFactory.registerTools()` with `ToolDefinition`, JSON properties, UI metadata, and an executor.
3. Declare intent types/aliases, required parameters, supported relations, and produced fields.
4. If the tool consumes previous location codes, declare `LOC_CD` in `consumedFields` and accept `locCds` or `locCd`.
5. Return a standard `{count, results}` response with `LOC_CD` rows where applicable.
6. Ensure the factory's `definition(...)` helper passes `consumedFields` to the full `ToolDefinition` constructor; otherwise `use_previous_codes` validation will reject the tool even when its registration looks correct.
7. Add direct-call and relation tests.
8. The tool appears automatically in `listTools()`, the agent loop, `/api/tools`, and `/skill`; no frontend changes are needed.
9. If fast-path keyword extraction must select the new tool, update the catalog-generated or fixed keyword prompt with its semantic guidance.

Separate provider classes are optional at this stage. The factory may keep anonymous `ToolProvider` executors and can be split by domain later.Do not add a new tool-specific branch to `OllamaService.executePlan()`. If a new tool needs behavior that cannot be expressed by the standard relations or result contract, first extend the catalog/provider contract rather than adding another intent switch.

#### Add a new graph node

1. Create a class in `com/ais/graph/nodes/` that implements `GraphNode`.
2. Register it in `VerificationGraphFactory.build()` with `graph.addNode("name", node)`.
3. Connect it with `graph.addEdge(...)` or `graph.addConditionalEdge(...)`.

#### Switch LLM provider

To switch from Tencent Cloud back to Ollama, remove or leave blank the `tencent.api.key` in `application.properties`. No code changes are needed.

### Logging, errors & troubleshooting

#### Common issues

| Symptom | Likely cause | Fix |
|---|---|---|
| Repeated BASIC login prompt; curl returns `401` | Tomcat rejected the identity/password | Edit the active `$CATALINA_BASE/conf/tomcat-users.xml`, confirm `UserDatabaseRealm`, restart Tomcat; see authentication section |
| Login succeeds but route returns `403` | Authenticated user lacks the case-sensitive required role | Assign `AIS_USER`; schema additionally requires `AIS_ADMIN` |
| No browser login prompt but API calls fail | The public JSP loads before the protected fetch, and the browser may not show BASIC UI for fetch | Open `/api/tools` directly once or test with `curl -u`; use SSO/form login for production UX |
| `ClassNotFoundException: com.ais.security.AuthenticationFilter` | Security source not compiled into the WAR | Confirm the class is under `src/main/java/com/ais/security/` and rebuild with `mvn clean package` |
| `HtmlPolicyBuilder cannot be resolved` | OWASP sanitizer dependency missing or Maven project not refreshed | Add `owasp-java-html-sanitizer` to `pom.xml`, run `mvn clean package`, then update the Eclipse Maven project |
| `LLM SQL request completed` | Raw SQL execution finished | Review row count/duration audit data; do not log or return the raw SQL statement |
| Empty prompt in logs | Frontend Content-Type mismatch | Ensure `Content-Type: application/json` |
| `Invalid column name MOD_TIME` | Table missing timestamp column | `DatabaseManager.getOrderColumn()` auto-detects |
| LLM never responds (Ollama mode) | Ollama unreachable | Run `curl http://<ollama-ip>:11434/api/tags` |
| LLM never responds (Tencent mode) | Invalid API key or wrong base URL | Check `tencent.api.key` and `tencent.api.base_url` |
| Tencent returns 401 | Expired or invalid API key | Regenerate key in Tencent Cloud console |
| Tencent returns 429 | Rate limit exceeded | Reduce request frequency or upgrade plan |
| Verifier always auto-approves | LLM service unreachable | Check network and API key config |
| Verifier rejects valid answers | Verifier prompt too strict | `VerifierNode` remaps REJECTED → RETRY; check logs |
| Graph loops or takes several minutes | Repeated SQL fallback, swallowed timeout, or retry budgets too high | Check structured `QUERY_TIMEOUT`, one-SQL-attempt guard, and separate regeneration/repair counters |
| `500` on production only | OkHttp SSL truststore error | See [Known issues & fixes](#known-issues--fixes) |
| `/api/tools` returns 404 | `ToolsServlet` not compiled | Verify class exists in `target/classes/` |
| Infinite retry on failed tool | Keyword cache trapping retries | `extractKeywords()` checks `isRetry` to bypass cache |
| Broken tool calls (`args: {}`) | `QueryPlanner` missing parameters | Pre-validation checks ensure fallback to `needsLlm=true` |
| Empty table on custom SQL | Hardcoded `formatSearchResults` | Ensure `OllamaService` uses dynamic `JsonNode` table builder |
| Location code link opens wrong page | `assetType` not detected | `formatSingleLocation` uses `isSlope` flag to set `Building` or `Slope` |

#### Log emoji legend

| Emoji | Meaning |
|---|---|
| 🎯 | Fast path triggered (no LLM call) or LLM selected tool in agent loop |
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
| 📋 | Slope URL classified or answer step identified |
| 📌 | Answer step (modifier target) identified in plan execution |
| 🌐 | Tencent Cloud API call made |
| 🔀 | Modifier transferred/relocated between plan steps |
| 🔗 | Referential prompt resolution or plan step relation applied |
| ❌ | Tencent API error |
| ⏭️ | Modifier skipped on intermediate step (not the answer step) |
| ⏱️ | Phase timing measurement |

#### Graph-specific log patterns

```
→ Executing node: planner (step 1)           node started
  Edge → primary_llm                         unconditional edge taken
→ Executing node: verifier (step 3)          verifier running
🌐 Tencent simple request → model=deepseek-v4-flash  Tencent Cloud call
[VerifierNode] Result=RETRY, Repair=REINVOKE_TOOL
[Router] RETRY → patch (attempt 1)            targeted repair requested
→ Executing node: patch (step 4)              catalog-validated MCP call
[PatchRouter] Repair succeeded → verifier     re-verifying patched answer
=== Graph Execution Complete [10863ms, 6 steps] ===
Execution path: [planner, primary_llm, verifier, patch, verifier, formatter]
```

---

## Verification graph

### What it is

The verification graph is a **LangGraph-style state machine** implemented in pure Java. Every chat request passes through a fixed sequence of nodes. Each node reads from and writes to a shared `GraphState` object. Conditional edges decide which node runs next based on the current state.

This pattern is equivalent to the Python LangGraph library but built from scratch to run inside a Java EE servlet container with no additional dependencies.

### Why it exists

Without verification, whatever the primary LLM returns goes directly to the user. With the graph:

- A second LLM call independently checks whether the response actually answers the question.
- If the response is incomplete and the verifier identifies a specific missing tool call, `PatchNode` performs a bounded catalog-validated repair and sends the result through verification again.
- If no targeted repair is possible, the graph may regenerate the primary pipeline within the separate configured regeneration budget.
- If all retries fail, the graph falls back to a safe message rather than showing a bad answer.
- Every response shown to the user has been verified by a second model call.

### Node descriptions

| Node | Class | What it does |
|---|---|---|
| `planner` | `PlannerNode` | Detects intent from the query text (LOCATION_CODE, PSM, DEPARTMENT, etc.) and stores it in state |
| `primary_llm` | `PrimaryLlmNode` | Calls `OllamaService.invoke(prompt, sessionId)` which runs keyword extraction, query planning, tool calls, and the agent loop. Stores the answer in `state.primaryResponse` |
| `verifier` | `VerifierNode` | Calls `OllamaService.callLlmSimple()` to ask whether the primary response answers the question. Routes to Tencent Cloud or Ollama based on config. Parses the verdict plus structured repair feedback (`suggestedAction`, `missingTools`, and `toolArgs`) and stores it in `GraphState` |
| `patch` | `PatchNode` | Resolves verifier-suggested tools through the catalog, filters arguments against the accepted schema, optionally reuses previous `LOC_CD` values through the shared `locCds`/`locCd` contract, invokes the dispatcher, appends dynamically formatted, collapsible HTML details and native tables (instead of raw JSON dumps) of the repair output, and sends successful repairs back to `verifier` |
| `formatter` | `FormatterNode` | Copies `primaryResponse` to `finalResponse`, sets `success=true` |
| `fallback` | `FallbackNode` | Sets a safe error message as `finalResponse`, sets `success=false` |

### Disabling verification

Set `graph.verification.enabled=false` in `application.properties` to skip the verifier node entirely. In this mode the graph becomes:

```
planner ──► primary_llm ──► formatter ──► END
```

This is useful for local development or when verifier latency is unacceptable. The `FormatterNode` treats a `null` / `SKIPPED` verification result the same as `APPROVED` — see [Known issues & fixes](#known-issues--fixes) for a bug where this was not actually implemented, causing a "Verification Note" banner to wrap every single response even with verification fully disabled.

### Edge routing

```text
planner ─────────────────────────────► primary_llm
primary_llm ─────────────────────────► verifier          (when enabled)
verifier (APPROVED / null / SKIPPED) ► formatter ──► END
verifier (RETRY + repair request) ───► patch ──► verifier
verifier (RETRY without repair) ────► primary_llm       (increments retryCount)
verifier (RETRY, maxRetries reached) ► formatter ──► END (best-effort answer)
patch (repair unavailable) ─────────► primary_llm       (bounded fallback)
```

`REJECTED` is remapped to `RETRY` inside `VerifierNode` so the graph always retries before giving up. A concrete `REINVOKE_TOOL` repair request goes through `PatchNode` first and is re-verified. Only after the bounded repair/regeneration budgets are exhausted does the router send to `formatter` with the best available answer.

### GraphState fields

| Field | Type | Description |
|---|---|---|
| `userQuery` | `String` | Original user prompt |
| `sessionId` | `String` | HTTP session ID for memory lookup |
| `detectedIntent` | `String` | Intent detected by PlannerNode |
| `primaryResponse` | `String` | HTML answer from PrimaryLlmNode |
| `toolCallsMade` | `List<String>` | Tool names called during primary LLM run (names only — kept for backward compatibility) |
| `rawToolOutput` | `String` | Combined text summary of ALL tool outputs concatenated together (kept for backward compatibility; do not use this to render per-call UI cards — see `toolCallDetails`) |
| `toolCallDetails` | `List<Map<String, Object>>` | One entry per real tool invocation, each with its own `name`, `args` (a real `Map`, not a placeholder), and `result` (that call's own output only). Populated by `PrimaryLlmNode` from `AgentResult.getToolCalls()`. This is what `ChatServlet.buildToolCallsJson()` should serialize for the `/api/chat` `toolCalls` array — see [Known issues & fixes](#known-issues--fixes) for the bug this field fixes. |
| `verificationResult` | `enum` | APPROVED / RETRY / REJECTED / SKIPPED |
| `verificationReason` | `String` | Verifier's explanation, including targeted-repair status |
| `verifierFeedback` | `VerifierFeedback` | Structured verifier output: `suggestedAction`, `missingTools`, `toolArgs`, `reason`, and `confidence`. Tool names/arguments are untrusted until catalog validation in `PatchNode` |
| `repairAttempted` / `repairSucceeded` | `boolean` | Tracks whether a targeted patch ran and whether at least one validated tool call succeeded |
| `retryCount` | `int` | Number of bounded retry/repair cycles so far |
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

The verifier node sends a short prompt to the configured LLM asking for structured JSON:

```json
{
  "verdict": "RETRY",
  "confidence": 0.9,
  "reason": "The answer did not check KAI availability",
  "suggestedAction": "REINVOKE_TOOL",
  "missingTools": ["check_reports"],
  "toolArgs": {
    "check_reports": {"reportType": "KAI"}
  }
}
```

Rules applied after parsing the verdict:

- `APPROVED` with confidence below 0.5 is treated as `RETRY`.
- `REJECTED` is remapped to `RETRY` (the graph always retries before giving up).
- `REINVOKE_TOOL` is used only when at least one concrete missing tool is supplied; otherwise the graph uses ordinary regeneration.
- Tool names and arguments are untrusted model output. `PatchNode` resolves the name through the catalog, filters arguments against the accepted schema, and lets `ToolDispatcher` perform final validation.
- A successful targeted repair is sent back through `VerifierNode`; it is not considered approved merely because the tool call succeeded.
- If the LLM service is unreachable or `graph.verification.enabled=false`, the verifier fails open (auto-approves) so the user still gets an answer.
- Responses shorter than 100 characters or matching configured failure patterns are sent directly to `RETRY` without an additional verifier call.

### Verifier fail-open design

The verifier is designed to **never block the user** if the verification service is unavailable:

```
Verifier LLM unreachable   → APPROVED (auto) → formatter → user sees answer
Verifier response unparseable → APPROVED (auto) → formatter → user sees answer
Verifier times out          → APPROVED (auto) → formatter → user sees answer
```

This means the graph degrades gracefully to the same behavior as before verification was added.

---

## API endpoints

| Method | URL | Required role | Description |
|---|---|---|---|
| `POST` | `/api/chat` | `AIS_USER` or `AIS_ADMIN` | Send a prompt, get a verified AI-generated answer |
| `GET` | `/api/chat?prompt=...` | `AIS_USER` or `AIS_ADMIN` | Browser-friendly prompt testing |
| `GET` | `/api/tools` | `AIS_USER` or `AIS_ADMIN` | Returns tool UI metadata for quick prompt buttons |
| `POST` | `/api/location/general-info` | `AIS_USER` or `AIS_ADMIN` | Get a single location row by `locCd` |
| `GET/POST` | `/api/location/schema` | `AIS_ADMIN` | Database schema introspection; also guarded inside `LocationServlet` |
| `GET` | `/report/view?type=<type>&locCd=<locCd>&reportId=<reportId>` | `AIS_USER` or `AIS_ADMIN` | Render a specific report detail page |

### Chat endpoint

All examples below require credentials from the active Tomcat Realm. Substitute real local values; never commit them.

```bash
curl -u 'ais-user:YOUR_LOCAL_PASSWORD' -X POST \
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
      "args": { "locCd": "SB04400361000" },
      "result": "{\"general\":{\"LOC_CD\":\"SB04400361000\", ...}}"
    }
  ],
  "elapsedMs": 10863,
  "verified": true,
  "verificationResult": "APPROVED",
  "retries": 0
}
```

Each entry in `toolCalls` carries **its own** `args` and `result` (see `GraphState.toolCallDetails` in [GraphState fields](#graphstate-fields)). Older builds of `ChatServlet` hardcoded `args` to the literal string `"{}"` and repeated the same combined `result` text across every entry — see [Known issues & fixes](#known-issues--fixes) for the fix.

### Tools endpoint

```bash
curl -u 'ais-user:YOUR_LOCAL_PASSWORD' \
  http://localhost:8090/ais_ai/api/tools
```

### Location schema endpoint

```bash
curl -u 'ais-admin:YOUR_ADMIN_PASSWORD' \
  http://localhost:8090/ais_ai/api/location/schema
```

### Location info endpoint

```bash
curl -u 'ais-user:YOUR_LOCAL_PASSWORD' -X POST \
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

### LLM-generated scalable execution plans

Keyword extraction can return an ordered `plan` array. Each `IntentStep` has:

- `type` — resolved through `ToolDefinition` intent types or aliases.
- `priority` — execution order; `QueryPlanner` sorts by priority.
- `params` — arguments for that step.
- `relation` — one of:
  - `independent` — execute normally.
  - `filter_previous` — retain only codes present in both the previous and current result.
  - `enrich_previous` — merge current-step attributes into previous result rows.
  - `use_previous_codes` — inject previous `LOC_CD` values into the target tool's `locCds` or `locCd` argument.

`QueryPlanner` validates each relation against the tool catalog. Unsupported relations, invalid first-step filtering/enrichment, missing required parameters, and `use_previous_codes` tools that do not consume `LOC_CD` are rejected before execution.

`OllamaService.executePlan()` then applies relations generically. It determines the answer step by preferring the last executable step with an explicit modifier, including dependent `use_previous_codes` steps. If no modifier exists, the last executable step is the answer step.

`QueryPlanner.distributeModifier()` preserves an explicitly assigned modifier on its original step. This is important for plans such as:

```text
NAME_SEARCH (independent)
DECLARED_MONUMENT (use_previous_codes, modifier=OLDEST)
```

The `OLDEST` modifier must remain on `DECLARED_MONUMENT`; moving it to `NAME_SEARCH` would compare the wrong result set.

For `NAME_SEARCH` steps, the plan distinguishes the subject (`locName`) from the district/area (`location`). For example:

```text
NAME_SEARCH(locName=playground, location=Lo Wu)
```

This prevents a district name from accidentally replacing the actual search subject.

### Generic enrichment (`enrichWithPrevious`)

`enrich_previous` is now handled through the generic `LOC_CD`-keyed merge path. The executor does not need a special branch for a monument-plus-historic combination. Providers that support enrichment declare `enrich_previous` in `supportedRelations` and return rows keyed by `LOC_CD`.

A provider may still expose domain-specific fields such as `GRD_HIST_BLDG`; the generic merger preserves those fields and the formatter renders them when present. Any future provider can participate in enrichment by following the same result contract without adding a new case to `OllamaService`.

### SQL generation fallback for empty tool results

Because the tool layer limits large searches to 50 rows, answers can be missed when the true match lies outside that window. If the fast-path tool result is empty (`Found 0 locations`, `No results found`, etc.), the pipeline now falls back to `generateAndExecuteSql()`. The SQL generator prompt uses `TOP 200` and the actual GIS database name from `AppConfig.GISdbName()`, and includes an example for finding historic buildings under a PSM.

Additional safeguards:

- `generateAndExecuteSql()` unescapes HTML entities (`&lt;`, `&gt;`, `&amp;`) that the LLM may return in the SQL, so the query is sent to SQL Server as real T-SQL operators.
- `isUselessSqlResult()` treats SQL execution errors and malformed output as useless, but a successful execution that returns `count=0` is now considered a valid "No results found" answer. This prevents the pipeline from entering a long, often-confusing agent loop when the database simply has no matching rows.
- `SQL_GENERATE_PROMPT` now includes a **Tool SQL Pattern Catalog** with the exact SQL patterns used by `DatabaseManager` for historic buildings, declared monuments, PSM locations, department locations, name search, and code history. This teaches the LLM to copy the working table aliases, joins, and filters instead of inventing new ones.

### Tencent Cloud LLM integration

All LLM calls (keyword extraction, SQL generation, agent loop, and verification) now route through a unified `callLlmSimple()` / `callOllama()` gateway in `OllamaService`. Set `tencent.api.key` in `application.properties` to switch from local Ollama to Tencent Cloud's OpenAI-compatible API. No code changes are needed to switch providers. Ollama remains available as a fallback when no API key is configured.

### AIS Asset Search deep links

Every location code shown in a result table is now a clickable link that opens the AIS Asset Search detail page (`/AIS/AssetSearch/index.jsp?locCd=<code>&assetType=<type>`). The `assetType` is automatically set to `Slope` for slope locations and `Building` for all others, based on the `isSlope` flag already present in the location data.

### Sticky location map as a message sibling

`OllamaService.formatSingleLocation()` wraps the AIS Asset Search link + map `<iframe>` in a `<div class="location-map-block">` at the end of the HTML it returns, still inline in the message content as before — no server-side layout changes needed. The split into a side-by-side layout happens entirely on the client: `js/chat.js`'s `appendMessage()` calls `extractMapBlock()`, which finds `.location-map-block` inside the rendered message, detaches it (`Node.remove()`), and re-parents it as a true DOM **sibling** of the message bubble — both wrapped in a `.message-row` flex row — rather than leaving it nested inside `.message`. The re-parented block is relabeled `.location-map-sticky` and pinned with CSS `position: sticky`, bounded to `.message-row` as its containing block, so it:

- stays visible near the top of the viewport while the user scrolls through a long result table,
- can never rise above that message's own top or escape past its bottom (sticky positioning is naturally bounded by its containing block — no scroll-listener JS needed),
- collapses to a stacked (non-sticky) layout on viewports narrower than 900px, where there isn't room for two columns.

A message with no map (i.e. no `.location-map-block` found) renders exactly as before, so this only affects single-location detail cards.

### `/skill` tools list with ranked search and keyboard navigation

The quick-prompt buttons row was replaced with an on-demand tools list, triggered by typing `/skill` in the chat input instead of always being visible. See [Dynamic quick prompt buttons / `/skill` tools list](#dynamic-quick-prompt-buttons--skill-tools-list) in the developer guide for the full ranking algorithm and keyboard-navigation model (`↑`/`↓`, `Tab`, `Enter`, click).

### LangGraph-style verification graph

Every chat response is validated by a second LLM call before it reaches the user. The graph runs five nodes in sequence: intent planner, primary LLM, verifier, formatter, and fallback. The verifier independently checks whether the primary response actually answers the question and triggers automatic retries on failure.

See [Verification graph](#verification-graph) for full details.

### Scalable Hybrid Query Planning & Pre-Validation

The app includes an advanced `QueryPlanner` that pre-validates extracted parameters before dispatching tools.

- **Fast Path (`needsLlm=false`):** Used when the user provides explicit, exact parameters for a tool (e.g., `deptCd=AFCD`), saving LLM compute.
- **Autonomous Path (`needsLlm=true`):** If an intent lacks its required parameter, `QueryPlanner` routes to the LLM Agent Loop or SQL Generator.
- **Natural Language Filter Guards:** Canonical parameters are extracted into the structured keyword object and validated by the planner. Do not add a new semantic prompt-regex detector for a new tool or filter; use the catalog schema and database-dimension registry instead.
- **Dynamic Multi-Report Chaining:** Requests for multiple report types (e.g., "BSI and KAI") generate ordered `CHECK_REPORTS` steps. The first report step consumes the previous location list with `use_previous_codes`; later report steps consume the narrowed code list with `use_previous_codes`, while the normalized result contract and generic filtering preserve the intersection.
- Multi-step queries such as PSM report checks, department report checks, and code-history lookups are validated against `ToolDefinition` relation and consumed-field metadata before execution.

### Dynamic T-SQL Generation & Table Formatting

For complex cross-table questions, `OllamaService` generates custom T-SQL `SELECT` statements and renders results as dynamic HTML tables with automatically detected column headers.

- **Accurate UI Row-Count Displays:** Removed hardcoded `"(showing first 50)"` text labels in HTML formatters (`formatLocationsByPsm`, `formatLocationsByDept`, `formatHistoricBuildings`, `formatDeclaredMonuments`). The formatters now dynamically compare `results.size()` against total `count` (`showing < count ? " (showing first " + showing + ")" : ""`), accurately displaying `Found 200 location(s).` when all 200 rows are rendered into the table.

### SQL validation layer (`validateLlmSql`)

After the LLM generates SQL, a post-generation validation step corrects common hallucinated table names before execution:

| Hallucinated name | Corrected to |
|---|---|
| `KAI_RECORD_PLANS_OR_DRAWINGS` | `KAI_RECORD_PLANS_AND_DRAWINGS` |
| `KAI_RECORD_PLANS_TO_DRAWINGS` | `KAI_RECORD_PLANS_AND_DRAWINGS` |
| `KAI_PLANS_AND_DRAWINGS` | `KAI_RECORD_PLANS_AND_DRAWINGS` |
| `KAI_RECORD_AND_DRAWINGS` | `KAI_RECORD_PLANS_AND_DRAWINGS` |
| `BLDG_SAFETY_INSPECTION_INFO` | `BSI_GENERAL_INFO` |
| `BSI_INFO` | `BSI_GENERAL_INFO` |

This works in addition to the `SQL_GENERATE_PROMPT` instructions that tell the LLM the correct names. The validator is a safety net for cases where the LLM ignores the instructions.

### Grade input normalization (`sanitizeGrade`)

The `sanitizeGrade()` method normalizes LLM-extracted grade values to a controlled set:

| LLM output | Normalized to |
|---|---|
| `1`, `2`, `3` | `1`, `2`, `3` |
| `ALL`, `ANY`, `SOME` | `ALL` |
| `NONE`, `0`, `NULL`, `NO GRADE` | `NONE` |
| `NOT NULL`, `NON-NULL`, `GRADED`, `HAS GRADE` | `GRADED` (special sentinel) |
| Anything else | `ALL` (with warning log) |

The `GRADED` sentinel is used by `enrichWithHistoricGrade()` to keep only locations that have a non-empty, non-zero historic grade — effectively filtering out ungraded entries while still enriching with the actual grade values.

### Generic post-processing and modifiers

`postProcess()` is intentionally limited to generic session bookkeeping: it extracts location codes from the standardized tool result and stores them for later referential prompts. It does not contain a per-tool post-step chain.

Relations are expressed in the plan itself. Use `filter_previous`, `enrich_previous`, or `use_previous_codes` rather than adding a new hardcoded chain to `OllamaService`.

`findByYear()` remains a domain-specific modifier implementation for OLDEST/NEWEST. It reads candidate completion years in one batched `DatabaseManager.getGeneralInfoBatch()` query and fetches full details only for the winner.

### Post-filtering and provider contracts

Location filtering should be performed by the provider/database when the tool accepts a `location` parameter. For compatible combinations of canonical dimensions, `PlanOptimizer` uses `location_query` so the database applies the predicates in one query. Generic Java relation processing only intersects or enriches standardized result rows when the plan cannot be collapsed. A new provider should return location rows under `results[]` with `LOC_CD`; this allows code extraction and relation processing without a tool-name check.

### Extensible plan execution with modifier safety nets

`executePlan()` computes the answer step and applies modifiers (OLDEST/NEWEST/FIRST/COUNT) only to that step. It first chooses the last executable step with an explicit modifier, including `use_previous_codes` steps. If no modifier is present, it chooses the last executable step. This keeps a modifier on the dependent step when that step is the actual answer, while avoiding duplicate detail rendering through the redundant-detail guard.

`QueryPlanner.distributeModifier()` preserves explicit step modifiers. A top-level keyword modifier is assigned only when no step already declares one. Unknown relation values, unsupported relations, invalid first-step filtering/enrichment, and missing consumed fields are rejected by the planner before execution.

### LLM step type alias normalization

The keyword extraction prompt now includes a `VALID PLAN STEP TYPES` section that constrains the LLM to use only recognized type names. As a safety net, `parseLlmPlan()` normalizes common LLM-invented aliases at parse time:

| LLM-invented alias | Normalized to |
|---|---|
| `LOCATION_NAME_SEARCH`, `NAME_LOOKUP`, `SEARCH_BY_NAME` | `NAME_SEARCH` |
| `DEPARTMENT_INFO`, `DEPT_INFO`, `GET_DEPARTMENT` | `LOCATION_CODE` |
| `MONUMENT_INFO`, `MONUMENT_LOOKUP` | `DECLARED_MONUMENT` |
| `HISTORIC_INFO`, `BUILDING_INFO` | `HISTORIC_BUILDING` |

This three-layer defense (prompt constraint → parse-time normalization → runtime validation in `executePlan()`) prevents the LLM from inventing unsupported step types that would silently fail.

### Generic code extraction and relation filtering

Tools should prefer the standard `results[]` contract, but the current generic extractor also walks nested response objects and collects `LOC_CD` and `CURRENT_LOC_CD` fields. This means legacy response shapes such as report availability arrays can participate in session memory and relation processing without a `check_reports` tool-name branch.

`crossFilter()` uses the extracted code sets and preserves the current step's rows when possible. It fails closed: an empty code set, missing row array, or processing exception produces an empty filtered result rather than returning unrelated unfiltered rows.

New providers should still use the standard response shape because it gives the best behavior for formatting, truncation, filtering, enrichment, and future tools.
### Generalized intersection summary (`describeStep`)

After all `filter_previous` steps execute, `executePlan()` checks whether two or more chained filters were applied. If so, it appends an intersection summary showing which codes survived all filters and a human-readable description of each filter:

| Query | Filter chain | Summary |
|---|---|---|
| AFCD + BSI + KAI | `BSI report ∩ KAI report` | ✅ 42 locations matched |
| AFCD + CSR + BSI + KAI | `CSR report ∩ BSI report ∩ KAI report` | ✅ 28 locations matched |
| PSM/KT + Grade 1 + BSI | `Grade 1 historic ∩ BSI report` | ✅ 5 locations matched |
| Lo Wu + monuments + Grade 1 | `Declared monument ∩ Grade 1 historic` | ✅ 2 locations matched |

The `describeStep()` helper generates human-readable descriptions for any step type:

```java
case CHECK_REPORTS:      return reportType + " report";
case HISTORIC_BUILDING:  return "Grade " + grade + " historic";
case DECLARED_MONUMENT:  return "Declared monument";
case PSM_LOCATIONS:      return "PSM " + psm;
// etc.
```

This is generalized — it works for any combination of `filter_previous` steps (reports, historic buildings, monuments, PSMs, departments), not just report intersections.

### Auto-generated `/skill` tools list

Quick prompt entries are auto-generated from the shared catalog built by `ToolRegistryFactory` and exposed through `MCPClientService`. Adding a new tool automatically creates a matching row in the `/skill` list — see [Dynamic quick prompt buttons / `/skill` tools list](#dynamic-quick-prompt-buttons--skill-tools-list) in the developer guide for the ranking/keyboard-navigation details.

### Keyword extraction and hybrid routing

`OllamaService` extracts normalized keywords from the user prompt and passes them to `QueryPlanner`. The keyword cache is bypassed automatically when a verifier retry prompt is detected to prevent stale cache interference.

### SQL-driven fallback and intent detection

The app detects `SQL_QUERY` intents and attempts direct SQL generation before entering the full agent loop. If a generated query returns 0 rows or an error, `isUselessSqlResult()` detects the empty table and falls back to the Agent Loop.

Additionally, when the fast-path tool result is empty (e.g., the 50-row tool limit caused a cross-filter to return 0 matches), the pipeline now triggers `generateAndExecuteSql()` to answer the question with a database query instead of the limited tool results.

### Session memory and follow-up queries

Session memory is stored per HTTP session. `resolveReferentialPrompt()` preserves session integrity by operating independently of verifier feedback notes.

### Slope and specialized report handling

The location detail flow detects slope locations and returns slope-specific report groups plus TMCP/TMIS data.

### Expanded tool coverage

- `locations_by_dept` — lookup locations by department code.
- `search_declared_monument` — find declared monuments, non-monuments, or both.
- `search_historic_building` — find historic buildings by grade.
- `search_loc_cd_history` — lookup former/current location code history.

### Automatic former-code detection and redirect

When searching location code history, the app automatically detects whether the searched code is a former (old) code or the current code.

- If the searched code is **not** in the current codes list → former code detected → `hardcode_query` auto-triggered for the current code.
- If the searched code **is** the current code → history shown as-is, no auto-trigger.

Example:

```
User: "Search location code history for UD04400253000"
  → DB returns: FORMER=UD04400253000, CURRENT=UC04400251000
  → UD04400253000 not in currentCodes → auto-trigger hardcode_query(UC04400251000)
  → Shows history table + full details of UC04400251000
```

### PSM name matching (`getLocationsByPsm`) — slash-anchored, prefix-restricted

Real PSM values in `ais.A_GENERAL_INFO.PSM` follow a `PREFIX/NAME` format (e.g. `PSM/CENTRAL EAST`, `PSM/KT`, but also non-PSM categories sharing the same column such as `MS/HERITAGE 1` and `SE/TIM`). `getLocationsByPsm()` matches on the **name portion after the slash**, restricted to prefix `PSM/` only:

```sql
WHERE UPPER(RTRIM(g.PSM)) LIKE 'PSM/%'
  AND UPPER(RTRIM(g.PSM)) LIKE ?      -- bound to '%/' + escaped(name) + '%'
```

This means a search for `CENTRAL` matches `PSM/CENTRAL EAST` and `PSM/CENTRAL WEST` (both start with `CENTRAL` right after the slash) but does **not** false-match `PSM/KLN. CITY CENTRAL (1)` (the substring `/CENTRAL` never appears in that string), and never returns `MS/HERITAGE 1` or `SE/TIM` regardless of the search term. `extractPsmNameTerm()` normalizes input that may or may not already include a prefix and/or slash (`"CENTRAL"`, `"PSM/CENTRAL"`, `"PSM CENTRAL"`, mixed case) down to a bare name term before building the pattern, and `escapeLikeWildcards()` escapes literal `%`/`_` characters in the search term so they can't be misinterpreted as SQL wildcards. See [Known issues & fixes](#known-issues--fixes) for the two earlier, less precise attempts at this matching logic and why each one failed.

**Design decision (confirmed with the maintainer):** when a search term is ambiguous and matches multiple real PSMs (e.g. `CENTRAL` matching both `PSM/CENTRAL EAST` and `PSM/CENTRAL WEST`), the query intentionally returns the **merged** location set from all matching PSMs rather than asking the user to disambiguate. PSM search is also intentionally restricted to prefix `PSM/` only — `MS/HERITAGE *` and `SE/TIM` are a different business category and must never be returned by a PSM search even if their name portion happens to match.

### Row-limit control (`"top N"` / `"first N"`) across all list-returning tools

Every list-returning `DatabaseManager` method (`getLocationsByPsm`, `getLocationsByDept`, `searchByName`, `getDeclaredMonuments`, `getHistoricBuildings`) now accepts an optional `Integer limit` parameter instead of always using a hardcoded `TOP 200`/`TOP 10`. `clampLimit(limit, defaultVal)` clamps any supplied value into `[1, MAX_LIST_LIMIT]` (500) and falls back to each method's existing default when `null`, so a hallucinated `"top 999999999"` can never force a runaway scan, and every pre-existing caller that doesn't pass a limit keeps its original behavior unchanged.

The user-facing phrase is detected end-to-end through the whole pipeline:

1. **Structured extraction:** the keyword object carries `limit`, `excludeUndefinedField`, and canonical filter parameters; the backend clamps and validates them before database execution.
2. **General prompt-level detection:** `OllamaService.extractLimitFromPrompt()` (a standalone `(?i)\b(?:top|first)\s+(\d{1,4})\b` regex) runs once per request in `invoke()` and applies to `keywords.getLimit()` if the template path didn't already set one — this covers free-text phrasing that doesn't match an exact template.
3. **`QueryPlanner`** copies `kw.getLimit()` into the plan step's `params` map, in both the single-intent loop (`analyse()`) and the LLM-generated-plan path (`convertLlmPlan()`).
4. **`OllamaService.buildArgs()`** reads `intent.params.get("limit")` and puts it into the tool-call `args` map for `NAME_SEARCH`, `PSM_LOCATIONS`, `DEPARTMENT_LOCATIONS`, `DECLARED_MONUMENT`, and `HISTORIC_BUILDING`.
5. **`ToolRegistryFactory`'s registered executors** for those 5 tools read `args.get("limit")` and pass it through to the matching `DatabaseManager` method; the tool JSON schemas also expose `limit` as an LLM-settable parameter so the agent loop can set it directly without relying on the prompt-text regex. `MCPClientService` remains the compatibility facade over this catalog.

### "Exclude undefined `<field>`" filter across all list-returning tools

Decommissioned/placeholder records in this dataset commonly have a field value that is `NULL`, blank, exactly `-`, or the literal text `UNDEFINED` (e.g. `ADDRESS = ", -, -, UNDEFINED"`). `DatabaseManager.appendExcludeUndefinedFilter(sql, alias, column)` appends a WHERE clause excluding all four cases for a given column. The column name is never taken directly from user/LLM text — `resolveUndefinedCheckColumn()` maps a free-text field name (`"address"`, `"name"`, `"department"`, etc.) to a whitelisted real column name (`ADDRESS`, `LOC_NAME`, `DEPT_CD`); an unrecognized field name causes the filter to be silently skipped (with a warning log) rather than ever being concatenated into SQL unchecked, since JDBC cannot bind column/identifier names as parameters.

Wired end-to-end the same way as the `limit` feature, but leveraging **scalable LLM semantic extraction** rather than brittle Java regex stripping:

1. **LLM Keyword Extraction (Phase 1):** The system prompt (`KEYWORD_EXTRACT_PROMPT`) instructs the LLM to extract `excludeUndefinedField` (`"address"`, `"name"`, `"department"`) natively from any natural language phrasing (`"with address not null"`, `"real address"`, `"valid name"`, `"with address not undefined"`). This eliminates brittle Java-side regex stripping (`stripExcludeUndefinedPhrase`) that previously swallowed tokens into greedy capture groups or broke referential session memory.
2. **Plan Step Parameter Propagation:** In `OllamaService.invoke()`, right after `QueryPlanner.analyse()`, a loop checks if `keywords.getExcludeUndefinedField()` is present and automatically injects `"excludeUndefinedField"` into `step.params` for every step in the plan. This guarantees that Fast Path (`needsLlm=false`) queries never drop the filter.
3. **Tool Parameter Binding:** `OllamaService.buildArgs()`'s `putExcludeUndefinedIfPresent()` helper copies `intent.params.get("excludeUndefinedField")` into the tool-call `args` map for the 5 list-returning tools (`NAME_SEARCH`, `PSM_LOCATIONS`, `DEPARTMENT_LOCATIONS`, `DECLARED_MONUMENT`, `HISTORIC_BUILDING`).
4. **Autonomous Agent Loop & Schema Exposure:** The tool definitions built by `ToolRegistryFactory` expose `excludeUndefinedField` as an enum-constrained string parameter (`["address", "name", "department"]`). When the fast path is skipped or returns empty, the autonomous agent loop reads the catalog schema and sets `excludeUndefinedField` directly without any Java pre-validation.
5. **Database Execution:** `DatabaseManager.appendExcludeUndefinedFilter(sql, alias, column)` appends the WHERE clause excluding all four undefined placeholder cases.

### Tool-call deduplication (`AgentResult.findEquivalentCallResult`)

`AgentResult` (nested in `OllamaService`) can look up whether an equivalent tool call — same tool name, same arguments after case-insensitive/order-independent normalization — has already been made anywhere in the current request, via `findEquivalentCallResult(toolName, args)` / the private `normalizeArgsKey(args)` helper. Both `executePlan()` (the fast path) and `runAgentLoop()` (the agent loop) check this before dispatching a real tool call; on a hit, the cached result is reused (no second DB/tool round-trip) and still recorded via `addToolCall()` so the duplicate remains visible in the UI's tool-call audit trail for transparency. This catches cross-phase duplicates too — e.g. the fast path calling `locations_by_psm(psm=CENTRAL)` and, after that returns empty, the agent loop's LLM re-selecting the exact same call (or a case-only variant like `psm=Central`) later in the same request. See [Known issues & fixes](#known-issues--fixes) for the two prior, narrower attempts at this fix and why each one only caught part of the problem.

---

## Debugging & testing tools

Two standalone helper tools exist outside the main webapp source tree to make debugging tool-selection and SQL behavior faster than reading through the full chat UI or pasting raw logs by hand.

### `tools/ToolCallAuditTest.java` — live batch prompt runner

A dependency-free (`javac`/`java` only, no Maven/JUnit) black-box test that fires a fixed batch of sample prompts at the real running app's `POST /api/chat` endpoint — the same endpoint `chat.js` calls — and reports every tool call made per prompt, flagging:

- **exact duplicates** — the same tool called 2+ times with identical args in one request,
- **blind-guessing loops** — the same tool called 3+ times with drifting args (e.g. an LLM trying `CENTRAL`, then `CENTRAL DISTRICT`, then `CENTRAL OFFICE` instead of consulting an already-fetched list),
- overall wall-clock time per prompt.

Run it against a live Tomcat instance with the app already deployed:

```bash
cd C:\Users\User\eclipse-workspace\ais_ai
javac src\main\java\tools\ToolCallAuditTest.java
java -cp src\main\java\tools ToolCallAuditTest
```

Each prompt in the `PROMPTS` array (edit freely to add new regression cases) takes as long as a real agent-loop request does — typically several seconds, sometimes 10–30s for compound queries — since it exercises the real LLM and DB, not a mock. Exit code is `1` if anything was flagged, `0` otherwise, so it can be wired into a post-deploy smoke-test script later.

This tool is what caught two of the bugs listed below: the `getLocationsByPsm` parameter-mismatch crash (11 calls / ~17s for one PSM query) and the `findOldestWithDetails` O(n) lookup bug (161 calls / ~33.6s for one "oldest historic building" query).

### `tools/tool_call_audit.py` — Tomcat log parser

A zero-dependency Python 3 script that parses a saved (or piped) Tomcat/logback console log and produces the same redundancy report as `ToolCallAuditTest.java`, but from logs you already have instead of firing new live requests. Useful for a quick "I just tested something in the browser, what actually happened" check without waiting through the LLM round-trip again.

```bash
python3 tools/tool_call_audit.py catalina.log
# or pipe a live tail:
tail -f catalina.out | python3 tools/tool_call_audit.py -
# machine-readable output:
python3 tools/tool_call_audit.py catalina.log --json report.json
```

It groups every `Chat request: session=..., prompt=...` block with the `LLM SELECTED TOOL` / `LLM GENERATED ARGS` / `Tool: ... args: ...` lines that follow it, and applies the same exact-duplicate / blind-guessing-loop heuristics as the Java tool.

### `DatabaseManager.logSql()` helper

Since the Microsoft JDBC driver does **not** override `PreparedStatement.toString()` (unlike Postgres/HSQLDB), `ps.toString()` only prints an opaque object reference — it never shows the resolved SQL or bound parameter values. `DatabaseManager` has a small `logSql(sql, params...)` helper (gated on `log.isDebugEnabled()`) that substitutes `?` placeholders with their bound values for a debug-log line, e.g.:

```
DEBUG com.ais.db.DatabaseManager - [SQL] SELECT TOP 200 ... WHERE UPPER(RTRIM(g.PSM)) LIKE '%CENTRAL'
DEBUG com.ais.db.DatabaseManager - [SQL-PARAMS] [%CENTRAL]
```

Call this immediately before every `executeQuery()` in any method that builds SQL dynamically. Requires `com.ais.db.DatabaseManager` to be at `DEBUG` level in the logback config, or nothing will print. This directly surfaced the `getLocationsByPsm` parameter/placeholder mismatch bug below — the mismatch between the SQL text (built at one code path) and the number of bound params (set at a different code path) was invisible until the actual resolved SQL and param list could be seen side by side.

---

## Known issues & fixes

### Schema endpoint defense-in-depth authorization

**Symptom before the fix:** The global filter required `AIS_ADMIN`, but a future filter-mapping regression could have exposed `LocationServlet` schema introspection directly.

**Fix:** `LocationServlet.doGet()` and `LocationServlet.doPost()` both check `req.isUserInRole("AIS_ADMIN")` before calling `DatabaseManager.introspectSchema()`. A normal authenticated user receives `403`; an administrator may continue. The global `AuthenticationFilter` remains the primary route boundary.

### Final-answer HTML XSS mitigation

**Symptom before the fix:** `ChatServlet` serialized `state.getFinalResponse()` directly. If an LLM response or database value reached the browser as unsafe HTML, the UI could render script elements or event handlers.

**Fix:** `ChatServlet.buildResponse()` passes the final answer through an OWASP Java HTML Sanitizer allowlist immediately before placing it in the JSON response. Database-derived values are still escaped in `OllamaService`; the sanitizer is the final defense for the complete assembled answer. The sanitizer dependency is present in `pom.xml` and must remain packaged in the WAR.


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

**Symptom:** Logs show auto-approved on every request without a Tencent API call.

**Cause:** `tencent.api.key` is missing or blank, and `ollama.base_url` points to the wrong host or port.

**Fix:** Confirm the active provider is configured correctly:

```bash
# Tencent mode — confirm key is set
grep tencent.api.key /opt/ais_ai/config/application.properties

# Ollama mode — confirm reachable
curl http://<ollama-ip>:11434/api/tags
```

### Verifier adds latency

**Symptom:** Each request takes 4–8 seconds longer than before.

**Cause:** The verifier makes a second LLM call for every response.

**Options:**

- Use a faster Tencent model: `tencent.api.model=deepseek-v4-flash` or `tencent.api.model=hunyuan-lite`
- Use a smaller Ollama model: `ollama.verifier.model=phi3:mini`
- Disable verification for development: `graph.verification.enabled=false`

### Tencent API key security

**Never commit your API key to source control.** Use an external config file:

```bash
# Store key outside the WAR
sudo nano /opt/ais_ai/config/application.properties
sudo chmod 600 /opt/ais_ai/config/application.properties
export APP_CONFIG_PATH=/opt/ais_ai/config/application.properties
```

Add to `.gitignore`:

```gitignore
src/main/resources/application.properties
bin/application.properties
*.properties
.env
```

### Duplicate location code / private owner confusion

`LOC_CD` can appear multiple times in historical data. Queries may return the current row by default.

### 50-row tool limit may miss matches / modifier applied to wrong step

`DatabaseManager` caps list queries at 50 rows for performance. If a compound query (e.g., "first historic location under PSM/KT") requires intersecting two 50-row lists, the true answer may be excluded from the first page. A second, related bug was that a modifier such as `FIRST` could be applied to an intermediate step (`PSM_LOCATIONS`) instead of the final filtering step (`HISTORIC_BUILDING`), causing the cross-filter to run against only one location and return zero matches.

**Fix:**

1. `QueryPlanner.convertLlmPlan()` sorts by priority, normalizes and validates relations, rejects invalid first-step filtering/enrichment, and preserves explicit modifiers on the step where the LLM placed them. A top-level modifier is distributed only when no step already declares one.
2. `OllamaService.executePlan()` selects the last executable step with a modifier, including dependent `use_previous_codes` steps. It applies the modifier after the relation has been evaluated, so `FIRST`/`OLDEST` operates on the intended intersection.
3. The redundant-detail guard prevents a following detail step from rendering the same selected location twice.
4. The pipeline detects an empty/useless fast-path result and falls back to LLM-generated SQL. `DatabaseManager.executeLlmGeneratedQuery()` already injects `TOP 200` when no `TOP` is present.

4. If the SQL fallback itself returns zero rows, `isUselessSqlResult()` now detects `count=0` / empty `results` and falls back to the agent loop instead of presenting an empty table.

5. When the SQL fallback returns a match and the user asked for details (`showDetails=true`), the pipeline automatically calls `hardcode_query` on the returned `LOC_CD` so the response is the full location detail card (general info table + reports) instead of a bare SQL result table.

### `getLocationsByPsm` placeholder/parameter count mismatch crashes every filtered PSM lookup

**Symptom:** Confirmed live via `logSql()` debug output — every `locations_by_psm` call with a `location` argument throws instead of returning rows, and the exception is silently swallowed:

```
[SQL] SELECT TOP 200 ... WHERE UPPER(RTRIM(g.PSM)) LIKE '%CENTRAL WEST' AND UPPER(g.ADDRESS) LIKE ? ...
ERROR: The value is not set for the parameter number 2.
```
or, with a location argument supplied:
```
[SQL] ... LIKE '%CENTRAL' AND UPPER(g.ADDRESS) LIKE '%CENTRAL%' ...
[SQL-PARAMS] [%CENTRAL, %CENTRAL%, %CENTRAL%]
ERROR: The index 3 is out of range.
```

**Cause:** The number of `?` placeholders actually present in the generated SQL string and the number of `ps.setString(n, ...)` calls were derived from two different conditions that had drifted out of sync during earlier edits — one branch appended a location clause with 1 placeholder but the code called `setString` for indices 2 **and** 3, and another branch had a location clause appended unconditionally while `setString(2, ...)` was only called conditionally. Because `DatabaseManager` catches `SQLException` internally and returns an empty list, this looked identical to a legitimate "no matches" result at every layer above it — including the LLM, which then wastefully retried several invented PSM name variants (`CENTRAL DISTRICT`, `CENTRAL OFFICE`, ...) believing the real match (e.g. `PSM/CENTRAL WEST`) genuinely had zero locations.

**Fix:** Rebuild `getLocationsByPsm(psm, location)` so the placeholder-append and the param-bind-count are both derived from **one** `hasLocation` boolean, so they cannot go out of sync again:

```java
boolean hasLocation = location != null && !location.trim().isEmpty();
StringBuilder sql = new StringBuilder(
    "SELECT TOP 200 g.LOC_CD, g.LOC_NAME, g.ADDRESS FROM ais.A_GENERAL_INFO g "
    + "WHERE UPPER(RTRIM(g.PSM)) LIKE ?");
if (hasLocation) {
    sql.append(" AND (UPPER(g.LOC_NAME) LIKE ? OR UPPER(g.ADDRESS) LIKE ?)");
}
...
ps.setString(1, psmParam);
if (hasLocation) {
    ps.setString(2, locParam);
    ps.setString(3, locParam);
}
```

This same call site also had a double-wildcard regression (`"%" + psm + "%"`, matching `PSM/CENTRAL EAST` and `PSM/CENTRAL WEST` when searching for `CENTRAL`) reappear after being fixed once already — the trailing-wildcard-only form (`"%" + psm.trim().toUpperCase()`, no closing `%`) must be preserved so a PSM name search only matches PSMs that *end with* the given term, not any PSM merely *containing* it.

### `findOldestWithDetails()` / `findByYear()` O(n) full-detail lookups on OLDEST/NEWEST modifiers

**Symptom:** Confirmed live via `ToolCallAuditTest.java` — a single, ordinary-sounding question triggered a 33.6-second response with **161** tool calls:

```
Modifier: oldest
Prompt: What is the oldest historic building on record?
Wall time: 33675ms | Total tool calls: 161
    -> search_historic_building
    -> hardcode_query   (x160)
```

**Cause:** `postProcess()` first calls `search_historic_building` with no location filter, which can return up to 200 candidate rows (`SELECT TOP 200`). The old `findOldestWithDetails()` (and its generalized twin `findByYear()`) then looped over **every single candidate**, firing a full `hardcode_query` (general info + reports + a rendered map `<iframe>` block) just to read one numeric column (`BLDG_COMPLETION_YEAR`) — an O(n) full-detail fetch to answer what is really a `MIN()`/`MAX()` question.

**Fix:** Added `DatabaseManager.getGeneralInfoBatch(locCds)`, which fetches `LOC_CD`/`LOC_NAME`/`DEPT_CD`/`DEPT_DESC`/`BLDG_COMPLETION_YEAR` for up to 200 codes in **one** `IN (...)` query — the same batch pattern already used by `getLocationNames()`. `findByYear()` now calls this once, does the min/max comparison in Java, and calls `hardcode_query` **exactly once**, for the single winning code, to render its detail card. This turns the 161-call / 33.6s case into 3 calls total (search + 1 batch query + 1 detail lookup). `findOldestWithDetails()` should be deleted and its two call sites in `postProcess()` redirected to `findByYear(codes, false, result)`, since it did the same thing with `findMax` hardcoded to `false` and no batching.

### Tool calls recorded twice per real invocation in the agent loop

**Symptom:** The UI's tool-call accordion, `getToolSummary()` text, and `ToolCallAuditTest.java` reports all showed pairs of identical `{name, args, result}` entries back to back for a single tool invocation — even though `logSql()` debug output confirmed the underlying SQL/DB call only executed **once**.

**Cause:** `OllamaService.runAgentLoop()` called `result.addToolCall(toolName, args, toolResult)` twice for the same call: once immediately after `mcpClient.callTool()`, and again after `postProcess()` ran on the same result.

```java
toolResult = mcpClient.callTool(toolName, args);
result.addToolCall(toolName, args, toolResult);          // 1st record
...
toolResult = postProcess(toolName, args, toolResult, userPrompt, sessionId, result);
result.addToolCall(toolName, args, toolResult);           // 2nd record — same call
```

This inflated every apparent "redundant tool call" count by roughly 2x on top of any real LLM-driven redundancy, making the true scope of tool-selection bugs hard to gauge from the UI or logs alone.

**Fix:** Tool execution now records one `ToolCallRecord` per real invocation. `OllamaService` no longer expands or dispatches `check_reports` through a tool-specific branch; report-type expansion belongs in the registered provider/catalog implementation. Duplicate detection is shared through `AgentResult.findEquivalentCallResult()` across the fast path and agent loop.

### `toolCalls` in the `/api/chat` JSON response always showed `args: {}` and a repeated/shared `result`

**Symptom:** Every entry in the `toolCalls` array returned by `/api/chat` — and therefore every tool-call card rendered by `chat.js`'s `renderToolCall()` — showed `Args: {}` regardless of what args the tool actually received, and when a request made multiple different tool calls, every card showed the exact same (very long, concatenated) `result` text instead of its own.

**Cause:** Two separate, compounding bugs across three files:

1. `ChatServlet.buildToolCallsJson()` hardcoded the JSON literal `"args":"{}"` into every entry — it never attempted to read real argument data at all.
2. `GraphState` only ever exposed a flat `List<String> toolCallsMade` (names only, no args) and one `String rawToolOutput` holding **all** tool outputs for the entire request concatenated together (`"Tool: X\nOutput: Y\n---\nTool: Z\nOutput: W\n---\n..."`) — there was no field capable of carrying per-call data at all.
3. `PrimaryLlmNode` populated those two flattened fields from `AgentResult.getToolCalls()` (which *does* have the real per-call `name`/`args`/`result` triples) via `result.getToolNames()` and `result.getToolSummary()`, discarding the real per-call data one layer before it ever reached `GraphState`.

**Fix (three-file change):**

1. `GraphState` gains a new field, `List<Map<String, Object>> toolCallDetails`, alongside (not replacing) the old `toolCallsMade`/`rawToolOutput` fields for backward compatibility. Each map has exactly `name`, `args` (a real `Map`), and `result` (that call's own output).
2. `PrimaryLlmNode.process()` additionally copies `AgentResult.getToolCalls()` directly into `state.setToolCallDetails(...)`, one map per `ToolCallRecord`.
3. `ChatServlet.buildToolCallsJson()` is rewritten to build a real Jackson `ArrayNode`/`ObjectNode` tree from `state.getToolCallDetails()` (falling back to the old flattened fields only if `toolCallDetails` is ever empty), so `args` is serialized as a genuine JSON object via `mapper.valueToTree(...)` and each entry's `result` is that call's own text — never hand-built string concatenation, which is also fragile against unescaped characters in tool output.

### "Verification Note" banner appears on every response even with verification disabled

**Symptom:** Every single chat response — regardless of prompt, tool calls, or content — was wrapped in a `"Verification Note / The verifier flagged this response as potentially incomplete / Best available answer:"` banner, even though logs confirmed `graph.verification.enabled=false` and the real verifier node was never registered (`Nodes: [formatter, planner, fallback, primary_llm]` — no `verifier`).

**Cause:** `GraphState.verificationResult` defaults to `VerificationResult.SKIPPED` (its field initializer), not `null`. `FormatterNode.process()`'s check only special-cased `null` and `APPROVED`:

```java
if (vr == null || vr == GraphState.VerificationResult.APPROVED) {
    finalResponse = state.getPrimaryResponse();       // clean path
} else {
    finalResponse = buildResponseWithDifference(state); // banner path
}
```

Since verification is disabled, `verificationResult` is left at its default `SKIPPED` value on every request, which fell into the `else` branch and got the full warning-banner treatment — even though `SKIPPED` means "verification never ran" (correct/expected when disabled), not "verification found a problem." This exactly matched this project's own documented intended behavior (see [Disabling verification](#disabling-verification): *"The `FormatterNode` treats a `null` / `SKIPPED` verification result the same as `APPROVED`"*) — the code had just never actually implemented that.

**Fix:** add `SKIPPED` to the clean-path condition:

```java
if (vr == null
        || vr == GraphState.VerificationResult.APPROVED
        || vr == GraphState.VerificationResult.SKIPPED) {
    finalResponse = state.getPrimaryResponse();
} else {
    // Only REJECTED / RETRY (i.e. a verifier actually ran and flagged
    // something) should show the warning banner.
    finalResponse = buildResponseWithDifference(state);
}
```

Confirmed fixed via live testing — no "Verification Note" banner appeared on any subsequent response with verification disabled.

### Tool-call deduplication needed two iterations to actually work

**Symptom:** After the [double-`addToolCall()` bug](#tool-calls-recorded-twice-per-real-invocation-in-the-agent-loop) was fixed, `ToolCallAuditTest` still flagged genuine `EXACT DUPLICATE` tool calls — e.g. `locations_by_psm(psm=CENTRAL)` called once by the fast path (`executePlan()`), then called again with the exact same (or a case-only-different, e.g. `psm=Central`) argument by the agent loop later in the same request, because the LLM had no way of knowing the fast path already tried that exact call and got zero rows.

**First attempt (insufficient):** a `Map<String, String> exactCallCache` local variable scoped inside `runAgentLoop()`. This only caught duplicates *between agent-loop iterations* — it was a fresh, empty map every time `runAgentLoop()` ran, so it never saw calls made earlier by `executePlan()`'s fast path, which is exactly the cross-phase case that was actually happening.

**Fix:** move the dedup check to `AgentResult` itself (`findEquivalentCallResult()` / `normalizeArgsKey()`), since it is the one object threaded through *every* phase of `invoke()` (`executePlan()`, `runAgentLoop()`, `generateAndExecuteSql()` all call `result.addToolCall(...)`). Checking "has this exact tool+args already been recorded anywhere in this request?" against `AgentResult`'s own list catches cross-phase repeats with no extra state to wire through method signatures. See [New capabilities](#new-capabilities) for how this is used.

### Referential prompt resolution (`resolveReferentialPrompt`) silently dropped from `invoke()`

**Symptom:** Follow-up prompts using referential words — e.g. *"show BSI report for that"*, *"check reports for them"* — stopped resolving to remembered session codes and were instead sent to keyword extraction as literal text containing the word `"that"`/`"them"`.

**Cause:** While adding the "exclude undefined `<field>`" feature's Phase-0 prompt-stripping step to `invoke()`, the pre-existing call to `resolveReferentialPrompt(userPrompt, sessionId)` was accidentally replaced rather than kept alongside it — the method itself was still defined in the class, just never called.

**Fix:** Both Phase-0 steps must run, referential resolution first. Note: in the latest architecture, Phase 0b (`extractExcludeUndefinedField` / `stripExcludeUndefinedPhrase`) was **completely removed** in favor of scalable LLM keyword extraction, simplifying `invoke()` back to cleanly running Phase 0a (`resolveReferentialPrompt`) without any brittle string stripping:

```java
// Phase 0a: resolve referential words ("that"/"them") from session memory
String resolvedPrompt = resolveReferentialPrompt(userPrompt, sessionId);
if (!resolvedPrompt.equals(userPrompt)) {
    userPrompt = resolvedPrompt;
    result.setPrompt(userPrompt);
}
```

A general lesson from this and the double-`addToolCall()` bug: when adding a new step to a long linear method like `invoke()`, diff the full before/after rather than only the new block, since it's easy to accidentally drop an adjacent, unrelated line while editing around it.

### Empty keyword extraction JSON when using reasoning models (e.g., `deepseek-v4-flash`)

**Symptom:** `extractKeywords()` logs an empty response string or falls back to `UNKNOWN` intent with empty plan `[]`, triggering SQL generation or agent loop fallbacks on simple queries.

**Cause:** Reasoning models (like DeepSeek-R1 or DeepSeek-V4-Flash) generate internal chain-of-thought reasoning blocks (`<think>...</think>`) before outputting JSON. With a strict token limit of `maxTokens = 1024`, complex phrasing causes the model to exhaust its token budget while still inside the `<think>` block, leaving no tokens for the actual JSON payload. When `stripThinkingTags()` removes the incomplete thought block, the resulting string is empty (`""`).

**Fix:** Increased the token budget in `extractKeywordsFromLlm()` from `1024` to `2048` (`callLlmSimple(fullPrompt, 0.0, 2048)`), providing sufficient headroom for chain-of-thought reasoning before JSON emission.

### Tomcat mid-request reload crashes and classloader leaks (`IllegalStateException`)

**Symptom:** When reloading the web application in Tomcat (e.g., via auto-deploy or IDE WTP reload) while a request thread is waiting for a slow LLM API response, logs show `WARNING: The thread [...] is still processing a request... This is very likely to create a memory leak`, followed by `java.lang.IllegalStateException: Illegal access: this web application instance has been stopped already. Could not load [ch.qos.logback.core.status.WarnStatus]` when the LLM response finally returns.

**Cause:** When Tomcat stops the application context, background pools and worker threads (HikariCP, OkHttp task runners, Okio watchdog, MSSQL timer threads) continue running if not explicitly shut down. When an orphaned worker thread attempts to log or load classes after context destruction, Tomcat's `WebappClassLoaderBase` blocks access and throws `IllegalStateException`.

**Fix:** Registered an `AppLifecycleListener` (`ServletContextListener`) that explicitly closes the HikariCP database pool (`DatabaseManager.closePool()`), evicts and shuts down OkHttp connection pools/dispatchers (`OllamaService.shutdownHttpClient()`), and deregisters JDBC drivers upon `contextDestroyed()`.

### Agent loop crashes when LLM returns tool-call arguments as a JSON string

**Symptom:** Logs show an `IllegalArgumentException` / `MismatchedInputException` inside `OllamaService.runAgentLoop()` when the LLM chooses a tool:

```
Cannot construct instance of `java.util.LinkedHashMap` ... from String value ('{"locName": "playground", "location": "Lo Wu"}')
```

**Cause:** Some LLM providers (e.g., Tencent Cloud) return the `function.arguments` field of a `tool_call` as a JSON string instead of a JSON object. Jackson's `convertValue()` cannot turn a string directly into a `Map`.

**Fix:** `runAgentLoop()` now detects textual `arguments` nodes and parses the string with `mapper.readValue()` before using the arguments. If the string is empty, it falls back to an empty `LinkedHashMap` so the tool call can still proceed.

### Disambiguation of row limits ('first 50') vs. single-location details ('FIRST')

**Symptom:** A query asking for a paginated list such as *"Show first 50 locations under PSM Central"* unexpectedly triggers `hardcode_query` on the #1 result in the list instead of displaying all 50 rows.

**Cause:** Cloud reasoning models (like `deepseek-v4-flash`) can exhibit semantic confusion when evaluating the word `"first"` in `"first 50"`. Without explicit disambiguation, the model can interpret `"first"` as a request for a single-location detail view (`modifier="FIRST"`), causing the backend modifier handler to fetch the first code and call the catalog-resolved detail tool.

**Fix:** Added an explicit disambiguation rule to `KEYWORD_EXTRACT_PROMPT` in `OllamaService.java`: when words like `'first'` or `'top'` are followed by a number (e.g. `'first 50 locations'`, `'top 20'`), this is exclusively a row count limit (`limit: 50`); the LLM is instructed never to set `modifier='FIRST'`, `showDetails=true`, or `autoFetchFirst=true` in that case.

### Follow-up step uses stale session codes after a `FIRST` modifier

**Symptom:** A query such as `Check BSI reports for first location under PSM/KT` fetches the first location correctly, but the subsequent `check_reports` step uses an unrelated list of codes from an earlier session (e.g., from a previous `search_by_name` query).

**Cause:** `executePlan()` applies the `FIRST` modifier by calling `hardcode_query` on the first matching code. The result is a single-location detail object, not a `results` array, so `extractCodesFromResult()` returned an empty list and the session memory was not updated. The next `use_previous_codes` step therefore fell back to whatever codes were still in memory from a previous turn.

**Fix:** `extractCodesFromResult()` now also extracts `LOC_CD` from:

1. the `results` array (existing behaviour),
2. the `general` object inside a single-location detail response,
3. a direct top-level `LOC_CD` field (e.g., from structured `findOldest`/`findByYear` responses).

This ensures the session memory is refreshed with the actual selected code after `FIRST`, `OLDEST`, `NEWEST`, or `LATEST` modifiers, so `use_previous_codes` steps receive the correct input.

### Agent loop returns a confusing answer after a valid 0-row SQL result

**Symptom:** For a query like `Which playground in Lo Wu has a historic status?`, the fast path and SQL fallback both return 0 rows, but the pipeline then enters the agent loop and returns a long, confusing concatenation of unrelated tool results (e.g., searching historic grades by grade 1/2/3, calling `search_loc_cd_history` for unrelated codes, etc.).

**Cause:** `isUselessSqlResult()` was treating a successful SQL execution that returned 0 rows as "useless", forcing the pipeline into the agent loop. At the same time, the LLM plan for `NAME_SEARCH + HISTORIC` only passed the district (`Lo Wu`) as the name query, missing the specific subject (`playground`), so the fast path returned 0 before the SQL fallback even ran.

**Fix:**
1. `isUselessSqlResult()` now treats a successful SQL execution with `count=0` as a valid empty answer (the formatter renders it as "No matching locations found"). The agent loop is only used when the SQL execution itself fails or returns malformed output.
2. The keyword-extraction prompt now includes a `LOCATION/NAME PLAN RULES` section and a concrete example for `Which playground in Lo Wu has a historic status?`. The LLM is instructed to put the subject (`playground`) in the step's `locName` param and the district (`Lo Wu`) in the step's `location` param.
3. `OllamaService.buildArgs()` now uses a shared `getLocationFilter()` helper that recognizes `locationFilter`, `location`, and `locationName` so the DB location filter is applied consistently.

### Department and private-owner data gaps

`locations_by_dept` supports common codes (`AFCD`, `LCSD`, `HD`, `DSD`). If a department has no rows the planner may fall back to broader searches.

### Multi-report intersection not computing

**Symptom:** A query like `Which AFCD locations have both BSI and KAI reports?` returns separate BSI and KAI check results but never shows the actual intersection (codes that have both). Each step independently checks all 96 AFCD codes instead of narrowing down from the previous step.

**Cause:** Two methods (`extractCodesFromResult()` and `crossFilter()`) could not read the `check_reports` output format. `check_reports` returns `{withReport: [...], withoutReport: [...], totalChecked: N}` instead of the standard `{results: [...]}` format that other tools return. As a result:
1. `extractCodesFromResult()` returned an empty list after each `check_reports` step, causing the next step to fall back to session memory (all 96 codes).
2. `crossFilter()` couldn't find a `results` array in either input and silently returned the second result unchanged.

**Fix:**
1. `extractCodesFromResult()` now recursively collects `LOC_CD` and `CURRENT_LOC_CD` from standardized and legacy nested response shapes.
2. `crossFilter()` uses those generic code sets and fails closed when either side has no usable codes or processing fails.
3. `QueryPlanner` validates relation support, first-step constraints, and consumed fields before execution.
4. `executePlan()` applies `filter_previous` after the current tool result is available and appends a generalized intersection summary when two or more filters are chained.

### LLM inventing unknown step types

**Symptom:** Queries like `Which department manages the oldest historic monument in Lo Wu?` log warnings like `⚠️ Unknown intent type in buildArgs: LOCATION_NAME_SEARCH` and `⚠️ No tool mapped for intent: DEPARTMENT_INFO`. The query fails silently because the LLM-invented step types have no tool mapping.

**Cause:** The LLM invents step type names that don't match the registered intent constants (e.g., `LOCATION_NAME_SEARCH` instead of `NAME_SEARCH`, `DEPARTMENT_INFO` instead of `LOCATION_CODE`).

**Fix (three-layer defense):**
1. **Prompt constraint:** The `KEYWORD_EXTRACT_PROMPT` now includes a `VALID PLAN STEP TYPES` section listing the 8 recognized type names with their expected params.
2. **Parse-time normalization:** `parseLlmPlan()` maps common LLM-invented aliases to the correct registered types via a switch statement.
3. **Runtime validation:** `executePlan()` validates each step has a tool mapping before execution, skipping unrecognized steps and transferring any orphan modifiers to the answer step.

### `request cannot be resolved` in `OllamaService.formatSingleLocation()`

**Symptom:** Compile error in Eclipse/Maven:

```
Description: request cannot be resolved
Resource: OllamaService.java
Location: line 1339
Type: Java Problem
```

**Cause:** `formatSingleLocation()` builds a location-map `<iframe>` and calls `request.getContextPath()` to prefix the `src` URL. `OllamaService` is a plain service class with no `HttpServletRequest` in scope — only `ChatServlet` ever sees a request object, per the [request flow](README.md#request-flow). The servlet's request was never threaded down through `invoke()` → `runAgentLoop()`/`executePlan()` → `formatAsHtml()` → `formatSingleLocation()`.

**Fix:** Since the context path never changes per-request, cache it once instead of passing a request object through the whole pipeline:

1. Add a static field + setter to `OllamaService`:

   ```java
   // ── Servlet context path, set once at startup by ChatServlet ─────
   private static volatile String contextPath = "";

   public static void setContextPath(String path) {
       contextPath = (path == null) ? "" : path;
   }
   ```

2. Replace `request.getContextPath()` with `contextPath` in `formatSingleLocation()`:

   ```java
   html.append("<iframe src='")
       .append(contextPath)
       .append("/plugin/plugin.html?locCd=")
   ```

3. Set it once from `ChatServlet`'s existing no-arg `init()` (no need to override `init(ServletConfig)` — `GenericServlet` already stores the config before the no-arg `init()` runs, so the inherited `getServletContext()` is available directly):

   ```java
   @Override
   public void init() throws ServletException {
       try {
           OllamaService.setContextPath(getServletContext().getContextPath());
           // ...existing init logic (loading properties, building the verification graph)...
       } catch (Exception e) {
           throw new ServletException("Failed to initialize ChatServlet", e);
       }
   }
   ```

   ⚠️ Use `getServletContext()`, not `config.getServletContext()` — `config` is not in scope inside the no-arg `init()` override.

### Location map iframe 404s even after the context-path fix

**Symptom:** The `<iframe src="{contextPath}/plugin/plugin.html?...">` on a location detail card 404s in the browser, even though `contextPath` resolves correctly (e.g. `/ais_ai`).

**Cause:** `plugin.html` lived at `src/main/plugin/plugin.html`, which is **outside** `src/main/webapp/`. Maven's `maven-war-plugin` only packages files under `src/main/webapp/` into the deployed WAR's web root, so the file was never copied to `/ais_ai/plugin/plugin.html` at all.

**Fix:** Move the file into the standard webapp resource tree so the existing `contextPath + "/plugin/plugin.html"` URL resolves correctly:

```bash
git mv src/main/plugin/plugin.html src/main/webapp/plugin/plugin.html
```

Then rebuild (`mvn clean package`). If `src/main/plugin` must stay where it is for another reason, an alternative is to add it as an extra web resource directory in `pom.xml` instead:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-war-plugin</artifactId>
    <configuration>
        <webResources>
            <resource>
                <directory>src/main/plugin</directory>
                <targetPath>plugin</targetPath>
            </resource>
        </webResources>
    </configuration>
</plugin>
```

### `ClassNotFoundException: org.apache.jsp.jsp.index_jsp` after editing `index.jsp`

**Symptom:** Tomcat logs a `SEVERE: Servlet.service() ... threw exception [org.apache.jasper.JasperException: java.lang.ClassNotFoundException: org.apache.jsp.jsp.index_jsp]` after `index.jsp` was edited (e.g. while splitting its inline `<script>` out into `js/chat.js`), even though the JSP source itself is well-formed.

**Cause:** This is a stale/incremental-publish problem, not a syntax error. Eclipse WTP's Tomcat integration does an incremental republish after a JSP edit, and Jasper's compiled-JSP work directory can end up out of sync — it references a `.class` file for `index_jsp` that no longer exists or was left in a partial state from an earlier broken save, so the class loader throws `ClassNotFoundException` instead of a compile error.

**Fix:**

1. Stop the server in Eclipse.
2. In the Servers view, double-click the Tomcat server entry and use **Clean...** on the Overview page (wipes the deployed webapp and Jasper's compiled-JSP cache), or manually delete:
   ```
   <workspace>\.metadata\.plugins\org.eclipse.wst.server.core\tmp1\work\Catalina\localhost\<context>
   <workspace>\.metadata\.plugins\org.eclipse.wst.server.core\tmp1\wtpwebapps\<context>
   ```
3. Refresh the project, run **Project → Clean...**, and restart the server.

If the error persists, check the Tomcat console for an earlier `JasperException: Unable to compile class for JSP` from a prior request — that indicates a genuine compile error (e.g. an unclosed `<script>`/`</script>` pair) rather than a stale-cache issue, and will point to the exact malformed line.

---

## Notes

- The project expects location data in the `ais` schema of SQL Server.
- Protected routes require a trusted Tomcat/container principal. Local BASIC users belong in the active `$CATALINA_BASE/conf/tomcat-users.xml`, never inside the WAR; production should use enterprise SSO/Realm integration over HTTPS.
- Authentication/RBAC is operational when the active Realm accepts credentials: anonymous protected routes return `401`, an authenticated user without the required role returns `403`, and an administrator can access the schema route. A repeated login prompt plus authenticated curl `401` still means Tomcat identity validation failed.
- The assistant requires either a Tencent Cloud API key or a reachable Ollama server, plus a SQL Server database.
- The verification graph is compiled once at `ChatServlet.init()` and reused for all requests.
- If `tencent.api.key` is set, all LLM calls — including verification — use Tencent Cloud. Ollama config is ignored.
- If `verifier.model` is not set, it defaults to the same model as the active provider (`ollama.model` or `tencent.api.model`).
- Update `src/main/resources/application.properties` before running in a new environment.
- Location code links in result tables use root-relative URLs (`/AIS/AssetSearch/index.jsp`) and work correctly as long as both apps are deployed on the same server.
- The location detail map `<iframe>` uses a context-relative URL (`{contextPath}/plugin/plugin.html?locCd=...`), built from a context path cached once via `OllamaService.setContextPath()` in `ChatServlet.init()`. `plugin.html` must live under `src/main/webapp/plugin/` — anywhere outside `src/main/webapp/` is excluded from the packaged WAR. See [Known issues & fixes](#known-issues--fixes).
- Likewise, `js/chat.js` must live under `src/main/webapp/js/chat.js` — the same WAR-packaging rule applies to any static asset referenced from `index.jsp`. Its `<script src="<%= request.getContextPath() %>/js/chat.js">` tag will 404 if the file isn't under `src/main/webapp/`.
- `index.jsp` sets `isELIgnored="true"` because its script relies on JS template-literal syntax (`` `${...}` ``) which Jasper would otherwise try to evaluate as JSP Expression Language and fail with `javax.el.ELException: Function [...] not found`. Keep this directive if you add more inline scripts to the page.
- **Resolved:** the "Verification Note" banner appearing on every response has been root-caused and fixed — see [Known issues & fixes](#known-issues--fixes) ("Verification Note" banner appears on every response even with verification disabled).
- **Security:** `ChatServlet` sanitizes the final answer with the OWASP Java HTML Sanitizer before JSON serialization. The Java 8 dependency is resolved; verify it remains packaged in the WAR.
- **Open investigation:** `search_by_name` / `DatabaseManager.searchByName()` can return geography-false-positive matches for queries like a PSM/district name that has no exact match — e.g. searching for `"Central"` can surface `"Hong Kong Central Library"` (physically in Causeway Bay) or `"Queen's Road Central"` (a street name spanning multiple districts), because the underlying `LIKE '%term%'` matches `LOC_NAME`/`ADDRESS` text with no district/geography anchoring. This may be an inherent limitation of free-text name search rather than a pure SQL bug — the agent loop arguably should not fall back to a plain name search for what is really a geography/PSM question in the first place. The `PSM/`-prefix-restricted, slash-anchored matching added to `getLocationsByPsm()` (see [New capabilities](#new-capabilities)) does not apply here since `searchByName()` searches `LOC_NAME`/`ADDRESS`, not `PSM`.
- **Open investigation:** the LLM/agent loop can still invent plausible-sounding but non-existent PSM name variants (e.g. trying `CENTRAL DISTRICT`, `CENTRAL OFFICE` after `CENTRAL` returns 0 rows) instead of cross-referencing the already-fetched `list_psms` result for a real fuzzy match. The tool-call deduplication (see [New capabilities](#new-capabilities)) prevents these invented variants from being *re-tried* if the exact same guess recurs, but does not stop the LLM from inventing a *new* wrong guess each iteration. Not yet fixed — would likely require either a stronger `SYSTEM_PROMPT` instruction or a Java-side fuzzy-match fallback in `postProcess()` that runs when `locations_by_psm` returns 0 rows.


### ArcGIS HTTP 401 / HTTPS timeout

Observed diagnostics:

```text
TCP 443: failed
TCP 80: succeeded
HTTP ArcGIS metadata: 401 Unauthorized
```

This proves that the iframe receives the code but the ArcGIS Web Adaptor requires authentication. Test with `Invoke-WebRequest -UseDefaultCredentials`. For browser-based integrated authentication, add the server to the approved intranet zone and configure ArcGIS JS `esriConfig.request.trustedServers`, plus credentialed CORS. Production should prefer a fixed-path same-origin reverse proxy. Do not create an unrestricted proxy.

The ArcGIS SDK logs layer failures before application catch handlers run; the plugin must still replace the iframe with a friendly message. A browser network stack trace is not evidence that `locCd` parsing failed.
