package com.zzl.web.service.ai;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** AI 助手的模型配置。运行时按厂商类型和能力协议选择适配器。 */
@Component
@ConfigurationProperties(prefix = "zzl.ai")
public class AiProperties
{
    private boolean enabled;
    private String defaultModel = "ollama";
    private Map<String, ModelProperties> models = new LinkedHashMap<>();

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    public String getDefaultModel()
    {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel)
    {
        this.defaultModel = defaultModel;
    }

    public Map<String, ModelProperties> getModels()
    {
        return models;
    }

    public void setModels(Map<String, ModelProperties> models)
    {
        this.models = models == null ? new LinkedHashMap<>() : models;
    }

    public static class ModelProperties
    {
        private String label;
        private String baseUrl;
        private String apiKey;
        private String modelName;
        private List<ModelOption> modelCatalog = new ArrayList<>();
        private boolean enabled = true;
        private Double temperature = 0.2D;
        private Integer maxTokens = 1200;
        /** auto 表示根据地址和模型名称识别厂商。 */
        private String providerType = "auto";
        /** auto 表示由厂商适配器选择能力协议。 */
        private String capabilityProtocol = "auto";
        /** 空数组表示使用适配器默认能力；前端会据此生成推理强度选择项。 */
        private List<String> reasoningLevels = new ArrayList<>();
        /** OpenAI/Claude 等视觉能力可由模型目录覆盖，空值表示未知。 */
        private Boolean supportsVision;
        private Boolean supportsTools;
        /** 兼容协议的推理强度；auto 表示交给模型默认策略。 */
        private String reasoningEffort = "auto";

        public String getLabel()
        {
            return label;
        }

        public void setLabel(String label)
        {
            this.label = label;
        }

        public String getBaseUrl()
        {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl)
        {
            this.baseUrl = baseUrl;
        }

        public String getApiKey()
        {
            return apiKey;
        }

        public void setApiKey(String apiKey)
        {
            this.apiKey = apiKey;
        }

        public String getModelName()
        {
            return modelName;
        }

        public void setModelName(String modelName)
        {
            this.modelName = modelName;
        }

        public List<ModelOption> getModelCatalog()
        {
            return modelCatalog;
        }

        public void setModelCatalog(List<ModelOption> modelCatalog)
        {
            this.modelCatalog = modelCatalog == null ? new ArrayList<>() : new ArrayList<>(modelCatalog);
        }

        public boolean isEnabled()
        {
            return enabled;
        }

        public void setEnabled(boolean enabled)
        {
            this.enabled = enabled;
        }

        public Double getTemperature()
        {
            return temperature;
        }

        public void setTemperature(Double temperature)
        {
            this.temperature = temperature;
        }

        public Integer getMaxTokens()
        {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens)
        {
            this.maxTokens = maxTokens;
        }

        public String getProviderType()
        {
            return providerType;
        }

        public void setProviderType(String providerType)
        {
            this.providerType = providerType == null || providerType.isBlank()
                    ? "auto" : providerType.trim().toLowerCase();
        }

        public String getCapabilityProtocol()
        {
            return capabilityProtocol;
        }

        public void setCapabilityProtocol(String capabilityProtocol)
        {
            this.capabilityProtocol = capabilityProtocol == null || capabilityProtocol.isBlank()
                    ? "auto" : capabilityProtocol.trim().toLowerCase();
        }

        public List<String> getReasoningLevels()
        {
            return reasoningLevels;
        }

        public void setReasoningLevels(List<String> reasoningLevels)
        {
            this.reasoningLevels = reasoningLevels == null ? new ArrayList<>() : new ArrayList<>(reasoningLevels);
        }

        public Boolean getSupportsVision()
        {
            return supportsVision;
        }

        public void setSupportsVision(Boolean supportsVision)
        {
            this.supportsVision = supportsVision;
        }

        public Boolean getSupportsTools()
        {
            return supportsTools;
        }

        public void setSupportsTools(Boolean supportsTools)
        {
            this.supportsTools = supportsTools;
        }

        public String getReasoningEffort()
        {
            return reasoningEffort;
        }

        public void setReasoningEffort(String reasoningEffort)
        {
            this.reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank()
                    ? "auto" : reasoningEffort.trim().toLowerCase();
        }

        public boolean isConfigured()
        {
            return enabled && notBlank(baseUrl) && notBlank(modelName) && notBlank(apiKey);
        }

        private static boolean notBlank(String value)
        {
            return value != null && !value.trim().isEmpty();
        }
    }

    /** 模型目录项：id 用于实际请求，label 用于界面展示。 */
    public static class ModelOption
    {
        private String id;
        private String label;

        public ModelOption()
        {
        }

        public ModelOption(String id, String label)
        {
            this.id = id;
            this.label = label == null || label.isBlank() ? id : label;
        }

        public String getId()
        {
            return id;
        }

        public void setId(String id)
        {
            this.id = id;
        }

        public String getLabel()
        {
            return label;
        }

        public void setLabel(String label)
        {
            this.label = label;
        }
    }
}
