package com.zzl.web.service.ai;

import com.alibaba.fastjson2.JSON;
import com.zzl.web.mapper.ai.AiAttachmentMapper;
import com.zzl.web.mapper.ai.AiAttachmentRecord;
import com.zzl.web.mapper.ai.AiMessageMapper;
import com.zzl.web.mapper.ai.AiMessageRecord;
import com.zzl.web.mapper.ai.AiRunMapper;
import com.zzl.web.mapper.ai.AiRunRecord;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * V6 Agent 运行审计服务。
 *
 * <p>每次模型调用对应一条 run，事件以 JSON 时间线写入 MySQL；消息和附件使用独立表，
 * 便于后续做搜索、统计和前端任务时间线。任何持久化失败都不会阻断模型回答。</p>
 */
@Service
public class AiRunService
{
    private final AiRunMapper runMapper;
    private final AiMessageMapper messageMapper;
    private final AiAttachmentMapper attachmentMapper;
    private final Map<String, RunState> activeRuns = new ConcurrentHashMap<>();

    public AiRunService(AiRunMapper runMapper, AiMessageMapper messageMapper,
            AiAttachmentMapper attachmentMapper)
    {
        this.runMapper = runMapper;
        this.messageMapper = messageMapper;
        this.attachmentMapper = attachmentMapper;
    }

    public RunHandle start(String scope, String conversationId, String model, String question)
    {
        String runId = UUID.randomUUID().toString().replace("-", "");
        return start(runId, scope, conversationId, model, question);
    }

    /** 使用外部 runtimeId 建立审计记录，保证 SSE、内存运行态和 MySQL 记录可互相定位。 */
    public RunHandle start(String runId, String scope, String conversationId, String model, String question)
    {
        RunHandle handle = new RunHandle(runId, System.currentTimeMillis(), scope,
                conversationId == null || conversationId.isBlank() ? "default" : conversationId, model);
        RunState state = new RunState(handle);
        activeRuns.put(runId, state);
        try
        {
            runMapper.insertRun(runId, handle.scope(), handle.conversationId(), handle.model(),
                    question, "RUNNING", LocalDateTime.now(), "[]");
        }
        catch (Exception ignored)
        {
            // 数据库未初始化时仍继续运行；内存状态会让本次 SSE 正常完成。
        }
        event(handle, "run_started", Map.of("question", question == null ? "" : question));
        return handle;
    }

    public void event(RunHandle handle, String type, Object data)
    {
        if (handle == null)
        {
            return;
        }
        RunState state = activeRuns.get(handle.runId());
        if (state == null)
        {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type == null ? "event" : type);
        event.put("at", System.currentTimeMillis());
        event.put("data", data == null ? "" : data);
        synchronized (state)
        {
            state.events().add(event);
            try
            {
                runMapper.updateEvents(handle.runId(), JSON.toJSONString(state.events()));
            }
            catch (Exception ignored)
            {
                // 事件写入失败不应断开模型流。
            }
        }
    }

    public void message(RunHandle handle, String role, String content)
    {
        if (handle == null || content == null || content.isBlank())
        {
            return;
        }
        try
        {
            messageMapper.insert(UUID.randomUUID().toString().replace("-", ""), handle.scope(),
                    handle.conversationId(), handle.runId(), role, content, handle.model(),
                    Math.max(1, content.length() / 2));
        }
        catch (Exception ignored)
        {
            // 消息审计失败不影响主流程。
        }
    }

    public void complete(RunHandle handle, String answer)
    {
        if (handle == null)
        {
            return;
        }
        event(handle, "run_completed", Map.of("answerChars", answer == null ? 0 : answer.length()));
        finish(handle, "SUCCEEDED", answer, null);
        message(handle, "assistant", answer);
    }

    public void fail(RunHandle handle, Throwable error)
    {
        if (handle == null)
        {
            return;
        }
        String message = error == null ? "未知错误" : error.getMessage();
        if (message == null || message.isBlank())
        {
            message = error == null ? "未知错误" : error.getClass().getSimpleName();
        }
        event(handle, "run_failed", Map.of("message", message));
        finish(handle, "FAILED", null, message);
    }

    /** 将尚未完成的运行标记为取消，供 Agent 运行态取消接口调用。 */
    public boolean cancel(String runId, String message)
    {
        RunState state = activeRuns.get(runId);
        if (state == null)
        {
            return false;
        }
        event(state.handle(), "run_cancelled", Map.of("message", message == null ? "用户取消" : message));
        activeRuns.remove(runId);
        try
        {
            runMapper.updateRun(runId, "CANCELLED", LocalDateTime.now(),
                    state == null ? 0L : Math.max(0, System.currentTimeMillis() - state.handle().startedAtMillis()),
                    null, message == null ? "用户取消" : message,
                    state == null ? "[]" : JSON.toJSONString(state.events()));
        }
        catch (Exception ignored)
        {
            // 运行态取消仍然有效，数据库审计失败不阻断用户操作。
        }
        return true;
    }

    private void finish(RunHandle handle, String status, String answer, String errorMessage)
    {
        RunState state = activeRuns.get(handle.runId());
        String events = "[]";
        if (state != null)
        {
            synchronized (state)
            {
                events = JSON.toJSONString(state.events());
            }
        }
        long duration = Math.max(0, System.currentTimeMillis() - handle.startedAtMillis());
        try
        {
            runMapper.updateRun(handle.runId(), status, LocalDateTime.now(), duration, answer,
                    errorMessage, events);
        }
        catch (Exception ignored)
        {
            // 运行结果已经通过 SSE 返回，数据库错误只影响审计。
        }
        activeRuns.remove(handle.runId());
    }

    public List<AiRunRecord> listRuns(String scope, String conversationId)
    {
        try
        {
            return conversationId == null || conversationId.isBlank()
                    ? runMapper.selectRecent(scope) : runMapper.selectByConversation(scope, conversationId);
        }
        catch (Exception ignored)
        {
            return List.of();
        }
    }

    public AiRunRecord getRun(String scope, String runId)
    {
        try
        {
            return runMapper.selectOne(scope, runId);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    public List<AiMessageRecord> listMessages(String scope, String conversationId)
    {
        try
        {
            return messageMapper.selectByConversation(scope, conversationId);
        }
        catch (Exception ignored)
        {
            return List.of();
        }
    }

    public void saveAttachment(String scope, AiFileService.FileInfo file)
    {
        if (file == null || file.id() == null || file.id().isBlank())
        {
            return;
        }
        try
        {
            attachmentMapper.upsert(file.id(), scope, file.conversationId(), file.originalName(), file.extension(),
                    file.contentType(), file.size(), file.path(), file.extractedChars(), file.textExtracted(),
                    file.extractMessage());
        }
        catch (Exception ignored)
        {
            // Redis/文件服务仍是当前 V5 兼容路径。
        }
    }

    public List<AiAttachmentRecord> listAttachments(String scope, String conversationId)
    {
        try
        {
            return attachmentMapper.selectByScope(scope, conversationId);
        }
        catch (Exception ignored)
        {
            return List.of();
        }
    }

    public void deleteAttachment(String scope, String fileId)
    {
        try
        {
            attachmentMapper.delete(scope, fileId);
        }
        catch (Exception ignored)
        {
            // 物理文件删除由 AiFileService 完成。
        }
    }

    public record RunHandle(String runId, long startedAtMillis, String scope, String conversationId, String model)
    {
    }

    private static final class RunState
    {
        private final RunHandle handle;
        private final List<Map<String, Object>> events = new ArrayList<>();

        private RunState(RunHandle handle)
        {
            this.handle = handle;
        }

        private List<Map<String, Object>> events()
        {
            return events;
        }

        private RunHandle handle()
        {
            return handle;
        }
    }
}
