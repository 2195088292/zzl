package com.zzl.web.service.ai;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** V6.2 RAG 索引专用线程池，避免文件索引占满通用任务线程。 */
@Configuration
public class AiRagIndexingConfig
{
    @Bean(name = "aiRagIndexTaskExecutor")
    public ThreadPoolTaskExecutor aiRagIndexTaskExecutor()
    {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(120);
        executor.setThreadNamePrefix("ai-rag-index-");
        // 队列满时快速返回 FAILED，不能让上传线程替任务执行索引。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
