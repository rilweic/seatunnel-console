package com.dataintegration.console.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/** One logical run; retries are attempts inside it. Table job_instance. */
@Data
@TableName("job_instance")
public class JobInstance {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;
    private String jobDefinitionId;
    private String jobVersionId;
    private String triggerType;
    private LocalDateTime scheduledTime;
    private String status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private String rerunOfInstanceId;
    private String backfillBatchId;
    private String engineType;
    private Integer xxlJobId;
    private Long xxlLogId;
    private String variablesJson;
    private String startupParamsJson;
    private String configMasked;
    private Long readRows;
    private Long writeRows;
    private Long readBytes;
    private Long writeBytes;
    private String errorMessage;
    private String validationStatus;
    private Long sourceCount;
    private Long targetCount;
    private String validationMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
