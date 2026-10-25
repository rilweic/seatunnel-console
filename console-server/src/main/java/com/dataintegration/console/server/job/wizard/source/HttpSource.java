package com.dataintegration.console.server.job.wizard.source;

import static com.dataintegration.console.server.job.wizard.Hocon.invalid;
import static com.dataintegration.console.server.job.wizard.Hocon.key;
import static com.dataintegration.console.server.job.wizard.Hocon.notBlank;
import static com.dataintegration.console.server.job.wizard.Hocon.str;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.datasource.http.HttpPreviewRequest;
import com.dataintegration.console.datasource.http.HttpPreviewResponse;
import com.dataintegration.console.datasource.meta.ColumnMeta;
import com.dataintegration.console.server.datasource.DatasourceService;
import com.dataintegration.console.server.job.Variables;
import com.dataintegration.console.server.job.wizard.Hocon;
import com.dataintegration.console.server.job.wizard.WizardConnectors;
import com.dataintegration.console.server.job.wizard.WizardForm;
import com.dataintegration.console.server.job.wizard.WizardForm.TableSpec;
import com.dataintegration.console.server.job.wizard.WizardGenerator;
import com.dataintegration.console.server.job.wizard.WizardGenerator.Endpoints;
import com.dataintegration.console.server.job.wizard.WizardService.Check;
import com.dataintegration.console.server.job.wizard.WizardService.Level;
import com.dataintegration.console.server.job.wizard.WizardSource;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One HTTP endpoint through SeaTunnel's Http source (docs/design.md §7.13): the request, paging,
 * the fields confirmed from a response preview and their JSON paths. The source names its table
 * after the target ({@code schema.table}), so no TableRename is needed.
 */
public final class HttpSource implements WizardSource {

    private static final Set<String> FIELD_TYPES = Set.of("STRING", "BIGINT", "DOUBLE", "BOOLEAN", "DATE", "TIMESTAMP", "DECIMAL");
    private static final Set<String> ADVANCED_OPTIONS = Set.of(
            "retry", "retry_backoff_multiplier_ms", "retry_backoff_max_ms", "json_filed_missed_return_null");
    /** Declared length of a string primary key, so it becomes VARCHAR(255). */
    private static final int STRING_KEY_LENGTH = 255;
    private static final Pattern BARE_FIELD = Pattern.compile("[A-Za-z0-9_-]+");
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /** The params and body of a request, paging applied. */
    public record RequestParts(Map<String, String> params, String body) {}

    /** The form's HTTP settings ({@code source.spec}), or null when they are missing. */
    public static HttpSpec spec(WizardForm form) {
        return form.source().spec(HttpSpec.class);
    }

    @Override
    public void requireShape(WizardForm form) {
        if (form.source().tables().size() != 1) {
            throw invalid("HTTP 任务只能配置一个接口");
        }
        HttpSpec http = spec(form);
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
        Map<String, HttpSpec.Field> fields = new LinkedHashMap<>();
        for (HttpSpec.Field field : http.fields()) {
            if (field == null || !notBlank(field.name()) || !notBlank(field.type())) {
                throw invalid("HTTP 字段名和类型不能为空");
            }
            fieldType(field);
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
        requirePaging(http.paging());
    }

    private static void requirePaging(HttpSpec.Paging paging) {
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
                if (paging.pageLocation() == HttpSpec.PageLocation.BODY) {
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

    @Override
    public void append(StringBuilder hocon, WizardForm form, Endpoints endpoints) {
        TableSpec table = form.source().tables().get(0);
        HttpSpec http = spec(form);
        HttpSpec.Paging paging = http.paging();
        RequestParts request = requestParts(http.params(), http.body(), paging, null);

        hocon.append("source {\n  Http {\n");
        hocon.append("    datasource_id = ").append(str(form.source().datasourceId())).append('\n');
        hocon.append("    url = ").append(str(http.path().trim())).append('\n');
        hocon.append("    method = ").append(str(http.method().name())).append('\n');
        if (!request.params().isEmpty()) {
            Hocon.appendStringMap(hocon, "params", request.params());
        }
        if (notBlank(request.body())) {
            hocon.append("    body = ").append(str(request.body())).append('\n');
        }
        hocon.append("    format = \"json\"\n");
        if (form.sourceOptions() == null || !form.sourceOptions().containsKey("json_filed_missed_return_null")) {
            hocon.append("    json_filed_missed_return_null = true\n");
        }
        List<HttpSpec.Field> fields = fields(http, table);
        boolean extractRows = fields.stream().anyMatch(f -> notBlank(f.jsonPath()))
                || notBlank(http.contentField()) && !"$".equals(http.contentField().trim());
        if (extractRows) {
            Map<String, String> jsonFields = new LinkedHashMap<>();
            fields.forEach(f -> jsonFields.put(f.name(), jsonPath(http.contentField(), f)));
            Hocon.appendStringMap(hocon, "json_field", jsonFields);
        }
        appendSchema(hocon, tablePath(form, endpoints, table), fields, http.primaryKeys());
        if (paging != null && (paging.mode() == HttpSpec.PagingMode.CURSOR
                || paging.mode() == HttpSpec.PagingMode.PAGE_NUMBER && paging.pageLocation() != HttpSpec.PageLocation.BODY)) {
            hocon.append("    keep_page_param_as_http_param = true\n");
        }
        appendPaging(hocon, paging);
        appendOptions(hocon, form.sourceOptions());
        hocon.append("    plugin_output = \"source\"\n  }\n}\n\n");
    }

    private static void appendSchema(StringBuilder hocon, String table, List<HttpSpec.Field> fields, List<String> keys) {
        hocon.append("    schema = {\n");
        hocon.append("      table = ").append(str(table)).append('\n');
        List<String> primaryKeys = keys == null ? List.of() : keys.stream().map(String::trim).toList();
        if (primaryKeys.isEmpty()) {
            hocon.append("      fields {\n");
            fields.forEach(f -> hocon.append("        ").append(key(f.name())).append(" = ").append(fieldType(f)).append('\n'));
            hocon.append("      }\n");
        } else {
            // schema.fields makes every column nullable, and MySQL / PostgreSQL refuse a nullable
            // primary key when the target table is created; a string key also needs a length, or
            // MySQL makes it LONGTEXT, which cannot be a key.
            hocon.append("      columns = [\n");
            fields.forEach(f -> {
                boolean key = primaryKeys.contains(f.name());
                hocon.append("        { name = ").append(str(f.name())).append(", type = ").append(fieldType(f));
                if (key && "STRING".equalsIgnoreCase(f.type().trim())) {
                    hocon.append(", columnLength = ").append(STRING_KEY_LENGTH);
                }
                hocon.append(", nullable = ").append(!key).append(" }\n");
            });
            hocon.append("      ]\n");
            hocon.append("      primaryKey {\n        name = \"http_pk\"\n        columnNames = [")
                    .append(String.join(", ", primaryKeys.stream().map(Hocon::str).toList()))
                    .append("]\n      }\n");
        }
        hocon.append("    }\n");
    }

    private static void appendPaging(StringBuilder hocon, HttpSpec.Paging paging) {
        if (paging == null || paging.mode() == HttpSpec.PagingMode.NONE) {
            return;
        }
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

    /** Only the options that do not touch what the wizard generates (format, schema, paging). */
    private static void appendOptions(StringBuilder hocon, Map<String, String> options) {
        if (options != null) {
            options.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null && !value.isBlank()
                        && !ADVANCED_OPTIONS.contains(key.trim())) {
                    throw invalid("不支持的 HTTP 高级参数：" + key);
                }
            });
        }
        Hocon.appendOptions(hocon, options);
    }

    private static String fieldType(HttpSpec.Field field) {
        String type = field.type().trim().toUpperCase(Locale.ROOT);
        if (!FIELD_TYPES.contains(type)) {
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

    private static String jsonPath(String contentField, HttpSpec.Field field) {
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

    private static List<HttpSpec.Field> fields(HttpSpec http, TableSpec table) {
        if (table.allColumns()) {
            return http.fields();
        }
        Map<String, HttpSpec.Field> byName = new LinkedHashMap<>();
        http.fields().forEach(f -> byName.put(f.name(), f));
        return table.columns().stream().map(byName::get).toList();
    }

    /**
     * Applies paging to the request params and body. Shared by the generated config and the preview,
     * so both send the same request.
     *
     * @param page value of the page field: null writes the connector's {@code ${page}} placeholder,
     *     the preview passes the first page number
     */
    public static RequestParts requestParts(Map<String, String> params, String body, HttpSpec.Paging paging, String page) {
        Map<String, String> result = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
        String resultBody = body;
        if (paging != null && paging.mode() != null) {
            switch (paging.mode()) {
                case PAGE_NUMBER -> {
                    String pageValue = page == null ? "${page}" : page;
                    if (paging.pageLocation() == HttpSpec.PageLocation.BODY) {
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
        return new RequestParts(result, resultBody);
    }

    /** A cursor response field as Jayway reads it: a bare name is a field of the root. */
    public static String cursorPath(String field) {
        String text = field == null ? "" : field.trim();
        return text.startsWith("$") ? text : "$." + text;
    }

    private static String pagedBody(String body, HttpSpec.Paging paging, String page) {
        JsonNode parsed;
        try {
            parsed = JSON.readTree(notBlank(body) ? body : "{}");
        } catch (RuntimeException e) {
            throw invalid("分页 Body 必须是有效的 JSON 对象");
        }
        if (!(parsed instanceof ObjectNode object)) {
            throw invalid("分页 Body 必须是 JSON 对象");
        }
        object.put(paging.pageField().trim(), page);
        object.put(paging.pageSizeField().trim(), paging.pageSize());
        return JSON.writeValueAsString(object);
    }

    /** The target name: the source names its table after it, so the sink needs no rename. */
    @Override
    public String tablePath(WizardForm form, Endpoints endpoints, TableSpec table) {
        return WizardGenerator.targetName(form, table, WizardConnectors.capabilities(endpoints.targetType()).lowerCaseNames());
    }

    @Override
    public String emittedName(WizardForm form, Endpoints endpoints, TableSpec table) {
        return tablePath(form, endpoints, table);
    }

    @Override
    public List<String> syncedColumns(WizardForm form, Endpoints endpoints, TableSpec table) {
        return table.allColumns() ? spec(form).fields().stream().map(HttpSpec.Field::name).toList() : table.columns();
    }

    @Override
    public Map<String, List<String>> knownColumns(WizardForm form, DatasourceService datasources) {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        if (form.source().tables() != null && spec(form) != null && spec(form).fields() != null) {
            List<String> names = spec(form).fields().stream().map(HttpSpec.Field::name).toList();
            form.source().tables().forEach(table -> columns.put(table.name(), names));
        }
        return columns;
    }

    @Override
    public String location(WizardForm form) {
        HttpSpec http = spec(form);
        return http == null ? null : http.path();
    }

    /** Without primary keys rows can only be appended, whatever the form says. */
    @Override
    public boolean upsertPossible(WizardForm form) {
        List<String> keys = spec(form).primaryKeys();
        return keys != null && !keys.isEmpty();
    }

    @Override
    public String uncountableNote() {
        return "HTTP 接口无法计数，不做行数校验";
    }

    /** Sends the first page exactly as the wizard is configured, variables rendered. */
    public HttpPreviewResponse preview(WizardForm form, Live live) {
        if (form.source() == null || spec(form) == null) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "请选择 HTTP 来源并填写请求");
        }
        HttpSpec http = spec(form);
        LocalDateTime now = LocalDateTime.now();
        Map<String, String> values = live.variables() == null ? Map.of() : live.variables();
        Map<String, String> params = new LinkedHashMap<>();
        if (http.params() != null) {
            http.params().forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null) {
                    params.put(key.trim(), Variables.render(value, values, now));
                }
            });
        }
        String body = http.body() == null ? null : Variables.render(http.body(), values, now);
        HttpSpec.Paging paging = http.paging();
        String firstPage = null;
        if (paging != null && paging.mode() == HttpSpec.PagingMode.PAGE_NUMBER) {
            if (!notBlank(paging.pageField()) || !notBlank(paging.pageSizeField()) || paging.pageSize() == null) {
                throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "请完整填写页码和每页条数字段");
            }
            firstPage = String.valueOf(paging.startPageNumber() == null ? 1 : paging.startPageNumber());
        }
        // the same assembly as the generated config, with the first page in place of ${page}
        RequestParts request = requestParts(params, body, paging, firstPage);
        String path = http.path() == null ? null : Variables.render(http.path(), values, now);
        return live.datasources().previewHttp(
                form.source().datasourceId(),
                new HttpPreviewRequest(path, http.method() == null ? "GET" : http.method().name(), request.params(), request.body()));
    }

    /**
     * Requests the first page: the data array is where {@code contentField} says, every field's
     * path is valid and found in the first records, and the cursor is there when paging by cursor.
     */
    @Override
    public Map<String, SourceTable> inspect(WizardForm form, Endpoints endpoints, Live live, List<Check> checks) {
        HttpSpec http = spec(form);
        JsonNode root;
        try {
            HttpPreviewResponse response = preview(form, live);
            if (response.status() < 200 || response.status() >= 300) {
                checks.add(new Check(Level.ERROR, "HTTP 请求返回状态 " + response.status()));
                return null;
            }
            root = JSON.readTree(response.body());
        } catch (ConsoleException | JacksonException e) {
            checks.add(new Check(Level.ERROR, "HTTP 请求或 JSON 解析失败：" + e.getMessage()));
            return null;
        }
        try {
            JsonNode content = HttpJsonPath.read(root, http.contentField());
            if (content == null || !content.isArray()) {
                checks.add(new Check(Level.ERROR, "content_field 没有取到 JSON 数组"));
                return null;
            }
            List<JsonNode> records = new ArrayList<>();
            content.forEach(record -> {
                if (records.size() < 20) {
                    records.add(record);
                }
            });
            if (records.isEmpty()) {
                checks.add(new Check(Level.WARN, "第一页的数据数组为空，只按已确认字段生成配置"));
            }
            for (HttpSpec.Field field : http.fields()) {
                boolean implicitPath = !notBlank(field.jsonPath());
                if (implicitPath && !BARE_FIELD.matcher(field.name()).matches()) {
                    throw invalid("HTTP 字段 " + field.name() + " 含特殊字符，请填写明确的 jsonPath");
                }
                String path = implicitPath ? field.name() : field.jsonPath();
                HttpJsonPath.requireValid(path);
                if (!records.isEmpty() && records.stream().noneMatch(record -> HttpJsonPath.read(record, path) != null)) {
                    checks.add(new Check(Level.ERROR, "前几条记录里找不到字段 " + field.name() + "（" + path + "）"));
                }
            }
            HttpSpec.Paging paging = http.paging();
            if (paging != null && paging.mode() == HttpSpec.PagingMode.CURSOR) {
                JsonNode cursor = HttpJsonPath.read(root, cursorPath(paging.cursorResponseField()));
                if (cursor == null || cursor.isNull() || cursor.isContainer() || cursor.asString().isBlank()) {
                    checks.add(new Check(Level.WARN, "第一页的响应里没有取到游标（" + paging.cursorResponseField()
                            + "），运行时只会读取这一页"));
                }
            }
        } catch (ConsoleException e) {
            checks.add(new Check(Level.ERROR, e.getMessage()));
            return null;
        }
        List<String> primaryKeys = http.primaryKeys() == null ? List.of() : http.primaryKeys();
        List<ColumnMeta> columns = http.fields().stream()
                .map(field -> new ColumnMeta(field.name(), field.type(), true, primaryKeys.contains(field.name()),
                        "DATE".equals(field.type()) || "TIMESTAMP".equals(field.type())))
                .toList();
        Map<String, SourceTable> found = new LinkedHashMap<>();
        found.put(form.source().tables().get(0).name(), new SourceTable(columns, primaryKeys));
        return found;
    }
}
