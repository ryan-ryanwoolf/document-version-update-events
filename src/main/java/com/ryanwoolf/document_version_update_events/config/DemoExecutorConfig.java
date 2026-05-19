package com.ryanwoolf.document_version_update_events.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class DemoExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService demoExecutorService() {
        return Executors.newFixedThreadPool(4);
    }

}
