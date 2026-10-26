package com.dataintegration.console.server.job;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

class SyncWindowTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 26, 10, 5, 0, 123_000_000);

    @Test
    void carriesOnFromTheWatermarkUpToTheScheduledTimeMinusTheLag() {
        SyncWindow window = SyncWindow.continuing(LocalDateTime.of(2026, 10, 26, 9, 59), T, 60);
        assertThat(window.startText()).isEqualTo("2026-10-26 09:59:00");
        assertThat(window.endText()).isEqualTo("2026-10-26 10:04:00");
        assertThat(window.empty()).isFalse();
    }

    @Test
    void withoutAWatermarkItStartsAtTheBeginning() {
        SyncWindow window = SyncWindow.continuing(null, T, 0);
        assertThat(window.start()).isNull();
        assertThat(window.startText()).isEqualTo("1970-01-01 00:00:00");
        assertThat(window.endText()).isEqualTo("2026-10-26 10:05:00");
        assertThat(window.empty()).isFalse();
    }

    @Test
    void isEmptyOnceTheWatermarkReachedTheEnd() {
        assertThat(SyncWindow.continuing(LocalDateTime.of(2026, 10, 26, 10, 4), T, 60).empty()).isTrue();
        assertThat(SyncWindow.continuing(LocalDateTime.of(2026, 10, 26, 10, 30), T, 60).empty()).isTrue();
    }

    @Test
    void windowVariablesAreBuiltinsOnlyWhenTheRunHasAWindow() {
        SyncWindow window = new SyncWindow(LocalDateTime.of(2026, 10, 26, 10, 0), LocalDateTime.of(2026, 10, 26, 10, 5));
        Map<String, String> vars = Variables.builtins(T, null, "j", "p", window);
        assertThat(vars).containsEntry(Variables.WINDOW_START, "2026-10-26 10:00:00").containsEntry(Variables.WINDOW_END, "2026-10-26 10:05:00");
        assertThat(Variables.render("t >= '${system.window.start}' and t < '${system.window.end}'", vars, T))
                .isEqualTo("t >= '2026-10-26 10:00:00' and t < '2026-10-26 10:05:00'");
        assertThat(Variables.builtins(T, null, "j", "p", null)).doesNotContainKey(Variables.WINDOW_START);
    }

    @Test
    void aRunUsesTheWindowWhenItsConfigOrAParameterReadsIt() {
        assertThat(Variables.usesWindow(List.of("query = \"select 1 where t >= '${system.window.start}'\""))).isTrue();
        assertThat(Variables.usesWindow(Arrays.asList(null, "${system.window.end}"))).isTrue();
        assertThat(Variables.usesWindow(List.of("$[yyyy-MM-dd]", "${system.biz.date}", "${system.window}"))).isFalse();
    }
}
