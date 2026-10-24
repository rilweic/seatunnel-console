package com.dataintegration.console.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** Settings under the {@code console.*} prefix in application.yml. */
@ConfigurationProperties(prefix = "console")
public record ConsoleProperties(Zeta zeta) {

    /**
     * SeaTunnel Zeta cluster REST API v2.
     *
     * @param masterUrls tried in order, e.g. http://localhost:8080
     */
    public record Zeta(
            List<String> masterUrls,
            String username,
            String password,
            Duration connectTimeout,
            Duration requestTimeout) {

        @Override
        public String toString() {
            return "Zeta[masterUrls=" + masterUrls + ", username=" + username + ", password=<redacted>]";
        }
    }
}
