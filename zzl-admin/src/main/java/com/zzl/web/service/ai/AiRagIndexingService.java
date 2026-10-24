package com.zzl.web.service.ai;

import com.zzl.web.mapper.ai.AiRagIndexTaskMapper;
import com.zzl.web.mapper.ai.AiRagIndexTaskRecord;
import jakarta.annotation.PostConstruct;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * V6.3 持久化异步文档索引任务。
 *
 * <p>文件上传只负责保存原文件和元数据，切片、关键词提取和入库交给线程池执行。
 * 任务状态落在 zzl_ai_rag_index_task，接口可以在上传后查询
 * QUEUED/RUNNING/SUCCEEDED/FAILED/UNSUPPORTED；进程重启后未完成任务会自动重新排队。</p>
 */
@Service
@org.springframework.context.annotation.DependsOn("aiConversationSchemaInitializer")
public class AiRagIndexingService
{
    private static final int MAX_TASKS = 2000;

    private final AiRagService ragService;
    private final AiFileService fileService;
    private final AiRagIndexTaskMapper taskMapper;
    private final ThreadPoolTaskExecutor executor;
    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final Map<String, String> latestByFile = new ConcurrentHashMap<>();

    public AiRagIndexingService(AiRagService ragService, AiFileService fileService,
            AiRagIndexTaskMapper taskMapper,
            @Qualifier("aiRagIndexTaskExecutor") ThreadPoolTaskExecutor executor)
    {
        this.ragService = ragService;
        this.fileService = fileService;
        this.taskMapper = taskMapper;
        this.executor = executor;
    }

    /** 应用重启后恢复尚未完成的任务，避免上传状态只存在内存中。 */
    @PostConstruct
    public void recoverPendingTasks()
    {
        try
        {
            for (AiRagIndexTaskRecord record : taskMapper.selectPending())
            {
                TaskState state = TaskState.from(record);
                if (state == null)
                {
                    continue;
                }
                // RUNNING 任务可能是在进程退出时中断的，重新置为 QUEUED 后再执行。
                state.requeueAfterRestart();
                remember(state);
                persist(state);
                try
                {
                    schedule(state);
                }
                catch (RejectedExecutionException error)
                {
                    state.fail("索引队列已满，请稍后重试");
                    persist(state);
                }
            }
        }
        catch (Exception ignored)
        {
            // 数据库不可用时保留当前进程能力，后续上传仍可进入内存队列。
        }
    }

    /** 将文件加入索引队列；同一文件已有排队或运行任务时直接返回该任务。 */
    public synchronized IndexTask enqueue(String scope, AiFileService.FileInfo file)
    {
        if (file == null || !StringUtils.hasText(file.id()))
        {
            return IndexTask.failed("", "文件不存在");
        }
        String key = fileKey(scope, file.id());
        String previousId = latestByFile.get(key);
        if (previousId != null)
        {
            TaskState previous = tasks.get(previousId);
            if (previous != null && (Status.QUEUED == previous.status || Status.RUNNING == previous.status))
            {
                return previous.snapshot();
            }
        }
        if (previousId == null)
        {
            AiRagIndexTaskRecord previous = findPersisted(scope, file.id());
            TaskState restored = TaskState.from(previous);
            if (restored != null && (restored.status == Status.QUEUED || restored.status == Status.RUNNING))
            {
                remember(restored);
                return restored.snapshot();
            }
        }
        trimIfNeeded();
        TaskState state = new TaskState(UUID.randomUUID().toString().replace("-", ""),
                normalizeScope(scope), file.id(), file.originalName());
        tasks.put(state.taskId, state);
        latestByFile.put(key, state.taskId);
        persistInsert(state);
        if (isImage(file) && !file.textExtracted())
        {
            state.unsupported("图片不建立文本索引，可直接用于图片分析");
            persist(state);
            return state.snapshot();
        }
        try
        {
            schedule(state);
        }
        catch (RejectedExecutionException error)
        {
            state.fail("索引队列已满，请稍后重试");
            persist(state);
        }
        return state.snapshot();
    }

    private boolean isImage(AiFileService.FileInfo file)
    {
        String contentType = file.contentType() == null ? "" : file.contentType().toLowerCase(Locale.ROOT);
        String extension = file.extension() == null ? "" : file.extension().toLowerCase(Locale.ROOT);
        return contentType.startsWith("image/")
                || List.of("png", "jpg", "jpeg", "gif", "webp", "bmp").contains(extension);
    }

    /** 查询文件最近一次索引任务；没有任务时返回 NOT_INDEXED。 */
    public IndexTask status(String scope, String fileId)
    {
        if (!StringUtils.hasText(fileId))
        {
            return IndexTask.notIndexed("", fileId);
        }
        String taskId = latestByFile.get(fileKey(scope, fileId));
        TaskState state = taskId == null ? null : tasks.get(taskId);
        if (state == null)
        {
            state = TaskState.from(findPersisted(scope, fileId));
            if (state != null)
            {
                remember(state);
                if (state.status == Status.QUEUED || state.status == Status.RUNNING)
                {
                    state.requeueAfterRestart();
                    persist(state);
                    try { schedule(state); } catch (RejectedExecutionException error) { state.fail("索引队列已满，请稍后重试"); persist(state); }
                }
            }
        }
        return state == null ? IndexTask.notIndexed("", fileId) : state.snapshot();
    }

    /** 按任务 ID 查询，便于前端上传后直接轮询。 */
    public IndexTask statusByTaskId(String taskId)
    {
        return statusByTaskId("anonymous", taskId);
    }

    /** 按用户作用域查询任务，避免不同用户通过任务 ID 互相读取状态。 */
    public IndexTask statusByTaskId(String scope, String taskId)
    {
        TaskState state = taskId == null ? null : tasks.get(taskId);
        if (state != null && !normalizeScope(scope).equals(state.scope))
        {
            state = null;
        }
        if (state == null && StringUtils.hasText(taskId))
        {
            state = TaskState.from(findPersistedByTaskId(scope, taskId));
            if (state != null)
            {
                remember(state);
            }
        }
        return state == null ? IndexTask.notIndexed(taskId, "") : state.snapshot();
    }

    /** 对失败任务创建一次新的索引尝试；文件级重建接口继续保持兼容。 */
    public IndexTask retry(String scope, String taskId)
    {
        IndexTask current = statusByTaskId(scope, taskId);
        if (current.status() == Status.NOT_INDEXED)
        {
            return IndexTask.failed(taskId, "索引任务不存在");
        }
        if (current.status() == Status.QUEUED || current.status() == Status.RUNNING)
        {
            return current;
        }
        AiFileService.FileInfo file = fileService.get(scope, current.fileId());
        return file == null ? IndexTask.failed(taskId, "文件不存在或已删除") : enqueue(scope, file);
    }

    /** 文件删除时取消尚未提交的任务并清理最近任务映射。 */
    public void forget(String scope, String fileId)
    {
        if (!StringUtils.hasText(fileId))
        {
            return;
        }
        String key = fileKey(scope, fileId);
        String taskId = latestByFile.remove(key);
        TaskState state = taskId == null ? null : tasks.get(taskId);
        if (state != null && (state.status == Status.QUEUED || state.status == Status.RUNNING))
        {
            state.cancel();
        }
        tasks.values().removeIf(item -> normalizeScope(scope).equals(item.scope)
                && fileId.equals(item.fileId));
        try
        {
            taskMapper.deleteByFile(normalizeScope(scope), fileId);
        }
        catch (Exception ignored)
        {
            // 数据库不可用时仍完成内存和物理文件清理。
        }
    }

    public IndexTask pause(String scope, String taskId)
    {
        IndexTask current = statusByTaskId(scope, taskId);
        if (current.status() == Status.NOT_INDEXED) return current;
        TaskState state = tasks.get(taskId);
        if (state != null && (current.status() == Status.QUEUED || current.status() == Status.RUNNING))
        {
            synchronized (state)
            {
                state.cancelled = true;
                state.status = Status.PAUSED;
                state.stage = "已暂停";
                state.message = "任务已暂停，恢复后重新建立本文件索引";
                persist(state);
            }
        }
        return state == null ? current : state.snapshot();
    }

    private void schedule(TaskState state)
    {
        executor.execute(() -> execute(state));
    }

    private void execute(TaskState state)
    {
        synchronized (state)
        {
            if (state.cancelled) return;
            state.running();
            persist(state);
        }
        try
        {
            AiFileService.FileInfo file = fileService.get(state.scope, state.fileId);
            if (file == null)
            {
                state.fail("文件不存在或已删除");
                persist(state);
                return;
            }
            AiFileService.ExtractedFile extracted = fileService.content(state.scope, state.fileId);
            if (state.cancelled)
            {
                return;
            }
            AiKnowledgeBaseConfigService.Settings options = ragService.settingsForFile(state.scope, file);
            state.indexSignature = ragService.signature(options);
            state.stage = "生成向量";
            int chunks = ragService.indexFile(state.scope, extracted.file(), extracted.content(), options,
                    (done, total) -> {
                        if (state.cancelled) throw new java.util.concurrent.CancellationException();
                        state.progress = Math.min(95, 10 + 85 * done / Math.max(total, 1));
                        state.stage = "生成向量 " + done + "/" + total;
                        persist(state);
                    }, () -> {
                        if (state.cancelled) throw new java.util.concurrent.CancellationException();
                    }, state);
            if (state.cancelled) return;
            if (chunks == 0)
                state.unsupported("文件没有可索引文本");
            else
                state.succeed(chunks, "索引完成");
            persist(state);
        }
        catch (Exception error)
        {
            if (state.cancelled) return;
            if (state.attempts < 3)
            {
                state.status = Status.QUEUED;
                state.message = "暂时失败，准备第 " + (state.attempts + 1) + " 次尝试：" + readable(error);
                state.stage = "等待自动重试";
                persist(state);
                java.util.concurrent.CompletableFuture.delayedExecutor(3L * state.attempts,
                        java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
                    if (!state.cancelled)
                    {
                        try { schedule(state); }
                        catch (RejectedExecutionException rejected) { state.fail("索引队列已满，请稍后重试"); persist(state); }
                    }
                });
            }
            else { state.fail(readable(error)); persist(state); }
        }
    }

    private void remember(TaskState state)
    {
        if (state == null)
        {
            return;
        }
        tasks.put(state.taskId, state);
        latestByFile.merge(fileKey(state.scope, state.fileId), state.taskId,
                (left, right) -> {
                    TaskState oldState = tasks.get(left);
                    return oldState == null || oldState.createdAt <= state.createdAt ? right : left;
                });
    }

    private void persistInsert(TaskState state)
    {
        try
        {
            taskMapper.insert(state.taskId, state.scope, state.fileId, state.fileName,
                    state.status.name(), state.chunks, state.message, state.attempts);
        }
        catch (Exception ignored)
        {
            // 索引增强能力不能阻断上传；内存状态仍可用于当前进程查询。
        }
    }

    private void persist(TaskState state)
    {
        if (state == null)
        {
            return;
        }
        try
        {
            taskMapper.updateStatus(state.taskId, state.status.name(), state.chunks, state.message,
                    state.attempts, state.startedAtDateTime(), state.finishedAtDateTime());
            taskMapper.updateProgress(state.taskId, state.progress, state.stage, state.indexSignature);
        }
        catch (Exception ignored)
        {
            // 表尚未初始化或数据库暂时不可用时保持内存状态。
        }
    }

    private AiRagIndexTaskRecord findPersisted(String scope, String fileId)
    {
        try { return taskMapper.selectLatestByFile(normalizeScope(scope), fileId); }
        catch (Exception ignored) { return null; }
    }

    private AiRagIndexTaskRecord findPersistedByTaskId(String scope, String taskId)
    {
        try { return taskMapper.selectByTaskId(normalizeScope(scope), taskId); }
        catch (Exception ignored) { return null; }
    }

    private void trimIfNeeded()
    {
        if (tasks.size() < MAX_TASKS)
        {
            return;
        }
        List<TaskState> finished = tasks.values().stream()
                .filter(item -> item.status == Status.SUCCEEDED || item.status == Status.FAILED
                        || item.status == Status.UNSUPPORTED)
                .sorted(Comparator.comparingLong(item -> item.createdAt))
                .toList();
        int removeCount = Math.max(1, finished.size() / 4);
        for (int index = 0; index < Math.min(removeCount, finished.size()); index++)
        {
            TaskState removed = finished.get(index);
            tasks.remove(removed.taskId, removed);
        }
    }

    private String fileKey(String scope, String fileId)
    {
        return normalizeScope(scope) + ":" + fileId;
    }

    private String normalizeScope(String scope)
    {
        return StringUtils.hasText(scope) ? scope.trim() : "anonymous";
    }

    private String readable(Throwable error)
    {
        Throwable cause = error;
        while (cause.getCause() != null && !StringUtils.hasText(cause.getMessage()))
        {
            cause = cause.getCause();
        }
        return StringUtils.hasText(cause.getMessage()) ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    public enum Status
    {
        QUEUED, RUNNING, PAUSED, SUCCEEDED, FAILED, UNSUPPORTED, NOT_INDEXED
    }

    public record IndexTask(String taskId, String fileId, String fileName, Status status,
            int chunks, String message, int attempts, long createdAt, Long startedAt, Long finishedAt,
            int progress, String stage, String indexSignature)
    {
        public IndexTask(String taskId, String fileId, String fileName, Status status, int chunks,
                String message, int attempts, long createdAt, Long startedAt, Long finishedAt)
        {
            this(taskId, fileId, fileName, status, chunks, message, attempts, createdAt, startedAt, finishedAt, 0, "", "");
        }

        static IndexTask failed(String taskId, String message)
        {
            return new IndexTask(taskId, "", "", Status.FAILED, 0, message, 0,
                    System.currentTimeMillis(), null, System.currentTimeMillis());
        }

        static IndexTask notIndexed(String taskId, String fileId)
        {
            return new IndexTask(taskId, fileId == null ? "" : fileId, "", Status.NOT_INDEXED,
                    0, "尚未建立索引", 0, 0, null, null);
        }
    }

    private static final class TaskState
    {
        private final String taskId;
        private final String scope;
        private final String fileId;
        private final String fileName;
        private long createdAt = System.currentTimeMillis();
        private volatile Status status = Status.QUEUED;
        private volatile int chunks;
        private volatile String message = "等待索引队列";
        private volatile int attempts;
        private volatile Long startedAt;
        private volatile Long finishedAt;
        private volatile boolean cancelled;
        private volatile int progress;
        private volatile String stage = "等待队列";
        private volatile String indexSignature = "";

        private TaskState(String taskId, String scope, String fileId, String fileName)
        {
            this.taskId = taskId;
            this.scope = scope;
            this.fileId = fileId;
            this.fileName = fileName == null ? "" : fileName;
        }

        private static TaskState from(AiRagIndexTaskRecord record)
        {
            if (record == null || !StringUtils.hasText(record.getTaskId())
                    || !StringUtils.hasText(record.getFileId()))
            {
                return null;
            }
            TaskState state = new TaskState(record.getTaskId(),
                    StringUtils.hasText(record.getScope()) ? record.getScope() : "anonymous",
                    record.getFileId(), record.getFileName());
            state.status = parseStatus(record.getStatus());
            state.chunks = record.getChunks() == null ? 0 : Math.max(0, record.getChunks());
            state.message = StringUtils.hasText(record.getMessage()) ? record.getMessage() : state.message;
            state.attempts = record.getAttempts() == null ? 0 : Math.max(0, record.getAttempts());
            state.createdAt = epoch(record.getCreatedAt(), state.createdAt);
            state.startedAt = epochNullable(record.getStartedAt());
            state.finishedAt = epochNullable(record.getFinishedAt());
            state.progress = record.getProgress() == null ? 0 : record.getProgress();
            state.stage = record.getStage() == null ? "" : record.getStage();
            state.indexSignature = record.getIndexSignature() == null ? "" : record.getIndexSignature();
            return state;
        }

        private static Status parseStatus(String value)
        {
            try { return value == null ? Status.QUEUED : Status.valueOf(value); }
            catch (Exception ignored) { return Status.QUEUED; }
        }

        private static long epoch(java.time.LocalDateTime value, long fallback)
        {
            return value == null ? fallback : value.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        }

        private static Long epochNullable(java.time.LocalDateTime value)
        {
            return value == null ? null : value.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        }

        private void requeueAfterRestart()
        {
            if (status == Status.QUEUED || status == Status.RUNNING)
            {
                status = Status.QUEUED;
                message = "应用重启后重新排队";
                startedAt = null;
                finishedAt = null;
                cancelled = false;
            }
        }

        private java.time.LocalDateTime startedAtDateTime()
        {
            return toDateTime(startedAt);
        }

        private java.time.LocalDateTime finishedAtDateTime()
        {
            return toDateTime(finishedAt);
        }

        private java.time.LocalDateTime toDateTime(Long millis)
        {
            return millis == null ? null : java.time.LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault());
        }

        private synchronized void running()
        {
            status = Status.RUNNING;
            attempts++;
            startedAt = System.currentTimeMillis();
            message = "正在切片并写入索引";
            stage = "抽取文本";
            progress = 5;
        }

        private synchronized void succeed(int chunks, String message)
        {
            this.status = Status.SUCCEEDED;
            this.progress = 100;
            this.stage = "已完成";
            this.chunks = Math.max(0, chunks);
            this.message = message;
            this.finishedAt = System.currentTimeMillis();
        }

        private synchronized void unsupported(String message)
        {
            this.status = Status.UNSUPPORTED;
            this.stage = "无可索引文本";
            this.message = message;
            this.finishedAt = System.currentTimeMillis();
        }

        private synchronized void fail(String message)
        {
            this.status = Status.FAILED;
            this.stage = "失败";
            this.message = message;
            this.finishedAt = System.currentTimeMillis();
        }

        private synchronized void cancel()
        {
            this.cancelled = true;
            this.status = Status.FAILED;
            this.message = "文件已删除，索引任务已取消";
            this.finishedAt = System.currentTimeMillis();
        }

        private IndexTask snapshot()
        {
            return new IndexTask(taskId, fileId, fileName, status, chunks, message, attempts,
                    createdAt, startedAt, finishedAt, progress, stage, indexSignature);
        }
    }
}
