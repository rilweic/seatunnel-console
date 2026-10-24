package com.dataintegration.console.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/** Batch job schedule, 1:1 with job_definition; the console is the source of truth. Table job_schedule. */
@Data
@TableName("job_schedule")
public class JobSchedule {

    @TableId(type = IdType.INPUT)
    private String jobDefinitionId;

    private String cron;
    private Boolean enabled;
    private Integer failRetryCount;
    private Integer retryIntervalSeconds;
    private Integer timeoutSeconds;
    private String blockStrategy;
    private String misfireStrategy;
    private String syncStatus;
    private String syncError;
    private LocalDateTime syncedAt;
    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
