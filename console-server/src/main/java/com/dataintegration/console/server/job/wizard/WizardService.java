package com.dataintegration.console.server.job.wizard;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.datasource.CountRequest;
import com.dataintegration.console.datasource.meta.ColumnMeta;
import com.dataintegration.console.datasource.meta.TableMeta;
import com.dataintegration.console.server.datasource.DatasourceService;
import com.dataintegration.console.server.encryptkey.EncryptKeyService;
import com.dataintegration.console.server.job.Variables;
import com.dataintegration.console.server.job.wizard.WizardForm.DataMode;
import com.dataintegration.console.server.job.wizard.WizardForm.SchemaMode;
import com.dataintegration.console.server.job.wizard.WizardForm.SyncMode;
import com.dataintegration.console.server.job.wizard.WizardForm.TableSpec;
import com.dataintegration.console.server.job.wizard.WizardForm.WriteMode;
import com.dataintegration.console.server.job.wizard.WizardGenerator.Endpoints;
import com.dataintegration.console.server.job.wizard.WizardGenerator.Generated;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Generates wizard jobs and checks them against the live datasources before they are saved
 * (docs/design.md §7.2). ERROR blocks saving, WARN is shown but allowed, INFO explains.
 */
@Service
public class WizardService {

    public enum Level {
        ERROR,
        WARN,
        INFO
    }

    public record Check(Level level, String message) {}

    /** @param generated null when the form is too incomplete to generate */
    public record PrecheckResult(List<Check> checks, Generated generated) {

        public boolean passed() {
            return checks.stream().noneMatch(c -> c.level() == Level.ERROR);
        }

        public List<String> errors() {
            return checks.stream().filter(c -> c.level() == Level.ERROR).map(Check::message).toList();
        }
    }

    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Characters a target table or field name may not hold: dots and quotes would break table
     * paths and generated SQL, and {@code $} would be read as a variable or placeholder.
     */
    private static final Pattern BAD_NAME = Pattern.compile("[\\s.$`\"'\\\\]");

    private final DatasourceService datasourceService;

    private final EncryptKeyService encryptKeys;

    public WizardService(DatasourceService datasourceService, EncryptKeyService encryptKeys) {
        this.datasourceService = datasourceService;
        this.encryptKeys = encryptKeys;
    }

    public Endpoints endpoints(WizardForm form) {
        String sourceType = datasourceService.typeOf(form.source().datasourceId());
        String targetType = datasourceService.typeOf(form.target().datasourceId());
        // A field rule on an all-columns table renames the columns the table has now.
        Map<String, List<String>> sourceColumns = new LinkedHashMap<>();
        if (form.source().tables() != null) {
            for (TableSpec table : form.source().tables()) {
                if (WizardGenerator.needsColumns(form, table)) {
                    try {
                        sourceColumns.put(table.name(), datasourceService.columns(
                                form.source().datasourceId(), form.source().namespace(), table.name()).stream().map(ColumnMeta::name).toList());
                    } catch (ConsoleException e) {
                        // reported by the source check
                    }
                }
            }
        }
        return new Endpoints(
                sourceType,
                WizardGenerator.POSTGRESQL.equals(sourceType) ? datasourceService.property(form.source().datasourceId(), "database") : null,
                targetType,
                WizardGenerator.POSTGRESQL.equals(targetType) ? datasourceService.property(form.target().datasourceId(), "database") : null,
                sourceColumns);
    }

    /**
     * @param variables the job's variables as a run now would see them, for rendering filters
     */
    public PrecheckResult precheck(WizardForm form, Map<String, String> variables) {
        List<Check> checks = new ArrayList<>();
        Endpoints endpoints;
        Generated generated;
        try {
            endpoints = endpoints(form);
            generated = WizardGenerator.generate(form, endpoints);
        } catch (ConsoleException e) {
            checks.add(new Check(Level.ERROR, e.getMessage()));
            return new PrecheckResult(checks, null);
        }

        boolean full = form.sync().mode() == SyncMode.FULL;
        if (!full && form.target().dataMode() == DataMode.CLEAR) {
            checks.add(new Check(Level.ERROR, "增量同步不能选择\"清空后写入\"，否则每次只会留下最近一个时间窗口的数据"));
        }
        if (form.target().dataMode() == DataMode.CLEAR) {
            checks.add(new Check(Level.WARN, "每次运行都会先清空目标表（或索引）里的已有数据，再写入"));
        }

        Map<String, List<ColumnMeta>> sourceColumns = checkSource(form, endpoints, variables, checks);
        if (sourceColumns != null && checkNames(form, endpoints, sourceColumns, checks)) {
            checkTarget(form, endpoints, sourceColumns, checks);
        }
        generated.notes().forEach(n -> checks.add(new Check(Level.INFO, n)));
        return new PrecheckResult(checks, generated);
    }

    /** Returns the selected columns of every source table that exists, or null when the source is unusable. */
    private Map<String, List<ColumnMeta>> checkSource(
            WizardForm form, Endpoints endpoints, Map<String, String> variables, List<Check> checks) {
        String ds = form.source().datasourceId();
        Map<String, TableMeta> tables;
        try {
            if (!datasourceService.namespaces(ds).contains(form.source().namespace())) {
                checks.add(new Check(Level.ERROR, "源端不存在库或 schema " + form.source().namespace()));
                return null;
            }
            tables = datasourceService.tables(ds, form.source().namespace()).stream()
                    .collect(Collectors.toMap(TableMeta::name, Function.identity()));
        } catch (ConsoleException e) {
            checks.add(new Check(Level.ERROR, "无法读取源端：" + e.getMessage()));
            return null;
        }

        Map<String, List<ColumnMeta>> selected = new LinkedHashMap<>();
        boolean full = form.sync().mode() == SyncMode.FULL;
        for (TableSpec table : form.source().tables()) {
            TableMeta meta = tables.get(table.name());
            if (meta == null) {
                checks.add(new Check(Level.ERROR, "源表不存在：" + table.name()));
                continue;
            }
            List<ColumnMeta> columns = datasourceService.columns(ds, form.source().namespace(), table.name());
            Map<String, ColumnMeta> byName = columns.stream().collect(Collectors.toMap(ColumnMeta::name, Function.identity()));
            if (!table.allColumns()) {
                List<String> missing = table.columns().stream().filter(c -> !byName.containsKey(c)).toList();
                if (!missing.isEmpty()) {
                    checks.add(new Check(Level.ERROR, table.name() + "：字段不存在 " + String.join(", ", missing)));
                    continue;
                }
            }
            String field = WizardGenerator.incrementalField(form, table);
            if (field != null) {
                ColumnMeta column = byName.get(field);
                if (column == null) {
                    checks.add(new Check(Level.ERROR, table.name() + "：增量字段 " + field + " 不存在"));
                } else if (!column.temporal()) {
                    checks.add(new Check(Level.ERROR, table.name() + "：增量字段 " + field + " 是 " + column.type()
                            + "，不是时间类型；数字型字段请用\"自定义条件\""));
                }
            }
            if (meta.primaryKeys().isEmpty()) {
                if (form.target().writeMode() == WriteMode.UPSERT) {
                    checks.add(new Check(Level.WARN, table.name() + "：没有主键，只能按追加方式写入"));
                }
                if (full && form.target().dataMode() == DataMode.KEEP) {
                    checks.add(new Check(Level.WARN, table.name() + "：没有主键又保留已有数据，每次全量运行都会再写入一遍，造成重复"));
                }
            } else if (form.target().writeMode() == WriteMode.UPSERT && !table.allColumns()
                    && !table.columns().containsAll(meta.primaryKeys())) {
                // The generated sink upserts by ${primary_key}; without the key columns the engine
                // fails at start-up instead of here.
                List<String> missingKeys = meta.primaryKeys().stream().filter(k -> !table.columns().contains(k)).toList();
                checks.add(new Check(Level.ERROR, table.name() + "：按主键更新需要主键字段，请在字段映射里恢复 "
                        + String.join(", ", missingKeys) + "，或改为追加写入"));
            } else if (full && form.target().dataMode() == DataMode.KEEP && form.target().writeMode() == WriteMode.APPEND) {
                checks.add(new Check(Level.WARN, table.name() + "：追加写入又保留已有数据，每次全量运行都会重复写入，建议改为 upsert"));
            }
            checkQuery(form, endpoints, table, variables, checks);
            List<ColumnMeta> synced = table.allColumns() ? columns : table.columns().stream().map(byName::get).toList();
            checkProcessing(table, synced, meta.primaryKeys(), checks);
            if (!FieldProcessing.encryptedFields(table, synced.stream().map(ColumnMeta::name).toList()).isEmpty()
                    && table.encryptKeyId() != null && !table.encryptKeyId().isBlank()) {
                try {
                    encryptKeys.requireUsable(table.encryptKeyId());
                } catch (ConsoleException e) {
                    checks.add(new Check(Level.ERROR, table.name() + "：" + e.getMessage()));
                }
            }
            selected.put(table.name(), synced);
        }
        return selected;
    }

    /** Runs the table's generated select under {@code where 1 = 0}, so a bad filter fails here and not at run time. */
    private void checkQuery(
            WizardForm form, Endpoints endpoints, TableSpec table, Map<String, String> variables, List<Check> checks) {
        String query = WizardGenerator.sourceQuery(form, endpoints, table, false);
        if (query == null) {
            return;
        }
        String rendered = Variables.render(query, variables, LocalDateTime.now());
        try {
            datasourceService.count(form.source().datasourceId(),
                    CountRequest.sql("select count(*) from (" + rendered + ") t_check where 1 = 0", CHECK_TIMEOUT));
        } catch (ConsoleException e) {
            checks.add(new Check(Level.ERROR, table.name() + "：生成的查询执行失败（检查过滤条件）：" + e.getMessage()));
        }
    }

    /**
     * Target table and field names (§7.8): none empty, none with characters that break paths or
     * SQL, and no two the same, ignoring case (MySQL and ES often do). Returns false when the
     * target cannot be checked against these names.
     */
    private boolean checkNames(
            WizardForm form, Endpoints endpoints, Map<String, List<ColumnMeta>> sourceColumns, List<Check> checks) {
        boolean es = WizardGenerator.ELASTICSEARCH.equals(endpoints.targetType());
        String what = es ? "索引" : "目标表";
        boolean ok = true;
        Map<String, String> tablesByName = new HashMap<>();
        for (TableSpec table : form.source().tables()) {
            String name = WizardGenerator.targetName(form, table, es);
            if (name.isBlank()) {
                checks.add(new Check(Level.ERROR, table.name() + "：" + what + "名为空"));
                ok = false;
                continue;
            }
            if (BAD_NAME.matcher(name).find()) {
                checks.add(new Check(Level.ERROR, table.name() + "：" + what + "名 " + name + " 不能包含空格、点、引号、$ 或反斜杠"));
                ok = false;
            }
            String other = tablesByName.putIfAbsent(name.toLowerCase(Locale.ROOT), table.name());
            if (other != null) {
                checks.add(new Check(Level.ERROR, other + " 和 " + table.name() + " 映射到同一个" + what + " " + name));
                ok = false;
            }
            List<ColumnMeta> columns = sourceColumns.get(table.name());
            if (columns == null) {
                continue;
            }
            Map<String, String> fieldsByName = new HashMap<>();
            for (Map.Entry<String, String> entry : targetFields(form, table, columns).entrySet()) {
                String from = entry.getKey();
                String field = entry.getValue();
                if (field.isBlank()) {
                    checks.add(new Check(Level.ERROR, table.name() + "：字段 " + from + " 的目标字段名为空"));
                    ok = false;
                    continue;
                }
                if (BAD_NAME.matcher(field).find()) {
                    checks.add(new Check(Level.ERROR, table.name() + "：目标字段名 " + field + " 不能包含空格、点、引号、$ 或反斜杠"));
                    ok = false;
                }
                String clash = fieldsByName.putIfAbsent(field.toLowerCase(Locale.ROOT), from);
                if (clash != null) {
                    checks.add(new Check(Level.ERROR, table.name() + "：" + clash + " 和 " + from + " 映射到同一个目标字段 " + field));
                    ok = false;
                }
            }
        }
        return ok;
    }

    /**
     * Every field the target table gets, keyed by where it comes from: synced fields under their
     * mapped names (less sources dropped after extraction), then extracted and added fields.
     */
    private static Map<String, String> targetFields(WizardForm form, TableSpec table, List<ColumnMeta> synced) {
        Set<String> dropped = FieldProcessing.droppedSources(table);
        Map<String, String> fields = new LinkedHashMap<>();
        synced.stream().map(ColumnMeta::name).filter(c -> !dropped.contains(c))
                .forEach(c -> fields.put("字段 " + c, WizardGenerator.targetField(form, table, c)));
        if (table.extracts() != null) {
            table.extracts().forEach(e -> e.outputs().forEach(o -> fields.put("提取字段 " + o.name(), o.name() == null ? "" : o.name().trim())));
        }
        if (table.addedFields() != null) {
            table.addedFields().forEach(a -> fields.put("新字段 " + a.name(), a.name() == null ? "" : a.name().trim()));
        }
        return fields;
    }

    /**
     * Field processing (§7.10): what it refers to exists and is synced, primary keys stay as they
     * are, and the patterns and types are usable. A hand-written expression is only checked when
     * the job runs, so it is pointed out.
     */
    private static void checkProcessing(TableSpec table, List<ColumnMeta> synced, List<String> primaryKeys, List<Check> checks) {
        String t = table.name() + "：";
        Set<String> available = synced.stream().map(ColumnMeta::name).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        Set<String> sourceFields = Set.copyOf(available);
        if (table.processes() != null) {
            table.processes().forEach((field, process) -> {
                if (!sourceFields.contains(field)) {
                    checks.add(new Check(Level.ERROR, t + "处理的字段 " + field + " 没有同步或不存在"));
                } else if (primaryKeys.contains(field) && (process.replace() != null || process.mask() != null
                        || process.cast() != null || process.encrypted())) {
                    checks.add(new Check(Level.ERROR, t + "主键字段 " + field + " 不能做替换、脱敏、类型转换或加密：目标表按主键写入，主键的值和类型要保持不变"));
                }
                if (process.cast() != null) {
                    checkDecimal(t + "字段 " + field, process.cast().type(), process.cast().precision(), process.cast().scale(), checks);
                }
                if (process.replace() != null && process.replace().regex() && process.replace().find() != null) {
                    checkRegex(t + "字段 " + field + " 的替换规则", process.replace().find(), -1, checks);
                }
            });
        }
        if (table.extracts() != null) {
            for (WizardForm.Extract e : table.extracts()) {
                if (!sourceFields.contains(e.source())) {
                    checks.add(new Check(Level.ERROR, t + "提取的来源字段 " + e.source() + " 没有同步或不存在"));
                    continue;
                }
                if (e.dropSource() && primaryKeys.contains(e.source())) {
                    checks.add(new Check(Level.ERROR, t + "主键字段 " + e.source() + " 不能在提取后去掉"));
                }
                if (e.outputs() == null || e.outputs().isEmpty()) {
                    checks.add(new Check(Level.ERROR, t + "从 " + e.source() + " 提取时至少要有一个新字段"));
                    continue;
                }
                switch (e.kind()) {
                    case JSON -> e.outputs().stream().filter(o -> o.path() == null || !o.path().trim().startsWith("$"))
                            .forEach(o -> checks.add(new Check(Level.ERROR, t + "新字段 " + o.name() + " 的 JSON 路径要以 $ 开头，如 $.address.city")));
                    case REGEX -> checkRegex(t + "从 " + e.source() + " 提取的正则", e.pattern(), e.outputs().size(), checks);
                    case SPLIT -> {
                        if (e.pattern() == null || e.pattern().isEmpty()) {
                            checks.add(new Check(Level.ERROR, t + "拆分 " + e.source() + " 需要填写分隔符"));
                        }
                    }
                }
                e.outputs().forEach(o -> {
                    checkDecimal(t + "新字段 " + o.name(), o.type(), o.precision(), o.scale(), checks);
                    available.add(o.name());
                });
            }
        }
        if (table.addedFields() != null) {
            for (WizardForm.AddedField a : table.addedFields()) {
                if (a.kind() == null) {
                    checks.add(new Check(Level.ERROR, t + "新字段 " + a.name() + " 没有选择计算方式"));
                    continue;
                }
                if (a.fields() != null) {
                    a.fields().stream().filter(f -> f != null && !f.isEmpty() && !available.contains(f))
                            .forEach(f -> checks.add(new Check(Level.ERROR, t + "新字段 " + a.name() + " 用到的字段 " + f + " 没有同步或不存在")));
                }
                if (a.kind() == WizardForm.AddKind.EXPRESSION) {
                    checks.add(new Check(Level.WARN, t + "新字段 " + a.name() + " 用了手写表达式，写法只有运行时才会检查，请在第一次运行后确认结果"));
                }
                checkDecimal(t + "新字段 " + a.name(), a.type(), a.precision(), a.scale(), checks);
                try {
                    FieldProcessing.added(a);
                } catch (ConsoleException ex) {
                    checks.add(new Check(Level.ERROR, ex.getMessage()));
                }
            }
        }
    }

    private static void checkDecimal(String what, WizardForm.FieldType type, Integer precision, Integer scale, List<Check> checks) {
        if (type != WizardForm.FieldType.DECIMAL) {
            return;
        }
        int p = precision == null ? 18 : precision;
        int s = scale == null ? 2 : scale;
        if (p < 1 || p > 38 || s < 0 || s > p) {
            checks.add(new Check(Level.ERROR, what + "：小数的总位数要在 1～38 之间，小数位不能超过总位数"));
        }
    }

    /** @param groups the capturing groups the pattern must have, or -1 for any */
    private static void checkRegex(String what, String pattern, int groups, List<Check> checks) {
        try {
            int count = java.util.regex.Pattern.compile(pattern == null ? "" : pattern).matcher("").groupCount();
            if (groups >= 0 && count != groups) {
                checks.add(new Check(Level.ERROR, what + "有 " + count + " 个分组（括号），但要提取 " + groups + " 个新字段，两者要一致"));
            }
        } catch (java.util.regex.PatternSyntaxException e) {
            checks.add(new Check(Level.ERROR, what + "写法不对：" + e.getDescription()));
        }
    }

    private void checkTarget(
            WizardForm form, Endpoints endpoints, Map<String, List<ColumnMeta>> sourceColumns, List<Check> checks) {
        String ds = form.target().datasourceId();
        boolean es = WizardGenerator.ELASTICSEARCH.equals(endpoints.targetType());
        Set<String> existing;
        try {
            if (!es && !datasourceService.namespaces(ds).contains(form.target().namespace())) {
                checks.add(new Check(Level.ERROR, "目标端不存在库或 schema " + form.target().namespace() + "（不会自动建库）"));
                return;
            }
            existing = datasourceService.tables(ds, es ? null : form.target().namespace()).stream()
                    .map(TableMeta::name)
                    .collect(Collectors.toSet());
        } catch (ConsoleException e) {
            checks.add(new Check(Level.ERROR, "无法读取目标端：" + e.getMessage()));
            return;
        }
        String what = es ? "索引" : "目标表";
        for (TableSpec table : form.source().tables()) {
            if (!sourceColumns.containsKey(table.name())) {
                continue;
            }
            String name = WizardGenerator.targetName(form, table, es);
            if (!existing.contains(name)) {
                checks.add(form.target().schemaMode() == SchemaMode.ERROR
                        ? new Check(Level.ERROR, what + " " + name + " 不存在，而选项是\"不存在时报错\"")
                        : new Check(Level.INFO, what + " " + name + " 不存在，运行时自动创建"));
                continue;
            }
            if (es) {
                continue;
            }
            Set<String> targetColumns = datasourceService.columns(ds, form.target().namespace(), name).stream()
                    .map(c -> c.name().toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            List<String> mapped = List.copyOf(targetFields(form, table, sourceColumns.get(table.name())).values());
            Set<String> mappedLower = mapped.stream().map(f -> f.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            List<String> missing = mapped.stream().filter(f -> !targetColumns.contains(f.toLowerCase(Locale.ROOT))).toList();
            if (!missing.isEmpty()) {
                checks.add(new Check(Level.ERROR, "目标表 " + name + " 缺少字段：" + String.join(", ", missing)
                        + "。已存在的目标表不会自动加字段，请先在目标库里加上这些字段，或者写到一张新表"));
            }
            List<String> extra = targetColumns.stream().filter(c -> !mappedLower.contains(c)).sorted().toList();
            if (!extra.isEmpty()) {
                checks.add(new Check(Level.INFO, "目标表 " + name + " 还有这些字段不会写入：" + String.join(", ", extra)));
            }
        }
    }
}
