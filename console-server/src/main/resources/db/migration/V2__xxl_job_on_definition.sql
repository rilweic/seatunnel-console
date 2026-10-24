-- Every batch job owns exactly one XXL-JOB job, created on first run with schedule type NONE
-- (trigger only) and switched to CRON when a schedule is enabled. The id therefore belongs to the
-- job definition, not to job_schedule which only exists once a schedule is configured.
ALTER TABLE job_definition
    ADD COLUMN xxl_job_id INT NULL COMMENT 'XXL-JOB job id; null until the first run or schedule' AFTER current_version_id,
    ADD UNIQUE KEY uk_job_definition_xxl_job (xxl_job_id);

ALTER TABLE job_schedule
    DROP INDEX uk_job_schedule_xxl_job,
    DROP COLUMN xxl_job_id;
