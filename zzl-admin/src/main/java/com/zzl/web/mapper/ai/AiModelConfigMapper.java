package com.zzl.web.mapper.ai;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AiModelConfigMapper
{
    List<AiModelConfigRecord> selectByScope(@Param("scope") String scope);

    int upsert(AiModelConfigRecord record);

    int delete(@Param("scope") String scope, @Param("modelId") String modelId);
}
