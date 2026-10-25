package com.dataintegration.console.server.job.wizard.sink;

import static com.dataintegration.console.server.job.wizard.Hocon.str;

import com.dataintegration.console.datasource.jdbc.SqlDialect;
import com.dataintegration.console.server.datasource.DatasourceService;
import com.dataintegration.console.server.job.ValidationSpec.Mode;
import com.dataintegration.console.server.job.ValidationSpec.Side;
import com.dataintegration.console.server.job.wizard.WizardForm;
import com.dataintegration.console.server.job.wizard.WizardForm.TableSpec;
import com.dataintegration.console.server.job.wizard.WizardGenerator;
import com.dataintegration.console.server.job.wizard.WizardGenerator.Endpoints;
import com.dataintegration.console.server.job.wizard.WizardSink;

import java.util.List;

/**
 * Tables of a relational database through SeaTunnel's Jdbc sink, addressed with the
 * {@code ${table_name}} placeholder; the sink generates its own SQL and upserts by
 * {@code ${primary_key}}. Which database it is only shows in the {@link SqlDialect}.
 */
public final class JdbcSink implements WizardSink {

    private final SqlDialect dialect;

    public JdbcSink(SqlDialect dialect) {
        this.dialect = dialect;
    }

    @Override
    public String tableWord() {
        return "目标表";
    }

    @Override
    public void appendHead(StringBuilder hocon, WizardForm form, Endpoints endpoints, boolean upsert) {
        hocon.append("  Jdbc {\n");
        hocon.append("    datasource_id = ").append(str(form.target().datasourceId())).append('\n');
        String name = "${table_name}";
        if (dialect.namespaceIsSchema()) {
            hocon.append("    database = ").append(str(endpoints.targetDatabase())).append('\n');
            hocon.append("    table = ").append(str(form.target().namespace() + "." + name)).append('\n');
        } else {
            hocon.append("    database = ").append(str(form.target().namespace())).append('\n');
            hocon.append("    table = ").append(str(name)).append('\n');
        }
        hocon.append("    generate_sink_sql = true\n");
        hocon.append("    enable_upsert = ").append(upsert).append('\n');
    }

    @Override
    public String database(WizardForm form, DatasourceService datasources) {
        return dialect.namespaceIsSchema() ? datasources.property(form.target().datasourceId(), "database") : null;
    }

    @Override
    public boolean comparesColumns() {
        return true;
    }

    @Override
    public Side countSide(WizardForm form, Endpoints endpoints, TableSpec table, String targetName,
            boolean full, List<String> notes) {
        String count = "select count(*) from " + dialect.qualified(form.target().namespace(), targetName);
        if (full) {
            return new Side(Mode.SQL, form.target().datasourceId(), count, null, null);
        }
        String field = WizardGenerator.incrementalField(form, table);
        if (!table.allColumns() && !table.columns().contains(field)) {
            notes.add(table.name() + "：增量字段 " + field + " 没有同步到目标表，这张表不做行数校验");
            return null;
        }
        TableSpec onTarget = new TableSpec(targetName, null, null, WizardGenerator.targetField(form, table, field));
        return new Side(Mode.SQL, form.target().datasourceId(),
                count + " where " + WizardGenerator.incrementalCondition(form, dialect, onTarget), null, null);
    }
}
