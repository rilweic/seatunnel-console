package com.dataintegration.console.server.job.wizard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dataintegration.console.server.job.HoconTemplate;
import com.dataintegration.console.server.job.ValidationSpec;
import com.dataintegration.console.server.job.Variables;
import com.dataintegration.console.server.job.wizard.WizardForm.DataMode;
import com.dataintegration.console.server.job.wizard.source.HttpSpec;
import com.dataintegration.console.server.job.wizard.WizardForm.NameCase;
import com.dataintegration.console.server.job.wizard.WizardForm.NameRule;
import com.dataintegration.console.server.job.wizard.WizardForm.SchemaMode;
import com.dataintegration.console.server.job.wizard.WizardForm.Sync;
import com.dataintegration.console.server.job.wizard.WizardForm.SyncMode;
import com.dataintegration.console.server.job.wizard.WizardForm.TableSpec;
import com.dataintegration.console.server.job.wizard.WizardForm.Target;
import com.dataintegration.console.server.job.wizard.WizardForm.Window;
import com.dataintegration.console.server.job.wizard.WizardForm.WriteMode;
import com.dataintegration.console.server.job.wizard.WizardGenerator.Endpoints;
import com.dataintegration.console.server.job.wizard.source.HttpSource;

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
                .contains("{ table_path = \"shop.orders\", replacements_with_regex = [{ replace_from = \"^.*$\", replace_to = \"ods_orders\" }] }")
                .contains("{ table_path = \"shop.users\", replacements_with_regex = [{ replace_from = \"^.*$\", replace_to = \"ods_users\" }] }")
                .contains("table = \"${table_name}\"")
                .contains("plugin_input = [\"tables_renamed\"]")
                .doesNotContain("FieldRename")
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
                .contains("replace_to = \"orders_copy\"")
                .contains("table = \"public.${table_name}\"")
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
    void continuousWindowReadsTheRunsSyncWindowOnBothSides() {
        WizardGenerator.Generated g = WizardGenerator.generate(
                form(List.of(new TableSpec("a", null, null, null)),
                        target("ods", DataMode.KEEP, WriteMode.UPSERT), new Sync(SyncMode.INCREMENTAL, Window.CONTINUOUS, "updated_at", null), true),
                MYSQL_TO_MYSQL);
        assertThat(g.hocon()).contains("`updated_at` >= '${system.window.start}' and `updated_at` < '${system.window.end}'");
        assertThat(Variables.usesWindow(List.of(g.hocon()))).isTrue();
        assertThat(g.validation().tables().get(0).target().sql())
                .endsWith("where `updated_at` >= '${system.window.start}' and `updated_at` < '${system.window.end}'");
        Map<String, String> vars = Map.of(Variables.WINDOW_START, "2026-10-26 10:00:00", Variables.WINDOW_END, "2026-10-26 10:05:00");
        assertThat(Variables.render(g.hocon(), vars, LocalDateTime.of(2026, 10, 26, 10, 6)))
                .contains("'2026-10-26 10:00:00'").contains("'2026-10-26 10:05:00'");
        parse(g.hocon());
    }

    @Test
    void elasticsearchTargetCountsIndicesAfterClearingAndUsesPrimaryKeyPlaceholder() {
        WizardGenerator.Generated g = WizardGenerator.generate(
                form(List.of(new TableSpec("orders", null, null, null), new TableSpec("users", null, null, null)),
                        new Target("es-ds", null, null, "app_", null, SchemaMode.CREATE, DataMode.CLEAR, WriteMode.UPSERT), FULL, true),
                PG_TO_ES);

        assertThat(g.hocon())
                .contains("{ table_path = \"app.shop.orders\" }")
                .contains("{ table_path = \"app.shop.orders\", replacements_with_regex = [{ replace_from = \"^.*$\", replace_to = \"app_orders\" }] }")
                .contains("index = \"${table_name}\"")
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
    void tableNamesFollowTheRuleUnlessSetByHand() {
        Target t = new Target("dst-ds", "ods", null, null, null, SchemaMode.CREATE, DataMode.KEEP, WriteMode.UPSERT,
                new NameRule("tb_", "", NameCase.UPPER, "ods_", "_d"));
        WizardForm f = form(List.of(new TableSpec("tb_orders", null, null, null),
                        new TableSpec("tb_users", null, null, null, "dim_user", null, null),
                        new TableSpec("ODS_X_D", null, null, null)),
                t, FULL, false);
        assertThat(f.source().tables()).extracting(table -> WizardGenerator.targetName(f, table, false))
                .containsExactly("ods_ORDERS_d", "dim_user", "ods_ODS_X_D_d");
        // Elasticsearch index names are lower case whatever the rule says
        assertThat(WizardGenerator.targetName(f, f.source().tables().get(0), true)).isEqualTo("ods_orders_d");
    }

    @Test
    void tableThatKeepsItsNameNeedsNoRename() {
        Target t = new Target("dst-ds", "ods", null, null, null, SchemaMode.CREATE, DataMode.KEEP, WriteMode.UPSERT, null);
        String hocon = WizardGenerator.generate(form(List.of(new TableSpec("orders", null, null, null)), t, FULL, false), MYSQL_TO_MYSQL).hocon();
        assertThat(hocon).doesNotContain("transform").contains("table = \"${table_name}\"").contains("plugin_input = [\"source\"]");
        parse(hocon);
    }

    @Test
    void fieldsAreRenamedPerTableByHandAndByRule() {
        Target t = new Target("dst-ds", "ods", null, null, null, SchemaMode.CREATE, DataMode.CLEAR, WriteMode.UPSERT, null);
        WizardForm f = new WizardForm(
                new WizardForm.Source("src-ds", "shop", List.of(
                        new TableSpec("orders", List.of("id", "amount", "updated_at"), null, null, null, Map.of("amount", "total"), null),
                        new TableSpec("users", null, null, null, null, null, new NameRule(null, null, NameCase.UPPER, null, null)),
                        new TableSpec("logs", null, null, null))),
                t, new Sync(SyncMode.INCREMENTAL, Window.DAY, "updated_at", null), 1, true, null, null, null);
        Endpoints endpoints = new Endpoints("MYSQL", null, "MYSQL", null, Map.of("users", List.of("id", "name", "updated_at")));
        WizardGenerator.Generated g = WizardGenerator.generate(f, endpoints);

        assertThat(g.hocon())
                .contains("FieldRename {\n    plugin_input = [\"source\"]\n    plugin_output = \"fields_renamed\"")
                .contains("{ table_path = \"shop.orders\", specific = [\n          { field_name = \"amount\", target_name = \"total\" }\n        ] }")
                .contains("{ field_name = \"id\", target_name = \"ID\" }")
                .contains("{ field_name = \"name\", target_name = \"NAME\" }")
                .doesNotContain("shop.logs\", specific")
                .doesNotContain("TableRename")
                .contains("plugin_input = [\"fields_renamed\"]")
                // source queries keep the source names
                .contains("select `id`, `amount`, `updated_at` from `shop`.`orders`");
        parse(g.hocon());
        // the target side of an incremental check counts on the renamed field
        assertThat(g.validation().tables()).filteredOn(c -> c.name().equals("users")).singleElement()
                .satisfies(c -> assertThat(c.target().sql()).contains("`UPDATED_AT` >= "));
    }

    @Test
    void formsSavedBeforeNameRulesKeepTheirNames() {
        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        WizardForm old = mapper.readValue("""
                {"source":{"datasourceId":"s","namespace":"shop","tables":[{"name":"orders","columns":null,"filter":null,"incrementalField":null}]},
                 "target":{"datasourceId":"d","namespace":"ods","tableName":"orders_copy","prefix":null,"suffix":null,
                           "schemaMode":"CREATE","dataMode":"KEEP","writeMode":"UPSERT"},
                 "sync":{"mode":"FULL"},"parallelism":1,"validation":false}
                """, WizardForm.class);
        assertThat(WizardGenerator.targetName(old, old.source().tables().get(0), false)).isEqualTo("orders_copy");
        assertThat(WizardGenerator.targetField(old, old.source().tables().get(0), "id")).isEqualTo("id");

        WizardForm multi = form(List.of(new TableSpec("a", null, null, null), new TableSpec("b", null, null, null)),
                new Target("d", "ods", null, "ods_", "_h", SchemaMode.CREATE, DataMode.KEEP, WriteMode.UPSERT), FULL, false);
        assertThat(multi.source().tables()).extracting(table -> WizardGenerator.targetName(multi, table, false))
                .containsExactly("ods_a_h", "ods_b_h");

        WizardForm roundTrip = mapper.readValue(mapper.writeValueAsString(new WizardForm(old.source(), old.target(), old.sync(), 1, false,
                null, null, new NameRule("x", "y", NameCase.LOWER, "p_", null))), WizardForm.class);
        assertThat(roundTrip.fieldRule().apply("AxB")).isEqualTo("p_ayb");
    }

    @Test
    void seatunnelPlaceholdersAreNotReportedAsUnresolved() {
        assertThat(Variables.unresolved("table = \"ods_${table_name}\", keys = [\"${primary_key}\"], x = ${typo}"))
                .containsExactly("typo");
    }

    private static WizardForm httpForm(HttpSpec http, TableSpec table, boolean validation) {
        return httpForm(http, table, validation, null);
    }

    private static WizardForm httpForm(HttpSpec http, TableSpec table, boolean validation, Map<String, String> sourceOptions) {
        return new WizardForm(
                WizardForm.Source.withSpec("http-ds", null, List.of(table), http),
                target("ods", DataMode.CLEAR, WriteMode.UPSERT),
                new Sync(SyncMode.INCREMENTAL, null, null, null),
                8,
                validation,
                sourceOptions,
                null);
    }

    @Test
    void httpSourceUsesFinalTableNameFieldsAndPrimaryKeyWithoutDatabaseValidation() {
        HttpSpec http = new HttpSpec(
                "/orders",
                HttpSpec.Method.GET,
                Map.of("tenant", "acme"),
                null,
                new HttpSpec.Paging(HttpSpec.PagingMode.NONE, null, null, null, null, null, null, null, null),
                "$.data.list",
                List.of(new HttpSpec.Field("id", "bigint", null), new HttpSpec.Field("name", "string", "$.name")),
                List.of("id"));

        WizardGenerator.Generated g = WizardGenerator.generate(httpForm(http, new TableSpec("orders", null, null, null), true),
                new Endpoints("HTTP", null, "MYSQL", null));

        assertThat(g.hocon())
                .contains("parallelism = 1")
                .contains("source {\n  Http {")
                .contains("datasource_id = \"http-ds\"")
                .contains("url = \"/orders\"")
                .contains("method = \"GET\"")
                .contains("tenant = \"acme\"")
                .contains("format = \"json\"")
                .doesNotContain("content_field")
                .contains("json_field = {")
                .contains("id = \"$.data.list[*].id\"")
                .contains("name = \"$.data.list[*].name\"")
                .contains("table = \"ods_orders\"")
                // a primary key column must be NOT NULL for the target table to be created
                .contains("{ name = \"id\", type = bigint, nullable = false }")
                .contains("{ name = \"name\", type = string, nullable = true }")
                .doesNotContain("fields {")
                .contains("primaryKey {")
                .contains("name = \"http_pk\"")
                .contains("columnNames = [\"id\"]")
                .contains("enable_upsert = true")
                .doesNotContain("request_interval_ms")
                .doesNotContain("table_list")
                .doesNotContain("pageing")
                .doesNotContain("TableRename")
                .doesNotContain("incremental")
                .contains("plugin_input = [\"source\"]");
        parse(g.hocon());
        assertThat(g.validation()).isNull();
        assertThat(g.notes()).anyMatch(n -> n.contains("接口无法计数"));
    }

    @Test
    void httpPageNumberPagingPopulatesParamsOrJsonBody() {
        HttpSpec.Paging paramsPaging = new HttpSpec.Paging(
                HttpSpec.PagingMode.PAGE_NUMBER, "pageNo", HttpSpec.PageLocation.PARAMS, 3L, "size", 50, 4L, null, null);
        HttpSpec paramsHttp = new HttpSpec("/orders", HttpSpec.Method.GET, Map.of("tenant", "acme"), null, paramsPaging,
                null, List.of(new HttpSpec.Field("id", "bigint", null)), null);
        String paramsHocon = WizardGenerator.generate(httpForm(paramsHttp, new TableSpec("orders", null, null, null), false),
                new Endpoints("HTTP", null, "MYSQL", null)).hocon();
        assertThat(paramsHocon)
                .contains("tenant = \"acme\"")
                .contains("pageNo = \"${page}\"")
                .contains("keep_page_param_as_http_param = true")
                .contains("size = \"50\"")
                .contains("pageing = {")
                .contains("page_field = \"pageNo\"")
                .contains("start_page_number = 3")
                .contains("batch_size = 50")
                .contains("total_page_size = 4")
                .contains("use_placeholder_replacement = true");
        parse(paramsHocon);

        HttpSpec.Paging bodyPaging = new HttpSpec.Paging(
                HttpSpec.PagingMode.PAGE_NUMBER, "pageNo", HttpSpec.PageLocation.BODY, 0L, "size", 25, null, null, null);
        HttpSpec bodyHttp = new HttpSpec("/orders/search", HttpSpec.Method.POST, null, "{\"filter\":\"active\"}", bodyPaging,
                "$", List.of(new HttpSpec.Field("id", "bigint", null)), null);
        String bodyHocon = WizardGenerator.generate(httpForm(bodyHttp, new TableSpec("orders", null, null, null), false),
                new Endpoints("HTTP", null, "MYSQL", null)).hocon();
        assertThat(bodyHocon)
                .contains("body = \"{\\\"filter\\\":\\\"active\\\",\\\"pageNo\\\":\\\"${page}\\\",\\\"size\\\":25}\"")
                .contains("start_page_number = 0")
                .contains("batch_size = 25")
                .doesNotContain("total_page_size");
        parse(bodyHocon);
    }

    @Test
    void httpCursorPagingUsesParamsAndKeepsTheCursorParameter() {
        HttpSpec.Paging paging = new HttpSpec.Paging(
                HttpSpec.PagingMode.CURSOR, null, HttpSpec.PageLocation.PARAMS, null, null, null, null,
                "next", "$.paging.cursors.next");
        HttpSpec http = new HttpSpec("/orders", HttpSpec.Method.GET, Map.of("tenant", "acme"), null, paging,
                null, List.of(new HttpSpec.Field("id", "bigint", null)), null);

        String hocon = WizardGenerator.generate(httpForm(http, new TableSpec("orders", null, null, null), false),
                new Endpoints("HTTP", null, "MYSQL", null)).hocon();

        assertThat(hocon)
                .doesNotContain("next = \"${cursor}\"")
                .contains("keep_page_param_as_http_param = true")
                .contains("page_type = \"Cursor\"")
                .contains("cursor_field = \"next\"")
                .contains("cursor_response_field = \"$.paging.cursors.next\"")
                .contains("use_placeholder_replacement = true")
                .doesNotContain("page_field")
                .doesNotContain("batch_size");
        parse(hocon);
    }

    @Test
    void httpStringPrimaryKeyGetsALength() {
        HttpSpec http = new HttpSpec("/orders", HttpSpec.Method.GET, null, null, null, "$",
                List.of(new HttpSpec.Field("code", "STRING", null), new HttpSpec.Field("note", "STRING", null)), List.of("code"));
        String hocon = WizardGenerator.generate(httpForm(http, new TableSpec("orders", null, null, null), false),
                new Endpoints("HTTP", null, "MYSQL", null)).hocon();
        assertThat(hocon)
                .contains("{ name = \"code\", type = STRING, columnLength = 255, nullable = false }")
                .contains("{ name = \"note\", type = STRING, nullable = true }");
        parse(hocon);
    }

    @Test
    void httpCursorResponseFieldMustBeAValidPath() {
        for (String field : List.of("nextCursor", "$.paging['next-cursor']")) {
            HttpSpec.Paging paging = new HttpSpec.Paging(HttpSpec.PagingMode.CURSOR, null, HttpSpec.PageLocation.PARAMS, null, null, null, null,
                    "cursor", field);
            HttpSpec http = new HttpSpec("/orders", HttpSpec.Method.GET, null, null, paging, "$",
                    List.of(new HttpSpec.Field("id", "bigint", null)), null);
            String hocon = WizardGenerator.generate(httpForm(http, new TableSpec("orders", null, null, null), false),
                    new Endpoints("HTTP", null, "MYSQL", null)).hocon();
            parse(hocon);
        }
        for (String field : List.of("$.paging[", "$..next", "$.paging next")) {
            HttpSpec.Paging paging = new HttpSpec.Paging(HttpSpec.PagingMode.CURSOR, null, HttpSpec.PageLocation.PARAMS, null, null, null, null,
                    "cursor", field);
            HttpSpec http = new HttpSpec("/orders", HttpSpec.Method.GET, null, null, paging, "$",
                    List.of(new HttpSpec.Field("id", "bigint", null)), null);
            assertThatThrownBy(() -> WizardGenerator.generate(httpForm(http, new TableSpec("orders", null, null, null), false),
                    new Endpoints("HTTP", null, "MYSQL", null)))
                    .hasMessageContaining("游标响应字段的路径不合法");
        }
    }

    @Test
    void previewAndGeneratedConfigShareTheRequestAssembly() {
        HttpSpec.Paging params = new HttpSpec.Paging(HttpSpec.PagingMode.PAGE_NUMBER, " pageNo ", HttpSpec.PageLocation.PARAMS, 3L, "size", 50,
                null, null, null);
        assertThat(HttpSource.requestParts(Map.of("tenant", "acme"), null, params, null).params())
                .containsExactly(Map.entry("tenant", "acme"), Map.entry("pageNo", "${page}"), Map.entry("size", "50"));
        assertThat(HttpSource.requestParts(Map.of("tenant", "acme"), null, params, "3").params())
                .containsExactly(Map.entry("tenant", "acme"), Map.entry("pageNo", "3"), Map.entry("size", "50"));

        HttpSpec.Paging body = new HttpSpec.Paging(HttpSpec.PagingMode.PAGE_NUMBER, "pageNo", HttpSpec.PageLocation.BODY, 1L, "size", 25,
                null, null, null);
        assertThat(HttpSource.requestParts(null, "{\"q\":\"x\"}", body, "1").body())
                .isEqualTo("{\"q\":\"x\",\"pageNo\":\"1\",\"size\":25}");

        HttpSpec.Paging cursor = new HttpSpec.Paging(HttpSpec.PagingMode.CURSOR, null, HttpSpec.PageLocation.PARAMS, null, null, null, null,
                "cursor", "next");
        assertThat(HttpSource.requestParts(Map.of("cursor", "${cursor}", "tenant", "acme"), null, cursor, null).params())
                .containsOnlyKeys("tenant");
    }

    @Test
    void advancedOptionsCannotOverrideGeneratedOptions() {
        HttpSpec http = new HttpSpec("/orders", HttpSpec.Method.GET, null, null, null, "$",
                List.of(new HttpSpec.Field("id", "bigint", null)), null);
        WizardForm base = httpForm(http, new TableSpec("orders", null, null, null), false);
        // written after the generated ones they would win, e.g. turn upsert back on without a primary key
        for (String option : List.of("enable_upsert", "primary_keys", "data_save_mode", "table")) {
            WizardForm f = new WizardForm(base.source(), base.target(), base.sync(), 1, false, null, Map.of(option, "x"));
            assertThatThrownBy(() -> WizardGenerator.generate(f, new Endpoints("HTTP", null, "MYSQL", null)))
                    .hasMessageContaining("由向导生成的 " + option);
        }
        WizardForm ordinarySink = new WizardForm(base.source(), base.target(), base.sync(), 1, false, null,
                Map.of("batch_size", "500"));
        assertThat(WizardGenerator.generate(ordinarySink, new Endpoints("HTTP", null, "MYSQL", null)).hocon())
                .contains("batch_size = 500");

        WizardForm jdbc = form(List.of(new TableSpec("orders", null, null, null)), target("ods", DataMode.KEEP, WriteMode.UPSERT),
                FULL, false);
        WizardForm jdbcQuery = new WizardForm(jdbc.source(), jdbc.target(), jdbc.sync(), 1, false,
                Map.of("query", "select 1"), null);
        assertThatThrownBy(() -> WizardGenerator.generate(jdbcQuery, MYSQL_TO_MYSQL))
                .hasMessageContaining("由向导生成的 query");
        WizardForm jdbcFetch = new WizardForm(jdbc.source(), jdbc.target(), jdbc.sync(), 1, false,
                Map.of("fetch_size", "1000"), null);
        assertThat(WizardGenerator.generate(jdbcFetch, MYSQL_TO_MYSQL).hocon()).contains("fetch_size = 1000");
    }

    @Test
    void httpSchemaAndNestedFieldsArePrunedWhileFieldTransformsUseTheFinalTableName() {
        HttpSpec http = new HttpSpec("/orders", HttpSpec.Method.GET, null, null, null, null,
                List.of(
                        new HttpSpec.Field("id", "bigint", "$.id"),
                        new HttpSpec.Field("name", "string", "$.data[*].name"),
                        new HttpSpec.Field("secret", "string", "$.data[*].secret")),
                null);
        TableSpec table = new TableSpec("orders", List.of("name", "id"), null, null, "orders_final",
                Map.of("id", "order_id"), null);

        String hocon = WizardGenerator.generate(httpForm(http, table, false),
                new Endpoints("HTTP", null, "MYSQL", null)).hocon();

        assertThat(hocon)
                .contains("table = \"orders_final\"")
                .contains("name = string")
                .contains("id = bigint")
                .contains("name = \"$.data[*].name\"")
                .contains("id = \"$[*].id\"")
                .doesNotContain("$[*].order_id")
                .doesNotContain("secret")
                .contains("{ table_path = \"orders_final\", specific = [")
                .contains("{ field_name = \"id\", target_name = \"order_id\" }")
                .doesNotContain("TableRename");
        parse(hocon);
    }

    @Test
    void httpDecimalHasPrecisionAndFieldTypesAreValidated() {
        HttpSpec decimal = new HttpSpec("/orders", HttpSpec.Method.GET, null, null, null, "$.data",
                List.of(new HttpSpec.Field("amount", "DECIMAL", "$.amount", 20, 6)), null);
        String hocon = WizardGenerator.generate(httpForm(decimal, new TableSpec("orders", null, null, null), false),
                new Endpoints("HTTP", null, "MYSQL", null)).hocon();
        assertThat(hocon).contains("amount = \"decimal(20,6)\"");
        parse(hocon);

        HttpSpec defaults = new HttpSpec("/orders", HttpSpec.Method.GET, null, null, null, "$",
                List.of(new HttpSpec.Field("amount", "decimal", null)), null);
        assertThat(WizardGenerator.generate(httpForm(defaults, new TableSpec("orders", null, null, null), false),
                new Endpoints("HTTP", null, "MYSQL", null)).hocon()).contains("amount = \"decimal(18,2)\"");

        for (HttpSpec.Field invalid : List.of(
                new HttpSpec.Field("id", "INT", null),
                new HttpSpec.Field("amount", "DECIMAL", null, 0, 2),
                new HttpSpec.Field("amount", "DECIMAL", null, 10, 11))) {
            HttpSpec invalidHttp = new HttpSpec("/orders", HttpSpec.Method.GET, null, null, null, "$", List.of(invalid), null);
            assertThatThrownBy(() -> WizardGenerator.generate(
                    httpForm(invalidHttp, new TableSpec("orders", null, null, null), false),
                    new Endpoints("HTTP", null, "MYSQL", null)))
                    .isInstanceOf(com.dataintegration.console.common.ConsoleException.class);
        }
    }

    @Test
    void httpAdvancedOptionsAreRestrictedToSupportedKeys() {
        HttpSpec http = new HttpSpec("/orders", HttpSpec.Method.GET, null, null, null, "$",
                List.of(new HttpSpec.Field("id", "bigint", null)), null);
        TableSpec table = new TableSpec("orders", null, null, null);
        String hocon = WizardGenerator.generate(httpForm(http, table, false, Map.of(
                        "retry", "3",
                        "retry_backoff_multiplier_ms", "100",
                        "retry_backoff_max_ms", "10000",
                        "json_filed_missed_return_null", "false")),
                new Endpoints("HTTP", null, "MYSQL", null)).hocon();
        assertThat(hocon)
                .contains("retry = 3")
                .contains("retry_backoff_multiplier_ms = 100")
                .contains("retry_backoff_max_ms = 10000")
                .contains("json_filed_missed_return_null = false");
        parse(hocon);

        for (String option : List.of("format", "schema", "pageing", "request_interval_ms")) {
            assertThatThrownBy(() -> WizardGenerator.generate(
                    httpForm(http, table, false, Map.of(option, "invalid")),
                    new Endpoints("HTTP", null, "MYSQL", null)))
                    .hasMessageContaining("不支持的 HTTP 高级参数");
        }
    }

    @Test
    void httpWithoutPrimaryKeysAlwaysAppends() {
        HttpSpec http = new HttpSpec("/orders", HttpSpec.Method.GET, null, null, null, "$",
                List.of(new HttpSpec.Field("id", "bigint", null)), null);
        TableSpec table = new TableSpec("orders", null, null, null);
        String jdbc = WizardGenerator.generate(httpForm(http, table, false),
                new Endpoints("HTTP", null, "MYSQL", null)).hocon();
        assertThat(jdbc).contains("enable_upsert = false");

        WizardForm esForm = new WizardForm(
                WizardForm.Source.withSpec("http-ds", null, List.of(table), http),
                new Target("es-ds", null, null, null, null, SchemaMode.CREATE, DataMode.CLEAR, WriteMode.UPSERT),
                new Sync(SyncMode.INCREMENTAL, null, null, null), 1, false, null, null);
        String es = WizardGenerator.generate(esForm, new Endpoints("HTTP", null, "ELASTICSEARCH", null)).hocon();
        assertThat(es).doesNotContain("primary_keys");
        parse(jdbc);
        parse(es);
    }

    @Test
    void httpShapeValidationRejectsMissingFieldsUnknownKeysAndCursorBody() {
        Endpoints endpoints = new Endpoints("HTTP", null, "MYSQL", null);
        TableSpec table = new TableSpec("orders", null, null, null);
        HttpSpec missingFields = new HttpSpec("/orders", HttpSpec.Method.GET, null, null, null, null, List.of(), null);
        assertThatThrownBy(() -> WizardGenerator.generate(httpForm(missingFields, table, false), endpoints))
                .hasMessageContaining("字段");

        HttpSpec unknownKey = new HttpSpec("/orders", HttpSpec.Method.GET, null, null, null, null,
                List.of(new HttpSpec.Field("id", "bigint", null)), List.of("missing"));
        assertThatThrownBy(() -> WizardGenerator.generate(httpForm(unknownKey, table, false), endpoints))
                .hasMessageContaining("主键");

        HttpSpec relativePath = new HttpSpec("/orders", HttpSpec.Method.GET, null, null, null, "$",
                List.of(new HttpSpec.Field("id", "bigint", "bad path")), null);
        assertThatThrownBy(() -> WizardGenerator.generate(httpForm(relativePath, table, false), endpoints))
                .hasMessageContaining("jsonPath 必须以 $ 开头");

        HttpSpec.Paging cursorBody = new HttpSpec.Paging(HttpSpec.PagingMode.CURSOR, null, HttpSpec.PageLocation.BODY, null, null, null,
                null, "cursor", "$.next");
        HttpSpec invalidCursor = new HttpSpec("/orders", HttpSpec.Method.GET, null, null, cursorBody, null,
                List.of(new HttpSpec.Field("id", "bigint", null)), null);
        assertThatThrownBy(() -> WizardGenerator.generate(httpForm(invalidCursor, table, false), endpoints))
                .hasMessageContaining("PARAMS");
    }
}
