package com.dataintegration.console.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/** Engine counters of a running attempt at one poll. Table job_metric_sample. */
@Data
@TableName("job_metric_sample")
public class JobMetricSample {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String instanceId;
    private Integer attemptNo;
    private Long readRows;
    private Long writeRows;
    private Long readBytes;
    private Long writeBytes;
    private LocalDateTime sampledAt;
}
