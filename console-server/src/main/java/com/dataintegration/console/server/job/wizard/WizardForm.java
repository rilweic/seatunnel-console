package com.dataintegration.console.server.job.wizard;

import java.util.List;
import java.util.Map;

/**
 * What the job wizard collects (docs/design.md §7.2), stored as job_version.form_json. The HOCON is
 * always generated from it on the server by {@link WizardGenerator}.
 *
 * @param parallelism env.parallelism, 1/2/4/8
 * @param validation generate per-table row-count validation where it can be accurate (§7.6)
 * @param sourceOptions advanced Jdbc source options, written as given
 * @param sinkOptions advanced sink options, written as given
 */
public record WizardForm(
        Source source,
        Target target,
        Sync sync,
        int parallelism,
        boolean validation,
        Map<String, String> sourceOptions,
        Map<String, String> sinkOptions) {

    /**
     * @param namespace database (MySQL) or schema (PostgreSQL) holding the tables
     */
    public record Source(String datasourceId, String namespace, List<TableSpec> tables) {}

    /**
     * @param columns columns to sync, in order; null or empty means all
     * @param filter fixed where condition for this table, e.g. {@code status = 1}
     * @param incrementalField overrides {@link Sync#field()} for this table
     */
    public record TableSpec(String name, List<String> columns, String filter, String incrementalField) {

        public boolean allColumns() {
            return columns == null || columns.isEmpty();
        }
    }

    /**
     * @param namespace database (MySQL) or schema (PostgreSQL); unused for Elasticsearch
     * @param tableName target table or index name; only used when a single table is synced
     * @param prefix put before each source table name when several tables are synced
     * @param suffix put after each source table name when several tables are synced
     */
    public record Target(
            String datasourceId,
            String namespace,
            String tableName,
            String prefix,
            String suffix,
            SchemaMode schemaMode,
            DataMode dataMode,
            WriteMode writeMode) {}

    /** When the target table or index does not exist. */
    public enum SchemaMode {
        CREATE,
        ERROR
    }

    /** What happens to rows already in the target. */
    public enum DataMode {
        KEEP,
        CLEAR
    }

    public enum WriteMode {
        UPSERT,
        APPEND
    }

    /**
     * @param field incremental field used by every table that does not name its own
     * @param customCondition where condition for {@link Window#CUSTOM}, may use variables
     */
    public record Sync(SyncMode mode, Window window, String field, String customCondition) {}

    public enum SyncMode {
        FULL,
        INCREMENTAL
    }

    /** Time window of an incremental run, relative to its scheduled time (§7.5). */
    public enum Window {
        DAY,
        HOUR,
        CUSTOM
    }
}
