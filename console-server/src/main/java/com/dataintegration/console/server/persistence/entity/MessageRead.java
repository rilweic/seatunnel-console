package com.dataintegration.console.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/** When a user last read a message. Table message_read. */
@Data
@TableName("message_read")
public class MessageRead {

    private String messageId;
    private String userId;
    private LocalDateTime readAt;
}
