package com.dataintegration.console.server.job.wizard;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.server.job.ValidationSpec;
import com.dataintegration.console.server.job.ValidationSpec.Mode;
import com.dataintegration.console.server.job.ValidationSpec.Side;
import com.dataintegration.console.server.job.wizard.WizardForm.DataMode;
import com.dataintegration.console.server.job.wizard.WizardForm.SchemaMode;
import com.dataintegration.console.server.job.wizard.WizardForm.SyncMode;
import com.dataintegration.console.server.job.wizard.WizardForm.TableSpec;
import com.dataintegration.console.server.job.wizard.WizardForm.Window;
import com.dataintegration.console.server.job.wizard.WizardForm.WriteMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns a {@link WizardForm} into a SeaTunnel 3.0 job config and its per-table validation
 * (docs/design.md §7.2, §7.4–7.6). Pure: everything it needs about the datasources comes in
 * through {@link Endpoints}, so the output is fully determined by its inputs.
 *
 * <p>Every table gets a {@code table_path}; a table with column pruning, a filter or an incremental
 * condition also gets its own {@code query} (Jdbc's {@code where_condition} would apply to every
 * table). The sink addresses tables with SeaTunnel's {@code ${table_name}} placeholder.
 */
public final class WizardGenerator {

    public static final String MYSQL = "MYSQL";
    public static final String POSTGRESQL = "POSTGRESQL";
    public static final String ELASTICSEARCH = "ELASTICSEARCH";

    private static final Pattern RAW_VALUE = Pattern.compile("-?\\d+(\\.\\d+)?|true|false|\\[.*]|\\{.*}", Pattern.DOTALL);

    /**
     * @param sourceDatabase PostgreSQL database of the source datasource (part of table_path); null for MySQL
     * @param targetDatabase PostgreSQL database of the target datasource; null otherwise
     */
    public record Endpoints(String sourceType, String sourceDatabase, String targetType, String targetDatabase) {}

    /**
     * @param validation per-table checks, or null when none can be accurate or validation is off
     * @param notes why validation is skipped for some tables, for the preview
     */
    public record Generated(String hocon, ValidationSpec validation, List<String> notes) {}

    private WizardGenerator() {}

    public static Generated generate(WizardForm form, Endpoints endpoints) {
        requireShape(form, endpoints);
        boolean single = form.source().tables().size() == 1;
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

        hocon.append("sink {\n");
        if (ELASTICSEARCH.equals(endpoints.targetType())) {
            hocon.append("  Elasticsearch {\n");
            hocon.append("    datasource_id = ").append(str(form.target().datasourceId())).append('\n');
            hocon.append("    index = ").append(str(single ? targetName(form, form.source().tables().get(0).name()) : placeholderName(form))).append('\n');
            if (form.target().writeMode() == WriteMode.UPSERT) {
                hocon.append("    primary_keys = [\"${primary_key}\"]\n");
            }
        } else {
            hocon.append("  Jdbc {\n");
            hocon.append("    datasource_id = ").append(str(form.target().datasourceId())).append('\n');
            String name = single ? targetName(form, form.source().tables().get(0).name()) : placeholderName(form);
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
        hocon.append("    plugin_input = [\"source\"]\n  }\n}\n");

        List<String> notes = new ArrayList<>();
        ValidationSpec validation = form.validation() ? validation(form, endpoints, notes) : null;
        return new Generated(hocon.toString(), validation, notes);
    }

    /** Target table or index name of one source table. */
    public static String targetName(WizardForm form, String sourceTable) {
        if (form.source().tables().size() == 1 && notBlank(form.target().tableName())) {
            return form.target().tableName().trim();
        }
        return nullToEmpty(form.target().prefix()) + sourceTable + nullToEmpty(form.target().suffix());
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
            String targetName = targetName(form, table.name());
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
                TableSpec onTarget = new TableSpec(targetName, null, null, table.incrementalField());
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

    private static String placeholderName(WizardForm form) {
        return nullToEmpty(form.target().prefix()) + "${table_name}" + nullToEmpty(form.target().suffix());
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
