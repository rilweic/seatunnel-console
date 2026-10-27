package com.dataintegration.console.server.scheduler;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.xxl.job.core.openapi.admin.dto.JobAddRequest;
import com.xxl.job.core.openapi.admin.dto.JobOperateRequest;
import com.xxl.job.core.openapi.admin.dto.JobTriggerRequest;
import com.xxl.job.core.openapi.admin.dto.JobUpdateRequest;
import com.xxl.tool.response.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Client for the xxl-job-admin 3.5.0 job OpenAPI ({@code POST /api/{uri}}). The console is the
 * source of truth for schedules; this class only mirrors them into XXL-JOB.
 */
public class XxlJobAdminClient {

    private static final Logger log = LoggerFactory.getLogger(XxlJobAdminClient.class);

    public static final String HANDLER_NAME = "seatunnelJobHandler";

    public static final String HEADER_ACCESS_TOKEN = "XXL-JOB-ACCESS-TOKEN";
    public static final String HEADER_APPNAME = "XXL-JOB-APPNAME";

    /**
     * How xxl-job-admin 3.5.0 says a job id does not exist, in each of its languages: update answers
     * "jobinfo_field_id + system_not_found", start/stop/remove answer "jobinfo_glue_jobid_invalid".
     */
    private static final List<String> JOB_MISSING_MESSAGES = List.of(
            "任务ID不存在", "任務ID不存在", "Job IDnot exist", "任务ID非法", "任務ID非法", "Job ID is illegal");

    private static String abbreviate(String text) {
        return text == null ? "" : text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    private static final ParameterizedTypeReference<Response<String>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final XxlJobProperties properties;
    private final List<RestClient> clients;

    public XxlJobAdminClient(XxlJobProperties properties, RestClient.Builder builder) {
        if (properties.adminAddresses() == null || properties.adminAddresses().isEmpty()) {
            throw new IllegalArgumentException("console.xxl-job.admin-addresses is required");
        }
        this.properties = properties;
        this.clients =
                properties.adminAddresses().stream()
                        .map(
                                address ->
                                        builder.clone()
                                                .baseUrl(stripTrailingSlash(address))
                                                .defaultHeader(HEADER_ACCESS_TOKEN, properties.accessToken())
                                                .defaultHeader(HEADER_APPNAME, properties.appname())
                                                .build())
                        .toList();
    }

    /** Creates a stopped job and returns its XXL-JOB id. Call {@link #startJob} to activate it. */
    public int addJob(String name, ScheduleSpec spec, String executorParam) {
        JobAddRequest request = new JobAddRequest();
        request.setJobGroup(properties.jobGroupId());
        request.setName(name);
        request.setAuthor("seatunnel-console");
        request.setScheduleType("CRON");
        request.setScheduleConf(spec.cron());
        request.setMisfireStrategy(spec.misfireStrategy().name());
        request.setExecutorRouteStrategy("FIRST");
        request.setExecutorHandler(HANDLER_NAME);
        request.setExecutorParam(executorParam);
        request.setExecutorBlockStrategy(spec.blockStrategy().name());
        request.setExecutorTimeout(spec.timeoutSeconds());
        // Retries happen inside one console run instance (docs/schema.md §4.2); XXL-JOB must not retry.
        request.setExecutorFailRetryCount(0);
        request.setGlueType("BEAN");
        return parseId(call("addJob", request));
    }

    private static int parseId(String id) {
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            throw new ConsoleException(
                    ErrorCodes.SCHEDULER_REQUEST_FAILED, "xxl-job addJob returned invalid id: " + id);
        }
    }

    /**
     * Creates a job with schedule type NONE: it never fires on its own and is only triggered for
     * manual runs, reruns and backfills until a schedule is enabled (accepted by 3.5.0 addJob).
     */
    public int addTriggerOnlyJob(String name, String executorParam) {
        JobAddRequest request = new JobAddRequest();
        request.setJobGroup(properties.jobGroupId());
        request.setName(name);
        request.setAuthor("seatunnel-console");
        request.setScheduleType("NONE");
        request.setScheduleConf("");
        request.setMisfireStrategy(ScheduleSpec.MisfireStrategy.DO_NOTHING.name());
        request.setExecutorRouteStrategy("FIRST");
        request.setExecutorHandler(HANDLER_NAME);
        request.setExecutorParam(executorParam);
        request.setExecutorBlockStrategy(ScheduleSpec.BlockStrategy.SERIAL_EXECUTION.name());
        request.setExecutorTimeout(0);
        request.setExecutorFailRetryCount(0);
        request.setGlueType("BEAN");
        return parseId(call("addJob", request));
    }

    public void updateJob(int jobId, String name, ScheduleSpec spec, String executorParam) {
        JobUpdateRequest request = new JobUpdateRequest();
        request.setId(jobId);
        request.setName(name);
        request.setAuthor("seatunnel-console");
        request.setScheduleType("CRON");
        request.setScheduleConf(spec.cron());
        request.setMisfireStrategy(spec.misfireStrategy().name());
        request.setExecutorRouteStrategy("FIRST");
        request.setExecutorHandler(HANDLER_NAME);
        request.setExecutorParam(executorParam);
        request.setExecutorBlockStrategy(spec.blockStrategy().name());
        request.setExecutorTimeout(spec.timeoutSeconds());
        // Retries happen inside one console run instance (docs/schema.md §4.2); XXL-JOB must not retry.
        request.setExecutorFailRetryCount(0);
        request.setGlueType("BEAN");
        call("updateJob", request);
    }

    public void removeJob(int jobId) {
        call("removeJob", operate(jobId));
    }

    public void startJob(int jobId) {
        call("startJob", operate(jobId));
    }

    public void stopJob(int jobId) {
        call("stopJob", operate(jobId));
    }

    /** Fires the job once, e.g. for a manual run, rerun or backfill day. */
    public void triggerJob(int jobId, String executorParam) {
        JobTriggerRequest request = new JobTriggerRequest();
        request.setId(jobId);
        request.setExecutorParam(executorParam);
        call("triggerJob", request);
    }

    private String call(String uri, Object body) {
        ResourceAccessException lastFailure = null;
        for (int i = 0; i < clients.size(); i++) {
            Response<String> response;
            try {
                response =
                        clients.get(i)
                                .post()
                                .uri("/api/{uri}", uri)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(body)
                                .retrieve()
                                .body(RESPONSE_TYPE);
            } catch (ResourceAccessException e) {
                log.warn("xxl-job-admin {} unreachable: {}", properties.adminAddresses().get(i), e.toString());
                lastFailure = e;
                continue;
            } catch (RestClientResponseException e) {
                // An HTTP error status (wrong token, admin error page): a failed call like any other,
                // so callers record it (run FAILED, backfill PAUSED, schedule FAILED) instead of crashing.
                throw new ConsoleException(ErrorCodes.SCHEDULER_REQUEST_FAILED,
                        "xxl-job " + uri + " returned HTTP " + e.getStatusCode().value() + ": " + abbreviate(e.getResponseBodyAsString()));
            } catch (RestClientException e) {
                throw new ConsoleException(ErrorCodes.SCHEDULER_REQUEST_FAILED, "xxl-job " + uri + " failed: " + e.getMessage());
            }
            if (response != null && !response.isSuccess() && response.getMsg() != null
                    && JOB_MISSING_MESSAGES.stream().anyMatch(response.getMsg()::contains)) {
                throw new ConsoleException(ErrorCodes.SCHEDULER_JOB_MISSING, "xxl-job " + uri + ": job no longer exists in xxl-job-admin");
            }
            if (response == null || !response.isSuccess()) {
                throw new ConsoleException(
                        ErrorCodes.SCHEDULER_REQUEST_FAILED,
                        "xxl-job " + uri + " failed: "
                                + (response == null ? "empty response" : response.getMsg()));
            }
            return response.getData();
        }
        throw new ConsoleException(
                ErrorCodes.SCHEDULER_UNAVAILABLE,
                "no xxl-job-admin reachable: " + properties.adminAddresses(),
                lastFailure);
    }

    private static JobOperateRequest operate(int jobId) {
        JobOperateRequest request = new JobOperateRequest();
        request.setId(jobId);
        return request;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
