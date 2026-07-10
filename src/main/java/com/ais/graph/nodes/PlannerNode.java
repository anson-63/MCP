package com.ais.graph.nodes;

import com.ais.graph.GraphNode;
import com.ais.graph.GraphState;
import com.ais.service.OllamaService;
import com.ais.service.QueryPlanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class PlannerNode implements GraphNode {

    private static final Logger log = LoggerFactory.getLogger(PlannerNode.class);

    private final OllamaService ollamaService;
    private final QueryPlanner queryPlanner;

    public PlannerNode(QueryPlanner queryPlanner) {
        this.queryPlanner = queryPlanner;
        this.ollamaService = null;
    }

    public PlannerNode(OllamaService ollamaService, QueryPlanner queryPlanner) {
        this.ollamaService = ollamaService;
        this.queryPlanner = queryPlanner;
    }

    @Override
    public GraphState process(GraphState state) {
        log.info("[Manual Info][PlannerNode] Planning for: {}", state.getUserQuery());

        try {
            // Simple intent detection from query text.
            // The real planning happens inside OllamaService.invoke()
            // which calls QueryPlanner internally.
            String intent = detectIntent(state.getUserQuery());
            state.setDetectedIntent(intent);
            state.setPlannedTools(new ArrayList<>());

            log.info("[Manual Info][PlannerNode] Detected intent: {}", intent);
        } catch (Exception e) {
            log.warn("[PlannerNode] Planning error: {}", e.getMessage());
            state.setDetectedIntent("GENERAL");
            state.setPlannedTools(new ArrayList<>());
        }

        return state;
    }

    private String detectIntent(String query) {
        if (query == null) {
            return "UNKNOWN";
        }
        String q = query.toUpperCase();

        // Check for location code pattern (e.g. SB04400361000)
        if (query.matches(".*\\b[A-Z]{2}\\d{5,}\\d*\\b.*")) {
            if (q.contains("BSI") || q.contains("KAI")
                    || q.contains("DSSR") || q.contains("EMMS")
                    || q.contains("CSR")) {
                return "REPORT";
            }
            if (q.contains("HISTORY") || q.contains("OLD")
                    || q.contains("NEW CODE") || q.contains("CURRENT CODE")
                    || q.contains("FORMER")) {
                return "CODE_HISTORY";
            }
            return "LOCATION_CODE";
        }

        // PSM query
        if (q.contains("PSM/") || q.contains("PSM ")) {
            return "PSM";
        }

        // Historic / monument
        if (q.contains("MONUMENT")) {
            return "MONUMENT";
        }
        if (q.contains("HISTORIC") || q.contains("GRADE")) {
            return "HISTORIC";
        }

        // Department
        if (q.contains("LCSD") || q.contains("AFCD")
                || q.contains("HD") || q.contains("DSD")) {
            return "DEPARTMENT";
        }

        // SQL/aggregate queries
        if (q.contains("HOW MANY") || q.contains("WHICH")
                || q.contains("ALL REPORTS") || q.contains("ALL 5")) {
            return "SQL_QUERY";
        }

        // Name search
        if (q.contains("SEARCH") || q.contains("FIND")
                || q.contains("LOOK UP")) {
            return "NAME_SEARCH";
        }

        return "GENERAL";
    }
}
