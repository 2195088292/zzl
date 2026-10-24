package com.zzl.web.mapper.ai;

import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface AiMessageMapper
{
    int insert(@Param("messageId") String messageId, @Param("scope") String scope,
            @Param("conversationId") String conversationId, @Param("runId") String runId,
            @Param("role") String role, @Param("content") String content,
            @Param("model") String model, @Param("tokenCount") Integer tokenCount);

    List<AiMessageRecord> selectByConversation(@Param("scope") String scope,
            @Param("conversationId") String conversationId);
}
