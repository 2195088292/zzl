package com.zzl.web.service.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 未配置外部向量库时的空实现，保持 MySQL JSON 检索链路无需额外基础设施。 */
@Component
@ConditionalOnProperty(name = "zzl.ai.rag.vector-store.provider", havingValue = "mysql", matchIfMissing = true)
public class DisabledAiVectorStore implements AiVectorStore
{
    @Override
    public boolean enabled()
    {
        return false;
    }
}
