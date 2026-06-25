package com.ais.model;

import lombok.Data;
import java.util.Map;

@Data
public class MCPTool {
    private String name;
    private String description;
    private Map<String, Object> inputSchema;
}