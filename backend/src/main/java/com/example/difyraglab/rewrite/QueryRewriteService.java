package com.example.difyraglab.rewrite;

import com.example.difyraglab.config.RewriteProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Query Rewrite 服务。
 *
 * <p>原则：只做语言规范化、同义改写、补全省略、术语统一、消歧；
 * 禁止加入用户问题中没有的信息，禁止加入答案或文档特有词汇。
 *
 * <p>降级策略：LLM 不可用、超时、返回异常时，一律回退到原始 query，不影响主链路。
 */
public class QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);

    private static final String SYSTEM_PROMPT = """
            你是金融知识库的检索查询改写助手。

            你的任务：
            - 把用户问题改写成更适合知识库检索的简洁中文查询
            - 只允许：
              1. 口语转书面语
              2. 补全省略成分
              3. 同义词替换
              4. 术语规范化
              5. 消除歧义
              6. 拆解多意图问题

            禁止：
            - 加入用户问题中没有的信息
            - 加入答案或文档中才出现的特有词汇
            - 猜测业务含义
            - 编造数字、期限、条款

            如果原问题已经清晰、适合检索，直接原样输出。

            示例 1（口语转书面）：
            用户：支付那个老是失败咋整
            改写：支付网关调用失败如何排查？

            示例 2（同义改写，不新增信息）：
            用户：反洗钱上报记录要保留多久？
            改写：反洗钱可疑交易上报记录的保存期限是多久？

            示例 3（原问题已清晰，原样输出）：
            用户：支付网关读超时设置是多少秒？
            改写：支付网关读超时设置是多少秒？
            """;

    private final RewriteProperties props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final QueryRewriteCache cache;

    // 可观测性计数（简单实现；生产可接入 Micrometer/Prometheus）
    private final AtomicLong totalCount = new AtomicLong();
    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();
    private final AtomicLong fallbackCount = new AtomicLong();
    private final AtomicLong noopCount = new AtomicLong();
    private final AtomicLong cacheHitCount = new AtomicLong();

    public QueryRewriteService(RewriteProperties props, RestClient rewriteRestClient,
                               ObjectMapper objectMapper, QueryRewriteCache cache) {
        this.props = props;
        this.restClient = rewriteRestClient;
        this.objectMapper = objectMapper;
        this.cache = cache;
    }

    /**
     * 改写查询；未启用或失败时返回原 query。
     */
    public String rewrite(String query) {
        if (!props.enabled() || query == null || query.isBlank()) {
            return query;
        }

        totalCount.incrementAndGet();
        String normalized = query.trim();
        String cacheKey = cacheKey(normalized);

        var cached = cache.get(cacheKey);
        if (cached.isPresent()) {
            cacheHitCount.incrementAndGet();
            return cached.get();
        }

        try {
            String rewritten = callLlm(normalized);
            if (rewritten == null || rewritten.isBlank() || rewritten.equals(normalized)) {
                noopCount.incrementAndGet();
                successCount.incrementAndGet();
                cache.put(cacheKey, normalized, props.cacheTtlSeconds());
                return normalized;
            }
            successCount.incrementAndGet();
            cache.put(cacheKey, rewritten, props.cacheTtlSeconds());
            return rewritten;
        } catch (Exception e) {
            failureCount.incrementAndGet();
            fallbackCount.incrementAndGet();
            log.warn("query rewrite failed, fallback to original query. query={}, error={}",
                    normalized, e.getMessage());
            return normalized;
        }
    }

    private String callLlm(String query) {
        String userPrompt = "用户问题：" + query + "\n改写后：";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        body.put("temperature", props.temperature());
        body.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", userPrompt)
        ));

        JsonNode resp = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (resp == null) {
            throw new IllegalStateException("rewrite LLM returned null response");
        }

        JsonNode content = resp.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("rewrite LLM response missing content");
        }
        return content.asText().trim();
    }

    private String cacheKey(String query) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String raw = props.promptVersion() + ":" + query;
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return "rewrite:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // 理论上不会发生
            return "rewrite:" + Integer.toHexString(query.hashCode());
        }
    }

    /**
     * 返回当前可观测性快照。
     */
    public Map<String, Object> metrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.enabled());
        m.put("total", totalCount.get());
        m.put("success", successCount.get());
        m.put("failure", failureCount.get());
        m.put("fallback", fallbackCount.get());
        m.put("noop", noopCount.get());
        m.put("cache_hit", cacheHitCount.get());
        return m;
    }
}
