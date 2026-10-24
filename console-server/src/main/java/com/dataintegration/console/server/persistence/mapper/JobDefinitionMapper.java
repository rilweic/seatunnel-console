package com.dataintegration.console.server.persistence.mapper;

import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface JobDefinitionMapper extends BaseMapper<JobDefinition> {

    /** Names of jobs including soft-deleted ones, for labelling historical runs. */
    @Select("<script>SELECT id, name FROM job_definition WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<JobDefinition> selectNamesIncludingDeleted(@Param("ids") List<String> ids);
}
