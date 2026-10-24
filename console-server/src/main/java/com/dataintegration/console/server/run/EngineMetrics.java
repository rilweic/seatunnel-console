package com.dataintegration.console.server.run;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parts of a Zeta job's metrics the console shows (docs/design.md §10.4). Totals are plain
 * numbers; per-table metrics arrive as JSON strings keyed by {@code "Source[0].db.table"} or
 * {@code "Sink[0].db.table"}.
 *
 * @param readRows SourceReceivedCount
 * @param writeRows SinkWriteCount
 * @param readQps SourceReceivedQPS, the engine's average over the job's lifetime
 */
public record EngineMetrics(
        Long readRows,
        Long writeRows,
        Long readBytes,
        Long writeBytes,
        Double readQps,
        Double writeQps,
        List<TableMetric> tables) {

    public enum Side {
        SOURCE,
        SINK
    }

    /**
     * @param vertex the plugin in the job, e.g. {@code Source[0]}
     * @param table {@code database.table}, or {@code database.schema.table} for PostgreSQL
     */
    public record TableMetric(Side side, String vertex, String table, Long rows, Long bytes, Double qps) {}

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    public static final EngineMetrics EMPTY = new EngineMetrics(null, null, null, null, null, null, List.of());

    public static EngineMetrics parse(Map<String, String> raw, ObjectMapper objectMapper) {
        if (raw == null || raw.isEmpty()) {
            return EMPTY;
        }
        Map<String, TableMetric> tables = new LinkedHashMap<>();
        collect(tables, Side.SOURCE, raw, "TableSourceReceived", objectMapper);
        collect(tables, Side.SINK, raw, "TableSinkWrite", objectMapper);
        return new EngineMetrics(
                longOf(raw.get("SourceReceivedCount")),
                longOf(raw.get("SinkWriteCount")),
                longOf(raw.get("SourceReceivedBytes")),
                longOf(raw.get("SinkWriteBytes")),
                doubleOf(raw.get("SourceReceivedQPS")),
                doubleOf(raw.get("SinkWriteQPS")),
                new ArrayList<>(tables.values()));
    }

    private static void collect(
            Map<String, TableMetric> tables, Side side, Map<String, String> raw, String prefix, ObjectMapper objectMapper) {
        Map<String, String> counts = nested(raw.get(prefix + "Count"), objectMapper);
        Map<String, String> bytes = nested(raw.get(prefix + "Bytes"), objectMapper);
        Map<String, String> qps = nested(raw.get(prefix + "QPS"), objectMapper);
        for (Map.Entry<String, String> entry : counts.entrySet()) {
            String key = entry.getKey();
            int split = key.indexOf("].");
            String vertex = split < 0 ? "" : key.substring(0, split + 1);
            String table = split < 0 ? key : key.substring(split + 2);
            tables.put(side + key, new TableMetric(
                    side, vertex, table, longOf(entry.getValue()), longOf(bytes.get(key)), doubleOf(qps.get(key))));
        }
    }

    private static Map<String, String> nested(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, STRING_MAP);
        } catch (JacksonException e) {
            return Map.of();
        }
    }

    static Long longOf(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double doubleOf(String value) {
        if (value == null) {
            return null;
        }
        try {
            double d = Double.parseDouble(value.trim());
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
