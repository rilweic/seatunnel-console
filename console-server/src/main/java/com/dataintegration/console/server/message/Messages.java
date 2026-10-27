package com.dataintegration.console.server.message;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataintegration.console.common.UuidV7;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.persistence.entity.Message;
import com.dataintegration.console.server.persistence.entity.MessageRead;
import com.dataintegration.console.server.persistence.mapper.JobDefinitionMapper;
import com.dataintegration.console.server.persistence.mapper.MessageMapper;
import com.dataintegration.console.server.persistence.mapper.MessageReadMapper;
import com.dataintegration.console.server.run.RunRecorder.Level;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * Writes messages (docs/design.md §10.7) where runs, real-time jobs, backfills and the cluster
 * watcher reach an outcome. Called from scheduler threads, so raising and resolving never throw: a
 * message that cannot be written is logged and the run carries on.
 */
@Component
public class Messages {

    private static final Logger log = LoggerFactory.getLogger(Messages.class);

    private static final int MAX_CONTENT_CHARS = 4000;
    static final Duration REOPEN_WITHIN = Duration.ofHours(1);
    static final Duration RENOTIFY_EVERY = Duration.ofHours(1);

    private final MessageMapper messages;
    private final MessageReadMapper reads;
    private final JobDefinitionMapper jobs;
    private final Duration retention;

    public Messages(MessageMapper messages, MessageReadMapper reads, JobDefinitionMapper jobs,
            @Value("${console.message.retention:90d}") Duration retention) {
        this.messages = messages;
        this.reads = reads;
        this.jobs = jobs;
        this.retention = retention;
    }

    /**
     * A message about a job, for its creator and the admins; nothing when the job is gone.
     *
     * @param subject what the message is about for merging (usually the job id), null = never merged
     * @param what what happened, after the job's name, e.g. "运行失败"
     */
    public void job(MessageCategory category, Level level, String jobId, String instanceId, String link, String subject, String what,
            String content) {
        try {
            JobDefinition job = jobs.selectById(jobId);
            if (job == null) {
                return;
            }
            Message m = message(category, level, "「" + job.getName() + "」" + what, content, link, subject);
            m.setOwnerId(job.getCreatedBy());
            m.setJobDefinitionId(jobId);
            m.setInstanceId(instanceId);
            write(m);
        } catch (RuntimeException e) {
            log.warn("Could not record a {} message for job {}: {}", category, jobId, e.toString());
        }
    }

    /**
     * A message for the admins only, about the console or the cluster.
     *
     * @return false when it could not be written, for a caller that tries again later
     */
    public boolean system(MessageCategory category, Level level, String link, String subject, String title, String content) {
        try {
            write(message(category, level, title, content, link, subject));
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not record a {} message: {}", category, e.toString());
            return false;
        }
    }

    /**
     * Merges into the open message with the same key, or into one resolved within the last
     * {@link #REOPEN_WITHIN}, else records a new one. A merged occurrence makes the message unread
     * again at most every {@link #RENOTIFY_EVERY}, so a job failing every few minutes does not keep
     * lighting the bell up after each read (docs/design.md §10.7).
     */
    private void write(Message m) {
        LocalDateTime renotifyBefore = m.getLastAt().minus(RENOTIFY_EVERY);
        if (m.getDedupKey() != null) {
            try {
                if (messages.reopen(m, renotifyBefore, m.getLastAt().minus(REOPEN_WITHIN)) > 0) {
                    return;
                }
            } catch (DuplicateKeyException e) {
                // an open message exists already; the upsert counts it there
            }
        }
        messages.upsert(m, renotifyBefore);
    }

    /**
     * Marks the open messages of these categories about {@code subject} as resolved.
     *
     * @return false when it could not be written, for a caller that tries again later
     */
    public boolean resolve(String subject, MessageCategory... categories) {
        try {
            messages.resolve(Arrays.stream(categories).map(c -> c.key(subject)).toList(), LocalDateTime.now());
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not resolve {} messages of {}: {}", Arrays.toString(categories), subject, e.toString());
            return false;
        }
    }

    /** Drops a deleted job's messages, in the caller's transaction. */
    public void forgetJob(String jobId) {
        delete(() -> new LambdaQueryWrapper<Message>().eq(Message::getJobDefinitionId, jobId));
    }

    /**
     * Drops messages that last happened longer ago than the retention, except open problems: an
     * outage that outlasts the retention is reported once and must stay until it clears.
     */
    @Scheduled(cron = "0 40 3 * * *")
    public void purge() {
        LocalDateTime cutoff = LocalDateTime.now().minus(retention);
        int purged = delete(() -> new LambdaQueryWrapper<Message>()
                .lt(Message::getLastAt, cutoff)
                .and(w -> w.isNull(Message::getDedupKey).or().isNotNull(Message::getResolvedAt)));
        if (purged > 0) {
            log.info("Purged {} message(s) older than {} days", purged, retention.toDays());
        }
    }

    /**
     * The delete repeats the condition, so a message that happened again since it was selected
     * stays; read marks go only with the messages actually deleted.
     */
    private int delete(Supplier<LambdaQueryWrapper<Message>> which) {
        List<String> ids = messages.selectList(which.get().select(Message::getId)).stream().map(Message::getId).toList();
        if (ids.isEmpty()) {
            return 0;
        }
        int deleted = messages.delete(which.get().in(Message::getId, ids));
        reads.delete(new LambdaQueryWrapper<MessageRead>()
                .in(MessageRead::getMessageId, ids)
                .notExists("select 1 from message where message.id = message_read.message_id"));
        return deleted;
    }

    private static Message message(MessageCategory category, Level level, String title, String content, String link, String subject) {
        Message m = new Message();
        m.setId(UuidV7.next());
        m.setCategory(category.name());
        m.setLevel(level.name());
        m.setTitle(title.length() > 255 ? title.substring(0, 255) : title);
        String text = content == null ? "" : content;
        m.setContent(text.length() > MAX_CONTENT_CHARS ? text.substring(0, MAX_CONTENT_CHARS) + "…" : text);
        m.setLink(link);
        m.setDedupKey(subject == null ? null : category.key(subject));
        m.setLastAt(LocalDateTime.now());
        return m;
    }
}
