package com.dataintegration.console.server.job.wizard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dataintegration.console.server.job.HoconTemplate;
import com.dataintegration.console.server.job.ValidationSpec;
import com.dataintegration.console.server.job.Variables;
import com.dataintegration.console.server.job.wizard.WizardForm.DataMode;
import com.dataintegration.console.server.job.wizard.WizardForm.SchemaMode;
import com.dataintegration.console.server.job.wizard.WizardForm.Sync;
import com.dataintegration.console.server.job.wizard.WizardForm.SyncMode;
import com.dataintegration.console.server.job.wizard.WizardForm.TableSpec;
import com.dataintegration.console.server.job.wizard.WizardForm.Target;
import com.dataintegration.console.server.job.wizard.WizardForm.Window;
import com.dataintegration.console.server.job.wizard.WizardForm.WriteMode;
import com.dataintegration.console.server.job.wizard.WizardGenerator.Endpoints;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

class WizardGeneratorTest {

    private static final Endpoints MYSQL_TO_MYSQL = new Endpoints("MYSQL", null, "MYSQL", null);
    private static final Endpoints MYSQL_TO_PG = new Endpoints("MYSQL", null, "POSTGRESQL", "dw");
    private static final Endpoints PG_TO_ES = new Endpoints("POSTGRESQL", "app", "ELASTICSEARCH", null);
    private static final Sync FULL = new Sync(SyncMode.FULL, null, null, null);

    private static WizardForm form(List<TableSpec> tables, Target target, Sync sync, boolean validation) {
        return new WizardForm(new WizardForm.Source("src-ds", "shop", tables), target, sync, 2, validation, null, null);
    }

    private static Target target(String ns, DataMode data, WriteMode write) {
        return new Target("dst-ds", ns, null, "ods_", null, SchemaMode.CREATE, data, write);
    }

    /** The output must survive what a run does to it: variables, then HOCON parsing. */
    private static HoconTemplate parse(String hocon) {
        return HoconTemplate.parse(Variables.render(hocon, Map.of(), LocalDateTime.of(2026, 10, 24, 2, 0)));
    }

    @Test
    void multiTableFullSyncUsesPlaceholdersAndPlainTablePaths() {
        WizardGenerator.Generated g = WizardGenerator.generate(
                form(List.of(new TableSpec("orders", null, null, null), new TableSpec("users", null, null, null)),
                        target("ods", DataMode.KEEP, WriteMode.UPSERT), FULL, true),
                MYSQL_TO_MYSQL);

        assertThat(g.hocon())
                .contains("parallelism = 2")
                .contains("{ table_path = \"shop.orders\" }")
                .contains("{ table_path = \"shop.users\" }")
                .contains("database = \"ods\"")
                .contains("table = \"ods_${table_name}\"")
                .contains("enable_upsert = true")
                .contains("schema_save_mode = \"CREATE_SCHEMA_WHEN_NOT_EXIST\"")
                .contains("data_save_mode = \"APPEND_DATA\"");
        HoconTemplate template = parse(g.hocon());
        assertThat(template.datasourceRefs()).extracting(r -> r.datasourceId()).containsExactly("src-ds", "dst-ds");
        // full sync keeping old rows cannot be counted reliably
        assertThat(g.validation()).isNull();
        assertThat(g.notes()).anyMatch(n -> n.contains("保留目标已有数据"));
    }

    @Test
    void singleTableIncrementalWithColumnsAndFilterIntoPostgres() {
        Target t = new Target("dst-ds", "public", "orders_copy", null, null, SchemaMode.ERROR, DataMode.KEEP, WriteMode.UPSERT);
        WizardGenerator.Generated g = WizardGenerator.generate(
                form(List.of(new TableSpec("orders", List.of("id", "amount", "updated_at"), "status = 1", null)),
                        t, new Sync(SyncMode.INCREMENTAL, Window.DAY, "updated_at", null), true),
                MYSQL_TO_PG);

        assertThat(g.hocon())
                .contains("query = \"select `id`, `amount`, `updated_at` from `shop`.`orders` where (status = 1) and "
                        + "(`updated_at` >= '$[yyyy-MM-dd-1] 00:00:00' and `updated_at` < '$[yyyy-MM-dd] 00:00:00')\"")
                .contains("database = \"dw\"")
                .contains("table = \"public.orders_copy\"")
                .contains("schema_save_mode = \"ERROR_WHEN_SCHEMA_NOT_EXIST\"");
        String rendered = Variables.render(g.hocon(), Map.of(), LocalDateTime.of(2026, 10, 24, 2, 0));
        assertThat(rendered).contains("'2026-10-23 00:00:00'").contains("'2026-10-24 00:00:00'");
        parse(g.hocon());

        ValidationSpec v = g.validation();
        assertThat(v.tables()).hasSize(1);
        assertThat(v.tables().get(0).source().sql())
                .startsWith("select count(*) from `shop`.`orders` where (status = 1) and (`updated_at` >=");
        assertThat(v.tables().get(0).target().sql())
                .isEqualTo("select count(*) from \"public\".\"orders_copy\" where \"updated_at\" >= '$[yyyy-MM-dd-1] 00:00:00'"
                        + " and \"updated_at\" < '$[yyyy-MM-dd] 00:00:00'");
    }

    @Test
    void hourlyWindowAndPerTableIncrementalField() {
        WizardGenerator.Generated g = WizardGenerator.generate(
                form(List.of(new TableSpec("a", null, null, null), new TableSpec("b", null, null, "modified")),
                        target("ods", DataMode.KEEP, WriteMode.UPSERT), new Sync(SyncMode.INCREMENTAL, Window.HOUR, "updated_at", null), false),
                MYSQL_TO_MYSQL);
        assertThat(g.hocon())
                .contains("`updated_at` >= '$[yyyy-MM-dd HH-1/24]:00:00' and `updated_at` < '$[yyyy-MM-dd HH]:00:00'")
                .contains("`modified` >= '$[yyyy-MM-dd HH-1/24]:00:00'");
        assertThat(Variables.render(g.hocon(), Map.of(), LocalDateTime.of(2026, 10, 24, 0, 30)))
                .contains("'2026-10-23 23:00:00'").contains("'2026-10-24 00:00:00'");
        assertThat(g.validation()).isNull();
    }

    @Test
    void elasticsearchTargetCountsIndicesAfterClearingAndUsesPrimaryKeyPlaceholder() {
        WizardGenerator.Generated g = WizardGenerator.generate(
                form(List.of(new TableSpec("orders", null, null, null), new TableSpec("users", null, null, null)),
                        new Target("es-ds", null, null, "app_", null, SchemaMode.CREATE, DataMode.CLEAR, WriteMode.UPSERT), FULL, true),
                PG_TO_ES);

        assertThat(g.hocon())
                .contains("{ table_path = \"app.shop.orders\" }")
                .contains("index = \"app_${table_name}\"")
                .contains("primary_keys = [\"${primary_key}\"]")
                .contains("data_save_mode = \"DROP_DATA\"")
                .doesNotContain("enable_upsert");
        parse(g.hocon());
        assertThat(g.validation().tables()).extracting(c -> c.target().index()).containsExactly("app_orders", "app_users");
        assertThat(g.validation().tables().get(0).source().sql()).isEqualTo("select count(*) from \"shop\".\"orders\"");
    }

    @Test
    void validationSkipsTablesWhoseIncrementalFieldIsNotSynced() {
        WizardGenerator.Generated g = WizardGenerator.generate(
                form(List.of(new TableSpec("a", List.of("id"), null, null), new TableSpec("b", null, null, null)),
                        target("ods", DataMode.KEEP, WriteMode.UPSERT), new Sync(SyncMode.INCREMENTAL, Window.DAY, "updated_at", null), true),
                MYSQL_TO_MYSQL);
        assertThat(g.validation().tables()).extracting(ValidationSpec.TableCheck::name).containsExactly("b");
        assertThat(g.notes()).anyMatch(n -> n.startsWith("a："));
    }

    @Test
    void advancedOptionsAndEscaping() {
        WizardForm f = new WizardForm(
                new WizardForm.Source("src-ds", "shop", List.of(new TableSpec("t", null, "note = \"x\"", null))),
                target("ods", DataMode.CLEAR, WriteMode.APPEND), FULL, 1, false,
                Map.of("fetch_size", "500"), Map.of("batch_size", "2000", "field_ide", "LOWERCASE"));
        String hocon = WizardGenerator.generate(f, MYSQL_TO_MYSQL).hocon();
        assertThat(hocon)
                .contains("fetch_size = 500")
                .contains("batch_size = 2000")
                .contains("field_ide = \"LOWERCASE\"")
                .contains("where (note = \\\"x\\\")")
                .contains("enable_upsert = false");
        parse(hocon);
    }

    @Test
    void rejectsIncompleteForms() {
        assertThatThrownBy(() -> WizardGenerator.generate(form(List.of(), target("ods", DataMode.KEEP, WriteMode.UPSERT), FULL, false), MYSQL_TO_MYSQL))
                .hasMessageContaining("至少选择一张");
        assertThatThrownBy(() -> WizardGenerator.generate(
                        form(List.of(new TableSpec("t", null, null, null)), target("ods", DataMode.KEEP, WriteMode.UPSERT),
                                new Sync(SyncMode.INCREMENTAL, Window.DAY, null, null), false),
                        MYSQL_TO_MYSQL))
                .hasMessageContaining("增量字段");
        assertThatThrownBy(() -> WizardGenerator.generate(
                        form(List.of(new TableSpec("t", null, null, null)), target("ods", DataMode.KEEP, WriteMode.UPSERT), FULL, false),
                        new Endpoints("ELASTICSEARCH", null, "MYSQL", null)))
                .hasMessageContaining("源端只支持");
    }

    @Test
    void seatunnelPlaceholdersAreNotReportedAsUnresolved() {
        assertThat(Variables.unresolved("table = \"ods_${table_name}\", keys = [\"${primary_key}\"], x = ${typo}"))
                .containsExactly("typo");
    }
}
