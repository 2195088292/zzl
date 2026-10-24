package com.zzl.web.service.ai.adapter;

import com.zzl.web.service.ai.AiProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.List;

/** 一个厂商一个适配器，隔离请求协议和推理能力映射。 */
public interface AiModelAdapter
{
    String providerType();

    String capabilityProtocol();

    boolean supports(AiProperties.ModelProperties config);

    List<String> defaultReasoningLevels();

    ChatModel buildChatModel(AiProperties.ModelProperties config);

    StreamingChatModel buildStreamingChatModel(AiProperties.ModelProperties config);
}
