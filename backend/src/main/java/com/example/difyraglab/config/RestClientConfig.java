package com.example.difyraglab.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * HTTP 客户端装配。
 *
 * <p>注意：Dify 客户端不设置默认 Authorization —— 建库/文档/检索用「数据集 API Key」，
 * 问答用「App API Key」，两者不同，须按调用分别传 header（见 DifyApiClient）。
 */
@Configuration
public class RestClientConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public RestClient difyRestClient(DifyProperties props) {
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Bean
    public RestClient weaviateRestClient(WeaviateProperties props) {
        // 实测：这套 Weaviate 1.27 配置下只有 Authorization: Bearer <api-key> 生效，
        // X-API-Key 头会被当作匿名（403）。与 Dify 官方默认配置一致。
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
