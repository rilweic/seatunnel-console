-- Engine metrics sampled on every poll of a running attempt, for the run's throughput chart
-- (docs/design.md §10.4). Only cumulative counters are kept; rates are derived from consecutive samples.
CREATE TABLE job_metric_sample
(
    id          CHAR(36)    NOT NULL,
    instance_id CHAR(36)    NOT NULL,
    attempt_no  INT         NOT NULL,
    read_rows   BIGINT      NULL,
    write_rows  BIGINT      NULL,
    read_bytes  BIGINT      NULL,
    write_bytes BIGINT      NULL,
    sampled_at  DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_job_metric_sample_instance (instance_id, attempt_no, sampled_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = 'engine counters of a running attempt, one row per poll';
