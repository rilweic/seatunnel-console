package com.dataintegration.console.server.scheduler;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * JSON carried in the XXL-JOB executor param, written by the console when it creates or triggers a
 * job (docs/schema.md §4.1):
 *
 * <pre>{"jobDefinitionId": "...", "instanceId": "...", "scheduledTime": "2026-10-22T02:00:00", "params": {"k": "v"}}</pre>
 *
 * Only {@code jobDefinitionId} is set on the cron job itself; the other fields are added for
 * manual runs, reruns and backfills.
 */
public record TriggerParam(
        String jobDefinitionId, String instanceId, LocalDateTime scheduledTime, Map<String, String> params) {

    public static TriggerParam forSchedule(String jobDefinitionId) {
        return new TriggerParam(jobDefinitionId, null, null, null);
    }

    public String toJson(ObjectMapper mapper) {
        return mapper.writeValueAsString(this);
    }

    static ScheduledTrigger parse(
            ObjectMapper mapper, String raw, long xxlJobId, long xxlLogId, long logDateTimeMillis, ZoneId zone) {
        if (raw == null || raw.isBlank()) {
            throw invalid("executor param is empty");
        }
        TriggerParam param;
        try {
            param = mapper.readValue(raw, TriggerParam.class);
        } catch (JacksonException e) {
            throw invalid("executor param is not valid JSON: " + e.getOriginalMessage());
        }
        if (param.jobDefinitionId() == null || param.jobDefinitionId().isBlank()) {
            throw invalid("executor param has no jobDefinitionId");
        }
        LocalDateTime scheduledTime =
                param.scheduledTime() != null
                        ? param.scheduledTime()
                        : LocalDateTime.ofInstant(Instant.ofEpochMilli(logDateTimeMillis), zone);
        return new ScheduledTrigger(
                param.jobDefinitionId(), param.instanceId(), scheduledTime, param.params(), xxlJobId, xxlLogId);
    }

    private static ConsoleException invalid(String message) {
        return new ConsoleException(ErrorCodes.INVALID_ARGUMENT, message);
    }
}
