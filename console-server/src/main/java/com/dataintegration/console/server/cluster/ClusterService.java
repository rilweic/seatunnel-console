package com.dataintegration.console.server.cluster;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.engine.EngineType;
import com.dataintegration.console.engine.MasterStatus;
import com.dataintegration.console.server.auth.Access;
import com.dataintegration.console.server.auth.CurrentUser;
import com.dataintegration.console.server.config.ConsoleProperties;
import com.dataintegration.console.server.crypto.SecretCipher;
import com.dataintegration.console.server.persistence.entity.EngineCluster;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.persistence.mapper.EngineClusterMapper;
import com.dataintegration.console.server.persistence.mapper.JobDefinitionMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages registered Zeta and Flink clusters (docs/flink-spark.md §5.3). Everyone can read;
 * only administrators may create, edit, delete or change the default cluster.
 */
@Service
public class ClusterService {

    private static final Logger log = LoggerFactory.getLogger(ClusterService.class);

    public record ClusterView(
            String id,
            String name,
            String engineType,
            String description,
            String restUrls,
            String username,
            boolean hasPassword,
            String flinkVersion,
            String flinkHome,
            String seatunnelHome,
            String javaHome,
            boolean isDefault,
            long jobCount,
            String createdAt,
            String updatedAt) {}

    public record ClusterRequest(
            String name,
            String engineType,
            String description,
            String restUrls,
            String username,
            String password,
            String flinkVersion,
            String flinkHome,
            String seatunnelHome,
            String javaHome,
            Boolean isDefault) {}

    public record TestResult(
            boolean ok,
            String version,
            long latencyMillis,
            List<MasterStatus> masters,
            List<String> warnings,
            String error) {}

    private final EngineClusterMapper clusterMapper;
    private final JobDefinitionMapper jobMapper;
    private final SecretCipher cipher;
    private final EngineRegistry registry;
    private final ConsoleProperties properties;

    public ClusterService(
            EngineClusterMapper clusterMapper,
            JobDefinitionMapper jobMapper,
            SecretCipher cipher,
            EngineRegistry registry,
            ConsoleProperties properties) {
        this.clusterMapper = clusterMapper;
        this.jobMapper = jobMapper;
        this.cipher = cipher;
        this.registry = registry;
        this.properties = properties;
    }

    /**
     * Seeds the default Zeta cluster from environment properties when upgrading from P2, and
     * backfills any legacy jobs with {@code cluster_id IS NULL} to their original Zeta cluster so
     * changing the default cluster later never moves existing jobs.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedDefaultClusterIfEmpty() {
        String zetaClusterId = null;
        if (clusterMapper.selectCount(null) == 0) {
            ConsoleProperties.Zeta zeta = properties.zeta();
            if (zeta == null || zeta.masterUrls() == null || zeta.masterUrls().isEmpty()) {
                return;
            }
            EngineCluster c = new EngineCluster();
            c.setName("默认 Zeta 集群");
            c.setEngineType(EngineType.ZETA.name());
            c.setDescription("启动时由环境变量自动导入");
            c.setRestUrls(String.join(",", zeta.masterUrls()));
            c.setUsername(zeta.username());
            if (zeta.password() != null && !zeta.password().isBlank()) {
                c.setPasswordEnc(cipher.encrypt(zeta.password()));
            }
            c.setIsDefault(true);
            clusterMapper.insert(c);
            zetaClusterId = c.getId();
            log.info("Seeded default Zeta cluster {} from console.zeta properties", c.getId());
        } else {
            EngineCluster oldestZeta = clusterMapper.selectOne(new LambdaQueryWrapper<EngineCluster>()
                    .eq(EngineCluster::getEngineType, EngineType.ZETA.name())
                    .orderByAsc(EngineCluster::getCreatedAt)
                    .last("LIMIT 1"));
            if (oldestZeta != null) {
                zetaClusterId = oldestZeta.getId();
            }
        }
        if (zetaClusterId != null) {
            int updated = jobMapper.update(null, new LambdaUpdateWrapper<JobDefinition>()
                    .set(JobDefinition::getClusterId, zetaClusterId)
                    .isNull(JobDefinition::getClusterId));
            if (updated > 0) {
                log.info("Backfilled cluster_id={} for {} legacy job(s)", zetaClusterId, updated);
            }
        }
    }

    public List<ClusterView> list() {
        List<EngineCluster> clusters = clusterMapper.selectList(
                new LambdaQueryWrapper<EngineCluster>()
                        .orderByDesc(EngineCluster::getIsDefault)
                        .orderByAsc(EngineCluster::getCreatedAt));
        Map<String, Long> jobCounts = countJobsByCluster(clusters);
        return clusters.stream().map(c -> toView(c, jobCounts.getOrDefault(c.getId(), 0L))).toList();
    }

    public ClusterView get(String id) {
        EngineCluster c = requireCluster(id);
        long count = countJobsForCluster(c);
        return toView(c, count);
    }

    public EngineCluster requireCluster(String id) {
        EngineCluster c = clusterMapper.selectById(id);
        if (c == null) {
            throw new ConsoleException(ErrorCodes.NOT_FOUND, "集群不存在");
        }
        return c;
    }

    public Map<String, EngineCluster> clustersById() {
        Map<String, EngineCluster> map = new HashMap<>();
        for (EngineCluster c : clusterMapper.selectList(null)) {
            map.put(c.getId(), c);
        }
        return map;
    }

    @Transactional
    public ClusterView create(ClusterRequest req) {
        Access.requireAdmin();
        validate(req, true);
        EngineCluster c = new EngineCluster();
        c.setName(req.name().trim());
        c.setEngineType(req.engineType().trim().toUpperCase());
        c.setDescription(trimOrNull(req.description()));
        c.setRestUrls(normalizeUrls(req.restUrls()));
        c.setUsername(trimOrNull(req.username()));
        if (req.password() != null && !req.password().isBlank()) {
            c.setPasswordEnc(cipher.encrypt(req.password()));
        }
        c.setFlinkVersion(trimOrNull(req.flinkVersion()));
        c.setFlinkHome(trimOrNull(req.flinkHome()));
        c.setSeatunnelHome(trimOrNull(req.seatunnelHome()));
        c.setJavaHome(trimOrNull(req.javaHome()));
        boolean makeDefault = Boolean.TRUE.equals(req.isDefault()) || clusterMapper.selectCount(null) == 0;
        if (makeDefault) {
            clearDefault();
        }
        c.setIsDefault(makeDefault);
        c.setCreatedBy(CurrentUser.idOrNull());
        clusterMapper.insert(c);
        registry.evictAll();
        return toView(c, 0L);
    }

    @Transactional
    public ClusterView update(String id, ClusterRequest req) {
        Access.requireAdmin();
        EngineCluster existing = requireCluster(id);
        validate(req, false);
        // Engine type cannot be changed once created so bound jobs keep matching semantics.
        if (req.engineType() != null && !req.engineType().trim().equalsIgnoreCase(existing.getEngineType())) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "集群创建后不能修改引擎类型");
        }
        EngineCluster patch = new EngineCluster();
        patch.setName(req.name().trim());
        patch.setDescription(trimOrNull(req.description()));
        patch.setRestUrls(normalizeUrls(req.restUrls()));
        patch.setUsername(trimOrNull(req.username()));
        if (req.password() != null && !req.password().isBlank()) {
            patch.setPasswordEnc(cipher.encrypt(req.password()));
        }
        patch.setFlinkVersion(trimOrNull(req.flinkVersion()));
        patch.setFlinkHome(trimOrNull(req.flinkHome()));
        patch.setSeatunnelHome(trimOrNull(req.seatunnelHome()));
        patch.setJavaHome(trimOrNull(req.javaHome()));
        if (Boolean.TRUE.equals(req.isDefault())) {
            clearDefault();
            patch.setIsDefault(true);
        }
        clusterMapper.update(patch, new LambdaUpdateWrapper<EngineCluster>().eq(EngineCluster::getId, id));
        registry.evict(id);
        return get(id);
    }

    @Transactional
    public void setDefault(String id) {
        Access.requireAdmin();
        requireCluster(id);
        clearDefault();
        EngineCluster patch = new EngineCluster();
        patch.setIsDefault(true);
        clusterMapper.update(patch, new LambdaUpdateWrapper<EngineCluster>().eq(EngineCluster::getId, id));
        registry.evictAll();
    }

    @Transactional
    public void delete(String id) {
        Access.requireAdmin();
        EngineCluster existing = requireCluster(id);
        if (Boolean.TRUE.equals(existing.getIsDefault())) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "不能删除默认集群，请先将其他集群设为默认");
        }
        long used = jobMapper.selectCount(new LambdaQueryWrapper<JobDefinition>().eq(JobDefinition::getClusterId, id));
        if (used > 0) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "仍有 " + used + " 个任务绑定在此集群，无法删除");
        }
        clusterMapper.deleteById(id);
        registry.evict(id);
    }

    public TestResult testConnection(ClusterRequest req, String existingClusterId) {
        Access.requireAdmin();
        validate(req, existingClusterId == null);
        EngineCluster probe = new EngineCluster();
        probe.setId("test-probe");
        probe.setEngineType(req.engineType().trim().toUpperCase());
        probe.setRestUrls(normalizeUrls(req.restUrls()));
        probe.setUsername(trimOrNull(req.username()));
        if (req.password() != null && !req.password().isBlank()) {
            probe.setPasswordEnc(cipher.encrypt(req.password()));
        } else if (existingClusterId != null) {
            EngineCluster existing = requireCluster(existingClusterId);
            probe.setPasswordEnc(existing.getPasswordEnc());
        }
        probe.setFlinkVersion(trimOrNull(req.flinkVersion()));
        probe.setFlinkHome(trimOrNull(req.flinkHome()));
        probe.setSeatunnelHome(trimOrNull(req.seatunnelHome()));
        probe.setJavaHome(trimOrNull(req.javaHome()));

        EngineClient client = registry.buildClient(probe);
        List<MasterStatus> masters = client.probeMasters();
        boolean reachable = masters.stream().anyMatch(MasterStatus::reachable);
        String version = masters.stream().filter(MasterStatus::reachable).map(MasterStatus::version)
                .filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
        long latency = masters.stream().filter(MasterStatus::reachable).mapToLong(MasterStatus::latencyMillis).min().orElse(0);
        String error = reachable ? null : masters.stream().map(MasterStatus::error).filter(e -> e != null).findFirst().orElse("集群不可达");
        List<String> warnings = reachable ? client.configWarnings() : List.of();
        if (reachable && EngineType.FLINK.name().equals(probe.getEngineType()) && version != null && version.startsWith("1.19.")) {
            warnings = new java.util.ArrayList<>(warnings);
            warnings.add("检测到 Flink 版本为 " + version + "，SeaTunnel 3.0 不支持 Flink 1.19，请使用 1.20.x 或 1.15–1.18");
        }
        return new TestResult(reachable, version, latency, masters, warnings, error);
    }

    private void validate(ClusterRequest req, boolean requireType) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "集群名称不能为空");
        }
        if (req.restUrls() == null || req.restUrls().isBlank()) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "集群 REST 地址不能为空");
        }
        if (requireType || req.engineType() != null) {
            EngineType type;
            try {
                type = EngineType.valueOf(req.engineType().trim().toUpperCase());
            } catch (Exception e) {
                throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "不支持的引擎类型：" + req.engineType());
            }
            if (type != EngineType.ZETA && type != EngineType.FLINK) {
                throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "当前仅支持 ZETA 和 FLINK 集群");
            }
            if (type == EngineType.FLINK) {
                if (req.flinkVersion() != null && req.flinkVersion().trim().startsWith("1.19.")) {
                    throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "SeaTunnel 3.0 不支持 Flink 1.19，请使用 1.20.x 或 1.15–1.18");
                }
            }
        }
    }

    private void clearDefault() {
        EngineCluster patch = new EngineCluster();
        patch.setIsDefault(false);
        clusterMapper.update(patch, new LambdaUpdateWrapper<EngineCluster>().eq(EngineCluster::getIsDefault, true));
    }

    private Map<String, Long> countJobsByCluster(List<EngineCluster> clusters) {
        Map<String, Long> counts = new HashMap<>();
        String defaultId = clusters.stream().filter(c -> Boolean.TRUE.equals(c.getIsDefault())).map(EngineCluster::getId).findFirst().orElse(null);
        for (JobDefinition job : jobMapper.selectList(new LambdaQueryWrapper<JobDefinition>().select(JobDefinition::getClusterId))) {
            String cid = job.getClusterId() != null ? job.getClusterId() : defaultId;
            if (cid != null) {
                counts.merge(cid, 1L, Long::sum);
            }
        }
        return counts;
    }

    private long countJobsForCluster(EngineCluster cluster) {
        long direct = jobMapper.selectCount(new LambdaQueryWrapper<JobDefinition>().eq(JobDefinition::getClusterId, cluster.getId()));
        if (Boolean.TRUE.equals(cluster.getIsDefault())) {
            direct += jobMapper.selectCount(new LambdaQueryWrapper<JobDefinition>().isNull(JobDefinition::getClusterId));
        }
        return direct;
    }

    private static ClusterView toView(EngineCluster c, long jobCount) {
        return new ClusterView(
                c.getId(),
                c.getName(),
                c.getEngineType(),
                c.getDescription(),
                c.getRestUrls(),
                c.getUsername(),
                c.getPasswordEnc() != null && !c.getPasswordEnc().isBlank(),
                c.getFlinkVersion(),
                c.getFlinkHome(),
                c.getSeatunnelHome(),
                c.getJavaHome(),
                Boolean.TRUE.equals(c.getIsDefault()),
                jobCount,
                c.getCreatedAt() == null ? null : c.getCreatedAt().toString(),
                c.getUpdatedAt() == null ? null : c.getUpdatedAt().toString());
    }

    private static String normalizeUrls(String raw) {
        return String.join(",", EngineRegistry.splitUrls(raw));
    }

    private static String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
