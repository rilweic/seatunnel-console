package com.dataintegration.console.server.persistence.mapper;

import com.dataintegration.console.server.persistence.entity.MessageRead;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

public interface MessageReadMapper extends BaseMapper<MessageRead> {

    @Insert("INSERT INTO message_read (message_id, user_id, read_at) VALUES (#{messageId}, #{userId}, #{at}) "
            + "ON DUPLICATE KEY UPDATE read_at = #{at}")
    int markRead(@Param("messageId") String messageId, @Param("userId") String userId, @Param("at") LocalDateTime at);

    /** Marks every message the user sees and has not read since it last asked for attention; {@code ownerId} null = all (an admin). */
    @Insert("<script>INSERT INTO message_read (message_id, user_id, read_at) "
            + "SELECT m.id, #{userId}, #{at} FROM message m WHERE m.notified_at &lt;= #{at} "
            + "<if test='ownerId != null'>AND m.owner_id = #{ownerId} </if>"
            + "AND NOT EXISTS (SELECT 1 FROM message_read r WHERE r.message_id = m.id AND r.user_id = #{userId} AND r.read_at &gt;= m.notified_at) "
            + "ON DUPLICATE KEY UPDATE read_at = #{at}</script>")
    int markAllRead(@Param("userId") String userId, @Param("ownerId") String ownerId, @Param("at") LocalDateTime at);
}
