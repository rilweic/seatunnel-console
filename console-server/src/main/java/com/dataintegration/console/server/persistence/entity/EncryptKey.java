package com.dataintegration.console.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/** Field encryption key; the key only in keyCipher (AES-GCM with the console key). Table encrypt_key. */
@Data
@TableName("encrypt_key")
public class EncryptKey {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String name;
    private String description;
    private String algorithm;
    private Integer keyBits;
    private String keyCipher;
    private Boolean enabled;
    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
