package com.dataintegration.console.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/** Datasource; secrets only in secretCipher (AES-GCM). Table datasource. */
@Data
@TableName("datasource")
public class DatasourceEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String name;
    private String type;
    private String description;
    private String configJson;
    private String secretCipher;
    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic(value = "null", delval = "now(3)")
    private LocalDateTime deletedAt;
}
