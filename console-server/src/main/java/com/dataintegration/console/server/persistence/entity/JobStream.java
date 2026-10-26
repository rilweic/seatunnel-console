package com.dataintegration.console.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/** Settings and live state of a streaming job, 1:1 with job_definition. Table job_stream. */
@Data
@TableName("job_stream")
public class JobStream {

    @TableId(type = IdType.INPUT)
    private String jobDefinitionId;

    private Boolean autoRestart;
    private Integer maxRestarts;
    private Integer restartIntervalSeconds;
    private Integer serverIdStart;
    private String replicationName;
    private String currentInstanceId;
    private String restoreEngineJobId;
    private String restoreKind;
    private String restoreVersionId;
    private String restoreBlocked;
    private LocalDateTime lastReadAt;
    private LocalDateTime checkpointAt;
    private String checkpointProblem;
    private Integer pipelineRestores;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
