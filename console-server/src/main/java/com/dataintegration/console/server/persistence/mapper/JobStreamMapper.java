package com.dataintegration.console.server.persistence.mapper;

import com.dataintegration.console.server.persistence.entity.JobStream;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Select;

public interface JobStreamMapper extends BaseMapper<JobStream> {

    /** The first free 8-id server-id block at or above {@code base}, for a new MySQL-CDC job. */
    @Select("select coalesce(max(server_id_start) + 8, #{base}) from job_stream where server_id_start >= #{base}")
    int nextServerIdStart(int base);
}
