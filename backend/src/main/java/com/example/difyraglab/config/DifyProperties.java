package com.example.difyraglab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dify 连接配置（application.yml 的 dify.* 前缀）。
 *
 * @param baseUrl         Dify API 入口，如 http://localhost:8080（/v1 由调用方拼接）
 * @param datasetApiKey   数据集 API Key（知识库 → 设置 → API 访问 生成）
 * @param appApiKey       聊天助手 App API Key（应用 → API 访问 生成）
 * @param datasetId       演练知识库 ID
 * @param embedding       Embedding 模型配置（对照实验用，需与 Dify 中配置的一致）
 */
@ConfigurationProperties(prefix = "dify")
public record DifyProperties(
        String baseUrl,
        String datasetApiKey,
        String appApiKey,
        String datasetId,
        Embedding embedding
) {
    public record Embedding(String baseUrl, String apiKey, String model) {
    }
}
