package com.zzl.web.service.ai.adapter;

import com.zzl.web.service.ai.AiProperties;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.List;

/** Claude 原生 Messages 协议适配器。 */
public class AnthropicModelAdapter implements AiModelAdapter
{
    @Override
    public String providerType()
    {
        return "claude";
    }

    @Override
    public String capabilityProtocol()
    {
        return "anthropic_thinking";
    }

    @Override
    public boolean supports(AiProperties.ModelProperties config)
    {
        String provider = config == null ? "" : config.getProviderType();
        return "claude".equalsIgnoreCase(provider) || "anthropic".equalsIgnoreCase(provider);
    }

    @Override
    public List<String> defaultReasoningLevels()
    {
        return List.of("auto", "low", "medium", "high");
    }

    @Override
    public ChatModel buildChatModel(AiProperties.ModelProperties config)
    {
        var builder = AnthropicChatModel.builder()
                .baseUrl(normalizeBaseUrl(config.getBaseUrl()))
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .maxTokens(maxTokens(config));
        applyThinking(builder, config);
        return builder.build();
    }

    @Override
    public StreamingChatModel buildStreamingChatModel(AiProperties.ModelProperties config)
    {
        var builder = AnthropicStreamingChatModel.builder()
                .baseUrl(normalizeBaseUrl(config.getBaseUrl()))
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .maxTokens(maxTokens(config));
        applyThinking(builder, config);
        return builder.build();
    }

    private void applyThinking(AnthropicChatModel.AnthropicChatModelBuilder builder,
            AiProperties.ModelProperties config)
    {
        Integer budget = thinkingBudget(config);
        if (budget != null)
        {
            builder.thinkingType("enabled").thinkingBudgetTokens(budget).sendThinking(false);
        }
    }

    private void applyThinking(AnthropicStreamingChatModel.AnthropicStreamingChatModelBuilder builder,
            AiProperties.ModelProperties config)
    {
        Integer budget = thinkingBudget(config);
        if (budget != null)
        {
            builder.thinkingType("enabled").thinkingBudgetTokens(budget).sendThinking(false);
        }
    }

    private Integer thinkingBudget(AiProperties.ModelProperties config)
    {
        String value = config == null ? null : config.getReasoningEffort();
        if ("low".equals(value)) return 1024;
        if ("medium".equals(value)) return 2048;
        if ("high".equals(value)) return 4096;
        return null;
    }

    private int maxTokens(AiProperties.ModelProperties config)
    {
        Integer configured = config.getMaxTokens();
        int output = configured == null ? 1200 : configured;
        Integer budget = thinkingBudget(config);
        return budget == null ? output : Math.max(output, budget + 512);
    }

    private String normalizeBaseUrl(String value)
    {
        if (value == null) return "";
        String result = value.trim();
        while (result.endsWith("/") && result.length() > 1) result = result.substring(0, result.length() - 1);
        return result.endsWith("/v1") ? result.substring(0, result.length() - 3) : result;
    }
}
