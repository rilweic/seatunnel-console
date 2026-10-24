package com.dataintegration.console.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/** Timeline entry of a run. Table job_instance_event. */
@Data
@TableName("job_instance_event")
public class JobInstanceEvent {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String instanceId;
    private Integer attemptNo;
    private String eventType;
    private String level;
    private String message;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
