package com.zzl.web.controller.system;

import com.zzl.common.core.domain.AjaxResult;
import com.zzl.common.utils.SecurityUtils;
import com.zzl.web.service.ai.AiRunService;
import com.zzl.web.service.ai.AiAgentRunService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent 运行时间线、消息和附件审计接口。 */
@RestController
@RequestMapping("/system/ai")
@PreAuthorize("@ss.hasPermi('ai:workbench:query')")
public class AiRunController
{
    private final AiRunService runService;
    private final AiAgentRunService agentRunService;

    public AiRunController(AiRunService runService, AiAgentRunService agentRunService)
    {
        this.runService = runService;
        this.agentRunService = agentRunService;
    }

    @GetMapping("/runs")
    public AjaxResult runs(@RequestParam(required = false) String conversationId)
    {
        return AjaxResult.success(runService.listRuns(userScope(), conversationId));
    }

    @GetMapping("/runs/{runId}")
    public AjaxResult run(@PathVariable String runId)
    {
        Object result = runService.getRun(userScope(), runId);
        return result == null ? AjaxResult.error("运行记录不存在") : AjaxResult.success(result);
    }

    /** 返回当前 SSE 运行态和工具时间线；运行结束后仍可通过 /runs/{runId} 读取 MySQL 审计记录。 */
    @GetMapping("/runs/{runId}/status")
    public AjaxResult runtime(@PathVariable String runId)
    {
        Map<String, Object> result = agentRunService.snapshot(runId);
        return result == null ? AjaxResult.error("运行态不存在或已清理") : AjaxResult.success(result);
    }

    /** 取消当前 Agent 运行。LangChain4j 当前流式模型无法强制中断底层 HTTP，但会立即停止 SSE 并丢弃未完成答案。 */
    @PostMapping("/runs/{runId}/cancel")
    @PreAuthorize("@ss.hasPermi('ai:chat:send')")
    public AjaxResult cancel(@PathVariable String runId)
    {
        boolean cancelled = agentRunService.cancel(runId, "用户取消了本次 Agent 运行");
        if (cancelled)
        {
            runService.cancel(runId, "用户取消了本次 Agent 运行");
        }
        return cancelled ? AjaxResult.success("已取消") : AjaxResult.error("运行不存在或已经结束");
    }

    @GetMapping("/messages")
    public AjaxResult messages(@RequestParam String conversationId)
    {
        return AjaxResult.success(runService.listMessages(userScope(), conversationId));
    }

    @GetMapping("/attachments")
    public AjaxResult attachments(@RequestParam(required = false) String conversationId)
    {
        return AjaxResult.success(runService.listAttachments(userScope(), conversationId).stream().map(item ->
        {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("fileId", item.getFileId());
            value.put("scope", item.getScope());
            value.put("conversationId", item.getConversationId());
            value.put("originalName", item.getOriginalName());
            value.put("extension", item.getExtension());
            value.put("contentType", item.getContentType());
            value.put("size", item.getSize());
            value.put("extractedChars", item.getExtractedChars());
            value.put("textExtracted", item.getTextExtracted());
            value.put("extractMessage", item.getExtractMessage());
            value.put("uploadedAt", item.getUploadedAt());
            return value;
        }).toList());
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
}
