package com.zzl.web.service.ai.adapter;

import com.zzl.web.service.ai.AiProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.util.List;

/** DeepSeek、Ollama 和通用厂商均可复用 OpenAI 兼容传输，但能力协议独立。 */
public abstract class AbstractOpenAiCompatibleAdapter implements AiModelAdapter
{
    @Override
    public List<String> defaultReasoningLevels()
    {
        return List.of("auto", "low", "medium", "high");
    }

    @Override
    public ChatModel buildChatModel(AiProperties.ModelProperties config)
    {
        var builder = OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens());
        applyReasoning(builder, config);
        return builder.build();
    }

    @Override
    public StreamingChatModel buildStreamingChatModel(AiProperties.ModelProperties config)
    {
        var builder = OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens());
        applyReasoning(builder, config);
        return builder.build();
    }

    protected void applyReasoning(OpenAiChatModel.OpenAiChatModelBuilder builder,
            AiProperties.ModelProperties config)
    {
        String value = reasoningValue(config);
        if (value != null)
        {
            builder.reasoningEffort(value);
        }
    }

    protected void applyReasoning(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder,
            AiProperties.ModelProperties config)
    {
        String value = reasoningValue(config);
        if (value != null)
        {
            builder.reasoningEffort(value);
        }
    }

    protected String reasoningValue(AiProperties.ModelProperties config)
    {
        String value = config == null ? null : config.getReasoningEffort();
        return "low".equals(value) || "medium".equals(value) || "high".equals(value) ? value : null;
    }
}
