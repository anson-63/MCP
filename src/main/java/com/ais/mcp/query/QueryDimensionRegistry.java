package com.ais.mcp.query;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Registry of database query dimensions.
 *
 * A dimension is a trusted database predicate builder. It is deliberately
 * independent of tool names: several tools and the composed location_query
 * can use the same dimension.
 */
public final class QueryDimensionRegistry {

    private final Map<String, QueryDimension> dimensions =
            new LinkedHashMap<String, QueryDimension>();

    public void register(QueryDimension dimension) {
        if (dimension == null) {
            throw new IllegalArgumentException(
                    "dimension must not be null"
            );
        }

        dimensions.put(normalize(dimension.getName()), dimension);
    }

    /**
     * Applies a registered dimension. Unknown keys are ignored so optional
     * planner metadata (for example a modifier) cannot become SQL text.
     * The return value tells callers whether a dimension was found.
     */
    public boolean apply(LocationQuerySpec spec,String name,String value) {

        if (spec == null || name == null || name.trim().isEmpty()) {
            return false;
        }

        QueryDimension dimension = dimensions.get(normalize(name));
        if (dimension == null) {
            return false;
        }

        dimension.apply(spec, value == null ? "" : value);
        return true;
    }

    public boolean contains(String name) {
        return name != null && dimensions.containsKey(normalize(name));
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Java-8 lambda-compatible concrete dimension type.
     */
    public static final class QueryDimension {

        private final String name;
        private final BiConsumer<LocationQuerySpec, String> applier;

        public QueryDimension(String name,BiConsumer<LocationQuerySpec, String> applier) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("dimension name must not be empty");
            }
            if (applier == null) {
                throw new IllegalArgumentException("dimension applier must not be null");
            }

            this.name = name.trim();
            this.applier = applier;
        }

        public String getName() {
            return name;
        }

        public void apply(LocationQuerySpec spec, String value) {
            applier.accept(spec, value);
        }
    }
}
