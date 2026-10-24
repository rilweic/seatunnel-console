package com.dataintegration.console.server.job.wizard;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.server.job.ValidationSpec;
import com.dataintegration.console.server.job.ValidationSpec.Mode;
import com.dataintegration.console.server.job.ValidationSpec.Side;
import com.dataintegration.console.server.job.wizard.WizardForm.DataMode;
import com.dataintegration.console.server.job.wizard.WizardForm.NameRule;
import com.dataintegration.console.server.job.wizard.WizardForm.SchemaMode;
import com.dataintegration.console.server.job.wizard.WizardForm.SyncMode;
import com.dataintegration.console.server.job.wizard.WizardForm.TableSpec;
import com.dataintegration.console.server.job.wizard.WizardForm.Window;
import com.dataintegration.console.server.job.wizard.WizardForm.WriteMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns a {@link WizardForm} into a SeaTunnel 3.0 job config and its per-table validation
 * (docs/design.md §7.2, §7.4–7.6). Pure: everything it needs about the datasources comes in
 * through {@link Endpoints}, so the output is fully determined by its inputs.
 *
 * <p>Every table gets a {@code table_path}; a table with column pruning, a filter or an incremental
 * condition also gets its own {@code query} (Jdbc's {@code where_condition} would apply to every
 * table). Target names are worked out here, table by table (§7.8): renamed fields go through a
 * {@code FieldRename} transform and renamed tables through a {@code TableRename} transform, each
 * configured per source table, and the sink addresses tables with SeaTunnel's
 * {@code ${table_name}} placeholder. Fields are not renamed with SQL aliases: the Jdbc source
 * matches query columns to the table's own by name, and a renamed key column would lose its
 * primary key.
 */
public final class WizardGenerator {

    public static final String MYSQL = "MYSQL";
    public static final String POSTGRESQL = "POSTGRESQL";
    public static final String ELASTICSEARCH = "ELASTICSEARCH";

    private static final Pattern RAW_VALUE = Pattern.compile("-?\\d+(\\.\\d+)?|true|false|\\[.*]|\\{.*}", Pattern.DOTALL);

    /**
     * @param sourceDatabase PostgreSQL database of the source datasource (part of table_path); null for MySQL
     * @param targetDatabase PostgreSQL database of the target datasource; null otherwise
     * @param sourceColumns column names of source tables that sync all columns under a field rule,
     *     by table; the rule renames these columns as they are now
     */
    public record Endpoints(
            String sourceType,
            String sourceDatabase,
            String targetType,
            String targetDatabase,
            Map<String, List<String>> sourceColumns) {

        public Endpoints(String sourceType, String sourceDatabase, String targetType, String targetDatabase) {
            this(sourceType, sourceDatabase, targetType, targetDatabase, Map.of());
        }
    }

    /**
     * @param validation per-table checks, or null when none can be accurate or validation is off
     * @param notes why validation is skipped for some tables, for the preview
     */
    public record Generated(String hocon, ValidationSpec validation, List<String> notes) {}

    private WizardGenerator() {}

    public static Generated generate(WizardForm form, Endpoints endpoints) {
        requireShape(form, endpoints);
        boolean es = ELASTICSEARCH.equals(endpoints.targetType());
        StringBuilder hocon = new StringBuilder();
        hocon.append("# 由任务向导生成，请在向导中修改；转为脚本模式后才能直接编辑\n");
        hocon.append("env {\n  job.mode = \"BATCH\"\n  parallelism = ").append(Math.max(1, form.parallelism())).append("\n}\n\n");

        hocon.append("source {\n  Jdbc {\n");
        hocon.append("    datasource_id = ").append(str(form.source().datasourceId())).append('\n');
        hocon.append("    table_list = [\n");
        for (TableSpec table : form.source().tables()) {
            hocon.append("      { table_path = ").append(str(tablePath(form, endpoints, table.name())));
            String query = sourceQuery(form, endpoints, table, false);
            if (query != null) {
                hocon.append(", query = ").append(str(query));
            }
            hocon.append(" }\n");
        }
        hocon.append("    ]\n");
        appendOptions(hocon, form.sourceOptions());
        hocon.append("    plugin_output = \"source\"\n  }\n}\n\n");
        String sinkInput = appendRenames(hocon, form, endpoints, es);

        hocon.append("sink {\n");
        if (es) {
            hocon.append("  Elasticsearch {\n");
            hocon.append("    datasource_id = ").append(str(form.target().datasourceId())).append('\n');
            hocon.append("    index = \"${table_name}\"\n");
            if (form.target().writeMode() == WriteMode.UPSERT) {
                hocon.append("    primary_keys = [\"${primary_key}\"]\n");
            }
        } else {
            hocon.append("  Jdbc {\n");
            hocon.append("    datasource_id = ").append(str(form.target().datasourceId())).append('\n');
            String name = "${table_name}";
            if (POSTGRESQL.equals(endpoints.targetType())) {
                hocon.append("    database = ").append(str(endpoints.targetDatabase())).append('\n');
                hocon.append("    table = ").append(str(form.target().namespace() + "." + name)).append('\n');
            } else {
                hocon.append("    database = ").append(str(form.target().namespace())).append('\n');
                hocon.append("    table = ").append(str(name)).append('\n');
            }
            hocon.append("    generate_sink_sql = true\n");
            hocon.append("    enable_upsert = ").append(form.target().writeMode() == WriteMode.UPSERT).append('\n');
        }
        hocon.append("    schema_save_mode = ").append(str(form.target().schemaMode() == SchemaMode.ERROR
                ? "ERROR_WHEN_SCHEMA_NOT_EXIST" : "CREATE_SCHEMA_WHEN_NOT_EXIST")).append('\n');
        hocon.append("    data_save_mode = ").append(str(form.target().dataMode() == DataMode.CLEAR ? "DROP_DATA" : "APPEND_DATA")).append('\n');
        appendOptions(hocon, form.sinkOptions());
        hocon.append("    plugin_input = [").append(str(sinkInput)).append("]\n  }\n}\n");

        List<String> notes = new ArrayList<>();
        ValidationSpec validation = form.validation() ? validation(form, endpoints, notes) : null;
        return new Generated(hocon.toString(), validation, notes);
    }

    /**
     * Renamed fields, then renamed tables, each configured only for the source tables it changes
     * (the others pass through unchanged). Returns the stream the sink reads.
     */
    private static String appendRenames(StringBuilder hocon, WizardForm form, Endpoints endpoints, boolean es) {
        Map<String, Map<String, String>> fieldRenames = new LinkedHashMap<>();
        Map<String, String> tableRenames = new LinkedHashMap<>();
        for (TableSpec table : form.source().tables()) {
            String path = tablePath(form, endpoints, table.name());
            Map<String, String> fields = new LinkedHashMap<>();
            syncedColumns(form, endpoints, table).forEach(c -> putIfRenamed(fields, c, targetField(form, table, c)));
            if (!fields.isEmpty()) {
                fieldRenames.put(path, fields);
            }
            String target = targetName(form, table, es);
            if (!target.equals(table.name())) {
                tableRenames.put(path, target);
            }
        }
        if (fieldRenames.isEmpty() && tableRenames.isEmpty()) {
            return "source";
        }
        String input = "source";
        hocon.append("transform {\n");
        if (!fieldRenames.isEmpty()) {
            hocon.append("  FieldRename {\n    plugin_input = [\"source\"]\n    plugin_output = \"fields_renamed\"\n");
            hocon.append("    table_transform = [\n");
            fieldRenames.forEach((path, fields) -> {
                hocon.append("      { table_path = ").append(str(path)).append(", specific = [\n");
                fields.forEach((from, to) -> hocon.append("          { field_name = ").append(str(from))
                        .append(", target_name = ").append(str(to)).append(" }\n"));
                hocon.append("        ] }\n");
            });
            hocon.append("    ]\n  }\n");
            input = "fields_renamed";
        }
        if (!tableRenames.isEmpty()) {
            hocon.append("  TableRename {\n    plugin_input = [").append(str(input)).append("]\n    plugin_output = \"tables_renamed\"\n");
            hocon.append("    table_transform = [\n");
            tableRenames.forEach((path, name) -> hocon.append("      { table_path = ").append(str(path))
                    .append(", replacements_with_regex = [{ replace_from = \"^.*$\", replace_to = ").append(str(name)).append(" }] }\n"));
            hocon.append("    ]\n  }\n");
            input = "tables_renamed";
        }
        hocon.append("}\n\n");
        return input;
    }

    /**
     * The table's synced columns as far as they are known: its column list, or for an all-columns
     * table the columns passed in {@link Endpoints#sourceColumns()}, else the fields named by hand.
     */
    static List<String> syncedColumns(WizardForm form, Endpoints endpoints, TableSpec table) {
        if (!table.allColumns()) {
            return table.columns();
        }
        List<String> known = endpoints.sourceColumns() == null ? null : endpoints.sourceColumns().get(table.name());
        if (known != null) {
            return known;
        }
        return table.fieldNames() == null ? List.of() : List.copyOf(table.fieldNames().keySet());
    }

    /** True when the table's field names follow a rule, so its full column list is needed. */
    public static boolean needsColumns(WizardForm form, TableSpec table) {
        return table.allColumns() && (table.fieldRule() != null || form.fieldRule() != null);
    }

    private static void putIfRenamed(Map<String, String> renames, String from, String to) {
        if (!from.equals(to)) {
            renames.put(from, to);
        }
    }

    /**
     * Target table or index name of one source table: set by hand, or derived by the table rule
     * (§7.8). Elasticsearch index names are always lower case.
     */
    public static String targetName(WizardForm form, TableSpec table, boolean es) {
        String name;
        if (notBlank(table.targetName())) {
            name = table.targetName().trim();
        } else if (form.target().tableRule() == null && form.source().tables().size() == 1 && notBlank(form.target().tableName())) {
            name = form.target().tableName().trim(); // forms saved before table rules
        } else {
            name = tableRule(form).apply(table.name());
        }
        return es ? name.toLowerCase(Locale.ROOT) : name;
    }

    /** Target name of one source field: set by hand, or derived by the table's or the job's field rule. */
    public static String targetField(WizardForm form, TableSpec table, String column) {
        String byHand = table.fieldNames() == null ? null : table.fieldNames().get(column);
        if (notBlank(byHand)) {
            return byHand.trim();
        }
        NameRule rule = table.fieldRule() != null ? table.fieldRule() : form.fieldRule();
        return rule == null ? column : rule.apply(column);
    }

    private static NameRule tableRule(WizardForm form) {
        WizardForm.Target t = form.target();
        return t.tableRule() != null
                ? t.tableRule()
                : new NameRule(null, null, WizardForm.NameCase.KEEP, t.prefix(), t.suffix());
    }

    /** Incremental field of a table: its own, or the job-wide one. Null for full sync or a custom window. */
    public static String incrementalField(WizardForm form, TableSpec table) {
        if (form.sync().mode() != SyncMode.INCREMENTAL || form.sync().window() == Window.CUSTOM) {
            return null;
        }
        return notBlank(table.incrementalField()) ? table.incrementalField().trim() : form.sync().field();
    }

    /**
     * The table's select, or null when the plain table_path reads the same rows.
     *
     * @param forCount count(*) instead of the columns, for validation
     */
    static String sourceQuery(WizardForm form, Endpoints endpoints, TableSpec table, boolean forCount) {
        List<String> conditions = new ArrayList<>();
        if (notBlank(table.filter())) {
            conditions.add("(" + table.filter().trim() + ")");
        }
        String incremental = incrementalCondition(form, endpoints.sourceType(), table);
        if (incremental != null) {
            conditions.add("(" + incremental + ")");
        }
        if (!forCount && conditions.isEmpty() && table.allColumns()) {
            return null;
        }
        String select = forCount
                ? "count(*)"
                : table.allColumns()
                        ? "*"
                        : String.join(", ", table.columns().stream().map(c -> quote(endpoints.sourceType(), c)).toList());
        return "select " + select + " from " + qualified(endpoints.sourceType(), form.source().namespace(), table.name())
                + (conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions));
    }

    /** The time window on the table's incremental field (or the custom condition), with variables. */
    static String incrementalCondition(WizardForm form, String dbType, TableSpec table) {
        if (form.sync().mode() != SyncMode.INCREMENTAL) {
            return null;
        }
        if (form.sync().window() == Window.CUSTOM) {
            return form.sync().customCondition().trim();
        }
        String field = quote(dbType, incrementalField(form, table));
        return form.sync().window() == Window.HOUR
                ? field + " >= '$[yyyy-MM-dd HH-1/24]:00:00' and " + field + " < '$[yyyy-MM-dd HH]:00:00'"
                : field + " >= '$[yyyy-MM-dd-1] 00:00:00' and " + field + " < '$[yyyy-MM-dd] 00:00:00'";
    }

    /**
     * Per-table counts where they can be accurate (§7.6): full sync into a cleared target counts the
     * whole target table; an incremental sync counts the same window on both sides.
     */
    private static ValidationSpec validation(WizardForm form, Endpoints endpoints, List<String> notes) {
        boolean es = ELASTICSEARCH.equals(endpoints.targetType());
        boolean full = form.sync().mode() == SyncMode.FULL;
        if (full && form.target().dataMode() == DataMode.KEEP) {
            notes.add("全量同步且保留目标已有数据：目标表里可能还有源端已删除的数据，计数不准，不做行数校验");
            return null;
        }
        if (!full && form.sync().window() == Window.CUSTOM) {
            notes.add("自定义增量条件无法套用到目标表，不做行数校验");
            return null;
        }
        if (!full && es) {
            notes.add("ES 目标的增量窗口不做行数校验（日期格式在 ES 里不好保证一致）");
            return null;
        }
        List<ValidationSpec.TableCheck> checks = new ArrayList<>();
        for (TableSpec table : form.source().tables()) {
            Side source = new Side(Mode.SQL, form.source().datasourceId(), sourceQuery(form, endpoints, table, true), null, null);
            String targetName = targetName(form, table, es);
            Side target;
            if (es) {
                target = new Side(Mode.ES, form.target().datasourceId(), null, targetName, null);
            } else if (full) {
                target = new Side(Mode.SQL, form.target().datasourceId(),
                        "select count(*) from " + qualified(endpoints.targetType(), form.target().namespace(), targetName), null, null);
            } else {
                String field = incrementalField(form, table);
                if (!table.allColumns() && !table.columns().contains(field)) {
                    notes.add(table.name() + "：增量字段 " + field + " 没有同步到目标表，这张表不做行数校验");
                    continue;
                }
                TableSpec onTarget = new TableSpec(targetName, null, null, targetField(form, table, field));
                target = new Side(Mode.SQL, form.target().datasourceId(),
                        "select count(*) from " + qualified(endpoints.targetType(), form.target().namespace(), targetName)
                                + " where " + incrementalCondition(form, endpoints.targetType(), onTarget),
                        null, null);
            }
            checks.add(new ValidationSpec.TableCheck(table.name(), source, target));
        }
        return checks.isEmpty() ? null : new ValidationSpec(true, null, null, false, checks);
    }

    private static void requireShape(WizardForm form, Endpoints endpoints) {
        if (form == null || form.source() == null || form.target() == null || form.sync() == null) {
            throw invalid("向导表单不完整");
        }
        if (!MYSQL.equals(endpoints.sourceType()) && !POSTGRESQL.equals(endpoints.sourceType())) {
            throw invalid("向导的源端只支持 MySQL 和 PostgreSQL");
        }
        if (!List.of(MYSQL, POSTGRESQL, ELASTICSEARCH).contains(endpoints.targetType())) {
            throw invalid("向导的目标端只支持 MySQL、PostgreSQL 和 Elasticsearch");
        }
        if (form.source().tables() == null || form.source().tables().isEmpty()) {
            throw invalid("请至少选择一张源表");
        }
        if (!notBlank(form.source().namespace())) {
            throw invalid("请选择源端的库或 schema");
        }
        if (!ELASTICSEARCH.equals(endpoints.targetType()) && !notBlank(form.target().namespace())) {
            throw invalid("请选择目标端的库或 schema");
        }
        for (TableSpec table : form.source().tables()) {
            if (targetName(form, table, false).isBlank()) {
                throw invalid(table.name() + "：目标表名为空");
            }
        }
        if (form.target().schemaMode() == null || form.target().dataMode() == null || form.target().writeMode() == null) {
            throw invalid("请完成目标端选项");
        }
        if (form.sync().mode() == null) {
            throw invalid("请选择同步方式");
        }
        if (form.sync().mode() == SyncMode.INCREMENTAL) {
            if (form.sync().window() == null) {
                throw invalid("请选择增量的时间窗口");
            }
            if (form.sync().window() == Window.CUSTOM && !notBlank(form.sync().customCondition())) {
                throw invalid("请填写自定义增量条件");
            }
            if (form.sync().window() != Window.CUSTOM) {
                for (TableSpec table : form.source().tables()) {
                    if (incrementalField(form, table) == null || incrementalField(form, table).isBlank()) {
                        throw invalid(table.name() + "：请选择增量字段");
                    }
                }
            }
        }
    }

    private static String tablePath(WizardForm form, Endpoints endpoints, String table) {
        return POSTGRESQL.equals(endpoints.sourceType())
                ? endpoints.sourceDatabase() + "." + form.source().namespace() + "." + table
                : form.source().namespace() + "." + table;
    }

    static String qualified(String dbType, String namespace, String table) {
        return quote(dbType, namespace) + "." + quote(dbType, table);
    }

    static String quote(String dbType, String identifier) {
        return MYSQL.equals(dbType)
                ? "`" + identifier.replace("`", "``") + "`"
                : "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** Advanced options: numbers, booleans, lists and objects written as-is, everything else quoted. */
    private static void appendOptions(StringBuilder hocon, Map<String, String> options) {
        if (options == null) {
            return;
        }
        options.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || value.isBlank()) {
                return;
            }
            String v = value.trim();
            hocon.append("    ").append(key.trim()).append(" = ").append(RAW_VALUE.matcher(v).matches() ? v : str(v)).append('\n');
        });
    }

    /** A HOCON quoted string; the text inside is never substituted by HOCON. */
    static String str(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static ConsoleException invalid(String message) {
        return new ConsoleException(ErrorCodes.INVALID_ARGUMENT, message);
    }
}
