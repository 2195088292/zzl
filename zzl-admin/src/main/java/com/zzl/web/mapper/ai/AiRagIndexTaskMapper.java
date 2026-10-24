package com.zzl.web.mapper.ai;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** RAG 索引任务持久化 Mapper。 */
public interface AiRagIndexTaskMapper
{
    int insert(@Param("taskId") String taskId, @Param("scope") String scope,
            @Param("fileId") String fileId, @Param("fileName") String fileName,
            @Param("status") String status, @Param("chunks") Integer chunks,
            @Param("message") String message, @Param("attempts") Integer attempts);

    AiRagIndexTaskRecord selectLatestByFile(@Param("scope") String scope, @Param("fileId") String fileId);

    AiRagIndexTaskRecord selectByTaskId(@Param("scope") String scope, @Param("taskId") String taskId);

    List<AiRagIndexTaskRecord> selectPending();

    int updateStatus(@Param("taskId") String taskId, @Param("status") String status,
            @Param("chunks") Integer chunks, @Param("message") String message,
            @Param("attempts") Integer attempts, @Param("startedAt") LocalDateTime startedAt,
            @Param("finishedAt") LocalDateTime finishedAt);

    int deleteByFile(@Param("scope") String scope, @Param("fileId") String fileId);

    int updateProgress(@Param("taskId") String taskId, @Param("progress") int progress,
            @Param("stage") String stage, @Param("indexSignature") String indexSignature);
}
