package com.dataintegration.console.server.stream;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.datasource.DataSourceCapabilities;
import com.dataintegration.console.datasource.meta.ColumnMeta;
import com.dataintegration.console.server.datasource.DatasourceService;
import com.dataintegration.console.server.job.JobService;
import com.dataintegration.console.server.job.wizard.WizardForm;
import com.dataintegration.console.server.message.MessageCategory;
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
import com.dataintegration.console.server.stream.SchemaFollow.ColumnChange;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Notices table-structure changes of running real-time jobs (docs/design.md §12 P2, Q16): every few
 * minutes the source and target tables of each running wizard job are read and compared with what
 * was seen before; a change goes into the run's timeline with whether the target followed and, if
 * not, why. Only running jobs are read, one table at a time, so a stopped console of many jobs
 * costs the databases nothing. What the tables looked like when the run started is kept too, so
 * the detail page can show each table's changes since (§12 P2, 表结构变更的反馈).
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
                check(stream.getJobDefinitionId(), false);
            } catch (RuntimeException e) {
                log.warn("Checking table structures of real-time job {} failed: {}", stream.getJobDefinitionId(), e.toString());
            }
        }
    }

    /**
     * Compares the job's tables with what was seen last; the first look only records them.
     *
     * @param starting called as a run starts: what is seen now becomes what the run started with
     */
    public void check(String jobId, boolean starting) {
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
        SchemaFollow follow = follow(form);
        for (TableSpec table : form.source().tables()) {
            String targetName = WizardGenerator.targetName(form, table, target.lowerCaseNames());
            List<ColumnMeta> source = read(form.source().datasourceId(), form.source().namespace(), table.name());
            List<ColumnMeta> sink = read(form.target().datasourceId(), target.namespaces() ? form.target().namespace() : null, targetName);
            if (source == null) {
                continue;
            }
            List<ColumnMeta> sourceBefore = remember(jobId, table.name(), SOURCE, source, starting);
            List<ColumnMeta> sinkBefore = sink == null ? null : remember(jobId, table.name(), TARGET, sink, starting);
            if (sourceBefore != null) {
                SchemaDiff changes = SchemaDiff.between(sourceBefore, source);
                if (!changes.empty()) {
                    report(instance, follow, table, targetName, changes, sink, sinkBefore);
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

    /**
     * A table's column changes since the job's latest start and whether the target followed each,
     * per table of the form the run uses; tables without changes are left out. Reads only what the
     * watcher stored, no database.
     */
    public List<TableChanges> sinceStart(String jobId, WizardForm form) {
        List<TableChanges> out = new ArrayList<>();
        SchemaFollow follow = null;
        for (TableSpec table : form.source().tables()) {
            StreamTableSchema source = schemas.selectOne(key(jobId, table.name(), SOURCE));
            if (source == null || source.getStartColumnsJson() == null) {
                continue;
            }
            SchemaDiff changes = SchemaDiff.between(columns(source.getStartColumnsJson()), columns(source.getColumnsJson()));
            if (changes.empty()) {
                continue;
            }
            follow = follow == null ? follow(form) : follow;
            StreamTableSchema target = schemas.selectOne(key(jobId, table.name(), TARGET));
            out.add(new TableChanges(table.name(), follow.targetName(table), source.getCapturedAt(),
                    follow.assess(table, changes, target == null ? null : columns(target.getColumnsJson()),
                            target == null || target.getStartColumnsJson() == null ? null : columns(target.getStartColumnsJson()))));
        }
        return out;
    }

    /** Drops what was seen of a deleted job's tables. */
    public void forget(String jobId) {
        schemas.delete(new LambdaQueryWrapper<StreamTableSchema>().eq(StreamTableSchema::getJobDefinitionId, jobId));
    }

    /** @param checkedAt when the source table was last read */
    public record TableChanges(String table, String targetName, LocalDateTime checkedAt, List<ColumnChange> changes) {}

    private SchemaFollow follow(WizardForm form) {
        return new SchemaFollow(form, datasources.typeOf(form.source().datasourceId()), datasources.typeOf(form.target().datasourceId()));
    }

    private List<ColumnMeta> columns(String json) {
        return objectMapper.readValue(json, COLUMNS);
    }

    private static LambdaQueryWrapper<StreamTableSchema> key(String jobId, String table, String side) {
        return new LambdaQueryWrapper<StreamTableSchema>()
                .eq(StreamTableSchema::getJobDefinitionId, jobId)
                .eq(StreamTableSchema::getTableName, table)
                .eq(StreamTableSchema::getSide, side);
    }

    /** Stores what is seen now and returns what was seen before; null on the first look. */
    private List<ColumnMeta> remember(String jobId, String table, String side, List<ColumnMeta> now, boolean starting) {
        LambdaQueryWrapper<StreamTableSchema> key = key(jobId, table, side);
        StreamTableSchema before = schemas.selectOne(key);
        StreamTableSchema row = new StreamTableSchema();
        row.setJobDefinitionId(jobId);
        row.setTableName(table);
        row.setSide(side);
        row.setColumnsJson(objectMapper.writeValueAsString(now));
        row.setCapturedAt(LocalDateTime.now());
        if (starting || before == null) {
            row.setStartColumnsJson(row.getColumnsJson());
        }
        if (before == null) {
            schemas.insert(row);
            return null;
        }
        schemas.update(row, key);
        return columns(before.getColumnsJson());
    }

    /** One event per changed source table: each change, and whether the target followed. */
    private void report(JobInstance instance, SchemaFollow follow, TableSpec table, String targetName, SchemaDiff changes, List<ColumnMeta> sink,
            List<ColumnMeta> sinkBefore) {
        List<ColumnChange> assessed = follow.assess(table, changes, sink, sinkBefore);
        boolean problem = assessed.stream().anyMatch(ColumnChange::problem);
        String message = "源表 " + table.name() + "（目标 " + targetName + "）结构变了："
                + String.join("；", assessed.stream().map(ColumnChange::line).toList());
        Level level = problem ? Level.WARN : Level.INFO;
        runs.recorder().event(instance.getId(), instance.getAttemptCount(), RunEventType.SCHEMA_CHANGE, level, message);
        String jobId = instance.getJobDefinitionId();
        runs.messages().job(MessageCategory.SCHEMA_CHANGE, level, jobId, instance.getId(), "/realtime/" + jobId, null,
                problem ? "源表结构变了，目标端没有跟上" : "源表结构变了", message);
    }
}
