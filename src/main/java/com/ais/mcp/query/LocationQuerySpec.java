package com.ais.mcp.query;

import java.util.ArrayList;
import java.util.List;

public final class LocationQuerySpec {

    private final List<QueryPredicate> predicates = new ArrayList<QueryPredicate>();

    private Integer limit;
    private String excludeUndefinedField;

    public void addPredicate(QueryPredicate predicate) {
        if (predicate != null) {
            predicates.add(predicate);
        }
    }

    public List<QueryPredicate> getPredicates() {
        return predicates;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public String getExcludeUndefinedField() {
        return excludeUndefinedField;
    }

    public void setExcludeUndefinedField(String excludeUndefinedField) {
        this.excludeUndefinedField = excludeUndefinedField;
    }
}