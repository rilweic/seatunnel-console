package com.dataintegration.console.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/** Something a person should know about or act on (docs/design.md §10.7). Table message. */
@Data
@TableName("message")
public class Message {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String category;
    private String level;
    private String title;
    private String content;
    private String ownerId;
    private String jobDefinitionId;
    private String instanceId;
    private String link;
    private String dedupKey;
    private Integer repeatCount;
    private LocalDateTime lastAt;
    private LocalDateTime notifiedAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
}
