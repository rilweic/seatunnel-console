package com.dataintegration.console.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataintegration.console.server.persistence.entity.RetentionCleanupLog;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RetentionCleanupLogMapper extends BaseMapper<RetentionCleanupLog> {

    /** Batch-delete expired metric samples in chunks to avoid long table locks. */
    @Delete("DELETE FROM job_metric_sample WHERE sampled_at < #{cutoff} LIMIT #{limit}")
    int deleteMetricSamplesBatch(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /** Find terminal job instances older than cutoff, never touching active stream current instances. */
    @Select("""
            SELECT ji.id FROM job_instance ji
            WHERE ji.status IN ('SUCCESS', 'FAILED', 'CANCELED')
              AND COALESCE(ji.finished_at, ji.created_at) < #{cutoff}
              AND NOT EXISTS (
                  SELECT 1 FROM job_stream js WHERE js.current_instance_id = ji.id
              )
            ORDER BY ji.created_at ASC
            LIMIT #{limit}
            """)
    List<String> findExpiredInstanceIds(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /** Count terminal job instances eligible for cleanup under the current cutoff. */
    @Select("""
            SELECT COUNT(*) FROM job_instance ji
            WHERE ji.status IN ('SUCCESS', 'FAILED', 'CANCELED')
              AND COALESCE(ji.finished_at, ji.created_at) < #{cutoff}
              AND NOT EXISTS (
                  SELECT 1 FROM job_stream js WHERE js.current_instance_id = ji.id
              )
            """)
    long countExpiredInstances(@Param("cutoff") LocalDateTime cutoff);

    /** Batch-delete terminal backfill batches older than cutoff. */
    @Delete("""
            DELETE FROM backfill_batch
            WHERE status IN ('FINISHED', 'CANCELED')
              AND COALESCE(finished_at, created_at) < #{cutoff}
            LIMIT #{limit}
            """)
    int deleteExpiredBackfillsBatch(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /** Count terminal backfill batches eligible for cleanup. */
    @Select("""
            SELECT COUNT(*) FROM backfill_batch
            WHERE status IN ('FINISHED', 'CANCELED')
              AND COALESCE(finished_at, created_at) < #{cutoff}
            """)
    long countExpiredBackfills(@Param("cutoff") LocalDateTime cutoff);

    /** Prune old cleanup logs, keeping only the latest {@code keepCount} entries. */
    @Delete("""
            DELETE FROM retention_cleanup_log
            WHERE id NOT IN (
                SELECT id FROM (
                    SELECT id FROM retention_cleanup_log ORDER BY started_at DESC LIMIT #{keepCount}
                ) AS keep_rows
            )
            """)
    int pruneOldLogs(@Param("keepCount") int keepCount);
}
