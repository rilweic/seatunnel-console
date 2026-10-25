package com.dataintegration.console.server.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dataintegration.console.common.ConsoleException;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

class VariablesTest {

    /** A Wednesday. */
    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 21, 2, 0, 0);

    @Test
    void timeExpressionsFollowDolphinScheduler() {
        assertThat(TimeExpression.evaluate("yyyyMMdd", T)).isEqualTo("20261021");
        assertThat(TimeExpression.evaluate("yyyy-MM-dd", T)).isEqualTo("2026-10-21");
        assertThat(TimeExpression.evaluate("yyyyMMdd-1", T)).isEqualTo("20261020");
        assertThat(TimeExpression.evaluate("yyyy-MM-dd-1", T)).isEqualTo("2026-10-20");
        assertThat(TimeExpression.evaluate("yyyyMMdd+7*1", T)).isEqualTo("20261028");
        assertThat(TimeExpression.evaluate("HHmmss-1/24", T)).isEqualTo("010000");
        assertThat(TimeExpression.evaluate("yyyyMMddHHmm-1/24/60", T)).isEqualTo("202610210159");
        assertThat(TimeExpression.evaluate("yyyy-MM-dd HH:mm:ss", T)).isEqualTo("2026-10-21 02:00:00");
        assertThat(TimeExpression.evaluate("add_months(yyyyMMdd,-1)", T)).isEqualTo("20260921");
        assertThat(TimeExpression.evaluate("add_months(yyyyMMdd,-12*1)", T)).isEqualTo("20251021");
        assertThat(TimeExpression.evaluate("this_day(yyyy-MM-dd)", T)).isEqualTo("2026-10-21");
        assertThat(TimeExpression.evaluate("last_day(yyyy-MM-dd)", T)).isEqualTo("2026-10-20");
        assertThat(TimeExpression.evaluate("month_first_day(yyyy-MM-dd,-1)", T)).isEqualTo("2026-09-01");
        assertThat(TimeExpression.evaluate("month_last_day(yyyy-MM-dd,-1)", T)).isEqualTo("2026-09-30");
        assertThat(TimeExpression.evaluate("week_first_day(yyyy-MM-dd,0)", T)).isEqualTo("2026-10-19");
        assertThat(TimeExpression.evaluate("week_last_day(yyyy-MM-dd,-1)", T)).isEqualTo("2026-10-18");
        assertThat(TimeExpression.evaluate("year_week(yyyy-MM-dd)", T)).isEqualTo("2026-43");
        assertThat(TimeExpression.evaluate("year_week(yyyyMMdd)", T)).isEqualTo("202643");
    }

    @Test
    void yearWeekBelongsToTheWeekBasedYear() {
        // 2027-01-01 is a Friday, so it is still in ISO week 53 of 2026
        assertThat(TimeExpression.evaluate("year_week(yyyy-MM-dd)", LocalDateTime.of(2027, 1, 1, 0, 0))).isEqualTo("2026-53");
    }

    @Test
    void invalidExpressionsAreRejected() {
        assertThatThrownBy(() -> TimeExpression.evaluate("nope(yyyy)", T)).isInstanceOf(ConsoleException.class);
        assertThatThrownBy(() -> TimeExpression.evaluate("add_months(yyyyMMdd)", T)).isInstanceOf(ConsoleException.class);
        assertThatThrownBy(() -> TimeExpression.evaluate("yyyyMMdd-x", T)).isInstanceOf(ConsoleException.class);
    }

    @Test
    void priorityAndValuesUsingBuiltinsAndTime() {
        Map<String, String> builtins = Variables.builtins(T, "inst-1", "orders", "demo");
        Map<String, String> vars = Variables.resolve(
                builtins,
                T,
                Map.of("dt", "${system.biz.date}", "table", "t_project", "month", "$[yyyyMM]"),
                Map.of("table", "t_job"),
                Map.of("dt", "20260101"));

        assertThat(vars)
                .containsEntry("system.biz.date", "20261020")
                .containsEntry("system.biz.curdate", "20261021")
                .containsEntry("system.datetime", "20261021020000")
                .containsEntry("system.task.instance.id", "inst-1")
                .containsEntry("dt", "20260101")
                .containsEntry("table", "t_job")
                .containsEntry("month", "202610");
    }

    @Test
    void rendersKnownNamesEvenInsideQuotesAndKeepsTheRest() {
        Map<String, String> vars = Map.of("dt", "20261020");
        String text = "query = \"select * from t where dt = '${dt}' and m = '$[yyyyMM-1]'\"\n"
                + "path = ${HOME}\nopt = ${?OPTIONAL}\n";

        String rendered = Variables.render(text, vars, T);

        assertThat(rendered)
                .contains("dt = '20261020'")
                .contains("m = '202610'")
                .contains("path = ${HOME}")
                .contains("opt = ${?OPTIONAL}");
        assertThat(Variables.unresolved(rendered)).containsExactly("HOME");
    }

    @Test
    void httpPaginationNamesAreReservedAndNotUnresolved() {
        assertThatThrownBy(() -> Variables.validateName("page"))
                .isInstanceOf(ConsoleException.class)
                .hasMessageContaining("reserved by SeaTunnel");
        assertThatThrownBy(() -> Variables.validateName("cursor"))
                .isInstanceOf(ConsoleException.class)
                .hasMessageContaining("reserved by SeaTunnel");
        assertThatThrownBy(() -> Variables.resolve(Map.of(), T, Map.of("page", "1")))
                .isInstanceOf(ConsoleException.class);
        assertThatThrownBy(() -> Variables.resolve(Map.of(), T, Map.of("cursor", "next")))
                .isInstanceOf(ConsoleException.class);

        assertThat(Variables.unresolved("${page} ${cursor} ${table_name} ${missing}"))
                .containsExactly("missing");
    }

    @Test
    void parameterNamesAreChecked() {
        Variables.validateName("biz_dt");
        Variables.validateName("src.table");
        assertThatThrownBy(() -> Variables.validateName("1dt")).isInstanceOf(ConsoleException.class);
        assertThatThrownBy(() -> Variables.validateName("a-b")).isInstanceOf(ConsoleException.class);
        assertThatThrownBy(() -> Variables.validateName("system.biz.date")).hasMessageContaining("reserved");
    }
}
