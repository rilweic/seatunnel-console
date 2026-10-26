package com.dataintegration.console.server.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.dataintegration.console.datasource.meta.ColumnMeta;
import com.dataintegration.console.server.job.wizard.WizardForm;
import com.dataintegration.console.server.job.wizard.WizardForm.SchemaMode;
import com.dataintegration.console.server.job.wizard.WizardForm.Startup;
import com.dataintegration.console.server.job.wizard.WizardForm.Stream;
import com.dataintegration.console.server.job.wizard.WizardForm.TableSpec;
import com.dataintegration.console.server.job.wizard.WizardForm.Target;
import com.dataintegration.console.server.stream.SchemaFollow.ColumnChange;

import org.junit.jupiter.api.Test;

import java.util.List;

class SchemaFollowTest {

    private static final TableSpec ORDERS = new TableSpec("orders", null, null, null);
    private static final List<ColumnMeta> BEFORE = List.of(col("id", "INT"), col("note", "VARCHAR(64)"), col("amount", "INT"), col("legacy", "VARCHAR(8)"));
    private static final List<ColumnMeta> AFTER = List.of(col("id", "INT"), col("memo", "VARCHAR(64)"), col("amount", "BIGINT"), col("remark", "TEXT"));

    private static ColumnMeta col(String name, String type) {
        return new ColumnMeta(name, type, true, false, false);
    }

    private static WizardForm form(Stream stream) {
        return new WizardForm(new WizardForm.Source("src-ds", "shop", List.of(ORDERS)),
                new Target("dst-ds", "ods", null, null, null, SchemaMode.CREATE, null, null), null, 1, false, null, null, null, stream);
    }

    @Test
    void mysqlToAJdbcTargetPassesOnRenamesAndTypesAndSaysWhatHasNotArrivedYet() {
        SchemaFollow follow = new SchemaFollow(form(new Stream(Startup.INITIAL, null, true, false, false, null)), "MYSQL", "MYSQL");
        List<ColumnMeta> target = List.of(col("id", "INT"), col("memo", "VARCHAR(64)"), col("amount", "BIGINT"), col("legacy", "VARCHAR(8)"));

        List<ColumnChange> changes = follow.assess(ORDERS, SchemaDiff.between(BEFORE, AFTER), target);

        assertThat(changes).extracting(ColumnChange::line).containsExactly(
                "新增列 remark TEXT → 目标端还没有变化（一般在这张表下一次有数据变更时同步）",
                "列改名 note → memo → 目标端已同步",
                "列 amount 类型 INT → BIGINT",
                "删除列 legacy → 目标端保留这一列，之后写入空值");
        assertThat(changes).noneMatch(ColumnChange::problem);
    }

    @Test
    void postgresOnlyAddsColumnsSoOtherChangesNeedSomeoneToAct() {
        SchemaFollow follow = new SchemaFollow(form(new Stream(Startup.INITIAL, null, true, false, false, null)), "POSTGRESQL", "MYSQL");

        List<ColumnChange> changes = follow.assess(ORDERS, SchemaDiff.between(BEFORE, AFTER), BEFORE);

        assertThat(changes).filteredOn(ColumnChange::problem).extracting(ColumnChange::change)
                .containsExactly("列改名 note → memo", "列 amount 类型 INT → BIGINT");
        assertThat(changes.get(2).status()).startsWith("PostgreSQL 只同步加列");
    }

    @Test
    void withoutSchemaChangesAnAddedColumnIsAProblem() {
        SchemaFollow follow = new SchemaFollow(form(new Stream(Startup.INITIAL, null, false, false, false, null)), "MYSQL", "MYSQL");

        List<ColumnChange> changes = follow.assess(ORDERS, SchemaDiff.between(BEFORE.subList(0, 1), BEFORE.subList(0, 2)), BEFORE.subList(0, 1));

        assertThat(changes).singleElement().satisfies(c -> {
            assertThat(c.problem()).isTrue();
            assertThat(c.status()).isEqualTo("目标端没有加这一列：没有打开同步表结构变更，需要修改任务后重启");
        });
        assertThat(follow.targetName(ORDERS)).isEqualTo("orders");
    }
}
