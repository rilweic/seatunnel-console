package com.dataintegration.console.engine.flink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.engine.ConnectorOption;
import com.dataintegration.console.engine.EngineCheckpoints;
import com.dataintegration.console.engine.EngineClient.LogContent;
import com.dataintegration.console.engine.EngineClient.StopResult;
import com.dataintegration.console.engine.EngineJobInfo;
import com.dataintegration.console.engine.EngineJobStatus;
import com.dataintegration.console.engine.EngineLogFile;
import com.dataintegration.console.engine.MasterStatus;
import com.dataintegration.console.engine.SubmitJobRequest;
import com.dataintegration.console.engine.SubmitJobRequest.RestoreMode;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

class FlinkEngineClientTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void probeMasterAndOverviewReadFlinkVersion() {
        respond("/overview", 200, """
                {"taskmanagers":1,"slots-total":8,"slots-available":6,"flink-version":"1.20.1"}
                """);
        FlinkRestClient rest = new FlinkRestClient(config());

        MasterStatus status = rest.probeMaster();
        assertThat(status.reachable()).isTrue();
        assertThat(status.version()).isEqualTo("1.20.1");
        assertThat(rest.overview()).containsEntry("slots-total", "8");
    }

    @Test
    void configWarningsAcceptBothFlink120AndLegacyKeys() {
        respond("/jobmanager/config", 200, """
                [
                  {"key":"execution.checkpointing.dir","value":"file:///opt/flink-state/checkpoints"},
                  {"key":"execution.checkpointing.savepoint-dir","value":"file:///opt/flink-state/savepoints"},
                  {"key":"execution.checkpointing.externalized-checkpoint-retention","value":"RETAIN_ON_CANCELLATION"}
                ]
                """);
        assertThat(new FlinkRestClient(config()).configWarnings()).isEmpty();
    }

    @Test
    void configWarningsReportMissingDirectoriesAndRetention() {
        respond("/jobmanager/config", 200, "[]");
        assertThat(new FlinkRestClient(config()).configWarnings()).hasSize(3);
    }

    @Test
    void jobInfoCombinesJobStatusAndUserAccumulators() {
        String id = "648758c2665e214be7ce0868f7d79df6";
        respond("/jobs/" + id, 200, """
                {"jid":"648758c2665e214be7ce0868f7d79df6","name":"demo","state":"FINISHED","start-time":1700000000000,"duration":4000}
                """);
        respond("/jobs/" + id + "/accumulators", 200, """
                {"user-task-accumulators":[
                  {"name":"SourceReceivedCount","type":"LongCounter","value":"20"},
                  {"name":"SinkWriteCount","type":"LongCounter","value":"20"}
                ]}
                """);

        Optional<EngineJobInfo> info = new FlinkRestClient(config()).jobInfo(id);

        assertThat(info).isPresent();
        assertThat(info.get().status()).isEqualTo(EngineJobStatus.FINISHED);
        assertThat(info.get().metrics())
                .containsEntry("SourceReceivedCount", "20")
                .containsEntry("SinkWriteCount", "20")
                .containsEntry("SourceReceivedQPS", "5.0")
                .containsEntry("SinkWriteQPS", "5.0");
    }

    @Test
    void unknownJobReturnsEmpty() {
        respond("/jobs/deadbeef", 404, "{\"errors\":[\"Job could not be found\"]}");
        assertThat(new FlinkRestClient(config()).jobInfo("deadbeef")).isEmpty();
    }

    @Test
    void stopWithSavepointPollsUntilCompletedAndReturnsLocation() {
        String id = "11112222333344445555666677778888";
        respond("/jobs/" + id + "/stop", 202, "{\"request-id\":\"req-1\"}");
        respond("/jobs/" + id + "/savepoints/req-1", 200, """
                {"status":{"id":"COMPLETED"},"operation":{"location":"file:/opt/flink-state/savepoints/sp-1"}}
                """);

        StopResult result = new FlinkRestClient(config()).stop(id, true, false);
        assertThat(result.savepointPath()).isEqualTo("file:/opt/flink-state/savepoints/sp-1");
    }

    @Test
    void cancelUsesPatchMethod() {
        String id = "11112222333344445555666677778888";
        respond("/jobs/" + id, 202, "{}");

        StopResult result = new FlinkRestClient(config()).stop(id, false, false);
        assertThat(result.savepointPath()).isNull();
        assertThat(lastMethod.get()).isEqualTo("PATCH");
    }

    @Test
    void disposeSavepointPostsHyphenatedSavepointPath() {
        respond("/savepoint-disposal", 202, "{\"request-id\":\"req-2\"}");

        new FlinkRestClient(config()).disposeSavepoint("file:/opt/flink-state/savepoints/sp-1");
        assertThat(lastBody.get()).contains("\"savepoint-path\":\"file:/opt/flink-state/savepoints/sp-1\"");
    }

    @Test
    void checkpointsExtractsLatestExternalPath() {
        String id = "11112222333344445555666677778888";
        respond("/jobs/" + id + "/checkpoints", 200, """
                {
                  "counts":{"total":3,"completed":2,"failed":1},
                  "latest":{
                    "completed":{"latest_ack_timestamp":5000,"external_path":"file:/opt/flink-state/checkpoints/chk-2"},
                    "failed":{"trigger_timestamp":6000,"failure_message":"expired"}
                  }
                }
                """);

        EngineCheckpoints cp = new FlinkRestClient(config()).checkpoints(id).orElseThrow();
        assertThat(cp.triggered()).isEqualTo(3);
        assertThat(cp.completed()).isEqualTo(2);
        assertThat(cp.failed()).isEqualTo(1);
        assertThat(cp.latestExternalPath()).isEqualTo("file:/opt/flink-state/checkpoints/chk-2");
        assertThat(cp.lastFailureReason()).isEqualTo("expired");
    }

    @Test
    void listLogsFiltersToJobTaskManagersAndSkipsRotatedFiles() {
        String id = "11112222333344445555666677778888";
        respond("/jobs/" + id, 200, "{\"jid\":\"" + id + "\",\"vertices\":[{\"id\":\"v1\"}]}");
        respond("/jobs/" + id + "/vertices/v1", 200, "{\"subtasks\":[{\"taskmanager-id\":\"tm-1\"}]}");
        respond("/jobmanager/logs", 200, "{\"logs\":[{\"name\":\"jobmanager.log\"},{\"name\":\"jobmanager.log.1\"}]}");
        respond("/taskmanagers/tm-1/logs", 200, "{\"logs\":[{\"name\":\"taskexecutor.log\"},{\"name\":\"taskexecutor.log.2\"}]}");
        respond("/taskmanagers/tm-2/logs", 200, "{\"logs\":[{\"name\":\"taskexecutor.log\"}]}");
        respond("/jobmanager/logs/jobmanager.log", 200, "hello flink log");

        FlinkRestClient rest = new FlinkRestClient(config());
        List<EngineLogFile> files = rest.listLogs(id);
        assertThat(files).extracting(EngineLogFile::node, EngineLogFile::name)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("jobmanager", "jobmanager.log"),
                        org.assertj.core.groups.Tuple.tuple("taskmanager/tm-1", "taskexecutor.log"));
        LogContent content = rest.readLog(files.get(0), 1024);
        assertThat(content.text()).isEqualTo("hello flink log");
        assertThat(content.truncated()).isFalse();
    }

    @Test
    void cliCommandIncludesDetachedFixedIdAndClaimRestore() {
        FlinkCliSubmitter submitter = new FlinkCliSubmitter(config());
        SubmitJobRequest req = new SubmitJobRequest(
                "my-job", "env {}", RestoreMode.SAVEPOINT, null, "file:/sp/1", "11112222333344445555666677778888");

        List<String> cmd = submitter.buildCommand(req, Path.of("/tmp/job.conf"));
        assertThat(cmd).contains(
                "/opt/flink/bin/flink",
                "run",
                "-d",
                "-m",
                "127.0.0.1:" + server.getAddress().getPort(),
                "-D$internal.pipeline.job-id=11112222333344445555666677778888",
                "-s",
                "file:/sp/1",
                "-restoreMode",
                "claim",
                "/opt/seatunnel/starter/seatunnel-flink-20-starter.jar",
                "--config",
                "/tmp/job.conf",
                "--name",
                "my-job");
    }

    @Test
    void starterJarMappingRejectsFlink119() {
        assertThat(FlinkCliSubmitter.starterJarName("1.20.1")).isEqualTo("seatunnel-flink-20-starter.jar");
        assertThat(FlinkCliSubmitter.starterJarName("1.18.0")).isEqualTo("seatunnel-flink-15-starter.jar");
        assertThat(FlinkCliSubmitter.starterJarName("1.14.6")).isEqualTo("seatunnel-flink-13-starter.jar");
        assertThatThrownBy(() -> FlinkCliSubmitter.starterJarName("1.19.1"))
                .isInstanceOf(ConsoleException.class)
                .extracting(e -> ((ConsoleException) e).getCode())
                .isEqualTo(ErrorCodes.INVALID_ARGUMENT);
    }

    @Test
    void connectorOptionCliParserReadsRequiredAndOptionalOptions() {
        String sample = """
                Jdbc source
                Required Options:
                 Key: 'url', default: null (fallback keys: [base-url]), Description: 'jdbc url'

                Optional Options:
                 Key: 'fetch_size', default: 0 (fallback keys: []), Description: 'rows per fetch'
                """;
        List<ConnectorOption> options = ConnectorOptionCliReader.parse(sample);
        assertThat(options).containsExactly(
                new ConnectorOption("url", null, null, "jdbc url", true),
                new ConnectorOption("fetch_size", null, "0", "rows per fetch", false));
    }

    @Test
    void restoreWithoutPathIsRejectedInsteadOfStartingFresh() {
        FlinkCliSubmitter submitter = new FlinkCliSubmitter(config());
        SubmitJobRequest req = new SubmitJobRequest(
                "my-job", "env {}", RestoreMode.CHECKPOINT, "11112222333344445555666677778888", null, null);

        assertThatThrownBy(() -> submitter.buildCommand(req, Path.of("/tmp/job.conf")))
                .isInstanceOf(ConsoleException.class)
                .extracting(e -> ((ConsoleException) e).getCode())
                .isEqualTo(ErrorCodes.INVALID_ARGUMENT);
    }

    @Test
    void nonZeroCliExitIsAlwaysResponseLostToPreventDuplicateSubmission() {
        assertThat(FlinkCliSubmitter.classifyFailure(1, "java.net.ConnectException: Connection refused").getCode())
                .isEqualTo(ErrorCodes.ENGINE_RESPONSE_LOST);
        assertThat(FlinkCliSubmitter.classifyFailure(1, "com.typesafe.config.ConfigException: syntax error").getCode())
                .isEqualTo(ErrorCodes.ENGINE_RESPONSE_LOST);
        assertThat(FlinkCliSubmitter.classifyFailure(1, "java.net.SocketTimeoutException: Read timed out").getCode())
                .isEqualTo(ErrorCodes.ENGINE_RESPONSE_LOST);
        assertThat(FlinkCliSubmitter.classifyFailure(1, "Job has been submitted with JobID 11112222333344445555666677778888\nConnection reset").getCode())
                .isEqualTo(ErrorCodes.ENGINE_RESPONSE_LOST);
    }

    @Test
    void submitTimeoutTriggersEvenWhenCliHoldsStdoutOpenAndCleansTempConfig() throws Exception {
        Path fakeRoot = java.nio.file.Files.createTempDirectory("flink-timeout-test");
        Path binDir = java.nio.file.Files.createDirectories(fakeRoot.resolve("flink/bin"));
        Path starterDir = java.nio.file.Files.createDirectories(fakeRoot.resolve("seatunnel/starter"));
        Path tempDir = java.nio.file.Files.createDirectories(fakeRoot.resolve("tmp"));
        Path fakeFlink = binDir.resolve("flink");
        java.nio.file.Files.writeString(fakeFlink, "#!/bin/sh\necho starting\nsleep 30\n");
        fakeFlink.toFile().setExecutable(true);
        java.nio.file.Files.writeString(starterDir.resolve("seatunnel-flink-20-starter.jar"), "jar");

        FlinkClusterConfig cfg = new FlinkClusterConfig(
                baseUrl,
                fakeRoot.resolve("flink").toString(),
                fakeRoot.resolve("seatunnel").toString(),
                "1.20.1",
                null,
                java.time.Duration.ofSeconds(1),
                java.time.Duration.ofSeconds(1),
                java.time.Duration.ofMillis(400),
                2,
                tempDir);
        FlinkCliSubmitter submitter = new FlinkCliSubmitter(cfg);

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> submitter.submit(SubmitJobRequest.fresh("hang-job", "env { secret = \"topsecret\" }")))
                .isInstanceOf(ConsoleException.class)
                .extracting(e -> ((ConsoleException) e).getCode())
                .isEqualTo(ErrorCodes.ENGINE_RESPONSE_LOST);
        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed).isLessThan(5000);
        try (var leftover = java.nio.file.Files.list(tempDir)) {
            assertThat(leftover.toList()).isEmpty();
        }
    }

    private FlinkClusterConfig config() {
        return new FlinkClusterConfig(baseUrl, "/opt/flink", "/opt/seatunnel", "1.20.1", null);
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            lastMethod.set(exchange.getRequestMethod());
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }
}
