-- Accounts (docs/design.md §2): users created by an admin must replace their initial password.
ALTER TABLE sys_user
    ADD COLUMN must_change_password TINYINT(1)  NOT NULL DEFAULT 0 COMMENT 'set for new and reset accounts' AFTER enabled,
    ADD COLUMN password_changed_at  DATETIME(3) NULL AFTER must_change_password;

-- Ownership: users may only change what they created, so every owned row needs a creator.
-- Rows created before accounts existed belong to the first administrator.
UPDATE project
SET created_by = (SELECT id FROM (SELECT id FROM sys_user WHERE role = 'ADMIN' ORDER BY created_at LIMIT 1) first_admin)
WHERE created_by IS NULL;
UPDATE datasource
SET created_by = (SELECT id FROM (SELECT id FROM sys_user WHERE role = 'ADMIN' ORDER BY created_at LIMIT 1) first_admin)
WHERE created_by IS NULL;
UPDATE job_definition
SET created_by = (SELECT id FROM (SELECT id FROM sys_user WHERE role = 'ADMIN' ORDER BY created_at LIMIT 1) first_admin)
WHERE created_by IS NULL;
