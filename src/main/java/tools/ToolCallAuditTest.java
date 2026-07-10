import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone black-box tool-call auditor.
 *
 * WHY: your recent bug ("show locations under PSM central" triggers 20+
 * redundant locations_by_psm / list_psms / search_by_name calls across 5
 * agent-loop iterations, ~14s wasted) lives in LLM tool-selection behavior,
 * not the DB layer — so a DB-layer unit test won't catch it. This hits the
 * REAL running app's /api/chat endpoint (same one chat.js calls), fires a
 * batch of prompts, and reports exactly which tools got called, how many
 * times, with what args, and flags anything that looks redundant.
 *
 * HOW TO RUN (no build tooling needed — plain javac/java):
 *   1. Make sure Tomcat is running and ais_ai is deployed (localhost:8090).
 *   2. From a terminal:
 *        javac ToolCallAuditTest.java
 *        java ToolCallAuditTest
 *   3. Read the console report. Exit code is non-zero if any prompt
 *      triggered a flagged redundancy, so you can wire this into a
 *      pre-commit/CI check later if you want.
 *
 * NOTE ON JSON PARSING: this uses lightweight regex extraction instead of a
 * JSON library, specifically so this file has ZERO dependencies and can be
 * dropped anywhere and run immediately. It assumes toolCalls[].args is a
 * flat (non-nested) JSON object, which matches every example you've pasted
 * so far (e.g. {"psm":"CENTRAL"}, {"psm":"CENTRAL","location":"Central"}).
 * If a tool ever returns nested-object args, upgrade this to use whatever
 * JSON library is already on your project's classpath (Jackson/Gson/org.json)
 * instead of the regex approach.
 */
public class ToolCallAuditTest {

    // ── Adjust to match your deployment ─────────────────────────────────
    private static final String BASE_URL = "http://localhost:8090/ais_ai";
    private static final String CHAT_ENDPOINT = BASE_URL + "/api/chat";

    // ── Sample prompts — one (or more) per tool/behavior you want covered.
    // Add/edit freely; label is just for the report, prompt is what gets
    // sent verbatim to /api/chat as {"prompt": "..."}.
    private static final String[][] PROMPTS = {
        // The specific bug you're chasing right now:
        { "PSM (ambiguous, no exact match)", "Show locations under PSM central" },
        { "PSM (exact match, should be fast)", "Show locations under PSM/KT" },
        { "PSM (exact match, CENTRAL WEST)", "Show locations under PSM CENTRAL WEST" },
        { "List all PSMs", "List all PSMs" },

        // Location code lookups:
        { "Direct location code", "SB04400361000" },
        { "Full info for location", "Tell me everything about SB04400361000" },

        // Name search (suspected false-positive source):
        { "Name search, ambiguous term", "Search for locations named Central" },
        { "Name search, specific", "Find playgrounds in Sha Tin" },

        // Department:
        { "Department lookup", "Show locations under department ASD" },

        // Heritage:
        { "Declared monuments", "List declared monuments" },
        { "Historic buildings, graded", "Show grade 1 historic buildings" },

        // Code history:
        { "Former code lookup", "What is the current code for a former code AA00000001000?" },

        // Modifiers (known past bug area — redundant LOCATION_CODE steps):
        { "Modifier: first result", "Show the first location under PSM/KT" },
        { "Modifier: oldest", "What is the oldest historic building on record?" },

        // Reports:
        { "Report check", "Does SB04400361000 have a BSI report?" },
    };

    private static final Pattern TOOLCALL_BLOCK =
            Pattern.compile("\\{\\s*\"name\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"args\"\\s*:\\s*(\\{[^}]*\\}|\"[^\"]*\")");

    public static void main(String[] args) throws Exception {
        System.out.println("=== Tool Call Audit ===");
        System.out.println("Endpoint: " + CHAT_ENDPOINT);
        System.out.println();

        int flaggedCount = 0;
        List<String[]> summaryRows = new ArrayList<String[]>();

        for (String[] entry : PROMPTS) {
            String label = entry[0];
            String prompt = entry[1];

            System.out.println("--- " + label + " ---");
            System.out.println("Prompt: " + prompt);

            long start = System.currentTimeMillis();
            String responseBody;
            try {
                responseBody = postChat(prompt);
            } catch (Exception e) {
                System.out.println("  [ERROR] Request failed: " + e);
                summaryRows.add(new String[]{ label, "REQUEST_FAILED", "-", "-" });
                System.out.println();
                continue;
            }
            long elapsedMs = System.currentTimeMillis() - start;

            List<ToolCall> calls = extractToolCalls(responseBody);
            Map<String, Integer> countByName = new LinkedHashMap<String, Integer>();
            Map<String, Integer> countByNameAndArgs = new LinkedHashMap<String, Integer>();

            for (ToolCall tc : calls) {
                countByName.put(tc.name, orZero(countByName.get(tc.name)) + 1);
                String key = tc.name + "|" + tc.args;
                countByNameAndArgs.put(key, orZero(countByNameAndArgs.get(key)) + 1);
            }

            System.out.println("  Wall time: " + elapsedMs + "ms | Total tool calls: " + calls.size());
            for (ToolCall tc : calls) {
                System.out.println("    -> " + tc.name + " " + tc.args);
            }

            List<String> issues = new ArrayList<String>();

            for (Map.Entry<String, Integer> e : countByName.entrySet()) {
                if (e.getValue() > 1) {
                    issues.add("Tool '" + e.getKey() + "' called " + e.getValue() + " times");
                }
            }
            for (Map.Entry<String, Integer> e : countByNameAndArgs.entrySet()) {
                if (e.getValue() > 1) {
                    String[] parts = e.getKey().split("\\|", 2);
                    issues.add("EXACT DUPLICATE: '" + parts[0] + "' called " + e.getValue()
                            + "x with identical args " + (parts.length > 1 ? parts[1] : ""));
                }
            }
            // Heuristic: same tool called with args that only differ by a
            // guessed variant of the same value (e.g. psm=CENTRAL then
            // psm=CENTRAL DISTRICT then psm=CENTRAL OFFICE) — flag if a
            // single tool is called 3+ times total even without exact dupes,
            // since that pattern is what wasted 14s in the PSM bug.
            for (Map.Entry<String, Integer> e : countByName.entrySet()) {
                if (e.getValue() >= 3) {
                    issues.add("POSSIBLE BLIND-GUESSING LOOP: '" + e.getKey()
                            + "' called " + e.getValue() + " times with varying args");
                }
            }

            if (issues.isEmpty()) {
                System.out.println("  [OK] No redundant tool calls detected.");
                summaryRows.add(new String[]{ label, "OK", String.valueOf(calls.size()), elapsedMs + "ms" });
            } else {
                flaggedCount++;
                System.out.println("  [FLAGGED]");
                for (String issue : issues) {
                    System.out.println("    - " + issue);
                }
                summaryRows.add(new String[]{ label, "FLAGGED (" + issues.size() + ")",
                        String.valueOf(calls.size()), elapsedMs + "ms" });
            }

            if (responseBody.contains("Verification Note")) {
                System.out.println("  [NOTE] Response contains 'Verification Note' banner"
                        + " (known to fire even though verificationEnabled=false — separate bug).");
            }

            System.out.println();
        }

        System.out.println("=== Summary ===");
        System.out.printf("%-40s %-18s %-10s %-8s%n", "Prompt", "Status", "ToolCalls", "Time");
        for (String[] row : summaryRows) {
            System.out.printf("%-40s %-18s %-10s %-8s%n", row[0], row[1], row[2], row[3]);
        }
        System.out.println();
        System.out.println(flaggedCount + " of " + PROMPTS.length + " prompts flagged for redundant tool calls.");

        if (flaggedCount > 0) {
            System.exit(1);
        }
    }

    // ── HTTP ────────────────────────────────────────────────────────────

    private static String postChat(String prompt) throws Exception {
        URL url = new URL(CHAT_ENDPOINT);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(60000); // agent loop can take 10-15s+, seen in your logs

        String body = "{\"prompt\": " + jsonQuote(prompt) + "}";
        OutputStream os = conn.getOutputStream();
        try {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        } finally {
            os.close();
        }

        int status = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } finally {
            reader.close();
        }

        if (status < 200 || status >= 300) {
            throw new RuntimeException("HTTP " + status + ": " + sb);
        }
        return sb.toString();
    }

    private static String jsonQuote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // ── Minimal extraction of toolCalls[].{name,args} ───────────────────

    private static List<ToolCall> extractToolCalls(String json) {
        List<ToolCall> result = new ArrayList<ToolCall>();
        Matcher m = TOOLCALL_BLOCK.matcher(json);
        while (m.find()) {
            result.add(new ToolCall(m.group(1), m.group(2)));
        }
        return result;
    }

    private static int orZero(Integer i) {
        return i == null ? 0 : i;
    }

    private static class ToolCall {
        final String name;
        final String args;

        ToolCall(String name, String args) {
            this.name = name;
            this.args = args;
        }
    }
}
