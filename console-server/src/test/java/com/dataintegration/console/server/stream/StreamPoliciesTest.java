package com.dataintegration.console.server.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dataintegration.console.engine.EngineCheckpoints;
import com.dataintegration.console.engine.SubmitJobRequest.RestoreMode;
import com.dataintegration.console.server.persistence.entity.JobInstanceAttempt;
import com.dataintegration.console.server.persistence.entity.JobStream;
import com.dataintegration.console.server.stream.RestorePlan.Asked;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/** The pure rules behind starting, restarting and watching streaming jobs (docs/design.md §12 P2). */
class StreamPoliciesTest {

    private static JobStream stream(String kind, String from, String blocked) {
        JobStream s = new JobStream();
        s.setRestoreKind(kind);
        s.setRestoreEngineJobId(from);
        s.setRestoreBlocked(blocked);
        s.setAutoRestart(true);
        s.setMaxRestarts(3);
        return s;
    }

    @Test
    void startsFromTheSavedPositionUnlessTheSourceChangedOrRestoringWouldFailAgain() {
        assertThat(RestorePlan.decide(stream("SAVEPOINT", "7", null), Asked.AUTO, false).mode()).isEqualTo(RestoreMode.SAVEPOINT);
        assertThat(RestorePlan.decide(stream("CHECKPOINT", "7", null), Asked.AUTO, false).restoreFrom()).isEqualTo(7L);
        assertThat(RestorePlan.decide(stream("SAVEPOINT", "7", null), Asked.AUTO, true).fresh()).isTrue();
        assertThat(RestorePlan.decide(stream("CHECKPOINT", "7", "DDL"), Asked.AUTO, false).note()).contains("DDL");
        assertThat(RestorePlan.decide(stream(null, null, null), Asked.AUTO, false).fresh()).isTrue();
        assertThat(RestorePlan.decide(stream("SAVEPOINT", "7", null), Asked.FRESH, false).fresh()).isTrue();
        // what was asked for explicitly has to be there
        assertThatThrownBy(() -> RestorePlan.decide(stream("CHECKPOINT", "7", "DDL"), Asked.CHECKPOINT, false))
                .hasMessageContaining("全新启动");
        assertThatThrownBy(() -> RestorePlan.decide(stream("SAVEPOINT", "7", null), Asked.CHECKPOINT, false))
                .hasMessageContaining("savepoint");
        assertThat(RestorePlan.decide(stream("SAVEPOINT", "7", null), Asked.SAVEPOINT, true).mode()).isEqualTo(RestoreMode.SAVEPOINT);
    }

    private static JobInstanceAttempt attempt(int minutes) {
        JobInstanceAttempt a = new JobInstanceAttempt();
        LocalDateTime start = LocalDateTime.of(2026, 10, 27, 10, 0);
        a.setSubmittedAt(start);
        a.setFinishedAt(start.plusMinutes(minutes));
        return a;
    }

    @Test
    void restartsAtMostTheConfiguredTimesInARowAndAStableRunResetsTheCount() {
        JobStream s = stream(null, null, null);
        assertThat(AutoRestartPolicy.restart(s, List.of(attempt(1)), false)).isTrue();
        assertThat(AutoRestartPolicy.restart(s, List.of(attempt(1), attempt(1), attempt(1), attempt(1)), false)).isFalse();
        assertThat(AutoRestartPolicy.restart(s, List.of(attempt(1), attempt(1), attempt(1), attempt(60), attempt(1)), false)).isTrue();
        assertThat(AutoRestartPolicy.restart(s, List.of(attempt(1)), true)).isFalse();
        s.setAutoRestart(false);
        assertThat(AutoRestartPolicy.restart(s, List.of(attempt(1)), false)).isFalse();
    }

    @Test
    void checkpointsAreLateAfterThreeIntervalsAndBrokenAfterThreeFailures() {
        Instant now = Instant.ofEpochMilli(1_000_000);
        Duration interval = Duration.ofSeconds(10);
        Instant started = now.minusSeconds(20);
        assertThat(CheckpointHealth.problem(new EngineCheckpoints(0, 0, 0, null, null, null), interval, started, now)).isNull();
        assertThat(CheckpointHealth.problem(new EngineCheckpoints(0, 0, 0, null, null, null), interval, now.minusSeconds(120), now))
                .contains("还没有完成过 checkpoint");
        assertThat(CheckpointHealth.problem(new EngineCheckpoints(9, 9, 0, now.minusSeconds(100).toEpochMilli(), null, null), interval, started, now))
                .contains("100 秒没有完成");
        assertThat(CheckpointHealth.problem(new EngineCheckpoints(9, 5, 4, now.minusSeconds(5).toEpochMilli(), now.toEpochMilli(),
                "CHECKPOINT_EXPIRED"), interval, started, now)).contains("CHECKPOINT_EXPIRED");
        assertThat(CheckpointHealth.problem(null, interval, started, now)).isNull();
    }

    @Test
    void readsTheRootCauseAndSchemaChangeFailuresFromTheLog() {
        String publication = """
                2026-10-26 22:59:12,100 WARN  [TaskExecutionService] - Exception in task
                java.lang.RuntimeException: SplitFetcher thread 0 received unexpected exception while polling the records
                \tat org.apache.seatunnel.Foo.bar(Foo.java:1)
                Caused by: io.debezium.jdbc.JdbcConnectionException: ERROR: must be superuser to create FOR ALL TABLES publication
                \t... 6 more
                Caused by: org.postgresql.util.PSQLException: ERROR: must be superuser to create FOR ALL TABLES publication
                \t... 6 more
                2026-10-26 22:59:12,269 INFO  [JdbcConnection] - Connection gracefully closed
                java.lang.RuntimeException: One or more fetchers have encountered exception
                """;
        String ddl = """
                org.apache.seatunnel.common.utils.SeaTunnelException: java.lang.InterruptedException: sleep interrupted
                org.apache.seatunnel.common.utils.SeaTunnelException: Unsupported schemaChangeEvent : SCHEMA_CHANGE_CHANGE_COLUMN
                \tat org.apache.seatunnel.connectors.seatunnel.elasticsearch.sink.ElasticsearchSinkWriter.applySingleSchemaChangeEvent(ElasticsearchSinkWriter.java:222)
                """;

        StreamFailure p = StreamFailure.fromLog(publication);
        StreamFailure d = StreamFailure.fromLog(ddl);

        assertThat(p.summary()).isEqualTo("org.postgresql.util.PSQLException: ERROR: must be superuser to create FOR ALL TABLES publication");
        assertThat(p.schemaChange()).isFalse();
        assertThat(p.restoreBlocked()).isNull();
        assertThat(d.summary()).contains("Unsupported schemaChangeEvent");
        assertThat(d.schemaChange()).isTrue();
        assertThat(d.restoreBlocked()).contains("结构变更");
        assertThat(StreamFailure.fromLog(null).summary()).isNull();
    }
}
