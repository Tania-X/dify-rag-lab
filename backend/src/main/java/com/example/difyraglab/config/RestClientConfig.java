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
        // Weaviate API Key 支持 X-API-Key 与 Authorization: Bearer 两种方式
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader("X-API-Key", props.apiKey())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
