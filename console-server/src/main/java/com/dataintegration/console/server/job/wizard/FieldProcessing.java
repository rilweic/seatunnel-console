package com.dataintegration.console.server.job.wizard;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.server.job.wizard.WizardForm.AddedField;
import com.dataintegration.console.server.job.wizard.WizardForm.Cast;
import com.dataintegration.console.server.job.wizard.WizardForm.Extract;
import com.dataintegration.console.server.job.wizard.WizardForm.ExtractKind;
import com.dataintegration.console.server.job.wizard.WizardForm.ExtractOutput;
import com.dataintegration.console.server.job.wizard.WizardForm.FieldProcess;
import com.dataintegration.console.server.job.wizard.WizardForm.FieldType;
import com.dataintegration.console.server.job.wizard.WizardForm.Mask;
import com.dataintegration.console.server.job.wizard.WizardForm.MaskPreset;
import com.dataintegration.console.server.job.wizard.WizardForm.TableSpec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a table's field processing (docs/design.md §7.10) into SeaTunnel transforms: JsonPath,
 * RegexExtract and Split add the extracted fields, then one Sql transform per table selects every
 * field the target gets, with replace, mask and type conversion as expressions and the new fields
 * appended. Pure; verified against SeaTunnel 3.0 by running the generated config.
 *
 * <p>Plain column references keep the source column's type and length (Sql copies them), so names
 * are only quoted when they must be: a backquoted reference loses its length, which breaks e.g. a
 * CHAR primary key on an auto-created MySQL table.
 */
public final class FieldProcessing {

    private static final Pattern SIMPLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** Words the Sql transform's parser would not take as a bare column name. */
    private static final Set<String> RESERVED = Set.of(
            "all", "and", "any", "as", "asc", "between", "by", "case", "check", "column", "date", "default", "desc",
            "distinct", "else", "end", "exists", "false", "from", "group", "having", "in", "index", "interval", "is",
            "join", "key", "left", "like", "limit", "not", "null", "offset", "on", "or", "order", "primary", "right",
            "row", "rows", "select", "table", "then", "time", "timestamp", "true", "union", "user", "value", "values",
            "when", "where");

    private FieldProcessing() {}

    /** The fields a table's Sql transform outputs, in order: synced fields (less dropped sources), extracted, added. */
    public static List<String> outputFields(TableSpec table, List<String> synced) {
        Set<String> dropped = droppedSources(table);
        List<String> out = new ArrayList<>(synced.stream().filter(c -> !dropped.contains(c)).toList());
        extracts(table).forEach(e -> e.outputs().forEach(o -> out.add(o.name().trim())));
        added(table).forEach(a -> out.add(a.name().trim()));
        return out;
    }

    /** Source fields left out of the target after extracting from them. */
    public static Set<String> droppedSources(TableSpec table) {
        Set<String> dropped = new LinkedHashSet<>();
        extracts(table).stream().filter(Extract::dropSource).forEach(e -> dropped.add(e.source()));
        return dropped;
    }

    /** The table's Sql query, or null when nothing is processed. */
    public static String sqlQuery(TableSpec table, List<String> synced) {
        if (!table.processed()) {
            return null;
        }
        Set<String> dropped = droppedSources(table);
        Map<String, FieldProcess> processes = table.processes() == null ? Map.of() : table.processes();
        List<String> select = new ArrayList<>();
        for (String column : synced) {
            if (dropped.contains(column)) {
                continue;
            }
            FieldProcess process = processes.get(column);
            String expr = process == null ? null : processed(ident(column), process);
            select.add(expr == null ? ident(column) : expr + " as " + ident(column));
        }
        for (Extract e : extracts(table)) {
            for (ExtractOutput o : e.outputs()) {
                String name = ident(o.name().trim());
                select.add(o.type() == null || o.type() == FieldType.STRING ? name : cast(name, o.type(), o.precision(), o.scale()) + " as " + name);
            }
        }
        for (AddedField a : added(table)) {
            select.add(added(a) + " as " + ident(a.name().trim()));
        }
        return "select " + String.join(", ", select) + " from dual";
    }

    /** Replace, mask, then convert; null when the process does none of them (e.g. encryption only). */
    static String processed(String column, FieldProcess process) {
        String expr = column;
        boolean changed = false;
        if (process.replace() != null && notEmpty(process.replace().find())) {
            String text = "TRY_CAST(" + expr + " AS STRING)";
            String replacement = process.replace().replacement() == null ? "" : process.replace().replacement();
            expr = process.replace().regex()
                    ? "REGEXP_REPLACE(" + text + ", " + lit(process.replace().find()) + ", " + lit(replacement) + ")"
                    : "REPLACE(" + text + ", " + lit(process.replace().find()) + ", " + lit(replacement) + ")";
            changed = true;
        }
        if (process.mask() != null && process.mask().preset() != null) {
            expr = mask(expr, process.mask());
            changed = true;
        }
        if (process.cast() != null && process.cast().type() != null) {
            Cast c = process.cast();
            expr = cast(expr, c.type(), c.precision(), c.scale());
            changed = true;
        }
        return changed ? expr : null;
    }

    /** Masks all but the kept characters; a value no longer than what is kept stays as it is. */
    static String mask(String expr, Mask mask) {
        String s = "TRY_CAST(" + expr + " AS STRING)";
        String ch = notEmpty(mask.maskChar()) ? mask.maskChar().substring(0, 1) : "*";
        if (mask.preset() == MaskPreset.EMAIL) {
            // first character, a fixed run of mask characters, then from the @ on
            return "CASE WHEN LOCATE('@', " + s + ") > 1 THEN CONCAT(LEFT(" + s + ", 1), " + lit(ch.repeat(3))
                    + ", SUBSTRING(" + s + ", LOCATE('@', " + s + "))) ELSE " + s + " END";
        }
        int first = mask.preset() == MaskPreset.CUSTOM ? nonNegative(mask.keepFirst()) : mask.preset().keepFirst;
        int last = mask.preset() == MaskPreset.CUSTOM ? nonNegative(mask.keepLast()) : mask.preset().keepLast;
        List<String> parts = new ArrayList<>();
        if (first > 0) {
            parts.add("LEFT(" + s + ", " + first + ")");
        }
        parts.add("REPEAT(" + lit(ch) + ", CHAR_LENGTH(" + s + ") - " + (first + last) + ")");
        if (last > 0) {
            parts.add("RIGHT(" + s + ", " + last + ")");
        }
        return "CASE WHEN CHAR_LENGTH(" + s + ") > " + (first + last) + " THEN CONCAT(" + String.join(", ", parts)
                + ") ELSE " + s + " END";
    }

    static String added(AddedField a) {
        List<String> f = a.fields() == null ? List.of() : a.fields();
        String expr = switch (a.kind()) {
            case CONSTANT -> constant(a);
            case CONCAT -> "CONCAT_WS(" + lit(a.value() == null ? "" : a.value()) + ", "
                    + String.join(", ", f.stream().map(FieldProcessing::ident).toList()) + ")";
            case SUBSTRING -> switch (upper(a.op())) {
                case "RIGHT" -> "RIGHT(" + ident(field(a, 0)) + ", " + positive(a.length()) + ")";
                case "MID" -> "SUBSTRING(" + ident(field(a, 0)) + ", " + positive(a.start()) + ", " + positive(a.length()) + ")";
                default -> "LEFT(" + ident(field(a, 0)) + ", " + positive(a.length()) + ")";
            };
            case CASE -> switch (upper(a.op())) {
                case "LOWER" -> "LOWER(" + ident(field(a, 0)) + ")";
                case "TRIM" -> "TRIM(" + ident(field(a, 0)) + ")";
                default -> "UPPER(" + ident(field(a, 0)) + ")";
            };
            case DATE_FORMAT -> "FORMATDATETIME(" + ident(field(a, 0)) + ", " + lit(a.format()) + ")";
            case ARITHMETIC -> {
                String op = a.op() == null ? "+" : a.op().trim();
                if (!List.of("+", "-", "*", "/").contains(op)) {
                    throw invalid("新字段 " + a.name() + "：不支持的运算 " + op);
                }
                String right = f.size() > 1 && notEmpty(f.get(1)) ? ident(f.get(1)) : number(a);
                yield "(" + ident(field(a, 0)) + " " + op + " " + right + ")";
            }
            case DEFAULT -> "IFNULL(" + ident(field(a, 0)) + ", " + lit(a.value() == null ? "" : a.value()) + ")";
            case UUID -> "UUID()";
            case NOW -> "NOW()";
            case EXPRESSION -> {
                if (!notEmpty(a.value())) {
                    throw invalid("新字段 " + a.name() + "：表达式为空");
                }
                yield "(" + a.value().trim() + ")";
            }
        };
        return a.kind() == WizardForm.AddKind.CONSTANT || a.type() == null ? expr : cast(expr, a.type(), a.precision(), a.scale());
    }

    /** A constant or variable value of the field's type; dates and times are parsed with the value's format. */
    private static String constant(AddedField a) {
        String value = lit(a.value() == null ? "" : a.value());
        FieldType type = a.type() == null ? FieldType.STRING : a.type();
        return switch (type) {
            case STRING -> value;
            case DATE -> "PARSEDATETIME(" + value + ", " + lit(notEmpty(a.format()) ? a.format() : "yyyy-MM-dd") + ")";
            case TIMESTAMP -> "PARSEDATETIME(" + value + ", " + lit(notEmpty(a.format()) ? a.format() : "yyyy-MM-dd HH:mm:ss") + ")";
            default -> cast(value, type, a.precision(), a.scale());
        };
    }

    static String cast(String expr, FieldType type, Integer precision, Integer scale) {
        String sqlType = switch (type) {
            case STRING -> "STRING";
            case BIGINT -> "BIGINT";
            case DECIMAL -> "DECIMAL(" + (precision == null ? 18 : precision) + "," + (scale == null ? 2 : scale) + ")";
            case DATE -> "DATE";
            case TIMESTAMP -> "TIMESTAMP";
            case BOOLEAN -> "BOOLEAN";
        };
        return "TRY_CAST(" + expr + " AS " + sqlType + ")";
    }

    /** A column reference: bare when the parser takes it as one (keeping the column's type), else backquoted. */
    static String ident(String name) {
        return SIMPLE_NAME.matcher(name).matches() && !RESERVED.contains(name.toLowerCase(Locale.ROOT))
                ? name
                : "`" + name.replace("`", "``") + "`";
    }

    /** True when {@link #ident} quotes the name, so the field loses its declared length on the way. */
    static boolean quoted(String name) {
        return !ident(name).equals(name);
    }

    static String lit(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    // ---- extraction transforms: one HOCON object of options per table entry ----

    /** JsonPath options for the table, or null without JSON extracts. */
    static String jsonPathOptions(TableSpec table) {
        List<String> columns = new ArrayList<>();
        for (Extract e : extracts(table)) {
            if (e.kind() != ExtractKind.JSON) {
                continue;
            }
            for (ExtractOutput o : e.outputs()) {
                columns.add("{ src_field = " + WizardGenerator.str(e.source()) + ", path = " + WizardGenerator.str(o.path().trim())
                        + ", dest_field = " + WizardGenerator.str(o.name().trim()) + ", column_error_handle_way = \"SKIP\" }");
            }
        }
        return columns.isEmpty() ? null : "columns = [" + String.join(", ", columns) + "]";
    }

    /** RegexExtract or Split options of each such extract of the table. */
    static List<String> extractOptions(TableSpec table, ExtractKind kind) {
        List<String> entries = new ArrayList<>();
        for (Extract e : extracts(table)) {
            if (e.kind() != kind) {
                continue;
            }
            String outputs = "[" + String.join(", ", e.outputs().stream().map(o -> WizardGenerator.str(o.name().trim())).toList()) + "]";
            entries.add(kind == ExtractKind.REGEX
                    ? "source_field = " + WizardGenerator.str(e.source()) + ", regex_pattern = " + WizardGenerator.str(e.pattern())
                            + ", output_fields = " + outputs
                    : "separator = " + WizardGenerator.str(e.pattern()) + ", split_field = " + WizardGenerator.str(e.source())
                            + ", output_fields = " + outputs);
        }
        return entries;
    }

    private static List<Extract> extracts(TableSpec table) {
        return table.extracts() == null ? List.of() : table.extracts();
    }

    private static List<AddedField> added(TableSpec table) {
        return table.addedFields() == null ? List.of() : table.addedFields();
    }

    private static String field(AddedField a, int index) {
        if (a.fields() == null || a.fields().size() <= index || !notEmpty(a.fields().get(index))) {
            throw invalid("新字段 " + a.name() + "：请选择字段");
        }
        return a.fields().get(index);
    }

    private static String number(AddedField a) {
        if (a.value() == null || !a.value().trim().matches("-?\\d+(\\.\\d+)?")) {
            throw invalid("新字段 " + a.name() + "：请填写一个数字或选择另一个字段");
        }
        return a.value().trim();
    }

    private static int positive(Integer n) {
        return n == null || n < 1 ? 1 : n;
    }

    private static int nonNegative(Integer n) {
        return n == null || n < 0 ? 0 : n;
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private static ConsoleException invalid(String message) {
        return new ConsoleException(ErrorCodes.INVALID_ARGUMENT, message);
    }
}
