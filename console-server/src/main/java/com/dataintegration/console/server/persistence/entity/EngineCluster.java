package com.dataintegration.console.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/** Registered execution engine cluster (Zeta or Flink). Table engine_cluster. */
@Data
@TableName("engine_cluster")
public class EngineCluster {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String name;
    private String engineType;
    private String description;
    private String restUrls;
    private String username;
    private String passwordEnc;
    private String flinkVersion;
    private String flinkHome;
    private String seatunnelHome;
    private String javaHome;
    private Boolean isDefault;
    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic(value = "null", delval = "now(3)")
    private LocalDateTime deletedAt;
}
