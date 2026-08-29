package com.example.difyraglab.config;

import com.example.difyraglab.dify.DifyApiClient;
import com.example.difyraglab.embedding.EmbeddingClient;
import com.example.difyraglab.rag.RagService;
import com.example.difyraglab.rewrite.InMemoryQueryRewriteCache;
import com.example.difyraglab.rewrite.QueryRewriteCache;
import com.example.difyraglab.rewrite.QueryRewriteService;
import com.example.difyraglab.weaviate.WeaviateClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** 业务 Bean 装配。 */
@Configuration
public class AppBeansConfig {

    @Bean
    public DifyApiClient difyApiClient(RestClient difyRestClient, ObjectMapper objectMapper,
                                       DifyProperties props) {
        return new DifyApiClient(difyRestClient, objectMapper, props);
    }

    @Bean
    public WeaviateClient weaviateClient(RestClient weaviateRestClient, ObjectMapper objectMapper) {
        return new WeaviateClient(weaviateRestClient, objectMapper);
    }

    @Bean
    public EmbeddingClient embeddingClient(DifyProperties props, ObjectMapper objectMapper) {
        return new EmbeddingClient(props, objectMapper);
    }

    @Bean
    public QueryRewriteCache queryRewriteCache() {
        return new InMemoryQueryRewriteCache();
    }

    @Bean
    public QueryRewriteService queryRewriteService(RewriteProperties props, RestClient rewriteRestClient,
                                                   ObjectMapper objectMapper, QueryRewriteCache queryRewriteCache) {
        return new QueryRewriteService(props, rewriteRestClient, objectMapper, queryRewriteCache);
    }

    @Bean
    public RagService ragService(DifyApiClient difyApiClient, WeaviateClient weaviateClient,
                                 EmbeddingClient embeddingClient, DifyProperties props) {
        return new RagService(difyApiClient, weaviateClient, embeddingClient, props);
    }
}
