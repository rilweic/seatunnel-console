package com.dataintegration.console.server.job.wizard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dataintegration.console.server.job.HoconTemplate;
import com.dataintegration.console.server.job.wizard.WizardForm.FieldProcess;
import com.dataintegration.console.server.job.wizard.WizardForm.Mask;
import com.dataintegration.console.server.job.wizard.WizardForm.MaskPreset;
import com.dataintegration.console.server.job.wizard.WizardForm.SchemaMode;
import com.dataintegration.console.server.job.wizard.WizardForm.Startup;
import com.dataintegration.console.server.job.wizard.WizardForm.Stream;
import com.dataintegration.console.server.job.wizard.WizardForm.TableSpec;
import com.dataintegration.console.server.job.wizard.WizardForm.Target;
import com.dataintegration.console.server.job.wizard.WizardGenerator.Endpoints;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** Real-time wizard jobs (docs/design.md §12 P2); the full output is locked by the wizard-golden scenarios. */
class StreamWizardGeneratorTest {

    private static final Endpoints MYSQL_TO_MYSQL = new Endpoints("MYSQL", null, "MYSQL", null);
    private static final Endpoints MYSQL_TO_ES = new Endpoints("MYSQL", null, "ELASTICSEARCH", null);
    private static final Endpoints PG_TO_MYSQL = new Endpoints("POSTGRESQL", "app", "MYSQL", null);
    private static final Stream ALL_CHANGES = new Stream(Startup.INITIAL, null, true, true, false, null);

    private static WizardForm form(List<TableSpec> tables, Stream stream) {
        return new WizardForm(new WizardForm.Source("src-ds", "shop", tables),
                new Target("dst-ds", "ods", null, null, null, SchemaMode.CREATE, null, null), null, 1, true, null, null, null, stream);
    }

    private static TableSpec table(String name) {
        return new TableSpec(name, null, null, null);
    }

    @Test
    void readsWithTheCdcSourceAsAStreamingJobThatKeepsCheckpointsAfterAForcedStop() {
        WizardGenerator.Generated g = WizardGenerator.generate(form(List.of(table("orders")), ALL_CHANGES), MYSQL_TO_MYSQL);

        HoconTemplate template = HoconTemplate.parse(g.hocon());
        assertThat(template.jobMode()).isEqualTo("STREAMING");
        assertThat(template.blocks("source")).singleElement().satisfies(b -> assertThat(b.connector()).isEqualTo("MySQL-CDC"));
        assertThat(g.hocon())
                .contains("checkpoint.retain-after-job-cancelled = true")
                .contains("enable_upsert = true")
                .contains("data_save_mode = \"APPEND_DATA\"");
        assertThat(g.validation()).isNull();
    }

    @Test
    void renamesAndTypeChangesAreAlwaysPassedOnButDropsOnlyWhenAskedAndTheTargetCanApplyThem() {
        String withDrops = WizardGenerator.generate(form(List.of(table("orders")), ALL_CHANGES), MYSQL_TO_MYSQL).hocon();
        String keepColumns = WizardGenerator.generate(
                form(List.of(table("orders")), new Stream(Startup.INITIAL, null, true, false, false, null)), MYSQL_TO_MYSQL).hocon();
        String toEs = WizardGenerator.generate(form(List.of(table("orders")), ALL_CHANGES), MYSQL_TO_ES).hocon();
        String fromPg = WizardGenerator.generate(form(List.of(table("orders")), ALL_CHANGES), PG_TO_MYSQL).hocon();

        assertThat(withDrops).contains("schema-changes.include = [\"add.column\", \"modify.column\", \"change.column\", \"drop.column\"]");
        assertThat(keepColumns).contains("schema-changes.include = [\"add.column\", \"modify.column\", \"change.column\"]");
        assertThat(toEs).contains("schema-changes.include = [\"add.column\", \"modify.column\", \"change.column\"]");
        assertThat(fromPg).contains("schema-changes.include = [\"add.column\"]").contains("decoding.plugin.name = \"pgoutput\"");
    }

    @Test
    void skippingDeletesComesLastAfterEveryOtherTransform() {
        TableSpec renamed = new TableSpec("orders", null, null, null, "ods_orders", null, null);
        String hocon = WizardGenerator.generate(
                form(List.of(renamed), new Stream(Startup.LATEST, null, false, false, true, null)), MYSQL_TO_MYSQL).hocon();

        assertThat(hocon.indexOf("FilterRowKind")).isGreaterThan(hocon.indexOf("TableRename"));
        assertThat(hocon).contains("plugin_input = [\"deletes_skipped\"]");
    }

    @Test
    void refusesWhatARealTimeJobCannotDo() {
        TableSpec filtered = new TableSpec("orders", null, "status = 1", null);
        TableSpec masked = new TableSpec("orders", null, null, null, null, null, null,
                Map.of("phone", new FieldProcess(null, new Mask(MaskPreset.PHONE, null, null, null), null, null)), null, null, null);

        assertThatThrownBy(() -> WizardGenerator.generate(form(List.of(filtered), ALL_CHANGES), MYSQL_TO_MYSQL))
                .hasMessageContaining("实时同步不支持过滤条件");
        assertThatThrownBy(() -> WizardGenerator.generate(form(List.of(masked), ALL_CHANGES),
                        new Endpoints("MYSQL", null, "MYSQL", null, Map.of("orders", List.of("id", "phone")))))
                .hasMessageContaining("不能使用字段处理");
        assertThatThrownBy(() -> WizardGenerator.generate(
                        form(List.of(table("orders")), new Stream(Startup.TIMESTAMP, 1L, false, false, false, null)), PG_TO_MYSQL))
                .hasMessageContaining("PostgreSQL 不支持从某个时间点开始");
        assertThatThrownBy(() -> WizardGenerator.generate(
                        form(List.of(table("orders")), new Stream(Startup.INITIAL, null, false, true, false, null)), MYSQL_TO_MYSQL))
                .hasMessageContaining("同步删列需要先打开");
        assertThatThrownBy(() -> WizardGenerator.generate(form(List.of(table("orders")), ALL_CHANGES),
                        new Endpoints("HTTP", null, "MYSQL", null)))
                .hasMessageContaining("实时同步源端只支持");
    }

    @Test
    void fieldProcessingIsFineWithoutSchemaChanges() {
        TableSpec masked = new TableSpec("orders", null, null, null, null, null, null,
                Map.of("phone", new FieldProcess(null, new Mask(MaskPreset.PHONE, null, null, null), null, null)), null, null, null);

        String hocon = WizardGenerator.generate(form(List.of(masked), new Stream(Startup.INITIAL, null, false, false, false, null)),
                new Endpoints("MYSQL", null, "MYSQL", null, Map.of("orders", List.of("id", "phone")))).hocon();

        assertThat(hocon).contains("Sql {").doesNotContain("schema-changes");
    }
}
