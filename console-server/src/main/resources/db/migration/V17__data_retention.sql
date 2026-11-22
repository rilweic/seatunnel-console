-- Historical data retention policy and cleanup execution history.

CREATE TABLE retention_policy
(
    id                      TINYINT     NOT NULL COMMENT 'singleton row, always 1',
    enabled                 TINYINT(1)  NOT NULL DEFAULT 1 COMMENT 'whether daily scheduled cleanup is enabled',
    run_hour                TINYINT     NOT NULL DEFAULT 3 COMMENT 'hour of day (0-23) to run daily cleanup',
    metric_retention_days   INT         NOT NULL DEFAULT 14 COMMENT 'job_metric_sample retention in days; 0 = keep forever',
    instance_retention_days INT         NOT NULL DEFAULT 90 COMMENT 'finished job_instance/attempt/event/backfill retention in days; 0 = keep forever',
    log_retention_days      INT         NOT NULL DEFAULT 30 COMMENT 'archived log files retention in days; 0 = keep forever',
    message_retention_days  INT         NOT NULL DEFAULT 90 COMMENT 'message retention in days (open alerts are never deleted); 0 = keep forever',
    updated_by              CHAR(36)    NULL,
    updated_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = 'singleton data retention policy configured by administrators';

INSERT INTO retention_policy (id, enabled, run_hour, metric_retention_days, instance_retention_days, log_retention_days, message_retention_days)
VALUES (1, 1, 3, 14, 90, 30, 90);

CREATE TABLE retention_cleanup_log
(
    id                     CHAR(36)      NOT NULL,
    trigger_type           VARCHAR(16)   NOT NULL COMMENT 'SCHEDULED | MANUAL',
    triggered_by           CHAR(36)      NULL COMMENT 'admin user id for MANUAL; null for SCHEDULED',
    status                 VARCHAR(16)   NOT NULL COMMENT 'SUCCESS | FAILED',
    deleted_metric_samples BIGINT        NOT NULL DEFAULT 0,
    deleted_instances      BIGINT        NOT NULL DEFAULT 0,
    deleted_attempts       BIGINT        NOT NULL DEFAULT 0,
    deleted_events         BIGINT        NOT NULL DEFAULT 0,
    deleted_backfills      BIGINT        NOT NULL DEFAULT 0,
    deleted_log_files      BIGINT        NOT NULL DEFAULT 0,
    deleted_log_bytes      BIGINT        NOT NULL DEFAULT 0,
    deleted_messages       BIGINT        NOT NULL DEFAULT 0,
    error_message          VARCHAR(1024) NULL,
    started_at             DATETIME(3)   NOT NULL,
    finished_at            DATETIME(3)   NOT NULL,
    duration_ms            BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_retention_cleanup_started (started_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = 'audit log of scheduled and manual retention cleanups';

-- Support fast time-range cleanup without full table scans.
ALTER TABLE job_metric_sample
    ADD KEY idx_job_metric_sample_sampled_at (sampled_at);

ALTER TABLE backfill_batch
    ADD KEY idx_backfill_batch_status_time (status, finished_at, created_at);
