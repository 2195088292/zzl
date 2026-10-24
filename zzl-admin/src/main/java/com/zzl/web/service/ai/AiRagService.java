package com.zzl.web.service.ai;

import com.zzl.common.utils.uuid.IdUtils;
import com.zzl.web.mapper.ai.AiDocumentChunkMapper;
import com.zzl.web.mapper.ai.AiDocumentChunkRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * V6.3 文档 RAG 索引。
 *
 * <p>当前使用关键词与可选向量的混合检索。向量提供器默认是本地确定性实现，
 * 也可以切换到 OpenAI 兼容 Embedding 服务；外部服务失败时仍保留关键词能力。</p>
 */
@Service
public class AiRagService
{
    private static final int MAX_CHUNKS_PER_FILE = 1000;
    private static final int MAX_SCAN_CHUNKS = 5000;
    private static final int RRF_K = 60;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("(?=([\\p{IsHan}]{2}))|[A-Za-z0-9_][A-Za-z0-9_.:/-]{1,}");

    private final AiDocumentChunkMapper mapper;
    private final AiFileService fileService;
    private final AiEmbeddingProvider embeddingProvider;
    private final AiVectorStore vectorStore;
    private final AiKnowledgeBaseConfigService configService;
    private final AiKnowledgeEmbeddingService embeddings;
    private final TransactionTemplate transaction;

    /** 保留三参数构造入口，便于旧的单元测试或扩展模块继续使用 MySQL 兜底。 */
    public AiRagService(AiDocumentChunkMapper mapper, AiFileService fileService,
            AiEmbeddingProvider embeddingProvider)
    {
        this(mapper, fileService, embeddingProvider, new DisabledAiVectorStore(), null,
                new AiKnowledgeEmbeddingService(embeddingProvider), null);
    }

    @Autowired
    public AiRagService(AiDocumentChunkMapper mapper, AiFileService fileService,
            AiEmbeddingProvider embeddingProvider, AiVectorStore vectorStore,
            AiKnowledgeBaseConfigService configService, AiKnowledgeEmbeddingService embeddings,
            PlatformTransactionManager transactionManager)
    {
        this.mapper = mapper;
        this.fileService = fileService;
        this.embeddingProvider = embeddingProvider;
        this.vectorStore = vectorStore;
        this.configService = configService;
        this.embeddings = embeddings;
        this.transaction = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    /** 上传后按文件 ID 读取抽取文本并建立索引。索引失败不影响原文件使用。 */
    public int index(String scope, AiFileService.FileInfo file)
    {
        if (file == null)
        {
            return 0;
        }
        try
        {
            return indexFile(scope, fileService.content(scope, file.id()));
        }
        catch (Exception ignored)
        {
            return 0;
        }
    }

    /** 删除文件时同步删除切片，避免历史内容继续被检索。 */
    public void delete(String scope, String fileId)
    {
        if (!StringUtils.hasText(fileId))
        {
            return;
        }
        try
        {
            String safeScope = normalizeScope(scope);
            List<AiDocumentChunkRecord> old = mapper.selectByScope(safeScope, "", List.of(fileId), MAX_SCAN_CHUNKS);
            old.stream().map(item -> vectorSpace(safeScope, item.getConversationId(), item.getEmbeddingModel()))
                    .distinct().forEach(space -> vectorStore.namespace(space).deleteByFile(safeScope, fileId));
            mapper.deleteByFile(safeScope, fileId);
            try
            {
                vectorStore.deleteByFile(safeScope, fileId);
            }
            catch (Exception ignored)
            {
                // 外部向量库失败不影响 MySQL 切片删除。
            }
        }
        catch (Exception ignored)
        {
            // 文件物理删除不应被索引清理失败阻断。
        }
    }

    /** 删除旧索引后重新切片，返回本次写入的切片数量。 */
    public int indexFile(String scope, AiFileService.FileInfo file, String content)
    {
        return indexFile(scope, file, content, settingsForFile(scope, file));
    }

    /** 按知识库配置重建文件切片。 */
    public int indexFile(String scope, AiFileService.FileInfo file, String content,
            AiKnowledgeBaseConfigService.Settings options)
    {
        return indexFile(scope, file, content, options, (done, total) -> {}, () -> {}, new Object());
    }

    public int indexFile(String scope, AiFileService.FileInfo file, String content,
            AiKnowledgeBaseConfigService.Settings options, java.util.function.BiConsumer<Integer, Integer> progress,
            Runnable guard, Object commitLock)
    {
        if (file == null || !StringUtils.hasText(file.id()) || !file.textExtracted()
                || content == null || content.isBlank() || content.startsWith("[暂不支持"))
        {
            return 0;
        }
        String safeScope = normalizeScope(scope);
        String fileId = file.id();
        AiKnowledgeBaseConfigService.Settings safeOptions = options == null ? settingsForFile(scope, file) : options;
        String embeddingModel = embeddings.model(safeOptions);
        List<Chunk> chunks = split(content, safeOptions.chunkSize(), safeOptions.chunkOverlap());
        List<float[]> vectors = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        // 完整生成向量后才替换索引；远端模型失败时保留旧索引。
        int dimension = -1;
        for (Chunk chunk : chunks)
        {
            guard.run();
            float[] vector = embeddings.embed(chunk.content(), safeOptions);
            if (dimension >= 0 && vector.length != dimension)
                throw new IllegalStateException("向量服务返回不一致的维度，请重试");
            dimension = vector.length;
            vectors.add(vector);
            ids.add(IdUtils.fastSimpleUUID());
            progress.accept(vectors.size(), chunks.size());
        }
        Runnable replace = () -> {
            mapper.deleteByFile(safeScope, fileId);
            for (int i = 0; i < chunks.size(); i++)
            {
                Chunk chunk = chunks.get(i);
                mapper.insert(ids.get(i), fileId, safeScope, blankToEmpty(file.conversationId()),
                        blankToEmpty(file.originalName()), i, chunk.start(), chunk.end(), chunk.content(),
                        keywords(chunk.content()), vectorJson(vectors.get(i)), embeddingModel);
            }
            if (configService != null)
                configService.markIndexed(safeScope, fileId, signature(safeOptions));
        };
        synchronized (commitLock)
        {
            guard.run();
            if (transaction != null) transaction.executeWithoutResult(status -> replace.run());
            else replace.run();
        }
        AiVectorStore store = vectorStore.namespace(vectorSpace(safeScope, file.conversationId(), embeddingModel));
        store.deleteByFile(safeScope, fileId);
        for (int i = 0; i < chunks.size(); i++)
        {
            guard.run();
            Chunk chunk = chunks.get(i);
            try
            {
                store.upsert(ids.get(i), vectors.get(i), Map.of("fileId", fileId, "scope", safeScope,
                        "sourceName", blankToEmpty(file.originalName()), "chunkIndex", i,
                        "knowledgeBase", blankToEmpty(file.conversationId()), "embeddingModel", embeddingModel));
            }
            catch (Exception ignored)
            {
                // MySQL JSON 是强兜底；Qdrant 不可用时仍保留完整切片。
            }
        }
        return chunks.size();
    }

    /** 直接接收文件服务的抽取结果，供上传和批量重建索引复用。 */
    public int indexFile(String scope, AiFileService.ExtractedFile extracted)
    {
        return extracted == null ? 0 : indexFile(scope, extracted.file(), extracted.content());
    }

    public int indexFile(String scope, AiFileService.ExtractedFile extracted,
            AiKnowledgeBaseConfigService.Settings options)
    {
        return extracted == null ? 0 : indexFile(scope, extracted.file(), extracted.content(), options);
    }

    /** 按用户、会话和指定文件检索最相关的切片。 */
    public List<ChunkHit> retrieve(String scope, String conversationId, List<String> fileIds,
            String query, int limit)
    {
        return retrieveDetailed(scope, conversationId, fileIds, query,
                limit <= 0 ? defaultSettings() : defaultSettings().withTopK(limit)).hits();
    }

    /** 返回混合检索结果和可观测追踪信息。 */
    public RetrievalResult retrieveDetailed(String scope, String conversationId, List<String> fileIds,
            String query, AiKnowledgeBaseConfigService.Settings options)
    {
        long started = System.currentTimeMillis();
        AiKnowledgeBaseConfigService.Settings safeOptions = options == null ? defaultSettings() : options;
        int safeLimit = Math.max(1, Math.min(safeOptions.topK(), 20));
        String safeScope = normalizeScope(scope);
        List<String> requestedFileIds = fileIds == null ? List.of()
                : fileIds.stream().filter(StringUtils::hasText).distinct().toList();
        if (!StringUtils.hasText(query)) return emptyResult(started, safeOptions);
        String conversationFilter = requestedFileIds.isEmpty() ? blankToEmpty(conversationId) : "";
        Map<String, AiDocumentChunkRecord> candidates = new java.util.LinkedHashMap<>();
        try
        {
            mapper.selectByScope(safeScope, conversationFilter, requestedFileIds, MAX_SCAN_CHUNKS)
                    .forEach(item -> candidates.put(item.getChunkId(), item));
        }
        catch (Exception ignored) { return emptyResult(started, safeOptions); }
        Set<String> terms = tokenSet(query);
        Map<String, Integer> fulltextRanks = new HashMap<>();
        boolean fulltextAvailable = false;
        try
        {
            List<AiDocumentChunkRecord> keywordRecords = mapper.selectKeywordByScope(safeScope,
                    conversationFilter, requestedFileIds, String.join(" ", terms), safeLimit * 8);
            int rank = 1;
            for (AiDocumentChunkRecord record : keywordRecords)
            {
                candidates.putIfAbsent(record.getChunkId(), record);
                fulltextRanks.put(record.getChunkId(), rank++);
            }
            fulltextAvailable = true;
        }
        catch (Exception ignored)
        {
            // MySQL FULLTEXT 不可用时，本地重叠二元分词和精确匹配继续提供词法召回。
        }
        Map<String, List<AiDocumentChunkRecord>> groups = candidates.values().stream().collect(
                java.util.stream.Collectors.groupingBy(item -> blankToEmpty(item.getConversationId())));
        Map<String, Float> vectorScores = new HashMap<>();
        Map<String, Integer> vectorRanks = new HashMap<>();
        Map<String, String> vectorErrors = new java.util.LinkedHashMap<>();
        Map<String, AiKnowledgeBaseConfigService.Settings> groupOptions = new HashMap<>();
        boolean externalUsed = false;
        for (Map.Entry<String, List<AiDocumentChunkRecord>> group : groups.entrySet())
        {
            AiKnowledgeBaseConfigService.Settings settings = configService == null ? safeOptions
                    : configService.forStorageConversation(scope, group.getKey());
            groupOptions.put(group.getKey(), settings);
            String model = embeddings.model(settings);
            List<AiDocumentChunkRecord> compatible = group.getValue().stream()
                    .filter(item -> model.equals(item.getEmbeddingModel())).toList();
            if (compatible.isEmpty() || "disabled".equals(model)) continue;
            try
            {
                float[] queryVector = embeddings.embed(query, settings);
                List<String> groupFileIds = group.getValue().stream().map(AiDocumentChunkRecord::getFileId).distinct().toList();
                List<AiVectorStore.VectorHit> external = vectorStore.namespace(vectorSpace(safeScope, group.getKey(), model))
                        .search(queryVector, Math.min(100, settings.topK() * 8), safeScope, groupFileIds);
                if (!external.isEmpty())
                {
                    List<String> missing = external.stream().map(AiVectorStore.VectorHit::chunkId)
                            .filter(id -> !candidates.containsKey(id)).toList();
                    if (!missing.isEmpty()) mapper.selectByChunkIds(safeScope, missing).stream()
                            .filter(item -> groupFileIds.contains(item.getFileId()) && model.equals(item.getEmbeddingModel()))
                            .forEach(item -> candidates.putIfAbsent(item.getChunkId(), item));
                    for (AiVectorStore.VectorHit hit : external)
                    {
                        if (candidates.containsKey(hit.chunkId()) && hit.score() >= settings.similarityThreshold())
                            vectorScores.put(hit.chunkId(), hit.score());
                    }
                    externalUsed = true;
                }
                else
                {
                    for (AiDocumentChunkRecord record : compatible)
                    {
                        float similarity = cosine(queryVector, parseVector(record.getEmbedding()));
                        if (similarity > 0 && similarity >= settings.similarityThreshold())
                            vectorScores.put(record.getChunkId(), similarity);
                    }
                }
            }
            catch (Exception error)
            {
                vectorErrors.put(group.getKey(), "向量服务暂不可用，本次使用关键词召回");
            }
        }
        // 不同知识库可以采用不同向量模型；各自在自己的空间内排序，再融合名次。
        Map<String, List<String>> vectorsByKnowledgeBase = vectorScores.keySet().stream().collect(
                java.util.stream.Collectors.groupingBy(id -> blankToEmpty(candidates.get(id).getConversationId())));
        for (List<String> ids : vectorsByKnowledgeBase.values())
        {
            ids.sort(Comparator.comparingDouble((String id) -> vectorScores.get(id)).reversed().thenComparing(id -> id));
            for (int i = 0; i < ids.size(); i++) vectorRanks.put(ids.get(i), i + 1);
        }
        List<AiDocumentChunkRecord> keywordRecords = candidates.values().stream()
                .filter(item -> lexicalScore(item, query, terms) > 0 || fulltextRanks.containsKey(item.getChunkId()))
                .sorted(Comparator.comparingInt((AiDocumentChunkRecord item) -> lexicalScore(item, query, terms)).reversed()
                        .thenComparingInt(item -> fulltextRanks.getOrDefault(item.getChunkId(), Integer.MAX_VALUE))
                        .thenComparing(AiDocumentChunkRecord::getChunkId)).toList();
        Map<String, Integer> keywordRanks = new HashMap<>();
        for (int i = 0; i < keywordRecords.size(); i++) keywordRanks.put(keywordRecords.get(i).getChunkId(), i + 1);
        List<ChunkHit> hits = new ArrayList<>();
        for (AiDocumentChunkRecord record : candidates.values())
        {
            Integer keywordRank = keywordRanks.get(record.getChunkId());
            Integer vectorRank = vectorRanks.get(record.getChunkId());
            if (keywordRank == null && vectorRank == null) continue;
            AiKnowledgeBaseConfigService.Settings settings = groupOptions.getOrDefault(
                    blankToEmpty(record.getConversationId()), safeOptions);
            double fused = rrf(keywordRank, vectorRank);
            if (settings.rerankEnabled())
            {
                // 规则重排只强化精确内容，不伪装成独立的语义重排序模型。
                fused += Math.min(lexicalScore(record, query, terms), 30) * 0.0002d;
                if (lower(record.getContent()).contains(lower(query))) fused += 0.015d;
            }
            hits.add(new ChunkHit(record, scoreValue(fused), keywordRank == null ? 0 : keywordRank,
                    vectorRank == null ? 0 : vectorRank, vectorScores.getOrDefault(record.getChunkId(), 0f), fused));
        }
        hits.sort(Comparator.comparingDouble(ChunkHit::hybridScore).reversed()
                .thenComparing(ChunkHit::getFileId).thenComparingInt(ChunkHit::getChunkIndex));
        // 重叠切片先去重；不同文件相同正文也只保留一个来源。
        Set<String> seen = new LinkedHashSet<>();
        List<ChunkHit> limited = hits.stream().filter(hit -> seen.add(hit.getContent().replaceAll("\\s+", " ").trim()))
                .limit(safeLimit).toList();
        String mode = vectorRanks.isEmpty() ? "keyword" : externalUsed ? "qdrant-hybrid" : "hybrid";
        RetrievalTrace trace = new RetrievalTrace(mode, candidates.size(), keywordRanks.size(), vectorRanks.size(),
                limited.size(), safeOptions.similarityThreshold(), System.currentTimeMillis() - started,
                fulltextAvailable, List.copyOf(vectorErrors.values()));
        return new RetrievalResult(limited, trace);
    }

    private RetrievalResult emptyResult(long started, AiKnowledgeBaseConfigService.Settings options)
    {
        return new RetrievalResult(List.of(), new RetrievalTrace(retrievalMode(), 0, 0, 0, 0,
                options == null ? 0.10f : options.similarityThreshold(), System.currentTimeMillis() - started, false, List.of()));
    }

    /** 返回可直接拼进模型提示词的引用上下文。 */
    public String retrieveContext(String scope, String conversationId, List<String> fileIds,
            String query, int limit)
    {
        List<ChunkHit> hits = retrieve(scope, conversationId, fileIds, query, limit);
        if (hits.isEmpty())
        {
            return "";
        }
        StringBuilder context = new StringBuilder("检索到的文件片段（回答时请标注文件名和片段位置）：\n");
        for (ChunkHit hit : hits)
        {
            AiDocumentChunkRecord record = hit.record();
            context.append("[文件：").append(blankToEmpty(record.getSourceName()))
                    .append("，片段：").append(value(record.getChunkIndex()) + 1)
                    .append("，字符：").append(value(record.getStartOffset())).append('-')
                    .append(value(record.getEndOffset())).append("]\n")
                    .append(record.getContent()).append("\n\n");
        }
        return context.toString();
    }

    /** 当前检索模式，供 Agent 时间线和前端展示。 */
    public String retrievalMode()
    {
        if (vectorStore != null && vectorStore.enabled())
        {
            return "qdrant-hybrid";
        }
        return embeddingProvider != null && !"disabled".equalsIgnoreCase(embeddingProvider.model())
                ? "hybrid" : "keyword";
    }

    /** ChatService 使用的简化检索入口；文件 ID 为空时按用户所有文件检索。 */
    public Retrieval retrieve(String scope, List<String> fileIds, String query)
    {
        List<ChunkHit> hits = retrieve(scope, "", fileIds, query, 6);
        List<Citation> citations = hits.stream().map(Citation::from).toList();
        return new Retrieval(citations, contextInternal(hits));
    }

    public String context(Retrieval retrieval)
    {
        return retrieval == null ? "" : retrieval.context();
    }

    /** 使用已经召回的切片生成上下文，避免向量检索重复执行。 */
    public String context(List<ChunkHit> hits)
    {
        return contextInternal(hits);
    }

    private String contextInternal(List<ChunkHit> hits)
    {
        if (hits == null || hits.isEmpty())
        {
            return "";
        }
        StringBuilder context = new StringBuilder("检索到的文件片段（回答时请标注文件名和片段位置）：\n");
        for (ChunkHit hit : hits)
        {
            AiDocumentChunkRecord record = hit.record();
            context.append("[文件：").append(blankToEmpty(record.getSourceName()))
                    .append("，片段：").append(value(record.getChunkIndex()) + 1)
                    .append("，字符：").append(value(record.getStartOffset())).append('-')
                    .append(value(record.getEndOffset())).append("]\n")
                    .append(record.getContent()).append("\n\n");
        }
        return context.toString();
    }

    private int lexicalScore(AiDocumentChunkRecord record, String query, Set<String> terms)
    {
        String content = lower(record.getContent());
        String indexed = lower(record.getKeywords());
        String normalizedQuery = lower(query);
        int score = normalizedQuery.isBlank() ? 0 : (content.contains(normalizedQuery) ? 12 : 0);
        for (String term : terms)
        {
            if (content.contains(term))
            {
                score += 2;
            }
            if (indexed.contains(term))
            {
                score += 3;
            }
        }
        if (lower(record.getSourceName()).contains(normalizedQuery) && !normalizedQuery.isBlank()) score += 8;
        return score;
    }

    private double rrf(Integer keywordRank, Integer vectorRank)
    {
        double result = 0d;
        if (keywordRank != null && keywordRank > 0) result += 0.5d / (RRF_K + keywordRank);
        if (vectorRank != null && vectorRank > 0) result += 0.5d / (RRF_K + vectorRank);
        return result;
    }

    private int scoreValue(double value)
    {
        return (int) Math.round(value * 1_000_000d);
    }

    private float cosine(float[] left, float[] right)
    {
        if (left.length == 0 || right.length != left.length) return 0f;
        float result = 0f;
        for (int i = 0; i < left.length; i++) result += left[i] * right[i];
        return result;
    }

    private String vectorJson(float[] vector)
    {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) json.append(',');
            json.append(Float.toString(vector[i]));
        }
        return json.append(']').toString();
    }

    private float[] parseVector(String value)
    {
        if (value == null || value.length() < 2) return new float[0];
        try {
            String[] parts = value.substring(1, value.length() - 1).split(",");
            float[] vector = new float[parts.length];
            for (int i = 0; i < parts.length; i++) vector[i] = Float.parseFloat(parts[i]);
            return vector;
        } catch (Exception ignored) { return new float[0]; }
    }

    private List<Chunk> split(String text, int chunkSize, int chunkOverlap)
    {
        List<Chunk> chunks = new ArrayList<>();
        int start = 0;
        int length = text.length();
        while (start < length && chunks.size() < MAX_CHUNKS_PER_FILE)
        {
            int hardEnd = Math.min(length, start + chunkSize);
            int end = hardEnd;
            if (hardEnd < length)
            {
                int lineBreak = text.lastIndexOf('\n', hardEnd);
                int sentence = Math.max(text.lastIndexOf('。', hardEnd), text.lastIndexOf('！', hardEnd));
                sentence = Math.max(sentence, text.lastIndexOf('？', hardEnd));
                int boundary = Math.max(lineBreak, sentence);
                if (boundary > start + chunkSize / 2)
                {
                    end = boundary + 1;
                }
            }
            String part = text.substring(start, end).trim();
            if (!part.isBlank())
            {
                int leading = text.substring(start, end).indexOf(part);
                int actualStart = start + Math.max(0, leading);
                chunks.add(new Chunk(actualStart, actualStart + part.length(), part));
            }
            if (end >= length)
            {
                break;
            }
            start = Math.max(start + 1, end - chunkOverlap);
        }
        return chunks;
    }

    private String keywords(String text)
    {
        return String.join(" ", tokenSet(text));
    }

    private Set<String> tokenSet(String text)
    {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null)
        {
            return tokens;
        }
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find() && tokens.size() < 120)
        {
            tokens.add((matcher.group(1) == null ? matcher.group() : matcher.group(1)).toLowerCase(Locale.ROOT));
        }
        return tokens;
    }

    private String normalizeScope(String scope)
    {
        String value = StringUtils.hasText(scope) ? scope.trim() : "anonymous";
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private String lower(String value)
    {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private String blankToEmpty(String value)
    {
        return value == null ? "" : value;
    }

    private int value(Integer number)
    {
        return number == null ? 0 : number;
    }

    AiKnowledgeBaseConfigService.Settings settingsForFile(String scope, AiFileService.FileInfo file)
    {
        return configService == null || file == null ? defaultSettings()
                : configService.forStorageConversation(scope, file.conversationId());
    }

    public String signatureForFile(String scope, AiFileService.FileInfo file)
    {
        return signature(settingsForFile(scope, file));
    }

    String signature(AiKnowledgeBaseConfigService.Settings settings)
    {
        return settings.indexSignature() + ":" + Integer.toHexString(embeddings.model(settings).hashCode());
    }

    private String vectorSpace(String scope, String conversationId, String model)
    {
        return conversationId != null && conversationId.startsWith("kb:")
                ? scope + ":" + conversationId + ":" + model : "";
    }

    private AiKnowledgeBaseConfigService.Settings defaultSettings()
    {
        return configService == null ? new AiKnowledgeBaseConfigService.Settings(1200, 180, 6, 0.10f, true, "global")
                : configService.defaults();
    }

    private record Chunk(int start, int end, String content) { }

    public record ChunkHit(AiDocumentChunkRecord record, int score, int keywordRank, int vectorRank,
            float vectorScore, double hybridScore)
    {
        public ChunkHit(AiDocumentChunkRecord record, int score)
        {
            this(record, score, 0, 0, 0f, score);
        }

        public String getChunkId() { return record.getChunkId(); }
        public String getFileId() { return record.getFileId(); }
        public String getSourceName() { return record.getSourceName(); }
        public int getChunkIndex() { return record.getChunkIndex() == null ? 0 : record.getChunkIndex(); }
        public int getStartOffset() { return record.getStartOffset() == null ? 0 : record.getStartOffset(); }
        public int getEndOffset() { return record.getEndOffset() == null ? 0 : record.getEndOffset(); }
        public String getContent() { return record.getContent(); }
    }

    public record RetrievalResult(List<ChunkHit> hits, RetrievalTrace trace)
    {
        public RetrievalResult
        {
            hits = hits == null ? List.of() : List.copyOf(hits);
            trace = trace == null ? new RetrievalTrace("keyword", 0, 0, 0, 0, 0f, 0, false, List.of()) : trace;
        }
    }

    public record RetrievalTrace(String mode, int scanned, int keywordCandidates, int vectorCandidates,
            int returned, float similarityThreshold, long elapsedMs, boolean fulltextAvailable, List<String> warnings)
    {
    }

    public record Retrieval(List<Citation> citations, String context)
    {
        public Retrieval
        {
            citations = citations == null ? List.of() : List.copyOf(citations);
            context = context == null ? "" : context;
        }
    }

    /** SSE/UI 可直接消费的文件引用。 */
    public record Citation(String key, String fileId, String chunkId, String name,
            int chunkIndex, int startOffset, int endOffset, String snippet)
    {
        private static Citation from(ChunkHit hit)
        {
            return new Citation(hit.getChunkId(), hit.getFileId(), hit.getChunkId(), hit.getSourceName(),
                    hit.getChunkIndex(), hit.getStartOffset(), hit.getEndOffset(), hit.getContent());
        }

        public java.util.Map<String, Object> toMap()
        {
            return java.util.Map.of(
                    "key", key == null ? "" : key,
                    "fileId", fileId == null ? "" : fileId,
                    "chunkId", chunkId == null ? "" : chunkId,
                    "name", name == null ? "" : name,
                    "chunkIndex", chunkIndex,
                    "startOffset", startOffset,
                    "endOffset", endOffset,
                    "pageLabel", "片段 " + (chunkIndex + 1),
                    "snippet", snippet == null ? "" : snippet);
        }
    }
}
