-- Continuous incremental sync (docs/design.md §7.5): a job remembers how far its windowed runs
-- got, and every run that uses the window records the one it read, so reruns repeat it exactly.
ALTER TABLE job_definition
    ADD COLUMN watermark          DATETIME(3) NULL COMMENT 'end of the last successful sync window; null = from the beginning' AFTER xxl_job_id,
    ADD COLUMN window_lag_seconds INT         NOT NULL DEFAULT 60 COMMENT 'a window ends this long before the scheduled time, for late commits' AFTER watermark;

ALTER TABLE job_instance
    ADD COLUMN window_start DATETIME(3) NULL COMMENT 'sync window start; null with window_end set = from the beginning' AFTER scheduled_time,
    ADD COLUMN window_end   DATETIME(3) NULL COMMENT 'sync window end (exclusive); null = the run does not use the window' AFTER window_start;
