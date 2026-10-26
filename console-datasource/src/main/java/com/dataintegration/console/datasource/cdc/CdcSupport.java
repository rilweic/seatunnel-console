package com.dataintegration.console.datasource.cdc;

import java.util.List;
import java.util.Map;

/**
 * What a database that real-time jobs read through CDC offers besides plain reads: checking it is
 * set up for CDC, and the replication resources a job leaves on it (docs/design.md §12 P2).
 * {@code props} are decrypted connection properties and must never be logged.
 */
public interface CdcSupport {

    /**
     * Which tables and replication identity a check is about.
     *
     * @param tables tables of the job; empty to check only the server and the account
     * @param replicationName the job's slot and publication (PostgreSQL); null when there is none yet
     */
    record Scope(String namespace, List<String> tables, String replicationName) {

        public static Scope server() {
            return new Scope(null, List.of(), null);
        }
    }

    /**
     * A replication resource a job keeps on the database while it is stopped.
     *
     * @param active a reader is attached
     * @param retainedBytes WAL the database keeps for it, which grows while nobody reads
     */
    record Replication(String name, boolean active, long retainedBytes) {}

    /** Never throws: an unreachable database is an ERROR check. */
    List<CdcCheck> check(Map<String, String> props, Scope scope);

    /** The job's replication resource on the database; null when there is none (or never is, MySQL). */
    default Replication replication(Map<String, String> props, String name) {
        return null;
    }

    /**
     * Drops the job's replication resources (PostgreSQL slot and publication).
     *
     * @return the SQL that is left to run by hand, empty when everything was dropped
     */
    default List<String> dropReplication(Map<String, String> props, String name) {
        return List.of();
    }
}
