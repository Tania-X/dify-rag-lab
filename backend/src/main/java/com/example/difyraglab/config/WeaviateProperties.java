package com.example.difyraglab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Weaviate 连接配置（application.yml 的 weaviate.* 前缀）。
 *
 * @param baseUrl 宿主可达地址：脚本 01 发布的 http://localhost:8090
 * @param apiKey  Weaviate API Key（你部署中为 Dify 官方默认值）
 */
@ConfigurationProperties(prefix = "weaviate")
public record WeaviateProperties(String baseUrl, String apiKey) {
}
