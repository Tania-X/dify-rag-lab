package com.example.difyraglab.rewrite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于本地内存的 Query Rewrite 缓存。
 *
 * <p>适合单机/演练环境；生产多实例时应替换为 Redis 等共享缓存。
 */
public class InMemoryQueryRewriteCache implements QueryRewriteCache {

    private static final Logger log = LoggerFactory.getLogger(InMemoryQueryRewriteCache.class);

    private record CacheEntry(String value, Instant expireAt) {
        boolean expired() {
            return Instant.now().isAfter(expireAt);
        }
    }

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Override
    public Optional<String> get(String key) {
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expired()) {
            cache.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public void put(String key, String rewrittenQuery, long ttlSeconds) {
        long ttl = Math.max(1, ttlSeconds);
        cache.put(key, new CacheEntry(rewrittenQuery, Instant.now().plusSeconds(ttl)));
        // 简单防无限增长：每 1000 次写入清理一次过期项
        if (cache.size() % 1000 == 0) {
            cache.entrySet().removeIf(e -> e.getValue().expired());
            log.info("query rewrite cache cleanup, size={}", cache.size());
        }
    }
}
