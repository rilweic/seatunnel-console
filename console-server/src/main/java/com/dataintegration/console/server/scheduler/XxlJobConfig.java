package com.dataintegration.console.server.scheduler;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Disabled with {@code console.xxl-job.enabled=false}, e.g. when no xxl-job-admin is available. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "console.xxl-job", name = "enabled", havingValue = "true", matchIfMissing = true)
public class XxlJobConfig {

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor(XxlJobProperties properties) {
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(String.join(",", properties.adminAddresses()));
        executor.setTimeout(properties.adminTimeoutSeconds());
        executor.setEnabled(true);
        executor.setAppname(properties.appname());
        executor.setAccessToken(properties.accessToken());
        executor.setIp(properties.executorIp());
        executor.setPort(properties.executorPort());
        executor.setAddress(properties.executorAddress());
        executor.setLogPath(properties.logPath());
        executor.setLogRetentionDays(properties.logRetentionDays());
        // Only the BEAN handler below is used; never run scripts pushed from the admin UI.
        executor.setGlueEnabled(false);
        return executor;
    }

    @Bean
    public XxlJobAdminClient xxlJobAdminClient(
            XxlJobProperties properties, RestClient.Builder restClientBuilder) {
        return new XxlJobAdminClient(properties, restClientBuilder);
    }
}
