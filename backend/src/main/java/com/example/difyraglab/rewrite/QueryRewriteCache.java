package com.example.difyraglab.rewrite;

import java.util.Optional;

/**
 * Query Rewrite 结果缓存。
 *
 * <p>当前提供本地内存实现；生产环境可替换为 Redis 实现，接口保持不变。
 */
public interface QueryRewriteCache {

    Optional<String> get(String key);

    void put(String key, String rewrittenQuery, long ttlSeconds);
}
