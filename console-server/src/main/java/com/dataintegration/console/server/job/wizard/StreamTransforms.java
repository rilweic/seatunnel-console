package com.dataintegration.console.server.job.wizard;

import com.dataintegration.console.server.job.wizard.WizardForm.TableSpec;

import java.util.List;
import java.util.Map;

/**
 * Transforms only real-time jobs need (docs/design.md §12 P2, 实测结论). A CDC source has no query
 * to prune columns with, so pruned columns are left out by name with {@code Filter}, which lets
 * columns added to the table later through. Skipping deletes is a {@code FilterRowKind}, which has
 * to come last: in front of another column-aware transform it fails the job on a column rename.
 */
final class StreamTransforms {

    private StreamTransforms() {}

    /** The table's columns that are not synced, from the columns known when the job was saved. */
    static List<String> excludedColumns(TableSpec table, Map<String, List<String>> known) {
        List<String> all = known == null ? null : known.get(table.name());
        if (all == null) {
            throw Hocon.invalid(table.name() + "：读不到源表的字段，无法生成屏蔽字段");
        }
        return all.stream().filter(c -> !table.columns().contains(c)).toList();
    }

    /** Appends the FilterRowKind that drops DELETE rows; returns the stream the sink then reads. */
    static String appendSkipDeletes(StringBuilder t, String input) {
        t.append("  FilterRowKind {\n    plugin_input = [").append(Hocon.str(input))
                .append("]\n    plugin_output = \"deletes_skipped\"\n    exclude_kinds = [\"DELETE\"]\n  }\n");
        return "deletes_skipped";
    }
}
