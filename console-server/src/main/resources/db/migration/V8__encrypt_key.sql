-- Keys for the wizard's field encryption (docs/design.md §7.11). The key itself is stored only
-- encrypted with CONSOLE_SECRET_KEY, like datasource secrets; jobs refer to it by id and it is put
-- into the config in memory at submit time. Keys are never deleted, only disabled, so data
-- encrypted with them can still be traced to the key.
CREATE TABLE encrypt_key
(
    id          CHAR(36)     NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(512) NULL,
    algorithm   VARCHAR(16)  NOT NULL COMMENT 'AES_GCM | AES_CBC',
    key_bits    INT          NOT NULL COMMENT '128 | 192 | 256',
    key_cipher  VARCHAR(512) NOT NULL COMMENT 'Base64 key, encrypted with CONSOLE_SECRET_KEY',
    enabled     TINYINT(1)   NOT NULL DEFAULT 1,
    created_by  CHAR(36)     NULL,
    created_at  DATETIME(3)  NOT NULL,
    updated_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_encrypt_key_name (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = 'field encryption keys';
