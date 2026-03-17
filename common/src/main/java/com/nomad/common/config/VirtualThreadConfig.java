package com.nomad.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@Profile("loom")
public class VirtualThreadConfig {

    @Bean(name = "virtualThreadExecutor")
    public ExecutorService virtualThreadExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                        .name("vt-", 0)
                        .factory()
        );
    }
}