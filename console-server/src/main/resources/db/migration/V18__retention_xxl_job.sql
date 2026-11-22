-- Track the XXL-JOB task that drives scheduled historical data retention cleanup.

ALTER TABLE retention_policy
    ADD COLUMN xxl_job_id  INT          NULL COMMENT 'synced xxl-job-admin job id when XXL-JOB is enabled' AFTER message_retention_days,
    ADD COLUMN sync_status VARCHAR(16)  NULL COMMENT 'SYNCED | FAILED | LOCAL' AFTER xxl_job_id,
    ADD COLUMN sync_error  VARCHAR(500) NULL COMMENT 'last XXL-JOB sync failure reason' AFTER sync_status;
