package com.dataintegration.console.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.dataintegration.console.server.run.EngineMetrics.Side;
import com.dataintegration.console.server.run.EngineMetrics.TableMetric;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

class EngineMetricsTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void parsesTotalsAndPerTableMetricsOfAZetaJob() {
        // Shape as returned by /job-info for a finished two-table MySQL -> MySQL job.
        Map<String, String> raw = Map.of(
                "SourceReceivedCount", "9",
                "SinkWriteCount", "9",
                "SourceReceivedBytes", "1448",
                "SinkWriteBytes", "1448",
                "SinkWriteQPS", "5.671077504725898",
                "TableSourceReceivedCount", "{\"Source[0].shop.orders\":\"6\",\"Source[0].shop.users\":\"3\"}",
                "TableSourceReceivedBytes", "{\"Source[0].shop.orders\":\"842\",\"Source[0].shop.users\":\"606\"}",
                "TableSinkWriteCount", "{\"Sink[0].ods.ods_orders\":\"6\",\"Sink[0].ods.ods_users\":\"3\"}",
                "TableSinkWriteQPS", "{\"Sink[0].ods.ods_orders\":\"3.78\",\"Sink[0].ods.ods_users\":\"1.89\"}");

        EngineMetrics metrics = EngineMetrics.parse(raw, mapper);

        assertThat(metrics.readRows()).isEqualTo(9);
        assertThat(metrics.writeBytes()).isEqualTo(1448);
        assertThat(metrics.writeQps()).isEqualTo(5.671077504725898);
        assertThat(metrics.readQps()).isNull();
        assertThat(metrics.tables()).contains(
                new TableMetric(Side.SOURCE, "Source[0]", "shop.orders", 6L, 842L, null),
                new TableMetric(Side.SINK, "Sink[0]", "ods.ods_users", 3L, null, 1.89));
        assertThat(metrics.tables()).hasSize(4);
    }

    @Test
    void toleratesMissingAndMalformedValues() {
        EngineMetrics metrics = EngineMetrics.parse(
                Map.of("SinkWriteCount", "n/a", "TableSinkWriteCount", "not json", "TableSourceReceivedCount", "{\"plain\":\"2\"}"),
                mapper);

        assertThat(metrics.writeRows()).isNull();
        assertThat(metrics.tables()).containsExactly(new TableMetric(Side.SOURCE, "", "plain", 2L, null, null));
        assertThat(EngineMetrics.parse(null, mapper)).isEqualTo(EngineMetrics.EMPTY);
    }
}
