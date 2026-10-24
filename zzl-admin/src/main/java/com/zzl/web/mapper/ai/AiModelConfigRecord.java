package com.zzl.web.mapper.ai;

import java.time.LocalDateTime;

/** 按用户保存的运行时 AI 模型配置。 */
public class AiModelConfigRecord
{
    private String scope;
    private String modelId;
    private String label;
    private String baseUrl;
    private String apiKey;
    private String modelName;
    private String modelCatalog;
    private Boolean enabled;
    private Double temperature;
    private Integer maxTokens;
    private String providerType;
    private String capabilityProtocol;
    private String capabilities;
    private String reasoningEffort;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getModelCatalog() { return modelCatalog; }
    public void setModelCatalog(String modelCatalog) { this.modelCatalog = modelCatalog; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    public String getProviderType() { return providerType; }
    public void setProviderType(String providerType) { this.providerType = providerType; }
    public String getCapabilityProtocol() { return capabilityProtocol; }
    public void setCapabilityProtocol(String capabilityProtocol) { this.capabilityProtocol = capabilityProtocol; }
    public String getCapabilities() { return capabilities; }
    public void setCapabilities(String capabilities) { this.capabilities = capabilities; }
    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
