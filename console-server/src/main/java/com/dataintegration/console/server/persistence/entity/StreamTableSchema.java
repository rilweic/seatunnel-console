package com.dataintegration.console.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/** Last known structure of one table of a streaming job, per side. Table stream_table_schema. */
@Data
@TableName("stream_table_schema")
public class StreamTableSchema {

    private String jobDefinitionId;
    private String tableName;
    /** SOURCE or TARGET */
    private String side;
    private String columnsJson;
    private LocalDateTime capturedAt;
}
