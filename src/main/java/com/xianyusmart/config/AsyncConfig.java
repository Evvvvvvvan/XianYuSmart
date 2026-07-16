package com.xianyusmart.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import com.xianyusmart.context.TenantContext;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 异步配置
 * 启用Spring的异步方法支持
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Value("${app.executor.core-size:4}")
    private int corePoolSize;

    @Value("${app.executor.max-size:8}")
    private int maxPoolSize;

    @Value("${app.executor.queue-capacity:500}")
    private int queueCapacity;
    
    /**
     * 自定义异步任务线程池
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // 核心线程数
        executor.setCorePoolSize(corePoolSize);
        
        // 最大线程数
        executor.setMaxPoolSize(maxPoolSize);
        
        // 队列容量
        executor.setQueueCapacity(queueCapacity);
        
        // 线程名称前缀
        executor.setThreadNamePrefix("xys-business-");

        // 异步任务继承租户上下文，避免通知、日志和AI配置跨租户读取。
        executor.setTaskDecorator(task -> {
            Long tenantId = TenantContext.get();
            return () -> {
                try {
                    if (tenantId != null) {
                        TenantContext.set(tenantId);
                    }
                    task.run();
                } finally {
                    TenantContext.clear();
                }
            };
        });
        
        // 拒绝策略：由调用线程处理
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        // 等待所有任务完成后再关闭线程池
        executor.setWaitForTasksToCompleteOnShutdown(true);
        
        // 等待时间（秒）
        executor.setAwaitTerminationSeconds(60);
        
        executor.initialize();
        return executor;
    }

    @Bean(name = "websocketMessageExecutor", destroyMethod = "shutdown")
    public ExecutorService websocketMessageExecutor() {
        return new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                namedThreadFactory("xys-message-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "webSocketScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService webSocketScheduler() {
        return scheduledExecutor(4, "xys-websocket-");
    }

    @Bean(name = "autoReplyScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService autoReplyScheduler() {
        return scheduledExecutor(2, "xys-reply-");
    }

    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("xys-schedule-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    private ScheduledExecutorService scheduledExecutor(int poolSize, String threadNamePrefix) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(poolSize, namedThreadFactory(threadNamePrefix));
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    private ThreadFactory namedThreadFactory(String threadNamePrefix) {
        AtomicInteger threadSequence = new AtomicInteger(1);
        return task -> {
            Thread thread = new Thread(task, threadNamePrefix + threadSequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
