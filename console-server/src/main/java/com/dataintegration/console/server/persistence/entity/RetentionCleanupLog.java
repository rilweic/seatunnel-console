package com.dataintegration.console.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/** Audit record for each scheduled or manual data retention cleanup run. Table retention_cleanup_log. */
@Data
@TableName("retention_cleanup_log")
public class RetentionCleanupLog {

    @TableId(type = IdType.INPUT)
    private String id;

    private String triggerType;
    private String triggeredBy;
    private String status;
    private Long deletedMetricSamples;
    private Long deletedInstances;
    private Long deletedAttempts;
    private Long deletedEvents;
    private Long deletedBackfills;
    private Long deletedLogFiles;
    private Long deletedLogBytes;
    private Long deletedMessages;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
}
