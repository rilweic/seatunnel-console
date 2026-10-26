package com.dataintegration.console.server.stream;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.datasource.DataSourceCapabilities;
import com.dataintegration.console.datasource.meta.ColumnMeta;
import com.dataintegration.console.datasource.jdbc.PostgreSqlDataSourcePlugin;
import com.dataintegration.console.server.datasource.DatasourceService;
import com.dataintegration.console.server.job.JobService;
import com.dataintegration.console.server.job.wizard.WizardConnectors;
import com.dataintegration.console.server.job.wizard.WizardForm;
import com.dataintegration.console.server.job.wizard.WizardForm.TableSpec;
import com.dataintegration.console.server.job.wizard.WizardGenerator;
import com.dataintegration.console.server.persistence.entity.JobInstance;
import com.dataintegration.console.server.persistence.entity.JobStream;
import com.dataintegration.console.server.persistence.entity.JobVersion;
import com.dataintegration.console.server.persistence.entity.StreamTableSchema;
import com.dataintegration.console.server.persistence.mapper.JobStreamMapper;
import com.dataintegration.console.server.persistence.mapper.StreamTableSchemaMapper;
import com.dataintegration.console.server.run.RunEventType;
import com.dataintegration.console.server.run.RunRecorder.Level;
import com.dataintegration.console.server.stream.SchemaDiff.Renamed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Notices table-structure changes of running real-time jobs (docs/design.md §12 P2, Q16): every few
 * minutes the source and target tables of each running wizard job are read and compared with what
 * was seen before; a change goes into the run's timeline with whether the target followed and, if
 * not, why. Only running jobs are read, one table at a time, so a stopped console of many jobs
 * costs the databases nothing.
 */
@Component
public class SchemaDriftWatcher {

    private static final Logger log = LoggerFactory.getLogger(SchemaDriftWatcher.class);
    private static final TypeReference<List<ColumnMeta>> COLUMNS = new TypeReference<>() {};
    static final String SOURCE = "SOURCE";
    static final String TARGET = "TARGET";

    private final JobStreamMapper streams;
    private final StreamTableSchemaMapper schemas;
    private final StreamRuns runs;
    private final JobService jobService;
    private final DatasourceService datasources;
    private final ObjectMapper objectMapper;

    public SchemaDriftWatcher(JobStreamMapper streams, StreamTableSchemaMapper schemas, StreamRuns runs, JobService jobService,
            DatasourceService datasources, ObjectMapper objectMapper) {
        this.streams = streams;
        this.schemas = schemas;
        this.runs = runs;
        this.jobService = jobService;
        this.datasources = datasources;
        this.objectMapper = objectMapper;
    }

    @Scheduled(initialDelayString = "${console.stream.schema-initial-delay:1m}", fixedDelayString = "${console.stream.schema-interval:5m}")
    public void watch() {
        for (JobStream stream : streams.selectList(new LambdaQueryWrapper<JobStream>().isNotNull(JobStream::getCurrentInstanceId))) {
            try {
                check(stream.getJobDefinitionId());
            } catch (RuntimeException e) {
                log.warn("Checking table structures of real-time job {} failed: {}", stream.getJobDefinitionId(), e.toString());
            }
        }
    }

    /** Compares the job's tables with what was seen last; the first look only records them (also called at start). */
    public void check(String jobId) {
        JobStream stream = streams.selectById(jobId);
        if (stream == null || stream.getCurrentInstanceId() == null) {
            return;
        }
        JobInstance instance = runs.recorder().requireInstance(stream.getCurrentInstanceId());
        JobVersion version = jobService.requireVersion(instance.getJobVersionId());
        if (version.getFormJson() == null) {
            return; // a script names no tables to the console
        }
        WizardForm form = objectMapper.readValue(version.getFormJson(), WizardForm.class);
        DataSourceCapabilities target = datasources.capabilitiesOf(form.target().datasourceId());
        for (TableSpec table : form.source().tables()) {
            String targetName = WizardGenerator.targetName(form, table, target.lowerCaseNames());
            List<ColumnMeta> source = read(form.source().datasourceId(), form.source().namespace(), table.name());
            List<ColumnMeta> sink = read(form.target().datasourceId(), target.namespaces() ? form.target().namespace() : null, targetName);
            if (source == null) {
                continue;
            }
            List<ColumnMeta> sourceBefore = remember(jobId, table.name(), SOURCE, source);
            List<ColumnMeta> sinkBefore = sink == null ? null : remember(jobId, table.name(), TARGET, sink);
            if (sourceBefore != null) {
                SchemaDiff changes = SchemaDiff.between(sourceBefore, source);
                if (!changes.empty()) {
                    report(instance, form, table, targetName, changes, sink);
                    continue;
                }
            }
            if (sinkBefore != null && sink != null) {
                SchemaDiff changes = SchemaDiff.between(sinkBefore, sink);
                if (!changes.empty()) {
                    runs.recorder().event(instance.getId(), instance.getAttemptCount(), RunEventType.SCHEMA_CHANGE, Level.INFO,
                            "目标表 " + targetName + " 的结构变了（源表没变）：" + String.join("；", changes.lines()));
                }
            }
        }
    }

    private List<ColumnMeta> read(String datasourceId, String namespace, String table) {
        try {
            return datasources.columnsForJob(datasourceId, namespace, table);
        } catch (ConsoleException e) {
            return null;
        }
    }

    /** Stores what is seen now and returns what was seen before; null on the first look. */
    private List<ColumnMeta> remember(String jobId, String table, String side, List<ColumnMeta> now) {
        LambdaQueryWrapper<StreamTableSchema> key = new LambdaQueryWrapper<StreamTableSchema>()
                .eq(StreamTableSchema::getJobDefinitionId, jobId)
                .eq(StreamTableSchema::getTableName, table)
                .eq(StreamTableSchema::getSide, side);
        StreamTableSchema before = schemas.selectOne(key);
        StreamTableSchema row = new StreamTableSchema();
        row.setJobDefinitionId(jobId);
        row.setTableName(table);
        row.setSide(side);
        row.setColumnsJson(objectMapper.writeValueAsString(now));
        row.setCapturedAt(LocalDateTime.now());
        if (before == null) {
            schemas.insert(row);
            return null;
        }
        schemas.update(row, key);
        return objectMapper.readValue(before.getColumnsJson(), COLUMNS);
    }

    /** One event per changed source table: each change, and whether the target followed. */
    private void report(JobInstance instance, WizardForm form, TableSpec table, String targetName, SchemaDiff changes, List<ColumnMeta> sink) {
        Set<String> targetColumns = sink == null ? Set.of() : sink.stream().map(c -> c.name().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        Expect expect = new Expect(form, datasources.typeOf(form.source().datasourceId()), datasources.typeOf(form.target().datasourceId()));
        List<String> lines = new ArrayList<>();
        boolean problem = false;
        for (ColumnMeta c : changes.added()) {
            boolean there = targetColumns.contains(c.name().toLowerCase(Locale.ROOT));
            lines.add("新增列 " + c.name() + " " + c.type() + " → " + (there ? "目标端已同步" : expect.notYet(expect.adds(), "目标端没有加这一列")));
            problem |= !there && !expect.adds();
        }
        for (Renamed r : changes.renamed()) {
            String to = WizardGenerator.targetField(form, table, r.from()).equals(r.from()) ? r.to() : null;
            boolean there = to != null && targetColumns.contains(to.toLowerCase(Locale.ROOT));
            String text;
            if (to == null) {
                text = "这一列在任务里改了名，任务可能已经因此失败，需要修改任务后全新启动";
            } else if (there) {
                text = "目标端已同步";
            } else if (expect.renames() && targetColumns.contains(r.from().toLowerCase(Locale.ROOT))) {
                text = "改名未同步，目标端还是 " + r.from() + "，之后写入的这一列在目标端为空（MySQL 8 的 RENAME COLUMN 不会被同步，请用 CHANGE COLUMN）；"
                        + "需要手动修改目标表，或修改任务后全新启动";
            } else {
                text = expect.notYet(expect.renames(), "目标端没有改名");
            }
            lines.add("列改名 " + r.from() + " → " + r.to() + " → " + text);
            problem |= !there;
        }
        for (SchemaDiff.Retyped r : changes.retyped()) {
            lines.add("列 " + r.column() + " 类型 " + r.from() + " → " + r.to() + (expect.retypes() ? "" : " → " + expect.why()));
            problem |= !expect.retypes();
        }
        for (ColumnMeta c : changes.dropped()) {
            boolean kept = targetColumns.contains(WizardGenerator.targetField(form, table, c.name()).toLowerCase(Locale.ROOT));
            String text = !kept ? "目标端已删除这一列"
                    : expect.drops() ? expect.notYet(true, "") : "目标端保留这一列，之后写入空值";
            lines.add("删除列 " + c.name() + " → " + text);
        }
        runs.recorder().event(instance.getId(), instance.getAttemptCount(), RunEventType.SCHEMA_CHANGE, problem ? Level.WARN : Level.INFO,
                "源表 " + table.name() + "（目标 " + targetName + "）结构变了：" + String.join("；", lines));
    }

    /** What the job passes on to the target, per its settings and connectors (§12 P2, Q24). */
    private static final class Expect {

        private final boolean on;
        private final boolean postgres;
        private final boolean allChanges;
        private final boolean drops;

        Expect(WizardForm form, String sourceType, String targetType) {
            on = form.stream() != null && form.stream().schemaChanges();
            postgres = PostgreSqlDataSourcePlugin.CDC_CONNECTOR.equals(WizardConnectors.capabilities(sourceType).cdcConnector());
            allChanges = WizardConnectors.sink(targetType).appliesAllColumnChanges();
            drops = on && !postgres && allChanges && form.stream().dropColumns();
        }

        boolean adds() {
            return on;
        }

        boolean renames() {
            return on && !postgres && allChanges;
        }

        boolean retypes() {
            return renames();
        }

        boolean drops() {
            return drops;
        }

        String why() {
            if (!on) {
                return "没有打开同步表结构变更，需要修改任务后重启";
            }
            if (postgres) {
                return "PostgreSQL 只同步加列，任务会停下，需要修改任务后全新启动";
            }
            return "目标是 Elasticsearch，只同步加列，任务会停下，需要处理后全新启动";
        }

        /** For a change that should reach the target: it does when the table next has a row change. */
        String notYet(boolean expected, String fallback) {
            return expected ? "目标端还没有变化（一般在这张表下一次有数据变更时同步）" : (fallback.isEmpty() ? "" : fallback + "：") + why();
        }
    }
}
