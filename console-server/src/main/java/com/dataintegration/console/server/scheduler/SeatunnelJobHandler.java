package com.dataintegration.console.server.scheduler;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.server.run.RunExecutor;
import com.dataintegration.console.server.run.RunStatus;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.time.ZoneId;

/**
 * The single XXL-JOB handler registered by the console (docs/design.md §5.2). Only offline (batch)
 * jobs are triggered through XXL-JOB; streaming jobs are started and stopped by the console.
 */
@Component
@ConditionalOnProperty(prefix = "console.xxl-job", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SeatunnelJobHandler {

    private static final Logger log = LoggerFactory.getLogger(SeatunnelJobHandler.class);

    private final ObjectMapper objectMapper;
    private final RunExecutor runExecutor;

    public SeatunnelJobHandler(ObjectMapper objectMapper, RunExecutor runExecutor) {
        this.objectMapper = objectMapper;
        this.runExecutor = runExecutor;
    }

    @XxlJob(XxlJobAdminClient.HANDLER_NAME)
    public void execute() {
        ScheduledTrigger trigger;
        try {
            trigger =
                    TriggerParam.parse(
                            objectMapper,
                            XxlJobHelper.getJobParam(),
                            XxlJobHelper.getJobId(),
                            XxlJobHelper.getLogId(),
                            XxlJobHelper.getLogDateTime(),
                            ZoneId.systemDefault());
        } catch (ConsoleException e) {
            XxlJobHelper.handleFail(e.getMessage());
            return;
        }
        XxlJobHelper.log(
                "trigger jobDefinitionId={}, instanceId={}, scheduledTime={}",
                trigger.jobDefinitionId(), trigger.instanceId(), trigger.scheduledTime());

        RunExecutor.RunResult result;
        try {
            result = runExecutor.execute(trigger, line -> XxlJobHelper.log(line));
        } catch (ConsoleException e) {
            log.warn("Run of job definition {} could not start: {}", trigger.jobDefinitionId(), e.getMessage());
            XxlJobHelper.handleFail(e.getMessage());
            return;
        }
        String summary = "instance " + result.instanceId() + " " + result.status() + ": " + result.message();
        if (result.status() == RunStatus.SUCCESS) {
            XxlJobHelper.handleSuccess(summary);
        } else {
            XxlJobHelper.handleFail(summary);
        }
    }
}
