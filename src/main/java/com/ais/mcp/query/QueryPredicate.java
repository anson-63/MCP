package com.ais.mcp.query;

import java.util.*;

public final class QueryPredicate {
    private final String sqlFragment;
    private final List<Object> parameters;

    public QueryPredicate(String sqlFragment, List<Object> parameters) {
        this.sqlFragment = sqlFragment;
        this.parameters = (parameters == null) ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(parameters));
    }

    public String getSqlFragment() { return sqlFragment; }
    public List<Object> getParameters() { return parameters; }
}