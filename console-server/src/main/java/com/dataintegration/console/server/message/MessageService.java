package com.dataintegration.console.server.message;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.common.PageResult;
import com.dataintegration.console.server.auth.ConsoleUser;
import com.dataintegration.console.server.auth.CurrentUser;
import com.dataintegration.console.server.persistence.entity.Message;
import com.dataintegration.console.server.persistence.entity.MessageRead;
import com.dataintegration.console.server.persistence.mapper.MessageMapper;
import com.dataintegration.console.server.persistence.mapper.MessageReadMapper;
import com.dataintegration.console.server.web.Pages;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The current user's messages (docs/design.md §10.7): an admin sees all of them, anyone else the
 * messages about their own jobs. A message is unread until read after it last asked for attention (notified_at).
 */
@Service
public class MessageService {

    public record MessageView(
            String id,
            String category,
            String level,
            String title,
            String content,
            String jobId,
            String instanceId,
            String link,
            int repeatCount,
            LocalDateTime createdAt,
            LocalDateTime lastAt,
            boolean resolvable,
            LocalDateTime resolvedAt,
            boolean read) {}

    /** @param unresolved open problems only; levels and categories are exact values, null = any */
    public record Filter(boolean unread, boolean unresolved, String level, String category) {}

    static final int UNREAD_COUNT_LIMIT = 100;

    private static final String UNREAD =
            "select 1 from message_read r where r.message_id = message.id and r.user_id = {0} and r.read_at >= message.notified_at";

    private final MessageMapper messages;
    private final MessageReadMapper reads;

    public MessageService(MessageMapper messages, MessageReadMapper reads) {
        this.messages = messages;
        this.reads = reads;
    }

    public PageResult<MessageView> list(Filter filter, Integer page, Integer size) {
        ConsoleUser user = user();
        LambdaQueryWrapper<Message> q = visible(user)
                .eq(filter.level() != null, Message::getLevel, filter.level())
                .eq(filter.category() != null, Message::getCategory, filter.category())
                .orderByDesc(Message::getLastAt);
        if (filter.unread()) {
            q.notExists(UNREAD, user.id());
        }
        if (filter.unresolved()) {
            q.isNotNull(Message::getDedupKey).isNull(Message::getResolvedAt);
        }
        Page<Message> result = messages.selectPage(Pages.of(page, size), q);
        Map<String, LocalDateTime> readAt = readAt(user.id(), result.getRecords().stream().map(Message::getId).toList());
        List<MessageView> views = result.getRecords().stream().map(m -> view(m, readAt.get(m.getId()))).toList();
        return new PageResult<>(views, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** At most {@link #UNREAD_COUNT_LIMIT}: the bell shows "99+" from 100 on. */
    public long unreadCount() {
        ConsoleUser user = user();
        return messages.countUnread(user.id(), user.admin() ? null : user.id(), UNREAD_COUNT_LIMIT);
    }

    public void markRead(String id) {
        ConsoleUser user = user();
        Message message = messages.selectById(id);
        if (message == null || !user.admin() && !user.id().equals(message.getOwnerId())) {
            throw new ConsoleException(ErrorCodes.NOT_FOUND, "消息不存在：" + id);
        }
        reads.markRead(id, user.id(), LocalDateTime.now());
    }

    public void markAllRead() {
        ConsoleUser user = user();
        reads.markAllRead(user.id(), user.admin() ? null : user.id(), LocalDateTime.now());
    }

    private static LambdaQueryWrapper<Message> visible(ConsoleUser user) {
        return new LambdaQueryWrapper<Message>().eq(!user.admin(), Message::getOwnerId, user.id());
    }

    private Map<String, LocalDateTime> readAt(String userId, List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return reads.selectList(new LambdaQueryWrapper<MessageRead>()
                        .eq(MessageRead::getUserId, userId)
                        .in(MessageRead::getMessageId, ids))
                .stream()
                .collect(Collectors.toMap(MessageRead::getMessageId, MessageRead::getReadAt));
    }

    private static MessageView view(Message m, LocalDateTime readAt) {
        return new MessageView(m.getId(), m.getCategory(), m.getLevel(), m.getTitle(), m.getContent(), m.getJobDefinitionId(),
                m.getInstanceId(), m.getLink(), m.getRepeatCount() == null ? 1 : m.getRepeatCount(), m.getCreatedAt(), m.getLastAt(),
                m.getDedupKey() != null, m.getResolvedAt(), readAt != null && !readAt.isBefore(m.getNotifiedAt()));
    }

    private static ConsoleUser user() {
        return CurrentUser.get().orElseThrow(() -> new ConsoleException(ErrorCodes.UNAUTHENTICATED, "未登录"));
    }
}
