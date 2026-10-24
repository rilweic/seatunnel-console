-- Row-count validation belongs to the version, like the config it checks (docs/design.md §10.3).
ALTER TABLE job_version
    ADD COLUMN validation_json JSON NULL COMMENT 'row-count validation spec, see ValidationSpec' AFTER params_json;
