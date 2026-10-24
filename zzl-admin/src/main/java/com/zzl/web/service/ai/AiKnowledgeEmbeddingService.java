package com.zzl.web.service.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 按知识库选择向量模型。远端失败不混入本地向量，避免同一索引出现不同维度和语义空间。 */
@Service
public class AiKnowledgeEmbeddingService
{
    private final AiEmbeddingProvider global;
    private final AiEmbeddingProvider local = new LocalHashEmbeddingProvider(true, 96, "local", "", "", "", 1000);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public AiKnowledgeEmbeddingService(AiEmbeddingProvider global) { this.global = global; }

    public String model(AiKnowledgeBaseConfigService.Settings settings)
    {
        return switch (settings.embeddingProvider())
        {
            case "local" -> local.model();
            case "ollama", "openai" -> settings.embeddingProvider() + ":" + settings.embeddingModel() + ":"
                    + Integer.toHexString(settings.embeddingBaseUrl().hashCode());
            default -> global.model();
        };
    }

    public float[] embed(String text, AiKnowledgeBaseConfigService.Settings settings)
    {
        if ("global".equals(settings.embeddingProvider())) return global.embed(text);
        if ("local".equals(settings.embeddingProvider())) return local.embed(text);
        try
        {
            boolean ollama = "ollama".equals(settings.embeddingProvider());
            String base = settings.embeddingBaseUrl();
            String url = ollama ? base.replaceAll("/(api|v1)$", "") + "/api/embed"
                    : (base.endsWith("/embeddings") ? base : base + "/embeddings");
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json");
            if (!settings.embeddingApiKey().isBlank())
                builder.header("Authorization", "Bearer " + settings.embeddingApiKey());
            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(
                    Map.of("model", settings.embeddingModel(), "input", text == null ? "" : text)))).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2)
                throw new IllegalStateException("向量服务返回 HTTP " + response.statusCode());
            JSONObject root = JSON.parseObject(response.body());
            JSONArray array;
            if (ollama)
                array = root.getJSONArray("embeddings").getJSONArray(0);
            else
                array = root.getJSONArray("data").getJSONObject(0).getJSONArray("embedding");
            if (array == null || array.isEmpty()) throw new IllegalStateException("向量服务返回空向量");
            float[] vector = new float[array.size()];
            for (int i = 0; i < vector.length; i++)
            {
                vector[i] = array.getFloatValue(i);
                if (!Float.isFinite(vector[i])) throw new IllegalStateException("向量服务返回无效数值");
            }
            LocalHashEmbeddingProvider.normalize(vector);
            return vector;
        }
        catch (Exception error)
        {
            throw new IllegalStateException("向量生成失败，请检查模型和服务连接：" + error.getMessage(), error);
        }
    }
}
