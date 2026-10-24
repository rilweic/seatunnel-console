package com.dataintegration.console.server.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.persistence.entity.JobInstance;
import com.dataintegration.console.server.persistence.mapper.JobDefinitionMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceAttemptMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceEventMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceMapper;
import com.dataintegration.console.server.persistence.mapper.JobMetricSampleMapper;
import com.dataintegration.console.server.persistence.mapper.JobVersionMapper;
import com.dataintegration.console.server.persistence.mapper.ProjectMapper;
import com.dataintegration.console.server.user.UserDirectory;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

@ExtendWith(MockitoExtension.class)
class InstanceServiceTest {

    @BeforeEach
    void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), JobInstance.class);
    }

    @Mock private JobInstanceMapper instanceMapper;
    @Mock private JobInstanceAttemptMapper attemptMapper;
    @Mock private JobInstanceEventMapper eventMapper;
    @Mock private JobDefinitionMapper jobMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private JobVersionMapper versionMapper;
    @Mock private EngineLogService engineLogService;
    @Mock private LogStorage logStorage;
    @Mock private UserDirectory users;
    @Mock private JobMetricSampleMapper sampleMapper;
    @Mock private ObjectMapper objectMapper;
    @InjectMocks private InstanceService instanceService;

    @Test
    @SuppressWarnings("unchecked")
    void listCombinesLiteralJobNameAndStartTimeRange() {
        when(instanceMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> invocation.getArgument(0));
        LocalDateTime from = LocalDateTime.of(2026, 10, 25, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 10, 26, 0, 0);

        instanceService.list("project-1", "job-1", "t_100%", "SUCCESS", null, from, to, false, 2, 50);

        ArgumentCaptor<LambdaQueryWrapper<JobInstance>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(instanceMapper).selectPage(any(Page.class), query.capture());
        assertThat(query.getValue().getSqlSegment())
                .contains("job_definition_id in (select id from job_definition where name like")
                .contains("started_at IS NOT NULL")
                .contains("started_at >=")
                .contains("started_at IS NULL")
                .contains("created_at >=")
                .doesNotContain("coalesce");
        assertThat(query.getValue().getParamNameValuePairs().values())
                .contains("%t\\_100\\%%", from, to);
    }

    @Test
    @SuppressWarnings("unchecked")
    void feedUsesKeysetAndReadsOneExtraRow() {
        LocalDateTime cursorTime = LocalDateTime.of(2026, 10, 25, 1, 2, 3);

        InstanceService.InstanceFeed result = instanceService.feed(
                null, null, null, null, null, null, null, false, cursorTime, "cursor-1", 20);

        ArgumentCaptor<LambdaQueryWrapper<JobInstance>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(instanceMapper).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment())
                .contains("created_at <")
                .contains("created_at =")
                .contains("id <")
                .contains("ORDER BY created_at DESC,id DESC")
                .contains("limit 21");
        assertThat(query.getValue().getParamNameValuePairs().values()).contains(cursorTime, "cursor-1");
        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextCursorTime()).isNull();
        assertThat(result.nextCursorId()).isNull();
    }

    @Test
    void viewsExposeJobDeletionStateAndKeepTheOriginalName() {
        JobInstance liveRun = new JobInstance();
        liveRun.setId("run-1");
        liveRun.setJobDefinitionId("job-1");
        JobInstance deletedRun = new JobInstance();
        deletedRun.setId("run-2");
        deletedRun.setJobDefinitionId("job-2");
        JobDefinition liveJob = new JobDefinition();
        liveJob.setId("job-1");
        liveJob.setName("live-job");
        JobDefinition deletedJob = new JobDefinition();
        deletedJob.setId("job-2");
        deletedJob.setName("deleted-job");
        deletedJob.setDeletedAt(LocalDateTime.of(2026, 10, 25, 2, 0));
        when(jobMapper.selectNamesIncludingDeleted(any())).thenReturn(java.util.List.of(liveJob, deletedJob));

        var views = instanceService.views(java.util.List.of(liveRun, deletedRun));

        assertThat(views).extracting(InstanceService.InstanceView::jobName)
                .containsExactly("live-job", "deleted-job");
        assertThat(views).extracting(InstanceService.InstanceView::jobDeleted)
                .containsExactly(false, true);
    }
}
