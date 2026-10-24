package com.zzl.web.service.ai;

/** 可替换的文本向量提供器。外部 Embedding 服务不可用时由本地实现兜底。 */
public interface AiEmbeddingProvider
{
    String model();
    float[] embed(String text);
}
