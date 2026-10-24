package com.zzl.web.service.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import java.io.IOException;
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
import java.util.concurrent.ConcurrentHashMap;
import com.zzl.web.service.ai.adapter.AiModelAdapterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** LangChain4j 流式对话服务。V1 保留最近 8 轮上下文，后续可替换为持久化 ChatMemory。 */
@Service
public class AiChatService
{
    private static final int MAX_TURNS = 8;
    private static final String SYSTEM_PROMPT = "你是 ZZL 管理系统的 AI 管理助手。"
            + "只回答管理系统、首页指标、用户、日志、任务和服务器状态相关问题。"
            + "当用户上传文件时，可以基于文件内容进行总结、解释、改写和数据分析。"
            + "不要编造数据库中没有的数据；无法确认时明确说不知道。回答简洁，使用中文。";

    private final AiProperties properties;
    private final AiConversationStore conversationStore;
    private final AiModelStore modelStore;
    private final AiRunService runService;
    private final AiAgentRunService agentRunService;
    private final AiRagService ragService;
    private final AiFileService fileService;
    private final AiModelAdapterRegistry adapterRegistry;
    private final AiKnowledgeBaseConfigService knowledgeBaseConfigService;
    private final Map<String, StreamingChatModel> modelCache = new ConcurrentHashMap<>();

    public AiChatService(AiProperties properties, AiConversationStore conversationStore, AiModelStore modelStore,
            AiRunService runService, AiAgentRunService agentRunService, AiRagService ragService,
            AiFileService fileService, AiModelAdapterRegistry adapterRegistry,
            AiKnowledgeBaseConfigService knowledgeBaseConfigService)
    {
        this.properties = properties;
        this.conversationStore = conversationStore;
        this.modelStore = modelStore;
        this.runService = runService;
        this.agentRunService = agentRunService;
        this.ragService = ragService;
        this.fileService = fileService;
        this.adapterRegistry = adapterRegistry;
        this.knowledgeBaseConfigService = knowledgeBaseConfigService;
    }

    public boolean isConfigured(String scope, String modelId)
    {
        return properties.isEnabled() && findModel(scope, modelId) != null && findModel(scope, modelId).isConfigured();
    }

    public String resolveModelId(String scope, String modelId)
    {
        Map<String, AiProperties.ModelProperties> models = allModels(scope);
        if (modelId != null && models.containsKey(modelId))
        {
            return modelId;
        }
        if (modelId != null)
        {
            int separator = modelId.indexOf("::");
            if (separator > 0 && models.containsKey(modelId.substring(0, separator)))
            {
                return modelId;
            }
        }
        return properties.getDefaultModel();
    }

    public List<Map<String, Object>> modelViews(String scope)
    {
        List<Map<String, Object>> result = new ArrayList<>();
        allModels(scope).forEach((id, config) -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", id);
            view.put("label", config.getLabel() == null || config.getLabel().isBlank() ? id : config.getLabel());
            view.put("modelName", config.getModelName() == null ? "" : config.getModelName());
            view.put("configured", isConfigured(scope, id));
            view.put("enabled", config.isEnabled());
            view.put("baseUrl", config.getBaseUrl() == null ? "" : config.getBaseUrl());
            view.put("apiKeyConfigured", config.getApiKey() != null && !config.getApiKey().isBlank());
            view.put("modelCatalog", config.getModelCatalog() == null ? List.of() : config.getModelCatalog());
            view.put("reasoningEffort", config.getReasoningEffort() == null ? "auto" : config.getReasoningEffort());
            view.putAll(adapterRegistry.capabilityView(config));
            view.put("capabilities", adapterRegistry.capabilityView(config));
            view.put("source", modelStore.get(scope, id) == null ? "config" : "runtime");
            view.put("defaultModel", id.equals(properties.getDefaultModel()));
            result.add(view);
        });
        return result;
    }

    public void saveModel(String scope, String id, AiProperties.ModelProperties model)
    {
        adapterRegistry.normalize(model);
        modelStore.save(scope, id, model);
        String prefix = cacheKey(scope, id);
        modelCache.keySet().removeIf(key -> key.equals(prefix) || key.startsWith(prefix + "::"));
    }

    public void deleteModel(String scope, String id)
    {
        modelStore.delete(scope, id);
        String prefix = cacheKey(scope, id);
        modelCache.keySet().removeIf(key -> key.equals(prefix) || key.startsWith(prefix + "::"));
    }

    public AiProperties.ModelProperties runtimeModel(String scope, String id)
    {
        return modelStore.get(scope, id);
    }

    public AiProperties.ModelProperties currentModel(String scope, String id)
    {
        return findModel(scope, id);
    }

    public String testModel(String scope, String id)
    {
        AiProperties.ModelProperties config = findModel(scope, id);
        if (!isConfigured(scope, id))
        {
            throw new IllegalArgumentException("模型未启用或配置不完整");
        }
        AiProperties.ModelProperties testConfig = copyModel(config);
        testConfig.setTemperature(0D);
        testConfig.setMaxTokens(32);
        return adapterRegistry.resolve(testConfig).buildChatModel(testConfig).chat("只回复：连接成功");
    }

    /**
     * 读取 OpenAI 兼容服务的模型目录；Ollama 同时兼容原生 /api/tags。
     * 返回只包含模型标识和显示名称，不返回任何凭据。
     */
    public List<Map<String, String>> fetchModelCatalog(String scope, String modelId, String baseUrl, String apiKey)
    {
        if (baseUrl == null || baseUrl.isBlank())
        {
            throw new IllegalArgumentException("Base URL 不能为空");
        }
        String configuredKey = apiKey;
        if ((configuredKey == null || configuredKey.isBlank() || "******".equals(configuredKey.trim()))
                && modelId != null && !modelId.isBlank())
        {
            AiProperties.ModelProperties existing = currentModel(scope, modelId.trim());
            configuredKey = existing == null ? "" : existing.getApiKey();
        }
        String root = trimTrailingSlash(baseUrl);
        List<String> endpoints = new ArrayList<>();
        endpoints.add(root.endsWith("/models") ? root : root + "/models");
        if (isOllamaEndpoint(root))
        {
            String nativeRoot = root.endsWith("/v1") ? root.substring(0, root.length() - 3) : root;
            endpoints.add(trimTrailingSlash(nativeRoot) + "/api/tags");
        }
        Exception lastError = null;
        for (String endpoint : endpoints)
        {
            try
            {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(8)).GET()
                        .header("Accept", "application/json");
                if (configuredKey != null && !configuredKey.isBlank() && !"******".equals(configuredKey.trim()))
                {
                    builder.header("Authorization", "Bearer " + configuredKey.trim());
                }
                HttpResponse<String> response = HttpClient.newHttpClient().send(builder.build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                {
                    lastError = new IllegalStateException("模型接口返回 HTTP " + response.statusCode());
                    continue;
                }
                List<Map<String, String>> result = parseModelCatalog(response.body());
                if (!result.isEmpty())
                {
                    return result;
                }
                lastError = new IllegalStateException("模型接口没有返回可用模型");
            }
            catch (Exception error)
            {
                lastError = error;
            }
        }
        throw new IllegalStateException("获取模型列表失败："
                + (lastError == null || lastError.getMessage() == null ? "接口不可用" : lastError.getMessage()));
    }

    private List<Map<String, String>> parseModelCatalog(String body)
    {
        JSONObject root = JSON.parseObject(body == null ? "{}" : body);
        JSONArray items = root == null ? null : root.getJSONArray("data");
        if (items == null && root != null)
        {
            items = root.getJSONArray("models");
        }
        List<Map<String, String>> result = new ArrayList<>();
        if (items == null)
        {
            return result;
        }
        for (Object value : items)
        {
            if (!(value instanceof JSONObject item))
            {
                continue;
            }
            String id = firstText(item.getString("id"), item.getString("name"), item.getString("model"));
            if (id == null || id.isBlank())
            {
                continue;
            }
            Map<String, String> model = new LinkedHashMap<>();
            model.put("id", id);
            model.put("label", firstText(item.getString("name"), item.getString("id"), id));
            result.add(model);
        }
        return result;
    }

    private String firstText(String... values)
    {
        for (String value : values)
        {
            if (value != null && !value.isBlank())
            {
                return value.trim();
            }
        }
        return "";
    }

    private String trimTrailingSlash(String value)
    {
        String result = value.trim();
        while (result.endsWith("/") && result.length() > "http://".length())
        {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private boolean isOllamaEndpoint(String value)
    {
        String endpoint = value.toLowerCase();
        return endpoint.contains("localhost:11434") || endpoint.contains("127.0.0.1:11434");
    }

    public void stream(String scope, String conversationId, String modelId, String question,
            String dashboardContext, SseEmitter emitter)
    {
        stream(scope, conversationId, modelId, question, dashboardContext, null, List.of(), null, emitter);
    }

    /** 带文件上下文的流式对话入口，供全功能 AI 工作区复用。 */
    public void stream(String scope, String conversationId, String modelId, String question,
            String dashboardContext, String attachmentContext, SseEmitter emitter)
    {
        stream(scope, conversationId, modelId, question, dashboardContext, attachmentContext, List.of(), null, emitter);
    }

    /** 带文件 ID 的 RAG 流式对话入口；只把相关片段放入模型上下文。 */
    public void stream(String scope, String conversationId, String modelId, String question,
            String dashboardContext, String attachmentContext, List<String> fileIds, SseEmitter emitter)
    {
        stream(scope, conversationId, modelId, question, dashboardContext, attachmentContext, fileIds, null, emitter);
    }

    /** 带单次推理强度覆盖的流式对话入口；auto 表示使用模型默认值。 */
    public void stream(String scope, String conversationId, String modelId, String question,
            String dashboardContext, String attachmentContext, List<String> fileIds,
            String requestedReasoningEffort, SseEmitter emitter)
    {
        String resolvedModelId = resolveModelId(scope, modelId);
        String resolvedConversationId = conversationId == null || conversationId.isBlank() ? "default" : conversationId;
        String runtimeId = agentRunService.start(scope, resolvedConversationId, resolvedModelId);
        AiRunService.RunHandle run = runService.start(runtimeId, scope, resolvedConversationId, resolvedModelId, question);
        runService.message(run, "user", question);
        agentRunService.bind(runtimeId, emitter);
        agentRunService.status(runtimeId, "正在准备 Agent 运行");
        runService.event(run, "status", Map.of("message", "正在准备 Agent 运行"));
        AiProperties.ModelProperties config = findModel(scope, resolvedModelId);
        if (config == null || !isConfigured(scope, resolvedModelId))
        {
            String message = "当前没有配置可用的 AI 模型，请在 application.yml 配置 zzl.ai.models 并开启 zzl.ai.enabled。";
            runService.fail(run, new IllegalStateException(message));
            agentRunService.fail(runtimeId, message);
            return;
        }
        String effectiveReasoningEffort = normalizeReasoningEffort(requestedReasoningEffort, config);
        AiProperties.ModelProperties effectiveConfig = config;
        if (effectiveReasoningEffort != null)
        {
            effectiveConfig = copyModel(config);
            effectiveConfig.setReasoningEffort(effectiveReasoningEffort);
        }

        List<AiConversationStore.Turn> history = conversationStore.load(scope, resolvedConversationId);
        String ragContext = "";
        List<Map<String, Object>> responseCitations = new ArrayList<>();
        if (fileIds != null && !fileIds.isEmpty())
        {
            AiRagService.RetrievalResult retrieval;
            try
            {
                retrieval = ragService.retrieveDetailed(scope, resolvedConversationId, fileIds,
                        question, knowledgeBaseConfigService.forFiles(scope, fileIds));
            }
            catch (Exception error)
            {
                runService.fail(run, error);
                agentRunService.fail(runtimeId, "知识库检索失败：" + readableMessage(error));
                return;
            }
            List<AiRagService.ChunkHit> hits = retrieval.hits();
            String retrievalMode = retrieval.trace().mode();
            String retrievalLabel = retrievalMode.contains("hybrid") ? "混合检索" : "关键词检索";
            String retrievalMessage = "文件检索：" + retrievalLabel + "，命中 " + hits.size() + " 个片段";
            agentRunService.status(runtimeId, retrievalMessage);
            Map<String, Object> retrievalEvent = new LinkedHashMap<>();
            retrievalEvent.put("retrievalMode", retrievalMode);
            retrievalEvent.put("status", hits.isEmpty() ? "empty" : "ready");
            retrievalEvent.put("count", hits.size());
            retrievalEvent.put("trace", retrieval.trace());
            runService.event(run, "retrieval", retrievalEvent);
            agentRunService.retrieval(runtimeId, retrievalEvent);
            for (AiRagService.ChunkHit hit : hits)
            {
                Map<String, Object> data = new java.util.LinkedHashMap<>();
                data.put("chunkId", hit.getChunkId());
                data.put("fileId", hit.getFileId());
                data.put("fileName", hit.getSourceName());
                data.put("chunkIndex", hit.getChunkIndex());
                data.put("startOffset", hit.getStartOffset());
                data.put("endOffset", hit.getEndOffset());
                data.put("score", hit.score());
                data.put("keywordRank", hit.keywordRank());
                data.put("vectorRank", hit.vectorRank());
                data.put("vectorScore", hit.vectorScore());
                data.put("hybridScore", hit.hybridScore());
                data.put("retrievalMode", retrievalMode);
                data.put("snippet", hit.getContent().length() > 260 ? hit.getContent().substring(0, 260) + "…" : hit.getContent());
                responseCitations.add(data);
                agentRunService.citation(runtimeId, data);
                runService.event(run, "file_ref", data);
            }
            ragContext = ragService.context(hits);
            if (ragContext.isBlank() && (attachmentContext == null || attachmentContext.isBlank()))
                ragContext = "本次所选知识库未召回相关内容。请明确说明资料中未找到依据，不要编造文件内容。";
        }
        List<AiFileService.ImageAttachment> images = imageAttachments(scope, fileIds);
        String prompt = buildPrompt(history, question, dashboardContext,
                images.isEmpty() && ragContext.isBlank() ? attachmentContext : ragContext);

        StringBuilder answer = new StringBuilder();
        try
        {
            agentRunService.status(runtimeId, "正在调用 " + resolvedModelId + " 模型");
            runService.event(run, "status", Map.of("message", "正在调用 " + resolvedModelId + " 模型"));
            String modelCacheKey = cacheKey(scope, resolvedModelId) + "::reasoning="
                    + (effectiveConfig.getReasoningEffort() == null ? "auto" : effectiveConfig.getReasoningEffort());
            final AiProperties.ModelProperties modelConfig = effectiveConfig;
            final String responseModelId = resolvedModelId;
            final String responseReasoningEffort = effectiveConfig.getReasoningEffort();
            final String responseProviderType = adapterRegistry.providerType(effectiveConfig);
            final String responseCapabilityProtocol = adapterRegistry.capabilityProtocol(effectiveConfig);
            StreamingChatModel model = modelCache.computeIfAbsent(modelCacheKey, id -> buildModel(modelConfig));
            if (!images.isEmpty())
            {
                streamMultimodal(scope, resolvedConversationId, resolvedModelId, question, prompt,
                        images, model, history, runtimeId, run, effectiveConfig.getReasoningEffort(),
                        responseProviderType, responseCapabilityProtocol, responseCitations, emitter);
                return;
            }
            StreamingAssistant assistant = AiServices.builder(StreamingAssistant.class)
                    .streamingChatModel(model)
                    .systemMessage(SYSTEM_PROMPT + " 需要读取管理数据时，调用 queryDashboardSnapshot 工具；"
                            + "需要查找上传文件时，调用 searchUploadedFiles 或 readUploadedFile，不要编造数据。")
                    .tools(new ManagementTools(dashboardContext, ragService, fileService, scope,
                            resolvedConversationId, fileIds))
                    .build();
            TokenStream tokenStream = assistant.chat(prompt);
            tokenStream
                .onPartialResponse(partialResponse ->
                {
                    answer.append(partialResponse);
                    agentRunService.message(runtimeId, partialResponse);
                })
                .beforeToolExecution(beforeTool -> handleToolStarted(runtimeId, run, beforeTool))
                .onToolExecuted(toolExecution -> handleToolFinished(runtimeId, run, toolExecution))
                .onCompleteResponse(completeResponse ->
            {
                    if (!agentRunService.isCancelled(runtimeId))
                    {
                        remember(history, question, answer.toString(), responseModelId, responseReasoningEffort,
                                responseProviderType, responseCapabilityProtocol, responseCitations);
                        conversationStore.save(scope, resolvedConversationId, history);
                        conversationStore.touch(scope, resolvedConversationId, question, resolvedModelId);
                        runService.complete(run, answer.toString());
                        agentRunService.complete(runtimeId);
                    }
                })
                .onError(error ->
                {
                    String message = "AI 模型调用失败：" + readableMessage(error);
                    runService.fail(run, error);
                    agentRunService.fail(runtimeId, message);
                })
                .start();
        }
        catch (Exception error)
        {
            String message = "AI 模型初始化失败：" + readableMessage(error);
            runService.fail(run, error);
            agentRunService.fail(runtimeId, message);
        }
    }

    private List<AiFileService.ImageAttachment> imageAttachments(String scope, List<String> fileIds)
    {
        List<AiFileService.ImageAttachment> images = new ArrayList<>();
        if (fileIds == null)
        {
            return images;
        }
        for (String fileId : fileIds)
        {
            try
            {
                AiFileService.FileInfo file = fileService.get(scope, fileId);
                if (file != null && file.conversationId() != null && file.conversationId().startsWith("kb:")) continue;
                images.add(fileService.image(scope, fileId));
            }
            catch (Exception ignored)
            {
                // 文本附件不是图片，继续走普通文本/RAG流程。
            }
        }
        return images;
    }

    /** 使用 LangChain4j 的多模态 UserMessage，把图片以 data URL 交给视觉模型。 */
    private void streamMultimodal(String scope, String conversationId, String modelId, String question,
            String prompt, List<AiFileService.ImageAttachment> images, StreamingChatModel model,
            List<AiConversationStore.Turn> history, String runtimeId, AiRunService.RunHandle run,
            String reasoningEffort, String providerType, String capabilityProtocol,
            List<Map<String, Object>> citations, SseEmitter emitter)
    {
        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from(prompt));
        for (AiFileService.ImageAttachment image : images)
        {
            contents.add(ImageContent.from(image.path(), image.contentType()));
        }
        List<ChatMessage> messages = List.of(
                SystemMessage.from(SYSTEM_PROMPT + " 用户上传了图片，请先观察图片内容再回答；如果当前模型不支持视觉输入，请明确说明。"),
                dev.langchain4j.data.message.UserMessage.from(contents));
        StringBuilder answer = new StringBuilder();
        agentRunService.status(runtimeId, "正在读取图片并调用 " + modelId + " 模型");
        runService.event(run, "status", Map.of("message", "正在读取图片并调用 " + modelId + " 模型"));
        try
        {
            model.chat(messages, new StreamingChatResponseHandler()
            {
                @Override
                public void onPartialResponse(String partialResponse)
                {
                    answer.append(partialResponse);
                    agentRunService.message(runtimeId, partialResponse);
                }

                @Override
                public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse response)
                {
                    if (!agentRunService.isCancelled(runtimeId))
                    {
                        remember(history, question, answer.toString(), modelId, reasoningEffort,
                                providerType, capabilityProtocol, citations);
                        conversationStore.save(scope, conversationId, history);
                        conversationStore.touch(scope, conversationId, question, modelId);
                        runService.complete(run, answer.toString());
                        agentRunService.complete(runtimeId);
                    }
                }

                @Override
                public void onError(Throwable error)
                {
                    String message = "图片模型调用失败：" + readableMessage(error);
                    runService.fail(run, error);
                    agentRunService.fail(runtimeId, message);
                }
            });
        }
        catch (Exception error)
        {
            String message = "图片模型初始化失败：" + readableMessage(error);
            runService.fail(run, error);
            agentRunService.fail(runtimeId, message);
        }
    }

    private void handleToolStarted(String runtimeId, AiRunService.RunHandle run, BeforeToolExecution beforeTool)
    {
        String toolName = beforeTool == null || beforeTool.request() == null ? "unknown" : beforeTool.request().name();
        String detail = "正在调用工具：" + toolName;
        agentRunService.toolStarted(runtimeId, toolName, detail);
        runService.event(run, "tool_started", Map.of("tool", toolName));
    }

    private void handleToolFinished(String runtimeId, AiRunService.RunHandle run, ToolExecution toolExecution)
    {
        String toolName = toolExecution == null || toolExecution.request() == null ? "unknown" : toolExecution.request().name();
        boolean failed = toolExecution != null && toolExecution.hasFailed();
        String result = toolExecution == null ? "" : toolExecution.result();
        if (result == null)
        {
            result = "";
        }
        String detail = failed ? "工具调用失败：" + result : "工具调用完成";
        agentRunService.toolFinished(runtimeId, toolName, detail, failed);
        runService.event(run, failed ? "tool_failed" : "tool_completed",
                Map.of("tool", toolName, "durationMs", toolExecution == null || toolExecution.duration() == null
                        ? 0 : toolExecution.duration().toMillis()));
    }

    private StreamingChatModel buildModel(AiProperties.ModelProperties config)
    {
        return adapterRegistry.resolve(config).buildStreamingChatModel(config);
    }

    private String normalizeReasoningEffort(String value, AiProperties.ModelProperties config)
    {
        if (value == null || value.isBlank())
        {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        return adapterRegistry.reasoningLevels(config).contains(normalized) ? normalized : null;
    }

    private String buildPrompt(List<AiConversationStore.Turn> history, String question, String dashboardContext,
            String attachmentContext)
    {
        StringBuilder prompt = new StringBuilder();
        if (!history.isEmpty())
        {
            prompt.append("这是同一会话的历史，请保持上下文：\n");
            for (AiConversationStore.Turn turn : history)
            {
                prompt.append("user".equals(turn.role()) ? "用户：" : "助手：")
                        .append(turn.content()).append('\n');
            }
        }
        if (dashboardContext != null && !dashboardContext.isBlank())
        {
            prompt.append("当前首页快照已可通过工具读取。\n");
        }
        if (attachmentContext != null && !attachmentContext.isBlank())
        {
            prompt.append("以下是用户上传文件的内容，只能基于这些内容回答文件相关问题：\n")
                    .append(attachmentContext).append('\n');
        }
        prompt.append("当前问题：").append(question);
        return prompt.toString();
    }

    private AiProperties.ModelProperties findModel(String scope, String modelId)
    {
        String baseId = modelId;
        String selectedModelName = null;
        if (modelId != null)
        {
            int separator = modelId.indexOf("::");
            if (separator > 0)
            {
                baseId = modelId.substring(0, separator);
                selectedModelName = modelId.substring(separator + 2);
            }
        }
        AiProperties.ModelProperties runtime = modelStore.get(scope, baseId);
        AiProperties.ModelProperties config = runtime == null ? properties.getModels().get(baseId) : runtime;
        if (config == null || selectedModelName == null || selectedModelName.isBlank())
        {
            return config;
        }
        AiProperties.ModelProperties selected = copyModel(config);
        selected.setModelName(selectedModelName);
        return selected;
    }

    private AiProperties.ModelProperties copyModel(AiProperties.ModelProperties source)
    {
        AiProperties.ModelProperties copy = new AiProperties.ModelProperties();
        copy.setLabel(source.getLabel());
        copy.setBaseUrl(source.getBaseUrl());
        copy.setApiKey(source.getApiKey());
        copy.setModelName(source.getModelName());
        copy.setModelCatalog(source.getModelCatalog());
        copy.setEnabled(source.isEnabled());
        copy.setTemperature(source.getTemperature());
        copy.setMaxTokens(source.getMaxTokens());
        copy.setProviderType(source.getProviderType());
        copy.setCapabilityProtocol(source.getCapabilityProtocol());
        copy.setReasoningLevels(source.getReasoningLevels());
        copy.setSupportsVision(source.getSupportsVision());
        copy.setSupportsTools(source.getSupportsTools());
        copy.setReasoningEffort(source.getReasoningEffort());
        return copy;
    }

    private Map<String, AiProperties.ModelProperties> allModels(String scope)
    {
        Map<String, AiProperties.ModelProperties> models = new java.util.LinkedHashMap<>(properties.getModels());
        models.putAll(modelStore.list(scope));
        return models;
    }

    private String cacheKey(String scope, String modelId)
    {
        String safeScope = scope == null || scope.isBlank() ? "anonymous" : scope.trim();
        return safeScope + ":" + modelId;
    }

    private void remember(List<AiConversationStore.Turn> history, String question, String answer,
            String modelId, String reasoningEffort, String providerType, String capabilityProtocol)
    {
        remember(history, question, answer, modelId, reasoningEffort, providerType, capabilityProtocol, List.of());
    }

    private void remember(List<AiConversationStore.Turn> history, String question, String answer,
            String modelId, String reasoningEffort, String providerType, String capabilityProtocol,
            List<Map<String, Object>> citations)
    {
        if (answer == null || answer.isBlank())
        {
            return;
        }
        synchronized (history)
        {
            history.add(new AiConversationStore.Turn("user", question, "", "auto"));
            history.add(new AiConversationStore.Turn("assistant", answer,
                    modelId == null ? "" : modelId,
                    reasoningEffort == null || reasoningEffort.isBlank() ? "auto" : reasoningEffort,
                    providerType == null ? "" : providerType,
                    capabilityProtocol == null ? "" : capabilityProtocol, citations));
            while (history.size() > MAX_TURNS * 2)
            {
                history.remove(0);
            }
        }
    }

    private void sendDone(SseEmitter emitter)
    {
        send(emitter, "done", "done");
        emitter.complete();
    }

    private void sendError(SseEmitter emitter, String message)
    {
        send(emitter, "error", message == null ? "AI 运行失败" : message);
        send(emitter, "done", "error");
        emitter.complete();
    }

    private void send(SseEmitter emitter, String event, String data)
    {
        try
        {
            emitter.send(SseEmitter.event().name(event).data(data));
        }
        catch (IOException ignored)
        {
            emitter.complete();
        }
    }

    private String readableMessage(Throwable error)
    {
        Throwable cause = error;
        while (cause.getCause() != null && (cause.getMessage() == null || cause.getMessage().isBlank()))
        {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    public interface StreamingAssistant
    {
        TokenStream chat(@UserMessage String message);
    }

    public static class ManagementTools
    {
        private final String dashboardContext;
        private final AiRagService ragService;
        private final AiFileService fileService;
        private final String scope;
        private final String conversationId;
        private final List<String> fileIds;

        public ManagementTools(String dashboardContext, AiRagService ragService, AiFileService fileService,
                String scope, String conversationId, List<String> fileIds)
        {
            this.dashboardContext = dashboardContext;
            this.ragService = ragService;
            this.fileService = fileService;
            this.scope = scope;
            this.conversationId = conversationId;
            this.fileIds = fileIds == null ? List.of() : List.copyOf(fileIds);
        }

        @Tool("读取当前首页的在线用户、用户总数、待办事项、服务状态和更新时间")
        public String queryDashboardSnapshot()
        {
            return dashboardContext == null || dashboardContext.isBlank() ? "当前无法读取首页快照" : dashboardContext;
        }

        @Tool("在当前会话已经上传的文件中检索相关片段，返回文件名、片段位置和原文")
        public String searchUploadedFiles(String query)
        {
            if (query == null || query.isBlank() || fileIds.isEmpty())
            {
                return fileIds.isEmpty() ? "当前会话没有可检索的上传文件" : "请输入要检索的文件问题";
            }
            List<AiRagService.ChunkHit> hits = ragService.retrieve(scope, conversationId, fileIds, query, 4);
            if (hits.isEmpty())
            {
                return "没有找到与问题直接相关的文件片段";
            }
            StringBuilder result = new StringBuilder();
            for (AiRagService.ChunkHit hit : hits)
            {
                result.append("[文件：").append(hit.getSourceName()).append("，片段：")
                        .append(hit.getChunkIndex() + 1).append("，字符：")
                        .append(hit.getStartOffset()).append('-').append(hit.getEndOffset()).append("]\n")
                        .append(hit.getContent()).append("\n\n");
            }
            return result.toString();
        }

        @Tool("读取当前会话已上传文件的一小段原文，适合需要查看完整上下文时使用")
        public String readUploadedFile(String fileId)
        {
            if (fileId == null || fileId.isBlank())
            {
                return "文件 ID 不能为空";
            }
            if (!fileIds.contains(fileId))
            {
                return "只能读取当前问题已选择的文件";
            }
            try
            {
                String content = fileService.content(scope, fileId).content();
                if (content == null || content.isBlank())
                {
                    return "文件没有可读取的文本内容";
                }
                return content.length() > 12000 ? content.substring(0, 12000) + "\n[原文已截断]" : content;
            }
            catch (Exception error)
            {
                return "读取文件失败：" + error.getMessage();
            }
        }
    }

}
