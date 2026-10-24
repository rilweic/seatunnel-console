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
                .hasMessageContaining("6 fields");
        // both day fields set (Quartz rejects this)
        assertThatThrownBy(() -> CronSchedule.parse("0 0 2 * * *")).isInstanceOf(ConsoleException.class)
                .hasMessageContaining("'?'");
        assertThatThrownBy(() -> CronSchedule.parse("0 0 25 * * ?")).isInstanceOf(ConsoleException.class)
                .hasMessageContaining("invalid cron");
    }
}
