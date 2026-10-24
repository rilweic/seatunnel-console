-- Startup params are kept on their own: variables_json is replaced by the final values of all
-- variables once a run starts, and a rerun must reuse only what the user typed.
ALTER TABLE job_instance
    ADD COLUMN startup_params_json JSON NULL COMMENT 'startup params given for this run, highest priority' AFTER variables_json;

-- XXL-JOB runs the triggers of one job one at a time (one executor thread per job), so a backfill
-- is paced by the console: the next scheduled time is triggered only when the job has no active run.
ALTER TABLE backfill_batch
    MODIFY COLUMN status VARCHAR(16) NOT NULL COMMENT 'RUNNING | PAUSED | FINISHED | CANCELED',
    ADD COLUMN job_version_id CHAR(36) NOT NULL COMMENT 'version every run of the batch uses: the current one at creation' AFTER job_definition_id,
    ADD COLUMN cron VARCHAR(64) NULL COMMENT 'schedule cron at creation; null = one run per day at 00:00:00' AFTER end_time,
    ADD COLUMN startup_params_json JSON NULL COMMENT 'startup params applied to every run' AFTER cron,
    ADD COLUMN next_index INT NOT NULL DEFAULT 0 COMMENT 'index of the next scheduled time to trigger' AFTER total_runs,
    ADD COLUMN error_message VARCHAR(1024) NULL COMMENT 'why the batch is PAUSED' AFTER status;
