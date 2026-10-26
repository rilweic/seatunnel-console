-- Real-time (streaming) jobs, docs/design.md §12 P2. A streaming job is a job_definition with
-- job_type STREAMING; each start until it stops or fails is one job_instance, and each automatic
-- restart inside it is an attempt. What belongs to the job itself, not to one of its versions or
-- runs, lives here: restart policy, the replication identity on the source database, the point the
-- next start can restore from, and what the monitor last saw.
CREATE TABLE job_stream
(
    job_definition_id        CHAR(36)     NOT NULL,
    auto_restart             TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'restart from the latest checkpoint after a failure',
    max_restarts             INT          NOT NULL DEFAULT 3,
    restart_interval_seconds INT          NOT NULL DEFAULT 30,
    server_id_start          INT          NULL COMMENT 'MySQL-CDC server-id block (8 ids) owned by this job',
    replication_name         VARCHAR(64)  NULL COMMENT 'PostgreSQL replication slot and publication of this job',
    current_instance_id      CHAR(36)     NULL COMMENT 'the run that is starting or running; null when stopped',
    restore_engine_job_id    VARCHAR(64)  NULL COMMENT 'engine job whose savepoint or checkpoint the next start can restore from',
    restore_kind             VARCHAR(16)  NULL COMMENT 'SAVEPOINT | CHECKPOINT',
    restore_version_id       CHAR(36)     NULL COMMENT 'job version the restore point was taken with',
    restore_blocked          VARCHAR(512) NULL COMMENT 'why restoring would fail again (e.g. a DDL replayed); a fresh start is needed',
    last_read_at             DATETIME(3)  NULL COMMENT 'last time the source read count went up',
    checkpoint_at            DATETIME(3)  NULL COMMENT 'last completed checkpoint',
    checkpoint_problem       VARCHAR(512) NULL COMMENT 'set while checkpoints do not complete; null when healthy',
    pipeline_restores        INT          NOT NULL DEFAULT 0 COMMENT 'engine-side pipeline restores of the running engine job',
    created_at               DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at               DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (job_definition_id),
    UNIQUE KEY uk_job_stream_server_id (server_id_start),
    UNIQUE KEY uk_job_stream_replication (replication_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = 'streaming job settings and live state, 1:1 with job_definition';

-- Table structures a running streaming job started with, compared with the live tables every few
-- minutes to report schema changes (design.md §12, 表结构变更的反馈).
CREATE TABLE stream_table_schema
(
    job_definition_id CHAR(36)     NOT NULL,
    table_name        VARCHAR(255) NOT NULL COMMENT 'source table name as selected in the job',
    side              VARCHAR(8)   NOT NULL COMMENT 'SOURCE | TARGET',
    columns_json      JSON         NOT NULL COMMENT '[{name, type, nullable}] in table order',
    captured_at       DATETIME(3)  NOT NULL,
    PRIMARY KEY (job_definition_id, table_name, side)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = 'last known table structure of a streaming job, per side';
