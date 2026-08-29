package com.example.difyraglab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Query Rewrite 配置。
 *
 * <p>用于在调用 Dify 检索/问答前，用 LLM 对用户问题进行合法改写（语言规范化、同义改写、
 * 补全省略、术语统一），禁止加入用户问题中没有的信息。
 *
 * @param enabled        是否启用 Query Rewrite
 * @param baseUrl        OpenAI 兼容 Chat Completions 端点，如 https://api.siliconflow.cn/v1
 * @param apiKey         LLM API Key
 * @param model          模型名，如 deepseek-ai/DeepSeek-V3
 * @param temperature    采样温度，改写建议 0
 * @param timeoutSeconds LLM 调用超时
 * @param promptVersion  Prompt 版本，缓存 key 的一部分；升级 Prompt 后应修改
 * @param cacheTtlSeconds 改写结果缓存 TTL
 */
@ConfigurationProperties(prefix = "rewrite")
public record RewriteProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String model,
        double temperature,
        int timeoutSeconds,
        String promptVersion,
        long cacheTtlSeconds
) {
}
