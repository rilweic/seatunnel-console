package com.dataintegration.console.server.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.common.ErrorCodes;
import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.server.auth.SecurityConfig;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.dataintegration.console.server.auth.AccountChanges;
import com.dataintegration.console.server.health.ClusterHealthService;
import com.dataintegration.console.server.persistence.mapper.SysUserMapper;
import com.dataintegration.console.server.health.DiagnosticService;

import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

@WebMvcTest(ClusterController.class)
@Import(SecurityConfig.class)
class ClusterControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private EngineClient engineClient;
    @MockitoBean private ClusterHealthService healthService;
    @MockitoBean private DiagnosticService diagnosticService;
    @MockitoBean private SysUserMapper userMapper;
    @MockitoBean private AccountChanges accountChanges;

    @Test
    void anonymousRequestIsRejectedWithJson401() throws Exception {
        mockMvc.perform(get("/api/cluster/overview"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCodes.UNAUTHENTICATED));
    }

    @Test
    @WithMockUser
    void overviewWrapsEngineResponse() throws Exception {
        when(engineClient.overview()).thenReturn(Map.of("projectVersion", "3.0.0"));

        mockMvc.perform(get("/api/cluster/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.projectVersion").value("3.0.0"));
    }

    @Test
    @WithMockUser
    void unreachableClusterReturns503() throws Exception {
        when(engineClient.overview())
                .thenThrow(new ConsoleException(ErrorCodes.ENGINE_UNAVAILABLE, "no Zeta master reachable"));

        mockMvc.perform(get("/api/cluster/overview"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCodes.ENGINE_UNAVAILABLE));
    }
}
