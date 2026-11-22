package com.dataintegration.console.engine.flink;

import com.dataintegration.console.engine.ConnectorOption;
import com.dataintegration.console.engine.EngineCheckpoints;
import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.engine.EngineJobInfo;
import com.dataintegration.console.engine.EngineLogFile;
import com.dataintegration.console.engine.EngineType;
import com.dataintegration.console.engine.EngineWorker;
import com.dataintegration.console.engine.MasterStatus;
import com.dataintegration.console.engine.SubmitJobRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link EngineClient} for Flink Standalone and Session clusters: submits jobs via the local Flink
 * CLI ({@link FlinkCliSubmitter}), monitors and controls them via Flink REST API v1
 * ({@link FlinkRestClient}), and reads connector option metadata offline from the local SeaTunnel
 * distribution ({@link ConnectorOptionCliReader}).
 */
public class FlinkEngineClient implements EngineClient {

    private final FlinkRestClient rest;
    private final FlinkCliSubmitter submitter;
    private final ConnectorOptionCliReader optionReader;

    public FlinkEngineClient(FlinkClusterConfig config) {
        this(new FlinkRestClient(config), new FlinkCliSubmitter(config), new ConnectorOptionCliReader(config));
    }

    FlinkEngineClient(FlinkRestClient rest, FlinkCliSubmitter submitter, ConnectorOptionCliReader optionReader) {
        this.rest = rest;
        this.submitter = submitter;
        this.optionReader = optionReader;
    }

    @Override
    public EngineType type() {
        return EngineType.FLINK;
    }

    @Override
    public String newJobId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public List<ConnectorOption> connectorOptions(String pluginType, String plugin) {
        return optionReader.readOptions(pluginType, plugin);
    }

    @Override
    public List<MasterStatus> probeMasters() {
        return List.of(rest.probeMaster());
    }

    @Override
    public List<EngineWorker> workers() {
        return rest.workers();
    }

    @Override
    public boolean hasConnector(String pluginType, String plugin) {
        return optionReader.hasConnector(pluginType, plugin);
    }

    @Override
    public Map<String, String> overview() {
        return rest.overview();
    }

    @Override
    public String submit(SubmitJobRequest request) {
        return submitter.submit(request);
    }

    @Override
    public StopResult stop(String jobId, boolean withSavepoint, boolean force) {
        return rest.stop(jobId, withSavepoint, force);
    }

    @Override
    public void disposeSavepoint(String path) {
        rest.disposeSavepoint(path);
    }

    @Override
    public Optional<EngineJobInfo> jobInfo(String jobId) {
        return rest.jobInfo(jobId);
    }

    @Override
    public Optional<EngineCheckpoints> checkpoints(String jobId) {
        return rest.checkpoints(jobId);
    }

    @Override
    public Optional<String> jobExceptions(String jobId) {
        return rest.jobExceptions(jobId);
    }

    @Override
    public boolean archivesPerJobLogs() {
        return false;
    }

    @Override
    public List<String> configWarnings() {
        return rest.configWarnings();
    }

    @Override
    public List<EngineLogFile> listLogs(String jobId) {
        return rest.listLogs(jobId);
    }

    @Override
    public LogContent readLog(EngineLogFile file, long maxBytes) {
        return rest.readLog(file, maxBytes);
    }
}
