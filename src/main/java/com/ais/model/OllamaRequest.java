package com.ais.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class OllamaRequest {
    private String model;
    private List<Map<String, Object>> messages;
    private List<Map<String, Object>> tools;
    private boolean stream = false;

    @JsonProperty("num_ctx")
    private int numCtx = 2048;
    private double temperature = 0.0;
}