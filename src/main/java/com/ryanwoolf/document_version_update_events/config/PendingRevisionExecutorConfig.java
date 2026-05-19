package com.ryanwoolf.document_version_update_events.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class PendingRevisionExecutorConfig {

    public static final String PENDING_REVISION_EXECUTOR_BEAN_NAME = "pendingRevisionExecutor";

    @Bean(name = PENDING_REVISION_EXECUTOR_BEAN_NAME)
    public ThreadPoolTaskExecutor pendingRevisionExecutor(
            @Value("${pending-revisions.executor.core-pool-size:4}") int corePoolSize,
            @Value("${pending-revisions.executor.max-pool-size:4}") int maxPoolSize,
            @Value("${pending-revisions.executor.queue-capacity:100}") int queueCapacity) {
        ThreadPoolTaskExecutor pendingRevisionExecutor = new ThreadPoolTaskExecutor();
        pendingRevisionExecutor.setCorePoolSize(corePoolSize);
        pendingRevisionExecutor.setMaxPoolSize(maxPoolSize);
        pendingRevisionExecutor.setQueueCapacity(queueCapacity);
        pendingRevisionExecutor.setThreadNamePrefix("pending-revision-");
        pendingRevisionExecutor.initialize();
        return pendingRevisionExecutor;
    }

}
