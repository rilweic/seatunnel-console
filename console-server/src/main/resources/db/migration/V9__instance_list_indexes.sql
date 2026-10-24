ALTER TABLE job_instance
    ADD KEY idx_job_instance_project_created (project_id, created_at),
    ADD KEY idx_job_instance_job_created (job_definition_id, created_at),
    ADD KEY idx_job_instance_started_created (started_at, created_at);
