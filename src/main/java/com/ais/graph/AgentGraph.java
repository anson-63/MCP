package com.ais.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Core graph execution engine. Equivalent to LangGraph's StateGraph with
 * compile() and invoke().
 */
public class AgentGraph {

    private static final Logger log = LoggerFactory.getLogger(AgentGraph.class);

    private static final String END = "END";
    private static final int MAX_STEPS = 20;

    private final Map<String, GraphNode> nodes = new HashMap<>();
    private final Map<String, String> edges = new HashMap<>();
    private final Map<String, GraphEdge> conditionalEdges = new HashMap<>();

    private String entryPoint;
    private boolean compiled;

    public AgentGraph addNode(String name, GraphNode node) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("node name must not be empty");
        }
        if (node == null) {
            throw new IllegalArgumentException("node must not be null");
        }

        nodes.put(name, node);
        log.debug("Registered node: {}", name);
        return this;
    }

    public AgentGraph addEdge(String from, String to) {
        edges.put(from, to);
        return this;
    }

    public AgentGraph addConditionalEdge(String from, GraphEdge router) {
        conditionalEdges.put(from, router);
        return this;
    }

    public AgentGraph setEntryPoint(String nodeName) {
        entryPoint = nodeName;
        return this;
    }

    public AgentGraph compile() {
        if (entryPoint == null) {
            throw new IllegalStateException("Entry point not set");
        }
        if (!nodes.containsKey(entryPoint)) {
            throw new IllegalStateException("Entry point node not registered: " + entryPoint);
        }

        validateRoutes();
        compiled = true;

        log.info("[Manual Info] Graph compiled. Entry: {}, Nodes: {}", entryPoint, nodes.keySet());
        return this;
    }

    public GraphState invoke(GraphState initialState) {
        if (!compiled) {
            throw new IllegalStateException("Graph not compiled. Call .compile() first.");
        }
        if (initialState == null) {
            throw new IllegalArgumentException("initialState must not be null");
        }

        GraphState state = initialState;
        String currentNodeName = entryPoint;
        int stepCount = 0;

        log.info("[Manual Info] === Graph Execution Start [Session: {}] ===", state.getSessionId());

        while (!END.equals(currentNodeName) && stepCount < MAX_STEPS) {
            stepCount++;
            GraphNode node = nodes.get(currentNodeName);

            if (node == null) {
                log.error("Node not found: {}", currentNodeName);
                state.setErrorMessage("Unknown node: " + currentNodeName);
                break;
            }

            log.info("[Manual Info] → Executing node: {} (step {})", currentNodeName, stepCount);
            state.addExecutionStep(currentNodeName);

            try {
                state = node.process(state);
            } catch (Exception e) {
                log.error("Node {} threw exception: {}", currentNodeName, e.getMessage(), e);
                state.setErrorMessage("Node " + currentNodeName + " failed: " + e.getMessage());
                break;
            }

            String nextNode;
            if (conditionalEdges.containsKey(currentNodeName)) {
                nextNode = conditionalEdges.get(currentNodeName).route(state);
                log.info("[Manual Info] Conditional edge → {}", nextNode);
            } else if (edges.containsKey(currentNodeName)) {
                nextNode = edges.get(currentNodeName);
                log.info("[Manual Info] Edge → {}", nextNode);
            } else {
                nextNode = END;
                log.info("[Manual Info] No outgoing edge → END");
            }

            if (!END.equals(nextNode) && !nodes.containsKey(nextNode)) {
                log.error("Router returned an unregistered node: {}", nextNode);
                state.setErrorMessage("Unregistered graph route: " + nextNode);
                break;
            }

            currentNodeName = nextNode;
        }

        if (stepCount >= MAX_STEPS) {
            log.warn("Graph hit max steps limit ({})", MAX_STEPS);
            state.setErrorMessage("Max steps exceeded");
        }

        log.info("[Manual Info] === Graph Execution Complete [{}ms, {} steps] ===", 
                 state.getElapsedMs(), stepCount);
        log.info("[Manual Info] Execution path: {}", state.getExecutionPath());

        return state;
    }

    private void validateRoutes() {
        for (Map.Entry<String, String> edge : edges.entrySet()) {
            validateRouteTarget(edge.getKey(), edge.getValue());
        }

        for (String from : conditionalEdges.keySet()) {
            if (!nodes.containsKey(from)) {
                throw new IllegalStateException("Conditional edge source not registered: " + from);
            }
        }
    }

    private void validateRouteTarget(String from, String to) {
        if (to == null || END.equals(to)) {
            return;
        }
        if (!nodes.containsKey(to)) {
            throw new IllegalStateException("Edge from " + from + " points to unregistered node: " + to);
        }
    }
}