package com.dataintegration.console.engine.zeta;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Connection settings for a Zeta cluster's REST API v2.
 *
 * @param masterUrls base URLs including any context-path, e.g. {@code http://st-master-1:8080};
 *     tried in order until one answers
 * @param username Basic auth user, null when basic auth is disabled on the cluster
 * @param password Basic auth password
 */
public record ZetaClusterConfig(
        List<String> masterUrls,
        String username,
        String password,
        Duration connectTimeout,
        Duration requestTimeout) {

    public ZetaClusterConfig {
        Objects.requireNonNull(masterUrls, "masterUrls");
        if (masterUrls.isEmpty()) {
            throw new IllegalArgumentException("at least one master url is required");
        }
        masterUrls = masterUrls.stream().map(ZetaClusterConfig::stripTrailingSlash).toList();
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
    }

    public boolean basicAuthEnabled() {
        return username != null && !username.isBlank();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Override
    public String toString() {
        return "ZetaClusterConfig[masterUrls=" + masterUrls + ", username=" + username
                + ", password=<redacted>]";
    }
}
