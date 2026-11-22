package com.dataintegration.console.server.cluster;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.engine.EngineType;
import com.dataintegration.console.engine.flink.FlinkClusterConfig;
import com.dataintegration.console.engine.flink.FlinkEngineClient;
import com.dataintegration.console.engine.zeta.ZetaClusterConfig;
import com.dataintegration.console.engine.zeta.ZetaRestClient;
import com.dataintegration.console.server.config.ConsoleProperties;
import com.dataintegration.console.server.crypto.SecretCipher;
import com.dataintegration.console.server.persistence.entity.EngineCluster;
import com.dataintegration.console.server.persistence.mapper.EngineClusterMapper;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Resolves and caches {@link EngineClient} instances for registered clusters (Zeta and Flink).
 * Falls back to the default cluster (or the {@code console.zeta} properties when no row exists
 * yet) when {@code clusterId} is null.
 */
@Component
public class EngineRegistry {

    private static final Logger log = LoggerFactory.getLogger(EngineRegistry.class);

    private record Cached(LocalDateTime updatedAt, EngineClient client) {}

    private final EngineClusterMapper clusterMapper;
    private final SecretCipher cipher;
    private final ConsoleProperties properties;
    private final Path flinkTempDir;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, EngineCluster> clusterCache = new ConcurrentHashMap<>();
    private volatile EngineCluster defaultClusterCache;
    private volatile EngineClient fallbackZeta;

    public EngineRegistry(
            EngineClusterMapper clusterMapper,
            SecretCipher cipher,
            ConsoleProperties properties,
            @Value("${console.data-dir:./data}") String dataDir) {
        this.clusterMapper = clusterMapper;
        this.cipher = cipher;
        this.properties = properties;
        this.flinkTempDir = Path.of(dataDir).toAbsolutePath().normalize().resolve("flink-tmp");
    }

    @PostConstruct
    void cleanStaleTempConfigs() {
        if (!Files.isDirectory(flinkTempDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(flinkTempDir)) {
            files.filter(Files::isRegularFile).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Could not remove stale Flink temp config {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Could not scan Flink temp dir {}: {}", flinkTempDir, e.getMessage());
        }
    }

    public EngineClient defaultClient() {
        return clientFor((String) null);
    }

    public EngineClient clientFor(String clusterId) {
        EngineCluster cluster = resolveCluster(clusterId);
        if (cluster == null) {
            return fallbackZetaClient();
        }
        return clientFor(cluster);
    }

    public EngineClient clientFor(EngineCluster cluster) {
        Cached existing = cache.get(cluster.getId());
        if (existing != null && java.util.Objects.equals(existing.updatedAt(), cluster.getUpdatedAt())) {
            return existing.client();
        }
        EngineClient built = buildClient(cluster);
        cache.put(cluster.getId(), new Cached(cluster.getUpdatedAt(), built));
        return built;
    }

    public EngineType engineType(String clusterId) {
        EngineCluster cluster = resolveCluster(clusterId);
        if (cluster == null) {
            return EngineType.ZETA;
        }
        return EngineType.valueOf(cluster.getEngineType());
    }

    public EngineCluster resolveCluster(String clusterId) {
        if (clusterId != null && !clusterId.isBlank()) {
            EngineCluster cached = clusterCache.get(clusterId);
            if (cached != null) {
                return cached;
            }
            EngineCluster found = clusterMapper.selectById(clusterId);
            if (found == null) {
                throw new ConsoleException(ErrorCodes.NOT_FOUND, "集群不存在：" + clusterId);
            }
            clusterCache.put(clusterId, found);
            return found;
        }
        EngineCluster cachedDefault = defaultClusterCache;
        if (cachedDefault != null) {
            return cachedDefault;
        }
        EngineCluster def = clusterMapper.selectOne(
                new LambdaQueryWrapper<EngineCluster>().eq(EngineCluster::getIsDefault, true).last("LIMIT 1"));
        if (def == null) {
            def = clusterMapper.selectOne(
                    new LambdaQueryWrapper<EngineCluster>().orderByAsc(EngineCluster::getCreatedAt).last("LIMIT 1"));
        }
        if (def != null) {
            defaultClusterCache = def;
            clusterCache.put(def.getId(), def);
        }
        return def;
    }

    public void evict(String clusterId) {
        if (clusterId != null) {
            cache.remove(clusterId);
            clusterCache.remove(clusterId);
        }
        defaultClusterCache = null;
    }

    public void evictAll() {
        cache.clear();
        clusterCache.clear();
        defaultClusterCache = null;
    }

    public EngineClient buildClient(EngineCluster cluster) {
        EngineType type = EngineType.valueOf(cluster.getEngineType());
        if (type == EngineType.ZETA) {
            List<String> urls = splitUrls(cluster.getRestUrls());
            String password = cluster.getPasswordEnc() == null || cluster.getPasswordEnc().isBlank()
                    ? null : cipher.decrypt(cluster.getPasswordEnc());
            ConsoleProperties.Zeta zetaProps = properties.zeta();
            return new ZetaRestClient(new ZetaClusterConfig(
                    urls,
                    cluster.getUsername(),
                    password,
                    zetaProps.connectTimeout(),
                    zetaProps.requestTimeout()));
        }
        if (type == EngineType.FLINK) {
            return new FlinkEngineClient(new FlinkClusterConfig(
                    cluster.getRestUrls(),
                    cluster.getFlinkHome(),
                    cluster.getSeatunnelHome(),
                    cluster.getFlinkVersion(),
                    cluster.getJavaHome(),
                    FlinkClusterConfig.DEFAULT_CONNECT_TIMEOUT,
                    FlinkClusterConfig.DEFAULT_REQUEST_TIMEOUT,
                    FlinkClusterConfig.DEFAULT_SUBMIT_TIMEOUT,
                    FlinkClusterConfig.DEFAULT_MAX_CONCURRENT_SUBMITS,
                    flinkTempDir));
        }
        throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "不支持的引擎类型：" + type);
    }

    public static List<String> splitUrls(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private EngineClient fallbackZetaClient() {
        if (fallbackZeta == null) {
            ConsoleProperties.Zeta zeta = properties.zeta();
            fallbackZeta = new ZetaRestClient(new ZetaClusterConfig(
                    zeta.masterUrls(), zeta.username(), zeta.password(), zeta.connectTimeout(), zeta.requestTimeout()));
        }
        return fallbackZeta;
    }
}
