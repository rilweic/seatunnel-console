-- In-console messages (docs/design.md §10.7): what someone should know about or act on, gathered
-- from runs, real-time jobs, backfills and the cluster.
CREATE TABLE message
(
    id                CHAR(36)      NOT NULL,
    category          VARCHAR(32)   NOT NULL COMMENT 'RUN_FAILED | VALIDATION | BACKFILL | STREAM_FAILED | STREAM_UNSTABLE | CHECKPOINT | SCHEMA_CHANGE | CLUSTER',
    level             VARCHAR(8)    NOT NULL COMMENT 'INFO | WARN | ERROR',
    title             VARCHAR(255)  NOT NULL,
    content           TEXT          NOT NULL,
    owner_id          CHAR(36)      NULL COMMENT 'recipient besides the admins: the job creator; null = admins only',
    job_definition_id CHAR(36)      NULL,
    instance_id       CHAR(36)      NULL,
    link              VARCHAR(255)  NULL COMMENT 'UI path the message opens',
    dedup_key         VARCHAR(128)  NULL COMMENT 'same key while unresolved = same message; null = never resolved',
    open_key          VARCHAR(128) AS (IF(resolved_at IS NULL, dedup_key, NULL)) STORED,
    repeat_count      INT           NOT NULL DEFAULT 1,
    last_at           DATETIME(3)   NOT NULL,
    notified_at       DATETIME(3)   NOT NULL COMMENT 'unread for whoever read it before this; a repeat moves it at most once an hour',
    resolved_at       DATETIME(3)   NULL,
    created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_open_key (open_key),
    KEY idx_message_owner_last (owner_id, last_at),
    KEY idx_message_last (last_at),
    KEY idx_message_job (job_definition_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = 'messages shown under the header bell and in the message center';

CREATE TABLE message_read
(
    message_id CHAR(36)    NOT NULL,
    user_id    CHAR(36)    NOT NULL,
    read_at    DATETIME(3) NOT NULL,
    PRIMARY KEY (message_id, user_id),
    KEY idx_message_read_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = 'who read which message when; read before notified_at counts as unread';
