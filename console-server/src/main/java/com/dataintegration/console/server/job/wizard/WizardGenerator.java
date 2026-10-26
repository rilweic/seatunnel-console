package com.dataintegration.console.server.job.wizard;

import static com.dataintegration.console.server.job.wizard.Hocon.invalid;
import static com.dataintegration.console.server.job.wizard.Hocon.notBlank;

import com.dataintegration.console.datasource.DataSourceCapabilities;
import com.dataintegration.console.datasource.jdbc.SqlDialect;
import com.dataintegration.console.server.job.ValidationSpec;
import com.dataintegration.console.server.job.Variables;
import com.dataintegration.console.server.job.ValidationSpec.Side;
import com.dataintegration.console.server.job.wizard.WizardForm.DataMode;
import com.dataintegration.console.server.job.wizard.WizardForm.NameRule;
import com.dataintegration.console.server.job.wizard.WizardForm.SchemaMode;
import com.dataintegration.console.server.job.wizard.WizardForm.SyncMode;
import com.dataintegration.console.server.job.wizard.WizardForm.TableSpec;
import com.dataintegration.console.server.job.wizard.WizardForm.Window;
import com.dataintegration.console.server.job.wizard.WizardForm.WriteMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a {@link WizardForm} into a SeaTunnel 3.0 job config and its per-table validation
 * (docs/design.md §7.2, §7.4–7.6). Pure: everything it needs about the datasources comes in
 * through {@link Endpoints}, so the output is fully determined by its inputs.
 *
 * <p>The source block comes from the {@link WizardSource} and the sink's own options from the
 * {@link WizardSink} of the datasource types (§7.14); this class only puts them together with what
 * every job shares. Target names are worked out here, table by table (§7.8): renamed fields go
 * through a {@code FieldRename} transform and renamed tables through a {@code TableRename}
 * transform, each configured per source table, and the sink addresses tables with SeaTunnel's
 * {@code ${table_name}} placeholder. Fields are not renamed with SQL aliases: the Jdbc source
 * matches query columns to the table's own by name, and a renamed key column would lose its
 * primary key.
 */
public final class WizardGenerator {

    /**
     * Options every sink writes itself or takes from the datasource, so advanced options may not set
     * them: written after the generated ones, they would win (mirrors {@code HANDLED} in JobWizard.vue).
     */
    private static final Set<String> GENERATED_SINK_OPTIONS = Set.of(
            "datasource_id", "plugin_input", "url", "driver", "username", "password", "hosts",
            "database", "table", "index", "generate_sink_sql", "enable_upsert", "primary_keys",
            "schema_save_mode", "data_save_mode", "tablePrefix", "tableSuffix");

    /**
     * @param sourceDatabase the source datasource's database where table paths need it (PostgreSQL)
     * @param targetDatabase the target datasource's database where the sink needs it (PostgreSQL)
     * @param sourceColumns column names of source tables the generator must know, by table: those
     *     that sync all columns under a field rule or with processing
     */
    public record Endpoints(
            String sourceType,
            String sourceDatabase,
            String targetType,
            String targetDatabase,
            Map<String, List<String>> sourceColumns) {

        public Endpoints(String sourceType, String sourceDatabase, String targetType, String targetDatabase) {
            this(sourceType, sourceDatabase, targetType, targetDatabase, Map.of());
        }
    }

    /**
     * @param validation per-table checks, or null when none can be accurate or validation is off
     * @param notes why validation is skipped for some tables, for the preview
     */
    public record Generated(String hocon, ValidationSpec validation, List<String> notes) {}

    private WizardGenerator() {}

    public static Generated generate(WizardForm form, Endpoints endpoints) {
        requireShape(form, endpoints);
        WizardSource source = WizardConnectors.source(endpoints.sourceType());
        WizardSink sink = WizardConnectors.sink(endpoints.targetType());
        DataSourceCapabilities from = WizardConnectors.capabilities(endpoints.sourceType());
        boolean lowerCase = WizardConnectors.capabilities(endpoints.targetType()).lowerCaseNames();
        boolean upsert = form.target().writeMode() == WriteMode.UPSERT && source.upsertPossible(form);

        StringBuilder hocon = new StringBuilder();
        hocon.append("# 由任务向导生成，请在向导中修改；转为脚本模式后才能直接编辑\n");
        hocon.append("env {\n  job.mode = \"BATCH\"\n  parallelism = ")
                .append(Math.min(Math.max(1, form.parallelism()), from.maxParallelism())).append("\n}\n\n");
        source.append(hocon, form, endpoints);
        String sinkInput = appendTransforms(hocon, form, endpoints, source, lowerCase);

        hocon.append("sink {\n");
        sink.appendHead(hocon, form, endpoints, upsert);
        hocon.append("    schema_save_mode = ").append(Hocon.str(form.target().schemaMode() == SchemaMode.ERROR
                ? "ERROR_WHEN_SCHEMA_NOT_EXIST" : "CREATE_SCHEMA_WHEN_NOT_EXIST")).append('\n');
        hocon.append("    data_save_mode = ").append(Hocon.str(form.target().dataMode() == DataMode.CLEAR ? "DROP_DATA" : "APPEND_DATA")).append('\n');
        Hocon.appendOptions(hocon, form.sinkOptions(), GENERATED_SINK_OPTIONS);
        hocon.append("    plugin_input = [").append(Hocon.str(sinkInput)).append("]\n  }\n}\n");

        List<String> notes = new ArrayList<>();
        ValidationSpec validation = null;
        if (!from.countable()) {
            notes.add(source.uncountableNote());
        } else if (form.validation()) {
            validation = validation(form, endpoints, source, sink, lowerCase, notes);
        }
        return new Generated(hocon.toString(), validation, notes);
    }

    /**
     * The transforms between source and sink (§7.8, §7.10), each configured per source table and
     * generated only for the tables it changes: extraction (JsonPath, RegexExtract, Split), value
     * processing (one Sql query per table), renamed fields, renamed tables. Returns the stream the
     * sink reads.
     */
    private static String appendTransforms(
            StringBuilder hocon, WizardForm form, Endpoints endpoints, WizardSource source, boolean lowerCase) {
        Map<String, List<String>> json = new LinkedHashMap<>();
        Map<String, List<String>> regex = new LinkedHashMap<>();
        Map<String, List<String>> split = new LinkedHashMap<>();
        Map<String, List<String>> sql = new LinkedHashMap<>();
        Map<String, List<String>> encrypt = new LinkedHashMap<>();
        Map<String, Map<String, String>> fieldRenames = new LinkedHashMap<>();
        Map<String, String> tableRenames = new LinkedHashMap<>();
        for (TableSpec table : form.source().tables()) {
            String path = source.tablePath(form, endpoints, table);
            List<String> synced = source.syncedColumns(form, endpoints, table);
            if (table.processed()) {
                String jsonOptions = FieldProcessing.jsonPathOptions(table);
                if (jsonOptions != null) {
                    json.put(path, List.of(jsonOptions));
                }
                putIfAny(regex, path, FieldProcessing.extractOptions(table, WizardForm.ExtractKind.REGEX));
                putIfAny(split, path, FieldProcessing.extractOptions(table, WizardForm.ExtractKind.SPLIT));
                sql.put(path, List.of("query = " + str(FieldProcessing.sqlQuery(table, synced))));
                List<String> encrypted = FieldProcessing.encryptedFields(table, synced);
                if (!encrypted.isEmpty()) {
                    if (!notBlank(table.encryptKeyId())) {
                        throw invalid(table.name() + "：有字段要加密，请选择加密密钥");
                    }
                    // the key itself is put in at submit time (HoconTemplate.ENCRYPT_KEY_ID)
                    encrypt.put(path, List.of("fields = [" + String.join(", ", encrypted.stream().map(Hocon::str).toList())
                            + "], encrypt_key_id = " + str(table.encryptKeyId())));
                }
            }
            Set<String> dropped = FieldProcessing.droppedSources(table);
            Map<String, String> fields = new LinkedHashMap<>();
            synced.stream().filter(c -> !dropped.contains(c)).forEach(c -> putIfRenamed(fields, c, targetField(form, table, c)));
            if (!fields.isEmpty()) {
                fieldRenames.put(path, fields);
            }
            String target = targetName(form, table, lowerCase);
            if (!target.equals(source.emittedName(form, endpoints, table))) {
                tableRenames.put(path, target);
            }
        }
        StringBuilder t = new StringBuilder();
        String input = "source";
        input = appendStage(t, "JsonPath", input, "json_extracted", json);
        input = appendStage(t, "RegexExtract", input, "regex_extracted", regex);
        input = appendStage(t, "Split", input, "split_extracted", split);
        input = appendStage(t, "Sql", input, "processed", sql);
        input = appendStage(t, "FieldEncrypt", input, "encrypted", encrypt);
        if (!fieldRenames.isEmpty()) {
            t.append("  FieldRename {\n    plugin_input = [").append(str(input)).append("]\n    plugin_output = \"fields_renamed\"\n");
            t.append("    table_transform = [\n");
            fieldRenames.forEach((path, renames) -> {
                t.append("      { table_path = ").append(str(path)).append(", specific = [\n");
                renames.forEach((from, to) -> t.append("          { field_name = ").append(str(from))
                        .append(", target_name = ").append(str(to)).append(" }\n"));
                t.append("        ] }\n");
            });
            t.append("    ]\n  }\n");
            input = "fields_renamed";
        }
        if (!tableRenames.isEmpty()) {
            t.append("  TableRename {\n    plugin_input = [").append(str(input)).append("]\n    plugin_output = \"tables_renamed\"\n");
            t.append("    table_transform = [\n");
            tableRenames.forEach((path, name) -> t.append("      { table_path = ").append(str(path))
                    .append(", replacements_with_regex = [{ replace_from = \"^.*$\", replace_to = ").append(str(name)).append(" }] }\n"));
            t.append("    ]\n  }\n");
            input = "tables_renamed";
        }
        if (!t.isEmpty()) {
            hocon.append("transform {\n").append(t).append("}\n\n");
        }
        return input;
    }

    /**
     * One transform configured per table through {@code table_transform}; a table may have several
     * entries (ALL_MATCH runs them in order). The top level repeats the first entry's options only
     * to satisfy the plugin's required options, and matches no table itself, so tables without an
     * entry pass through unchanged.
     */
    private static String appendStage(StringBuilder t, String plugin, String input, String output, Map<String, List<String>> entries) {
        if (entries.isEmpty()) {
            return input;
        }
        boolean several = entries.values().stream().anyMatch(e -> e.size() > 1);
        t.append("  ").append(plugin).append(" {\n    plugin_input = [").append(str(input)).append("]\n    plugin_output = ")
                .append(str(output)).append("\n    table_match_regex = \"(?!)\"\n");
        if (several) {
            t.append("    rule_match_mode = \"ALL_MATCH\"\n");
        }
        t.append("    ").append(entries.values().iterator().next().get(0)).append("\n    table_transform = [\n");
        entries.forEach((path, options) -> options.forEach(o ->
                t.append("      { table_path = ").append(str(path)).append(", ").append(o).append(" }\n")));
        t.append("    ]\n  }\n");
        return output;
    }

    private static void putIfAny(Map<String, List<String>> map, String path, List<String> options) {
        if (!options.isEmpty()) {
            map.put(path, options);
        }
    }

    private static void putIfRenamed(Map<String, String> renames, String from, String to) {
        if (!from.equals(to)) {
            renames.put(from, to);
        }
    }

    /**
     * Target table or index name of one source table: set by hand, or derived by the table rule
     * (§7.8).
     *
     * @param lowerCase the target needs lower-case names (Elasticsearch indices)
     */
    public static String targetName(WizardForm form, TableSpec table, boolean lowerCase) {
        String name;
        if (notBlank(table.targetName())) {
            name = table.targetName().trim();
        } else if (form.target().tableRule() == null && form.source().tables().size() == 1 && notBlank(form.target().tableName())) {
            name = form.target().tableName().trim(); // forms saved before table rules
        } else {
            name = tableRule(form).apply(table.name());
        }
        return lowerCase ? name.toLowerCase(Locale.ROOT) : name;
    }

    /** Target name of one source field: set by hand, or derived by the table's or the job's field rule. */
    public static String targetField(WizardForm form, TableSpec table, String column) {
        String byHand = table.fieldNames() == null ? null : table.fieldNames().get(column);
        if (notBlank(byHand)) {
            return byHand.trim();
        }
        NameRule rule = table.fieldRule() != null ? table.fieldRule() : form.fieldRule();
        return rule == null ? column : rule.apply(column);
    }

    private static NameRule tableRule(WizardForm form) {
        WizardForm.Target t = form.target();
        return t.tableRule() != null
                ? t.tableRule()
                : new NameRule(null, null, WizardForm.NameCase.KEEP, t.prefix(), t.suffix());
    }

    /** Incremental field of a table: its own, or the job-wide one. Null for full sync or a custom window. */
    public static String incrementalField(WizardForm form, TableSpec table) {
        if (form.sync().mode() != SyncMode.INCREMENTAL || form.sync().window() == Window.CUSTOM) {
            return null;
        }
        return notBlank(table.incrementalField()) ? table.incrementalField().trim() : form.sync().field();
    }

    /** The time window on the table's incremental field (or the custom condition), with variables. */
    public static String incrementalCondition(WizardForm form, SqlDialect dialect, TableSpec table) {
        if (form.sync().mode() != SyncMode.INCREMENTAL) {
            return null;
        }
        if (form.sync().window() == Window.CUSTOM) {
            return form.sync().customCondition().trim();
        }
        String field = dialect.quote(incrementalField(form, table));
        return switch (form.sync().window()) {
            case CONTINUOUS -> field + " >= '${" + Variables.WINDOW_START + "}' and " + field + " < '${" + Variables.WINDOW_END + "}'";
            case HOUR -> field + " >= '$[yyyy-MM-dd HH-1/24]:00:00' and " + field + " < '$[yyyy-MM-dd HH]:00:00'";
            default -> field + " >= '$[yyyy-MM-dd-1] 00:00:00' and " + field + " < '$[yyyy-MM-dd] 00:00:00'";
        };
    }

    /**
     * Per-table counts where they can be accurate (§7.6): full sync into a cleared target counts the
     * whole target table; an incremental sync counts the same window on both sides.
     */
    private static ValidationSpec validation(
            WizardForm form, Endpoints endpoints, WizardSource source, WizardSink sink, boolean lowerCase, List<String> notes) {
        boolean full = form.sync().mode() == SyncMode.FULL;
        if (full && form.target().dataMode() == DataMode.KEEP) {
            notes.add("全量同步且保留目标已有数据：目标表里可能还有源端已删除的数据，计数不准，不做行数校验");
            return null;
        }
        if (!full && form.sync().window() == Window.CUSTOM) {
            notes.add("自定义增量条件无法套用到目标表，不做行数校验");
            return null;
        }
        if (!full && sink.incrementalValidationSkipped() != null) {
            notes.add(sink.incrementalValidationSkipped());
            return null;
        }
        List<ValidationSpec.TableCheck> checks = new ArrayList<>();
        for (TableSpec table : form.source().tables()) {
            Side target = sink.countSide(form, endpoints, table, targetName(form, table, lowerCase), full, notes);
            if (target != null) {
                checks.add(new ValidationSpec.TableCheck(table.name(), source.countSide(form, endpoints, table), target));
            }
        }
        return checks.isEmpty() ? null : new ValidationSpec(true, null, null, false, checks);
    }

    /** What every job needs; each source checks its own part ({@link WizardSource#requireShape}). */
    private static void requireShape(WizardForm form, Endpoints endpoints) {
        if (form == null || form.source() == null || form.target() == null || form.sync() == null) {
            throw invalid("向导表单不完整");
        }
        WizardSource source = WizardConnectors.source(endpoints.sourceType());
        WizardConnectors.sink(endpoints.targetType());
        if (form.source().tables() == null || form.source().tables().isEmpty()) {
            throw invalid("请至少选择一张源表");
        }
        if (WizardConnectors.capabilities(endpoints.sourceType()).namespaces() && !notBlank(form.source().namespace())) {
            throw invalid("请选择源端的库或 schema");
        }
        if (WizardConnectors.capabilities(endpoints.targetType()).namespaces() && !notBlank(form.target().namespace())) {
            throw invalid("请选择目标端的库或 schema");
        }
        for (TableSpec table : form.source().tables()) {
            if (targetName(form, table, false).isBlank()) {
                throw invalid(table.name() + "：目标表名为空");
            }
        }
        if (form.target().schemaMode() == null || form.target().dataMode() == null || form.target().writeMode() == null) {
            throw invalid("请完成目标端选项");
        }
        if (form.sync().mode() == null) {
            throw invalid("请选择同步方式");
        }
        source.requireShape(form);
    }

    /** A HOCON quoted string; the text inside is never substituted by HOCON. */
    static String str(String value) {
        return Hocon.str(value);
    }
}
