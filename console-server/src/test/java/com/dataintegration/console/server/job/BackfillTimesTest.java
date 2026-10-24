package com.dataintegration.console.server.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dataintegration.console.common.ConsoleException;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

class BackfillTimesTest {

    private static final LocalDate OCT_1 = LocalDate.of(2026, 10, 1);
    private static final LocalDate OCT_3 = LocalDate.of(2026, 10, 3);

    @Test
    void oneRunPerDayAtMidnightWithoutCron() {
        assertThat(ScheduleService.backfillTimes(null, OCT_1, OCT_3, 100))
                .containsExactly(
                        LocalDateTime.of(2026, 10, 1, 0, 0),
                        LocalDateTime.of(2026, 10, 2, 0, 0),
                        LocalDateTime.of(2026, 10, 3, 0, 0));
    }

    @Test
    void everyCronFireTimeInTheRangeIncludingBothEnds() {
        assertThat(ScheduleService.backfillTimes("0 0 2 * * ?", OCT_1, OCT_3, 100))
                .containsExactly(
                        LocalDateTime.of(2026, 10, 1, 2, 0),
                        LocalDateTime.of(2026, 10, 2, 2, 0),
                        LocalDateTime.of(2026, 10, 3, 2, 0));
        assertThat(ScheduleService.backfillTimes("0 0 0 * * ?", OCT_1, OCT_1, 100))
                .containsExactly(LocalDateTime.of(2026, 10, 1, 0, 0));
        assertThat(ScheduleService.backfillTimes("0 0 * * * ?", OCT_1, OCT_3, 100)).hasSize(72);
    }

    @Test
    void rejectsEmptyReversedAndOversizedRanges() {
        assertThatThrownBy(() -> ScheduleService.backfillTimes(null, OCT_3, OCT_1, 100)).hasMessageContaining("before");
        // 2026-10-01..03 has no Monday
        assertThatThrownBy(() -> ScheduleService.backfillTimes("0 0 2 ? * MON", OCT_1, OCT_3, 100))
                .isInstanceOf(ConsoleException.class).hasMessageContaining("never fires");
        assertThatThrownBy(() -> ScheduleService.backfillTimes("0 0 * * * ?", OCT_1, OCT_3, 50)).hasMessageContaining("at most 50");
    }
}
