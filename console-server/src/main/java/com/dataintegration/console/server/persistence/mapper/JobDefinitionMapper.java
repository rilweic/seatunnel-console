package com.dataintegration.console.server.persistence.mapper;

import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface JobDefinitionMapper extends BaseMapper<JobDefinition> {

    /** Names and deletion state of jobs including soft-deleted ones, for labelling historical runs. */
    @Select("<script>SELECT id, name, created_by, deleted_at FROM job_definition WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<JobDefinition> selectNamesIncludingDeleted(@Param("ids") List<String> ids);

    /** Deleted jobs whose XXL-JOB job could not be removed at the time, for the reconciler. */
    @Select("SELECT id, name, xxl_job_id FROM job_definition WHERE deleted_at IS NOT NULL AND xxl_job_id IS NOT NULL")
    List<JobDefinition> selectDeletedWithXxlJob();

    /**
     * Forgets the job's XXL-JOB job, live or deleted, only if it still points at {@code xxlJobId}
     * (plain SQL, so the logic-delete filter does not apply).
     */
    @Update("UPDATE job_definition SET xxl_job_id = NULL WHERE id = #{id} AND xxl_job_id = #{xxlJobId}")
    int clearXxlJobId(@Param("id") String id, @Param("xxlJobId") int xxlJobId);
}
