-- Engine cluster registry (Zeta and Flink) and per-job cluster binding (docs/flink-spark.md §5.3).

CREATE TABLE engine_cluster (
    id              CHAR(36)      NOT NULL,
    name            VARCHAR(100)  NOT NULL,
    engine_type     VARCHAR(16)   NOT NULL COMMENT 'ZETA or FLINK',
    description     VARCHAR(500)  NULL,
    rest_urls       VARCHAR(1000) NOT NULL COMMENT 'comma-separated master URLs for ZETA, or single JobManager REST URL for FLINK',
    username        VARCHAR(100)  NULL COMMENT 'optional basic-auth username',
    password_enc    TEXT          NULL COMMENT 'AES-GCM encrypted password',
    flink_version   VARCHAR(32)   NULL COMMENT 'Flink version, e.g. 1.20.1',
    flink_home      VARCHAR(500)  NULL COMMENT 'local Flink client path on the console host',
    seatunnel_home  VARCHAR(500)  NULL COMMENT 'local SeaTunnel distribution path on the console host',
    java_home       VARCHAR(500)  NULL COMMENT 'optional JAVA_HOME override for Flink CLI',
    is_default      TINYINT(1)    NOT NULL DEFAULT 0,
    created_by      CHAR(36)      NULL,
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at      DATETIME(3)   NULL,
    PRIMARY KEY (id),
    KEY idx_engine_cluster_type (engine_type),
    KEY idx_engine_cluster_default (is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE job_definition
    ADD COLUMN cluster_id CHAR(36) NULL COMMENT 'engine_cluster.id; null falls back to the default cluster' AFTER project_id,
    ADD KEY idx_job_definition_cluster (cluster_id);

ALTER TABLE job_stream
    ADD COLUMN restore_path VARCHAR(1000) NULL COMMENT 'Flink savepoint or retained checkpoint path to restore from' AFTER restore_engine_job_id;
