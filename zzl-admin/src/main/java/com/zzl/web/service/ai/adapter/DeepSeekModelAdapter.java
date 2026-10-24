package com.zzl.web.service.ai.adapter;

import com.zzl.web.service.ai.AiProperties;
import java.util.List;

/** DeepSeek 适配器：使用 OpenAI 兼容传输，推理由 DeepSeek 自身策略控制。 */
public class DeepSeekModelAdapter extends AbstractOpenAiCompatibleAdapter
{
    @Override
    public String providerType()
    {
        return "deepseek";
    }

    @Override
    public String capabilityProtocol()
    {
        return "deepseek_native";
    }

    @Override
    public boolean supports(AiProperties.ModelProperties config)
    {
        return "deepseek".equalsIgnoreCase(config == null ? "" : config.getProviderType());
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
