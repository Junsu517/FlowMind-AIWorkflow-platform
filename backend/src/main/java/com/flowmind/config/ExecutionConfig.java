package com.flowmind.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 工作流执行线程池（任务卡：核心 2、最大 4、队列 100）。 */
@Configuration
public class ExecutionConfig {

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor executionThreadPool() {
        AtomicInteger seq = new AtomicInteger();
        return new ThreadPoolExecutor(
                2, 4, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "flowmind-exec-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
    }
}
