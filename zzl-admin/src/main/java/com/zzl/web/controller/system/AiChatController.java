package com.zzl.web.controller.system;

import com.zzl.common.core.domain.AjaxResult;
import com.zzl.common.utils.SecurityUtils;
import com.zzl.web.service.ai.AiChatService;
import com.zzl.web.service.ai.AiConversationStore;
import com.zzl.web.service.ai.AiFileService;
import com.zzl.web.service.ai.AiKnowledgeBaseService;
import com.zzl.web.service.ai.AiProperties;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** AI 管理助手接口：模型配置启用后使用 LangChain4j 流式回答，否则使用本地首页摘要。 */
@RestController
@RequestMapping("/system/ai")
public class AiChatController
{
    private final SysDashboardController dashboardController;
    private final AiChatService aiChatService;
    private final AiConversationStore conversationStore;
    private final AiFileService fileService;
    private final AiKnowledgeBaseService knowledgeBaseService;

    public AiChatController(SysDashboardController dashboardController, AiChatService aiChatService,
            AiConversationStore conversationStore, AiFileService fileService,
            AiKnowledgeBaseService knowledgeBaseService)
    {
        this.dashboardController = dashboardController;
        this.aiChatService = aiChatService;
        this.conversationStore = conversationStore;
        this.fileService = fileService;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @GetMapping("/models")
    @PreAuthorize("@ss.hasAnyPermi('ai:workbench:query,ai:model:list')")
    public AjaxResult models()
    {
        return AjaxResult.success(aiChatService.modelViews(userScope()));
    }

    /** 读取第三方 OpenAI 兼容接口或本地 Ollama 的模型目录。 */
    @PostMapping("/models/catalog")
    @PreAuthorize("@ss.hasPermi('ai:model:query')")
    public AjaxResult modelCatalog(@RequestBody AiModelRequest request)
    {
        try
        {
            if (request == null)
            {
                return AjaxResult.error("模型连接信息不能为空");
            }
            return AjaxResult.success(aiChatService.fetchModelCatalog(userScope(), request.getId(), request.getBaseUrl(),
                    request.getApiKey()));
        }
        catch (Exception error)
        {
            return AjaxResult.error("获取模型列表失败："
                    + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        }
    }

    @PostMapping("/models")
    @PreAuthorize("@ss.hasPermi('ai:model:add')")
    public AjaxResult addModel(@RequestBody AiModelRequest request)
    {
        return saveModel(userScope(), request == null ? null : request.getId(), request);
    }

    @PostMapping("/models/{id}")
    @PreAuthorize("@ss.hasPermi('ai:model:edit')")
    public AjaxResult updateModel(@PathVariable String id, @RequestBody AiModelRequest request)
    {
        return saveModel(userScope(), id, request);
    }

    @DeleteMapping("/models/{id}")
    @PreAuthorize("@ss.hasPermi('ai:model:remove')")
    public AjaxResult deleteModel(@PathVariable String id)
    {
        aiChatService.deleteModel(userScope(), id);
        return AjaxResult.success();
    }

    @PostMapping("/models/{id}/test")
    @PreAuthorize("@ss.hasPermi('ai:model:query')")
    public AjaxResult testModel(@PathVariable String id)
    {
        try
        {
            return AjaxResult.success("连接成功", Map.of("answer", aiChatService.testModel(userScope(), id)));
        }
        catch (Exception error)
        {
            return AjaxResult.error("连接失败：" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        }
    }

    @GetMapping("/conversations")
    @PreAuthorize("@ss.hasPermi('ai:workbench:query')")
    public AjaxResult conversations()
    {
        return AjaxResult.success(conversationStore.list(userScope()));
    }

    @GetMapping("/conversations/{conversationId}")
    @PreAuthorize("@ss.hasPermi('ai:workbench:query')")
    public AjaxResult conversation(@PathVariable String conversationId)
    {
        return AjaxResult.success(conversationStore.load(userScope(), conversationId));
    }

    @DeleteMapping("/conversations/{conversationId}")
    @PreAuthorize("@ss.hasPermi('ai:chat:remove')")
    public AjaxResult deleteConversation(@PathVariable String conversationId)
    {
        conversationStore.delete(userScope(), conversationId);
        return AjaxResult.success();
    }

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@ss.hasPermi('ai:chat:send')")
    public SseEmitter chat(@RequestParam String question,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String reasoningEffort,
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) String fileId,
            @RequestParam(required = false) String fileIds,
            @RequestParam(required = false) String knowledgeBaseIds)
    {
        SseEmitter emitter = new SseEmitter(120_000L);
        String scope = userScope();
        String modelId = aiChatService.resolveModelId(scope, model);
        if (aiChatService.isConfigured(scope, modelId))
        {
            List<String> attachmentIds = attachmentIds(fileId, fileIds);
            String attachmentContext = attachmentContext(attachmentIds);
            attachmentIds = mergeIds(attachmentIds,
                    knowledgeBaseService.fileIds(scope, parseIds(knowledgeBaseIds)));
            if ((fileId != null && !fileId.isBlank()) || (fileIds != null && !fileIds.isBlank()))
            {
                if (attachmentContext.isBlank())
                {
                    sendError(emitter, "文件读取失败");
                    return emitter;
                }
            }
            aiChatService.stream(userScope(), conversationId, modelId, question.trim(), dashboardContext(),
                    attachmentContext, attachmentIds, reasoningEffort, emitter);
        }
        else
        {
            sendLocalAnswer(question, conversationId, modelId, emitter);
        }
        return emitter;
    }

    private List<String> attachmentIds(String fileId, String fileIds)
    {
        Set<String> ids = new HashSet<>();
        if (fileId != null && !fileId.isBlank())
        {
            ids.add(fileId.trim());
        }
        if (fileIds != null && !fileIds.isBlank())
        {
            for (String item : fileIds.split(","))
            {
                if (item != null && !item.isBlank())
                {
                    ids.add(item.trim());
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private String attachmentContext(List<String> fileIds)
    {
        List<String> contents = new ArrayList<>();
        for (String id : fileIds == null ? List.<String>of() : fileIds)
        {
            try
            {
                AiFileService.ExtractedFile extracted = fileService.content(userScope(), id);
                contents.add("文件名：" + extracted.file().originalName() + "\n"
                        + "文件类型：" + extracted.file().extension() + "\n"
                        + "文件内容：\n" + extracted.content());
            }
            catch (Exception ignored)
            {
                // 忽略单个失效附件；全部失效时由调用方返回错误。
            }
        }
        return String.join("\n\n", contents);
    }

    private List<String> parseIds(String values)
    {
        if (values == null || values.isBlank())
        {
            return List.of();
        }
        return List.of(values.split(",")).stream().filter(item -> item != null && !item.isBlank())
                .map(String::trim).distinct().toList();
    }

    private List<String> mergeIds(List<String> first, List<String> second)
    {
        Set<String> ids = new java.util.LinkedHashSet<>();
        if (first != null) ids.addAll(first);
        if (second != null) ids.addAll(second);
        return new ArrayList<>(ids);
    }

    private void sendError(SseEmitter emitter, String message)
    {
        try
        {
            emitter.send(SseEmitter.event().name("error").data(message == null ? "文件读取失败" : message));
            emitter.send(SseEmitter.event().name("done").data("done"));
        }
        catch (IOException ignored)
        {
            // 客户端已断开连接。
        }
        emitter.complete();
    }

    private void sendLocalAnswer(String question, String conversationId, String modelId, SseEmitter emitter)
    {
        try
        {
            String answer = localAnswer(question);
            String resolvedConversationId = conversationId == null || conversationId.isBlank() ? "default" : conversationId;
            java.util.List<AiConversationStore.Turn> history = conversationStore.load(userScope(), resolvedConversationId);
            history.add(new AiConversationStore.Turn("user", question));
            history.add(new AiConversationStore.Turn("assistant", answer));
            while (history.size() > 16)
            {
                history.remove(0);
            }
            conversationStore.save(userScope(), resolvedConversationId, history);
            conversationStore.touch(userScope(), resolvedConversationId, question, modelId);
            emitter.send(SseEmitter.event().name("message").data(answer));
            emitter.send(SseEmitter.event().name("done").data("done"));
            emitter.complete();
        }
        catch (IOException error)
        {
            emitter.completeWithError(error);
        }
    }

    private String localAnswer(String question)
    {
        String text = question == null ? "" : question.trim();
        if (text.contains("在线") || text.contains("指标") || text.contains("首页") || text.contains("系统状态"))
        {
            AjaxResult result = dashboardController.overview();
            Object data = result.get(AjaxResult.DATA_TAG);
            if (data instanceof Map<?, ?> overview)
            {
                return "当前系统状态：在线用户 " + metricValue(overview, "onlineUsers")
                        + "，用户总数 " + metricValue(overview, "totalUsers")
                        + "，待办事项 " + valueOrDefault(overview, "todoCount", 0)
                        + "。数据更新时间：" + valueOrDefault(overview, "updatedAt", "未知")
                        + "。当前为本地摘要模式，配置模型后可切换为 LangChain4j 对话。";
            }
            return "首页数据已返回，但暂时无法整理成摘要。";
        }
        return "当前为本地摘要模式。支持查询首页指标、在线用户、操作日志、定时任务、公告和服务器状态；配置 zzl.ai 后可使用 LangChain4j 模型进行自然语言问答。";
    }

    private Object metricValue(Map<?, ?> overview, String key)
    {
        Object metrics = overview.get("metrics");
        if (metrics instanceof Iterable<?> items)
        {
            for (Object item : items)
            {
                if (item instanceof Map<?, ?> metric && key.equals(metric.get("key")))
                {
                    Object value = metric.get("value");
                    return value == null ? 0 : value;
                }
            }
        }
        return 0;
    }

    private Object valueOrDefault(Map<?, ?> values, String key, Object defaultValue)
    {
        Object value = values.get(key);
        return value == null ? defaultValue : value;
    }

    private String userScope()
    {
        try
        {
            return String.valueOf(SecurityUtils.getUserId());
        }
        catch (Exception ignored)
        {
            return "anonymous";
        }
    }

    private String dashboardContext()
    {
        try
        {
            Object data = dashboardController.overview().get(AjaxResult.DATA_TAG);
            return data == null ? "" : data.toString();
        }
        catch (Exception ignored)
        {
            return "";
        }
    }

    private AjaxResult saveModel(String scope, String id, AiModelRequest request)
    {
        if (id == null || !id.matches("[A-Za-z0-9_-]{2,40}"))
        {
            return AjaxResult.error("模型标识只能使用 2-40 位字母、数字、下划线或短横线");
        }
        if (request == null || isBlank(request.getBaseUrl()) || isBlank(request.getModelName()))
        {
            return AjaxResult.error("Base URL 和模型名称不能为空");
        }
        AiProperties.ModelProperties existing = aiChatService.currentModel(scope, id);
        if (isBlank(request.getApiKey()) && existing == null && !isLocalEndpoint(request.getBaseUrl()))
        {
            return AjaxResult.error("远程模型的 API Key 不能为空；本地 Ollama 可留空");
        }
        AiProperties.ModelProperties model = new AiProperties.ModelProperties();
        model.setLabel(isBlank(request.getLabel()) ? id : request.getLabel().trim());
        model.setBaseUrl(request.getBaseUrl().trim());
        model.setModelName(request.getModelName().trim());
        model.setApiKey(isBlank(request.getApiKey()) || "******".equals(request.getApiKey())
                ? existing == null ? isLocalEndpoint(request.getBaseUrl()) ? "ollama" : "" : existing.getApiKey()
                : request.getApiKey().trim());
        List<AiProperties.ModelOption> catalog = new ArrayList<>();
        if (request.getModelCatalog() != null)
        {
            for (Map<String, String> item : request.getModelCatalog())
            {
                if (item == null || isBlank(item.get("id")))
                {
                    continue;
                }
                catalog.add(new AiProperties.ModelOption(item.get("id").trim(), item.get("label")));
            }
        }
        model.setModelCatalog(catalog);
        model.setEnabled(request.getEnabled() == null || request.getEnabled());
        model.setTemperature(request.getTemperature() == null ? 0.2D : request.getTemperature());
        model.setMaxTokens(request.getMaxTokens() == null ? 1200 : request.getMaxTokens());
        model.setProviderType(request.getProviderType());
        model.setCapabilityProtocol(request.getCapabilityProtocol());
        model.setReasoningLevels(request.getReasoningLevels());
        model.setSupportsVision(request.getSupportsVision());
        model.setSupportsTools(request.getSupportsTools());
        model.setReasoningEffort(request.getReasoningEffort());
        aiChatService.saveModel(scope, id, model);
        return AjaxResult.success("模型已保存");
    }

    private boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }

    private boolean isLocalEndpoint(String value)
    {
        if (value == null)
        {
            return false;
        }
        String endpoint = value.toLowerCase();
        return endpoint.contains("localhost") || endpoint.contains("127.0.0.1") || endpoint.contains("0.0.0.0");
    }

    public static class AiModelRequest
    {
        private String id;
        private String label;
        private String baseUrl;
        private String apiKey;
        private String modelName;
        private List<Map<String, String>> modelCatalog;
        private Boolean enabled;
        private Double temperature;
        private Integer maxTokens;
        private String providerType;
        private String capabilityProtocol;
        private List<String> reasoningLevels;
        private Boolean supportsVision;
        private Boolean supportsTools;
        private String reasoningEffort;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public List<Map<String, String>> getModelCatalog() { return modelCatalog; }
        public void setModelCatalog(List<Map<String, String>> modelCatalog) { this.modelCatalog = modelCatalog; }
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }
        public Integer getMaxTokens() { return maxTokens; }
        public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
        public String getProviderType() { return providerType; }
        public void setProviderType(String providerType) { this.providerType = providerType; }
        public String getCapabilityProtocol() { return capabilityProtocol; }
        public void setCapabilityProtocol(String capabilityProtocol) { this.capabilityProtocol = capabilityProtocol; }
        public List<String> getReasoningLevels() { return reasoningLevels; }
        public void setReasoningLevels(List<String> reasoningLevels) { this.reasoningLevels = reasoningLevels; }
        public Boolean getSupportsVision() { return supportsVision; }
        public void setSupportsVision(Boolean supportsVision) { this.supportsVision = supportsVision; }
        public Boolean getSupportsTools() { return supportsTools; }
        public void setSupportsTools(Boolean supportsTools) { this.supportsTools = supportsTools; }
        public String getReasoningEffort() { return reasoningEffort; }
        public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }
    }
}
