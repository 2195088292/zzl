package com.zzl.web.service.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 确定性本地向量；配置 OpenAI 兼容服务且有密钥时优先远程，失败自动回退本地。 */
@Component
public class LocalHashEmbeddingProvider implements AiEmbeddingProvider
{
    private static final Pattern EMBEDDING = Pattern.compile("\\\"embedding\\\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
    private static final Pattern LOCAL_TOKEN = Pattern.compile("[\\p{IsHan}]{2}|[A-Za-z0-9_]{2,}");
    private final int dimension;
    private final boolean enabled;
    private final String provider;
    private final String endpoint;
    private final String apiKey;
    private final String remoteModel;
    private final HttpClient client;

    public LocalHashEmbeddingProvider(
            @Value("${zzl.ai.rag.embedding.enabled:true}") boolean enabled,
            @Value("${zzl.ai.rag.embedding.dimension:96}") int dimension,
            @Value("${zzl.ai.rag.embedding.provider:local}") String provider,
            @Value("${zzl.ai.rag.embedding.endpoint:https://api.openai.com/v1/embeddings}") String endpoint,
            @Value("${zzl.ai.rag.embedding.api-key:}") String apiKey,
            @Value("${zzl.ai.rag.embedding.model:text-embedding-3-small}") String remoteModel,
            @Value("${zzl.ai.rag.embedding.timeout-ms:2500}") int timeoutMs)
    {
        this.enabled = enabled;
        this.dimension = Math.max(32, Math.min(dimension, 512));
        this.provider = provider == null ? "local" : provider.trim().toLowerCase(Locale.ROOT);
        this.endpoint = endpoint;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.remoteModel = remoteModel == null || remoteModel.isBlank() ? "text-embedding-3-small" : remoteModel;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Math.max(300, timeoutMs))).build();
    }

    @Override public String model()
    {
        if (!enabled) return "disabled";
        return isRemoteConfigured() ? "openai:" + remoteModel : "local-hash-v1-" + dimension;
    }

    @Override public float[] embed(String text)
    {
        if (!enabled || text == null || text.isBlank()) return new float[dimension];
        if (isRemoteConfigured()) {
            float[] remote = remoteEmbed(text);
            if (remote.length > 0) return remote;
            throw new IllegalStateException("配置的向量服务不可用，请检查连接后重试");
        }
        return localEmbed(text);
    }

    private boolean isRemoteConfigured() { return "openai".equals(provider) && !apiKey.isBlank() && endpoint != null && !endpoint.isBlank(); }

    private float[] remoteEmbed(String text)
    {
        try {
            String body = "{\"model\":\"" + jsonEscape(remoteModel) + "\",\"input\":\"" + jsonEscape(text) + "\"}";
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            String response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
            Matcher matcher = EMBEDDING.matcher(response);
            if (!matcher.find()) return new float[0];
            String[] values = matcher.group(1).split(",");
            float[] vector = new float[values.length];
            for (int i = 0; i < values.length; i++) vector[i] = Float.parseFloat(values[i].trim());
            normalize(vector); return vector;
        } catch (Exception ignored) { return new float[0]; }
    }

    private float[] localEmbed(String text)
    {
        float[] vector = new float[dimension];
        Matcher matcher = LOCAL_TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String term = matcher.group();
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(term.getBytes(StandardCharsets.UTF_8));
                int index = ((digest[0] & 0xff) << 8 | (digest[1] & 0xff)) % dimension;
                vector[index] += (digest[2] & 1) == 0 ? 1f : -1f;
            } catch (Exception ignored) { }
        }
        normalize(vector); return vector;
    }

    private static String jsonEscape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }
    static void normalize(float[] vector) { double norm = 0; for (float value : vector) norm += value * value; if (norm == 0) return; float scale = (float)(1d / Math.sqrt(norm)); for (int i=0;i<vector.length;i++) vector[i] *= scale; }
}
