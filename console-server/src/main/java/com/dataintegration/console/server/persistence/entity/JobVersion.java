package com.dataintegration.console.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/** Immutable job version. Table job_version. */
@Data
@TableName("job_version")
public class JobVersion {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String jobDefinitionId;
    private Integer versionNo;
    private String formJson;
    private String hoconTemplate;
    private String paramsJson;
    private String validationJson;
    private String seatunnelVersion;
    private String remark;
    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
