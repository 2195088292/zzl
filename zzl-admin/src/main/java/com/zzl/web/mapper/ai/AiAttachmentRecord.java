package com.zzl.web.mapper.ai;

import java.time.LocalDateTime;

/** AI 工作区上传文件的持久化元数据。文件正文仍由 AiFileService 保存在 profile 目录。 */
public class AiAttachmentRecord
{
    private String fileId;
    private String scope;
    private String conversationId;
    private String originalName;
    private String extension;
    private String contentType;
    private Long size;
    private String path;
    private Integer extractedChars;
    private Boolean textExtracted;
    private String extractMessage;
    private LocalDateTime uploadedAt;

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public String getExtension() { return extension; }
    public void setExtension(String extension) { this.extension = extension; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getSize() { return size; }
    public void setSize(Long size) { this.size = size; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public Integer getExtractedChars() { return extractedChars; }
    public void setExtractedChars(Integer extractedChars) { this.extractedChars = extractedChars; }
    public Boolean getTextExtracted() { return textExtracted; }
    public void setTextExtracted(Boolean textExtracted) { this.textExtracted = textExtracted; }
    public String getExtractMessage() { return extractMessage; }
    public void setExtractMessage(String extractMessage) { this.extractMessage = extractMessage; }
    public LocalDateTime getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(LocalDateTime uploadedAt) { this.uploadedAt = uploadedAt; }
}
