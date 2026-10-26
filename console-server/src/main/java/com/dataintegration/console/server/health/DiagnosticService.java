package com.dataintegration.console.server.health;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.engine.EngineJobInfo;
import com.dataintegration.console.engine.SubmitJobRequest;
import com.dataintegration.console.server.datasource.DatasourceService;
import com.dataintegration.console.server.health.ClusterHealthService.Check;
import com.dataintegration.console.server.health.ClusterHealthService.Status;
import com.dataintegration.console.server.job.HoconTemplate;
import com.dataintegration.console.server.persistence.entity.DatasourceEntity;
import com.dataintegration.console.server.persistence.mapper.DatasourceMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runs tiny real jobs on the cluster to check what the REST API cannot show (docs/design.md §4.3):
 *
 * <ul>
 *   <li>a FakeSource -> Console job whose per-job log must be readable afterwards, which needs
 *       {@code routingAppender} in log4j2.properties on the workers
 *   <li>one {@code select 1} Jdbc job per MySQL / PostgreSQL datasource, which needs the JDBC
 *       driver in lib/ on the workers and network access from them to the database
 * </ul>
 *
 * The jobs are submitted together and polled until they end or the timeout passes.
 */
@Service
public class DiagnosticService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticService.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final Duration POLL = Duration.ofSeconds(2);

    private static final String LOG_JOB = """
            env { job.mode = "BATCH", parallelism = 1 }
            source { FakeSource { row.num = 3, schema = { fields { id = "int" } }, plugin_output = "fake" } }
            sink { Console { plugin_input = ["fake"] } }
            """;

    private static final String JDBC_JOB = """
            env { job.mode = "BATCH", parallelism = 1 }
            source { Jdbc { datasource_id = "%s", query = "select 1 as ok", plugin_output = "probe" } }
            sink { Console { plugin_input = ["probe"] } }
            """;

    private final EngineClient engineClient;
    private final DatasourceService datasourceService;
    private final DatasourceMapper datasourceMapper;
    private final StreamProbe streamProbe;

    public DiagnosticService(EngineClient engineClient, DatasourceService datasourceService, DatasourceMapper datasourceMapper,
            StreamProbe streamProbe) {
        this.streamProbe = streamProbe;
        this.engineClient = engineClient;
        this.datasourceService = datasourceService;
        this.datasourceMapper = datasourceMapper;
    }

    private interface Verdict {
        Check judge(EngineJobInfo info);
    }

    private record Probe(String name, long jobId, Verdict verdict) {}

    public List<Check> run() {
        List<Check> results = new ArrayList<>();
        List<Probe> probes = new ArrayList<>();

        submit("作业日志", LOG_JOB, this::judgeLogs, probes, results);
        for (DatasourceEntity ds : datasourceMapper.selectList(new LambdaQueryWrapper<DatasourceEntity>()
                .in(DatasourceEntity::getType, jdbcDriverTypes())
                .orderByAsc(DatasourceEntity::getName))) {
            String config;
            try {
                config = HoconTemplate.parse(JDBC_JOB.formatted(ds.getId()))
                        .render((connector, id) -> datasourceService.connectorOptions(id, connector, false));
            } catch (ConsoleException e) {
                results.add(new Check("JDBC 驱动", ds.getName(), Status.ERROR, "无法生成诊断作业：" + e.getMessage()));
                continue;
            }
            String name = ds.getName() + "（" + ds.getType() + "）";
            submit("JDBC " + name, config,
                    info -> info.status().name().equals("FINISHED")
                            ? new Check("JDBC 驱动", name, Status.OK, "worker 能加载驱动并连上数据库")
                            : new Check("JDBC 驱动", name, Status.ERROR, "作业 " + info.status() + "：" + info.errorMessage()
                                    + "。检查每个节点 lib/ 下是否有对应的 JDBC 驱动，以及 worker 能否访问数据库"),
                    probes, results);
        }

        // runs while the batch probes do; it takes a few checkpoints and a stop and start
        results.add(streamProbe.run());

        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        List<Probe> pending = new ArrayList<>(probes);
        while (!pending.isEmpty() && System.currentTimeMillis() < deadline) {
            sleep();
            for (Probe probe : List.copyOf(pending)) {
                Optional<EngineJobInfo> info;
                try {
                    info = engineClient.jobInfo(probe.jobId());
                } catch (ConsoleException e) {
                    continue;
                }
                if (info.isPresent() && info.get().status().isTerminal()) {
                    results.add(probe.verdict().judge(info.get()));
                    pending.remove(probe);
                }
            }
        }
        for (Probe probe : pending) {
            results.add(new Check("诊断作业", probe.name(), Status.WARN,
                    "超过 " + TIMEOUT.toSeconds() + " 秒还没结束（引擎作业 " + probe.jobId() + "），集群可能资源不足或在排队"));
            try {
                engineClient.stop(probe.jobId(), false, false);
            } catch (ConsoleException e) {
                log.warn("Could not stop diagnostic job {}: {}", probe.jobId(), e.getMessage());
            }
        }
        return results;
    }

    /** SeaTunnel rejects a job whose source cannot be set up (e.g. no driver) at submission, with HTTP 500. */
    private void submit(String name, String config, Verdict verdict, List<Probe> probes, List<Check> results) {
        try {
            long jobId = engineClient.submit(SubmitJobRequest.fresh("console-diagnostic: " + name, config));
            probes.add(new Probe(name, jobId, verdict));
        } catch (ConsoleException e) {
            String group = name.startsWith("JDBC") ? "JDBC 驱动" : "诊断作业";
            results.add(new Check(group, name.replaceFirst("^JDBC ", ""), Status.ERROR,
                    "提交被拒绝：" + e.getMessage() + "。根因在 master 的 logs/seatunnel-engine-server.log；常见原因是缺少驱动或连接器"));
        }
    }

    private Check judgeLogs(EngineJobInfo info) {
        if (!info.status().name().equals("FINISHED")) {
            return new Check("作业日志", "FakeSource → Console", Status.ERROR,
                    "诊断作业 " + info.status() + "：" + info.errorMessage() + "（需要 FakeSource 和 Console 连接器）");
        }
        try {
            return engineClient.listLogs(info.jobId()).isEmpty()
                    ? new Check("作业日志", "按作业拆分日志", Status.ERROR,
                            "作业成功但拿不到它的日志：每个节点的 config/log4j2.properties 里要把 rootLogger.appenderRef.file.ref 改为 routingAppender，否则运行详情页看不到 SeaTunnel 日志")
                    : new Check("作业日志", "按作业拆分日志", Status.OK, "已开启，运行详情页能看到 SeaTunnel 作业日志");
        } catch (ConsoleException e) {
            return new Check("作业日志", "按作业拆分日志", Status.WARN, "无法读取日志列表：" + e.getMessage());
        }
    }

    /** Types whose JDBC driver the workers must have, e.g. MYSQL and POSTGRESQL. */
    private List<String> jdbcDriverTypes() {
        return datasourceService.types().stream().filter(t -> t.capabilities().jdbcDriver())
                .map(DatasourceService.TypeView::type).toList();
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConsoleException(ErrorCodes.INTERNAL_ERROR, "diagnostics interrupted");
        }
    }
}
