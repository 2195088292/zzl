package com.zzl.web.service.ai.adapter;

import com.zzl.web.service.ai.AiProperties;
import java.util.List;

/** Ollama 适配器：通过本地 OpenAI 兼容端点调用，不发送云厂商推理字段。 */
public class OllamaModelAdapter extends AbstractOpenAiCompatibleAdapter
{
    @Override
    public String providerType()
    {
        return "ollama";
    }

    @Override
    public String capabilityProtocol()
    {
        return "ollama_native";
    }

    @Override
    public boolean supports(AiProperties.ModelProperties config)
    {
        return "ollama".equalsIgnoreCase(config == null ? "" : config.getProviderType());
    }

    @Override
    public List<String> defaultReasoningLevels()
    {
        return List.of("auto");
    }

    @Override
    protected String reasoningValue(AiProperties.ModelProperties config)
    {
        return null;
    }
}
