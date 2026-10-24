package com.zzl.web.mapper.ai;

import java.time.LocalDateTime;

/** Agent 一次运行记录。事件以 JSON 数组保存在 events 字段，便于 SSE 时间线回放。 */
public class AiRunRecord
{
    private String runId;
    private String scope;
    private String conversationId;
    private String model;
    private String question;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long durationMs;
    private String answer;
    private String errorMessage;
    private String events;
    private LocalDateTime createdAt;

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(LocalDateTime endedAt) { this.endedAt = endedAt; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getEvents() { return events; }
    public void setEvents(String events) { this.events = events; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
