package com.dataintegration.console.datasource;

import java.time.Duration;

/**
 * A row count for validation (docs/design.md §10.3).
 *
 * @param sql for JDBC: a query whose first column of the first row is the count
 * @param index for Elasticsearch: the index (or pattern) to count
 * @param esQuery for Elasticsearch: optional query DSL, the value of {@code "query"} in _count
 */
public record CountRequest(String sql, String index, String esQuery, Duration timeout) {

    public static CountRequest sql(String sql, Duration timeout) {
        return new CountRequest(sql, null, null, timeout);
    }

    public static CountRequest elasticsearch(String index, String esQuery, Duration timeout) {
        return new CountRequest(null, index, esQuery, timeout);
    }
}
