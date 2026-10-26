package com.dataintegration.console.server.job.wizard;

import com.dataintegration.console.datasource.meta.ColumnMeta;
import com.dataintegration.console.server.job.wizard.WizardForm.Stream;
import com.dataintegration.console.server.job.wizard.WizardForm.TableSpec;
import com.dataintegration.console.server.job.wizard.WizardGenerator.Endpoints;
import com.dataintegration.console.server.job.wizard.WizardService.Check;
import com.dataintegration.console.server.job.wizard.WizardService.Level;
import com.dataintegration.console.server.job.wizard.source.CdcSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Prechecks only real-time jobs have: what later table-structure changes on the source will do to
 * the job (docs/design.md §12 P2, Q23–Q25). None of them stops saving; they say in advance which
 * changes will stop the job, so a failure later is no surprise.
 */
final class StreamPrecheck {

    private StreamPrecheck() {}

    /** @param sourceColumns the synced columns of every source table that exists */
    static void check(WizardForm form, Endpoints endpoints, Map<String, List<ColumnMeta>> sourceColumns, List<Check> checks) {
        Stream stream = form.stream();
        if (!stream.schemaChanges()) {
            checks.add(new Check(Level.INFO, "没有同步表结构变更：源表加的列不会同步；源表改名、删列或改类型后，需要修改任务并重新启动"));
            return;
        }
        boolean allChanges = WizardConnectors.sink(endpoints.targetType()).appliesAllColumnChanges();
        boolean onlyAdds = WizardConnectors.source(endpoints.sourceType(), form) instanceof CdcSource cdc
                && cdc.schemaChanges(form, endpoints).equals(List.of("add.column"));
        if (onlyAdds) {
            checks.add(new Check(Level.INFO, "PostgreSQL 来源只能同步加列；源表改名、删列或改类型会让任务停下，需要修改任务后全新启动"));
        } else if (!allChanges) {
            checks.add(new Check(Level.INFO, "目标是 Elasticsearch：只能自动同步加列；源表改名、改类型或删列会让任务停下，需要处理后全新启动"));
        } else {
            checks.add(new Check(Level.INFO, "源表改列名请用 ALTER TABLE … CHANGE COLUMN；MySQL 8 的 RENAME COLUMN 不会被同步，之后这一列在目标端会是空值"));
        }
        for (TableSpec table : form.source().tables()) {
            List<ColumnMeta> columns = sourceColumns.get(table.name());
            if (columns != null) {
                checkTable(form, table, columns, endpoints.sourceColumns(), stream, allChanges && !onlyAdds, checks);
            }
        }
    }

    private static void checkTable(WizardForm form, TableSpec table, List<ColumnMeta> synced, Map<String, List<String>> known,
            Stream stream, boolean dropsApplied, List<Check> checks) {
        // renamed and pruned columns are handled by transforms that fail the job on a change to them
        List<String> fragile = new ArrayList<>();
        for (ColumnMeta column : synced) {
            if (!WizardGenerator.targetField(form, table, column.name()).equals(column.name())) {
                fragile.add(column.name() + "（改名为 " + WizardGenerator.targetField(form, table, column.name()) + "）");
            }
        }
        if (!table.allColumns() && known != null && known.get(table.name()) != null) {
            known.get(table.name()).stream().filter(c -> !table.columns().contains(c)).forEach(c -> fragile.add(c + "（不同步）"));
        }
        if (!fragile.isEmpty()) {
            checks.add(new Check(Level.WARN, table.name() + "：这些列以后不能在源端改名、删除或改类型，否则任务会失败：" + String.join("、", fragile)));
        }
        if (stream.dropColumns() && dropsApplied) {
            return;
        }
        // a dropped column the target keeps gets nulls, which a NOT NULL column refuses; an
        // auto-created target table copies NOT NULL but not the default
        List<String> notNull = synced.stream()
                .filter(c -> !c.nullable() && !c.primaryKey())
                .map(c -> WizardGenerator.targetField(form, table, c.name()))
                .toList();
        if (!notNull.isEmpty()) {
            checks.add(new Check(Level.WARN, table.name() + "：目标端会保留源端删掉的列，之后写入空值；这些列不允许为空，在源端删掉后任务会失败："
                    + String.join("、", notNull)));
        }
    }
}
