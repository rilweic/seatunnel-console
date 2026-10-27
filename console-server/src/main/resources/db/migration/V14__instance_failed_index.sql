-- The overview's "最近失败" takes the latest failed runs by finish time; without this index MySQL
-- sorts every failed run ever recorded on each refresh (docs/design.md §10.5).
ALTER TABLE job_instance
    ADD KEY idx_job_instance_status_finished (status, finished_at);
