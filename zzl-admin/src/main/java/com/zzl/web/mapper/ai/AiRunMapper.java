package com.zzl.web.mapper.ai;

import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface AiRunMapper
{
    int insertRun(@Param("runId") String runId, @Param("scope") String scope,
            @Param("conversationId") String conversationId, @Param("model") String model,
            @Param("question") String question, @Param("status") String status,
            @Param("startedAt") java.time.LocalDateTime startedAt, @Param("events") String events);

    int updateRun(@Param("runId") String runId, @Param("status") String status,
            @Param("endedAt") java.time.LocalDateTime endedAt, @Param("durationMs") Long durationMs,
            @Param("answer") String answer, @Param("errorMessage") String errorMessage,
            @Param("events") String events);

    int updateEvents(@Param("runId") String runId, @Param("events") String events);

    AiRunRecord selectOne(@Param("scope") String scope, @Param("runId") String runId);

    List<AiRunRecord> selectByConversation(@Param("scope") String scope,
            @Param("conversationId") String conversationId);

    List<AiRunRecord> selectRecent(@Param("scope") String scope);

    int delete(@Param("scope") String scope, @Param("runId") String runId);
}
