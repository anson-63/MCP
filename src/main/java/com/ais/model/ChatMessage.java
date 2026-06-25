package com.ais.model;


public class ChatMessage {
    private String role;
    private String content;
    private String toolName;

    public ChatMessage(String role, String content, String toolName) {
        this.role = role;
        this.content = content;
        this.toolName = toolName;
    }
    
    public ChatMessage(String role, String content) {
        this(role, content, null);
    }

    public String getRole()           { return role; }
    public void setRole(String v)     { this.role = v; }
    
    public String getContent()        { return content; }
    public void setContent(String v)  { this.content = v; }
    
    public String getToolName()       { return toolName; }
    public void setToolName(String v) { this.toolName = v; }
}