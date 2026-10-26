package com.dataintegration.console.server.stream;

import com.dataintegration.console.datasource.jdbc.PostgreSqlDataSourcePlugin;
import com.dataintegration.console.datasource.meta.ColumnMeta;
import com.dataintegration.console.server.job.wizard.WizardConnectors;
import com.dataintegration.console.server.job.wizard.WizardForm;
import com.dataintegration.console.server.job.wizard.WizardForm.TableSpec;
import com.dataintegration.console.server.job.wizard.WizardGenerator;
import com.dataintegration.console.server.stream.SchemaDiff.Renamed;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Whether the target of a real-time job followed each column change of a source table, and why not
 * (docs/design.md §12 P2, Q16, Q24): what the job passes on depends on its settings and connectors.
 * Used for the run's timeline and for the detail page's table mapping. Pure.
 */
public final class SchemaFollow {

    /**
     * @param change what changed, e.g. "新增列 remark VARCHAR(32)"
     * @param status what happened on the target, or why it did not follow; null when there is nothing to say
     * @param problem the target did not follow and someone has to act
     */
    public record ColumnChange(String change, String status, boolean problem) {

        /** One line of a timeline event. */
        public String line() {
            return status == null || status.isEmpty() ? change : change + " → " + status;
        }
    }

    private final WizardForm form;
    private final boolean lowerCase;
    private final boolean on;
    private final boolean postgres;
    private final boolean allChanges;
    private final boolean drops;

    public SchemaFollow(WizardForm form, String sourceType, String targetType) {
        this.form = form;
        lowerCase = WizardConnectors.capabilities(targetType).lowerCaseNames();
        on = form.stream() != null && form.stream().schemaChanges();
        postgres = PostgreSqlDataSourcePlugin.CDC_CONNECTOR.equals(WizardConnectors.capabilities(sourceType).cdcConnector());
        allChanges = WizardConnectors.sink(targetType).appliesAllColumnChanges();
        drops = on && !postgres && allChanges && form.stream().dropColumns();
    }

    /** The table's target table or index name. */
    public String targetName(TableSpec table) {
        return WizardGenerator.targetName(form, table, lowerCase);
    }

    /**
     * @param target the target table's columns as last read; null when it could not be read
     */
    public List<ColumnChange> assess(TableSpec table, SchemaDiff changes, List<ColumnMeta> target) {
        Set<String> targetColumns = target == null ? Set.of() : target.stream().map(c -> key(c.name())).collect(Collectors.toSet());
        List<ColumnChange> out = new ArrayList<>();
        for (ColumnMeta c : changes.added()) {
            boolean there = targetColumns.contains(key(c.name()));
            out.add(new ColumnChange("新增列 " + c.name() + " " + c.type(), there ? "目标端已同步" : notYet(on, "目标端没有加这一列"), !there && !on));
        }
        for (Renamed r : changes.renamed()) {
            String to = WizardGenerator.targetField(form, table, r.from()).equals(r.from()) ? r.to() : null;
            boolean there = to != null && targetColumns.contains(key(to));
            String status;
            if (to == null) {
                status = "这一列在任务里改了名，任务可能已经因此失败，需要修改任务后全新启动";
            } else if (there) {
                status = "目标端已同步";
            } else if (renames() && targetColumns.contains(key(r.from()))) {
                status = "改名未同步，目标端还是 " + r.from() + "，之后写入的这一列在目标端为空（MySQL 8 的 RENAME COLUMN 不会被同步，请用 CHANGE COLUMN）；"
                        + "需要手动修改目标表，或修改任务后全新启动";
            } else {
                status = notYet(renames(), "目标端没有改名");
            }
            out.add(new ColumnChange("列改名 " + r.from() + " → " + r.to(), status, !there));
        }
        for (SchemaDiff.Retyped r : changes.retyped()) {
            out.add(new ColumnChange("列 " + r.column() + " 类型 " + r.from() + " → " + r.to(), renames() ? null : why(), !renames()));
        }
        for (ColumnMeta c : changes.dropped()) {
            boolean kept = targetColumns.contains(key(WizardGenerator.targetField(form, table, c.name())));
            String status = !kept ? "目标端已删除这一列" : drops ? notYet(true, "") : "目标端保留这一列，之后写入空值";
            out.add(new ColumnChange("删除列 " + c.name(), status, false));
        }
        return out;
    }

    /** Renames and type changes reach the target only from MySQL to a JDBC target. */
    private boolean renames() {
        return on && !postgres && allChanges;
    }

    private String why() {
        if (!on) {
            return "没有打开同步表结构变更，需要修改任务后重启";
        }
        if (postgres) {
            return "PostgreSQL 只同步加列，任务会停下，需要修改任务后全新启动";
        }
        return "目标是 Elasticsearch，只同步加列，任务会停下，需要处理后全新启动";
    }

    /** For a change that should reach the target: it does when the table next has a row change. */
    private String notYet(boolean expected, String fallback) {
        return expected ? "目标端还没有变化（一般在这张表下一次有数据变更时同步）" : (fallback.isEmpty() ? "" : fallback + "：") + why();
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
