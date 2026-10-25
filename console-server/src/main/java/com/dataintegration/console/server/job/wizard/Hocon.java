package com.dataintegration.console.server.job.wizard;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Writing HOCON text for generated configs, shared by the generator and the connector parts. */
public final class Hocon {

    private static final Pattern RAW_VALUE = Pattern.compile("-?\\d+(\\.\\d+)?|true|false|\\[.*]|\\{.*}", Pattern.DOTALL);
    private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9_-]+");

    private Hocon() {}

    /** A HOCON quoted string; the text inside is never substituted by HOCON. */
    public static String str(String value) {
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

    /** An object key: bare when it can be, quoted otherwise. */
    public static String key(String value) {
        return BARE_KEY.matcher(value).matches() ? value : str(value);
    }

    /** {@code name = { key = "value" ... }} at option indentation. */
    public static void appendStringMap(StringBuilder hocon, String name, Map<String, String> values) {
        hocon.append("    ").append(name).append(" = {\n");
        values.forEach((key, value) -> hocon.append("      ").append(key(key)).append(" = ").append(str(value)).append('\n'));
        hocon.append("    }\n");
    }

    /**
     * Advanced options, refusing the ones the wizard writes itself: written after the generated
     * ones, they would win.
     */
    public static void appendOptions(StringBuilder hocon, Map<String, String> options, Set<String> generated) {
        if (options != null) {
            options.forEach((key, value) -> {
                if (key != null && value != null && !value.isBlank() && generated.contains(key.trim())) {
                    throw invalid("高级参数不能设置由向导生成的 " + key.trim());
                }
            });
        }
        appendOptions(hocon, options);
    }

    /** Advanced options: numbers, booleans, lists and objects written as-is, everything else quoted. */
    public static void appendOptions(StringBuilder hocon, Map<String, String> options) {
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

    public static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    public static ConsoleException invalid(String message) {
        return new ConsoleException(ErrorCodes.INVALID_ARGUMENT, message);
    }
}
