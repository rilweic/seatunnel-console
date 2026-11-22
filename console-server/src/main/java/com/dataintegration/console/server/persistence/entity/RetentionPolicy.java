package com.dataintegration.console.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/** Singleton data retention policy configured by administrators. Table retention_policy. */
@Data
@TableName("retention_policy")
public class RetentionPolicy {

    public static final int SINGLETON_ID = 1;

    @TableId(type = IdType.INPUT)
    private Integer id;

    private Boolean enabled;
    private Integer runHour;
    private Integer metricRetentionDays;
    private Integer instanceRetentionDays;
    private Integer logRetentionDays;
    private Integer messageRetentionDays;
    private Integer xxlJobId;
    private String syncStatus;
    private String syncError;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
