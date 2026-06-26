package com.ais.graph.nodes;

import com.ais.graph.GraphNode;
import com.ais.graph.GraphState;
import com.ais.service.OllamaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class VerifierNode implements GraphNode {

    private static final Logger log =
        LoggerFactory.getLogger(VerifierNode.class);

    private final OllamaService ollamaService;
    private final String        ollamaBaseUrl;
    private final String        verifierModel;
    private final OkHttpClient  httpClient;
    private final ObjectMapper  mapper = new ObjectMapper();

    public VerifierNode(OllamaService ollamaService,
                        String ollamaBaseUrl,
                        String verifierModel) {
        this.ollamaService = ollamaService;
        this.ollamaBaseUrl  = ollamaBaseUrl;
        this.verifierModel  = verifierModel;
        this.httpClient = buildHttpClient();
        log.info("[VerifierNode] Initialized. URL={}, Model={}",
            ollamaBaseUrl, verifierModel);
    }
    
    private OkHttpClient buildHttpClient() {
        try {
            javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public void checkClientTrusted(
                            java.security.cert.X509Certificate[] c, String a) {}
                    public void checkServerTrusted(
                            java.security.cert.X509Certificate[] c, String a) {}
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                }
            };

            javax.net.ssl.SSLContext sslContext =
                    javax.net.ssl.SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            return new OkHttpClient.Builder()
                .sslSocketFactory(
                    sslContext.getSocketFactory(),
                    (javax.net.ssl.X509TrustManager) trustAllCerts[0])
                .hostnameVerifier((hostname, session) -> true)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        } catch (Exception e) {
            log.warn("[VerifierNode] SSL bypass failed ({}), using plain client",
                    e.getMessage());
            return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        }
    }
    
    @Override
    public GraphState process(GraphState state) {
        log.info("[VerifierNode] Verifying. Intent={}, ResponseLen={}",
            state.getDetectedIntent(),
            state.getPrimaryResponse() != null
                ? state.getPrimaryResponse().length() : 0);

        // ── Guard: nothing to verify ───────────────────────────
        if (state.getPrimaryResponse() == null ||
            state.getPrimaryResponse().trim().isEmpty()) {
            state.setVerificationResult(GraphState.VerificationResult.RETRY);
            state.setVerificationReason("Empty primary response - will retry");
            return state;
        }

        // ── Pre-check: detect known failure patterns ───────────
        // If primary LLM already returned an error/empty result message,
        // go straight to RETRY without calling the verifier LLM
        if (isKnownFailureResponse(state.getPrimaryResponse())) {
            log.info("[VerifierNode] Detected failure pattern in response → RETRY");
            state.setVerificationResult(GraphState.VerificationResult.RETRY);
            state.setVerificationReason(
                "Primary LLM returned no data - retrying with different approach");
            return state;
        }

        // ── Guard: very short responses → RETRY not REJECTED ──
        if (state.getPrimaryResponse().length() < 100) {
            log.info("[VerifierNode] Very short response ({} chars) → RETRY",
                state.getPrimaryResponse().length());
            state.setVerificationResult(GraphState.VerificationResult.RETRY);
            state.setVerificationReason("Response too short - retrying");
            return state;
        }

        try {
            String prompt   = buildPrompt(state);
            String response = callOllama(prompt);
            parseResponse(response, state);

        } catch (Exception e) {
            log.warn("[VerifierNode] Verification error ({}): {} → auto-approving",
                e.getClass().getSimpleName(), e.getMessage());
            state.setVerificationResult(GraphState.VerificationResult.APPROVED);
            state.setVerificationReason(
                "Verifier error: " + e.getMessage() + " → auto-approved");
        }

        log.info("[VerifierNode] Result={}, Reason={}",
            state.getVerificationResult(),
            state.getVerificationReason());

        return state;
    }

    /**
     * Detect when the primary LLM returned a known "no data" pattern.
     * These should RETRY not REJECT.
     */
    private boolean isKnownFailureResponse(String response) {
        if (response == null) return true;
        String lower = response.toLowerCase();
        return lower.contains("no results found")
            || lower.contains("no data found")
            || lower.contains("no locations found")
            || lower.contains("no history found")
            || lower.contains("could not find")
            || lower.contains("unable to find")
            || lower.contains("0 results")
            || lower.contains("no records")
            || (lower.contains("error") && response.length() < 200);
    }

    private String buildPrompt(GraphState state) {
        return "You are a quality checker for an AI database assistant.\n"
            + "The AI queries a location database and returns results.\n"
            + "Evaluate if the response reasonably answers the question.\n"
            + "Return ONLY valid JSON, no other text.\n\n"
            + "QUESTION: " + state.getUserQuery() + "\n\n"
            + "RESPONSE (first 500 chars):\n"
            + truncate(stripHtml(state.getPrimaryResponse()), 500) + "\n\n"
            + "IMPORTANT RULES:\n"
            + "- Use APPROVED if response contains relevant data/information\n"
            + "- Use RETRY if response seems incomplete but not wrong\n"
            + "- Use REJECTED ONLY if response contains harmful or completely "
            + "  made-up information\n"
            + "- Database 'no results' or empty responses = RETRY not REJECTED\n"
            + "- HTML tables with data = APPROVED\n\n"
            + "Return exactly this JSON:\n"
            + "{\"verdict\":\"APPROVED\",\"confidence\":0.9,"
            + "\"reason\":\"brief reason\"}\n\n"
            + "verdict = APPROVED | RETRY | REJECTED\n"
            + "/nothink";
    }

    private String callOllama(String prompt) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("model",  verifierModel);
        body.put("stream", false);

        Map<String, String> msg = new HashMap<>();
        msg.put("role",    "user");
        msg.put("content", prompt);

        body.put("messages",    new Object[]{msg});
        body.put("temperature", 0.0);
        body.put("num_ctx",     512);

        String jsonBody = mapper.writeValueAsString(body);
        String url      = ollamaBaseUrl + "/api/chat";

        log.debug("[VerifierNode] POST → {}", url);

        Request request = new Request.Builder()
            .url(url)
            .post(RequestBody.create(
                jsonBody,
                MediaType.get("application/json; charset=utf-8")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Ollama HTTP " + response.code());
            }
            String responseBody = response.body().string();
            JsonNode json = mapper.readTree(responseBody);

            if (json.has("message") && json.get("message").has("content")) {
                return json.get("message").get("content").asText();
            }
            if (json.has("response")) {
                return json.get("response").asText();
            }
            throw new IOException("Unknown Ollama response format");
        }
    }

    private void parseResponse(String raw, GraphState state) {
        try {
            String cleaned = cleanResponse(raw);
            JsonNode json  = mapper.readTree(cleaned);

            String verdict = json.has("verdict")
                ? json.get("verdict").asText().toUpperCase().trim()
                : "APPROVED";

            String reason = json.has("reason")
                ? json.get("reason").asText()
                : "Verification complete";

            double confidence = json.has("confidence")
                ? json.get("confidence").asDouble(1.0)
                : 1.0;

            // ── Remap REJECTED → RETRY ─────────────────────────
            // We never hard-reject on first attempt from verifier.
            // Only reject after max retries (handled in router).
            // This prevents valid-but-incomplete answers being killed.
            if ("REJECTED".equals(verdict)) {
                log.info("[VerifierNode] Remapping REJECTED → RETRY " +
                    "(will retry before giving up)");
                verdict = "RETRY";
                reason  = "[Will retry] " + reason;
            }

            // Low confidence → retry
            if (confidence < 0.5 && "APPROVED".equals(verdict)) {
                verdict = "RETRY";
                reason  = "Low confidence (" + confidence + "): " + reason;
            }

            switch (verdict) {
                case "RETRY":
                    state.setVerificationResult(
                        GraphState.VerificationResult.RETRY);
                    break;
                case "APPROVED":
                default:
                    state.setVerificationResult(
                        GraphState.VerificationResult.APPROVED);
                    break;
            }
            state.setVerificationReason(reason);

        } catch (Exception e) {
            log.warn("[VerifierNode] JSON parse failed '{}': {}",
                raw.substring(0, Math.min(100, raw.length())), e.getMessage());
            state.setVerificationResult(GraphState.VerificationResult.APPROVED);
            state.setVerificationReason("Parse failed - auto-approved");
        }
    }

    private String cleanResponse(String text) {
        if (text == null) return "{}";
        text = text.replaceAll("(?s)<think>.*?</think>", "").trim();
        text = text.replaceAll("(?s)```json\\s*", "")
                   .replaceAll("(?s)```\\s*",     "")
                   .trim();
        int start = text.indexOf('{');
        int end   = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return "{}";
    }

    private String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", " ")
                   .replaceAll("\\s+",   " ")
                   .trim();
    }

    private String truncate(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return text.substring(0, max) + "...";
    }
}