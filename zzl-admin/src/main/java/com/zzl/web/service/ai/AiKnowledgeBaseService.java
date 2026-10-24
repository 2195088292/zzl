package com.zzl.web.service.ai;

import com.zzl.common.utils.StringUtils;
import com.zzl.common.utils.uuid.IdUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 独立知识库目录。
 *
 * <p>知识库只保存文档关系和管理信息，正文、切片和向量继续复用现有文件服务与 RAG 服务。
 * 文档使用隐藏的 kb:conversation 标记保存，兼容 V6.3 已有的 fileId 检索链路。</p>
 */
@Service
public class AiKnowledgeBaseService
{
    private static final String STORAGE_PREFIX = "kb:";

    private final JdbcTemplate jdbcTemplate;
    private final AiFileService fileService;
    private final AiRunService runService;
    private final AiRagService ragService;
    private final AiRagIndexingService indexingService;
    private final AiKnowledgeBaseConfigService configService;
    private final Map<String, KnowledgeBase> memoryBases = new ConcurrentHashMap<>();
    private final Map<String, List<DocumentRef>> memoryDocuments = new ConcurrentHashMap<>();

    public AiKnowledgeBaseService(JdbcTemplate jdbcTemplate, AiFileService fileService,
            AiRunService runService, AiRagService ragService, AiRagIndexingService indexingService,
            AiKnowledgeBaseConfigService configService)
    {
        this.jdbcTemplate = jdbcTemplate;
        this.fileService = fileService;
        this.runService = runService;
        this.ragService = ragService;
        this.indexingService = indexingService;
        this.configService = configService;
    }

    public List<Map<String, Object>> list(String scope)
    {
        String safeScope = normalizeScope(scope);
        try
        {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT knowledge_base_id, name, description, enabled, created_at, updated_at "
                            + "FROM zzl_ai_knowledge_base WHERE scope = ? ORDER BY updated_at DESC",
                    safeScope);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> row : rows)
            {
                result.add(view(new KnowledgeBase(String.valueOf(row.get("knowledge_base_id")), safeScope,
                        String.valueOf(row.getOrDefault("name", "")), String.valueOf(row.getOrDefault("description", "")),
                        number(row.get("enabled"), 1) != 0)));
            }
            return result;
        }
        catch (Exception ignored)
        {
            return memoryBases.values().stream().filter(item -> safeScope.equals(item.scope()))
                    .sorted(Comparator.comparing(KnowledgeBase::id).reversed()).map(this::view).toList();
        }
    }

    public Map<String, Object> create(String scope, String name, String description)
    {
        String cleanName = cleanName(name);
        if (cleanName.isBlank())
        {
            throw new IllegalArgumentException("知识库名称不能为空");
        }
        String safeScope = normalizeScope(scope);
        KnowledgeBase base = new KnowledgeBase(IdUtils.fastSimpleUUID(), safeScope, cleanName,
                cleanDescription(description), true);
        try
        {
            jdbcTemplate.update("INSERT INTO zzl_ai_knowledge_base "
                    + "(knowledge_base_id, scope, name, description, enabled) VALUES (?, ?, ?, ?, 1)",
                    base.id(), base.scope(), base.name(), base.description());
        }
        catch (Exception error)
        {
            memoryBases.put(baseKey(safeScope, base.id()), base);
        }
        return view(base);
    }

    public Map<String, Object> update(String scope, String id, String name, String description, Boolean enabled)
    {
        KnowledgeBase current = require(scope, id);
        String cleanName = name == null ? current.name() : cleanName(name);
        if (cleanName.isBlank())
        {
            throw new IllegalArgumentException("知识库名称不能为空");
        }
        String cleanDescription = description == null ? current.description() : cleanDescription(description);
        boolean active = enabled == null ? current.enabled() : enabled;
        KnowledgeBase updated = new KnowledgeBase(current.id(), current.scope(), cleanName, cleanDescription, active);
        try
        {
            jdbcTemplate.update("UPDATE zzl_ai_knowledge_base SET name = ?, description = ?, enabled = ? "
                    + "WHERE scope = ? AND knowledge_base_id = ?", updated.name(), updated.description(),
                    updated.enabled() ? 1 : 0, updated.scope(), updated.id());
        }
        catch (Exception error)
        {
            memoryBases.put(baseKey(updated.scope(), updated.id()), updated);
        }
        return view(updated);
    }

    public void delete(String scope, String id)
    {
        KnowledgeBase base = require(scope, id);
        for (DocumentRef document : documentsInternal(base.scope(), base.id()))
        {
            deleteDocumentInternal(base.scope(), base.id(), document.fileId());
        }
        try
        {
            jdbcTemplate.update("DELETE FROM zzl_ai_knowledge_document WHERE scope = ? AND knowledge_base_id = ?",
                    base.scope(), base.id());
            jdbcTemplate.update("DELETE FROM zzl_ai_knowledge_base WHERE scope = ? AND knowledge_base_id = ?",
                    base.scope(), base.id());
        }
        catch (Exception ignored)
        {
            // 内存降级目录已在下方清理。
        }
        memoryDocuments.remove(baseKey(base.scope(), base.id()));
        memoryBases.remove(baseKey(base.scope(), base.id()));
    }

    public Map<String, Object> upload(String scope, String id, MultipartFile multipartFile) throws IOException
    {
        KnowledgeBase base = require(scope, id);
        AiFileService.FileInfo file = fileService.upload(scope, storageConversationId(base.id()), multipartFile);
        runService.saveAttachment(scope, file);
        saveDocument(base.scope(), base.id(), file.id(), file.originalName());
        AiRagIndexingService.IndexTask task = indexingService.enqueue(scope, file);
        return documentView(base.scope(), base.id(), file.id(), file.originalName(), task);
    }

    public List<Map<String, Object>> documents(String scope, String id)
    {
        KnowledgeBase base = require(scope, id);
        List<Map<String, Object>> result = new ArrayList<>();
        for (DocumentRef ref : documentsInternal(base.scope(), base.id()))
        {
            AiFileService.FileInfo file = fileService.get(base.scope(), ref.fileId());
            AiRagIndexingService.IndexTask task = indexingService.status(base.scope(), ref.fileId());
            result.add(documentView(base.scope(), base.id(), ref.fileId(),
                    file == null ? ref.title() : file.originalName(), task));
        }
        return result;
    }

    public List<Map<String, Object>> reindex(String scope, String id)
    {
        KnowledgeBase base = require(scope, id);
        List<Map<String, Object>> result = new ArrayList<>();
        for (DocumentRef ref : documentsInternal(base.scope(), base.id()))
        {
            AiFileService.FileInfo file = fileService.get(base.scope(), ref.fileId());
            AiRagIndexingService.IndexTask task = file == null
                    ? AiRagIndexingService.IndexTask.failed(ref.fileId(), "文件不存在或已删除")
                    : indexingService.enqueue(base.scope(), file);
            result.add(documentView(base.scope(), base.id(), ref.fileId(), ref.title(), task));
        }
        return result;
    }

    public void deleteDocument(String scope, String id, String fileId)
    {
        KnowledgeBase base = require(scope, id);
        requireDocument(base, fileId);
        deleteDocumentInternal(base.scope(), base.id(), fileId);
    }

    public Map<String, Object> reindexDocument(String scope, String id, String fileId)
    {
        KnowledgeBase base = require(scope, id);
        DocumentRef ref = requireDocument(base, fileId);
        AiFileService.FileInfo file = fileService.get(base.scope(), fileId);
        if (file == null) throw new IllegalArgumentException("文件不存在");
        return documentView(base.scope(), base.id(), fileId, ref.title(), indexingService.enqueue(base.scope(), file));
    }

    public Map<String, Object> pauseDocument(String scope, String id, String fileId)
    {
        KnowledgeBase base = require(scope, id);
        DocumentRef ref = requireDocument(base, fileId);
        AiRagIndexingService.IndexTask task = indexingService.status(base.scope(), fileId);
        return documentView(base.scope(), base.id(), fileId, ref.title(), indexingService.pause(base.scope(), task.taskId()));
    }

    private DocumentRef requireDocument(KnowledgeBase base, String fileId)
    {
        return documentsInternal(base.scope(), base.id()).stream().filter(item -> item.fileId().equals(fileId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("文件不在当前知识库中"));
    }

    public List<String> fileIds(String scope, List<String> knowledgeBaseIds)
    {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty())
        {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String id : knowledgeBaseIds)
        {
            if (StringUtils.hasText(id))
            {
                try
                {
                    KnowledgeBase base = require(scope, id);
                    if (!base.enabled()) continue;
                    documentsInternal(normalizeScope(scope), id).forEach(item -> result.add(item.fileId()));
                }
                catch (Exception ignored)
                {
                    // 忽略当前用户不存在的知识库，避免跨用户读取。
                }
            }
        }
        return result.stream().distinct().toList();
    }

    public List<Map<String, Object>> search(String scope, String id, String query, int limit)
    {
        return searchHits(scope, id, query, limit).stream().map(this::citationView).toList();
    }

    public Map<String, Object> searchDetail(String scope, String id, String query, int limit)
    {
        KnowledgeBase base = require(scope, id);
        if (!base.enabled()) throw new IllegalArgumentException("知识库已停用");
        AiRagService.RetrievalResult retrieval = ragService.retrieveDetailed(base.scope(), storageConversationId(base.id()),
                fileIds(base.scope(), List.of(base.id())), query, configService.forKnowledgeBase(base.scope(), base.id()));
        List<AiRagService.ChunkHit> hits = retrieval.hits();
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiRagService.ChunkHit hit : hits)
        {
            result.add(citationView(hit));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", result);
        response.put("trace", traceView(retrieval.trace()));
        return response;
    }

    private List<AiRagService.ChunkHit> searchHits(String scope, String id, String query, int limit)
    {
        KnowledgeBase base = require(scope, id);
        if (!base.enabled()) return List.of();
        return ragService.retrieveDetailed(base.scope(), storageConversationId(base.id()),
                fileIds(base.scope(), List.of(base.id())), query,
                configService.forKnowledgeBase(base.scope(), base.id())).hits();
    }

    private Map<String, Object> citationView(AiRagService.ChunkHit hit)
    {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("key", hit.getChunkId());
        value.put("fileId", hit.getFileId());
        value.put("chunkId", hit.getChunkId());
        value.put("name", hit.getSourceName());
        value.put("chunkIndex", hit.getChunkIndex());
        value.put("startOffset", hit.getStartOffset());
        value.put("endOffset", hit.getEndOffset());
        value.put("pageLabel", "片段 " + (hit.getChunkIndex() + 1));
        value.put("snippet", hit.getContent());
        value.put("keywordRank", hit.keywordRank());
        value.put("vectorRank", hit.vectorRank());
        value.put("vectorScore", hit.vectorScore());
        value.put("hybridScore", hit.hybridScore());
        return value;
    }

    private void saveDocument(String scope, String baseId, String fileId, String title)
    {
        try
        {
            jdbcTemplate.update("INSERT INTO zzl_ai_knowledge_document "
                    + "(knowledge_base_id, scope, file_id, title) VALUES (?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE title = VALUES(title), updated_at = CURRENT_TIMESTAMP",
                    baseId, scope, fileId, title == null ? "" : title);
        }
        catch (Exception ignored)
        {
            memoryDocuments.computeIfAbsent(baseKey(scope, baseId), key -> new ArrayList<>())
                    .removeIf(item -> fileId.equals(item.fileId()));
            memoryDocuments.computeIfAbsent(baseKey(scope, baseId), key -> new ArrayList<>())
                    .add(new DocumentRef(fileId, title == null ? "" : title));
        }
    }

    private void deleteDocumentInternal(String scope, String baseId, String fileId)
    {
        if (!StringUtils.hasText(fileId))
        {
            return;
        }
        indexingService.forget(scope, fileId);
        ragService.delete(scope, fileId);
        fileService.delete(scope, fileId);
        runService.deleteAttachment(scope, fileId);
        try
        {
            jdbcTemplate.update("DELETE FROM zzl_ai_knowledge_document WHERE scope = ? AND knowledge_base_id = ? AND file_id = ?",
                    scope, baseId, fileId);
        }
        catch (Exception ignored)
        {
            memoryDocuments.computeIfAbsent(baseKey(scope, baseId), key -> new ArrayList<>())
                    .removeIf(item -> fileId.equals(item.fileId()));
        }
    }

    private List<DocumentRef> documentsInternal(String scope, String baseId)
    {
        try
        {
            return jdbcTemplate.query("SELECT file_id, title, index_signature FROM zzl_ai_knowledge_document "
                    + "WHERE scope = ? AND knowledge_base_id = ? ORDER BY updated_at DESC", (rs, rowNum) ->
                    new DocumentRef(rs.getString("file_id"), rs.getString("title"), rs.getString("index_signature")), scope, baseId);
        }
        catch (Exception ignored)
        {
            return new ArrayList<>(memoryDocuments.getOrDefault(baseKey(scope, baseId), List.of()));
        }
    }

    private KnowledgeBase require(String scope, String id)
    {
        if (!StringUtils.hasText(id))
        {
            throw new IllegalArgumentException("知识库 ID 不能为空");
        }
        String safeScope = normalizeScope(scope);
        try
        {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT knowledge_base_id, scope, name, description, enabled "
                    + "FROM zzl_ai_knowledge_base WHERE scope = ? AND knowledge_base_id = ? LIMIT 1", safeScope, id);
            if (!rows.isEmpty())
            {
                Map<String, Object> row = rows.get(0);
                return new KnowledgeBase(String.valueOf(row.get("knowledge_base_id")), safeScope,
                        String.valueOf(row.getOrDefault("name", "")), String.valueOf(row.getOrDefault("description", "")),
                        number(row.get("enabled"), 1) != 0);
            }
        }
        catch (Exception ignored)
        {
            // 继续读取进程内降级目录。
        }
        KnowledgeBase base = memoryBases.get(baseKey(safeScope, id));
        if (base == null)
        {
            throw new IllegalArgumentException("知识库不存在");
        }
        return base;
    }

    private Map<String, Object> view(KnowledgeBase base)
    {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", base.id());
        value.put("name", base.name());
        value.put("description", base.description());
        value.put("enabled", base.enabled());
        value.put("documentCount", documentsInternal(base.scope(), base.id()).size());
        value.put("settings", configService.view(base.scope(), base.id()));
        return value;
    }

    private Map<String, Object> traceView(AiRagService.RetrievalTrace trace)
    {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("mode", trace.mode());
        value.put("scanned", trace.scanned());
        value.put("keywordCandidates", trace.keywordCandidates());
        value.put("vectorCandidates", trace.vectorCandidates());
        value.put("returned", trace.returned());
        value.put("similarityThreshold", trace.similarityThreshold());
        value.put("elapsedMs", trace.elapsedMs());
        value.put("fulltextAvailable", trace.fulltextAvailable());
        value.put("warnings", trace.warnings());
        return value;
    }

    private Map<String, Object> documentView(String scope, String baseId, String fileId, String title,
            AiRagIndexingService.IndexTask task)
    {
        AiFileService.FileInfo file = fileService.get(scope, fileId);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("fileId", fileId);
        value.put("title", title == null ? "" : title);
        value.put("name", title == null ? "" : title);
        value.put("size", file == null ? 0 : file.size());
        value.put("contentType", file == null ? "" : file.contentType());
        value.put("indexTaskId", task == null ? "" : task.taskId());
        value.put("progress", task == null ? 0 : task.progress());
        value.put("stage", task == null ? "" : task.stage());
        value.put("indexSignature", task == null ? "" : task.indexSignature());
        value.put("indexStatus", task == null ? "NOT_INDEXED" : task.status().name());
        value.put("indexedChunks", task == null ? 0 : task.chunks());
        value.put("indexMessage", task == null ? "" : task.message());
        value.put("attempts", task == null ? 0 : task.attempts());
        value.put("startedAt", task == null ? null : task.startedAt());
        value.put("finishedAt", task == null ? null : task.finishedAt());
        value.put("durationMs", task == null || task.startedAt() == null ? 0
                : (task.finishedAt() == null ? System.currentTimeMillis() : task.finishedAt()) - task.startedAt());
        List<String> signatures = jdbcTemplate.query("SELECT index_signature FROM zzl_ai_knowledge_document "
                + "WHERE scope=? AND knowledge_base_id=? AND file_id=?", (rs, i) -> rs.getString(1), scope, baseId, fileId);
        value.put("needsReindex", file != null && !signatures.isEmpty() && !ragService.signatureForFile(scope, file).equals(signatures.get(0))
                && task != null && task.status() == AiRagIndexingService.Status.SUCCEEDED);
        value.put("knowledgeBaseId", baseId);
        return value;
    }

    private String storageConversationId(String id)
    {
        return STORAGE_PREFIX + id;
    }

    private String baseKey(String scope, String id)
    {
        return normalizeScope(scope) + ":" + id;
    }

    private String normalizeScope(String scope)
    {
        return StringUtils.hasText(scope) ? scope.trim() : "anonymous";
    }

    private String cleanName(String value)
    {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private String cleanDescription(String value)
    {
        String clean = value == null ? "" : value.trim();
        return clean.length() > 500 ? clean.substring(0, 500) : clean;
    }

    private int number(Object value, int fallback)
    {
        if (value instanceof Boolean flag) return flag ? 1 : 0;
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private record KnowledgeBase(String id, String scope, String name, String description, boolean enabled) { }

    private record DocumentRef(String fileId, String title, String indexSignature)
    {
        private DocumentRef(String fileId, String title) { this(fileId, title, ""); }
    }
}
