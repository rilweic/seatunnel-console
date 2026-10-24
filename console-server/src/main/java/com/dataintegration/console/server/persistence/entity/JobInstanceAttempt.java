package com.dataintegration.console.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/** One engine submission with its own engine job id and log. Table job_instance_attempt. */
@Data
@TableName("job_instance_attempt")
public class JobInstanceAttempt {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String instanceId;
    private Integer attemptNo;
    private String engineJobId;
    private String engineStatus;
    private String metricsJson;
    private String errorMessage;
    private String logStorageKey;
    private Long logSizeBytes;
    private Boolean logTruncated;
    private LocalDateTime logArchivedAt;
    private LocalDateTime submittedAt;
    private LocalDateTime finishedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
