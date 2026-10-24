package com.dataintegration.console.server.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Embedded XXL-JOB executor and admin OpenAPI settings (docs/design.md §5.2). Requires
 * xxl-job-admin 3.5.0 or later.
 *
 * @param adminAddresses xxl-job-admin base URLs, tried in order
 * @param appname executor AppName; the executor group must exist in xxl-job-admin with this name
 * @param accessToken the executor group's AccessToken, used for registration and the OpenAPI
 * @param jobGroupId id of that executor group in xxl-job-admin, needed when creating jobs
 * @param executorPort port the embedded executor listens on for triggers from xxl-job-admin
 * @param executorAddress full callback address registered to admin, overrides ip:port when set
 */
@ConfigurationProperties(prefix = "console.xxl-job")
public record XxlJobProperties(
        @DefaultValue("true") boolean enabled,
        List<String> adminAddresses,
        @DefaultValue("seatunnel-console") String appname,
        String accessToken,
        int jobGroupId,
        @DefaultValue("3") int adminTimeoutSeconds,
        String executorIp,
        @DefaultValue("9999") int executorPort,
        String executorAddress,
        @DefaultValue("./logs/xxl-job") String logPath,
        @DefaultValue("30") int logRetentionDays) {

    @Override
    public String toString() {
        return "XxlJobProperties[enabled=" + enabled + ", adminAddresses=" + adminAddresses
                + ", appname=" + appname + ", jobGroupId=" + jobGroupId + ", accessToken=<redacted>]";
    }
}
