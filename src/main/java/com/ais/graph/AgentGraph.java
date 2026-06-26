package com.ais.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Core graph execution engine.
 * Equivalent to LangGraph's StateGraph with .compile() and .invoke().
 *
 * Usage:
 *   AgentGraph graph = new AgentGraph()
 *       .addNode("planner",  plannerNode)
 *       .addNode("llm",      primaryLlmNode)
 *       .addNode("verifier", verifierNode)
 *       .addNode("format",   formatterNode)
 *       .addEdge("planner",  "llm")               // unconditional
 *       .addConditionalEdge("verifier",            // conditional
 *           state -> state.getVerificationResult() == APPROVED ? "format" : "llm")
 *       .setEntryPoint("planner")
 *       .compile();
 *
 *   GraphState result = graph.invoke(initialState);
 */
public class AgentGraph {

    private static final Logger log = LoggerFactory.getLogger(AgentGraph.class);
    private static final String END = "END";
    private static final int MAX_STEPS = 20; // Circuit breaker

    // Node registry
    private final Map<String, GraphNode> nodes = new HashMap<>();

    // Edge registry: nodeName -> next node name (unconditional)
    private final Map<String, String> edges = new HashMap<>();

    // Conditional edges: nodeName -> routing function
    private final Map<String, GraphEdge> conditionalEdges = new HashMap<>();

    private String entryPoint;
    private boolean compiled = false;

    // ── Builder Methods ───────────────────────────────────────

    public AgentGraph addNode(String name, GraphNode node) {
        nodes.put(name, node);
        log.debug("Registered node: {}", name);
        return this;
    }

    /** Unconditional edge: always go from 'from' to 'to' */
    public AgentGraph addEdge(String from, String to) {
        edges.put(from, to);
        return this;
    }

    /** Conditional edge: routing function decides next node */
    public AgentGraph addConditionalEdge(String from, GraphEdge router) {
        conditionalEdges.put(from, router);
        return this;
    }

    public AgentGraph setEntryPoint(String nodeName) {
        this.entryPoint = nodeName;
        return this;
    }

    public AgentGraph compile() {
        if (entryPoint == null) {
            throw new IllegalStateException("Entry point not set");
        }
        if (!nodes.containsKey(entryPoint)) {
            throw new IllegalStateException(
                "Entry point node not registered: " + entryPoint);
        }
        this.compiled = true;
        log.info("Graph compiled. Entry: {}, Nodes: {}", 
            entryPoint, nodes.keySet());
        return this;
    }

    // ── Execution ─────────────────────────────────────────────

    /**
     * Execute the graph with the given initial state.
     * Equivalent to LangGraph's graph.invoke(state)
     */
    public GraphState invoke(GraphState initialState) {
        if (!compiled) {
            throw new IllegalStateException("Graph not compiled. Call .compile() first.");
        }

        GraphState state = initialState;
        String currentNodeName = entryPoint;
        int stepCount = 0;

        log.info("=== Graph Execution Start [Session: {}] ===", 
            state.getSessionId());

        while (!END.equals(currentNodeName) && stepCount < MAX_STEPS) {
            stepCount++;

            // ── Execute Current Node ───────────────────────────
            GraphNode node = nodes.get(currentNodeName);
            if (node == null) {
                log.error("Node not found: {}", currentNodeName);
                state.setErrorMessage("Unknown node: " + currentNodeName);
                break;
            }

            log.info("→ Executing node: {} (step {})", currentNodeName, stepCount);
            state.addExecutionStep(currentNodeName);

            try {
                state = node.process(state);
            } catch (Exception e) {
                log.error("Node {} threw exception: {}", currentNodeName, e.getMessage(), e);
                state.setErrorMessage("Node " + currentNodeName + " failed: " + e.getMessage());
                break;
            }

            // ── Determine Next Node ────────────────────────────
            if (conditionalEdges.containsKey(currentNodeName)) {
                // Conditional routing
                String nextNode = conditionalEdges.get(currentNodeName).route(state);
                log.info("  Conditional edge → {}", nextNode);
                currentNodeName = nextNode;

            } else if (edges.containsKey(currentNodeName)) {
                // Unconditional routing
                currentNodeName = edges.get(currentNodeName);
                log.info("  Edge → {}", currentNodeName);

            } else {
                // No outgoing edge = implicit END
                log.info("  No outgoing edge → END");
                currentNodeName = END;
            }
        }

        if (stepCount >= MAX_STEPS) {
            log.warn("Graph hit max steps limit ({})", MAX_STEPS);
            state.setErrorMessage("Max steps exceeded");
        }

        log.info("=== Graph Execution Complete [{}ms, {} steps] ===",
            state.getElapsedMs(), stepCount);
        log.info("Execution path: {}", state.getExecutionPath());

        return state;
    }
}