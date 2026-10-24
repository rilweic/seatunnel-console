package com.dataintegration.console.server.config;

import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.engine.zeta.ZetaClusterConfig;
import com.dataintegration.console.engine.zeta.ZetaRestClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class EngineConfig {

    @Bean
    public EngineClient engineClient(ConsoleProperties properties) {
        ConsoleProperties.Zeta zeta = properties.zeta();
        return new ZetaRestClient(
                new ZetaClusterConfig(
                        zeta.masterUrls(),
                        zeta.username(),
                        zeta.password(),
                        zeta.connectTimeout(),
                        zeta.requestTimeout()));
    }
}
