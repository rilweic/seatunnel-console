package com.dataintegration.console.server.job;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates the body of a DolphinScheduler-style time placeholder {@code $[...]} against the
 * run's scheduled time (docs/design.md §8):
 *
 * <ul>
 *   <li>{@code yyyyMMdd}, {@code yyyy-MM-dd HH:mm:ss}: the scheduled time in that format
 *   <li>{@code yyyyMMdd-1}, {@code yyyyMMdd+7*1}, {@code HHmmss-1/24}, {@code yyyyMMddHHmm-1/24/60}:
 *       shifted by N days, where N may use * and / (so 1/24 is an hour)
 *   <li>{@code add_months(yyyyMMdd,N)}, {@code this_day(fmt)}, {@code last_day(fmt)} (the day before)
 *   <li>{@code month_first_day(fmt,N)}, {@code month_last_day(fmt,N)}: N months away
 *   <li>{@code week_first_day(fmt,N)}, {@code week_last_day(fmt,N)}: Monday / Sunday, N weeks away
 *   <li>{@code year_week(fmt[,D])}: week-based year and week number, the week starting on day D
 *       (1 = Monday, the default, … 7 = Sunday); "2026-43" when fmt contains '-', else "202643"
 * </ul>
 */
final class TimeExpression {

    private static final Pattern FUNCTION = Pattern.compile("^(\\w+)\\((.*)\\)$");
    /** A trailing day offset such as -1, +7*1 or -1/24; a format's own '-' is never followed by a digit. */
    private static final Pattern OFFSET = Pattern.compile("^(.*?)\\s*([+-])\\s*(\\d+(?:\\.\\d+)?(?:\\s*[*/]\\s*\\d+(?:\\.\\d+)?)*)$");

    private TimeExpression() {}

    static String evaluate(String expression, LocalDateTime base) {
        String expr = expression.trim();
        try {
            Matcher function = FUNCTION.matcher(expr);
            if (function.matches()) {
                return function(function.group(1), function.group(2).split(","), base);
            }
            Matcher offset = OFFSET.matcher(expr);
            if (offset.matches()) {
                long millis = Math.round(evaluateNumber(offset.group(3)) * 86_400_000d);
                LocalDateTime shifted = "+".equals(offset.group(2)) ? base.plusNanos(millis * 1_000_000) : base.minusNanos(millis * 1_000_000);
                return format(offset.group(1), shifted);
            }
            return format(expr, base);
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            throw new ConsoleException(ErrorCodes.INVALID_ARGUMENT, "invalid time expression $[" + expression + "]: " + e.getMessage());
        }
    }

    private static String function(String name, String[] args, LocalDateTime base) {
        String fmt = args[0].trim();
        switch (name) {
            case "add_months":
                return format(fmt, base.plusMonths(intArg(args, 1, name)));
            case "this_day":
                return format(fmt, base);
            case "last_day":
                return format(fmt, base.minusDays(1));
            case "month_first_day":
                return format(fmt, base.plusMonths(intArg(args, 1, name)).with(TemporalAdjusters.firstDayOfMonth()));
            case "month_last_day":
                return format(fmt, base.plusMonths(intArg(args, 1, name)).with(TemporalAdjusters.lastDayOfMonth()));
            case "week_first_day":
                return format(fmt, base.plusWeeks(intArg(args, 1, name)).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
            case "week_last_day":
                return format(fmt, base.plusWeeks(intArg(args, 1, name)).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)));
            case "year_week":
                return yearWeek(fmt, args.length > 1 ? intArg(args, 1, name) : 1, base);
            default:
                throw new IllegalArgumentException("unknown function " + name);
        }
    }

    private static String yearWeek(String fmt, int firstDay, LocalDateTime base) {
        if (firstDay < 1 || firstDay > 7) {
            throw new IllegalArgumentException("year_week first day must be 1 (Monday) to 7 (Sunday)");
        }
        WeekFields fields = firstDay == 1 ? WeekFields.ISO : WeekFields.of(DayOfWeek.of(firstDay), 4);
        int year = base.get(firstDay == 1 ? IsoFields.WEEK_BASED_YEAR : fields.weekBasedYear());
        int week = base.get(fields.weekOfWeekBasedYear());
        return String.format(fmt.contains("-") ? "%d-%02d" : "%d%02d", year, week);
    }

    private static int intArg(String[] args, int index, String function) {
        if (args.length <= index) {
            throw new IllegalArgumentException(function + " needs a second argument");
        }
        return (int) Math.round(evaluateNumber(args[index].trim()));
    }

    /** Evaluates "N", "-N", "7*1", "1/24/60": numbers joined by * and /, left to right. */
    private static double evaluateNumber(String text) {
        String s = text.replace(" ", "");
        double sign = 1;
        if (s.startsWith("-") || s.startsWith("+")) {
            sign = s.startsWith("-") ? -1 : 1;
            s = s.substring(1);
        }
        Matcher m = Pattern.compile("([*/]?)(\\d+(?:\\.\\d+)?)").matcher(s);
        double value = 0;
        int end = 0;
        boolean first = true;
        while (m.find() && m.start() == end) {
            double n = Double.parseDouble(m.group(2));
            if (first) {
                value = n;
                first = false;
            } else if ("*".equals(m.group(1))) {
                value *= n;
            } else {
                value /= n;
            }
            end = m.end();
        }
        if (first || end != s.length()) {
            throw new IllegalArgumentException("not a number expression: " + text);
        }
        return sign * value;
    }

    private static String format(String pattern, LocalDateTime time) {
        if (pattern.isEmpty()) {
            throw new IllegalArgumentException("empty format");
        }
        return DateTimeFormatter.ofPattern(pattern).format(time);
    }
}
