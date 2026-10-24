package com.zzl.web.service.ai.adapter;

import com.zzl.web.service.ai.AiProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 统一识别厂商、能力协议和可用推理档位。 */
@Component
public class AiModelAdapterRegistry
{
    private final List<AiModelAdapter> adapters;

    public AiModelAdapterRegistry()
    {
        this.adapters = List.of(new OpenAiModelAdapter(), new AnthropicModelAdapter(),
                new DeepSeekModelAdapter(), new OllamaModelAdapter(), new GenericModelAdapter());
    }

    public AiModelAdapter resolve(AiProperties.ModelProperties config)
    {
        String provider = providerType(config);
        return adapters.stream().filter(item -> item.providerType().equals(provider)).findFirst()
                .orElse(adapters.get(adapters.size() - 1));
    }

    public String providerType(AiProperties.ModelProperties config)
    {
        String explicit = config == null ? "" : normalize(config.getProviderType());
        if (!explicit.isBlank() && !"auto".equals(explicit))
        {
            if ("anthropic".equals(explicit)) return "claude";
            if ("generic".equals(explicit)) return "custom";
            return explicit;
        }
        String text = ((config == null ? "" : config.getBaseUrl()) + " "
                + (config == null ? "" : config.getModelName()) + " "
                + (config == null ? "" : config.getLabel())).toLowerCase(Locale.ROOT);
        if (text.contains("anthropic") || text.contains("claude")) return "claude";
        if (text.contains("deepseek")) return "deepseek";
        if (text.contains("ollama") || text.contains("localhost:11434") || text.contains("127.0.0.1:11434")) return "ollama";
        if (text.contains("api.openai.com") || text.matches(".*\\bgpt[-_].*")) return "openai";
        return "custom";
    }

    public String capabilityProtocol(AiProperties.ModelProperties config)
    {
        String explicit = config == null ? "" : normalize(config.getCapabilityProtocol());
        if (!explicit.isBlank() && !"auto".equals(explicit)) return explicit;
        return resolve(config).capabilityProtocol();
    }

    public List<String> reasoningLevels(AiProperties.ModelProperties config)
    {
        if (config != null && config.getReasoningLevels() != null && !config.getReasoningLevels().isEmpty())
        {
            return normalizeLevels(config.getReasoningLevels());
        }
        return resolve(config).defaultReasoningLevels();
    }

    public Map<String, Object> capabilityView(AiProperties.ModelProperties config)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("providerType", providerType(config));
        result.put("capabilityProtocol", capabilityProtocol(config));
        result.put("reasoningLevels", reasoningLevels(config));
        result.put("supportsVision", config == null ? null : config.getSupportsVision());
        result.put("supportsTools", config == null ? null : config.getSupportsTools());
        return result;
    }

    public void normalize(AiProperties.ModelProperties config)
    {
        if (config == null) return;
        config.setProviderType(providerType(config));
        config.setCapabilityProtocol(capabilityProtocol(config));
        config.setReasoningLevels(reasoningLevels(config));
        if (config.getReasoningEffort() == null || !reasoningLevels(config).contains(config.getReasoningEffort()))
        {
            config.setReasoningEffort("auto");
        }
    }

    private List<String> normalizeLevels(List<String> values)
    {
        List<String> result = new ArrayList<>();
        for (String value : values)
        {
            String normalized = normalize(value);
            if (!normalized.isBlank() && !result.contains(normalized)) result.add(normalized);
        }
        if (!result.contains("auto")) result.add(0, "auto");
        return result;
    }

    private String normalize(String value)
    {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
