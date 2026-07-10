package com.ais.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class OpenAiRequest {

    @JsonProperty("model")
    private String model;

    @JsonProperty("messages")
    private List<Message> messages;

    @JsonProperty("temperature")
    private double temperature;

    @JsonProperty("max_tokens")
    private int maxTokens;

    @JsonProperty("stream")
    private boolean stream = false;

    // ── Constructors ──────────────────────────────────────────────
    public OpenAiRequest(String model,
                         List<Message> messages,
                         double temperature,
                         int maxTokens) {
        this.model       = model;
        this.messages    = messages;
        this.temperature = temperature;
        this.maxTokens   = maxTokens;
        this.stream      = false;
    }

    // ── Getters ───────────────────────────────────────────────────
    public String getModel()              { return model; }
    public List<Message> getMessages()    { return messages; }
    public double getTemperature()        { return temperature; }
    public int getMaxTokens()             { return maxTokens; }
    public boolean isStream()             { return stream; }

    // ── Inner Message class ───────────────────────────────────────
    public static class Message {
        @JsonProperty("role")
        private String role;

        @JsonProperty("content")
        private String content;

        public Message(String role, String content) {
            this.role    = role;
            this.content = content;
        }

        public String getRole()    { return role; }
        public String getContent() { return content; }
    }
}