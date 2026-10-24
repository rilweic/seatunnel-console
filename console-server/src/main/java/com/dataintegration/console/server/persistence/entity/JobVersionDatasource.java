package com.dataintegration.console.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Datasources referenced by a job version. Table job_version_datasource (composite key). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("job_version_datasource")
public class JobVersionDatasource {

    private String jobVersionId;
    private String datasourceId;
}
