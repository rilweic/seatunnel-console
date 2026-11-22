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

    /** Deletes one archived log if its key is valid and the file still exists. */
    void delete(String key);

    /** Current archived log file count and total size in bytes. */
    default StorageStats stats() {
        return new StorageStats(0, 0);
    }

    /** Deletes archived logs strictly older than {@code cutoff} (day directory < cutoff). */
    default PurgeStats purgeOlderThan(LocalDate cutoff) {
        return new PurgeStats(0, 0);
    }

    record StorageStats(long fileCount, long totalBytes) {}

    record PurgeStats(long deletedFiles, long deletedBytes) {}
}
