package com.dataintegration.console.datasource.http;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.common.SecretRules;
import com.dataintegration.console.datasource.ConnectionTestResult;
import com.dataintegration.console.datasource.CountRequest;
import com.dataintegration.console.datasource.DataSourcePlugin;
import com.dataintegration.console.datasource.DataSourceCapabilities;
import com.dataintegration.console.datasource.DataSourceType;
import com.dataintegration.console.datasource.Props;
import com.dataintegration.console.datasource.meta.ColumnMeta;
import com.dataintegration.console.datasource.meta.TableMeta;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** HTTP JSON source. Authentication belongs to the datasource, not to individual jobs. */
public class HttpDataSourcePlugin implements DataSourcePlugin {

    public static final String HTTP_CONNECTOR = "Http";

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_SOCKET_TIMEOUT_MS = 10_000;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Override
    public DataSourceType type() {
        return DataSourceType.HTTP;
    }

    @Override
    public String address(Map<String, String> config) {
        return config.get("baseUrl");
    }

    /** A wizard source only: one endpoint per job, read page by page, and it cannot be counted. */
    @Override
    public DataSourceCapabilities capabilities() {
        return new DataSourceCapabilities("HTTP", HTTP_CONNECTOR, null, false, false, null, 1, false, false);
    }

    @Override
    public Set<String> secretKeys() {
        return Set.of("token", "password", "apiKey");
    }

    @Override
    public void validate(Map<String, String> props) {
        String baseUrl = Props.required(props, "baseUrl");
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "baseUrl must be a valid HTTP URL");
        }
        if (!uri.isAbsolute()
                || uri.getHost() == null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "baseUrl must be an absolute HTTP or HTTPS URL");
        }
        if (uri.getUserInfo() != null) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT,
                    "baseUrl must not contain userinfo; use the authentication settings");
        }
        // A job's path is appended to the base URL, so a query or fragment here would swallow it.
        // This also keeps credentials out of the stored URL.
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || baseUrl.contains("?") || baseUrl.contains("#")) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT,
                    "baseUrl must not contain a query or fragment; put query parameters in the job's request parameters");
        }
        timeout(props, "connectTimeoutMs", DEFAULT_CONNECT_TIMEOUT_MS);
        timeout(props, "socketTimeoutMs", DEFAULT_SOCKET_TIMEOUT_MS);
        commonHeaders(props);

        String authType = authType(props);
        switch (authType) {
            case "BEARER" -> Props.required(props, "token");
            case "BASIC" -> {
                Props.required(props, "username");
                Props.required(props, "password");
            }
            case "API_KEY" -> {
                Props.required(props, "apiKeyName");
                Props.required(props, "apiKey");
                String location = apiKeyLocation(props);
                if (!location.equals("HEADER") && !location.equals("QUERY")) {
                    throw new ConsoleException(
                            ErrorCodes.INVALID_ARGUMENT, "apiKeyLocation must be HEADER or QUERY");
                }
            }
            case "NONE" -> { }
            default -> throw new ConsoleException(
                    ErrorCodes.INVALID_ARGUMENT, "authType must be NONE, BEARER, BASIC or API_KEY");
        }
    }

    @Override
    public ConnectionTestResult testConnection(Map<String, String> props) {
        long start = System.currentTimeMillis();
        try {
            HttpPreviewResponse response = execute(
                    props, new HttpPreviewRequest(Props.optional(props, "testPath"), "GET", Map.of(), null));
            if (response.status() >= 200 && response.status() < 300) {
                return ConnectionTestResult.ok(response.elapsedMillis());
            }
            if (response.status() == 401 || response.status() == 403) {
                return ConnectionTestResult.failed(
                        "authentication failed (HTTP " + response.status() + ")", response.elapsedMillis());
            }
            String body = response.body();
            String excerpt = body.substring(0, Math.min(200, body.length()));
            return ConnectionTestResult.failed(
                    "HTTP " + response.status() + ": " + redact(excerpt, props), response.elapsedMillis());
        } catch (ConsoleException e) {
            return ConnectionTestResult.failed(e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    public HttpPreviewResponse preview(Map<String, String> props, HttpPreviewRequest request) {
        return execute(props, request);
    }

    /** Sends exactly one request and returns non-2xx responses to the caller for inspection. */
    public HttpPreviewResponse execute(Map<String, String> props, HttpPreviewRequest request) {
        validate(props);
        if (request == null) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "request is required");
        }
        String method = request.method() == null || request.method().isBlank()
                ? "GET"
                : request.method().trim().toUpperCase(Locale.ROOT);
        if (!method.equals("GET") && !method.equals("POST")) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "method must be GET or POST");
        }

        Map<String, String> params = requestParams(props, request.params());
        URI uri = requestUri(props, request.path(), params);
        Duration socketTimeout = Duration.ofMillis(timeout(props, "socketTimeoutMs", DEFAULT_SOCKET_TIMEOUT_MS));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(socketTimeout);
        Map<String, String> headers = requestHeaders(props);
        headers.forEach(builder::header);
        if (method.equals("POST")) {
            if (headers.keySet().stream().noneMatch("Content-Type"::equalsIgnoreCase)) {
                builder.header("Content-Type", "application/json");
            }
            builder.POST(HttpRequest.BodyPublishers.ofString(
                    request.body() == null ? "" : request.body(), StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeout(props, "connectTimeoutMs", DEFAULT_CONNECT_TIMEOUT_MS)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
        long start = System.currentTimeMillis();
        try {
            HttpResponse<InputStream> response = client.send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            String body;
            try (InputStream input = response.body()) {
                byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new ConsoleException(
                            ErrorCodes.DATASOURCE_CONNECT_FAILED, "HTTP response body exceeds 2 MB");
                }
                body = new String(bytes, StandardCharsets.UTF_8);
            }
            return new HttpPreviewResponse(
                    response.statusCode(), System.currentTimeMillis() - start, redact(body, props));
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ConsoleException(ErrorCodes.DATASOURCE_CONNECT_FAILED, "HTTP request timed out");
        } catch (IOException e) {
            throw new ConsoleException(
                    ErrorCodes.DATASOURCE_CONNECT_FAILED,
                    "HTTP request failed: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConsoleException(ErrorCodes.DATASOURCE_CONNECT_FAILED, "HTTP request interrupted");
        } catch (IllegalArgumentException e) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "invalid HTTP request path or parameters");
        }
    }

    @Override
    public Map<String, Object> toConnectorOptions(String connector, Map<String, String> props) {
        if (!HTTP_CONNECTOR.equals(connector)) {
            throw Props.unsupportedConnector(type(), connector);
        }
        validate(props);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("url", Props.required(props, "baseUrl"));
        options.put("headers", requestHeaders(props));
        options.put("params", requestParams(props, Map.of()));
        options.put("connect_timeout_ms", timeout(props, "connectTimeoutMs", DEFAULT_CONNECT_TIMEOUT_MS));
        options.put("socket_timeout_ms", timeout(props, "socketTimeoutMs", DEFAULT_SOCKET_TIMEOUT_MS));
        return options;
    }

    @Override
    public long count(Map<String, String> props, CountRequest request) {
        throw unsupported("count rows");
    }

    @Override
    public List<String> namespaces(Map<String, String> props) {
        throw unsupported("list namespaces");
    }

    @Override
    public List<TableMeta> tables(Map<String, String> props, String namespace) {
        throw unsupported("list tables");
    }

    @Override
    public List<ColumnMeta> columns(Map<String, String> props, String namespace, String table) {
        throw unsupported("list columns");
    }

    private ConsoleException unsupported(String operation) {
        return new ConsoleException(
                ErrorCodes.INVALID_ARGUMENT, "HTTP datasource does not support " + operation);
    }

    private static int timeout(Map<String, String> props, String key, int defaultValue) {
        String value = Props.optional(props, key);
        if (value == null) {
            return defaultValue;
        }
        try {
            int timeout = Integer.parseInt(value);
            if (timeout <= 0) {
                throw new NumberFormatException();
            }
            return timeout;
        } catch (NumberFormatException e) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, key + " must be a positive integer");
        }
    }

    private static String authType(Map<String, String> props) {
        String authType = Props.optional(props, "authType");
        return authType == null ? "NONE" : authType.toUpperCase(Locale.ROOT);
    }

    private static String apiKeyLocation(Map<String, String> props) {
        String location = Props.optional(props, "apiKeyLocation");
        return location == null ? "HEADER" : location.toUpperCase(Locale.ROOT);
    }

    private static Map<String, String> commonHeaders(Map<String, String> props) {
        String text = Props.optional(props, "headers");
        if (text == null) {
            return new LinkedHashMap<>();
        }
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (JacksonException e) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "headers must be a JSON object");
        }
        if (root == null || !root.isObject()) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "headers must be a JSON object");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        root.forEachEntry((name, value) -> {
            if (SecretRules.isSecretKey(name) || SecretRules.isSecretHeader(name)) {
                throw new ConsoleException(
                        ErrorCodes.INVALID_ARGUMENT, "sensitive header is not allowed: " + name);
            }
            if (value == null || value.isNull() || value.isObject() || value.isArray()) {
                throw new ConsoleException(
                        ErrorCodes.INVALID_ARGUMENT, "header values must be scalar: " + name);
            }
            headers.put(name, value.asString());
        });
        return headers;
    }

    private static Map<String, String> requestHeaders(Map<String, String> props) {
        Map<String, String> headers = commonHeaders(props);
        switch (authType(props)) {
            case "BEARER" -> headers.put("Authorization", "Bearer " + Props.required(props, "token"));
            case "BASIC" -> {
                String credentials = Props.required(props, "username") + ":" + Props.required(props, "password");
                headers.put(
                        "Authorization",
                        "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
            }
            case "API_KEY" -> {
                if (apiKeyLocation(props).equals("HEADER")) {
                    headers.put(Props.required(props, "apiKeyName"), Props.required(props, "apiKey"));
                }
            }
            default -> { }
        }
        return headers;
    }

    private static Map<String, String> requestParams(
            Map<String, String> props, Map<String, String> taskParams) {
        Map<String, String> params = new LinkedHashMap<>();
        if (taskParams != null) {
            params.putAll(taskParams);
        }
        if (authType(props).equals("API_KEY") && apiKeyLocation(props).equals("QUERY")) {
            String name = Props.required(props, "apiKeyName");
            if (params.containsKey(name)) {
                throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT,
                        "task parameter conflicts with datasource API Key: " + name);
            }
            params.put(name, Props.required(props, "apiKey"));
        }
        params.entrySet().removeIf(entry -> {
            String value = entry.getValue();
            return value == null || value.isBlank() || value.trim().equals("${cursor}");
        });
        return params;
    }

    /** Joins a task path to the datasource base URL without allowing a host override. */
    public static String resolveUrl(String baseUrl, String path) {
        String suffix = path == null ? "" : path.trim();
        if (suffix.matches("(?i)^https?://.*")) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT,
                    "HTTP request path must be a relative path within the datasource baseUrl");
        }
        if (baseUrl == null) {
            return path;
        }
        if (path == null) {
            return baseUrl;
        }
        if (suffix.isEmpty()) {
            return baseUrl;
        }
        if (baseUrl.endsWith("/") && suffix.startsWith("/")) {
            return baseUrl + suffix.substring(1);
        }
        if (!baseUrl.endsWith("/") && !suffix.startsWith("/")) {
            return baseUrl + "/" + suffix;
        }
        return baseUrl + suffix;
    }

    private static URI requestUri(Map<String, String> props, String path, Map<String, String> params) {
        String url = resolveUrl(Props.required(props, "baseUrl"), path);
        if (!params.isEmpty()) {
            StringBuilder query = new StringBuilder();
            params.forEach((name, value) -> {
                if (query.length() > 0) {
                    query.append('&');
                }
                query.append(URLEncoder.encode(name, StandardCharsets.UTF_8));
                query.append('=');
                query.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            });
            url += (url.contains("?") ? "&" : "?") + query;
        }
        return URI.create(url);
    }

    private static String redact(String text, Map<String, String> props) {
        String redacted = text;
        String authorization = requestHeaders(props).get("Authorization");
        if (authorization != null && !authorization.isEmpty()) {
            redacted = redacted.replace(authorization, "******");
        }
        for (String key : Set.of("token", "password", "apiKey")) {
            String secret = props.get(key);
            if (secret != null && !secret.isEmpty()) {
                redacted = redacted.replace(secret, "******");
            }
        }
        return redacted;
    }
}
