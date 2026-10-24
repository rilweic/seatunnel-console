package com.dataintegration.console.server.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.server.datasource.DatasourceService;
import com.dataintegration.console.server.job.HoconTemplate;
import com.dataintegration.console.server.job.JobService;
import com.dataintegration.console.server.job.JobService.VersionDetail;
import com.dataintegration.console.server.job.ScheduleService;
import com.dataintegration.console.server.job.ScheduleService.ScheduleView;
import com.dataintegration.console.server.job.wizard.WizardForm;
import com.dataintegration.console.server.persistence.entity.JobInstance;
import com.dataintegration.console.server.persistence.mapper.JobInstanceMapper;
import com.dataintegration.console.server.run.InstanceService.InstanceView;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The job detail page's overview (docs/design.md §10.4): how the job has been running, what it
 * syncs, and the engine metrics of its latest run.
 */
@Service
public class JobOverviewService {

    static final int TREND_RUNS = 30;
    static final Duration STATS_WINDOW = Duration.ofDays(7);

    /**
     * Runs finished in the last 7 days.
     *
     * @param avgDurationMillis average over successful runs only
     */
    public record Stats(int runs, int success, int failed, int canceled, Long avgDurationMillis, Long writeRows) {}

    /** One finished run, oldest first in the trend. */
    public record RunPoint(
            String id,
            String status,
            String triggerType,
            LocalDateTime scheduledTime,
            LocalDateTime startedAt,
            Long durationMillis,
            Long readRows,
            Long writeRows) {}

    /**
     * One end of the data flow.
     *
     * @param location database / schema, index, or a short form of the query
     */
    public record Endpoint(String connector, String datasourceId, String datasourceName, String datasourceType, String location) {}

    /**
     * What the current version syncs; derived from the wizard form, or read from the HOCON for
     * script jobs (best effort: a config that does not parse yields empty lists).
     *
     * @param tables source tables, for wizard jobs
     * @param mode FULL or INCREMENTAL for wizard jobs, null for script jobs
     * @param detail human-readable target handling, e.g. "UPSERT · 保留已有数据"
     */
    public record Flow(List<Endpoint> sources, List<Endpoint> sinks, List<String> tables, String mode, String window, String detail) {}

    /**
     * @param lastRun the newest run in any state
     * @param active runs PENDING or RUNNING right now, newest first
     * @param metricsInstanceId the run {@code metrics} were taken from: the newest one that reported any
     */
    public record Overview(
            Stats stats,
            InstanceView lastRun,
            List<InstanceView> active,
            List<RunPoint> trend,
            String metricsInstanceId,
            EngineMetrics metrics,
            Flow flow,
            ScheduleView schedule) {}

    private static final List<String> FINISHED = List.of(RunStatus.SUCCESS.name(), RunStatus.FAILED.name(), RunStatus.CANCELED.name());
    private static final List<String> ACTIVE = List.of(RunStatus.PENDING.name(), RunStatus.RUNNING.name());

    private final JobInstanceMapper instanceMapper;
    private final InstanceService instanceService;
    private final JobService jobService;
    private final ScheduleService scheduleService;
    private final DatasourceService datasourceService;

    public JobOverviewService(
            JobInstanceMapper instanceMapper,
            InstanceService instanceService,
            JobService jobService,
            ScheduleService scheduleService,
            DatasourceService datasourceService) {
        this.instanceMapper = instanceMapper;
        this.instanceService = instanceService;
        this.jobService = jobService;
        this.scheduleService = scheduleService;
        this.datasourceService = datasourceService;
    }

    public Overview overview(String jobId) {
        VersionDetail current = jobService.get(jobId).currentVersion();

        List<JobInstance> recent = instanceMapper.selectList(new LambdaQueryWrapper<JobInstance>()
                .select(JobInstance.class, f -> !f.getColumn().equals("config_masked"))
                .eq(JobInstance::getJobDefinitionId, jobId)
                .orderByDesc(JobInstance::getCreatedAt)
                .last("limit 1"));
        List<JobInstance> active = instanceMapper.selectList(new LambdaQueryWrapper<JobInstance>()
                .select(JobInstance.class, f -> !f.getColumn().equals("config_masked"))
                .eq(JobInstance::getJobDefinitionId, jobId)
                .in(JobInstance::getStatus, ACTIVE)
                .orderByDesc(JobInstance::getCreatedAt));
        List<JobInstance> finished = instanceMapper.selectList(new LambdaQueryWrapper<JobInstance>()
                .select(JobInstance.class, f -> !f.getColumn().equals("config_masked"))
                .eq(JobInstance::getJobDefinitionId, jobId)
                .in(JobInstance::getStatus, FINISHED)
                .orderByDesc(JobInstance::getFinishedAt)
                .last("limit " + TREND_RUNS));

        List<RunPoint> trend = new ArrayList<>(finished.stream().map(JobOverviewService::point).toList());
        Collections.reverse(trend);

        // The live run's metrics when one is going, otherwise the latest finished run's.
        String metricsInstanceId = null;
        EngineMetrics metrics = EngineMetrics.EMPTY;
        for (JobInstance candidate : concat(active, finished)) {
            InstanceService.RunMetrics run = instanceService.metrics(candidate.getId(), null, false);
            if (!run.metrics().tables().isEmpty() || run.metrics().writeRows() != null) {
                metricsInstanceId = candidate.getId();
                metrics = run.metrics();
                break;
            }
        }

        return new Overview(
                stats(jobId),
                recent.isEmpty() ? null : instanceService.views(recent).get(0),
                instanceService.views(active),
                trend,
                metricsInstanceId,
                metrics,
                flow(current),
                scheduleService.get(jobId));
    }

    private Stats stats(String jobId) {
        List<JobInstance> runs = instanceMapper.selectList(new LambdaQueryWrapper<JobInstance>()
                .select(JobInstance::getStatus, JobInstance::getStartedAt, JobInstance::getFinishedAt, JobInstance::getWriteRows)
                .eq(JobInstance::getJobDefinitionId, jobId)
                .in(JobInstance::getStatus, FINISHED)
                .ge(JobInstance::getFinishedAt, LocalDateTime.now().minus(STATS_WINDOW)));
        int success = 0;
        int failed = 0;
        int canceled = 0;
        long durationSum = 0;
        int durationCount = 0;
        long writeRows = 0;
        for (JobInstance run : runs) {
            switch (RunStatus.valueOf(run.getStatus())) {
                case SUCCESS -> {
                    success++;
                    if (run.getStartedAt() != null && run.getFinishedAt() != null) {
                        durationSum += Duration.between(run.getStartedAt(), run.getFinishedAt()).toMillis();
                        durationCount++;
                    }
                }
                case FAILED -> failed++;
                default -> canceled++;
            }
            writeRows += run.getWriteRows() == null ? 0 : run.getWriteRows();
        }
        return new Stats(runs.size(), success, failed, canceled, durationCount == 0 ? null : durationSum / durationCount, writeRows);
    }

    private static RunPoint point(JobInstance i) {
        Long duration = i.getStartedAt() == null || i.getFinishedAt() == null
                ? null
                : Duration.between(i.getStartedAt(), i.getFinishedAt()).toMillis();
        return new RunPoint(i.getId(), i.getStatus(), i.getTriggerType(), i.getScheduledTime(), i.getStartedAt(), duration,
                i.getReadRows(), i.getWriteRows());
    }

    private Flow flow(VersionDetail version) {
        if (version == null) {
            return new Flow(List.of(), List.of(), List.of(), null, null, null);
        }
        WizardForm form = version.form();
        if (form != null) {
            return wizardFlow(form);
        }
        HoconTemplate template;
        try {
            template = HoconTemplate.parse(version.hocon());
        } catch (ConsoleException e) {
            return new Flow(List.of(), List.of(), List.of(), null, null, null);
        }
        List<Endpoint> sources = template.blocks("source").stream().map(b -> endpoint(b, sourceLocation(b))).toList();
        List<Endpoint> sinks = template.blocks("sink").stream().map(b -> endpoint(b, sinkLocation(b))).toList();
        return new Flow(sources, sinks, List.of(), null, null, null);
    }

    private Flow wizardFlow(WizardForm form) {
        List<String> tables = form.source().tables().stream().map(WizardForm.TableSpec::name).toList();
        Endpoint source = endpoint(null, form.source().datasourceId(), form.source().namespace());
        WizardForm.Target t = form.target();
        String targetType = typeOf(t.datasourceId());
        String naming;
        if (tables.size() == 1 && t.tableName() != null && !t.tableName().isBlank()) {
            naming = t.tableName();
        } else {
            naming = Objects.toString(t.prefix(), "") + "{表名}" + Objects.toString(t.suffix(), "");
        }
        String location = "ELASTICSEARCH".equals(targetType) ? naming : t.namespace() + " · " + naming;
        Endpoint sink = endpoint(null, t.datasourceId(), location);
        String detail = (t.writeMode() == null ? "" : t.writeMode().name()) + " · "
                + (t.dataMode() == WizardForm.DataMode.CLEAR ? "写入前清空" : "保留已有数据");
        return new Flow(List.of(source), List.of(sink), tables,
                form.sync().mode() == null ? null : form.sync().mode().name(),
                form.sync().window() == null ? null : form.sync().window().name(),
                detail);
    }

    private Endpoint endpoint(HoconTemplate.Block block, String location) {
        return endpoint(block.connector(), block.string(HoconTemplate.DATASOURCE_KEY), location);
    }

    private Endpoint endpoint(String connector, String datasourceId, String location) {
        String name = null;
        String type = null;
        if (datasourceId != null && !datasourceId.isBlank()) {
            try {
                DatasourceService.DatasourceView ds = datasourceService.get(datasourceId);
                name = ds.name();
                type = ds.type();
            } catch (ConsoleException e) {
                // Deleted since the version was saved; show the id only.
            }
        }
        return new Endpoint(connector, datasourceId, name, type, location);
    }

    private String typeOf(String datasourceId) {
        try {
            return datasourceService.typeOf(datasourceId);
        } catch (ConsoleException e) {
            return null;
        }
    }

    private static String sourceLocation(HoconTemplate.Block b) {
        int listed = b.objects("table_list").size();
        if (listed > 0) {
            return listed + " 张表";
        }
        String tablePath = b.string("table_path");
        if (tablePath != null) {
            return tablePath;
        }
        String query = b.string("query");
        if (query == null) {
            return null;
        }
        String flat = query.replaceAll("\\s+", " ").trim();
        return flat.length() <= 60 ? flat : flat.substring(0, 60) + "…";
    }

    private static String sinkLocation(HoconTemplate.Block b) {
        String index = b.string("index");
        if (index != null) {
            return index;
        }
        String database = b.string("database");
        String table = b.string("table");
        if (table == null) {
            return database;
        }
        return database == null ? table : database + " · " + table;
    }

    private static List<JobInstance> concat(List<JobInstance> a, List<JobInstance> b) {
        List<JobInstance> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
