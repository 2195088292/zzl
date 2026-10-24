package com.zzl.web.mapper.ai;

import java.time.LocalDateTime;

/** 会话消息明细。AiConversationStore 继续保存轻量上下文，本表保存可查询的 V6 消息审计。 */
public class AiMessageRecord
{
    private String messageId;
    private String scope;
    private String conversationId;
    private String runId;
    private String role;
    private String content;
    private String model;
    private Integer tokenCount;
    private LocalDateTime createdAt;

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Integer getTokenCount() { return tokenCount; }
    public void setTokenCount(Integer tokenCount) { this.tokenCount = tokenCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
