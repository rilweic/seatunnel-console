package com.dataintegration.console.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/** Job identity; content lives in JobVersion. Table job_definition. */
@Data
@TableName("job_definition")
public class JobDefinition {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String projectId;
    private String name;
    private String jobType;
    private String editMode;
    private String currentVersionId;
    private Integer xxlJobId;
    /** End of the last successful sync window (docs/design.md §7.5); null = from the beginning. */
    private LocalDateTime watermark;
    private Integer windowLagSeconds;
    private String description;
    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic(value = "null", delval = "now(3)")
    private LocalDateTime deletedAt;
}
