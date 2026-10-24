package com.dataintegration.console.engine.zeta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.engine.EngineJobInfo;
import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.engine.EngineJobStatus;
import com.dataintegration.console.engine.EngineLogFile;
import com.dataintegration.console.engine.SubmitJobRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

class ZetaRestClientTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<HttpExchange> lastExchange = new AtomicReference<>();
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
    void submitSendsHoconWithBasicAuthAndReturnsJobId() {
        // Real 3.0.0 responses carry jobId as a string, although the docs show a number.
        respond("/submit-job", 200, "{\"jobId\": \"733584788375093248\", \"jobName\": \"smoke\"}");
        ZetaRestClient client = client(List.of(baseUrl), "admin", "secret");

        long jobId = client.submit(SubmitJobRequest.fresh("smoke job", "env { job.mode = \"batch\" }"));

        assertThat(jobId).isEqualTo(733584788375093248L);
        HttpExchange exchange = lastExchange.get();
        assertThat(exchange.getRequestMethod()).isEqualTo("POST");
        assertThat(exchange.getRequestURI().getRawQuery()).isEqualTo("format=hocon&jobName=smoke+job");
        assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                .isEqualTo("Basic YWRtaW46c2VjcmV0");
        assertThat(lastBody.get()).isEqualTo("env { job.mode = \"batch\" }");
    }

    @Test
    void submitWithRestorePassesRestoreParameters() {
        respond("/submit-job", 200, "{\"jobId\": 2}");
        ZetaRestClient client = client(List.of(baseUrl), null, null);

        client.submit(
                new SubmitJobRequest("j", "cfg", SubmitJobRequest.RestoreMode.SAVEPOINT, 1L, null));

        assertThat(lastExchange.get().getRequestURI().getRawQuery())
                .isEqualTo("format=hocon&jobName=j&restoreMode=SAVEPOINT&restoreSourceJobId=1");
        assertThat(lastExchange.get().getRequestHeaders().containsKey("Authorization")).isFalse();
    }

    @Test
    void freshSubmitCarriesTheCallersJobId() {
        respond("/submit-job", 200, "{\"jobId\": \"5000000000000000001\"}");

        long jobId = client(List.of(baseUrl), null, null).submit(SubmitJobRequest.fresh("j", "cfg").withJobId(5000000000000000001L));

        assertThat(jobId).isEqualTo(5000000000000000001L);
        assertThat(lastExchange.get().getRequestURI().getRawQuery()).isEqualTo("format=hocon&jobName=j&jobId=5000000000000000001");
    }

    @Test
    void logTailKeepsOnlyTheLastBytes() {
        ZetaRestClient.TailBuffer tail = new ZetaRestClient.TailBuffer(7);
        tail.write("0123456789".getBytes(StandardCharsets.UTF_8));
        tail.write("abcdefghij".getBytes(StandardCharsets.UTF_8));
        ZetaRestClient.TailBuffer whole = new ZetaRestClient.TailBuffer(64);
        whole.write("0123456789".getBytes(StandardCharsets.UTF_8));

        assertThat(tail.result().text()).isEqualTo("defghij");
        assertThat(tail.result().truncated()).isTrue();
        assertThat(whole.result().text()).isEqualTo("0123456789");
        assertThat(whole.result().truncated()).isFalse();
    }

    @Test
    void errorFromTheNextMasterAfterATimeoutStaysUnknown() throws Exception {
        HttpServer silent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        silent.createContext("/submit-job", exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        silent.start();
        try {
            respond("/submit-job", 500, "{\"message\":\"boom\"}");
            ZetaRestClient client = new ZetaRestClient(new ZetaClusterConfig(
                    List.of("http://127.0.0.1:" + silent.getAddress().getPort(), baseUrl), null, null,
                    Duration.ofSeconds(1), Duration.ofMillis(300)));

            assertThatThrownBy(() -> client.submit(SubmitJobRequest.fresh("j", "cfg").withJobId(5000000000000000001L)))
                    .isInstanceOf(ConsoleException.class)
                    .extracting(e -> ((ConsoleException) e).getCode())
                    .isEqualTo(ErrorCodes.ENGINE_RESPONSE_LOST);
        } finally {
            silent.stop(0);
        }
    }

    @Test
    void stopPostsJsonBody() {
        respond("/stop-job", 200, "{\"jobId\": 7}");

        client(List.of(baseUrl), null, null).stop(7L, true, false);

        assertThat(lastBody.get())
                .contains("\"jobId\":7")
                .contains("\"isStopWithSavePoint\":true")
                .contains("\"force\":false");
    }

    @Test
    void jobInfoParsesStatusAndMetrics() {
        respond(
                "/job-info/7",
                200,
                """
                {"jobId": "7", "jobName": "smoke", "jobStatus": "FINISHED",
                 "createTime": "2026-10-23 10:00:00",
                 "metrics": {"SourceReceivedCount": "10", "SinkWriteCount": "10"},
                 "errorMsg": null}
                """);

        Optional<EngineJobInfo> info = client(List.of(baseUrl), null, null).jobInfo(7L);

        assertThat(info).isPresent();
        assertThat(info.get().status()).isEqualTo(EngineJobStatus.FINISHED);
        assertThat(info.get().status().isTerminal()).isTrue();
        assertThat(info.get().metrics()).containsEntry("SinkWriteCount", "10");
        assertThat(info.get().errorMessage()).isNull();
    }

    @Test
    void jobInfoWithoutStatusMeansUnknownJob() {
        respond("/job-info/8", 200, "{\"jobId\": \"8\"}");

        assertThat(client(List.of(baseUrl), null, null).jobInfo(8L)).isEmpty();
    }

    @Test
    void failsOverToNextMasterWhenFirstIsUnreachable() {
        respond("/overview", 200, "{\"projectVersion\": \"3.0.0\", \"workers\": \"1\"}");
        ZetaRestClient client = client(List.of("http://127.0.0.1:1", baseUrl), null, null);

        assertThat(client.overview()).containsEntry("projectVersion", "3.0.0");
    }

    @Test
    void httpErrorIsReportedWithoutRetry() {
        respond("/submit-job", 400, "{\"status\": \"fail\", \"message\": \"bad config\"}");

        assertThatThrownBy(
                        () ->
                                client(List.of(baseUrl), null, null)
                                        .submit(SubmitJobRequest.fresh("j", "cfg")))
                .isInstanceOf(ConsoleException.class)
                .hasMessageContaining("HTTP 400")
                .hasMessageNotContaining("?format")
                .extracting(e -> ((ConsoleException) e).getCode())
                .isEqualTo(ErrorCodes.ENGINE_REQUEST_FAILED);
    }

    @Test
    void listsLogFilesAcrossNodesAndReadsOneWithAuth() {
        respond(
                "/logs/7",
                200,
                "[{\"node\":\"n1:8080\",\"logLink\":\"" + baseUrl + "/logs/job-7.log\",\"logName\":\"job-7.log\"}]");
        respond("/logs/job-7.log", 200, "line1\nline2\n");
        ZetaRestClient client = client(List.of(baseUrl), "admin", "secret");

        List<EngineLogFile> files = client.listLogs(7L);
        EngineClient.LogContent content = client.readLog(files.get(0), 1024);

        assertThat(files).containsExactly(new EngineLogFile("n1:8080", "job-7.log", baseUrl + "/logs/job-7.log"));
        assertThat(content.text()).isEqualTo("line1\nline2\n");
        assertThat(content.truncated()).isFalse();
        assertThat(lastExchange.get().getRequestHeaders().getFirst("Authorization")).isEqualTo("Basic YWRtaW46c2VjcmV0");
    }

    @Test
    void loopbackLogLinksAreReadThroughTheConfiguredMaster() {
        assertThat(ZetaRestClient.rebaseLoopback("http://localhost:8080/logs/job-1.log", "http://seatunnel-master:8080"))
                .isEqualTo("http://seatunnel-master:8080/logs/job-1.log");
        assertThat(ZetaRestClient.rebaseLoopback("http://127.0.0.1:8080/logs/job-1.log?x=1", "https://10.0.0.5:18080"))
                .isEqualTo("https://10.0.0.5:18080/logs/job-1.log?x=1");
        // a real node address, or a master that is itself local, stays as it is
        assertThat(ZetaRestClient.rebaseLoopback("http://worker-2:8080/logs/job-1.log", "http://seatunnel-master:8080"))
                .isEqualTo("http://worker-2:8080/logs/job-1.log");
        assertThat(ZetaRestClient.rebaseLoopback("http://localhost:8080/logs/job-1.log", "http://localhost:8080"))
                .isEqualTo("http://localhost:8080/logs/job-1.log");
    }

    @Test
    void readLogKeepsOnlyTheTail() {
        respond("/logs/job-7.log", 200, "0123456789");

        EngineClient.LogContent content =
                client(List.of(baseUrl), null, null).readLog(new EngineLogFile("n1", "job-7.log", baseUrl + "/logs/job-7.log"), 4);

        assertThat(content.text()).isEqualTo("6789");
        assertThat(content.truncated()).isTrue();
    }

    @Test
    void noPerJobLogsGivesEmptyList() {
        respond("/logs/8", 200, "[]");

        assertThat(client(List.of(baseUrl), null, null).listLogs(8L)).isEmpty();
    }

    @Test
    void submitRequestNeverPrintsConfig() {
        assertThat(SubmitJobRequest.fresh("j", "password = \"p@ss\"").toString())
                .doesNotContain("p@ss");
    }

    private ZetaRestClient client(List<String> urls, String user, String password) {
        return new ZetaRestClient(
                new ZetaClusterConfig(urls, user, password, Duration.ofSeconds(1), Duration.ofSeconds(5)));
    }

    private void respond(String path, int status, String body) {
        server.createContext(
                path,
                exchange -> {
                    lastExchange.set(exchange);
                    lastBody.set(
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
    }
}
