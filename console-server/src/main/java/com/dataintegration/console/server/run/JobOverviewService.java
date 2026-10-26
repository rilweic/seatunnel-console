package com.dataintegration.console.server.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.datasource.DataSourceCapabilities;
import com.dataintegration.console.server.datasource.DatasourceService;
import com.dataintegration.console.server.job.HoconTemplate;
import com.dataintegration.console.server.job.JobService;
import com.dataintegration.console.server.job.JobService.VersionDetail;
import com.dataintegration.console.server.job.ScheduleService;
import com.dataintegration.console.server.job.ScheduleService.ScheduleView;
import com.dataintegration.console.server.job.wizard.WizardConnectors;
import com.dataintegration.console.server.job.wizard.WizardForm;
import com.dataintegration.console.server.job.wizard.WizardGenerator;
import com.dataintegration.console.server.job.wizard.WizardSource;
import com.dataintegration.console.server.persistence.entity.JobInstance;
import com.dataintegration.console.server.persistence.mapper.JobInstanceMapper;
import com.dataintegration.console.server.run.InstanceService.InstanceView;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
     * @param location database / schema, endpoint path, index, or a short form of the query
     * @param note one more line, e.g. "8 张表" or "目标不存在时自动创建"
     */
    public record Endpoint(String connector, String datasourceId, String datasourceName, String datasourceType,
            String location, String note) {}

    /**
     * How a wizard job writes and checks, with what the source can do already applied (an HTTP
     * source without keys appends, runs single-threaded and is not counted).
     *
     * @param mode FULL or INCREMENTAL; window DAY, HOUR or CUSTOM; incrementalField the job-wide one
     */
    public record Settings(String mode, String window, String incrementalField, String writeMode, String dataMode,
            String schemaMode, int parallelism, boolean validation) {}

    /** @param notes what happens to this table, e.g. "过滤 status = 1", "脱敏 1 个字段" */
    public record TableFlow(String source, String target, List<String> notes) {}

    /**
     * What the current version syncs (docs/design.md §10.4); derived from the wizard form, or read
     * from the HOCON for script jobs (best effort: a config that does not parse yields empty lists).
     *
     * @param processing what the wizard does to the data between source and sink, summed over all
     *     tables, e.g. "改名 3 个字段"; empty when rows pass through unchanged
     * @param transforms the transform plugins of a script job, in order
     * @param settings null for script jobs
     * @param tables source and target of each table, for wizard jobs
     */
    public record Flow(List<Endpoint> sources, List<Endpoint> sinks, List<String> processing, List<String> transforms,
            Settings settings, List<TableFlow> tables) {}

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
            return new Flow(List.of(), List.of(), List.of(), List.of(), null, List.of());
        }
        List<Endpoint> sources = template.blocks("source").stream().map(b -> endpoint(b, sourceLocation(b))).toList();
        List<Endpoint> sinks = template.blocks("sink").stream().map(b -> endpoint(b, sinkLocation(b))).toList();
        return new Flow(sources, sinks, List.of(), template.transformPlugins(), null, List.of());
    }

    private Flow wizardFlow(WizardForm form) {
        String sourceType = typeOf(form.source().datasourceId());
        DataSourceCapabilities from = capabilities(sourceType);
        WizardSource source = null;
        try {
            source = sourceType == null ? null : WizardConnectors.source(sourceType, form);
        } catch (ConsoleException e) {
            // not a wizard source (any more)
        }
        String sourceLocation = source == null ? form.source().namespace() : source.location(form);
        int tableCount = form.source().tables().size();
        Endpoint sourceEnd = endpoint(null, form.source().datasourceId(), sourceLocation,
                from != null && !from.multiTable() ? null : tableCount + " 张表");

        WizardForm.Target t = form.target();
        DataSourceCapabilities target = capabilities(typeOf(t.datasourceId()));
        boolean lowerCase = target != null && target.lowerCaseNames();
        Endpoint sinkEnd = endpoint(null, t.datasourceId(), target != null && !target.namespaces() ? null : t.namespace(),
                t.schemaMode() == WizardForm.SchemaMode.ERROR ? "目标不存在时报错" : "目标不存在时自动创建");

        boolean incremental = form.sync().mode() == WizardForm.SyncMode.INCREMENTAL;
        boolean upsert = t.writeMode() == WizardForm.WriteMode.UPSERT && (source == null || source.upsertPossible(form));
        int parallelism = Math.min(Math.max(1, form.parallelism()), from == null ? form.parallelism() : from.maxParallelism());
        Settings settings = new Settings(
                form.sync().mode() == null ? null : form.sync().mode().name(),
                incremental && form.sync().window() != null ? form.sync().window().name() : null,
                incremental ? form.sync().field() : null,
                upsert ? "UPSERT" : "APPEND",
                t.dataMode() == null ? null : t.dataMode().name(),
                t.schemaMode() == null ? null : t.schemaMode().name(),
                parallelism,
                form.validation() && (from == null || from.countable()));

        Totals totals = new Totals();
        List<TableFlow> tables = form.source().tables().stream()
                .map(table -> new TableFlow(table.name(), WizardGenerator.targetName(form, table, lowerCase),
                        tableNotes(form, table, incremental, totals)))
                .toList();
        return new Flow(List.of(sourceEnd), List.of(sinkEnd), totals.summary(form), List.of(), settings, tables);
    }

    /** Field processing summed over the tables, for the processing step of the flow. */
    private static final class Totals {
        int filtered;
        int pruned;
        int renamed;
        int ruled;
        int replaced;
        int masked;
        int cast;
        int encrypted;
        int extracted;
        int added;

        List<String> summary(WizardForm form) {
            List<String> steps = new ArrayList<>();
            add(steps, filtered, "过滤 %d 张表");
            add(steps, pruned, "裁剪字段 %d 张表");
            add(steps, renamed, "改名 %d 个字段");
            if (form.fieldRule() != null) {
                steps.add("字段名按规则改写");
            } else {
                add(steps, ruled, "字段名规则 %d 张表");
            }
            add(steps, replaced, "替换 %d 个字段");
            add(steps, masked, "脱敏 %d 个字段");
            add(steps, cast, "类型转换 %d 个字段");
            add(steps, encrypted, "加密 %d 个字段");
            add(steps, extracted, "提取 %d 个新字段");
            add(steps, added, "新增 %d 个字段");
            return steps;
        }

        private static void add(List<String> steps, int count, String text) {
            if (count > 0) {
                steps.add(text.formatted(count));
            }
        }
    }

    /** What happens to one table, counted into the totals as well. */
    private static List<String> tableNotes(WizardForm form, WizardForm.TableSpec table, boolean incremental, Totals totals) {
        List<String> notes = new ArrayList<>();
        if (table.filter() != null && !table.filter().isBlank()) {
            notes.add("过滤 " + table.filter().trim());
            totals.filtered++;
        }
        if (incremental && table.incrementalField() != null && !table.incrementalField().isBlank()) {
            notes.add("增量字段 " + table.incrementalField().trim());
        }
        if (!table.allColumns()) {
            notes.add("同步 " + table.columns().size() + " 个字段");
            totals.pruned++;
        }
        long renamed = table.fieldNames() == null ? 0 : table.fieldNames().values().stream().filter(n -> n != null && !n.isBlank()).count();
        if (renamed > 0) {
            notes.add("改名 " + renamed + " 个字段");
            totals.renamed += (int) renamed;
        }
        if (table.fieldRule() != null) {
            notes.add("字段名按规则改写");
            totals.ruled++;
        }
        if (table.processes() != null) {
            int replaced = 0, masked = 0, cast = 0, encrypted = 0;
            for (WizardForm.FieldProcess p : table.processes().values()) {
                replaced += p.replace() != null ? 1 : 0;
                masked += p.mask() != null ? 1 : 0;
                cast += p.cast() != null ? 1 : 0;
                encrypted += p.encrypted() ? 1 : 0;
            }
            note(notes, replaced, "替换 %d 个字段");
            note(notes, masked, "脱敏 %d 个字段");
            note(notes, cast, "类型转换 %d 个字段");
            note(notes, encrypted, "加密 %d 个字段");
            totals.replaced += replaced;
            totals.masked += masked;
            totals.cast += cast;
            totals.encrypted += encrypted;
        }
        int extracted = table.extracts() == null ? 0
                : table.extracts().stream().mapToInt(e -> e.outputs() == null ? 0 : e.outputs().size()).sum();
        note(notes, extracted, "提取 %d 个新字段");
        totals.extracted += extracted;
        int added = table.addedFields() == null ? 0 : table.addedFields().size();
        note(notes, added, "新增 %d 个字段");
        totals.added += added;
        return notes;
    }

    private static void note(List<String> notes, int count, String text) {
        if (count > 0) {
            notes.add(text.formatted(count));
        }
    }

    private Endpoint endpoint(HoconTemplate.Block block, String location) {
        return endpoint(block.connector(), block.string(HoconTemplate.DATASOURCE_KEY), location, null);
    }

    private Endpoint endpoint(String connector, String datasourceId, String location, String note) {
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
        return new Endpoint(connector, datasourceId, name, type, location, note);
    }

    private static DataSourceCapabilities capabilities(String type) {
        try {
            return type == null ? null : WizardConnectors.capabilities(type);
        } catch (ConsoleException e) {
            return null;
        }
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
