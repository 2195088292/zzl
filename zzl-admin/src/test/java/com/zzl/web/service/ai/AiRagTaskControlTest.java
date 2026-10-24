package com.zzl.web.service.ai;

import com.zzl.web.mapper.ai.AiRagIndexTaskMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiRagTaskControlTest
{
    @Test void pausedQueuedWorkDoesNotRunAndResumeCreatesANewAttempt()
    {
        var rag = mock(AiRagService.class);
        var files = mock(AiFileService.class);
        var mapper = mock(AiRagIndexTaskMapper.class);
        var executor = mock(ThreadPoolTaskExecutor.class);
        List<Runnable> queue = new ArrayList<>();
        doAnswer(call -> { queue.add(call.getArgument(0)); return null; }).when(executor).execute(any(Runnable.class));
        var service = new AiRagIndexingService(rag, files, mapper, executor);
        var file = new AiFileService.FileInfo("file", "guide.txt", "txt", "text/plain", 10, "",
                "kb:a", 0, 10, true, "");
        when(files.get("owner", "file")).thenReturn(file);
        var first = service.enqueue("owner", file);
        assertEquals(first.taskId(), service.enqueue("owner", file).taskId());
        assertEquals(1, queue.size());
        assertEquals(AiRagIndexingService.Status.PAUSED, service.pause("owner", first.taskId()).status());
        queue.get(0).run();
        verifyNoInteractions(rag);
        var resumed = service.retry("owner", first.taskId());
        assertNotEquals(first.taskId(), resumed.taskId());
        assertEquals(AiRagIndexingService.Status.QUEUED, resumed.status());
        assertEquals(2, queue.size());
        assertEquals(AiRagIndexingService.Status.PAUSED, service.statusByTaskId("owner", first.taskId()).status());
    }

    @Test void anotherScopeCannotPauseQueuedWork()
    {
        var executor = mock(ThreadPoolTaskExecutor.class);
        var service = new AiRagIndexingService(mock(AiRagService.class), mock(AiFileService.class),
                mock(AiRagIndexTaskMapper.class), executor);
        var file = new AiFileService.FileInfo("file", "guide.txt", "txt", "text/plain", 10, "",
                "kb:a", 0, 10, true, "");
        var task = service.enqueue("owner", file);
        assertEquals(AiRagIndexingService.Status.NOT_INDEXED, service.pause("other", task.taskId()).status());
        assertEquals(AiRagIndexingService.Status.QUEUED, service.status("owner", "file").status());
    }
}
