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
        assertThatThrownBy(() -> ScheduleService.backfillTimes(null, OCT_3, OCT_1, 100)).hasMessageContaining("不能早于");
        // 2026-10-01..03 has no Monday
        assertThatThrownBy(() -> ScheduleService.backfillTimes("0 0 2 ? * MON", OCT_1, OCT_3, 100))
                .isInstanceOf(ConsoleException.class).hasMessageContaining("没有要补的运行");
        assertThatThrownBy(() -> ScheduleService.backfillTimes("0 0 * * * ?", OCT_1, OCT_3, 50)).hasMessageContaining("最多 50 次");
    }

    @Test
    void leavesOutTimesThatHaveNotComeYet() {
        // at 2026-10-03 10:30 the 02:00 run of the 3rd has come, the 12:00 one has not
        java.time.LocalDateTime now = java.time.LocalDateTime.of(2026, 10, 3, 10, 30);
        assertThat(ScheduleService.backfillTimes("0 0 2,12 * * ?", OCT_1, OCT_3, 100, now)).hasSize(5)
                .last().isEqualTo(java.time.LocalDateTime.of(2026, 10, 3, 2, 0));
        assertThat(ScheduleService.backfillTimes(null, OCT_1, OCT_3, 100, now)).hasSize(3);
        assertThatThrownBy(() -> ScheduleService.backfillTimes("0 0 12 * * ?", OCT_3, OCT_3, 100, now))
                .hasMessageContaining("还没到");
    }
}
