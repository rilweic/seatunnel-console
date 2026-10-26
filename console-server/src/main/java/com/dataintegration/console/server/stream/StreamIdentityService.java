package com.dataintegration.console.server.stream;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.datasource.jdbc.MySqlDataSourcePlugin;
import com.dataintegration.console.datasource.jdbc.PostgreSqlDataSourcePlugin;
import com.dataintegration.console.server.persistence.entity.JobStream;
import com.dataintegration.console.server.persistence.mapper.JobStreamMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The identity a streaming job has on its source database (docs/design.md §12 P2, Q9): a block of
 * MySQL server-ids no other job uses, and a PostgreSQL replication slot and publication of its
 * own. It belongs to the job, not to a version, so it is put into the CDC source block when the
 * config is rendered, unless the config sets it itself.
 */
@Service
public class StreamIdentityService {

    /** Ids per job: enough for the highest wizard parallelism, one per CDC reader. */
    static final int SERVER_IDS_PER_JOB = 8;

    private final JobStreamMapper mapper;
    private final int serverIdBase;

    public StreamIdentityService(JobStreamMapper mapper, @Value("${console.stream.server-id-base:5400}") int serverIdBase) {
        this.mapper = mapper;
        this.serverIdBase = serverIdBase;
    }

    /** Creates the job's stream row with its identity; called when a streaming job is created. */
    public JobStream create(String jobId) {
        for (int attempt = 0; ; attempt++) {
            JobStream stream = new JobStream();
            stream.setJobDefinitionId(jobId);
            stream.setAutoRestart(false);
            stream.setMaxRestarts(3);
            stream.setRestartIntervalSeconds(30);
            stream.setPipelineRestores(0);
            stream.setServerIdStart(mapper.nextServerIdStart(serverIdBase));
            stream.setReplicationName(replicationName(jobId));
            try {
                mapper.insert(stream);
                return stream;
            } catch (DuplicateKeyException e) {
                // another job took the same server-id block at the same moment
                if (attempt >= 2) {
                    throw e;
                }
            }
        }
    }

    public JobStream require(String jobId) {
        JobStream stream = mapper.selectById(jobId);
        if (stream == null) {
            throw new ConsoleException(ErrorCodes.NOT_FOUND, "not a real-time job: " + jobId);
        }
        return stream;
    }

    /**
     * Slot and publication name: lower case so PostgreSQL needs no quoting, and from the random
     * end of the UUID v7, since its start is a timestamp jobs created together share.
     */
    static String replicationName(String jobId) {
        String hex = jobId.replace("-", "").toLowerCase();
        return "seatunnel_" + hex.substring(hex.length() - 12);
    }

    /**
     * Options to add to a CDC source block of this job, written into the config only where the
     * block does not set them.
     *
     * @param parallelism the job's parallelism: each CDC reader needs its own server-id
     */
    public Map<String, Object> sourceOptions(JobStream stream, String connector, int parallelism) {
        Map<String, Object> options = new LinkedHashMap<>();
        if (MySqlDataSourcePlugin.CDC_CONNECTOR.equals(connector) && stream.getServerIdStart() != null) {
            int start = stream.getServerIdStart();
            int count = Math.min(Math.max(1, parallelism), SERVER_IDS_PER_JOB);
            options.put("server-id", count == 1 ? String.valueOf(start) : start + "-" + (start + count - 1));
        } else if (PostgreSqlDataSourcePlugin.CDC_CONNECTOR.equals(connector) && stream.getReplicationName() != null) {
            options.put("slot.name", stream.getReplicationName());
            // only the job's tables, which an owner of them may publish without being superuser
            options.put("debezium", Map.of(
                    "publication.autocreate.mode", "filtered",
                    "publication.name", stream.getReplicationName()));
        }
        return options;
    }
}
