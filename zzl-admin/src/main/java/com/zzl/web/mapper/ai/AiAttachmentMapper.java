package com.zzl.web.mapper.ai;

import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface AiAttachmentMapper
{
    int upsert(@Param("fileId") String fileId, @Param("scope") String scope,
            @Param("conversationId") String conversationId, @Param("originalName") String originalName,
            @Param("extension") String extension, @Param("contentType") String contentType,
            @Param("size") Long size, @Param("path") String path,
            @Param("extractedChars") Integer extractedChars, @Param("textExtracted") Boolean textExtracted,
            @Param("extractMessage") String extractMessage);

    List<AiAttachmentRecord> selectByScope(@Param("scope") String scope,
            @Param("conversationId") String conversationId);

    AiAttachmentRecord selectOne(@Param("scope") String scope, @Param("fileId") String fileId);

    int delete(@Param("scope") String scope, @Param("fileId") String fileId);
}
