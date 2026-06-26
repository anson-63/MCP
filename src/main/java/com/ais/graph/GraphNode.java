package com.ais.graph;

/**
 * Functional interface for all graph nodes.
 * Each node receives state, processes it, and returns updated state.
 * Equivalent to LangGraph's node functions.
 */
@FunctionalInterface
public interface GraphNode {
    
    /**
     * Process the current graph state and return updated state.
     * @param state Current graph state
     * @return Updated graph state
     */
    GraphState process(GraphState state);
}