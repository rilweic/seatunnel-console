-- Continuous incremental sync (docs/design.md §7.5): counts the times the watermark was set by hand,
-- so a run that read the job before a reset never moves the watermark afterwards, even when the
-- reset put it back at the value the run started from.
ALTER TABLE job_definition
    ADD COLUMN watermark_gen INT NOT NULL DEFAULT 0 COMMENT 'bumped on every manual watermark change' AFTER watermark;
