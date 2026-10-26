package com.dataintegration.console.server.job.wizard.source;

import static com.dataintegration.console.server.job.wizard.Hocon.invalid;
import static com.dataintegration.console.server.job.wizard.Hocon.notBlank;
import static com.dataintegration.console.server.job.wizard.Hocon.str;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.datasource.jdbc.JdbcDataSourcePlugin;
import com.dataintegration.console.datasource.jdbc.PostgreSqlDataSourcePlugin;
import com.dataintegration.console.datasource.meta.ColumnMeta;
import com.dataintegration.console.server.datasource.DatasourceService;
import com.dataintegration.console.server.job.wizard.Hocon;
import com.dataintegration.console.server.job.wizard.WizardConnectors;
import com.dataintegration.console.server.job.wizard.WizardForm;
import com.dataintegration.console.server.job.wizard.WizardForm.Startup;
import com.dataintegration.console.server.job.wizard.WizardForm.Stream;
import com.dataintegration.console.server.job.wizard.WizardForm.TableSpec;
import com.dataintegration.console.server.job.wizard.WizardGenerator.Endpoints;
import com.dataintegration.console.server.job.wizard.WizardService.Check;
import com.dataintegration.console.server.job.wizard.WizardService.Level;
import com.dataintegration.console.server.job.wizard.WizardSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tables of MySQL or PostgreSQL read with SeaTunnel's CDC source (MySQL-CDC, Postgres-CDC) for a
 * real-time job (docs/design.md §12 P2): a snapshot, then every change from the binlog or WAL.
 * Tables are listed by name and must have a primary key; there is no query, so pruned columns are
 * left out by a transform instead (WizardGenerator). The replication identity on the database
 * (MySQL server-id, PostgreSQL slot and publication) belongs to the job, not to a version, and is
 * put in when the job is submitted ({@code stream.StreamIdentity}).
 */
public final class CdcSource implements WizardSource {

    /** Options this source writes or the console puts in at submit time, so advanced options may not set them. */
    private static final Set<String> GENERATED_OPTIONS = Set.of(
            "datasource_id", "plugin_output", "url", "username", "password", "database-names", "schema-name",
            "table-names", "table-pattern", "startup.mode", "startup.timestamp", "schema-changes.enabled",
            "schema-changes.include", "schema-changes.exclude", "decoding.plugin.name", "slot.name");

    private final JdbcDataSourcePlugin plugin;
    /** Columns, keys and database of the tables work exactly as for the Jdbc source. */
    private final JdbcSource tables;

    public CdcSource(JdbcDataSourcePlugin plugin) {
        this.plugin = plugin;
        this.tables = new JdbcSource(plugin.dialect());
    }

    /** PostgreSQL only streams added columns, and has no start-from-a-time mode (verified on 3.0.1). */
    private boolean postgres() {
        return PostgreSqlDataSourcePlugin.CDC_CONNECTOR.equals(plugin.capabilities().cdcConnector());
    }

    @Override
    public void requireShape(WizardForm form) {
        Stream stream = form.stream();
        if (stream == null || stream.startup() == null) {
            throw invalid("请选择从哪里开始同步");
        }
        if (stream.startup() == Startup.TIMESTAMP) {
            if (postgres()) {
                throw invalid("PostgreSQL 不支持从某个时间点开始，请选择\"全量 + 增量\"或\"只同步之后的变更\"");
            }
            if (stream.startupTimestamp() == null) {
                throw invalid("请选择开始同步的时间点");
            }
        }
        if (stream.dropColumns() && !stream.schemaChanges()) {
            throw invalid("同步删列需要先打开\"同步表结构变更\"");
        }
        for (TableSpec table : form.source().tables()) {
            if (notBlank(table.filter())) {
                // a filtered stream misses updates that move a row out of the filter, so the target
                // would keep rows the filter no longer selects
                throw invalid(table.name() + "：实时同步不支持过滤条件");
            }
            if (stream.schemaChanges() && (table.processed() || hasEncryption(table))) {
                // the Sql transform behind them fails the job on any rename or drop in the table
                throw invalid(table.name() + "：打开\"同步表结构变更\"后不能使用字段处理（替换、脱敏、类型转换、加密、新增和提取字段）。"
                        + "两者只能选一个：源表改名或删列时，字段处理会让任务失败");
            }
        }
    }

    private static boolean hasEncryption(TableSpec table) {
        return table.processes() != null && table.processes().values().stream().anyMatch(p -> p != null && p.encrypted());
    }

    @Override
    public void append(StringBuilder hocon, WizardForm form, Endpoints endpoints) {
        Stream stream = form.stream();
        String namespace = form.source().namespace();
        String database = postgres() ? endpoints.sourceDatabase() : namespace;
        hocon.append("source {\n  ").append(plugin.capabilities().cdcConnector()).append(" {\n");
        hocon.append("    datasource_id = ").append(str(form.source().datasourceId())).append('\n');
        hocon.append("    database-names = [").append(str(database)).append("]\n");
        hocon.append("    table-names = [\n");
        for (TableSpec table : form.source().tables()) {
            hocon.append("      ").append(str(tablePath(form, endpoints, table))).append('\n');
        }
        hocon.append("    ]\n");
        hocon.append("    startup.mode = ").append(str(stream.startup().name().toLowerCase())).append('\n');
        if (stream.startup() == Startup.TIMESTAMP) {
            hocon.append("    startup.timestamp = ").append(stream.startupTimestamp()).append('\n');
        }
        if (postgres()) {
            // the only plugin that streams schema changes, and the one PostgreSQL ships with
            hocon.append("    decoding.plugin.name = \"pgoutput\"\n");
        }
        if (stream.schemaChanges()) {
            hocon.append("    schema-changes.enabled = true\n");
            hocon.append("    schema-changes.include = [")
                    .append(String.join(", ", schemaChanges(form, endpoints).stream().map(Hocon::str).toList())).append("]\n");
        }
        Hocon.appendOptions(hocon, form.sourceOptions(), GENERATED_OPTIONS);
        hocon.append("    plugin_output = \"source\"\n  }\n}\n\n");
    }

    /**
     * The column changes passed on (design.md §12 P2, Q24). Renames and type changes always are:
     * left out, the source keeps streaming rows in the new shape and the target silently gets
     * nulls or overflowed values; passed on, a target that cannot apply them stops the job
     * instead. Drops are optional because a target that keeps the column is still correct.
     * PostgreSQL streams only added columns and stops the job on anything else.
     */
    public List<String> schemaChanges(WizardForm form, Endpoints endpoints) {
        if (postgres()) {
            return List.of("add.column");
        }
        List<String> types = new ArrayList<>(List.of("add.column", "modify.column", "change.column"));
        if (form.stream().dropColumns() && WizardConnectors.sink(endpoints.targetType()).appliesAllColumnChanges()) {
            types.add("drop.column");
        }
        return types;
    }

    @Override
    public String tablePath(WizardForm form, Endpoints endpoints, TableSpec table) {
        return tables.tablePath(form, endpoints, table);
    }

    @Override
    public String emittedName(WizardForm form, Endpoints endpoints, TableSpec table) {
        return table.name();
    }

    @Override
    public List<String> syncedColumns(WizardForm form, Endpoints endpoints, TableSpec table) {
        return tables.syncedColumns(form, endpoints, table);
    }

    /**
     * Also the columns of pruned tables: without a query, the pruned columns are left out by name,
     * so new columns added later still reach the target.
     */
    @Override
    public Map<String, List<String>> knownColumns(WizardForm form, DatasourceService datasources) {
        Map<String, List<String>> columns = new LinkedHashMap<>(tables.knownColumns(form, datasources));
        if (form.source().tables() == null) {
            return columns;
        }
        for (TableSpec table : form.source().tables()) {
            if (!table.allColumns() && !columns.containsKey(table.name())) {
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
        return tables.database(form, datasources);
    }

    @Override
    public boolean upsertPossible(WizardForm form) {
        return true;
    }

    @Override
    public String uncountableNote() {
        return "实时任务的数据一直在变，不做行数校验";
    }

    /** The tables exist, and each has a primary key: changes are applied to the target by key. */
    @Override
    public Map<String, SourceTable> inspect(WizardForm form, Endpoints endpoints, Live live, List<Check> checks) {
        Map<String, SourceTable> found = tables.inspect(form, endpoints, live, checks);
        if (found != null) {
            found.forEach((name, table) -> {
                if (table.primaryKeys().isEmpty()) {
                    checks.add(new Check(Level.ERROR, name + "：没有主键，实时同步要按主键把更新和删除写到目标端"));
                }
            });
        }
        return found;
    }
}
