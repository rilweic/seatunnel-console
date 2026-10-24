package com.dataintegration.console.server.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dataintegration.console.common.ConsoleException;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

class CronScheduleTest {

    @Test
    void previewsNextFireTimes() {
        assertThat(CronSchedule.nextFireTimes("0 0 2 * * ?", LocalDateTime.of(2026, 10, 24, 9, 0), 3))
                .containsExactly(
                        LocalDateTime.of(2026, 10, 25, 2, 0),
                        LocalDateTime.of(2026, 10, 26, 2, 0),
                        LocalDateTime.of(2026, 10, 27, 2, 0));
    }

    @Test
    void acceptsQuestionMarkInDayOfMonth() {
        assertThat(CronSchedule.nextFireTimes("0 30 1 ? * MON", LocalDateTime.of(2026, 10, 24, 9, 0), 1))
                .containsExactly(LocalDateTime.of(2026, 10, 26, 1, 30));
    }

    @Test
    void rejectsWhatXxlJobWouldReject() {
        // Unix 5-field cron
        assertThatThrownBy(() -> CronSchedule.parse("0 2 * * *")).isInstanceOf(ConsoleException.class)
                .hasMessageContaining("6 段");
        // both day fields set (Quartz rejects this)
        assertThatThrownBy(() -> CronSchedule.parse("0 0 2 * * *")).isInstanceOf(ConsoleException.class)
                .hasMessageContaining("?");
        assertThatThrownBy(() -> CronSchedule.parse("0 0 25 * * ?")).isInstanceOf(ConsoleException.class)
                .hasMessageContaining("cron 无效");
    }

    @Test
    void schedulesRunAtMostEveryTwoMinutes() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 24, 9, 0);
        assertThatThrownBy(() -> CronSchedule.parseSchedule("* * * * * ?", from)).hasMessageContaining("至少要间隔 2 分钟");
        assertThatThrownBy(() -> CronSchedule.parseSchedule("0 * * * * ?", from)).hasMessageContaining("至少要间隔 2 分钟");
        // crowded only during one hour of the day
        assertThatThrownBy(() -> CronSchedule.parseSchedule("0 * 2 * * ?", from))
                .hasMessageContaining("10-25 02:00:00 和 10-25 02:01:00");
        // two runs a minute apart once a week
        assertThatThrownBy(() -> CronSchedule.parseSchedule("0 0,1 3 ? * SUN", from)).hasMessageContaining("至少要间隔");

        CronSchedule.parseSchedule("0 0/2 * * * ?", from);
        CronSchedule.parseSchedule("0 0/5 * * * ?", from);
        CronSchedule.parseSchedule("0 0 2 * * ?", from);
        CronSchedule.parseSchedule("0 0 9 ? * MON-FRI", from);
        // minute 59 of every other hour: never closer than two hours
        CronSchedule.parseSchedule("0 59 0/2 * * ?", from);
    }
}
