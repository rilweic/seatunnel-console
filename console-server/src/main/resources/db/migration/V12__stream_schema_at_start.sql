ALTER TABLE stream_table_schema
    ADD COLUMN start_columns_json JSON NULL COMMENT 'structure when the latest start began; the detail page shows changes since'
        AFTER columns_json;
