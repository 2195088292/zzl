package com.zzl.web.mapper.ai;

import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface AiDocumentChunkMapper
{
    int insert(@Param("chunkId") String chunkId, @Param("fileId") String fileId,
            @Param("scope") String scope, @Param("conversationId") String conversationId,
            @Param("sourceName") String sourceName, @Param("chunkIndex") Integer chunkIndex,
            @Param("startOffset") Integer startOffset, @Param("endOffset") Integer endOffset,
            @Param("content") String content, @Param("keywords") String keywords,
            @Param("embedding") String embedding, @Param("embeddingModel") String embeddingModel);

    int deleteByFile(@Param("scope") String scope, @Param("fileId") String fileId);

    List<AiDocumentChunkRecord> selectByScope(@Param("scope") String scope,
            @Param("conversationId") String conversationId, @Param("fileIds") List<String> fileIds,
            @Param("limit") int limit);

    List<AiDocumentChunkRecord> selectKeywordByScope(@Param("scope") String scope,
            @Param("conversationId") String conversationId, @Param("fileIds") List<String> fileIds,
            @Param("query") String query, @Param("limit") int limit);

    List<AiDocumentChunkRecord> selectByChunkIds(@Param("scope") String scope,
            @Param("chunkIds") List<String> chunkIds);
}
