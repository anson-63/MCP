package com.ais.graph;

/**
 * Conditional edge that determines the next node based on state.
 * Equivalent to LangGraph's conditional_edges.
 */
@FunctionalInterface
public interface GraphEdge {
    
    /**
     * Evaluate current state and return the name of the next node.
     * @param state Current graph state
     * @return Name of next node to execute (or "END" to terminate)
     */
    String route(GraphState state);
}