package com.dataintegration.console.server.persistence.mapper;

import com.dataintegration.console.server.persistence.entity.Message;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;

public interface MessageMapper extends BaseMapper<Message> {

    /** One more occurrence: the latest is the content; unread again only if the last nudge was before {@code renotifyBefore}. */
    String OCCURRED = "level = #{m.level}, title = #{m.title}, content = #{m.content}, instance_id = #{m.instanceId}, link = #{m.link}, "
            + "repeat_count = repeat_count + 1, last_at = #{m.lastAt}, "
            + "notified_at = IF(notified_at &lt; #{renotifyBefore}, #{m.lastAt}, notified_at)";

    /**
     * Records a message, or, when an unresolved one with the same dedup key exists (the unique
     * open_key), counts one more occurrence on it. The update repeats the parameters rather than
     * VALUES(), which MySQL 8.0.20+ deprecates.
     */
    @Insert("<script>INSERT INTO message (id, category, level, title, content, owner_id, job_definition_id, instance_id, link, dedup_key, "
            + "repeat_count, last_at, notified_at, created_at) VALUES (#{m.id}, #{m.category}, #{m.level}, #{m.title}, #{m.content}, "
            + "#{m.ownerId}, #{m.jobDefinitionId}, #{m.instanceId}, #{m.link}, #{m.dedupKey}, 1, #{m.lastAt}, #{m.lastAt}, #{m.lastAt}) "
            + "ON DUPLICATE KEY UPDATE " + OCCURRED + "</script>")
    int upsert(@Param("m") Message message, @Param("renotifyBefore") LocalDateTime renotifyBefore);

    /**
     * Opens again the latest message with the same dedup key resolved since {@code resolvedSince},
     * as one more occurrence: a problem that comes back soon after it cleared (a flapping cluster,
     * a job failing again after one good run) stays one message. 0 when there is none; a duplicate
     * key error when an open one exists already.
     */
    @Update("<script>UPDATE message SET resolved_at = NULL, " + OCCURRED + " "
            + "WHERE dedup_key = #{m.dedupKey} AND resolved_at &gt;= #{resolvedSince} ORDER BY resolved_at DESC LIMIT 1</script>")
    int reopen(@Param("m") Message message, @Param("renotifyBefore") LocalDateTime renotifyBefore,
            @Param("resolvedSince") LocalDateTime resolvedSince);

    /**
     * Unread messages the user sees, counted up to {@code limit}: the bell shows "99+" beyond that,
     * so the count costs the same however many messages an admin has. {@code ownerId} null = all.
     */
    @Select("<script>SELECT COUNT(*) FROM (SELECT 1 FROM message m WHERE 1 = 1 "
            + "<if test='ownerId != null'>AND m.owner_id = #{ownerId} </if>"
            + "AND NOT EXISTS (SELECT 1 FROM message_read r WHERE r.message_id = m.id AND r.user_id = #{userId} AND r.read_at &gt;= m.notified_at) "
            + "LIMIT #{limit}) unread</script>")
    long countUnread(@Param("userId") String userId, @Param("ownerId") String ownerId, @Param("limit") int limit);

    @Update("<script>UPDATE message SET resolved_at = #{at} WHERE open_key IN "
            + "<foreach collection='keys' item='k' open='(' separator=',' close=')'>#{k}</foreach></script>")
    int resolve(@Param("keys") Collection<String> keys, @Param("at") LocalDateTime at);
}
