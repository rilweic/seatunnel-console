package com.dataintegration.console.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/** One backfill request; its runs are job_instance rows with backfill_batch_id. Table backfill_batch. */
@Data
@TableName("backfill_batch")
public class BackfillBatch {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String jobDefinitionId;
    private String jobVersionId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String cron;
    private String startupParamsJson;
    private Integer parallelism;
    private Boolean useLatestVersion;
    private Integer totalRuns;
    private Integer nextIndex;
    private String status;
    private String errorMessage;
    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private LocalDateTime finishedAt;
}
