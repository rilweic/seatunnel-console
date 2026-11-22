package com.dataintegration.console.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataintegration.console.server.persistence.entity.RetentionPolicy;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RetentionPolicyMapper extends BaseMapper<RetentionPolicy> {}
