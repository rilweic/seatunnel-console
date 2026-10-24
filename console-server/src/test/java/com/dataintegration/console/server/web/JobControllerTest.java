package com.dataintegration.console.server.web;

import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dataintegration.console.server.auth.AccountChanges;
import com.dataintegration.console.server.auth.SecurityConfig;
import com.dataintegration.console.server.job.JobController;
import com.dataintegration.console.server.job.JobService;
import com.dataintegration.console.server.job.ScheduleService;
import com.dataintegration.console.server.persistence.mapper.SysUserMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(JobController.class)
@Import(SecurityConfig.class)
class JobControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private JobService jobService;
    @MockitoBean private ScheduleService scheduleService;
    @MockitoBean private SysUserMapper userMapper;
    @MockitoBean private AccountChanges accountChanges;

    @Test
    @WithMockUser
    void deleteDefaultsToRemovingRunHistory() throws Exception {
        mockMvc.perform(delete("/api/jobs/job-1").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(jobService).delete("job-1", true);
    }

    @Test
    @WithMockUser
    void deleteCanPreserveRunHistory() throws Exception {
        mockMvc.perform(delete("/api/jobs/job-1?deleteRuns=false").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(jobService).delete("job-1", false);
    }
}
