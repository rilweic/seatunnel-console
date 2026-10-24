package com.dataintegration.console.server.persistence.mapper;

import com.dataintegration.console.server.persistence.entity.JobInstance;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

public interface JobInstanceMapper extends BaseMapper<JobInstance> {

    /** The newest run of each job (id, job, status, times only), for the job list. */
    @Select("""
            <script>
            select i.id, i.job_definition_id, i.status, i.started_at, i.created_at
            from job_instance i
            join (select job_definition_id, max(created_at) as latest
                  from job_instance
                  where job_definition_id in
                  <foreach collection="jobIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                  group by job_definition_id) x
              on x.job_definition_id = i.job_definition_id and x.latest = i.created_at
            </script>
            """)
    List<JobInstance> selectLatestPerJob(@Param("jobIds") Collection<String> jobIds);
}
