package com.dataintegration.console.server.job.wizard;

import com.fasterxml.jackson.annotation.JsonAlias;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the job wizard collects (docs/design.md §7.2), stored as job_version.form_json. The HOCON is
 * always generated from it on the server by {@link WizardGenerator}.
 *
 * @param parallelism env.parallelism, 1/2/4/8
 * @param validation generate per-table row-count validation where it can be accurate (§7.6)
 * @param sourceOptions advanced options of the source connector, as far as it accepts them
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
     * @param namespace database (MySQL) or schema (PostgreSQL) holding the tables; null where the
     *     source type has none
     * @param spec the source type's own settings, read by its {@link WizardSource} (e.g. the HTTP
     *     request, §7.13); null for sources that need nothing beyond tables. Forms saved before
     *     §7.14 kept the HTTP settings under {@code http}, read here as well.
     */
    public record Source(String datasourceId, String namespace, List<TableSpec> tables, @JsonAlias("http") JsonNode spec) {

        private static final ObjectMapper JSON = JsonMapper.builder().build();

        public Source(String datasourceId, String namespace, List<TableSpec> tables) {
            this(datasourceId, namespace, tables, null);
        }

        /** A source whose spec is the given settings object, e.g. an {@code HttpSpec}. */
        public static Source withSpec(String datasourceId, String namespace, List<TableSpec> tables, Object spec) {
            return new Source(datasourceId, namespace, tables, spec == null ? null : JSON.valueToTree(spec));
        }

        /** The spec as the source type's settings record, or null when there is none. */
        public <T> T spec(Class<T> type) {
            return spec == null || spec.isNull() ? null : JSON.treeToValue(spec, type);
        }
    }

    /**
     * @param columns columns to sync, in order; null or empty means all
     * @param filter fixed where condition for this table, e.g. {@code status = 1}
     * @param incrementalField overrides {@link Sync#field()} for this table
     * @param targetName target table or index name set by hand; overrides {@link Target#tableRule()}
     * @param fieldNames target field names set by hand, by source field; override the field rule
     * @param fieldRule this table's field rule; null uses {@link WizardForm#fieldRule()}
     * @param processes value processing of synced fields, by source field (§7.10)
     * @param extracts new fields taken out of a synced field: JSON path, regex groups or split parts
     * @param addedFields new fields: constants, variables or values computed from other fields
     * @param encryptKeyId the console key {@link FieldProcess#encrypt()} fields are encrypted with
     */
    public record TableSpec(
            String name,
            List<String> columns,
            String filter,
            String incrementalField,
            String targetName,
            Map<String, String> fieldNames,
            NameRule fieldRule,
            Map<String, FieldProcess> processes,
            List<Extract> extracts,
            List<AddedField> addedFields,
            String encryptKeyId) {

        public TableSpec(String name, List<String> columns, String filter, String incrementalField) {
            this(name, columns, filter, incrementalField, null, null, null);
        }

        public TableSpec(
                String name,
                List<String> columns,
                String filter,
                String incrementalField,
                String targetName,
                Map<String, String> fieldNames,
                NameRule fieldRule) {
            this(name, columns, filter, incrementalField, targetName, fieldNames, fieldRule, null, null, null, null);
        }

        public boolean allColumns() {
            return columns == null || columns.isEmpty();
        }

        /** Whether the table's values are processed at all, i.e. it needs transforms besides renames. */
        public boolean processed() {
            return (processes != null && !processes.isEmpty())
                    || (extracts != null && !extracts.isEmpty())
                    || (addedFields != null && !addedFields.isEmpty());
        }
    }

    /** Types a processed or new field can be given. */
    public enum FieldType {
        STRING,
        BIGINT,
        DECIMAL,
        DATE,
        TIMESTAMP,
        BOOLEAN
    }

    /**
     * What happens to one synced field's values, in this order: replace, mask, convert type; then
     * encrypt (after the other processing, with the table's key).
     */
    public record FieldProcess(Replace replace, Mask mask, Cast cast, Boolean encrypt) {

        public boolean encrypted() {
            return Boolean.TRUE.equals(encrypt);
        }
    }

    /** @param regex find is a Java regex and replacement may use $1 */
    public record Replace(String find, String replacement, boolean regex) {}

    /**
     * Keeps the first and last characters and masks the rest; a value too short to mask is kept.
     *
     * @param keepFirst for CUSTOM; the presets fix their own
     */
    public record Mask(MaskPreset preset, Integer keepFirst, Integer keepLast, String maskChar) {}

    public enum MaskPreset {
        PHONE(3, 4),
        ID_CARD(6, 4),
        EMAIL(1, 0),
        NAME(1, 0),
        BANK_CARD(0, 4),
        CUSTOM(0, 0);

        public final int keepFirst;
        public final int keepLast;

        MaskPreset(int keepFirst, int keepLast) {
            this.keepFirst = keepFirst;
            this.keepLast = keepLast;
        }
    }

    /** A value that does not convert becomes null (TRY_CAST). Precision and scale are for DECIMAL. */
    public record Cast(FieldType type, Integer precision, Integer scale) {}

    /**
     * New fields taken out of a synced field; a value that cannot be extracted becomes null.
     *
     * @param pattern the regex (REGEX: one capturing group per output) or the separator (SPLIT)
     * @param dropSource leave the source field out of the target
     */
    public record Extract(String source, ExtractKind kind, String pattern, List<ExtractOutput> outputs, boolean dropSource) {}

    public enum ExtractKind {
        JSON,
        REGEX,
        SPLIT
    }

    /** @param path the JSON path, e.g. {@code $.address.city}; JSON only */
    public record ExtractOutput(String name, String path, FieldType type, Integer precision, Integer scale) {}

    /**
     * A new field (§7.10). What it reads depends on {@code kind}:
     *
     * <ul>
     *   <li>CONSTANT: {@code value}, which may hold variables; {@code format} parses it for DATE/TIMESTAMP
     *   <li>CONCAT: {@code fields}, joined with {@code value} as separator (null fields are skipped)
     *   <li>SUBSTRING: {@code fields[0]}; {@code op} LEFT / RIGHT take {@code length} characters, MID
     *       takes {@code length} from {@code start} (1-based)
     *   <li>CASE: {@code fields[0]} through {@code op} UPPER / LOWER / TRIM
     *   <li>DATE_FORMAT: {@code fields[0]} formatted with {@code format}
     *   <li>ARITHMETIC: {@code fields[0]} {@code op} (+ - * /) {@code fields[1]}, or the number in {@code value}
     *   <li>DEFAULT: {@code fields[0]}, or {@code value} where it is null
     *   <li>UUID, NOW: nothing
     *   <li>EXPRESSION: {@code value} as a SeaTunnel SQL expression, checked only when the job runs
     * </ul>
     *
     * @param type the field's type; for computed kinds optional (the expression's own type)
     */
    public record AddedField(
            String name,
            AddKind kind,
            List<String> fields,
            String value,
            String op,
            Integer start,
            Integer length,
            String format,
            FieldType type,
            Integer precision,
            Integer scale) {}

    public enum AddKind {
        CONSTANT,
        CONCAT,
        SUBSTRING,
        CASE,
        DATE_FORMAT,
        ARITHMETIC,
        DEFAULT,
        UUID,
        NOW,
        EXPRESSION
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

    /**
     * Time window of an incremental run (§7.5): CONTINUOUS carries on from where the last successful
     * run got (the job's watermark); DAY and HOUR are the day or hour before the scheduled time.
     */
    public enum Window {
        CONTINUOUS,
        DAY,
        HOUR,
        CUSTOM
    }
}
