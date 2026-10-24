package com.dataintegration.console.server.job;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DolphinScheduler-style variables (docs/design.md §8), resolved against the run's scheduled time.
 *
 * <p>Priority, highest first: startup params &gt; job params &gt; project params &gt; built-ins.
 * Parameter values may themselves use built-ins and {@code $[...]}, not other user parameters.
 *
 * <p>Substitution is textual on the HOCON template, so it also works inside quoted strings where
 * HOCON would not substitute. Only known names are replaced: any other {@code ${...}} is left for
 * HOCON to resolve (e.g. environment variables), and {@code ${?x}} is never touched.
 */
public final class Variables {

    public static final String BIZ_DATE = "system.biz.date";
    public static final String BIZ_CURDATE = "system.biz.curdate";
    public static final String DATETIME = "system.datetime";
    public static final String INSTANCE_ID = "system.task.instance.id";
    public static final String JOB_NAME = "system.task.definition.name";
    public static final String PROJECT_NAME = "system.project.name";

    /**
     * SeaTunnel's own sink placeholders (3.0 TablePlaceholder), filled per table by the engine. They
     * are never console variables and are not reported as unresolved.
     */
    static final Set<String> SEATUNNEL_PLACEHOLDERS = Set.of(
            "database_name", "schema_name", "schema_full_name", "table_name", "table_full_name",
            "primary_key", "unique_key", "field_names", "partition_keys");

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.]*)}");
    private static final Pattern TIME = Pattern.compile("\\$\\[([^\\]]+)]");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private Variables() {}

    /** Rejects keys that could not be referenced as ${key}, and the reserved system. prefix. */
    public static void validateName(String key) {
        if (key == null || !NAME.matcher(key).matches()) {
            throw new ConsoleException(
                    ErrorCodes.INVALID_ARGUMENT,
                    "parameter name '" + key + "' must start with a letter or _ and contain only letters, digits, _ and .");
        }
        if (key.startsWith("system.")) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "parameter names starting with system. are reserved: " + key);
        }
        if (SEATUNNEL_PLACEHOLDERS.contains(key)) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "parameter name " + key + " is reserved by SeaTunnel as a table placeholder");
        }
    }

    public static Map<String, String> builtins(
            LocalDateTime scheduledTime, String instanceId, String jobName, String projectName) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put(BIZ_DATE, DATE.format(scheduledTime.minusDays(1)));
        vars.put(BIZ_CURDATE, DATE.format(scheduledTime));
        vars.put(DATETIME, DATE_TIME.format(scheduledTime));
        if (instanceId != null) {
            vars.put(INSTANCE_ID, instanceId);
        }
        if (jobName != null) {
            vars.put(JOB_NAME, jobName);
        }
        if (projectName != null) {
            vars.put(PROJECT_NAME, projectName);
        }
        return vars;
    }

    /**
     * Merges the layers (lowest priority first after the built-ins) and renders every user value
     * against the built-ins and the scheduled time. Returns built-ins plus user parameters.
     */
    @SafeVarargs
    public static Map<String, String> resolve(
            Map<String, String> builtins, LocalDateTime scheduledTime, Map<String, String>... layers) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (Map<String, String> layer : layers) {
            if (layer != null) {
                layer.forEach((k, v) -> {
                    validateName(k);
                    merged.put(k, v == null ? "" : v);
                });
            }
        }
        Map<String, String> resolved = new LinkedHashMap<>(builtins);
        merged.forEach((k, v) -> resolved.put(k, render(v, builtins, scheduledTime)));
        return resolved;
    }

    /** Replaces $[...] time expressions and ${name} for every known name; leaves the rest. */
    public static String render(String text, Map<String, String> vars, LocalDateTime scheduledTime) {
        Matcher time = TIME.matcher(text);
        StringBuilder out = new StringBuilder();
        while (time.find()) {
            time.appendReplacement(out, Matcher.quoteReplacement(TimeExpression.evaluate(time.group(1), scheduledTime)));
        }
        time.appendTail(out);

        Matcher ref = REFERENCE.matcher(out.toString());
        StringBuilder result = new StringBuilder();
        while (ref.find()) {
            String value = vars.get(ref.group(1));
            ref.appendReplacement(result, Matcher.quoteReplacement(value == null ? ref.group() : value));
        }
        ref.appendTail(result);
        return result.toString();
    }

    /** ${name} references left after rendering: HOCON substitutions or typos in parameter names. */
    public static Set<String> unresolved(String rendered) {
        Set<String> names = new LinkedHashSet<>();
        Matcher ref = REFERENCE.matcher(rendered);
        while (ref.find()) {
            if (!SEATUNNEL_PLACEHOLDERS.contains(ref.group(1))) {
                names.add(ref.group(1));
            }
        }
        return names;
    }
}
