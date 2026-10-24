package com.zzl.web.service.ai;

import java.util.List;
import java.util.Map;

/**
 * 可选的外部向量库适配层。
 *
 * <p>MySQL JSON 仍然是系统的本地兜底；实现不可用时返回空结果，调用方继续走本地关键词/向量检索。</p>
 */
public interface AiVectorStore
{
    /** 当前适配器是否启用并可参与检索。 */
    boolean enabled();

    /** 写入一个文档片段。失败时返回 false，不应阻断 MySQL 索引。 */
    default boolean upsert(String chunkId, float[] vector, Map<String, Object> payload)
    {
        return false;
    }

    /** 按向量检索，结果中的 chunkId 对应 MySQL 文档切片主键。 */
    default List<VectorHit> search(float[] vector, int limit, String scope, List<String> fileIds)
    {
        return List.of();
    }

    /** 删除某个文件的外部向量。 */
    default boolean deleteByFile(String scope, String fileId)
    {
        return false;
    }

    /** 每个知识库/向量模型拥有独立空间，普通会话保留默认空间。 */
    default AiVectorStore namespace(String namespace) { return this; }

    record VectorHit(String chunkId, float score) { }
}
