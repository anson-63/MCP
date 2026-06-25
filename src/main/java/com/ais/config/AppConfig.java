package com.ais.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Properties;

/**
 * Centralized configuration loader.
 * Reads application.properties from classpath (WEB-INF/classes/).
 */
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);
    
    private static final Properties props = new Properties();
    private static boolean loaded = false;

    // ── Load once on first access ──────────────────────────────
    private static synchronized void loadIfNeeded() {
        if (loaded) return;

        InputStream is = null;
        try {
            // 1️⃣ Try external file first (production override)
            String externalPath = System.getProperty(
                "app.config", 
                System.getenv("APP_CONFIG_PATH")
            );
            
            if (externalPath != null) {
                java.io.File f = new java.io.File(externalPath);
                if (f.exists()) {
                    is = new java.io.FileInputStream(f);
                    log.info("✅ Loading EXTERNAL config: {}", externalPath);
                }
            }

            // 2️⃣ Fall back to classpath (dev / packaged)
            if (is == null) {
                is = AppConfig.class.getClassLoader()
                    .getResourceAsStream("application.properties");
                log.info("✅ Loading classpath config");
            }

            if (is == null) {
                log.error("❌ No config found!");
                loaded = true;
                return;
            }
            
            props.load(is);
            loaded = true;
            log.info("Loaded {} properties", props.size());

        } catch (Exception e) {
            log.error("Config load error: {}", e.getMessage());
            loaded = true;
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignored) {}
        }
    }

    // ── Generic getters ────────────────────────────────────────
    public static String get(String key) {
        loadIfNeeded();
        return props.getProperty(key);
    }

    public static String get(String key, String defaultValue) {
        loadIfNeeded();
        return props.getProperty(key, defaultValue);
    }

    public static int getInt(String key, int defaultValue) {
        loadIfNeeded();
        String val = props.getProperty(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid int for {}: {}, using default {}", 
                     key, val, defaultValue);
            return defaultValue;
        }
    }

    public static double getDouble(String key, double defaultValue) {
        loadIfNeeded();
        String val = props.getProperty(key);
        if (val == null) return defaultValue;
        try {
            return Double.parseDouble(val.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        loadIfNeeded();
        String val = props.getProperty(key);
        if (val == null) return defaultValue;
        return Boolean.parseBoolean(val.trim());
    }

    // ── Convenient typed accessors ─────────────────────────────
    public static String dbUser()     { return get("db.user", ""); }
    public static String dbPassword() { return get("db.password", ""); }
    public static String dbServer()   { return get("db.server", ""); }
    public static String dbName()     { return get("db.name", ""); }
    public static String GISdbName()  { return get("gis_db.name", ""); }
    
    public static int    dbPoolMaxSize()        { return getInt("db.pool.max_size", 10); }
    public static int    dbPoolMinIdle()        { return getInt("db.pool.min_idle", 2); }
    public static int    dbConnectionTimeout()  { return getInt("db.connection_timeout", 30000); }
    
    public static String ollamaBaseUrl()        { return get("ollama.base_url", "http://localhost:11434"); } //hardcode default
    public static String ollamaModel()          { return get("ollama.model", "qwen3:4b-q4_K_M"); } //hardcode default
    public static int    ollamaNumCtx()         { return getInt("ollama.num_ctx", 2048); } //hardcode default
    public static double ollamaTemperature()    { return getDouble("ollama.temperature", 0.0); } //hardcode default
    public static int    ollamaTimeoutSeconds() { return getInt("ollama.timeout_seconds", 120); } //hardcode default
    
    public static String reportAisBase()        { return get("report.url.ais_base", "domain"); } //hardcode default
    public static String reportDssrBase()       { return get("report.url.dssr_base", "http://domain/asdiis/sebiis/2k/application/dssr/reportmain.aspx"); } //hardcode default
}