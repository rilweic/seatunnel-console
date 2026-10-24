package com.dataintegration.console.server.job.wizard;

import static org.assertj.core.api.Assertions.assertThat;

import com.dataintegration.console.server.job.HoconTemplate;
import com.dataintegration.console.server.job.Variables;
import com.dataintegration.console.server.job.wizard.WizardForm.AddKind;
import com.dataintegration.console.server.job.wizard.WizardForm.AddedField;
import com.dataintegration.console.server.job.wizard.WizardForm.Cast;
import com.dataintegration.console.server.job.wizard.WizardForm.DataMode;
import com.dataintegration.console.server.job.wizard.WizardForm.Extract;
import com.dataintegration.console.server.job.wizard.WizardForm.ExtractKind;
import com.dataintegration.console.server.job.wizard.WizardForm.ExtractOutput;
import com.dataintegration.console.server.job.wizard.WizardForm.FieldProcess;
import com.dataintegration.console.server.job.wizard.WizardForm.FieldType;
import com.dataintegration.console.server.job.wizard.WizardForm.Mask;
import com.dataintegration.console.server.job.wizard.WizardForm.MaskPreset;
import com.dataintegration.console.server.job.wizard.WizardForm.Replace;
import com.dataintegration.console.server.job.wizard.WizardForm.SchemaMode;
import com.dataintegration.console.server.job.wizard.WizardForm.Sync;
import com.dataintegration.console.server.job.wizard.WizardForm.SyncMode;
import com.dataintegration.console.server.job.wizard.WizardForm.TableSpec;
import com.dataintegration.console.server.job.wizard.WizardForm.Target;
import com.dataintegration.console.server.job.wizard.WizardForm.WriteMode;
import com.dataintegration.console.server.job.wizard.WizardGenerator.Endpoints;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

class FieldProcessingTest {

    private static AddedField added(String name, AddKind kind, List<String> fields, String value, String op, String format, FieldType type) {
        return new AddedField(name, kind, fields, value, op, null, 4, format, type, null, null);
    }

    @Test
    void masksKeepTheirEndsAndLeaveShortValues() {
        assertThat(FieldProcessing.mask("phone", new Mask(MaskPreset.PHONE, null, null, null)))
                .isEqualTo("CASE WHEN CHAR_LENGTH(TRY_CAST(phone AS STRING)) > 7 THEN CONCAT(LEFT(TRY_CAST(phone AS STRING), 3), "
                        + "REPEAT('*', CHAR_LENGTH(TRY_CAST(phone AS STRING)) - 7), RIGHT(TRY_CAST(phone AS STRING), 4)) "
                        + "ELSE TRY_CAST(phone AS STRING) END");
        assertThat(FieldProcessing.mask("card", new Mask(MaskPreset.BANK_CARD, null, null, "#")))
                .startsWith("CASE WHEN CHAR_LENGTH(TRY_CAST(card AS STRING)) > 4 THEN CONCAT(REPEAT('#',")
                .doesNotContain("LEFT(");
        assertThat(FieldProcessing.mask("mail", new Mask(MaskPreset.EMAIL, null, null, null)))
                .contains("LOCATE('@', TRY_CAST(mail AS STRING)) > 1").contains("'***'");
    }

    @Test
    void namesAreQuotedOnlyWhenTheyMustBe() {
        assertThat(FieldProcessing.ident("order_id")).isEqualTo("order_id");
        assertThat(FieldProcessing.ident("order")).isEqualTo("`order`");
        assertThat(FieldProcessing.ident("订单号")).isEqualTo("`订单号`");
        assertThat(FieldProcessing.lit("it's")).isEqualTo("'it''s'");
    }

    @Test
    void sqlSelectsEveryTargetFieldInOrder() {
        TableSpec table = new TableSpec("users", null, null, null, null, null, null,
                Map.of("name", new FieldProcess(new Replace(" ", "", false), null, null, null),
                        "phone", new FieldProcess(null, new Mask(MaskPreset.PHONE, null, null, null), null, null),
                        "age", new FieldProcess(null, null, new Cast(FieldType.BIGINT, null, null), null)),
                List.of(new Extract("profile", ExtractKind.JSON, null,
                        List.of(new ExtractOutput("city", "$.address.city", FieldType.STRING, null, null),
                                new ExtractOutput("score", "$.score", FieldType.DECIMAL, 10, 1)), true)),
                List.of(added("etl_time", AddKind.CONSTANT, null, "${system.datetime}", null, "yyyyMMddHHmmss", FieldType.TIMESTAMP),
                        added("full_name", AddKind.CONCAT, List.of("first", "last"), " ", null, null, null),
                        added("name_head", AddKind.SUBSTRING, List.of("name"), null, "LEFT", null, null)),
                null);
        String sql = FieldProcessing.sqlQuery(table, List.of("id", "name", "phone", "age", "profile", "first", "last"));

        assertThat(sql)
                .startsWith("select id, REPLACE(TRY_CAST(name AS STRING), ' ', '') as name, CASE WHEN CHAR_LENGTH(TRY_CAST(phone AS STRING)) > 7")
                .contains("as phone, TRY_CAST(age AS BIGINT) as age, first, last, city, TRY_CAST(score AS DECIMAL(10,1)) as score, ")
                .contains("PARSEDATETIME('${system.datetime}', 'yyyyMMddHHmmss') as etl_time")
                .contains("CONCAT_WS(' ', first, last) as full_name, LEFT(name, 4) as name_head from dual")
                .doesNotContain("profile");
        assertThat(FieldProcessing.outputFields(table, List.of("id", "profile")))
                .containsExactly("id", "city", "score", "etl_time", "full_name", "name_head");
    }

    @Test
    void generatesStagesPerTableThatPassThroughTheOthers() {
        TableSpec users = new TableSpec("users", List.of("id", "log", "tags", "note"), null, null, null, Map.of("note", "remark"), null,
                Map.of("note", new FieldProcess(new Replace("\\s+", " ", true), null, null, null)),
                List.of(new Extract("log", ExtractKind.REGEX, "^(\\w+) (\\d+)$",
                                List.of(new ExtractOutput("level", null, null, null, null), new ExtractOutput("code", null, FieldType.BIGINT, null, null)), false),
                        new Extract("tags", ExtractKind.SPLIT, ",",
                                List.of(new ExtractOutput("tag1", null, null, null, null), new ExtractOutput("tag2", null, null, null, null)), false),
                        new Extract("log", ExtractKind.SPLIT, " ", List.of(new ExtractOutput("word", null, null, null, null)), false)),
                List.of(added("uid", AddKind.UUID, null, null, null, null, null)),
                null);
        TableSpec plain = new TableSpec("orders", null, null, null);
        WizardForm form = new WizardForm(new WizardForm.Source("src", "shop", List.of(users, plain)),
                new Target("dst", "ods", null, null, null, SchemaMode.CREATE, DataMode.CLEAR, WriteMode.UPSERT, null),
                new Sync(SyncMode.FULL, null, null, null), 1, false, null, null, null);
        String hocon = WizardGenerator.generate(form, new Endpoints("MYSQL", null, "MYSQL", null)).hocon();

        assertThat(hocon)
                .doesNotContain("JsonPath")
                .contains("RegexExtract {\n    plugin_input = [\"source\"]\n    plugin_output = \"regex_extracted\"\n    table_match_regex = \"(?!)\"\n")
                .contains("{ table_path = \"shop.users\", source_field = \"log\", regex_pattern = \"^(\\\\w+) (\\\\d+)$\", output_fields = [\"level\", \"code\"] }")
                // two splits of one table run one after the other
                .contains("Split {\n    plugin_input = [\"regex_extracted\"]\n    plugin_output = \"split_extracted\"\n    table_match_regex = \"(?!)\"\n    rule_match_mode = \"ALL_MATCH\"")
                .contains("{ table_path = \"shop.users\", separator = \",\", split_field = \"tags\", output_fields = [\"tag1\", \"tag2\"] }")
                .contains("{ table_path = \"shop.users\", separator = \" \", split_field = \"log\", output_fields = [\"word\"] }")
                .contains("Sql {\n    plugin_input = [\"split_extracted\"]")
                .contains("select id, log, tags, REGEXP_REPLACE(TRY_CAST(note AS STRING), '\\\\s+', ' ') as note, level, TRY_CAST(code AS BIGINT) as code, tag1, tag2, word, UUID() as uid from dual")
                // renames run on the processed fields
                .contains("FieldRename {\n    plugin_input = [\"processed\"]")
                .contains("{ field_name = \"note\", target_name = \"remark\" }")
                .doesNotContain("shop.orders\", query")
                .contains("plugin_input = [\"fields_renamed\"]");
        HoconTemplate.parse(Variables.render(hocon, Map.of(), LocalDateTime.of(2026, 10, 25, 2, 0)));
    }
}
