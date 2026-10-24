package com.dataintegration.console.server.run;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Archive for SeaTunnel job logs, one entry per attempt (docs/schema.md §5). P1 stores files on the
 * console's local disk; a shared object store replaces it once the console runs multi-instance.
 */
public interface LogStorage {

    /** Stores the log and returns the key recorded in {@code job_instance_attempt.log_storage_key}. */
    String write(String attemptId, LocalDate day, String content);

    /** Empty when the entry was removed by retention or never existed. */
    Optional<String> read(String key);
}
