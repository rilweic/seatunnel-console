package com.dataintegration.console.server.job;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;

import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Cron expressions as XXL-JOB accepts them (Quartz syntax: second minute hour day-of-month month
 * day-of-week). Checked locally so the user gets the error and a preview before anything is sent
 * to XXL-JOB; the optional Quartz year field is not supported.
 */
final class CronSchedule {

    private CronSchedule() {}

    static CronExpression parse(String cron) {
        String trimmed = cron == null ? "" : cron.trim();
        String[] fields = trimmed.split("\\s+");
        if (fields.length != 6) {
            throw invalid("cron needs 6 fields: second minute hour day-of-month month day-of-week, e.g. 0 0 2 * * ?");
        }
        // Quartz (and so XXL-JOB) requires exactly one of the two day fields to be '?'.
        if ("?".equals(fields[3]) == "?".equals(fields[5])) {
            throw invalid("exactly one of day-of-month and day-of-week must be '?', e.g. 0 0 2 * * ? or 0 0 2 ? * MON");
        }
        try {
            return CronExpression.parse(trimmed);
        } catch (IllegalArgumentException e) {
            throw invalid("invalid cron: " + e.getMessage());
        }
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

    private static ConsoleException invalid(String message) {
        return new ConsoleException(ErrorCodes.INVALID_ARGUMENT, message);
    }
}
