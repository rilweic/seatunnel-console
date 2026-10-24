package com.dataintegration.console.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

class XxlJobAdminClientTest {

    private MockRestServiceServer server;
    private XxlJobAdminClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        XxlJobProperties properties =
                new XxlJobProperties(
                        true, List.of("http://admin:8081/"), "seatunnel-console", "tok", 3, 3, null, 9999,
                        null, "./logs", 30);
        client = new XxlJobAdminClient(properties, builder);
    }

    @Test
    void addJobSendsCronBeanJobWithAuthHeadersAndReturnsId() {
        server.expect(requestTo("http://admin:8081/api/addJob"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("XXL-JOB-ACCESS-TOKEN", "tok"))
                .andExpect(header("XXL-JOB-APPNAME", "seatunnel-console"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.jobGroup").value(3))
                .andExpect(jsonPath("$.scheduleType").value("CRON"))
                .andExpect(jsonPath("$.scheduleConf").value("0 0 2 * * ?"))
                .andExpect(jsonPath("$.executorHandler").value("seatunnelJobHandler"))
                .andExpect(jsonPath("$.executorBlockStrategy").value("SERIAL_EXECUTION"))
                .andExpect(jsonPath("$.executorFailRetryCount").value(0))
                .andExpect(jsonPath("$.glueType").value("BEAN"))
                .andRespond(withSuccess("{\"code\":200,\"msg\":null,\"data\":\"15\"}", MediaType.APPLICATION_JSON));

        int id =
                client.addJob(
                        "orders_sync",
                        new ScheduleSpec("0 0 2 * * ?", 2, 3600, null, null),
                        "{\"jobDefinitionId\":12}");

        assertThat(id).isEqualTo(15);
        server.verify();
    }

    @Test
    void triggerJobPassesExecutorParam() {
        server.expect(requestTo("http://admin:8081/api/triggerJob"))
                .andExpect(jsonPath("$.id").value(15))
                .andExpect(jsonPath("$.executorParam").value("{\"jobDefinitionId\":12}"))
                .andRespond(withSuccess("{\"code\":200}", MediaType.APPLICATION_JSON));

        client.triggerJob(15, "{\"jobDefinitionId\":12}");

        server.verify();
    }

    @Test
    void adminRejectionBecomesSchedulerError() {
        server.expect(requestTo("http://admin:8081/api/startJob"))
                .andRespond(
                        withSuccess(
                                "{\"code\":500,\"msg\":\"invalid request, accessToken or appname invalid.\"}",
                                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.startJob(15))
                .isInstanceOf(ConsoleException.class)
                .hasMessageContaining("accessToken or appname invalid")
                .extracting(e -> ((ConsoleException) e).getCode())
                .isEqualTo(ErrorCodes.SCHEDULER_REQUEST_FAILED);
    }
}
