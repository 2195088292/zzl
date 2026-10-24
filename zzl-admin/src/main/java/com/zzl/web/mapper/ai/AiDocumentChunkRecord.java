package com.zzl.web.mapper.ai;

import java.time.LocalDateTime;

/** 文档切片及来源位置。正文保存在数据库，便于后续重建索引和审计。 */
public class AiDocumentChunkRecord
{
    private String chunkId;
    private String fileId;
    private String scope;
    private String conversationId;
    private String sourceName;
    private Integer chunkIndex;
    private Integer startOffset;
    private Integer endOffset;
    private String content;
    private String keywords;
    private String embedding;
    private String embeddingModel;
    private LocalDateTime createdAt;

    public String getChunkId() { return chunkId; }
    public void setChunkId(String chunkId) { this.chunkId = chunkId; }
    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }
    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }
    public Integer getStartOffset() { return startOffset; }
    public void setStartOffset(Integer startOffset) { this.startOffset = startOffset; }
    public Integer getEndOffset() { return endOffset; }
    public void setEndOffset(Integer endOffset) { this.endOffset = endOffset; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }
    public String getEmbedding() { return embedding; }
    public void setEmbedding(String embedding) { this.embedding = embedding; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
