package com.zzl.web.service.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 知识库分块、召回和独立 Embedding 配置。写入失败必须向调用方报告。 */
@Service
public class AiKnowledgeBaseConfigService
{
    private final JdbcTemplate jdbc;

    public AiKnowledgeBaseConfigService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Settings defaults() { return new Settings(1200, 180, 6, 0.10f, true, "global", "global", "", ""); }

    public Settings forKnowledgeBase(String scope, String id)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT chunk_size, chunk_overlap, top_k, similarity_threshold, rerank_enabled, embedding_model, "
                        + "embedding_config FROM zzl_ai_knowledge_base WHERE scope = ? AND knowledge_base_id = ?",
                safeScope(scope), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("知识库不存在");
        Map<String, Object> row = rows.get(0);
        JSONObject embedding = row.get("embedding_config") == null ? new JSONObject()
                : JSON.parseObject(String.valueOf(row.get("embedding_config")));
        return validate(new Settings(number(row.get("chunk_size"), 1200), number(row.get("chunk_overlap"), 180),
                number(row.get("top_k"), 6), decimal(row.get("similarity_threshold"), 0.10f),
                number(row.get("rerank_enabled"), 1) != 0, String.valueOf(row.get("embedding_model")),
                embedding.getString("provider") == null ? "global" : embedding.getString("provider"),
                embedding.getString("baseUrl"), embedding.getString("apiKey")));
    }

    public Settings forStorageConversation(String scope, String conversationId)
    {
        return conversationId != null && conversationId.startsWith("kb:")
                ? forKnowledgeBase(scope, conversationId.substring(3)) : defaults();
    }

    /** 只合并最终结果数量；各库的向量模型和阈值在检索时分别应用。 */
    public Settings forFiles(String scope, List<String> fileIds)
    {
        if (fileIds == null || fileIds.isEmpty()) return defaults();
        String marks = String.join(",", java.util.Collections.nCopies(fileIds.size(), "?"));
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        args.add(safeScope(scope));
        args.addAll(fileIds);
        List<Integer> counts = jdbc.query(
                "SELECT DISTINCT b.top_k FROM zzl_ai_knowledge_base b JOIN zzl_ai_knowledge_document d "
                        + "ON d.scope = b.scope AND d.knowledge_base_id = b.knowledge_base_id "
                        + "WHERE b.enabled = 1 AND d.scope = ? AND d.file_id IN (" + marks + ")",
                (rs, i) -> rs.getInt(1), args.toArray());
        return defaults().withTopK(counts.stream().mapToInt(Integer::intValue).max().orElse(6));
    }

    public Settings update(String scope, String id, Integer chunkSize, Integer overlap, Integer topK,
            Float threshold, Boolean rerank, String model, String provider, String baseUrl, String apiKey)
    {
        Settings old = forKnowledgeBase(scope, id);
        Settings next = validate(new Settings(chunkSize == null ? old.chunkSize() : chunkSize,
                overlap == null ? old.chunkOverlap() : overlap, topK == null ? old.topK() : topK,
                threshold == null ? old.similarityThreshold() : threshold,
                rerank == null ? old.rerankEnabled() : rerank, model == null ? old.embeddingModel() : model,
                provider == null ? old.embeddingProvider() : provider,
                baseUrl == null ? old.embeddingBaseUrl() : baseUrl,
                apiKey == null ? old.embeddingApiKey() : apiKey));
        jdbc.update("UPDATE zzl_ai_knowledge_base SET chunk_size=?, chunk_overlap=?, top_k=?, "
                        + "similarity_threshold=?, rerank_enabled=?, embedding_model=?, embedding_config=? "
                        + "WHERE scope=? AND knowledge_base_id=?",
                next.chunkSize(), next.chunkOverlap(), next.topK(), next.similarityThreshold(),
                next.rerankEnabled() ? 1 : 0, next.embeddingModel(),
                JSON.toJSONString(Map.of("provider", next.embeddingProvider(), "baseUrl", next.embeddingBaseUrl(),
                        "apiKey", next.embeddingApiKey())), safeScope(scope), id);
        return next;
    }

    public Map<String, Object> view(String scope, String id) { return view(forKnowledgeBase(scope, id)); }

    public void markIndexed(String scope, String fileId, String signature)
    {
        jdbc.update("UPDATE zzl_ai_knowledge_document SET index_signature=? WHERE scope=? AND file_id=?",
                signature, safeScope(scope), fileId);
    }

    public Map<String, Object> view(Settings settings)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chunkSize", settings.chunkSize());
        result.put("chunkOverlap", settings.chunkOverlap());
        result.put("topK", settings.topK());
        result.put("similarityThreshold", settings.similarityThreshold());
        result.put("rerankEnabled", settings.rerankEnabled());
        result.put("embeddingModel", settings.embeddingModel());
        result.put("embeddingProvider", settings.embeddingProvider());
        result.put("embeddingBaseUrl", settings.embeddingBaseUrl());
        result.put("apiKeyConfigured", !settings.embeddingApiKey().isBlank());
        result.put("indexSignature", settings.indexSignature());
        return result;
    }

    private Settings validate(Settings value)
    {
        if (value.chunkSize() < 300 || value.chunkSize() > 8000)
            throw new IllegalArgumentException("分块大小需为 300–8000 个字符");
        if (value.chunkOverlap() < 0 || value.chunkOverlap() > value.chunkSize() / 2)
            throw new IllegalArgumentException("重叠长度不能超过分块大小的一半");
        if (value.topK() < 1 || value.topK() > 20)
            throw new IllegalArgumentException("召回数量需为 1–20");
        if (!Float.isFinite(value.similarityThreshold()) || value.similarityThreshold() < 0 || value.similarityThreshold() > 1)
            throw new IllegalArgumentException("相似度阈值需为 0–1");
        if (!List.of("global", "local", "openai", "ollama").contains(value.embeddingProvider()))
            throw new IllegalArgumentException("不支持该向量服务");
        if (List.of("openai", "ollama").contains(value.embeddingProvider())
                && (!StringUtils.hasText(value.embeddingBaseUrl()) || !StringUtils.hasText(value.embeddingModel())))
            throw new IllegalArgumentException("请填写向量服务地址和模型名称");
        return value;
    }

    private int number(Object value, int fallback)
    {
        if (value instanceof Boolean flag) return flag ? 1 : 0;
        return value instanceof Number n ? n.intValue() : fallback;
    }
    private float decimal(Object value, float fallback) { return value instanceof Number n ? n.floatValue() : fallback; }
    private String safeScope(String value) { return StringUtils.hasText(value) ? value.trim() : "anonymous"; }

    public record Settings(int chunkSize, int chunkOverlap, int topK, float similarityThreshold,
            boolean rerankEnabled, String embeddingModel, String embeddingProvider,
            String embeddingBaseUrl, String embeddingApiKey)
    {
        public Settings
        {
            embeddingProvider = embeddingProvider == null ? "global" : embeddingProvider;
            embeddingModel = embeddingModel == null ? "global" : embeddingModel.trim();
            embeddingBaseUrl = embeddingBaseUrl == null ? "" : embeddingBaseUrl.trim().replaceAll("/+$", "");
            embeddingApiKey = embeddingApiKey == null ? "" : embeddingApiKey;
        }

        public Settings(int chunkSize, int overlap, int topK, float threshold, boolean rerank, String model)
        {
            this(chunkSize, overlap, topK, threshold, rerank, model, "global", "", "");
        }

        public Settings withTopK(int value)
        {
            return new Settings(chunkSize, chunkOverlap, Math.max(1, Math.min(value, 20)), similarityThreshold,
                    rerankEnabled, embeddingModel, embeddingProvider, embeddingBaseUrl, embeddingApiKey);
        }

        public String indexSignature()
        {
            return UUID.nameUUIDFromBytes(("v2|" + chunkSize + "|" + chunkOverlap + "|" + embeddingProvider
                    + "|" + embeddingBaseUrl + "|" + embeddingModel).getBytes(StandardCharsets.UTF_8)).toString();
        }
    }
}
