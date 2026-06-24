package com.fastclaim.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    // L2 缓存 — 带 TTL 的 ConcurrentHashMap
    private final ConcurrentHashMap<String, CachedEntry<?>> l2Cache = new ConcurrentHashMap<>();

    @Value("${chat.cache.ttl-minutes:5}")
    private int cacheTtlMinutes;

    /**
     * L1 Spring Cache — LLM 响应缓存。
     * cacheKey 由调用方按 MD5(prompt + conversationContext) 生成。
     */
    @Cacheable(value = "llm-responses", key = "#cacheKey")
    public String getCachedLlmResponse(String cacheKey) {
        CachedEntry<?> entry = l2Cache.get(cacheKey);
        if (entry != null && !entry.isExpired(cacheTtlMinutes)) {
            log.debug("LLM 缓存命中 — key: {}", cacheKey);
            return (String) entry.value();
        }
        return null;
    }

    /**
     * L1 Spring Cache — RAG 搜索结果缓存。
     * cacheKey 由调用方按 MD5(query) 生成。
     */
    @Cacheable(value = "rag-searches", key = "#cacheKey")
    public String getCachedRagResult(String cacheKey) {
        CachedEntry<?> entry = l2Cache.get(cacheKey);
        if (entry != null && !entry.isExpired(cacheTtlMinutes)) {
            log.debug("RAG 缓存命中 — key: {}", cacheKey);
            return (String) entry.value();
        }
        return null;
    }

    public void putLlmResponse(String cacheKey, String response) {
        l2Cache.put(cacheKey, new CachedEntry<>(response));
    }

    public void putRagResult(String cacheKey, String result) {
        l2Cache.put(cacheKey, new CachedEntry<>(result));
    }

    /**
     * 定时清理过期条目。
     */
    @Scheduled(fixedRateString = "${chat.cache.cleanup-interval-ms:300000}")
    public void cleanExpiredEntries() {
        int before = l2Cache.size();
        l2Cache.entrySet().removeIf(entry -> entry.getValue().isExpired(cacheTtlMinutes));
        int removed = before - l2Cache.size();
        if (removed > 0) {
            log.debug("缓存清理 — 移除 {} 条过期条目，剩余 {}", removed, l2Cache.size());
        }
    }

    private static class CachedEntry<T> {
        private final T value;
        private final LocalDateTime cachedAt;

        CachedEntry(T value) {
            this.value = value;
            this.cachedAt = LocalDateTime.now();
        }

        T value() {
            return value;
        }

        boolean isExpired(int ttlMinutes) {
            return cachedAt.plusMinutes(ttlMinutes).isBefore(LocalDateTime.now());
        }
    }
}
