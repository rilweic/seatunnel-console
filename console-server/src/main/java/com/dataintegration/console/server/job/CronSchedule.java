package com.dataintegration.console.server.job;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;

import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Cron expressions as XXL-JOB accepts them (Quartz syntax: second minute hour day-of-month month
 * day-of-week). Checked locally so the user gets the error and a preview before anything is sent
 * to XXL-JOB; the optional Quartz year field is not supported.
 */
final class CronSchedule {

    /** Batch jobs run at most this often (docs/design.md §5.2); a cron that fires faster is a mistake. */
    static final Duration MIN_INTERVAL = Duration.ofMinutes(2);

    /** Fire times looked at for the interval check, within a year from now. */
    private static final int INTERVAL_SAMPLES = 2000;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    private CronSchedule() {}

    static CronExpression parse(String cron) {
        String trimmed = cron == null ? "" : cron.trim();
        String[] fields = trimmed.split("\\s+");
        if (fields.length != 6) {
            throw invalid("cron 需要 6 段：秒 分 时 日 月 周，例如 0 0 2 * * ?");
        }
        // Quartz (and so XXL-JOB) requires exactly one of the two day fields to be '?'.
        if ("?".equals(fields[3]) == "?".equals(fields[5])) {
            throw invalid("日和周两段中必须有且只有一个是 ?，例如 0 0 2 * * ? 或 0 0 2 ? * MON");
        }
        try {
            return CronExpression.parse(trimmed);
        } catch (IllegalArgumentException e) {
            throw invalid("cron 无效：" + e.getMessage());
        }
    }

    /**
     * A cron for a schedule: valid, and never firing twice within {@link #MIN_INTERVAL}. The fire
     * times of the coming year are sampled, so a cron that only crowds its runs on some days (e.g.
     * every minute during 02:00) is caught too.
     */
    static CronExpression parseSchedule(String cron, LocalDateTime from) {
        CronExpression expression = parse(cron);
        LocalDateTime until = from.plusYears(1);
        LocalDateTime previous = expression.next(from);
        for (int i = 0; previous != null && previous.isBefore(until) && i < INTERVAL_SAMPLES; i++) {
            LocalDateTime next = expression.next(previous);
            if (next == null) {
                break;
            }
            if (Duration.between(previous, next).compareTo(MIN_INTERVAL) < 0) {
                throw invalid("两次运行至少要间隔 " + MIN_INTERVAL.toMinutes() + " 分钟，这个 cron 会在 " + previous.format(TIME)
                        + " 和 " + next.format(TIME) + " 连续运行");
            }
            previous = next;
        }
        return expression;
    }

    static List<LocalDateTime> nextFireTimes(String cron, LocalDateTime from, int count) {
        CronExpression expression = parse(cron);
        List<LocalDateTime> times = new ArrayList<>(count);
        LocalDateTime next = from;
        while (times.size() < count && (next = expression.next(next)) != null) {
            times.add(next);
        }
        return times;
    }

    /**
     * The last fire time strictly before {@code time}, looking back up to a year (Spring's cron has
     * no reverse search). Null when the cron did not fire in that year.
     */
    static LocalDateTime previous(CronExpression expression, LocalDateTime time) {
        for (Duration back = Duration.ofMinutes(1); back.compareTo(Duration.ofDays(400)) <= 0; back = back.multipliedBy(2)) {
            LocalDateTime candidate = expression.next(time.minus(back));
            if (candidate == null || !candidate.isBefore(time)) {
                continue;
            }
            LocalDateTime next;
            while ((next = expression.next(candidate)) != null && next.isBefore(time)) {
                candidate = next;
            }
            return candidate;
        }
        return null;
    }

    private static ConsoleException invalid(String message) {
        return new ConsoleException(ErrorCodes.INVALID_ARGUMENT, message);
    }
}
