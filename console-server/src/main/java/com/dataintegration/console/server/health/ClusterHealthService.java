package com.dataintegration.console.server.health;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.engine.EngineWorker;
import com.dataintegration.console.engine.MasterStatus;
import com.dataintegration.console.server.job.HoconTemplate;
import com.dataintegration.console.server.job.ScheduleService;
import com.dataintegration.console.server.job.XxlJobReconciler;
import com.dataintegration.console.server.job.Variables;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.persistence.entity.JobVersion;
import com.dataintegration.console.server.persistence.mapper.JobDefinitionMapper;
import com.dataintegration.console.server.persistence.mapper.JobVersionMapper;
import com.dataintegration.console.server.scheduler.XxlJobProperties;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Quick health checks for the cluster health page (docs/design.md §4.3): what can be told from the
 * REST API and the console itself, without running anything. What only a running job can show
 * (per-job logs, JDBC drivers on the workers) is left to {@link DiagnosticService}.
 */
@Service
public class ClusterHealthService {

    public enum Status {
        OK,
        WARN,
        ERROR
    }

    public record Check(String group, String name, Status status, String message) {}

    public record Health(List<Check> checks, List<MasterStatus> masters, List<EngineWorker> workers, Map<String, String> overview) {}

    /** Connectors the wizard generates, always required. */
    private static final List<String> WIZARD_CONNECTORS = List.of("source/Jdbc", "sink/Jdbc", "sink/Elasticsearch");

    private final EngineClient engineClient;
    private final JobDefinitionMapper jobMapper;
    private final JobVersionMapper versionMapper;
    private final ObjectProvider<XxlJobProperties> xxlJobProperties;
    private final Path dataDir;
    private final XxlJobReconciler reconciler;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).proxy(HttpClient.Builder.NO_PROXY).build();

    public ClusterHealthService(
            EngineClient engineClient,
            JobDefinitionMapper jobMapper,
            JobVersionMapper versionMapper,
            ObjectProvider<XxlJobProperties> xxlJobProperties,
            @Value("${console.data-dir:./data}") String dataDir,
            XxlJobReconciler reconciler) {
        this.engineClient = engineClient;
        this.jobMapper = jobMapper;
        this.versionMapper = versionMapper;
        this.xxlJobProperties = xxlJobProperties;
        this.dataDir = Path.of(dataDir).toAbsolutePath().normalize();
        this.reconciler = reconciler;
    }

    public Health check() {
        List<Check> checks = new ArrayList<>();
        List<MasterStatus> masters = engineClient.probeMasters();
        boolean anyMaster = false;
        for (MasterStatus m : masters) {
            if (!m.reachable() || m.error() != null) {
                checks.add(new Check("SeaTunnel", "Master " + m.url(), Status.ERROR,
                        m.reachable() ? m.error() : "连不上：" + m.error() + "。确认集群以 cluster 模式运行，并开启了 http（seatunnel.yaml 的 enable-http）"));
                continue;
            }
            anyMaster = true;
            checks.add(new Check("SeaTunnel", "Master " + m.url(), Status.OK, "可连通，响应 " + m.latencyMillis() + " ms"));
            if (Boolean.FALSE.equals(m.authRequired())) {
                checks.add(new Check("SeaTunnel", "认证 " + m.url(), Status.WARN,
                        "没有开启 Basic 认证，任何能访问这个端口的人都能提交和停止作业（seatunnel.yaml 的 enable-basic-auth）"));
            } else if (Boolean.TRUE.equals(m.authRequired())) {
                checks.add(new Check("SeaTunnel", "认证 " + m.url(), Status.OK, "已开启 Basic 认证"));
            }
            if (m.version() == null || !m.version().startsWith("3.0")) {
                checks.add(new Check("SeaTunnel", "版本 " + m.url(), Status.WARN,
                        "集群版本是 " + m.version() + "，console 按 3.0.x 开发和验证，其他版本的 REST 行为可能不同"));
            } else {
                checks.add(new Check("SeaTunnel", "版本 " + m.url(), Status.OK, m.version()));
            }
        }
        if (!anyMaster) {
            checks.add(new Check("SeaTunnel", "其余检查", Status.WARN, "没有可用的 master，跳过节点和连接器检查"));
            addConsoleChecks(checks);
            return new Health(checks, masters, List.of(), Map.of());
        }

        Map<String, String> overview = engineClient.overview();
        List<EngineWorker> workers = engineClient.workers();
        if (workers.isEmpty()) {
            checks.add(new Check("SeaTunnel", "Worker 节点", Status.ERROR, "集群没有可用的 worker 节点，作业无法运行"));
        } else {
            checks.add(new Check("SeaTunnel", "Worker 节点", Status.OK, workers.size() + " 个"));
        }

        Map<String, Set<String>> usage = connectorUsage();
        for (Map.Entry<String, Set<String>> e : usage.entrySet()) {
            String[] parts = e.getKey().split("/", 2);
            String label = (parts[0].equals("source") ? "Source " : "Sink ") + parts[1];
            String users = e.getValue().isEmpty() ? "向导会用到" : "被任务使用：" + String.join("、", e.getValue());
            try {
                checks.add(engineClient.hasConnector(parts[0], parts[1])
                        ? new Check("连接器", label, Status.OK, "已安装（" + users + "）")
                        : new Check("连接器", label, Status.ERROR, "集群缺少这个连接器（" + users + "），把它的 jar 放进每个节点的 connectors/ 目录"));
            } catch (ConsoleException ex) {
                checks.add(new Check("连接器", label, Status.WARN, "无法确认：" + ex.getMessage()));
            }
        }
        checks.add(new Check("连接器", "说明", Status.OK,
                "只能检查响应的那个 master；其他节点是否装齐、JDBC 驱动是否在 lib/ 下，请点\"运行诊断\"用真实作业确认"));
        addConsoleChecks(checks);
        return new Health(checks, masters, workers, overview);
    }

    /**
     * Connector -> names of live jobs whose current version uses it, plus the wizard's connectors.
     * A version that cannot be parsed is skipped; its own runs will report the problem.
     */
    private Map<String, Set<String>> connectorUsage() {
        Map<String, Set<String>> usage = new LinkedHashMap<>();
        WIZARD_CONNECTORS.forEach(c -> usage.put(c, new LinkedHashSet<>()));
        List<JobDefinition> jobs = jobMapper.selectList(new LambdaQueryWrapper<JobDefinition>().isNotNull(JobDefinition::getCurrentVersionId));
        if (jobs.isEmpty()) {
            return usage;
        }
        Map<String, JobVersion> versions = versionMapper
                .selectList(new LambdaQueryWrapper<JobVersion>()
                        .in(JobVersion::getId, jobs.stream().map(JobDefinition::getCurrentVersionId).toList()))
                .stream()
                .collect(Collectors.toMap(JobVersion::getId, v -> v));
        LocalDateTime now = LocalDateTime.now();
        for (JobDefinition job : jobs) {
            JobVersion version = versions.get(job.getCurrentVersionId());
            if (version == null) {
                continue;
            }
            try {
                HoconTemplate template = HoconTemplate.parse(Variables.render(version.getHoconTemplate(), Map.of(), now));
                for (String section : List.of("source", "sink")) {
                    for (HoconTemplate.Block block : template.blocks(section)) {
                        usage.computeIfAbsent(section + "/" + block.connector(), k -> new LinkedHashSet<>()).add(job.getName());
                    }
                }
            } catch (ConsoleException e) {
                // reported by the job's own runs
            }
        }
        return usage;
    }

    private void addConsoleChecks(List<Check> checks) {
        XxlJobProperties xxl = xxlJobProperties.getIfAvailable();
        if (xxl == null || !xxl.enabled()) {
            checks.add(new Check("Console", "XXL-JOB", Status.WARN, "未启用（console.xxl-job.enabled=false），定时调度和补数不可用，手动运行在本机线程池执行"));
        } else {
            for (String address : xxl.adminAddresses()) {
                checks.add(reachable(address)
                        ? new Check("Console", "XXL-JOB " + address, Status.OK, "admin 可访问")
                        : new Check("Console", "XXL-JOB " + address, Status.ERROR, "admin 连不上，定时调度、手动运行和补数都会失败"));
            }
            checks.add(reconciliationCheck());
        }
        boolean writable;
        try {
            Files.createDirectories(dataDir);
            writable = Files.isWritable(dataDir);
        } catch (Exception e) {
            writable = false;
        }
        checks.add(writable
                ? new Check("Console", "数据目录", Status.OK, dataDir + " 可写（作业日志归档在这里）")
                : new Check("Console", "数据目录", Status.ERROR, dataDir + " 不可写，作业日志无法归档（console.data-dir）"));
    }

    private Check reconciliationCheck() {
        XxlJobReconciler.Round round = reconciler.lastRound();
        if (round == null) {
            return new Check("Console", "XXL-JOB 对账", Status.OK, "console 启动后约 2 分钟执行第一次，之后每 10 分钟一次");
        }
        String when = round.finishedAt().toString().replace('T', ' ').substring(0, 19);
        if (round.error() != null) {
            return new Check("Console", "XXL-JOB 对账", Status.WARN, when + " 中断：" + round.error());
        }
        if (!round.failures().isEmpty()) {
            return new Check("Console", "XXL-JOB 对账", Status.WARN,
                    when + " 有任务同步失败（到任务的调度页查看原因）：" + String.join("、", round.failures()));
        }
        int recreated = round.counts().getOrDefault(ScheduleService.Reconciled.RECREATED, 0);
        int forgotten = round.counts().getOrDefault(ScheduleService.Reconciled.FORGOTTEN, 0);
        return new Check("Console", "XXL-JOB 对账", Status.OK, when + " 完成"
                + (recreated + forgotten + round.removedOrphans() == 0
                        ? "，两边一致"
                        : "，修正：重建 " + recreated + " 个、清除失效引用 " + forgotten + " 个、删除残留 " + round.removedOrphans() + " 个"));
    }

    private boolean reachable(String address) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(address)).timeout(Duration.ofSeconds(3)).GET().build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}
