package com.zzl.web.controller.system;

import com.zzl.common.core.domain.AjaxResult;
import com.zzl.common.utils.SecurityUtils;
import com.zzl.web.service.ai.AiChatService;
import com.zzl.web.service.ai.AiFileService;
import com.zzl.web.service.ai.AiKnowledgeBaseService;
import com.zzl.web.service.ai.AiRagIndexingService;
import com.zzl.web.service.ai.AiRunService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 左侧全功能 AI 工作区接口：文件上传、文本抽取、文件分析和文件管理。 */
@RestController
@RequestMapping("/system/ai/workspace")
@PreAuthorize("@ss.hasAnyPermi('ai:workbench:query,ai:knowledge:list')")
public class AiWorkspaceController
{
    private final AiFileService fileService;
    private final AiChatService aiChatService;
    private final SysDashboardController dashboardController;
    private final AiRunService runService;
    private final com.zzl.web.service.ai.AiRagService ragService;
    private final AiRagIndexingService ragIndexingService;
    private final AiKnowledgeBaseService knowledgeBaseService;

    public AiWorkspaceController(AiFileService fileService, AiChatService aiChatService,
            SysDashboardController dashboardController, AiRunService runService,
            com.zzl.web.service.ai.AiRagService ragService, AiRagIndexingService ragIndexingService,
            AiKnowledgeBaseService knowledgeBaseService)
    {
        this.fileService = fileService;
        this.aiChatService = aiChatService;
        this.dashboardController = dashboardController;
        this.runService = runService;
        this.ragService = ragService;
        this.ragIndexingService = ragIndexingService;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@ss.hasPermi('ai:file:upload')")
    public AjaxResult upload(@RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String conversationId)
    {
        try
        {
            AiFileService.FileInfo info = fileService.upload(userScope(), conversationId, file);
            runService.saveAttachment(userScope(), info);
            AiRagIndexingService.IndexTask task = ragIndexingService.enqueue(userScope(), info);
            // 保留原有 data.id/originalName 等字段，同时返回异步索引状态，兼容旧前端。
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", info.id());
            payload.put("originalName", info.originalName());
            payload.put("extension", info.extension());
            payload.put("contentType", info.contentType());
            payload.put("size", info.size());
            payload.put("conversationId", info.conversationId());
            payload.put("uploadedAt", info.uploadedAt());
            payload.put("extractedChars", info.extractedChars());
            payload.put("textExtracted", info.textExtracted());
            payload.put("extractMessage", info.extractMessage());
            payload.put("indexTaskId", task.taskId());
            payload.put("indexStatus", task.status().name());
            payload.put("indexMessage", task.message());
            return AjaxResult.success(payload);
        }
        catch (Exception error)
        {
            return AjaxResult.error("文件上传失败：" + readable(error));
        }
    }

    @GetMapping("/files")
    public AjaxResult files(@RequestParam(required = false) String conversationId)
    {
        return AjaxResult.success(fileService.list(userScope(), conversationId));
    }

    @GetMapping("/files/{fileId}")
    public AjaxResult file(@PathVariable String fileId)
    {
        AiFileService.FileInfo info = fileService.get(userScope(), fileId);
        return info == null ? AjaxResult.error("文件不存在或已过期") : AjaxResult.success(info);
    }

    /** 查询文件异步 RAG 索引状态。 */
    @GetMapping("/files/{fileId}/index")
    public AjaxResult indexStatus(@PathVariable String fileId)
    {
        return AjaxResult.success(ragIndexingService.status(userScope(), fileId));
    }

    /** 按上传响应返回的任务 ID 查询索引状态。 */
    @GetMapping("/index/tasks/{taskId}")
    public AjaxResult indexTask(@PathVariable String taskId)
    {
        return AjaxResult.success(ragIndexingService.statusByTaskId(userScope(), taskId));
    }

    /** 对失败或不支持的任务重新排队，返回新的任务状态。 */
    @PostMapping("/index/tasks/{taskId}/retry")
    @PreAuthorize("@ss.hasAnyPermi('ai:file:index,ai:knowledge:index')")
    public AjaxResult retryIndex(@PathVariable String taskId)
    {
        AiRagIndexingService.IndexTask task = ragIndexingService.retry(userScope(), taskId);
        return task.status() == AiRagIndexingService.Status.FAILED
                && "索引任务不存在".equals(task.message())
                ? AjaxResult.error(task.message()) : AjaxResult.success(task);
    }

    /** 手工重新建立文件索引，适合数据库恢复或切片规则升级后使用。 */
    @PostMapping("/files/{fileId}/index")
    @PreAuthorize("@ss.hasAnyPermi('ai:file:index,ai:knowledge:index')")
    public AjaxResult rebuildIndex(@PathVariable String fileId)
    {
        AiFileService.FileInfo info = fileService.get(userScope(), fileId);
        if (info == null)
        {
            return AjaxResult.error("文件不存在或已过期");
        }
        return AjaxResult.success(ragIndexingService.enqueue(userScope(), info));
    }

    @GetMapping("/files/{fileId}/content")
    public AjaxResult content(@PathVariable String fileId)
    {
        try
        {
            AiFileService.ExtractedFile extracted = fileService.content(userScope(), fileId);
            return AjaxResult.success(Map.of("file", extracted.file(), "content", extracted.content()));
        }
        catch (Exception error)
        {
            return AjaxResult.error("读取文件失败：" + readable(error));
        }
    }

    @DeleteMapping("/files/{fileId}")
    @PreAuthorize("@ss.hasPermi('ai:chat:remove')")
    public AjaxResult delete(@PathVariable String fileId)
    {
        ragIndexingService.forget(userScope(), fileId);
        fileService.delete(userScope(), fileId);
        ragService.delete(userScope(), fileId);
        runService.deleteAttachment(userScope(), fileId);
        return AjaxResult.success();
    }

    /** 使用文件内容发起一次流式分析。 */
    @PostMapping(value = "/analyze", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@ss.hasPermi('ai:chat:send')")
    public SseEmitter analyze(@RequestBody AnalyzeRequest request)
    {
        return analyzeInternal(request == null ? null : request.getFileId(), request);
    }

    /** 文件列表中的快捷分析入口。 */
    @PostMapping(value = "/files/{fileId}/analyze", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@ss.hasPermi('ai:chat:send')")
    public SseEmitter analyzeFile(@PathVariable String fileId, @RequestBody(required = false) AnalyzeRequest request)
    {
        return analyzeInternal(fileId, request);
    }

    private SseEmitter analyzeInternal(String fileId, AnalyzeRequest request)
    {
        SseEmitter emitter = new SseEmitter(180_000L);
        try
        {
            List<String> fileIds = new ArrayList<>();
            if (request != null && request.getFileIds() != null)
            {
                fileIds.addAll(request.getFileIds());
            }
            if (fileId != null && !fileId.isBlank())
            {
                fileIds.add(fileId);
            }
            fileIds = fileIds.stream().filter(item -> item != null && !item.isBlank()).distinct().toList();
            if (fileIds.isEmpty())
            {
                sendError(emitter, "fileId 不能为空");
                return emitter;
            }
            List<AiFileService.ExtractedFile> extractedFiles = new ArrayList<>();
            for (String id : fileIds)
            {
                extractedFiles.add(fileService.content(userScope(), id));
            }
            // 知识库只通过召回片段进入上下文，避免把全部文档拼进提示词。
            if (request != null && request.getKnowledgeBaseIds() != null)
            {
                List<String> merged = new ArrayList<>(fileIds);
                merged.addAll(knowledgeBaseService.fileIds(userScope(), request.getKnowledgeBaseIds()));
                fileIds = merged.stream().distinct().toList();
            }
            String question = request == null || request.getQuestion() == null || request.getQuestion().isBlank()
                    ? "请总结这个文件的主要内容，并列出值得关注的关键点。"
                    : request.getQuestion().trim();
            String scope = userScope();
            String model = aiChatService.resolveModelId(scope, request == null ? null : request.getModel());
            String conversationId = request == null ? null : request.getConversationId();
            if (!aiChatService.isConfigured(scope, model))
            {
                String names = extractedFiles.stream().map(item -> item.file().originalName()).reduce((left, right) -> left + "、" + right).orElse("附件");
                int chars = extractedFiles.stream().mapToInt(item -> item.content().length()).sum();
                String local = "文件“" + names + "”已读取，共 " + chars
                        + " 个字符。当前没有配置可用 AI 模型，请先在左侧模型管理中配置。";
                emitter.send(SseEmitter.event().name("message").data(local));
                emitter.send(SseEmitter.event().name("done").data("done"));
                emitter.complete();
                return emitter;
            }
            emitter.send(SseEmitter.event().name("status").data("已读取 " + extractedFiles.size() + " 个文件"));
            aiChatService.stream(scope, conversationId, model, question, dashboardContext(),
                    attachmentContext(extractedFiles), fileIds,
                    request == null ? null : request.getReasoningEffort(), emitter);
        }
        catch (Exception error)
        {
            sendError(emitter, "文件分析失败：" + readable(error));
        }
        return emitter;
    }

    private String attachmentContext(List<AiFileService.ExtractedFile> extractedFiles)
    {
        return extractedFiles.stream().map(extracted -> "文件名：" + extracted.file().originalName() + "\n"
                + "文件类型：" + extracted.file().extension() + "\n"
                + "文件内容：\n" + extracted.content()).reduce((left, right) -> left + "\n\n" + right).orElse("");
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

    private void sendError(SseEmitter emitter, String message)
    {
        try
        {
            emitter.send(SseEmitter.event().name("error").data(message));
            emitter.send(SseEmitter.event().name("done").data("done"));
        }
        catch (IOException ignored)
        {
            // 客户端已断开连接。
        }
        emitter.complete();
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

    private String readable(Throwable error)
    {
        Throwable cause = error;
        while (cause.getCause() != null && (cause.getMessage() == null || cause.getMessage().isBlank()))
        {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    public static class AnalyzeRequest
    {
        private String fileId;
        private String question;
        private String model;
        private String reasoningEffort;
        private String conversationId;
        private List<String> fileIds;
        private List<String> knowledgeBaseIds;

        public String getFileId() { return fileId; }
        public void setFileId(String fileId) { this.fileId = fileId; }
        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getReasoningEffort() { return reasoningEffort; }
        public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }
        public String getConversationId() { return conversationId; }
        public void setConversationId(String conversationId) { this.conversationId = conversationId; }
        public List<String> getFileIds() { return fileIds; }
        public void setFileIds(List<String> fileIds) { this.fileIds = fileIds; }
        public List<String> getKnowledgeBaseIds() { return knowledgeBaseIds; }
        public void setKnowledgeBaseIds(List<String> knowledgeBaseIds) { this.knowledgeBaseIds = knowledgeBaseIds; }
    }
}
