package com.dataintegration.console.server.stream;

import com.dataintegration.console.datasource.meta.ColumnMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * How a table's columns changed between two looks (docs/design.md §12 P2, 表结构变更的反馈). A
 * column that disappeared where one of the same type appeared at the same position counts as a
 * rename. Names compare ignoring case, as MySQL and Elasticsearch treat them. Pure.
 */
public record SchemaDiff(List<ColumnMeta> added, List<ColumnMeta> dropped, List<Renamed> renamed, List<Retyped> retyped) {

    public record Renamed(String from, String to) {}

    public record Retyped(String column, String from, String to) {}

    public boolean empty() {
        return added.isEmpty() && dropped.isEmpty() && renamed.isEmpty() && retyped.isEmpty();
    }

    public static SchemaDiff between(List<ColumnMeta> before, List<ColumnMeta> after) {
        Map<String, ColumnMeta> was = byName(before);
        Map<String, ColumnMeta> now = byName(after);
        List<ColumnMeta> added = new ArrayList<>(after.stream().filter(c -> !was.containsKey(key(c.name()))).toList());
        List<ColumnMeta> dropped = new ArrayList<>(before.stream().filter(c -> !now.containsKey(key(c.name()))).toList());
        List<Renamed> renamed = new ArrayList<>();
        for (ColumnMeta gone : List.copyOf(dropped)) {
            int position = before.indexOf(gone);
            ColumnMeta replacement = position < after.size() ? after.get(position) : null;
            if (replacement != null && added.contains(replacement) && sameType(gone.type(), replacement.type())) {
                renamed.add(new Renamed(gone.name(), replacement.name()));
                dropped.remove(gone);
                added.remove(replacement);
            }
        }
        List<Retyped> retyped = before.stream()
                .filter(c -> now.containsKey(key(c.name())) && !sameType(c.type(), now.get(key(c.name())).type()))
                .map(c -> new Retyped(c.name(), c.type(), now.get(key(c.name())).type()))
                .toList();
        return new SchemaDiff(added, dropped, renamed, retyped);
    }

    /** One line per change, e.g. "新增列 remark VARCHAR(64)". */
    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        added.forEach(c -> lines.add("新增列 " + c.name() + " " + c.type()));
        renamed.forEach(r -> lines.add("列改名 " + r.from() + " → " + r.to()));
        retyped.forEach(r -> lines.add("列 " + r.column() + " 类型 " + r.from() + " → " + r.to()));
        dropped.forEach(c -> lines.add("删除列 " + c.name()));
        return lines;
    }

    private static boolean sameType(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    private static Map<String, ColumnMeta> byName(List<ColumnMeta> columns) {
        return columns.stream().collect(Collectors.toMap(c -> key(c.name()), Function.identity(), (x, y) -> x));
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
