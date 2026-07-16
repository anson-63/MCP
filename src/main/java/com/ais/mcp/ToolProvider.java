package com.ais.mcp;

import java.util.Map;

import com.ais.service.ToolDefinition;

public interface ToolProvider {

    ToolDefinition getDefinition();

    Object execute(Map<String, Object> args);
}