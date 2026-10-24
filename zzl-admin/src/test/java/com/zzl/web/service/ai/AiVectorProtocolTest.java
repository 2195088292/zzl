package com.zzl.web.service.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiVectorProtocolTest
{
    @Test void qdrantParsesModernQueryEnvelopeAndCreatesIndependentCollections() throws Exception
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> paths = new ArrayList<>();
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            String json = exchange.getRequestMethod().equals("GET") ? "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":2}}}}}"
                    : "{\"result\":{\"points\":[{\"score\":0.91,\"payload\":{\"chunkId\":\"chunk-a\"}}]}}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try
        {
            QdrantVectorStore store = new QdrantVectorStore(true, "http://127.0.0.1:" + server.getAddress().getPort(),
                    "", "test", true, 1000, 40, null);
            AiVectorStore first = store.namespace("u1:kb:a:embedding-a");
            AiVectorStore second = store.namespace("u1:kb:b:embedding-a");
            var hits = first.search(new float[] { 1, 0 }, 6, "u1", List.of("f"));
            assertEquals("chunk-a", hits.get(0).chunkId());
            second.search(new float[] { 1, 0 }, 6, "u1", List.of("g"));
            assertNotEquals(paths.get(0), paths.get(2));
            assertSame(first, store.namespace("u1:kb:a:embedding-a"));
        }
        finally { server.stop(0); }
    }

    @Test void qdrantUsesLegacySearchWhenQueryEndpointIsMissing() throws Exception
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String json = path.endsWith("/search")
                    ? "{\"result\":[{\"score\":0.8,\"payload\":{\"chunkId\":\"legacy\"}}]}"
                    : "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":2}}}}}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(path.endsWith("/query") ? 404 : 200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try
        {
            var store = new QdrantVectorStore(true, "http://127.0.0.1:" + server.getAddress().getPort(),
                    "", "legacy-test", true, 1000, 40, null);
            assertEquals("legacy", store.search(new float[] {1, 0}, 4, "u", List.of("f")).get(0).chunkId());
        }
        finally { server.stop(0); }
    }

    @Test void embeddingAdaptersUseCorrectProtocolAndNormalizeVectors() throws Exception
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> paths = new ArrayList<>();
        List<JSONObject> requests = new ArrayList<>();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath(); paths.add(path);
            requests.add(JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            String json = path.equals("/api/embed") ? "{\"embeddings\":[[3,4]]}" : "{\"data\":[{\"embedding\":[3,4]}]}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try
        {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var embeddings = new AiKnowledgeEmbeddingService(null);
            float[] openai = embeddings.embed("test", new AiKnowledgeBaseConfigService.Settings(
                    1200, 180, 6, .1f, true, "embed-a", "openai", base + "/v1", "secret"));
            float[] ollama = embeddings.embed("test", new AiKnowledgeBaseConfigService.Settings(
                    1200, 180, 6, .1f, true, "embed-b", "ollama", base, ""));
            assertEquals(List.of("/v1/embeddings", "/api/embed"), paths);
            assertEquals("embed-a", requests.get(0).getString("model"));
            assertEquals("embed-b", requests.get(1).getString("model"));
            assertEquals(.6f, openai[0], .0001f); assertEquals(.8f, ollama[1], .0001f);
        }
        finally { server.stop(0); }
    }

    @Test void remoteEmbeddingFailureDoesNotFallBackToHashVector()
    {
        var embeddings = new AiKnowledgeEmbeddingService(null);
        assertThrows(IllegalStateException.class, () -> embeddings.embed("test",
                new AiKnowledgeBaseConfigService.Settings(1200, 180, 6, .1f, true,
                        "missing", "openai", "http://127.0.0.1:1/v1", "")));
    }
}
