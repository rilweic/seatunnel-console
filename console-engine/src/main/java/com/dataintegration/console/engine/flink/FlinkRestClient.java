package com.dataintegration.console.engine.flink;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.engine.EngineCheckpoints;
import com.dataintegration.console.engine.EngineClient.LogContent;
import com.dataintegration.console.engine.EngineClient.StopResult;
import com.dataintegration.console.engine.EngineJobInfo;
import com.dataintegration.console.engine.EngineJobStatus;
import com.dataintegration.console.engine.EngineLogFile;
import com.dataintegration.console.engine.EngineWorker;
import com.dataintegration.console.engine.MasterStatus;
import com.dataintegration.console.engine.TailBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * HTTP client for Flink JobManager REST API v1 (verified on Flink 1.20.1).
 */
public class FlinkRestClient {

    private static final Logger log = LoggerFactory.getLogger(FlinkRestClient.class);

    private static final int MAX_ERROR_BODY = 2000;
    private static final int LOG_READ_TIMEOUT_FACTOR = 4;
    private static final int SAVEPOINT_POLL_ATTEMPTS = 120;
    private static final long SAVEPOINT_POLL_INTERVAL_MS = 1000L;

    private final FlinkClusterConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public FlinkRestClient(FlinkClusterConfig config) {
        this(config, HttpClient.newBuilder().connectTimeout(config.connectTimeout()).build());
    }

    FlinkRestClient(FlinkClusterConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    public MasterStatus probeMaster() {
        long start = System.nanoTime();
        try {
            JsonNode body = send("/overview", HttpRequest.newBuilder().GET(), "application/json");
            long latency = (System.nanoTime() - start) / 1_000_000;
            String version = textOrNull(body, "flink-version");
            return new MasterStatus(config.restUrl(), true, false, version, latency, null);
        } catch (ConsoleException e) {
            long latency = (System.nanoTime() - start) / 1_000_000;
            return new MasterStatus(config.restUrl(), false, null, null, latency, e.getMessage());
        }
    }

    public Map<String, String> overview() {
        JsonNode body = send("/overview", HttpRequest.newBuilder().GET(), "application/json");
        Map<String, String> out = new LinkedHashMap<>();
        body.properties().forEach(e -> out.put(e.getKey(), e.getValue().isValueNode() ? e.getValue().asString() : e.getValue().toString()));
        return out;
    }

    public Map<String, String> jobManagerConfig() {
        JsonNode body = send("/jobmanager/config", HttpRequest.newBuilder().GET(), "application/json");
        Map<String, String> out = new LinkedHashMap<>();
        if (body.isArray()) {
            for (JsonNode item : body) {
                String k = textOrNull(item, "key");
                if (k != null) {
                    out.put(k, textOrNull(item, "value"));
                }
            }
        }
        return out;
    }

    /**
     * Checks whether checkpoint/savepoint directories and externalized retention are configured on
     * the cluster, supporting both Flink 1.20 ({@code execution.checkpointing.*}) and legacy
     * ({@code state.*}) keys.
     */
    public List<String> configWarnings() {
        Map<String, String> cfg;
        try {
            cfg = jobManagerConfig();
        } catch (ConsoleException e) {
            return List.of("无法读取 Flink 集群配置：" + e.getMessage());
        }
        List<String> warnings = new ArrayList<>();
        String checkpointDir = firstNonBlank(cfg.get("execution.checkpointing.dir"), cfg.get("state.checkpoints.dir"));
        if (checkpointDir == null) {
            warnings.add("集群未配置 checkpoint 存储目录（execution.checkpointing.dir / state.checkpoints.dir），实时任务无法持久化检查点");
        }
        String savepointDir = firstNonBlank(cfg.get("execution.checkpointing.savepoint-dir"), cfg.get("state.savepoints.dir"));
        if (savepointDir == null) {
            warnings.add("集群未配置 savepoint 默认目录（execution.checkpointing.savepoint-dir / state.savepoints.dir），带 savepoint 停止实时任务会失败");
        }
        String retention = cfg.get("execution.checkpointing.externalized-checkpoint-retention");
        if (retention == null || !"RETAIN_ON_CANCELLATION".equalsIgnoreCase(retention.trim())) {
            warnings.add("集群未配置 execution.checkpointing.externalized-checkpoint-retention=RETAIN_ON_CANCELLATION，取消实时任务后无法从 checkpoint 恢复");
        }
        return warnings;
    }

    public List<EngineWorker> workers() {
        JsonNode body = send("/taskmanagers", HttpRequest.newBuilder().GET(), "application/json");
        List<EngineWorker> result = new ArrayList<>();
        for (JsonNode tm : body.path("taskmanagers")) {
            String address = firstNonBlank(textOrNull(tm, "id"), textOrNull(tm, "path"));
            JsonNode mem = tm.path("memoryConfiguration");
            long heap = mem.path("taskHeap").asLong(0) + mem.path("frameworkHeap").asLong(0);
            if (heap == 0) {
                heap = tm.path("hardware").path("freeMemory").asLong(0);
            }
            long freeMem = tm.path("hardware").path("freeMemory").asLong(heap);
            int totalSlots = tm.path("slotsNumber").asInt(0);
            int freeSlots = tm.path("freeSlots").asInt(0);
            result.add(new EngineWorker(address, heap, freeMem, totalSlots, freeSlots, false, Math.max(0, totalSlots - freeSlots)));
        }
        return result;
    }

    public Optional<EngineJobInfo> jobInfo(String jobId) {
        Optional<JsonNode> maybe = sendOptional("/jobs/" + encode(jobId), HttpRequest.newBuilder().GET(), "application/json");
        if (maybe.isEmpty()) {
            return Optional.empty();
        }
        JsonNode body = maybe.get();
        String state = textOrNull(body, "state");
        EngineJobStatus status = mapStatus(state);
        String name = textOrNull(body, "name");
        long startTime = body.path("start-time").asLong(0);
        long durationMs = body.path("duration").asLong(0);
        String createTime = startTime > 0 ? Instant.ofEpochMilli(startTime).toString() : null;

        Map<String, String> metrics = readAccumulators(jobId);
        if (durationMs > 0) {
            double seconds = durationMs / 1000.0;
            addDerivedQps(metrics, "SourceReceivedCount", "SourceReceivedQPS", seconds);
            addDerivedQps(metrics, "SinkWriteCount", "SinkWriteQPS", seconds);
        }
        String errorMessage = null;
        if (status == EngineJobStatus.FAILED || status == EngineJobStatus.FAILING) {
            errorMessage = readRootExceptionSummary(jobId);
        }
        return Optional.of(new EngineJobInfo(jobId, name, status, createTime, metrics, errorMessage, 0));
    }

    public Optional<EngineCheckpoints> checkpoints(String jobId) {
        Optional<JsonNode> maybe = sendOptional("/jobs/" + encode(jobId) + "/checkpoints", HttpRequest.newBuilder().GET(), "application/json");
        if (maybe.isEmpty()) {
            return Optional.empty();
        }
        JsonNode body = maybe.get();
        JsonNode counts = body.path("counts");
        int total = counts.path("total").asInt(0);
        int completed = counts.path("completed").asInt(0);
        int failed = counts.path("failed").asInt(0);
        if (total == 0 && completed == 0 && failed == 0) {
            return Optional.empty();
        }
        JsonNode latestCompleted = body.path("latest").path("completed");
        Long lastCompletedAt = null;
        String externalPath = null;
        if (latestCompleted.isObject()) {
            JsonNode ack = latestCompleted.path("latest_ack_timestamp");
            if (ack.canConvertToLong() && ack.asLong() > 0) {
                lastCompletedAt = ack.asLong();
            }
            externalPath = textOrNull(latestCompleted, "external_path");
            if ("<checkpoint-not-externally-addressable>".equals(externalPath)) {
                externalPath = null;
            }
        }
        JsonNode latestFailed = body.path("latest").path("failed");
        Long lastFailedAt = null;
        String failureReason = null;
        if (latestFailed.isObject()) {
            JsonNode ts = latestFailed.path("trigger_timestamp");
            if (ts.canConvertToLong() && ts.asLong() > 0) {
                lastFailedAt = ts.asLong();
            }
            failureReason = textOrNull(latestFailed, "failure_message");
        }
        return Optional.of(new EngineCheckpoints(total, completed, failed, lastCompletedAt, lastFailedAt, failureReason, externalPath));
    }

    public Optional<String> jobExceptions(String jobId) {
        Optional<JsonNode> maybe = sendOptional("/jobs/" + encode(jobId) + "/exceptions", HttpRequest.newBuilder().GET(), "application/json");
        if (maybe.isEmpty()) {
            return Optional.empty();
        }
        JsonNode body = maybe.get();
        String root = textOrNull(body, "root-exception");
        if (root != null && !root.isBlank()) {
            return Optional.of(root);
        }
        JsonNode history = body.path("exceptionHistory").path("entries");
        if (history.isArray() && !history.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode entry : history) {
                String stack = textOrNull(entry, "stacktrace");
                if (stack != null && !stack.isBlank()) {
                    if (!sb.isEmpty()) {
                        sb.append("\n---\n");
                    }
                    String task = textOrNull(entry, "taskName");
                    if (task != null) {
                        sb.append("Task: ").append(task).append('\n');
                    }
                    sb.append(stack);
                }
            }
            if (!sb.isEmpty()) {
                return Optional.of(sb.toString());
            }
        }
        return Optional.empty();
    }

    public StopResult stop(String jobId, boolean withSavepoint, boolean force) {
        if (withSavepoint && !force) {
            return stopWithSavepoint(jobId);
        }
        send("/jobs/" + encode(jobId), HttpRequest.newBuilder().method("PATCH", HttpRequest.BodyPublishers.noBody()), "application/json");
        return StopResult.NONE;
    }

    private StopResult stopWithSavepoint(String jobId) {
        JsonNode trigger = send(
                "/jobs/" + encode(jobId) + "/stop",
                HttpRequest.newBuilder().POST(HttpRequest.BodyPublishers.ofString("{}")),
                "application/json");
        String requestId = textOrNull(trigger, "request-id");
        if (requestId == null || requestId.isBlank()) {
            throw new ConsoleException(ErrorCodes.ENGINE_REQUEST_FAILED, "Flink /stop returned no request-id: " + trigger);
        }
        for (int i = 0; i < SAVEPOINT_POLL_ATTEMPTS; i++) {
            JsonNode status = send(
                    "/jobs/" + encode(jobId) + "/savepoints/" + encode(requestId),
                    HttpRequest.newBuilder().GET(),
                    "application/json");
            String id = status.path("status").path("id").asString("");
            if ("COMPLETED".equalsIgnoreCase(id)) {
                JsonNode op = status.path("operation");
                String location = textOrNull(op, "location");
                if (location != null && !location.isBlank()) {
                    log.info("Stopped Flink job {} with savepoint {}", jobId, location);
                    return new StopResult(location);
                }
                String cause = op.path("failure-cause").path("stack-trace").asString("");
                throw new ConsoleException(
                        ErrorCodes.ENGINE_REQUEST_FAILED,
                        "Flink stop-with-savepoint failed: " + abbreviate(cause.isEmpty() ? op.toString() : cause));
            }
            try {
                Thread.sleep(SAVEPOINT_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConsoleException(ErrorCodes.ENGINE_RESPONSE_LOST, "interrupted while waiting for Flink savepoint", e);
            }
        }
        throw new ConsoleException(ErrorCodes.ENGINE_RESPONSE_LOST, "timed out waiting for Flink savepoint on job " + jobId);
    }

    public void disposeSavepoint(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        String payload = mapper.writeValueAsString(Map.of("savepoint-path", path));
        send(
                "/savepoint-disposal",
                HttpRequest.newBuilder().POST(HttpRequest.BodyPublishers.ofString(payload)),
                "application/json");
        log.info("Requested Flink savepoint disposal for {}", path);
    }

    /**
     * Lists active JobManager and TaskManager log files for the TaskManagers that actually ran
     * {@code jobId}, skipping historical rotated log archives so live viewing stays lightweight.
     */
    public List<EngineLogFile> listLogs(String jobId) {
        List<EngineLogFile> files = new ArrayList<>();
        try {
            JsonNode jmLogs = send("/jobmanager/logs", HttpRequest.newBuilder().GET(), "application/json");
            for (JsonNode item : jmLogs.path("logs")) {
                String name = textOrNull(item, "name");
                if (isPrimaryLogFile(name)) {
                    files.add(new EngineLogFile("jobmanager", name, config.restUrl() + "/jobmanager/logs/" + encode(name)));
                }
            }
        } catch (ConsoleException e) {
            log.debug("Could not list Flink JobManager logs: {}", e.getMessage());
        }

        Set<String> tmIds = taskManagersForJob(jobId);
        if (tmIds.isEmpty()) {
            // Fall back to active TaskManagers only when the job already expired from JobManager memory.
            try {
                JsonNode tms = send("/taskmanagers", HttpRequest.newBuilder().GET(), "application/json");
                for (JsonNode tm : tms.path("taskmanagers")) {
                    String id = textOrNull(tm, "id");
                    if (id != null) {
                        tmIds.add(id);
                    }
                }
            } catch (ConsoleException e) {
                log.debug("Could not list Flink TaskManagers: {}", e.getMessage());
            }
        }

        for (String tmId : tmIds) {
            try {
                JsonNode tmLogs = send("/taskmanagers/" + encode(tmId) + "/logs", HttpRequest.newBuilder().GET(), "application/json");
                for (JsonNode item : tmLogs.path("logs")) {
                    String name = textOrNull(item, "name");
                    if (isPrimaryLogFile(name)) {
                        files.add(new EngineLogFile(
                                "taskmanager/" + tmId,
                                name,
                                config.restUrl() + "/taskmanagers/" + encode(tmId) + "/logs/" + encode(name)));
                    }
                }
            } catch (ConsoleException e) {
                log.debug("Could not list logs for TaskManager {}: {}", tmId, e.getMessage());
            }
        }
        return files;
    }

    /** Finds only the TaskManager IDs whose subtasks executed {@code jobId}. */
    private Set<String> taskManagersForJob(String jobId) {
        Set<String> tmIds = new LinkedHashSet<>();
        if (jobId == null || jobId.isBlank()) {
            return tmIds;
        }
        try {
            Optional<JsonNode> job = sendOptional("/jobs/" + encode(jobId), HttpRequest.newBuilder().GET(), "application/json");
            if (job.isEmpty()) {
                return tmIds;
            }
            for (JsonNode vertex : job.get().path("vertices")) {
                String vid = textOrNull(vertex, "id");
                if (vid == null) {
                    continue;
                }
                Optional<JsonNode> detail = sendOptional(
                        "/jobs/" + encode(jobId) + "/vertices/" + encode(vid),
                        HttpRequest.newBuilder().GET(),
                        "application/json");
                if (detail.isPresent()) {
                    for (JsonNode subtask : detail.get().path("subtasks")) {
                        String tmId = textOrNull(subtask, "taskmanager-id");
                        if (tmId != null && !tmId.isBlank()) {
                            tmIds.add(tmId);
                        }
                    }
                }
            }
        } catch (ConsoleException e) {
            log.debug("Could not resolve TaskManagers for Flink job {}: {}", jobId, e.getMessage());
        }
        return tmIds;
    }

    private static boolean isPrimaryLogFile(String name) {
        return name != null && (name.endsWith(".log") || name.endsWith(".out"));
    }

    public LogContent readLog(EngineLogFile file, long maxBytes) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(file.location()))
                .timeout(config.requestTimeout())
                .header("Accept", "text/plain")
                .GET()
                .build();
        TailBuffer tail = new TailBuffer((int) Math.min(maxBytes, Integer.MAX_VALUE - 8));
        CompletableFuture<HttpResponse<Void>> call =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArrayConsumer(chunk -> chunk.ifPresent(tail::write)));
        Duration limit = config.requestTimeout().multipliedBy(LOG_READ_TIMEOUT_FACTOR);
        HttpResponse<Void> response;
        try {
            response = call.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            throw new ConsoleException(ErrorCodes.ENGINE_UNAVAILABLE, "reading Flink log " + file.name() + " timed out");
        } catch (ExecutionException e) {
            throw new ConsoleException(ErrorCodes.ENGINE_UNAVAILABLE, "cannot read Flink log " + file.name() + ": " + e.getCause());
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new ConsoleException(ErrorCodes.ENGINE_UNAVAILABLE, "interrupted while reading Flink log", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new ConsoleException(ErrorCodes.ENGINE_REQUEST_FAILED, "reading Flink log " + file.name() + " returned HTTP " + response.statusCode());
        }
        return tail.result();
    }

    private Map<String, String> readAccumulators(String jobId) {
        Map<String, String> metrics = new LinkedHashMap<>();
        try {
            Optional<JsonNode> maybe = sendOptional("/jobs/" + encode(jobId) + "/accumulators", HttpRequest.newBuilder().GET(), "application/json");
            if (maybe.isPresent()) {
                for (JsonNode acc : maybe.get().path("user-task-accumulators")) {
                    String name = textOrNull(acc, "name");
                    String value = textOrNull(acc, "value");
                    if (name != null && value != null) {
                        metrics.put(name, value);
                    }
                }
            }
        } catch (ConsoleException e) {
            log.debug("Could not read accumulators for Flink job {}: {}", jobId, e.getMessage());
        }
        return metrics;
    }

    private static void addDerivedQps(Map<String, String> metrics, String countKey, String qpsKey, double seconds) {
        if (metrics.containsKey(qpsKey)) {
            return;
        }
        String raw = metrics.get(countKey);
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            double count = Double.parseDouble(raw.trim());
            metrics.put(qpsKey, String.valueOf(count / seconds));
        } catch (NumberFormatException ignored) {
            // non-numeric accumulator
        }
    }

    private String readRootExceptionSummary(String jobId) {
        try {
            return jobExceptions(jobId).map(FlinkRestClient::firstLineOrClip).orElse(null);
        } catch (ConsoleException e) {
            return null;
        }
    }

    static EngineJobStatus mapStatus(String flinkState) {
        if (flinkState == null || flinkState.isBlank()) {
            return EngineJobStatus.UNKNOWABLE;
        }
        return switch (flinkState.trim().toUpperCase()) {
            case "INITIALIZING" -> EngineJobStatus.INITIALIZING;
            case "CREATED" -> EngineJobStatus.CREATED;
            case "RUNNING", "RESTARTING", "RECONCILING" -> EngineJobStatus.RUNNING;
            case "FAILING" -> EngineJobStatus.FAILING;
            case "FAILED" -> EngineJobStatus.FAILED;
            case "CANCELLING" -> EngineJobStatus.CANCELING;
            case "CANCELED", "SUSPENDED" -> EngineJobStatus.CANCELED;
            case "FINISHED" -> EngineJobStatus.FINISHED;
            default -> EngineJobStatus.UNKNOWABLE;
        };
    }

    private JsonNode send(String path, HttpRequest.Builder builder, String contentType) {
        return sendInternal(path, builder, contentType, false).orElseGet(mapper::createObjectNode);
    }

    private Optional<JsonNode> sendOptional(String path, HttpRequest.Builder builder, String contentType) {
        return sendInternal(path, builder, contentType, true);
    }

    private Optional<JsonNode> sendInternal(String path, HttpRequest.Builder builder, String contentType, boolean allowNotFound) {
        HttpRequest request = builder.copy()
                .uri(URI.create(config.restUrl() + path))
                .timeout(config.requestTimeout())
                .header("Content-Type", contentType)
                .header("Accept", "application/json")
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            boolean maybeDelivered = !(e instanceof ConnectException || e instanceof HttpConnectTimeoutException);
            throw new ConsoleException(
                    maybeDelivered ? ErrorCodes.ENGINE_RESPONSE_LOST : ErrorCodes.ENGINE_UNAVAILABLE,
                    (maybeDelivered ? "no answer from Flink for " : "Flink unreachable at ") + config.restUrl() + path + ": " + e.getMessage(),
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConsoleException(ErrorCodes.ENGINE_RESPONSE_LOST, "interrupted while calling Flink " + path, e);
        }
        if (allowNotFound && response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() / 100 != 2) {
            throw new ConsoleException(
                    ErrorCodes.ENGINE_REQUEST_FAILED,
                    "Flink " + path + " returned HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
        }
        String body = response.body();
        return Optional.of(body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= MAX_ERROR_BODY ? body : body.substring(0, MAX_ERROR_BODY) + "...";
    }

    private static String firstLineOrClip(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.length() <= MAX_ERROR_BODY ? text : text.substring(0, MAX_ERROR_BODY) + "...";
    }
}
