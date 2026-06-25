package com.ais.service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central registry for all report types.
 * Stores short name, full name, and search aliases for each.
 */
public class ReportTypeRegistry {

    public static class ReportType {
        public final String shortName;     // "BSI"
        public final String fullName;      // "Building Safety Inspection"
        public final List<String> aliases; // ["BSIR", "bldg safety", ...]

        public ReportType(String shortName, String fullName, String... aliases) {
            this.shortName = shortName;
            this.fullName  = fullName;
            this.aliases   = new ArrayList<String>(Arrays.asList(aliases));
        }
    }

    // ── Master registry ──────────────────────────────────────────────
    private static final List<ReportType> REPORTS = new ArrayList<ReportType>();
    
    static {
        REPORTS.add(new ReportType(
            "BSI", "Building Safety Inspection Report",
            "BSIR", "Building Safety", "Bldg Safety", "safety inspection"
        ));
        REPORTS.add(new ReportType(
            "CSR", "Condition Survey Report by Term Contractor",
            "Condition Survey", "Contractor Survey", "condition report"
        ));
        REPORTS.add(new ReportType(
            "KAI", "Key Asset Information Report",
            "Key Asset", "Asset Info", "asset information"
        ));
        REPORTS.add(new ReportType(
            "EMMS", "EMMS Report",
            "Engineering Maintenance", "maintenance management"
        ));
        REPORTS.add(new ReportType(
            "DSSR", "Detailed Survey Summary Report",
            "Detailed Survey", "Survey Summary", "summary report"
        ));
    }
    
    static {
        // Standard reports
        REPORTS.add(new ReportType("BSI", "Building Safety Inspection",
            "BSIR", "Building Safety", "safety inspection"));
        REPORTS.add(new ReportType("CSR", "Condition Survey by Contractor",
            "Condition Survey", "Contractor Survey"));
        REPORTS.add(new ReportType("KAI", "Key Asset Information",
            "Key Asset", "Asset Info"));
        REPORTS.add(new ReportType("EMMS", "EMMS",
            "Engineering Maintenance"));
        REPORTS.add(new ReportType("DSSR", "Detailed Survey Summary",
            "Detailed Survey", "Survey Summary"));
        
        // ── Slope-specific reports ──────────────────────────────────
        REPORTS.add(new ReportType("BWCS", "Boundary & Works Completion Survey",
            "Boundary Survey", "Works Completion"));
        REPORTS.add(new ReportType("VMI", "Visual Maintenance Inspection",
            "Visual Inspection"));
        REPORTS.add(new ReportType("RMI", "Routine Maintenance Inspection",
            "Routine Inspection"));
        REPORTS.add(new ReportType("AMI", "Annual Maintenance Inspection",
            "Annual Inspection"));
        REPORTS.add(new ReportType("TMCP", "TMCP Form",
            "TMCP", "Form 1", "Form 2"));
    }

    public static List<ReportType> getAll() {
        return REPORTS;
    }

    public static ReportType getByShortName(String shortName) {
        if (shortName == null) return null;
        for (ReportType r : REPORTS) {
            if (r.shortName.equalsIgnoreCase(shortName)) return r;
        }
        return null;
    }

    public static List<String> getAllShortNames() {
        List<String> names = new ArrayList<String>();
        for (ReportType r : REPORTS) names.add(r.shortName);
        return names;
    }

    // ── Detect report types mentioned in text ─────────────────────────
    // Returns short names (e.g., ["BSI", "KAI"]), preserves order, no duplicates
    public static List<String> detectReportTypes(String text) {
        if (text == null || text.isEmpty()) return new ArrayList<String>();
        
        String lower = text.toLowerCase();
        LinkedHashSet<String> found = new LinkedHashSet<String>();
        
        for (ReportType r : REPORTS) {
            // Match short name as whole word: BSI, KAI, etc.
            // (?<![A-Z]) and (?![A-Z]) prevent partial matches like "BSIR" matching "BSI"
            Pattern shortPattern = Pattern.compile(
                "\\b" + Pattern.quote(r.shortName) + "\\b",
                Pattern.CASE_INSENSITIVE
            );
            if (shortPattern.matcher(text).find()) {
                found.add(r.shortName);
                continue;  // already matched
            }
            
            // Match full name
            if (lower.contains(r.fullName.toLowerCase())) {
                found.add(r.shortName);
                continue;
            }
            
            // Match any alias
            for (String alias : r.aliases) {
                if (lower.contains(alias.toLowerCase())) {
                    found.add(r.shortName);
                    break;
                }
            }
        }
        
        return new ArrayList<String>(found);
    }

    // ── Check if text mentions ANY report-related keyword ─────────────
    // Used to decide whether to clear or keep conversation memory
    public static boolean isReportRelated(String text) {
        if (text == null || text.isEmpty()) return false;
        
        // Any report type mentioned?
        if (!detectReportTypes(text).isEmpty()) return true;
        
        // Generic "report" keywords (so "which has reports" works)
        String lower = text.toLowerCase();
        String[] generalKeywords = {
            "report", "which", "which one", "available",
            "show me", "filter", "has", "have"
        };
        for (String kw : generalKeywords) {
            if (lower.contains(kw)) return true;
        }
        
        return false;
    }
}