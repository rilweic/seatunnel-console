package com.dataintegration.console.datasource.es;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.datasource.ConnectionTestResult;
import com.dataintegration.console.datasource.CountRequest;
import com.dataintegration.console.datasource.meta.ColumnMeta;
import com.dataintegration.console.datasource.meta.TableMeta;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.dataintegration.console.datasource.DataSourcePlugin;
import com.dataintegration.console.datasource.DataSourceType;
import com.dataintegration.console.datasource.Props;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Elasticsearch over HTTP. Properties: hosts (comma separated, e.g. {@code http://es1:9200}),
 * optional username and password (secret). Maps onto SeaTunnel's {@code Elasticsearch} connector,
 * whose hosts/username/password options were verified against 3.0.0 ElasticsearchBaseOptions.
 */
public class ElasticsearchDataSourcePlugin implements DataSourcePlugin {

    public static final String ES_CONNECTOR = "Elasticsearch";

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern COUNT = Pattern.compile("\"count\"\\s*:\\s*(\\d+)");
    private static final Pattern INDEX = Pattern.compile("[A-Za-z0-9_.*,\\-]+");

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Set<String> TEMPORAL = Set.of("date", "date_nanos");

    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(TIMEOUT).proxy(HttpClient.Builder.NO_PROXY).build();

    @Override
    public DataSourceType type() {
        return DataSourceType.ELASTICSEARCH;
    }

    @Override
    public Set<String> secretKeys() {
        return Set.of("password");
    }

    @Override
    public void validate(Map<String, String> props) {
        for (String host : hosts(props)) {
            if (!host.startsWith("http://") && !host.startsWith("https://")) {
                throw new ConsoleException(
                        ErrorCodes.INVALID_ARGUMENT, "hosts must start with http:// or https://: " + host);
            }
        }
    }

    @Override
    public ConnectionTestResult testConnection(Map<String, String> props) {
        long start = System.currentTimeMillis();
        try {
            validate(props);
            String failure = null;
            for (String host : hosts(props)) {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(host + "/")).timeout(TIMEOUT).GET();
                String username = Props.optional(props, "username");
                if (username != null) {
                    String token = username + ":" + (props.get("password") == null ? "" : props.get("password"));
                    request.header(
                            "Authorization",
                            "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)));
                }
                HttpResponse<String> response =
                        httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return ConnectionTestResult.ok(System.currentTimeMillis() - start);
                }
                failure = host + " returned HTTP " + response.statusCode();
            }
            return ConnectionTestResult.failed(failure, System.currentTimeMillis() - start);
        } catch (IOException e) {
            return ConnectionTestResult.failed(e.toString(), System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ConnectionTestResult.failed("interrupted", System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            return ConnectionTestResult.failed(e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    /** {@code POST /<index>/_count}, with {"query": esQuery} as body when a query is given. */
    @Override
    public long count(Map<String, String> props, CountRequest request) {
        if (request.index() == null || !INDEX.matcher(request.index().trim()).matches()) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "invalid index for count: " + request.index());
        }
        validate(props);
        String body = request.esQuery() == null || request.esQuery().isBlank() ? "" : "{\"query\":" + request.esQuery() + "}";
        String failure = null;
        for (String host : hosts(props)) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(host + "/" + request.index().trim() + "/_count"))
                    .timeout(request.timeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.isEmpty() ? "{}" : body));
            authorize(builder, props);
            try {
                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    String text = response.body() == null ? "" : response.body();
                    throw new ConsoleException(ErrorCodes.DATASOURCE_CONNECT_FAILED,
                            host + " _count returned HTTP " + response.statusCode() + ": " + text.substring(0, Math.min(500, text.length())));
                }
                Matcher m = COUNT.matcher(response.body());
                if (!m.find()) {
                    throw new ConsoleException(ErrorCodes.DATASOURCE_CONNECT_FAILED, host + " _count response has no count");
                }
                return Long.parseLong(m.group(1));
            } catch (IOException e) {
                failure = host + ": " + e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConsoleException(ErrorCodes.DATASOURCE_CONNECT_FAILED, "interrupted while counting");
            }
        }
        throw new ConsoleException(ErrorCodes.DATASOURCE_CONNECT_FAILED, "no Elasticsearch host reachable: " + failure);
    }

    /** Elasticsearch has no database or schema level. */
    @Override
    public List<String> namespaces(Map<String, String> props) {
        return List.of();
    }

    /** Indices, without hidden ones (names starting with a dot); ES has no primary keys. */
    @Override
    public List<TableMeta> tables(Map<String, String> props, String namespace) {
        JsonNode indices = getJson(props, "/_cat/indices?format=json&h=index");
        List<TableMeta> tables = new ArrayList<>();
        for (JsonNode index : indices) {
            String name = index.path("index").asString();
            if (!name.startsWith(".")) {
                tables.add(new TableMeta(name, List.of()));
            }
        }
        tables.sort((a, b) -> a.name().compareTo(b.name()));
        return tables;
    }

    /** Top-level fields of the index mapping; an object field is reported with type "object". */
    @Override
    public List<ColumnMeta> columns(Map<String, String> props, String namespace, String table) {
        if (table == null || !INDEX.matcher(table).matches()) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "invalid index name: " + table);
        }
        JsonNode root = getJson(props, "/" + table + "/_mapping");
        List<ColumnMeta> columns = new ArrayList<>();
        for (JsonNode index : root) {
            index.path("mappings").path("properties").forEachEntry((name, field) -> {
                String type = field.has("type") ? field.get("type").asString() : "object";
                columns.add(new ColumnMeta(name, type, true, false, TEMPORAL.contains(type)));
            });
            break;
        }
        return columns;
    }

    private JsonNode getJson(Map<String, String> props, String path) {
        validate(props);
        String failure = null;
        for (String host : hosts(props)) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(host + path)).timeout(TIMEOUT).GET();
            authorize(builder, props);
            try {
                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 404) {
                    throw new ConsoleException(ErrorCodes.NOT_FOUND, "not found in Elasticsearch: " + path);
                }
                if (response.statusCode() != 200) {
                    throw new ConsoleException(ErrorCodes.DATASOURCE_CONNECT_FAILED, host + path + " returned HTTP " + response.statusCode());
                }
                return JSON.readTree(response.body());
            } catch (IOException e) {
                failure = host + ": " + e;
            } catch (JacksonException e) {
                throw new ConsoleException(ErrorCodes.DATASOURCE_CONNECT_FAILED, host + path + " returned invalid JSON");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConsoleException(ErrorCodes.DATASOURCE_CONNECT_FAILED, "interrupted");
            }
        }
        throw new ConsoleException(ErrorCodes.DATASOURCE_CONNECT_FAILED, "no Elasticsearch host reachable: " + failure);
    }

    private static void authorize(HttpRequest.Builder builder, Map<String, String> props) {
        String username = Props.optional(props, "username");
        if (username != null) {
            String token = username + ":" + (props.get("password") == null ? "" : props.get("password"));
            builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Override
    public Map<String, Object> toConnectorOptions(String connector, Map<String, String> props) {
        if (!ES_CONNECTOR.equals(connector)) {
            throw Props.unsupportedConnector(type(), connector);
        }
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("hosts", hosts(props));
        String username = Props.optional(props, "username");
        if (username != null) {
            options.put("username", username);
            options.put("password", props.get("password") == null ? "" : props.get("password"));
        }
        return options;
    }

    static List<String> hosts(Map<String, String> props) {
        List<String> hosts =
                Arrays.stream(Props.required(props, "hosts").split(","))
                        .map(String::trim)
                        .filter(h -> !h.isEmpty())
                        .map(h -> h.endsWith("/") ? h.substring(0, h.length() - 1) : h)
                        .toList();
        if (hosts.isEmpty()) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "hosts is required");
        }
        return hosts;
    }
}
