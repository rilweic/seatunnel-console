package com.dataintegration.console.server.job;

import java.util.List;

/**
 * Row-count validation of a job version (docs/design.md §10.3), stored as job_version.validation_json.
 * SQL, the ES index and the ES query may use variables, rendered like the job config.
 *
 * <p>Script jobs set one {@code source}/{@code target} pair. Wizard jobs set {@code tables}, one
 * check per synced table (§7.6); when present, {@code source} and {@code target} are ignored.
 *
 * @param failOnMismatch a MISMATCH fails the run; otherwise it is only reported
 */
public record ValidationSpec(boolean enabled, Side source, Side target, boolean failOnMismatch, List<TableCheck> tables) {

    public ValidationSpec(boolean enabled, Side source, Side target, boolean failOnMismatch) {
        this(enabled, source, target, failOnMismatch, null);
    }

    public boolean perTable() {
        return tables != null && !tables.isEmpty();
    }

    /** The count pair for one synced table, named after it in results. */
    public record TableCheck(String name, Side source, Side target) {}

    public enum Mode {
        /** Source only: derived from the job's single Jdbc source (query, or table_path + where_condition). */
        AUTO,
        /** A query on a JDBC datasource whose first column of the first row is the count. */
        SQL,
        /** Elasticsearch _count on an index, optionally filtered by a query DSL. */
        ES
    }

    /** @param query ES query DSL, the value of "query" in a _count request */
    public record Side(Mode mode, String datasourceId, String sql, String index, String query) {}
}
