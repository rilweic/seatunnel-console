package com.dataintegration.console.server.job.wizard;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the job wizard collects (docs/design.md §7.2), stored as job_version.form_json. The HOCON is
 * always generated from it on the server by {@link WizardGenerator}.
 *
 * @param parallelism env.parallelism, 1/2/4/8
 * @param validation generate per-table row-count validation where it can be accurate (§7.6)
 * @param sourceOptions advanced Jdbc source options, written as given
 * @param sinkOptions advanced sink options, written as given
 * @param fieldRule how target field names derive from source field names, for tables without their
 *     own rule; null keeps them
 */
public record WizardForm(
        Source source,
        Target target,
        Sync sync,
        int parallelism,
        boolean validation,
        Map<String, String> sourceOptions,
        Map<String, String> sinkOptions,
        NameRule fieldRule) {

    public WizardForm(
            Source source,
            Target target,
            Sync sync,
            int parallelism,
            boolean validation,
            Map<String, String> sourceOptions,
            Map<String, String> sinkOptions) {
        this(source, target, sync, parallelism, validation, sourceOptions, sinkOptions, null);
    }

    /**
     * @param namespace database (MySQL) or schema (PostgreSQL) holding the tables
     */
    public record Source(String datasourceId, String namespace, List<TableSpec> tables) {}

    /**
     * @param columns columns to sync, in order; null or empty means all
     * @param filter fixed where condition for this table, e.g. {@code status = 1}
     * @param incrementalField overrides {@link Sync#field()} for this table
     * @param targetName target table or index name set by hand; overrides {@link Target#tableRule()}
     * @param fieldNames target field names set by hand, by source field; override the field rule
     * @param fieldRule this table's field rule; null uses {@link WizardForm#fieldRule()}
     */
    public record TableSpec(
            String name,
            List<String> columns,
            String filter,
            String incrementalField,
            String targetName,
            Map<String, String> fieldNames,
            NameRule fieldRule) {

        public TableSpec(String name, List<String> columns, String filter, String incrementalField) {
            this(name, columns, filter, incrementalField, null, null, null);
        }

        public boolean allColumns() {
            return columns == null || columns.isEmpty();
        }
    }

    /**
     * Derives a target name from a source name: replace (plain text, every occurrence), then
     * change case, then add prefix and suffix (docs/design.md §7.8).
     */
    public record NameRule(String find, String replace, NameCase nameCase, String prefix, String suffix) {

        public String apply(String name) {
            String out = name;
            if (find != null && !find.isEmpty()) {
                out = out.replace(find, replace == null ? "" : replace);
            }
            if (nameCase == NameCase.UPPER) {
                out = out.toUpperCase(Locale.ROOT);
            } else if (nameCase == NameCase.LOWER) {
                out = out.toLowerCase(Locale.ROOT);
            }
            return trim(prefix) + out + trim(suffix);
        }

        private static String trim(String s) {
            return s == null ? "" : s.trim();
        }
    }

    public enum NameCase {
        KEEP,
        UPPER,
        LOWER
    }

    /**
     * @param namespace database (MySQL) or schema (PostgreSQL); unused for Elasticsearch
     * @param tableName forms saved before {@code tableRule}: the single table's target name
     * @param prefix forms saved before {@code tableRule}: put before each source table name
     * @param suffix forms saved before {@code tableRule}: put after each source table name
     * @param tableRule how target table names derive from source table names; null falls back to
     *     prefix and suffix
     */
    public record Target(
            String datasourceId,
            String namespace,
            String tableName,
            String prefix,
            String suffix,
            SchemaMode schemaMode,
            DataMode dataMode,
            WriteMode writeMode,
            NameRule tableRule) {

        public Target(
                String datasourceId,
                String namespace,
                String tableName,
                String prefix,
                String suffix,
                SchemaMode schemaMode,
                DataMode dataMode,
                WriteMode writeMode) {
            this(datasourceId, namespace, tableName, prefix, suffix, schemaMode, dataMode, writeMode, null);
        }
    }

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
