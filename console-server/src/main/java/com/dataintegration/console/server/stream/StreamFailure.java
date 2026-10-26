package com.dataintegration.console.server.stream;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads why a streaming engine job failed from its log, since Zeta 3.0's job info carries no error
 * (docs/design.md §12 P2, 实测结论). A failure caused by a table-structure change happens again on
 * every restore, which replays the same change. Pure.
 *
 * @param summary the root cause as the log states it, or null when the log shows none
 * @param schemaChange the failure comes from a table-structure change
 */
public record StreamFailure(String summary, boolean schemaChange) {

    /** Lines the tested schema-change failures log (SeaTunnel 3.0.1). */
    private static final List<Pattern> SCHEMA_CHANGE = List.of(
            Pattern.compile("Unsupported schemaChangeEvent"),
            Pattern.compile("SE-07"),
            Pattern.compile("Schema change type is not supported"),
            Pattern.compile("AlterTableSchemaEventHandler"),
            Pattern.compile("SchemaChange\\w*Exception"));

    private static final Pattern EXCEPTION = Pattern.compile("^(?:Caused by: )?((?:[\\w$]+\\.)+[\\w$]*(?:Exception|Error)\\b.*)$");

    public static StreamFailure fromLog(String log) {
        if (log == null || log.isBlank()) {
            return new StreamFailure(null, false);
        }
        boolean schemaChange = SCHEMA_CHANGE.stream().anyMatch(p -> p.matcher(log).find());
        return new StreamFailure(rootCause(log.split("\\R")), schemaChange);
    }

    /**
     * The deepest "Caused by" of the first stack trace that is not an interruption: later traces
     * are mostly other tasks being cancelled because of it.
     */
    static String rootCause(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            Matcher start = EXCEPTION.matcher(lines[i].trim());
            if (!start.matches() || lines[i].startsWith("Caused by") || start.group(1).contains("InterruptedException")) {
                continue;
            }
            String deepest = start.group(1);
            for (int k = i + 1; k < lines.length; k++) {
                String line = lines[k].trim();
                if (line.startsWith("Caused by: ")) {
                    deepest = line.substring("Caused by: ".length());
                } else if (!line.startsWith("at ") && !line.startsWith("...") && !line.startsWith("Suppressed")) {
                    break;
                }
            }
            return clip(deepest);
        }
        return null;
    }

    /** Why restoring cannot help, recorded on the job so the next start is fresh. */
    public String restoreBlocked() {
        return schemaChange ? "上次因为源表结构变更失败，从 checkpoint 恢复会重放同一条变更再次失败" : null;
    }

    private static String clip(String text) {
        return text.length() <= 400 ? text : text.substring(0, 400) + "...";
    }
}
