package com.dataintegration.console.server.job.wizard;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.server.job.ValidationSpec;
import com.dataintegration.console.server.job.ValidationSpec.Mode;
import com.dataintegration.console.server.job.ValidationSpec.Side;
import com.dataintegration.console.server.job.wizard.WizardForm.DataMode;
import com.dataintegration.console.server.job.wizard.WizardForm.HttpField;
import com.dataintegration.console.server.job.wizard.WizardForm.HttpPageLocation;
import com.dataintegration.console.server.job.wizard.WizardForm.HttpPaging;
import com.dataintegration.console.server.job.wizard.WizardForm.HttpSpec;
import com.dataintegration.console.server.job.wizard.WizardForm.NameRule;
import com.dataintegration.console.server.job.wizard.WizardForm.SchemaMode;
import com.dataintegration.console.server.job.wizard.WizardForm.SyncMode;
import com.dataintegration.console.server.job.wizard.WizardForm.TableSpec;
import com.dataintegration.console.server.job.wizard.WizardForm.Window;
import com.dataintegration.console.server.job.wizard.WizardForm.WriteMode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a {@link WizardForm} into a SeaTunnel 3.0 job config and its per-table validation
 * (docs/design.md §7.2, §7.4–7.6). Pure: everything it needs about the datasources comes in
 * through {@link Endpoints}, so the output is fully determined by its inputs.
 *
 * <p>Every table gets a {@code table_path}; a table with column pruning, a filter or an incremental
 * condition also gets its own {@code query} (Jdbc's {@code where_condition} would apply to every
 * table). Target names are worked out here, table by table (§7.8): renamed fields go through a
 * {@code FieldRename} transform and renamed tables through a {@code TableRename} transform, each
 * configured per source table, and the sink addresses tables with SeaTunnel's
 * {@code ${table_name}} placeholder. Fields are not renamed with SQL aliases: the Jdbc source
 * matches query columns to the table's own by name, and a renamed key column would lose its
 * primary key.
 */
public final class WizardGenerator {

    public static final String MYSQL = "MYSQL";
    public static final String POSTGRESQL = "POSTGRESQL";
    public static final String ELASTICSEARCH = "ELASTICSEARCH";
    public static final String HTTP = "HTTP";

    private static final Pattern RAW_VALUE = Pattern.compile("-?\\d+(\\.\\d+)?|true|false|\\[.*]|\\{.*}", Pattern.DOTALL);
    private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Set<String> HTTP_FIELD_TYPES = Set.of("STRING", "BIGINT", "DOUBLE", "BOOLEAN", "DATE", "TIMESTAMP", "DECIMAL");
    private static final Set<String> HTTP_SOURCE_OPTIONS = Set.of(
            "retry", "retry_backoff_multiplier_ms", "retry_backoff_max_ms", "json_filed_missed_return_null");
    /**
     * Options the wizard writes itself or takes from the datasource, so advanced options may not set
     * them: written after the generated ones, they would win (mirrors {@code HANDLED} in JobWizard.vue).
     */
    private static final Set<String> GENERATED_JDBC_SOURCE_OPTIONS = Set.of(
            "datasource_id", "plugin_output", "url", "driver", "username", "password",
            "table_path", "table_list", "query", "where_condition", "parallelism");
    private static final Set<String> GENERATED_SINK_OPTIONS = Set.of(
            "datasource_id", "plugin_input", "url", "driver", "username", "password", "hosts",
            "database", "table", "index", "generate_sink_sql", "enable_upsert", "primary_keys",
            "schema_save_mode", "data_save_mode", "tablePrefix", "tableSuffix");
    /** Declared length of a string primary key from an HTTP source, so it becomes VARCHAR(255). */
    private static final int HTTP_STRING_KEY_LENGTH = 255;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** The params and body of an HTTP source request, paging applied. */
    public record HttpRequestParts(Map<String, String> params, String body) {}

    /**
     * @param sourceDatabase PostgreSQL database of the source datasource (part of table_path); null for MySQL
     * @param targetDatabase PostgreSQL database of the target datasource; null otherwise
     * @param sourceColumns column names of source tables that sync all columns under a field rule,
     *     by table; the rule renames these columns as they are now
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
        boolean http = HTTP.equals(endpoints.sourceType());
        boolean es = ELASTICSEARCH.equals(endpoints.targetType());
        boolean upsert = form.target().writeMode() == WriteMode.UPSERT
                && (!http || form.source().http().primaryKeys() != null && !form.source().http().primaryKeys().isEmpty());
        StringBuilder hocon = new StringBuilder();
        hocon.append("# 由任务向导生成，请在向导中修改；转为脚本模式后才能直接编辑\n");
        hocon.append("env {\n  job.mode = \"BATCH\"\n  parallelism = ")
                .append(http ? 1 : Math.max(1, form.parallelism())).append("\n}\n\n");

        if (http) {
            appendHttpSource(hocon, form, es);
        } else {
            appendJdbcSource(hocon, form, endpoints);
        }
        String sinkInput = appendTransforms(hocon, form, endpoints, es);

        hocon.append("sink {\n");
        if (es) {
            hocon.append("  Elasticsearch {\n");
            hocon.append("    datasource_id = ").append(str(form.target().datasourceId())).append('\n');
            hocon.append("    index = \"${table_name}\"\n");
            if (upsert) {
                hocon.append("    primary_keys = [\"${primary_key}\"]\n");
            }
        } else {
            hocon.append("  Jdbc {\n");
            hocon.append("    datasource_id = ").append(str(form.target().datasourceId())).append('\n');
            String name = "${table_name}";
            if (POSTGRESQL.equals(endpoints.targetType())) {
                hocon.append("    database = ").append(str(endpoints.targetDatabase())).append('\n');
                hocon.append("    table = ").append(str(form.target().namespace() + "." + name)).append('\n');
            } else {
                hocon.append("    database = ").append(str(form.target().namespace())).append('\n');
                hocon.append("    table = ").append(str(name)).append('\n');
            }
            hocon.append("    generate_sink_sql = true\n");
            hocon.append("    enable_upsert = ").append(upsert).append('\n');
        }
        hocon.append("    schema_save_mode = ").append(str(form.target().schemaMode() == SchemaMode.ERROR
                ? "ERROR_WHEN_SCHEMA_NOT_EXIST" : "CREATE_SCHEMA_WHEN_NOT_EXIST")).append('\n');
        hocon.append("    data_save_mode = ").append(str(form.target().dataMode() == DataMode.CLEAR ? "DROP_DATA" : "APPEND_DATA")).append('\n');
        appendOptions(hocon, form.sinkOptions(), GENERATED_SINK_OPTIONS);
        hocon.append("    plugin_input = [").append(str(sinkInput)).append("]\n  }\n}\n");

        List<String> notes = new ArrayList<>();
        if (http) {
            notes.add("HTTP 接口无法计数，不做行数校验");
        }
        ValidationSpec validation = !http && form.validation() ? validation(form, endpoints, notes) : null;
        return new Generated(hocon.toString(), validation, notes);
    }

    private static void appendJdbcSource(StringBuilder hocon, WizardForm form, Endpoints endpoints) {
        hocon.append("source {\n  Jdbc {\n");
        hocon.append("    datasource_id = ").append(str(form.source().datasourceId())).append('\n');
        hocon.append("    table_list = [\n");
        for (TableSpec table : form.source().tables()) {
            hocon.append("      { table_path = ").append(str(tablePath(form, endpoints, table)));
            String query = sourceQuery(form, endpoints, table, false);
            if (query != null) {
                hocon.append(", query = ").append(str(query));
            }
            hocon.append(" }\n");
        }
        hocon.append("    ]\n");
        appendOptions(hocon, form.sourceOptions(), GENERATED_JDBC_SOURCE_OPTIONS);
        hocon.append("    plugin_output = \"source\"\n  }\n}\n\n");
    }

    private static void appendHttpSource(StringBuilder hocon, WizardForm form, boolean es) {
        TableSpec table = form.source().tables().get(0);
        HttpSpec http = form.source().http();
        HttpPaging paging = http.paging();
        HttpRequestParts request = httpRequestParts(http.params(), http.body(), paging, null);
        Map<String, String> params = request.params();
        String body = request.body();

        hocon.append("source {\n  Http {\n");
        hocon.append("    datasource_id = ").append(str(form.source().datasourceId())).append('\n');
        hocon.append("    url = ").append(str(http.path().trim())).append('\n');
        hocon.append("    method = ").append(str(http.method().name())).append('\n');
        if (!params.isEmpty()) {
            appendStringMap(hocon, "params", params);
        }
        if (notBlank(body)) {
            hocon.append("    body = ").append(str(body)).append('\n');
        }
        hocon.append("    format = \"json\"\n");
        if (form.sourceOptions() == null || !form.sourceOptions().containsKey("json_filed_missed_return_null")) {
            hocon.append("    json_filed_missed_return_null = true\n");
        }
        List<HttpField> fields = httpFields(form, table);
        boolean extractRows = fields.stream().anyMatch(f -> notBlank(f.jsonPath()))
                || notBlank(http.contentField()) && !"$".equals(http.contentField().trim());
        if (extractRows) {
            Map<String, String> jsonFields = new LinkedHashMap<>();
            fields.forEach(f -> jsonFields.put(f.name(), httpJsonPath(http.contentField(), f)));
            appendStringMap(hocon, "json_field", jsonFields);
        }
        hocon.append("    schema = {\n");
        hocon.append("      table = ").append(str(targetName(form, table, es))).append('\n');
        List<String> primaryKeys = http.primaryKeys() == null ? List.of() : http.primaryKeys().stream().map(String::trim).toList();
        if (primaryKeys.isEmpty()) {
            hocon.append("      fields {\n");
            fields.forEach(f -> hocon.append("        ").append(key(f.name())).append(" = ").append(httpFieldType(f)).append('\n'));
            hocon.append("      }\n");
        } else {
            // schema.fields makes every column nullable, and MySQL / PostgreSQL refuse a nullable
            // primary key when the target table is created; a string key also needs a length, or
            // MySQL makes it LONGTEXT, which cannot be a key.
            hocon.append("      columns = [\n");
            fields.forEach(f -> {
                boolean key = primaryKeys.contains(f.name());
                hocon.append("        { name = ").append(str(f.name())).append(", type = ").append(httpFieldType(f));
                if (key && "STRING".equalsIgnoreCase(f.type().trim())) {
                    hocon.append(", columnLength = ").append(HTTP_STRING_KEY_LENGTH);
                }
                hocon.append(", nullable = ").append(!key).append(" }\n");
            });
            hocon.append("      ]\n");
        }
        if (!primaryKeys.isEmpty()) {
            hocon.append("      primaryKey {\n        name = \"http_pk\"\n        columnNames = [")
                    .append(String.join(", ", http.primaryKeys().stream().map(String::trim).map(WizardGenerator::str).toList()))
                    .append("]\n      }\n");
        }
        hocon.append("    }\n");
        if (paging != null && (paging.mode() == WizardForm.HttpPagingMode.CURSOR
                || paging.mode() == WizardForm.HttpPagingMode.PAGE_NUMBER && paging.pageLocation() != HttpPageLocation.BODY)) {
            hocon.append("    keep_page_param_as_http_param = true\n");
        }
        if (paging != null && paging.mode() != WizardForm.HttpPagingMode.NONE) {
            hocon.append("    pageing = {\n");
            switch (paging.mode()) {
                case PAGE_NUMBER -> {
                    hocon.append("      page_type = \"PageNumber\"\n");
                    hocon.append("      page_field = ").append(str(paging.pageField().trim())).append('\n');
                    hocon.append("      start_page_number = ").append(paging.startPageNumber()).append('\n');
                    hocon.append("      batch_size = ").append(paging.pageSize()).append('\n');
                    if (paging.totalPageSize() != null) {
                        hocon.append("      total_page_size = ").append(paging.totalPageSize()).append('\n');
                    }
                }
                case CURSOR -> {
                    hocon.append("      page_type = \"Cursor\"\n");
                    hocon.append("      cursor_field = ").append(str(paging.cursorField().trim())).append('\n');
                    hocon.append("      cursor_response_field = ").append(str(paging.cursorResponseField().trim())).append('\n');
                }
                case NONE -> {}
            }
            hocon.append("      use_placeholder_replacement = true\n    }\n");
        }
        appendHttpOptions(hocon, form.sourceOptions());
        hocon.append("    plugin_output = \"source\"\n  }\n}\n\n");
    }

    private static void appendHttpOptions(StringBuilder hocon, Map<String, String> options) {
        if (options != null) {
            options.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null && !value.isBlank()
                        && !HTTP_SOURCE_OPTIONS.contains(key.trim())) {
                    throw invalid("不支持的 HTTP 高级参数：" + key);
                }
            });
        }
        appendOptions(hocon, options);
    }

    private static String httpFieldType(HttpField field) {
        String type = field.type().trim().toUpperCase(Locale.ROOT);
        if (!HTTP_FIELD_TYPES.contains(type)) {
            throw invalid("HTTP 字段 " + field.name() + " 的类型不支持：" + field.type());
        }
        if (!"DECIMAL".equals(type)) {
            return field.type().trim();
        }
        int precision = field.precision() == null ? 18 : field.precision();
        int scale = field.scale() == null ? 2 : field.scale();
        if (precision < 1 || precision > 38 || scale < 0 || scale > precision) {
            throw invalid("HTTP 字段 " + field.name() + "：小数总位数要在 1～38 之间，小数位不能超过总位数");
        }
        return str("decimal(" + precision + "," + scale + ")");
    }

    private static String httpJsonPath(String contentField, HttpField field) {
        if (notBlank(field.jsonPath()) && field.jsonPath().contains("[*]")) {
            return field.jsonPath().trim();
        }
        String prefix = notBlank(contentField) ? contentField.trim() : "$";
        String relative = notBlank(field.jsonPath()) ? field.jsonPath().trim() : field.name();
        if (relative.startsWith("$")) {
            relative = relative.substring(1);
        }
        if (relative.startsWith(".")) {
            relative = relative.substring(1);
        }
        return prefix + "[*]" + (relative.startsWith("[") ? relative : "." + relative);
    }

    /**
     * Applies paging to the request params and body. Shared by the generated config and the preview,
     * so both send the same request.
     *
     * @param page value of the page field: null writes the connector's {@code ${page}} placeholder,
     *     the preview passes the first page number
     */
    static HttpRequestParts httpRequestParts(Map<String, String> params, String body, HttpPaging paging, String page) {
        Map<String, String> result = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
        String resultBody = body;
        if (paging != null && paging.mode() != null) {
            switch (paging.mode()) {
                case PAGE_NUMBER -> {
                    String pageValue = page == null ? "${page}" : page;
                    if (paging.pageLocation() == HttpPageLocation.BODY) {
                        resultBody = pagedBody(body, paging, pageValue);
                    } else {
                        result.put(paging.pageField().trim(), pageValue);
                        result.put(paging.pageSizeField().trim(), String.valueOf(paging.pageSize()));
                    }
                }
                case CURSOR -> {
                    if (paging.cursorField() != null) {
                        result.remove(paging.cursorField().trim());
                    }
                }
                case NONE -> {}
            }
        }
        return new HttpRequestParts(result, resultBody);
    }

    /** A cursor response field as Jayway reads it: a bare name is a field of the root. */
    static String cursorPath(String field) {
        String text = field == null ? "" : field.trim();
        return text.startsWith("$") ? text : "$." + text;
    }

    private static String pagedBody(String body, HttpPaging paging, String page) {
        JsonNode parsed;
        try {
            parsed = OBJECT_MAPPER.readTree(notBlank(body) ? body : "{}");
        } catch (RuntimeException e) {
            throw invalid("分页 Body 必须是有效的 JSON 对象");
        }
        if (!(parsed instanceof ObjectNode object)) {
            throw invalid("分页 Body 必须是 JSON 对象");
        }
        object.put(paging.pageField().trim(), page);
        object.put(paging.pageSizeField().trim(), paging.pageSize());
        return OBJECT_MAPPER.writeValueAsString(object);
    }

    private static void appendStringMap(StringBuilder hocon, String name, Map<String, String> values) {
        hocon.append("    ").append(name).append(" = {\n");
        values.forEach((key, value) -> hocon.append("      ").append(key(key)).append(" = ").append(str(value)).append('\n'));
        hocon.append("    }\n");
    }

    private static List<HttpField> httpFields(WizardForm form, TableSpec table) {
        List<HttpField> fields = form.source().http().fields();
        if (table.allColumns()) {
            return fields;
        }
        Map<String, HttpField> byName = new LinkedHashMap<>();
        fields.forEach(f -> byName.put(f.name(), f));
        return table.columns().stream().map(byName::get).toList();
    }

    /**
     * The transforms between source and sink (§7.8, §7.10), each configured per source table and
     * generated only for the tables it changes: extraction (JsonPath, RegexExtract, Split), value
     * processing (one Sql query per table), renamed fields, renamed tables. Returns the stream the
     * sink reads.
     */
    private static String appendTransforms(StringBuilder hocon, WizardForm form, Endpoints endpoints, boolean es) {
        boolean http = HTTP.equals(endpoints.sourceType());
        Map<String, List<String>> json = new LinkedHashMap<>();
        Map<String, List<String>> regex = new LinkedHashMap<>();
        Map<String, List<String>> split = new LinkedHashMap<>();
        Map<String, List<String>> sql = new LinkedHashMap<>();
        Map<String, List<String>> encrypt = new LinkedHashMap<>();
        Map<String, Map<String, String>> fieldRenames = new LinkedHashMap<>();
        Map<String, String> tableRenames = new LinkedHashMap<>();
        for (TableSpec table : form.source().tables()) {
            String path = tablePath(form, endpoints, table);
            List<String> synced = syncedColumns(form, endpoints, table);
            if (table.processed()) {
                if (!http && table.allColumns()
                        && (endpoints.sourceColumns() == null || !endpoints.sourceColumns().containsKey(table.name()))) {
                    throw invalid(table.name() + "：读不到源表的字段，无法生成字段处理");
                }
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
                    encrypt.put(path, List.of("fields = [" + String.join(", ", encrypted.stream().map(WizardGenerator::str).toList())
                            + "], encrypt_key_id = " + str(table.encryptKeyId())));
                }
            }
            Set<String> dropped = FieldProcessing.droppedSources(table);
            Map<String, String> fields = new LinkedHashMap<>();
            synced.stream().filter(c -> !dropped.contains(c)).forEach(c -> putIfRenamed(fields, c, targetField(form, table, c)));
            if (!fields.isEmpty()) {
                fieldRenames.put(path, fields);
            }
            String target = targetName(form, table, es);
            if (!http && !target.equals(table.name())) {
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

    /**
     * The table's synced columns as far as they are known: its column list, or for an all-columns
     * table the columns passed in {@link Endpoints#sourceColumns()}, else the fields named by hand.
     */
    static List<String> syncedColumns(WizardForm form, Endpoints endpoints, TableSpec table) {
        if (!table.allColumns()) {
            return table.columns();
        }
        if (HTTP.equals(endpoints.sourceType())) {
            return form.source().http().fields().stream().map(HttpField::name).toList();
        }
        List<String> known = endpoints.sourceColumns() == null ? null : endpoints.sourceColumns().get(table.name());
        if (known != null) {
            return known;
        }
        return table.fieldNames() == null ? List.of() : List.copyOf(table.fieldNames().keySet());
    }

    /** True when the table's field names follow a rule or its values are processed, so its full column list is needed. */
    public static boolean needsColumns(WizardForm form, TableSpec table) {
        return form.source().http() == null
                && table.allColumns() && (table.fieldRule() != null || form.fieldRule() != null || table.processed());
    }

    private static void putIfRenamed(Map<String, String> renames, String from, String to) {
        if (!from.equals(to)) {
            renames.put(from, to);
        }
    }

    /**
     * Target table or index name of one source table: set by hand, or derived by the table rule
     * (§7.8). Elasticsearch index names are always lower case.
     */
    public static String targetName(WizardForm form, TableSpec table, boolean es) {
        String name;
        if (notBlank(table.targetName())) {
            name = table.targetName().trim();
        } else if (form.target().tableRule() == null && form.source().tables().size() == 1 && notBlank(form.target().tableName())) {
            name = form.target().tableName().trim(); // forms saved before table rules
        } else {
            name = tableRule(form).apply(table.name());
        }
        return es ? name.toLowerCase(Locale.ROOT) : name;
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

    /**
     * The table's select, or null when the plain table_path reads the same rows.
     *
     * @param forCount count(*) instead of the columns, for validation
     */
    static String sourceQuery(WizardForm form, Endpoints endpoints, TableSpec table, boolean forCount) {
        List<String> conditions = new ArrayList<>();
        if (notBlank(table.filter())) {
            conditions.add("(" + table.filter().trim() + ")");
        }
        String incremental = incrementalCondition(form, endpoints.sourceType(), table);
        if (incremental != null) {
            conditions.add("(" + incremental + ")");
        }
        if (!forCount && conditions.isEmpty() && table.allColumns()) {
            return null;
        }
        String select = forCount
                ? "count(*)"
                : table.allColumns()
                        ? "*"
                        : String.join(", ", table.columns().stream().map(c -> quote(endpoints.sourceType(), c)).toList());
        return "select " + select + " from " + qualified(endpoints.sourceType(), form.source().namespace(), table.name())
                + (conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions));
    }

    /** The time window on the table's incremental field (or the custom condition), with variables. */
    static String incrementalCondition(WizardForm form, String dbType, TableSpec table) {
        if (form.sync().mode() != SyncMode.INCREMENTAL) {
            return null;
        }
        if (form.sync().window() == Window.CUSTOM) {
            return form.sync().customCondition().trim();
        }
        String field = quote(dbType, incrementalField(form, table));
        return form.sync().window() == Window.HOUR
                ? field + " >= '$[yyyy-MM-dd HH-1/24]:00:00' and " + field + " < '$[yyyy-MM-dd HH]:00:00'"
                : field + " >= '$[yyyy-MM-dd-1] 00:00:00' and " + field + " < '$[yyyy-MM-dd] 00:00:00'";
    }

    /**
     * Per-table counts where they can be accurate (§7.6): full sync into a cleared target counts the
     * whole target table; an incremental sync counts the same window on both sides.
     */
    private static ValidationSpec validation(WizardForm form, Endpoints endpoints, List<String> notes) {
        boolean es = ELASTICSEARCH.equals(endpoints.targetType());
        boolean full = form.sync().mode() == SyncMode.FULL;
        if (full && form.target().dataMode() == DataMode.KEEP) {
            notes.add("全量同步且保留目标已有数据：目标表里可能还有源端已删除的数据，计数不准，不做行数校验");
            return null;
        }
        if (!full && form.sync().window() == Window.CUSTOM) {
            notes.add("自定义增量条件无法套用到目标表，不做行数校验");
            return null;
        }
        if (!full && es) {
            notes.add("ES 目标的增量窗口不做行数校验（日期格式在 ES 里不好保证一致）");
            return null;
        }
        List<ValidationSpec.TableCheck> checks = new ArrayList<>();
        for (TableSpec table : form.source().tables()) {
            Side source = new Side(Mode.SQL, form.source().datasourceId(), sourceQuery(form, endpoints, table, true), null, null);
            String targetName = targetName(form, table, es);
            Side target;
            if (es) {
                target = new Side(Mode.ES, form.target().datasourceId(), null, targetName, null);
            } else if (full) {
                target = new Side(Mode.SQL, form.target().datasourceId(),
                        "select count(*) from " + qualified(endpoints.targetType(), form.target().namespace(), targetName), null, null);
            } else {
                String field = incrementalField(form, table);
                if (!table.allColumns() && !table.columns().contains(field)) {
                    notes.add(table.name() + "：增量字段 " + field + " 没有同步到目标表，这张表不做行数校验");
                    continue;
                }
                TableSpec onTarget = new TableSpec(targetName, null, null, targetField(form, table, field));
                target = new Side(Mode.SQL, form.target().datasourceId(),
                        "select count(*) from " + qualified(endpoints.targetType(), form.target().namespace(), targetName)
                                + " where " + incrementalCondition(form, endpoints.targetType(), onTarget),
                        null, null);
            }
            checks.add(new ValidationSpec.TableCheck(table.name(), source, target));
        }
        return checks.isEmpty() ? null : new ValidationSpec(true, null, null, false, checks);
    }

    private static void requireShape(WizardForm form, Endpoints endpoints) {
        if (form == null || form.source() == null || form.target() == null || form.sync() == null) {
            throw invalid("向导表单不完整");
        }
        boolean http = HTTP.equals(endpoints.sourceType());
        if (!http && !MYSQL.equals(endpoints.sourceType()) && !POSTGRESQL.equals(endpoints.sourceType())) {
            throw invalid("向导的源端只支持 MySQL、PostgreSQL 和 HTTP");
        }
        if (!List.of(MYSQL, POSTGRESQL, ELASTICSEARCH).contains(endpoints.targetType())) {
            throw invalid("向导的目标端只支持 MySQL、PostgreSQL 和 Elasticsearch");
        }
        if (form.source().tables() == null || form.source().tables().isEmpty()) {
            throw invalid("请至少选择一张源表");
        }
        if (http && form.source().tables().size() != 1) {
            throw invalid("HTTP 任务只能配置一个接口");
        }
        if (!http && !notBlank(form.source().namespace())) {
            throw invalid("请选择源端的库或 schema");
        }
        if (!ELASTICSEARCH.equals(endpoints.targetType()) && !notBlank(form.target().namespace())) {
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
        if (http) {
            requireHttpShape(form);
        } else if (form.sync().mode() == SyncMode.INCREMENTAL) {
            if (form.sync().window() == null) {
                throw invalid("请选择增量的时间窗口");
            }
            if (form.sync().window() == Window.CUSTOM && !notBlank(form.sync().customCondition())) {
                throw invalid("请填写自定义增量条件");
            }
            if (form.sync().window() != Window.CUSTOM) {
                for (TableSpec table : form.source().tables()) {
                    if (incrementalField(form, table) == null || incrementalField(form, table).isBlank()) {
                        throw invalid(table.name() + "：请选择增量字段");
                    }
                }
            }
        }
    }

    private static void requireHttpShape(WizardForm form) {
        HttpSpec http = form.source().http();
        if (http == null || !notBlank(http.path()) || http.method() == null) {
            throw invalid("请填写 HTTP 接口路径并选择请求方法");
        }
        if (http.params() != null) {
            http.params().forEach((name, value) -> {
                if (!notBlank(name) || value == null) {
                    throw invalid("HTTP 参数名和值不能为空");
                }
            });
        }
        if (http.fields() == null || http.fields().isEmpty()) {
            throw invalid("请至少选择一个 HTTP 响应字段");
        }
        Map<String, HttpField> fields = new LinkedHashMap<>();
        for (HttpField field : http.fields()) {
            if (field == null || !notBlank(field.name()) || !notBlank(field.type())) {
                throw invalid("HTTP 字段名和类型不能为空");
            }
            httpFieldType(field);
            if (notBlank(field.jsonPath()) && !field.jsonPath().trim().startsWith("$")) {
                throw invalid("HTTP 字段 " + field.name() + " 的 jsonPath 必须以 $ 开头");
            }
            if (fields.putIfAbsent(field.name(), field) != null) {
                throw invalid("HTTP 字段重复：" + field.name());
            }
        }
        TableSpec table = form.source().tables().get(0);
        List<String> synced = table.allColumns() ? List.copyOf(fields.keySet()) : table.columns();
        for (String column : synced) {
            if (!fields.containsKey(column)) {
                throw invalid("HTTP 同步字段不存在：" + column);
            }
        }
        if (http.primaryKeys() != null) {
            for (String primaryKey : http.primaryKeys()) {
                if (!notBlank(primaryKey) || !synced.contains(primaryKey)) {
                    throw invalid("HTTP 主键必须属于同步字段");
                }
            }
        }
        HttpPaging paging = http.paging();
        if (paging == null) {
            return;
        }
        if (paging.mode() == null) {
            throw invalid("请选择 HTTP 分页方式");
        }
        switch (paging.mode()) {
            case PAGE_NUMBER -> {
                if (!notBlank(paging.pageField()) || paging.pageLocation() == null
                        || paging.startPageNumber() == null || paging.startPageNumber() < 0
                        || !notBlank(paging.pageSizeField()) || paging.pageSize() == null || paging.pageSize() <= 0) {
                    throw invalid("请完整填写 HTTP 页码分页参数");
                }
                if (paging.pageField().trim().equals(paging.pageSizeField().trim())) {
                    throw invalid("HTTP 页码和每页条数不能使用同一个参数名");
                }
                if (paging.totalPageSize() != null && paging.totalPageSize() < 0) {
                    throw invalid("HTTP 总页数不能为负数");
                }
            }
            case CURSOR -> {
                if (paging.pageLocation() == HttpPageLocation.BODY) {
                    throw invalid("HTTP 游标分页只支持 PARAMS");
                }
                if (!notBlank(paging.cursorField()) || !notBlank(paging.cursorResponseField())) {
                    throw invalid("请完整填写 HTTP 游标分页参数");
                }
                try {
                    HttpJsonPath.requireValid(cursorPath(paging.cursorResponseField()));
                } catch (ConsoleException e) {
                    throw invalid("HTTP 游标响应字段的路径不合法：" + paging.cursorResponseField());
                }
            }
            case NONE -> {}
        }
    }

    private static String tablePath(WizardForm form, Endpoints endpoints, TableSpec table) {
        if (HTTP.equals(endpoints.sourceType())) {
            return targetName(form, table, ELASTICSEARCH.equals(endpoints.targetType()));
        }
        return POSTGRESQL.equals(endpoints.sourceType())
                ? endpoints.sourceDatabase() + "." + form.source().namespace() + "." + table.name()
                : form.source().namespace() + "." + table.name();
    }

    static String qualified(String dbType, String namespace, String table) {
        return quote(dbType, namespace) + "." + quote(dbType, table);
    }

    static String quote(String dbType, String identifier) {
        return MYSQL.equals(dbType)
                ? "`" + identifier.replace("`", "``") + "`"
                : "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** Advanced options: numbers, booleans, lists and objects written as-is, everything else quoted. */
    private static void appendOptions(StringBuilder hocon, Map<String, String> options, Set<String> generated) {
        if (options != null) {
            options.forEach((key, value) -> {
                if (key != null && value != null && !value.isBlank() && generated.contains(key.trim())) {
                    throw invalid("高级参数不能设置由向导生成的 " + key.trim());
                }
            });
        }
        appendOptions(hocon, options);
    }

    private static void appendOptions(StringBuilder hocon, Map<String, String> options) {
        if (options == null) {
            return;
        }
        options.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || value.isBlank()) {
                return;
            }
            String v = value.trim();
            hocon.append("    ").append(key.trim()).append(" = ").append(RAW_VALUE.matcher(v).matches() ? v : str(v)).append('\n');
        });
    }

    private static String key(String value) {
        return BARE_KEY.matcher(value).matches() ? value : str(value);
    }

    /** A HOCON quoted string; the text inside is never substituted by HOCON. */
    static String str(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static ConsoleException invalid(String message) {
        return new ConsoleException(ErrorCodes.INVALID_ARGUMENT, message);
    }
}
