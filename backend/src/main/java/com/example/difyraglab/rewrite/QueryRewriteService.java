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
import java.util.ArrayList;
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
 * <p>除改写文本外，还会尝试从问题中提取可结构化的元数据过滤条件
 * （如年份、版本、文档类型），供检索前做硬性过滤。
 *
 * <p>降级策略：LLM 不可用、超时、返回异常时，一律回退到原始 query，不影响主链路。
 */
public class QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);

    private static final String SYSTEM_PROMPT = """
            你是金融知识库的检索查询改写助手。

            你的任务：
            - 把用户问题改写成更适合知识库检索的简洁中文查询
            - 如果问题中包含明确的业务条件（如年份、版本、部门、文档类型、状态等），
              提取为元数据过滤条件，用于检索前硬性过滤
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
            - 把模糊条件强行变成硬条件

            如果原问题已经清晰、适合检索，直接原样输出。

            输出格式：必须严格输出 JSON，不要输出多余文字。
            {
              "query": "改写后的查询",
              "metadata_filters": [
                {"name": "year", "operator": "is", "value": "2025"}
              ]
            }

            没有可提取条件时，metadata_filters 返回空数组 []。

            示例 1（口语转书面）：
            用户：支付那个老是失败咋整
            输出：{"query":"支付网关调用失败如何排查？","metadata_filters":[]}

            示例 2（带年份条件）：
            用户：2025年支付网关读超时是多少秒？
            输出：{"query":"支付网关读超时设置是多少秒？","metadata_filters":[{"name":"year","operator":"is","value":"2025"}]}

            示例 3（原问题已清晰，无额外条件）：
            用户：支付网关读超时设置是多少秒？
            输出：{"query":"支付网关读超时设置是多少秒？","metadata_filters":[]}
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

    /** 改写结果：查询文本 + 可结构化的元数据过滤条件。 */
    public record RewriteResult(String query, List<MetadataCondition> metadataConditions) {
    }

    /** 元数据过滤条件。operator 使用 Dify 支持的 comparison_operator，如 is / contains / > / <。 */
    public record MetadataCondition(String name, String operator, Object value) {
    }

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
        return rewriteWithMetadata(query).query();
    }

    /**
     * 改写查询并返回可能的结构化元数据过滤条件。
     */
    public RewriteResult rewriteWithMetadata(String query) {
        if (!props.enabled() || query == null || query.isBlank()) {
            return new RewriteResult(query, List.of());
        }

        totalCount.incrementAndGet();
        String normalized = query.trim();
        String cacheKey = cacheKey(normalized);

        var cached = cache.get(cacheKey);
        if (cached.isPresent()) {
            cacheHitCount.incrementAndGet();
            return parseLlmOutput(cached.get(), normalized);
        }

        try {
            String raw = callLlm(normalized);
            RewriteResult result = parseLlmOutput(raw, normalized);
            if (result.query() == null || result.query().isBlank() || result.query().equals(normalized)) {
                noopCount.incrementAndGet();
                successCount.incrementAndGet();
                RewriteResult noop = new RewriteResult(normalized, List.of());
                cache.put(cacheKey, toCacheJson(noop), props.cacheTtlSeconds());
                return noop;
            }
            successCount.incrementAndGet();
            cache.put(cacheKey, toCacheJson(result), props.cacheTtlSeconds());
            return result;
        } catch (Exception e) {
            failureCount.incrementAndGet();
            fallbackCount.incrementAndGet();
            log.warn("query rewrite failed, fallback to original query. query={}, error={}",
                    normalized, e.getMessage());
            return new RewriteResult(normalized, List.of());
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

    private RewriteResult parseLlmOutput(String raw, String fallbackQuery) {
        String text = stripCodeFence(raw);
        if (text == null || text.isBlank()) {
            return new RewriteResult(fallbackQuery, List.of());
        }
        try {
            JsonNode node = objectMapper.readTree(text);
            String query = node.path("query").asText("");
            if (query.isBlank()) {
                query = fallbackQuery;
            }

            List<MetadataCondition> conditions = new ArrayList<>();
            JsonNode filters = node.path("metadata_filters");
            if (filters.isArray()) {
                for (JsonNode condition : filters) {
                    String name = condition.path("name").asText("");
                    String operator = condition.path("operator").asText("");
                    if (operator.isBlank()) {
                        operator = condition.path("comparison_operator").asText("");
                    }
                    JsonNode valueNode = condition.get("value");
                    Object value = null;
                    if (valueNode != null && !valueNode.isNull()) {
                        if (valueNode.isNumber()) {
                            value = valueNode.numberValue();
                        } else if (valueNode.isBoolean()) {
                            value = valueNode.asBoolean();
                        } else {
                            value = valueNode.asText();
                        }
                    }
                    if (!name.isBlank() && !operator.isBlank()) {
                        conditions.add(new MetadataCondition(name, operator, value));
                    }
                }
            }
            return new RewriteResult(query, conditions);
        } catch (Exception e) {
            log.warn("rewrite LLM output is not valid JSON, using raw text as query. output={}", raw);
            return new RewriteResult(text, List.of());
        }
    }

    private String stripCodeFence(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline >= 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
            return trimmed.trim();
        }
        return trimmed;
    }

    private String toCacheJson(RewriteResult result) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", result.query());
            payload.put("metadata_filters", result.metadataConditions());
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            // 缓存写失败不应影响主链路；退化为不缓存
            log.warn("failed to serialize rewrite cache payload", e);
            return "{\"query\":\"\",\"metadata_filters\":[]}";
        }
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
