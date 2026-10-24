package com.zzl.web.service.ai.adapter;

import com.zzl.web.service.ai.AiProperties;

/** 未知厂商的兜底适配器，默认使用通用 OpenAI 兼容协议。 */
public class GenericModelAdapter extends AbstractOpenAiCompatibleAdapter
{
    @Override
    public String providerType()
    {
        return "custom";
    }

    @Override
    public String capabilityProtocol()
    {
        return "generic_reasoning_effort";
    }

    @Override
    public boolean supports(AiProperties.ModelProperties config)
    {
        return true;
    }
}
