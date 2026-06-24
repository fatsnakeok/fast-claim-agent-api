package com.fastclaim.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@EnableCaching
public class CacheConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CacheConfiguration.class);

    @Bean
    public CacheManager cacheManager() {
        log.info("初始化 Spring Cache — llm-responses, rag-searches 两个缓存域");
        ConcurrentMapCacheManager manager = new ConcurrentMapCacheManager();
        manager.setCacheNames(List.of("llm-responses", "rag-searches"));
        return manager;
    }
}
