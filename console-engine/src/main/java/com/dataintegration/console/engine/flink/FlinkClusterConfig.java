package com.dataintegration.console.engine.flink;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Connection and submission settings for a Flink Standalone or Session cluster.
 *
 * @param restUrl JobManager REST base URL, e.g. {@code http://localhost:8082}
 * @param flinkHome local Flink client directory (same version as the cluster; not started)
 * @param seatunnelHome local SeaTunnel distribution directory (with connectors and JDBC drivers)
 * @param flinkVersion cluster Flink version, e.g. {@code 1.20.1} (1.19 is unsupported by SeaTunnel)
 * @param javaHome optional JAVA_HOME override for the Flink CLI process
 */
public record FlinkClusterConfig(
        String restUrl,
        String flinkHome,
        String seatunnelHome,
        String flinkVersion,
        String javaHome,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration submitTimeout,
        int maxConcurrentSubmits,
        Path tempDir) {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration DEFAULT_SUBMIT_TIMEOUT = Duration.ofSeconds(120);
    public static final int DEFAULT_MAX_CONCURRENT_SUBMITS = 4;

    public FlinkClusterConfig {
        Objects.requireNonNull(restUrl, "restUrl");
        restUrl = stripTrailingSlash(restUrl.trim());
        if (restUrl.isEmpty()) {
            throw new IllegalArgumentException("restUrl is required");
        }
        flinkHome = trimOrNull(flinkHome);
        seatunnelHome = trimOrNull(seatunnelHome);
        flinkVersion = trimOrNull(flinkVersion);
        javaHome = trimOrNull(javaHome);
        connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
        submitTimeout = submitTimeout == null ? DEFAULT_SUBMIT_TIMEOUT : submitTimeout;
        maxConcurrentSubmits = maxConcurrentSubmits <= 0 ? DEFAULT_MAX_CONCURRENT_SUBMITS : maxConcurrentSubmits;
    }

    public FlinkClusterConfig(String restUrl, String flinkHome, String seatunnelHome, String flinkVersion, String javaHome) {
        this(
                restUrl,
                flinkHome,
                seatunnelHome,
                flinkVersion,
                javaHome,
                DEFAULT_CONNECT_TIMEOUT,
                DEFAULT_REQUEST_TIMEOUT,
                DEFAULT_SUBMIT_TIMEOUT,
                DEFAULT_MAX_CONCURRENT_SUBMITS,
                null);
    }

    /** Host:port form passed to {@code flink run -m}. */
    public String jobManagerAddress() {
        String stripped = restUrl.replaceFirst("^https?://", "");
        int slash = stripped.indexOf('/');
        return slash < 0 ? stripped : stripped.substring(0, slash);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
