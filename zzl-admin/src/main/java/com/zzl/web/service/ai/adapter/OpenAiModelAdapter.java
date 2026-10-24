package com.zzl.web.service.ai.adapter;

import com.zzl.web.service.ai.AiProperties;

/** OpenAI 厂商适配器。 */
public class OpenAiModelAdapter extends AbstractOpenAiCompatibleAdapter
{
    @Override
    public String providerType()
    {
        return "openai";
    }

    @Override
    public String capabilityProtocol()
    {
        return "openai_reasoning_effort";
    }

    @Override
    public boolean supports(AiProperties.ModelProperties config)
    {
        return "openai".equalsIgnoreCase(config == null ? "" : config.getProviderType());
    }
}
