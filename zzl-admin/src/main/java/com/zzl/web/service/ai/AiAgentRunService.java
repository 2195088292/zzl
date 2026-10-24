package com.zzl.web.service.ai;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * V6 Agent 运行时目录。
 *
 * <p>每次聊天都会有一个 runtimeId。这里记录运行状态和工具时间线，并负责把
 * status/tool/message/done/error 事件写入 SSE。运行记录是短期内存状态，正式对话仍由
 * AiConversationStore 持久化。</p>
 */
@Service
public class AiAgentRunService
{
    private static final int MAX_RUNS = 2000;
    private static final int MAX_TIMELINE = 100;
    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    public String start(String scope, String conversationId, String modelId)
    {
        String runId = UUID.randomUUID().toString().replace("-", "");
        RunState state = new RunState(runId, scope, conversationId, modelId);
        runs.put(runId, state);
        trimRuns();
        return runId;
    }

    public void bind(String runId, SseEmitter emitter)
    {
        RunState state = runs.get(runId);
        if (state == null)
        {
            return;
        }
        state.emitter = emitter;
        emitter.onCompletion(() -> state.emitter = null);
        emitter.onTimeout(() -> cancel(runId, "SSE 连接超时"));
        emitter.onError(error -> state.emitter = null);
        emit(state, "run", Map.of("runId", runId, "status", state.status));
    }

    public void status(String runId, String message)
    {
        RunState state = runs.get(runId);
        if (state == null || state.finished.get())
        {
            return;
        }
        state.status = "RUNNING";
        state.lastMessage = message == null ? "" : message;
        appendTimeline(state, timeline("status", message, null, "RUNNING"));
        emit(state, "status", message == null ? "" : message);
    }

    public void toolStarted(String runId, String toolName, String detail)
    {
        RunState state = runs.get(runId);
        if (state == null || state.finished.get())
        {
            return;
        }
        state.status = "TOOL_RUNNING";
        Map<String, Object> event = timeline("tool", detail, toolName, "RUNNING");
        appendTimeline(state, event);
        emit(state, "tool", event);
    }

    public void toolFinished(String runId, String toolName, String detail, boolean failed)
    {
        RunState state = runs.get(runId);
        if (state == null || state.finished.get())
        {
            return;
        }
        state.status = failed ? "TOOL_ERROR" : "RUNNING";
        Map<String, Object> event = timeline("tool", detail, toolName, failed ? "ERROR" : "COMPLETED");
        appendTimeline(state, event);
        // 工具结果单独使用 tool_result，工作台可把同一工具步骤从 running 标成 completed/error。
        emit(state, "tool_result", event);
    }

    public void message(String runId, String partial)
    {
        RunState state = runs.get(runId);
        if (state == null || state.finished.get() || state.cancelled.get())
        {
            return;
        }
        state.status = "ANSWERING";
        emit(state, "message", partial == null ? "" : partial);
    }

    /** 向工作台发送文件引用，兼容单数 file_ref 和复数 citations 两种前端事件名。 */
    public void retrieval(String runId, Map<String, Object> data)
    {
        RunState state = runs.get(runId);
        if (state == null || state.finished.get()) return;
        emit(state, "retrieval", data);
    }

    /** 向工作台发送文件引用，兼容单数 file_ref 和复数 citations 两种前端事件名。 */
    public void citation(String runId, Map<String, Object> citation)
    {
        RunState state = runs.get(runId);
        if (state == null || state.finished.get() || state.cancelled.get())
        {
            return;
        }
        Map<String, Object> data = citation == null ? Map.of() : citation;
        appendTimeline(state, timeline("file_ref", String.valueOf(data.getOrDefault("fileName", "文件引用")),
                "rag", "COMPLETED"));
        emit(state, "citations", data);
        emit(state, "file_ref", data);
    }

    public void complete(String runId)
    {
        RunState state = runs.get(runId);
        if (state == null || !state.finished.compareAndSet(false, true))
        {
            return;
        }
        state.status = state.cancelled.get() ? "CANCELLED" : "COMPLETED";
        state.finishedAt = Instant.now().toString();
        emit(state, "done", state.cancelled.get() ? "cancelled" : "done");
        completeEmitter(state);
    }

    public void fail(String runId, String message)
    {
        RunState state = runs.get(runId);
        if (state == null || !state.finished.compareAndSet(false, true))
        {
            return;
        }
        state.status = "ERROR";
        state.lastMessage = message == null ? "AI 运行失败" : message;
        state.finishedAt = Instant.now().toString();
        emit(state, "error", state.lastMessage);
        emit(state, "done", "error");
        completeEmitter(state);
    }

    public boolean cancel(String runId, String reason)
    {
        RunState state = runs.get(runId);
        if (state == null || state.finished.get())
        {
            return false;
        }
        state.cancelled.set(true);
        state.status = "CANCELLED";
        state.lastMessage = reason == null || reason.isBlank() ? "用户取消了本次运行" : reason;
        appendTimeline(state, timeline("status", state.lastMessage, null, "CANCELLED"));
        if (state.finished.compareAndSet(false, true))
        {
            emit(state, "status", state.lastMessage);
            emit(state, "done", "cancelled");
            state.finishedAt = Instant.now().toString();
            completeEmitter(state);
        }
        return true;
    }

    public boolean isCancelled(String runId)
    {
        RunState state = runs.get(runId);
        return state != null && state.cancelled.get();
    }

    public Map<String, Object> snapshot(String runId)
    {
        RunState state = runs.get(runId);
        return state == null ? null : state.snapshot();
    }

    public List<Map<String, Object>> list(String scope)
    {
        List<Map<String, Object>> result = new ArrayList<>();
        runs.values().stream()
                .filter(item -> scope == null || scope.equals(item.scope))
                .sorted((left, right) -> right.startedAt.compareTo(left.startedAt))
                .limit(50)
                .forEach(item -> result.add(item.snapshot()));
        return result;
    }

    private void emit(RunState state, String event, Object data)
    {
        SseEmitter emitter = state.emitter;
        if (emitter == null)
        {
            return;
        }
        synchronized (state.emitterLock)
        {
            if (state.emitter != emitter)
            {
                return;
            }
            try
            {
                emitter.send(SseEmitter.event().name(event).data(data));
            }
            catch (IOException | IllegalStateException ignored)
            {
                state.emitter = null;
            }
        }
    }

    private void completeEmitter(RunState state)
    {
        SseEmitter emitter = state.emitter;
        state.emitter = null;
        if (emitter != null)
        {
            emitter.complete();
        }
    }

    private Map<String, Object> timeline(String type, String detail, String tool, String status)
    {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("status", status);
        event.put("detail", detail == null ? "" : detail);
        if (tool != null && !tool.isBlank())
        {
            event.put("tool", tool);
        }
        event.put("at", Instant.now().toString());
        return event;
    }

    private void appendTimeline(RunState state, Map<String, Object> event)
    {
        synchronized (state.timeline)
        {
            state.timeline.add(event);
            while (state.timeline.size() > MAX_TIMELINE)
            {
                state.timeline.remove(0);
            }
        }
    }

    private void trimRuns()
    {
        if (runs.size() <= MAX_RUNS)
        {
            return;
        }
        runs.values().stream()
                .filter(item -> item.finished.get())
                .sorted((left, right) -> left.startedAt.compareTo(right.startedAt))
                .limit(Math.max(1, runs.size() - MAX_RUNS))
                .forEach(item -> runs.remove(item.runId, item));
    }

    private static final class RunState
    {
        private final String runId;
        private final String scope;
        private final String conversationId;
        private final String modelId;
        private final String startedAt = Instant.now().toString();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final Object emitterLock = new Object();
        private final List<Map<String, Object>> timeline = new ArrayList<>();
        private volatile String status = "QUEUED";
        private volatile String lastMessage = "";
        private volatile String finishedAt;
        private volatile SseEmitter emitter;

        private RunState(String runId, String scope, String conversationId, String modelId)
        {
            this.runId = runId;
            this.scope = scope;
            this.conversationId = conversationId;
            this.modelId = modelId;
        }

        private Map<String, Object> snapshot()
        {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("runId", runId);
            result.put("scope", scope);
            result.put("conversationId", conversationId);
            result.put("model", modelId);
            result.put("status", status);
            result.put("message", lastMessage);
            result.put("startedAt", startedAt);
            result.put("finishedAt", finishedAt);
            synchronized (timeline)
            {
                result.put("timeline", Collections.unmodifiableList(new ArrayList<>(timeline)));
            }
            return result;
        }
    }
}
