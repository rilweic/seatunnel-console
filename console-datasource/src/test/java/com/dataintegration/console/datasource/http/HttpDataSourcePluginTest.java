package com.dataintegration.console.datasource.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.datasource.ConnectionTestResult;
import com.dataintegration.console.datasource.CountRequest;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class HttpDataSourcePluginTest {

    private final HttpDataSourcePlugin plugin = new HttpDataSourcePlugin();
    private final AtomicReference<ObservedRequest> observed = new AtomicReference<>();

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            observed.set(new ObservedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders(),
                    new String(requestBody, StandardCharsets.UTF_8)));
            switch (exchange.getRequestURI().getPath()) {
                case "/unauthorized" -> respond(exchange, 401, "denied");
                case "/error" -> respond(exchange, 500, "failed secret-token " + "x".repeat(250));
                case "/redirect" -> {
                    exchange.getResponseHeaders().add("Location", "/none");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                }
                case "/large" -> respond(exchange, 200, "x".repeat(2 * 1024 * 1024 + 1));
                case "/post" -> respond(exchange, 201, new String(requestBody, StandardCharsets.UTF_8));
                case "/echo-authorization" -> respond(exchange, 200,
                        exchange.getRequestHeaders().getFirst("Authorization"));
                default -> respond(exchange, 200, "{\"ok\":true}");
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void noneAndBearerAuthentication() {
        ConnectionTestResult none = plugin.testConnection(props("NONE"));
        assertThat(none.success()).isTrue();
        assertThat(observed.get().headers().getFirst("Authorization")).isNull();

        Map<String, String> bearer = props("BEARER");
        bearer.put("token", "secret-token");
        bearer.put("testPath", "/bearer");
        assertThat(plugin.testConnection(bearer).success()).isTrue();
        assertThat(observed.get().headers().getFirst("Authorization")).isEqualTo("Bearer secret-token");
        assertThat(plugin.secretKeys()).containsExactlyInAnyOrder("token", "password", "apiKey");
    }

    @Test
    void basicAuthentication() {
        Map<String, String> props = props("BASIC");
        props.put("username", "user");
        props.put("password", "pass");

        assertThat(plugin.testConnection(props).success()).isTrue();
        assertThat(observed.get().headers().getFirst("Authorization")).isEqualTo("Basic dXNlcjpwYXNz");
    }

    @Test
    void headerApiKeyAuthentication() {
        Map<String, String> props = props("API_KEY");
        props.put("apiKeyName", "X-Access-Key");
        props.put("apiKey", "header-secret");
        props.put("headers", "{\"X-Tenant\":\"acme\"}");

        assertThat(plugin.testConnection(props).success()).isTrue();
        assertThat(observed.get().headers().getFirst("X-Access-Key")).isEqualTo("header-secret");
        assertThat(observed.get().headers().getFirst("X-Tenant")).isEqualTo("acme");
    }

    @Test
    void queryApiKeyCannotBeOverriddenAndEmptyCursorIsOmitted() {
        Map<String, String> props = props("API_KEY");
        props.put("apiKeyName", "appid");
        props.put("apiKeyLocation", "QUERY");
        props.put("apiKey", "query-secret");

        plugin.preview(props, new HttpPreviewRequest("/query-key", "GET", Map.of("page", "1"), null));
        assertThat(observed.get().query()).contains("appid=query-secret", "page=1");

        Map<String, String> taskParams = new HashMap<>();
        taskParams.put("next", "${cursor}");
        taskParams.put("empty", "");
        taskParams.put("q", "a b");
        plugin.preview(props, new HttpPreviewRequest("/query-key", "GET", taskParams, null));

        assertThat(observed.get().query())
                .contains("appid=query-secret", "q=a+b")
                .doesNotContain("next=", "empty=");
        assertThat(observed.get().headers().getFirst("appid")).isNull();
        assertThatThrownBy(() -> plugin.preview(
                props, new HttpPreviewRequest("/query-key", "GET", Map.of("appid", "task-value"), null)))
                .isInstanceOf(ConsoleException.class)
                .hasMessageContaining("conflicts with datasource API Key");
    }

    @Test
    void postSendsJsonBodyAndReturnsStatusElapsedAndBody() {
        HttpPreviewResponse response = plugin.execute(
                props("NONE"),
                new HttpPreviewRequest("/post", "post", Map.of(), "{\"name\":\"alice\"}"));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.elapsedMillis()).isNotNegative();
        assertThat(response.body()).isEqualTo("{\"name\":\"alice\"}");
        assertThat(observed.get().method()).isEqualTo("POST");
        assertThat(observed.get().headers().getFirst("Content-Type")).startsWith("application/json");
    }

    @Test
    void errorsAreUsefulWithoutLeakingCredentials() {
        Map<String, String> props = props("BEARER");
        props.put("token", "secret-token");
        props.put("testPath", "/unauthorized");
        ConnectionTestResult unauthorized = plugin.testConnection(props);
        assertThat(unauthorized.success()).isFalse();
        assertThat(unauthorized.message()).contains("authentication failed", "401");

        props.put("testPath", "/error");
        ConnectionTestResult error = plugin.testConnection(props);
        assertThat(error.success()).isFalse();
        assertThat(error.message())
                .contains("HTTP 500", "******")
                .doesNotContain("secret-token")
                .hasSizeLessThan(240);
    }

    @Test
    void redirectsAreNotFollowedAndResponsesAreLimitedToTwoMegabytes() {
        HttpPreviewResponse redirect = plugin.preview(
                props("NONE"), new HttpPreviewRequest("/redirect", "GET", Map.of(), null));
        assertThat(redirect.status()).isEqualTo(302);

        assertThatThrownBy(() -> plugin.preview(
                        props("NONE"), new HttpPreviewRequest("/large", "GET", Map.of(), null)))
                .isInstanceOf(ConsoleException.class)
                .hasMessageContaining("2 MB");
    }

    @Test
    void baseUrlRejectsUserinfoQueryAndFragment() {
        // credentials and ordinary parameters alike: a query would swallow the job's path
        for (String suffix : new String[] {"?token=secret", "?%74oken=secret", "?tenant=acme", "?", "#top"}) {
            Map<String, String> props = props("NONE");
            props.put("baseUrl", baseUrl + suffix);
            assertThatThrownBy(() -> plugin.validate(props))
                    .isInstanceOf(ConsoleException.class)
                    .hasMessageContaining("query or fragment");
        }
        Map<String, String> userinfo = props("NONE");
        userinfo.put("baseUrl", baseUrl.replace("http://", "http://user:pass@"));
        assertThatThrownBy(() -> plugin.validate(userinfo))
                .isInstanceOf(ConsoleException.class)
                .hasMessageContaining("userinfo");

        Map<String, String> withPath = props("NONE");
        withPath.put("baseUrl", baseUrl + "/api/v1");
        plugin.validate(withPath);
    }

    @Test
    void previewRedactsBearerAndBasicAuthorizationEchoes() {
        Map<String, String> bearer = props("BEARER");
        bearer.put("token", "secret-token");
        HttpPreviewResponse bearerResponse = plugin.preview(
                bearer, new HttpPreviewRequest("/echo-authorization", "GET", Map.of(), null));
        assertThat(bearerResponse.body()).isEqualTo("******")
                .doesNotContain("secret-token", "Bearer");

        Map<String, String> basic = props("BASIC");
        basic.put("username", "user");
        basic.put("password", "pass");
        HttpPreviewResponse basicResponse = plugin.preview(
                basic, new HttpPreviewRequest("/echo-authorization", "GET", Map.of(), null));
        assertThat(basicResponse.body()).isEqualTo("******")
                .doesNotContain("dXNlcjpwYXNz", "Basic");
    }

    @Test
    void absolutePreviewUrlIsRejected() {
        assertThatThrownBy(() -> plugin.preview(
                props("NONE"), new HttpPreviewRequest("https://other.example.com/orders", "GET", Map.of(), null)))
                .isInstanceOf(ConsoleException.class)
                .hasMessageContaining("relative");
    }

    @Test
    void sensitiveCommonHeadersAreRejectedCaseInsensitively() {
        for (String name : new String[] {
                "Authorization", "cookie", "X-API-Key", "monkey", "X-Token-Source", "X-App-Secret", "X-Password"}) {
            Map<String, String> props = props("NONE");
            props.put("headers", "{\"" + name + "\":\"credential-value\"}");
            assertThatThrownBy(() -> plugin.validate(props))
                    .isInstanceOf(ConsoleException.class)
                    .hasMessageContaining("sensitive header")
                    .hasMessageNotContaining("credential-value");
        }
    }

    @Test
    void mapsOnlyToHttpConnectorOptions() {
        Map<String, String> props = props("API_KEY");
        props.put("apiKeyName", "X-Access-Key");
        props.put("apiKey", "secret");
        props.put("headers", "{\"Accept\":\"application/json\"}");
        props.put("connectTimeoutMs", "1234");
        props.put("socketTimeoutMs", "5678");

        Map<String, Object> options = plugin.toConnectorOptions("Http", props);
        assertThat(options)
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "url", baseUrl,
                        "headers", Map.of("Accept", "application/json", "X-Access-Key", "secret"),
                        "params", Map.of(),
                        "connect_timeout_ms", 1234,
                        "socket_timeout_ms", 5678));
        assertThatThrownBy(() -> plugin.toConnectorOptions("Jdbc", props))
                .isInstanceOf(ConsoleException.class)
                .hasMessageContaining("cannot be used with connector Jdbc");
    }

    @Test
    void metadataAndCountAreExplicitlyUnsupported() {
        Map<String, String> props = props("NONE");
        assertThatThrownBy(() -> plugin.count(props, new CountRequest(null, null, null, Duration.ofSeconds(1))))
                .isInstanceOf(ConsoleException.class)
                .hasMessageContaining("does not support count");
        assertThatThrownBy(() -> plugin.namespaces(props)).isInstanceOf(ConsoleException.class);
        assertThatThrownBy(() -> plugin.tables(props, null)).isInstanceOf(ConsoleException.class);
        assertThatThrownBy(() -> plugin.columns(props, null, "items")).isInstanceOf(ConsoleException.class);
    }

    private Map<String, String> props(String authType) {
        Map<String, String> props = new HashMap<>();
        props.put("baseUrl", baseUrl);
        props.put("authType", authType);
        return props;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record ObservedRequest(String method, String path, String query, Headers headers, String body) {}
}
