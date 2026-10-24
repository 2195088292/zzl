package com.zzl.web.controller.system;

import com.zzl.common.core.domain.AjaxResult;
import com.zzl.common.utils.SecurityUtils;
import com.zzl.web.service.ai.AiKnowledgeBaseService;
import com.zzl.web.service.ai.AiKnowledgeBaseConfigService;
import java.io.IOException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 知识库管理：目录、文档上传、独立索引和检索测试。 */
@RestController
@RequestMapping("/system/ai/knowledge-bases")
@PreAuthorize("@ss.hasPermi('ai:knowledge:list')")
public class AiKnowledgeBaseController
{
    private final AiKnowledgeBaseService knowledgeBaseService;
    private final AiKnowledgeBaseConfigService configService;

    public AiKnowledgeBaseController(AiKnowledgeBaseService knowledgeBaseService,
            AiKnowledgeBaseConfigService configService)
    {
        this.knowledgeBaseService = knowledgeBaseService;
        this.configService = configService;
    }

    @GetMapping
    @PreAuthorize("@ss.hasAnyPermi('ai:knowledge:list,ai:workbench:query')")
    public AjaxResult list()
    {
        return AjaxResult.success(knowledgeBaseService.list(userScope()));
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermi('ai:knowledge:add')")
    public AjaxResult create(@RequestBody(required = false) KnowledgeBaseRequest request)
    {
        return AjaxResult.success(knowledgeBaseService.create(userScope(), request == null ? null : request.name,
                request == null ? null : request.description));
    }

    @PutMapping("/{knowledgeBaseId}")
    @PreAuthorize("@ss.hasPermi('ai:knowledge:edit')")
    public AjaxResult update(@PathVariable String knowledgeBaseId,
            @RequestBody(required = false) KnowledgeBaseRequest request)
    {
        return AjaxResult.success(knowledgeBaseService.update(userScope(), knowledgeBaseId,
                request == null ? null : request.name, request == null ? null : request.description,
                request == null ? null : request.enabled));
    }

    @DeleteMapping("/{knowledgeBaseId}")
    @PreAuthorize("@ss.hasPermi('ai:knowledge:remove')")
    public AjaxResult delete(@PathVariable String knowledgeBaseId)
    {
        knowledgeBaseService.delete(userScope(), knowledgeBaseId);
        return AjaxResult.success();
    }

    @GetMapping("/{knowledgeBaseId}/documents")
    public AjaxResult documents(@PathVariable String knowledgeBaseId)
    {
        return AjaxResult.success(knowledgeBaseService.documents(userScope(), knowledgeBaseId));
    }

    @GetMapping("/{knowledgeBaseId}/settings")
    public AjaxResult settings(@PathVariable String knowledgeBaseId)
    {
        return AjaxResult.success(configService.view(userScope(), knowledgeBaseId));
    }

    @PutMapping("/{knowledgeBaseId}/settings")
    @PreAuthorize("@ss.hasPermi('ai:knowledge:edit')")
    public AjaxResult updateSettings(@PathVariable String knowledgeBaseId,
            @RequestBody(required = false) KnowledgeBaseSettingsRequest request)
    {
        KnowledgeBaseSettingsRequest value = request == null ? new KnowledgeBaseSettingsRequest() : request;
        AiKnowledgeBaseConfigService.Settings settings = configService.update(userScope(), knowledgeBaseId, value.chunkSize,
                value.chunkOverlap, value.topK, value.similarityThreshold, value.rerankEnabled,
                value.embeddingModel, value.embeddingProvider, value.embeddingBaseUrl, value.embeddingApiKey);
        return AjaxResult.success(configService.view(settings));
    }

    @PostMapping(value = "/{knowledgeBaseId}/documents", consumes = "multipart/form-data")
    @PreAuthorize("@ss.hasPermi('ai:knowledge:upload')")
    public AjaxResult upload(@PathVariable String knowledgeBaseId, @RequestParam("file") MultipartFile file)
            throws IOException
    {
        return AjaxResult.success(knowledgeBaseService.upload(userScope(), knowledgeBaseId, file));
    }

    @DeleteMapping("/{knowledgeBaseId}/documents/{fileId}")
    @PreAuthorize("@ss.hasPermi('ai:knowledge:remove')")
    public AjaxResult deleteDocument(@PathVariable String knowledgeBaseId, @PathVariable String fileId)
    {
        knowledgeBaseService.deleteDocument(userScope(), knowledgeBaseId, fileId);
        return AjaxResult.success();
    }

    @PostMapping("/{knowledgeBaseId}/reindex")
    @PreAuthorize("@ss.hasPermi('ai:knowledge:index')")
    public AjaxResult reindex(@PathVariable String knowledgeBaseId)
    {
        return AjaxResult.success(knowledgeBaseService.reindex(userScope(), knowledgeBaseId));
    }

    @PostMapping("/{knowledgeBaseId}/documents/{fileId}/reindex")
    @PreAuthorize("@ss.hasPermi('ai:knowledge:index')")
    public AjaxResult reindexDocument(@PathVariable String knowledgeBaseId, @PathVariable String fileId)
    {
        return AjaxResult.success(knowledgeBaseService.reindexDocument(userScope(), knowledgeBaseId, fileId));
    }

    @PostMapping("/{knowledgeBaseId}/documents/{fileId}/pause")
    @PreAuthorize("@ss.hasPermi('ai:knowledge:index')")
    public AjaxResult pauseDocument(@PathVariable String knowledgeBaseId, @PathVariable String fileId)
    {
        return AjaxResult.success(knowledgeBaseService.pauseDocument(userScope(), knowledgeBaseId, fileId));
    }

    @GetMapping("/{knowledgeBaseId}/search")
    @PreAuthorize("@ss.hasPermi('ai:knowledge:query')")
    public AjaxResult search(@PathVariable String knowledgeBaseId,
            @RequestParam String query, @RequestParam(required = false, defaultValue = "6") int limit)
    {
        return AjaxResult.success(knowledgeBaseService.search(userScope(), knowledgeBaseId, query, limit));
    }

    @GetMapping("/{knowledgeBaseId}/search/detail")
    @PreAuthorize("@ss.hasPermi('ai:knowledge:query')")
    public AjaxResult searchDetail(@PathVariable String knowledgeBaseId,
            @RequestParam String query, @RequestParam(required = false, defaultValue = "6") int limit)
    {
        return AjaxResult.success(knowledgeBaseService.searchDetail(userScope(), knowledgeBaseId, query, limit));
    }

    public static class KnowledgeBaseRequest
    {
        private String name;
        private String description;
        private Boolean enabled;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    }

    public static class KnowledgeBaseSettingsRequest
    {
        private Integer chunkSize;
        private Integer chunkOverlap;
        private Integer topK;
        private Float similarityThreshold;
        private Boolean rerankEnabled;
        private String embeddingModel;
        private String embeddingProvider;
        private String embeddingBaseUrl;
        private String embeddingApiKey;

        public Integer getChunkSize() { return chunkSize; }
        public void setChunkSize(Integer chunkSize) { this.chunkSize = chunkSize; }
        public Integer getChunkOverlap() { return chunkOverlap; }
        public void setChunkOverlap(Integer chunkOverlap) { this.chunkOverlap = chunkOverlap; }
        public Integer getTopK() { return topK; }
        public void setTopK(Integer topK) { this.topK = topK; }
        public Float getSimilarityThreshold() { return similarityThreshold; }
        public void setSimilarityThreshold(Float similarityThreshold) { this.similarityThreshold = similarityThreshold; }
        public Boolean getRerankEnabled() { return rerankEnabled; }
        public void setRerankEnabled(Boolean rerankEnabled) { this.rerankEnabled = rerankEnabled; }
        public String getEmbeddingModel() { return embeddingModel; }
        public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
        public String getEmbeddingProvider() { return embeddingProvider; }
        public void setEmbeddingProvider(String embeddingProvider) { this.embeddingProvider = embeddingProvider; }
        public String getEmbeddingBaseUrl() { return embeddingBaseUrl; }
        public void setEmbeddingBaseUrl(String embeddingBaseUrl) { this.embeddingBaseUrl = embeddingBaseUrl; }
        public String getEmbeddingApiKey() { return embeddingApiKey; }
        public void setEmbeddingApiKey(String embeddingApiKey) { this.embeddingApiKey = embeddingApiKey; }
    }

    private String userScope()
    {
        try
        {
            return SecurityUtils.getUserId() == null ? "anonymous" : String.valueOf(SecurityUtils.getUserId());
        }
        catch (Exception ignored)
        {
            return "anonymous";
        }
    }
}
