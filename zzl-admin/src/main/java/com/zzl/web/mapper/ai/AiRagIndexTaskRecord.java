package com.zzl.web.mapper.ai;

import java.time.LocalDateTime;

/** 持久化的 RAG 索引任务状态。 */
public class AiRagIndexTaskRecord
{
    private String taskId;
    private String scope;
    private String fileId;
    private String fileName;
    private String status;
    private Integer chunks;
    private String message;
    private Integer attempts;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer progress;
    private String stage;
    private String indexSignature;

    public Integer getProgress() { return progress; }
    public void setProgress(Integer value) { progress = value; }
    public String getStage() { return stage; }
    public void setStage(String value) { stage = value; }
    public String getIndexSignature() { return indexSignature; }
    public void setIndexSignature(String value) { indexSignature = value; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getChunks() { return chunks; }
    public void setChunks(Integer chunks) { this.chunks = chunks; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
