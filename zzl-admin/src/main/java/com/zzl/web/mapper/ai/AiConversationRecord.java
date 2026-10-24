package com.zzl.web.mapper.ai;

import java.time.LocalDateTime;

/** AI 会话表的持久化记录。 */
public class AiConversationRecord
{
    private String scope;
    private String conversationId;
    private String title;
    private String model;
    private String turns;
    private LocalDateTime updatedAt;

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getTurns() { return turns; }
    public void setTurns(String turns) { this.turns = turns; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
