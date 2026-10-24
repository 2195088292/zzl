package com.zzl.web.service.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Qdrant HTTP 向量库适配器。
 *
 * <p>这是可选增强：Qdrant 未启动、集合维度不匹配或请求失败时只返回空结果，
 * AiRagService 会继续使用 MySQL JSON + 关键词检索。</p>
 */
@Component
@ConditionalOnProperty(name = "zzl.ai.rag.vector-store.provider", havingValue = "qdrant")
public class QdrantVectorStore implements AiVectorStore
{
    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);

    private final boolean configured;
    private final String baseUrl;
    private final String apiKey;
    private final String collection;
    private final boolean autoCreate;
    private final int requestTimeoutMs;
    private final int searchLimit;
    private final HttpClient client;
    private final Object lock = new Object();
    private volatile boolean collectionReady;
    private volatile int collectionDimension;
    private final Map<String, AiVectorStore> namespaces = new java.util.concurrent.ConcurrentHashMap<>();

    public QdrantVectorStore(
            @Value("${zzl.ai.rag.vector-store.enabled:true}") boolean enabled,
            @Value("${zzl.ai.rag.vector-store.base-url:http://localhost:6333}") String baseUrl,
            @Value("${zzl.ai.rag.vector-store.api-key:}") String apiKey,
            @Value("${zzl.ai.rag.vector-store.collection:zzl_ai_chunks}") String collection,
            @Value("${zzl.ai.rag.vector-store.auto-create:true}") boolean autoCreate,
            @Value("${zzl.ai.rag.vector-store.timeout-ms:1800}") int requestTimeoutMs,
            @Value("${zzl.ai.rag.vector-store.search-limit:40}") int searchLimit,
            AiEmbeddingProvider embeddingProvider)
    {
        this.configured = enabled && StringUtils.hasText(baseUrl);
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.collection = sanitizeCollection(collection);
        this.autoCreate = autoCreate;
        this.requestTimeoutMs = Math.max(500, Math.min(requestTimeoutMs, 15_000));
        this.searchLimit = Math.max(1, Math.min(searchLimit, 100));
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.requestTimeoutMs)).build();
    }

    @Override
    public boolean enabled()
    {
        return configured;
    }

    @Override
    public AiVectorStore namespace(String namespace)
    {
        if (!StringUtils.hasText(namespace)) return this;
        return namespaces.computeIfAbsent(namespace, key -> new QdrantVectorStore(configured, baseUrl, apiKey,
                collection + "_" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString().replace("-", ""),
                autoCreate, requestTimeoutMs, searchLimit, null));
    }

    @Override
    public boolean upsert(String chunkId, float[] vector, Map<String, Object> payload)
    {
        if (!configured || !StringUtils.hasText(chunkId) || vector == null || vector.length == 0)
        {
            return false;
        }
        if (!ensureCollection(vector.length))
        {
            return false;
        }
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", pointId(chunkId));
        point.put("vector", vector);
        Map<String, Object> safePayload = new LinkedHashMap<>();
        if (payload != null)
        {
            safePayload.putAll(payload);
        }
        safePayload.put("chunkId", chunkId);
        point.put("payload", safePayload);
        Map<String, Object> body = Map.of("points", List.of(point));
        try
        {
            HttpResponse<String> response = request("PUT", "/collections/" + collection + "/points?wait=true", body);
            return isSuccess(response);
        }
        catch (Exception ex)
        {
            log.debug("Qdrant upsert failed, fallback to MySQL vector search: {}", ex.getMessage());
            return false;
        }
    }

    @Override
    public List<VectorHit> search(float[] vector, int limit, String scope, List<String> fileIds)
    {
        if (!configured || vector == null || vector.length == 0 || !ensureCollection(vector.length))
        {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? searchLimit : limit, searchLimit));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", vector);
        body.put("limit", safeLimit);
        body.put("with_payload", true);
        body.put("filter", filter(scope, fileIds));
        try
        {
            // Qdrant 新版使用 /points/query；旧版仍使用 /points/search，按 404 自动兼容。
            HttpResponse<String> response = request("POST", "/collections/" + collection + "/points/query", body);
            if (response.statusCode() == 404)
            {
                body.remove("query");
                body.put("vector", vector);
                response = request("POST", "/collections/" + collection + "/points/search", body);
            }
            if (!isSuccess(response))
            {
                return List.of();
            }
            JSONObject root = JSON.parseObject(response.body());
            Object rawResult = root == null ? null : root.get("result");
            JSONArray result = rawResult instanceof JSONArray array ? array
                    : rawResult instanceof JSONObject object ? object.getJSONArray("points") : null;
            if (result == null || result.isEmpty())
            {
                return List.of();
            }
            List<VectorHit> hits = new ArrayList<>();
            for (Object item : result)
            {
                if (!(item instanceof JSONObject row))
                {
                    continue;
                }
                JSONObject payload = row.getJSONObject("payload");
                String chunkId = payload == null ? "" : payload.getString("chunkId");
                if (!StringUtils.hasText(chunkId))
                {
                    continue;
                }
                hits.add(new VectorHit(chunkId, row.getFloatValue("score")));
            }
            return hits;
        }
        catch (Exception ex)
        {
            log.debug("Qdrant search failed, fallback to MySQL vector search: {}", ex.getMessage());
            return List.of();
        }
    }

    @Override
    public boolean deleteByFile(String scope, String fileId)
    {
        if (!configured || !StringUtils.hasText(fileId))
        {
            return false;
        }
        try
        {
            HttpResponse<String> response = request("POST",
                    "/collections/" + collection + "/points/delete?wait=true",
                    Map.of("filter", filter(scope, List.of(fileId))));
            return isSuccess(response);
        }
        catch (Exception ex)
        {
            log.debug("Qdrant delete failed, MySQL cleanup still completed: {}", ex.getMessage());
            return false;
        }
    }

    private boolean ensureCollection(int dimension)
    {
        if (!configured)
        {
            return false;
        }
        if (collectionReady)
        {
            return collectionDimension <= 0 || collectionDimension == dimension;
        }
        synchronized (lock)
        {
            if (collectionReady)
            {
                return collectionDimension <= 0 || collectionDimension == dimension;
            }
            try
            {
                HttpResponse<String> existing = request("GET", "/collections/" + collection, null);
                if (existing.statusCode() == 200)
                {
                    int existingDimension = readDimension(existing.body());
                    if (existingDimension > 0 && existingDimension != dimension)
                    {
                        log.warn("Qdrant collection {} dimension {} differs from embedding dimension {}; using MySQL fallback",
                                collection, existingDimension, dimension);
                        return false;
                    }
                    collectionDimension = existingDimension > 0 ? existingDimension : dimension;
                    collectionReady = true;
                    return true;
                }
                if (existing.statusCode() != 404 || !autoCreate)
                {
                    return false;
                }
                Map<String, Object> vectors = new LinkedHashMap<>();
                vectors.put("size", dimension);
                vectors.put("distance", "Cosine");
                HttpResponse<String> created = request("PUT", "/collections/" + collection,
                        Map.of("vectors", vectors));
                if (isSuccess(created) || created.statusCode() == 409)
                {
                    collectionDimension = dimension;
                    collectionReady = true;
                    return true;
                }
            }
            catch (Exception ex)
            {
                log.debug("Qdrant collection initialization failed, fallback to MySQL: {}", ex.getMessage());
            }
            return false;
        }
    }

    private HttpResponse<String> request(String method, String path, Object body) throws Exception
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofMillis(requestTimeoutMs));
        if (StringUtils.hasText(apiKey))
        {
            builder.header("api-key", apiKey);
        }
        builder.header("Content-Type", "application/json");
        String json = body == null ? "" : JSON.toJSONString(body);
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8);
        builder.method(method, publisher);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private boolean isSuccess(HttpResponse<String> response)
    {
        return response != null && response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private Map<String, Object> filter(String scope, List<String> fileIds)
    {
        List<Map<String, Object>> must = new ArrayList<>();
        if (StringUtils.hasText(scope))
        {
            must.add(match("scope", scope));
        }
        List<Map<String, Object>> ids = new ArrayList<>();
        if (fileIds != null)
        {
            for (String fileId : fileIds)
            {
                if (StringUtils.hasText(fileId))
                {
                    ids.add(match("fileId", fileId));
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("must", must);
        if (!ids.isEmpty())
        {
            // 顶层 should 表达 fileId 的 OR 条件，同时保留 scope 必选条件。
            result.put("should", ids);
        }
        return result;
    }

    private Map<String, Object> match(String key, String value)
    {
        return Map.of("key", key, "match", Map.of("value", value));
    }

    private int readDimension(String response)
    {
        try
        {
            JSONObject root = JSON.parseObject(response);
            JSONObject result = root == null ? null : root.getJSONObject("result");
            JSONObject config = result == null ? null : result.getJSONObject("config");
            JSONObject params = config == null ? null : config.getJSONObject("params");
            JSONObject vectors = params == null ? null : params.getJSONObject("vectors");
            return vectors == null ? 0 : vectors.getIntValue("size");
        }
        catch (Exception ignored)
        {
            return 0;
        }
    }

    private String pointId(String chunkId)
    {
        return UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String trimTrailingSlash(String value)
    {
        String result = value == null ? "http://localhost:6333" : value.trim();
        while (result.endsWith("/") && result.length() > "http://".length())
        {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String sanitizeCollection(String value)
    {
        String result = value == null || value.isBlank() ? "zzl_ai_chunks" : value.trim();
        return result.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
