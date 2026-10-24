package com.dataintegration.console.engine.zeta;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.engine.ConnectorOption;
import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.engine.EngineJobInfo;
import com.dataintegration.console.engine.EngineJobStatus;
import com.dataintegration.console.engine.EngineLogFile;
import com.dataintegration.console.engine.EngineType;
import com.dataintegration.console.engine.SubmitJobRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * {@link EngineClient} backed by SeaTunnel Zeta REST API v2 (verified against 3.0.0).
 *
 * <p>Requests go to the first configured master; on a connection failure the next master is tried.
 * HTTP error responses are not retried, because a submit may already have been accepted.
 */
public class ZetaRestClient implements EngineClient {

    private static final Logger log = LoggerFactory.getLogger(ZetaRestClient.class);

    private static final int MAX_ERROR_BODY = 2000;

    private final ZetaClusterConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public ZetaRestClient(ZetaClusterConfig config) {
        this(config, HttpClient.newBuilder().connectTimeout(config.connectTimeout()).build());
    }

    ZetaRestClient(ZetaClusterConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    @Override
    public EngineType type() {
        return EngineType.ZETA;
    }

    @Override
    public Map<String, String> overview() {
        JsonNode body = send("/overview", HttpRequest.newBuilder().GET(), "application/json");
        Map<String, String> result = new LinkedHashMap<>();
        body.properties().forEach(e -> result.put(e.getKey(), e.getValue().asString()));
        return result;
    }

    /** {@code GET /option-rules?type=&plugin=} (3.0); required option groups are flattened. */
    @Override
    public List<ConnectorOption> connectorOptions(String pluginType, String plugin) {
        JsonNode body = send(
                "/option-rules?type=" + URLEncoder.encode(pluginType, StandardCharsets.UTF_8)
                        + "&plugin=" + URLEncoder.encode(plugin, StandardCharsets.UTF_8),
                HttpRequest.newBuilder().GET(),
                "application/json");
        if ("FAIL".equals(textOrNull(body, "status"))) {
            throw new ConsoleException(ErrorCodes.ENGINE_REQUEST_FAILED,
                    "Zeta has no option rules for " + pluginType + " " + plugin + ": " + textOrNull(body, "message"));
        }
        JsonNode rule = body.path("optionRule");
        List<ConnectorOption> options = new ArrayList<>();
        collectOptions(rule.path("requiredOptions"), true, options);
        collectOptions(rule.path("optionalOptions"), false, options);
        return options;
    }

    private static void collectOptions(JsonNode list, boolean required, List<ConnectorOption> into) {
        for (JsonNode item : list) {
            if (item.has("key")) {
                into.add(new ConnectorOption(
                        item.get("key").asString(),
                        textOrNull(item, "type"),
                        item.path("defaultValue").isNull() || item.path("defaultValue").isMissingNode()
                                ? null
                                : item.get("defaultValue").isValueNode() ? item.get("defaultValue").asString() : item.get("defaultValue").toString(),
                        textOrNull(item, "description"),
                        required));
            } else if (item.has("options")) {
                collectOptions(item.get("options"), required, into);
            }
        }
    }

    @Override
    public long submit(SubmitJobRequest request) {
        StringJoiner query = new StringJoiner("&", "?", "");
        query.add("format=hocon");
        if (request.jobName() != null) {
            query.add("jobName=" + URLEncoder.encode(request.jobName(), StandardCharsets.UTF_8));
        }
        if (request.restoreMode() != SubmitJobRequest.RestoreMode.NONE) {
            query.add("restoreMode=" + request.restoreMode().name());
            query.add("restoreSourceJobId=" + request.restoreSourceJobId());
        }
        JsonNode body =
                send(
                        "/submit-job" + query,
                        HttpRequest.newBuilder()
                                .POST(HttpRequest.BodyPublishers.ofString(request.config())),
                        "text/plain; charset=utf-8");
        JsonNode jobId = body.get("jobId");
        if (jobId == null || jobId.isNull()) {
            throw new ConsoleException(
                    ErrorCodes.ENGINE_REQUEST_FAILED, "submit-job returned no jobId: " + body);
        }
        log.info("Submitted job '{}' to Zeta, jobId={}", request.jobName(), jobId.asString());
        return jobId.asLong();
    }

    @Override
    public void stop(long jobId, boolean withSavepoint, boolean force) {
        String payload =
                mapper.writeValueAsString(
                        Map.of(
                                "jobId", jobId,
                                "isStopWithSavePoint", withSavepoint,
                                "force", force));
        send(
                "/stop-job",
                HttpRequest.newBuilder().POST(HttpRequest.BodyPublishers.ofString(payload)),
                "application/json");
    }

    @Override
    public Optional<EngineJobInfo> jobInfo(long jobId) {
        JsonNode body = send("/job-info/" + jobId, HttpRequest.newBuilder().GET(), "application/json");
        // An unknown or expired job comes back as {"jobId": "..."} without a status.
        if (!body.hasNonNull("jobStatus")) {
            return Optional.empty();
        }
        Map<String, String> metrics = new LinkedHashMap<>();
        JsonNode metricsNode = body.path("metrics");
        metricsNode.properties()
                .forEach(
                        e ->
                                metrics.put(
                                        e.getKey(),
                                        e.getValue().isValueNode()
                                                ? e.getValue().asString()
                                                : e.getValue().toString()));
        return Optional.of(
                new EngineJobInfo(
                        jobId,
                        textOrNull(body, "jobName"),
                        EngineJobStatus.parse(body.get("jobStatus").asString()),
                        textOrNull(body, "createTime"),
                        metrics,
                        textOrNull(body, "errorMsg")));
    }

    @Override
    public List<EngineLogFile> listLogs(long jobId) {
        // Response format (verified on 3.0.0): [{"node": "host:port", "logLink": "http://...", "logName": "job-<id>.log"}]
        JsonNode body = send("/logs/" + jobId + "?format=json", HttpRequest.newBuilder().GET(), "application/json");
        List<EngineLogFile> files = new ArrayList<>();
        for (JsonNode node : body) {
            String link = textOrNull(node, "logLink");
            if (link != null) {
                files.add(new EngineLogFile(textOrNull(node, "node"), textOrNull(node, "logName"), link));
            }
        }
        return files;
    }

    /** {@code file.location()} is the absolute logLink reported by the node that owns the file. */
    @Override
    public LogContent readLog(EngineLogFile file, long maxBytes) {
        HttpRequest request = decorate(HttpRequest.newBuilder().GET(), file.location(), "text/plain");
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new ConsoleException(
                    ErrorCodes.ENGINE_UNAVAILABLE, "cannot read log " + file.name() + " from " + file.node() + ": " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConsoleException(ErrorCodes.ENGINE_UNAVAILABLE, "interrupted while reading log", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new ConsoleException(
                    ErrorCodes.ENGINE_REQUEST_FAILED,
                    "reading log " + file.name() + " returned HTTP " + response.statusCode());
        }
        byte[] bytes = response.body();
        boolean truncated = bytes.length > maxBytes;
        int from = truncated ? (int) (bytes.length - maxBytes) : 0;
        return new LogContent(new String(bytes, from, bytes.length - from, StandardCharsets.UTF_8), truncated);
    }

    private JsonNode send(String path, HttpRequest.Builder builder, String contentType) {
        IOException lastFailure = null;
        for (String master : config.masterUrls()) {
            HttpRequest request = decorate(builder.copy(), master + path, contentType);
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                log.warn("Zeta master {} unreachable: {}", master, e.toString());
                lastFailure = e;
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConsoleException(
                        ErrorCodes.ENGINE_UNAVAILABLE, "interrupted while calling Zeta", e);
            }
            if (response.statusCode() / 100 != 2) {
                throw new ConsoleException(
                        ErrorCodes.ENGINE_REQUEST_FAILED,
                        "Zeta " + stripQuery(path) + " returned HTTP " + response.statusCode()
                                + ": " + abbreviate(response.body()));
            }
            String responseBody = response.body();
            return responseBody == null || responseBody.isBlank()
                    ? mapper.createObjectNode()
                    : mapper.readTree(responseBody);
        }
        throw new ConsoleException(
                ErrorCodes.ENGINE_UNAVAILABLE,
                "no Zeta master reachable: " + config.masterUrls(),
                lastFailure);
    }

    private HttpRequest decorate(HttpRequest.Builder builder, String url, String contentType) {
        builder.uri(URI.create(url))
                .timeout(config.requestTimeout())
                .header("Content-Type", contentType)
                .header("Accept", "application/json");
        if (config.basicAuthEnabled()) {
            String token = config.username() + ":" + (config.password() == null ? "" : config.password());
            builder.header(
                    "Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)));
        }
        return builder.build();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= MAX_ERROR_BODY ? body : body.substring(0, MAX_ERROR_BODY) + "...";
    }

    private static String stripQuery(String path) {
        int idx = path.indexOf('?');
        return idx < 0 ? path : path.substring(0, idx);
    }
}
