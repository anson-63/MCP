package com.ais.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Central registry for report types used by the availability/query paths.
 *
 * The table and identifier-column metadata belongs here because it describes
 * database/report semantics, not a particular tool or controller.
 */
public final class ReportTypeRegistry {

    public static final String ALL_REPORTS = "ALL";

    private static final Map<String, ReportType> REPORTS =
            new LinkedHashMap<String, ReportType>();

    static {
        register(new ReportType(
                "BSI",
                "Building Safety Inspection Report",
                "ais.BSI_GENERAL_INFO",
                "BLDG_SAFETY_INSP_REPORT_NO",
                Arrays.asList(
                        "BSIR",
                        "Building Safety",
                        "Bldg Safety",
                        "safety inspection"
                )
        ));

        register(new ReportType(
                "CSR",
                "Condition Survey Report by Term Contractor",
                "ais.CS_PLAN",
                "FILE_PATH_AUTOCAD",
                Arrays.asList(
                        "Condition Survey",
                        "Contractor Survey",
                        "condition report"
                )
        ));

        register(new ReportType(
                "KAI",
                "Key Asset Information Report",
                "ais.KAI_RECORD_PLANS_AND_DRAWINGS",
                "AUTOCAD_PATH",
                Arrays.asList(
                        "Key Asset",
                        "Asset Info",
                        "asset information"
                )
        ));

        register(new ReportType(
                "EMMS",
                "EMMS Report",
                "ais.OLD_EMMS",
                "REPORT_LINK",
                Arrays.asList(
                        "Engineering Maintenance",
                        "maintenance management"
                )
        ));

        register(new ReportType(
                "DSSR",
                "Detailed Survey Summary Report",
                "ais.DSSR_REPORT",
                "REPORT_NO",
                Arrays.asList(
                        "Detailed Survey",
                        "Survey Summary",
                        "summary report"
                )
        ));

        // Slope/report-view types do not participate in availability checks.
        register(new ReportType(
                "BWCS",
                "Boundary & Works Completion Survey",
                "Boundary Survey",
                "Works Completion"
        ));
        register(new ReportType(
                "VMI",
                "Visual Maintenance Inspection",
                "Visual Inspection"
        ));
        register(new ReportType(
                "RMI",
                "Routine Maintenance Inspection",
                "Routine Inspection"
        ));
        register(new ReportType(
                "AMI",
                "Annual Maintenance Inspection",
                "Annual Inspection"
        ));
        register(new ReportType(
                "TMCP",
                "TMCP Form",
                "Form 1",
                "Form 2"
        ));
    }

    private ReportTypeRegistry() {
    }

    private static void register(ReportType report) {
        REPORTS.put(report.shortName, report);
    }

    public static ReportType getByShortName(String shortName) {
        if (shortName == null || shortName.trim().isEmpty()) {
            return null;
        }

        return REPORTS.get(
                shortName.trim().toUpperCase(Locale.ROOT)
        );
    }

    public static List<ReportType> getAll() {
        return Collections.unmodifiableList(
                new ArrayList<ReportType>(REPORTS.values())
        );
    }

    public static List<String> getAllShortNames() {
        return Collections.unmodifiableList(
                new ArrayList<String>(REPORTS.keySet())
        );
    }

    /**
     * Returns only report types that can be checked in the availability SQL
     * path. Slope-only display types intentionally are not returned.
     */
    public static List<String> getAvailabilityShortNames() {
        List<String> result = new ArrayList<String>();

        for (ReportType report : REPORTS.values()) {
            if (report.hasAvailabilityTable()) {
                result.add(report.shortName);
            }
        }

        return Collections.unmodifiableList(result);
    }

    /**
     * Values accepted by the report-query interface. ALL_REPORTS is a
     * virtual aggregate and is expanded through this registry; it is not a
     * database table.
     */
    public static List<String> getAvailabilityQueryValues() {
        List<String> result = new ArrayList<String>(
                getAvailabilityShortNames()
        );
        result.add(ALL_REPORTS);
        return Collections.unmodifiableList(result);
    }

    /**
     * Normalizes one report name, a comma-separated report list, or the
     * virtual ALL token to registered availability report types.
     */
    public static List<String> normalizeReportTypes(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> types = new LinkedHashSet<String>();
        String input = raw.trim();

        if (ALL_REPORTS.equalsIgnoreCase(input)) {
            types.addAll(getAvailabilityShortNames());
        } else {
            for (String token : input.split(",")) {
                String shortName = token.trim()
                        .toUpperCase(Locale.ROOT);

                if (shortName.isEmpty()) {
                    continue;
                }

                ReportType report = getByShortName(shortName);
                if (report == null || !report.hasAvailabilityTable()) {
                    throw new IllegalArgumentException(
                            "Unsupported availability report type: "
                                    + shortName
                    );
                }

                types.add(shortName);
            }
        }

        return new ArrayList<String>(types);
    }

    public static List<ReportType> getAvailabilityReports() {
        List<ReportType> result = new ArrayList<ReportType>();

        for (ReportType report : REPORTS.values()) {
            if (report.hasAvailabilityTable()) {
                result.add(report);
            }
        }

        return Collections.unmodifiableList(result);
    }

    public static List<String> detectReportTypes(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String lower = text.toLowerCase(Locale.ROOT);
        Set<String> found = new LinkedHashSet<String>();

        for (ReportType report : REPORTS.values()) {
            boolean matched = Pattern.compile(
                    "\\b" + Pattern.quote(report.shortName) + "\\b",
                    Pattern.CASE_INSENSITIVE
            ).matcher(text).find();

            if (!matched && report.fullName != null) {
                matched = lower.contains(
                        report.fullName.toLowerCase(Locale.ROOT)
                );
            }

            if (!matched) {
                for (String alias : report.aliases) {
                    if (lower.contains(alias.toLowerCase(Locale.ROOT))) {
                        matched = true;
                        break;
                    }
                }
            }

            if (matched) {
                found.add(report.shortName);
            }
        }

        return new ArrayList<String>(found);
    }

    public static boolean isReportRelated(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }

        if (!detectReportTypes(text).isEmpty()) {
            return true;
        }

        String lower = text.toLowerCase(Locale.ROOT);
        String[] genericTerms = {
                "report",
                "available",
                "has report",
                "have report",
                "without report"
        };

        for (String term : genericTerms) {
            if (lower.contains(term)) {
                return true;
            }
        }

        return false;
    }

    public static final class ReportType {

        public final String shortName;
        public final String fullName;
        public final String tableName;
        public final String idColumn;
        public final List<String> aliases;

        public ReportType(
                String shortName,
                String fullName,
                String tableName,
                String idColumn,
                List<String> aliases) {

            if (shortName == null || shortName.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "shortName must not be empty"
                );
            }

            this.shortName = shortName.trim().toUpperCase(Locale.ROOT);
            this.fullName = fullName;
            this.tableName = tableName;
            this.idColumn = idColumn;

            List<String> copiedAliases = new ArrayList<String>();
            if (aliases != null) {
                copiedAliases.addAll(aliases);
            }
            this.aliases = Collections.unmodifiableList(copiedAliases);
        }

        public ReportType(
                String shortName,
                String fullName,
                String... aliases) {
            this(
                    shortName,
                    fullName,
                    null,
                    null,
                    aliases == null
                            ? Collections.<String>emptyList()
                            : Arrays.asList(aliases)
            );
        }

        public String getTableName() {
            return tableName;
        }

        public String getIdColumn() {
            return idColumn;
        }

        public boolean hasAvailabilityTable() {
            return hasText(tableName) && hasText(idColumn);
        }

        private static boolean hasText(String value) {
            return value != null && !value.trim().isEmpty();
        }
    }
}
