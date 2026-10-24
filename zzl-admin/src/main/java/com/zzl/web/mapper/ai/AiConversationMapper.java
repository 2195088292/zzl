package com.zzl.web.mapper.ai;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** MySQL 持久化的 AI 会话数据访问接口。 */
public interface AiConversationMapper
{
    List<AiConversationRecord> selectByScope(@Param("scope") String scope);

    AiConversationRecord selectOne(@Param("scope") String scope, @Param("conversationId") String conversationId);

    int upsert(@Param("scope") String scope,
            @Param("conversationId") String conversationId,
            @Param("title") String title,
            @Param("model") String model,
            @Param("turns") String turns);

    int delete(@Param("scope") String scope, @Param("conversationId") String conversationId);
}
