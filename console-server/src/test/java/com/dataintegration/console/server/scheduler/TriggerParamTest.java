package com.dataintegration.console.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dataintegration.console.common.ConsoleException;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

class TriggerParamTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final String JOB = "019a1b2c-3d4e-7f00-8a00-000000000012";
    private static final String INSTANCE = "019a1b2c-3d4e-7f00-8a00-000000000099";

    @Test
    void cronTriggerUsesLogTimeAndHasNoInstance() {
        long logTime = LocalDateTime.of(2026, 10, 23, 2, 0).toInstant(ZoneOffset.UTC).toEpochMilli();

        ScheduledTrigger trigger =
                TriggerParam.parse(MAPPER, TriggerParam.forSchedule(JOB).toJson(MAPPER), 7, 99, logTime, UTC);

        assertThat(trigger.jobDefinitionId()).isEqualTo(JOB);
        assertThat(trigger.preCreated()).isFalse();
        assertThat(trigger.scheduledTime()).isEqualTo(LocalDateTime.of(2026, 10, 23, 2, 0));
        assertThat(trigger.startupParams()).isEmpty();
        assertThat(trigger.xxlJobId()).isEqualTo(7L);
        assertThat(trigger.xxlLogId()).isEqualTo(99L);
    }

    @Test
    void manualTriggerCarriesInstanceScheduledTimeAndParams() {
        String raw =
                new TriggerParam(JOB, INSTANCE, LocalDateTime.of(2026, 10, 1, 0, 0), Map.of("region", "cn"))
                        .toJson(MAPPER);

        ScheduledTrigger trigger = TriggerParam.parse(MAPPER, raw, 7, 99, 0L, UTC);

        assertThat(trigger.preCreated()).isTrue();
        assertThat(trigger.instanceId()).isEqualTo(INSTANCE);
        assertThat(trigger.scheduledTime()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
        assertThat(trigger.startupParams()).containsEntry("region", "cn");
    }

    @Test
    void rejectsMissingOrBrokenParam() {
        assertThatThrownBy(() -> TriggerParam.parse(MAPPER, "", 1, 1, 0L, UTC))
                .isInstanceOf(ConsoleException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> TriggerParam.parse(MAPPER, "{oops", 1, 1, 0L, UTC))
                .isInstanceOf(ConsoleException.class)
                .hasMessageContaining("not valid JSON");
        assertThatThrownBy(() -> TriggerParam.parse(MAPPER, "{}", 1, 1, 0L, UTC))
                .isInstanceOf(ConsoleException.class)
                .hasMessageContaining("jobDefinitionId");
    }
}
