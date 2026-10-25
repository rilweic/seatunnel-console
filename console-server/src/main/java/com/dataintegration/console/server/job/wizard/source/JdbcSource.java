package com.dataintegration.console.server.job.wizard.source;

import static com.dataintegration.console.server.job.wizard.Hocon.invalid;
import static com.dataintegration.console.server.job.wizard.Hocon.notBlank;
import static com.dataintegration.console.server.job.wizard.Hocon.str;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.datasource.CountRequest;
import com.dataintegration.console.datasource.jdbc.SqlDialect;
import com.dataintegration.console.datasource.meta.ColumnMeta;
import com.dataintegration.console.datasource.meta.TableMeta;
import com.dataintegration.console.server.datasource.DatasourceService;
import com.dataintegration.console.server.job.ValidationSpec;
import com.dataintegration.console.server.job.ValidationSpec.Mode;
import com.dataintegration.console.server.job.Variables;
import com.dataintegration.console.server.job.wizard.Hocon;
import com.dataintegration.console.server.job.wizard.WizardForm;
import com.dataintegration.console.server.job.wizard.WizardForm.SyncMode;
import com.dataintegration.console.server.job.wizard.WizardForm.TableSpec;
import com.dataintegration.console.server.job.wizard.WizardForm.Window;
import com.dataintegration.console.server.job.wizard.WizardGenerator;
import com.dataintegration.console.server.job.wizard.WizardGenerator.Endpoints;
import com.dataintegration.console.server.job.wizard.WizardService.Check;
import com.dataintegration.console.server.job.wizard.WizardService.Level;
import com.dataintegration.console.server.job.wizard.WizardSource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Tables of a relational database through SeaTunnel's Jdbc source (docs/design.md §7.2): one
 * {@code table_path} per table, plus the table's own {@code query} when it is pruned, filtered or
 * read incrementally (Jdbc's {@code where_condition} would apply to every table). Which database it
 * is only shows in the {@link SqlDialect}.
 */
public final class JdbcSource implements WizardSource {

    /** Options this source writes or takes from the datasource, so advanced options may not set them. */
    private static final Set<String> GENERATED_OPTIONS = Set.of(
            "datasource_id", "plugin_output", "url", "driver", "username", "password",
            "table_path", "table_list", "query", "where_condition", "parallelism");
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(30);

    private final SqlDialect dialect;

    public JdbcSource(SqlDialect dialect) {
        this.dialect = dialect;
    }

    @Override
    public void requireShape(WizardForm form) {
        if (form.sync().mode() != SyncMode.INCREMENTAL) {
            return;
        }
        if (form.sync().window() == null) {
            throw invalid("请选择增量的时间窗口");
        }
        if (form.sync().window() == Window.CUSTOM && !notBlank(form.sync().customCondition())) {
            throw invalid("请填写自定义增量条件");
        }
        if (form.sync().window() != Window.CUSTOM) {
            for (TableSpec table : form.source().tables()) {
                String field = WizardGenerator.incrementalField(form, table);
                if (field == null || field.isBlank()) {
                    throw invalid(table.name() + "：请选择增量字段");
                }
            }
        }
    }

    @Override
    public void append(StringBuilder hocon, WizardForm form, Endpoints endpoints) {
        hocon.append("source {\n  Jdbc {\n");
        hocon.append("    datasource_id = ").append(str(form.source().datasourceId())).append('\n');
        hocon.append("    table_list = [\n");
        for (TableSpec table : form.source().tables()) {
            hocon.append("      { table_path = ").append(str(tablePath(form, endpoints, table)));
            String query = sourceQuery(form, table, false);
            if (query != null) {
                hocon.append(", query = ").append(str(query));
            }
            hocon.append(" }\n");
        }
        hocon.append("    ]\n");
        Hocon.appendOptions(hocon, form.sourceOptions(), GENERATED_OPTIONS);
        hocon.append("    plugin_output = \"source\"\n  }\n}\n\n");
    }

    @Override
    public String tablePath(WizardForm form, Endpoints endpoints, TableSpec table) {
        return dialect.tablePath(endpoints.sourceDatabase(), form.source().namespace(), table.name());
    }

    @Override
    public String emittedName(WizardForm form, Endpoints endpoints, TableSpec table) {
        return table.name();
    }

    /**
     * Its column list, or for an all-columns table the columns read from the database (see
     * {@link #knownColumns}), else the fields named by hand. A processed all-columns table needs
     * the real list.
     */
    @Override
    public List<String> syncedColumns(WizardForm form, Endpoints endpoints, TableSpec table) {
        if (!table.allColumns()) {
            return table.columns();
        }
        List<String> known = endpoints.sourceColumns() == null ? null : endpoints.sourceColumns().get(table.name());
        if (known != null) {
            return known;
        }
        if (table.processed()) {
            throw invalid(table.name() + "：读不到源表的字段，无法生成字段处理");
        }
        return table.fieldNames() == null ? List.of() : List.copyOf(table.fieldNames().keySet());
    }

    /** The columns of all-columns tables whose field names follow a rule or whose values are processed. */
    @Override
    public Map<String, List<String>> knownColumns(WizardForm form, DatasourceService datasources) {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        if (form.source().tables() == null) {
            return columns;
        }
        for (TableSpec table : form.source().tables()) {
            if (table.allColumns() && (table.fieldRule() != null || form.fieldRule() != null || table.processed())) {
                try {
                    columns.put(table.name(), datasources.columns(form.source().datasourceId(), form.source().namespace(), table.name())
                            .stream().map(ColumnMeta::name).toList());
                } catch (ConsoleException e) {
                    // reported by the source check
                }
            }
        }
        return columns;
    }

    @Override
    public String database(WizardForm form, DatasourceService datasources) {
        return dialect.namespaceIsSchema() ? datasources.property(form.source().datasourceId(), "database") : null;
    }

    @Override
    public ValidationSpec.Side countSide(WizardForm form, Endpoints endpoints, TableSpec table) {
        return new ValidationSpec.Side(Mode.SQL, form.source().datasourceId(), sourceQuery(form, table, true), null, null);
    }

    /**
     * The table's select, or null when the plain table_path reads the same rows.
     *
     * @param forCount count(*) instead of the columns, for validation
     */
    public String sourceQuery(WizardForm form, TableSpec table, boolean forCount) {
        List<String> conditions = new ArrayList<>();
        if (notBlank(table.filter())) {
            conditions.add("(" + table.filter().trim() + ")");
        }
        String incremental = WizardGenerator.incrementalCondition(form, dialect, table);
        if (incremental != null) {
            conditions.add("(" + incremental + ")");
        }
        if (!forCount && conditions.isEmpty() && table.allColumns()) {
            return null;
        }
        String select = forCount
                ? "count(*)"
                : table.allColumns() ? "*" : String.join(", ", table.columns().stream().map(dialect::quote).toList());
        return "select " + select + " from " + dialect.qualified(form.source().namespace(), table.name())
                + (conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions));
    }

    @Override
    public Map<String, SourceTable> inspect(WizardForm form, Endpoints endpoints, Live live, List<Check> checks) {
        String ds = form.source().datasourceId();
        DatasourceService datasources = live.datasources();
        Map<String, TableMeta> tables;
        try {
            if (!datasources.namespaces(ds).contains(form.source().namespace())) {
                checks.add(new Check(Level.ERROR, "源端不存在库或 schema " + form.source().namespace()));
                return null;
            }
            tables = datasources.tables(ds, form.source().namespace()).stream()
                    .collect(Collectors.toMap(TableMeta::name, Function.identity()));
        } catch (ConsoleException e) {
            checks.add(new Check(Level.ERROR, "无法读取源端：" + e.getMessage()));
            return null;
        }
        Map<String, SourceTable> found = new LinkedHashMap<>();
        for (TableSpec table : form.source().tables()) {
            TableMeta meta = tables.get(table.name());
            if (meta == null) {
                checks.add(new Check(Level.ERROR, "源表不存在：" + table.name()));
                continue;
            }
            found.put(table.name(), new SourceTable(
                    datasources.columns(ds, form.source().namespace(), table.name()), meta.primaryKeys()));
        }
        return found;
    }

    /** The incremental field is a time column, and the generated query runs (under {@code where 1 = 0}). */
    @Override
    public void checkTable(WizardForm form, Endpoints endpoints, TableSpec table, Map<String, ColumnMeta> columns,
            Live live, List<Check> checks) {
        String field = WizardGenerator.incrementalField(form, table);
        if (field != null) {
            ColumnMeta column = columns.get(field);
            if (column == null) {
                checks.add(new Check(Level.ERROR, table.name() + "：增量字段 " + field + " 不存在"));
            } else if (!column.temporal()) {
                checks.add(new Check(Level.ERROR, table.name() + "：增量字段 " + field + " 是 " + column.type()
                        + "，不是时间类型；数字型字段请用\"自定义条件\""));
            }
        }
        String query = sourceQuery(form, table, false);
        if (query == null) {
            return;
        }
        String rendered = Variables.render(query, live.variables(), LocalDateTime.now());
        try {
            live.datasources().count(form.source().datasourceId(),
                    CountRequest.sql("select count(*) from (" + rendered + ") t_check where 1 = 0", CHECK_TIMEOUT));
        } catch (ConsoleException e) {
            checks.add(new Check(Level.ERROR, table.name() + "：生成的查询执行失败（检查过滤条件）：" + e.getMessage()));
        }
    }
}
