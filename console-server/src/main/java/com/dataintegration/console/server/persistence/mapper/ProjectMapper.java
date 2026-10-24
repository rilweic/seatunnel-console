package com.dataintegration.console.server.persistence.mapper;

import com.dataintegration.console.server.persistence.entity.Project;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ProjectMapper extends BaseMapper<Project> {

    /** Names of projects including soft-deleted ones, for labelling historical runs. */
    @Select("<script>SELECT id, name FROM project WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<Project> selectNamesIncludingDeleted(@Param("ids") List<String> ids);
}
