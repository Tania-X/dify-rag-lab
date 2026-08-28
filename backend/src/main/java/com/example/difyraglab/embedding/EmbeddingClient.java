package com.example.difyraglab.embedding;

import com.example.difyraglab.config.DifyProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 Embedding 客户端。
 *
 * <p>用途：自实现向量检索的"向量来源"。Dify 内部也是调同一个模型生成向量后
 * 写入 Weaviate（self_provided），因此本客户端必须配置<b>与 Dify 知识库相同的
 * Embedding 模型</b>，对照实验才有意义。
 *
 * <p>端点约定：POST {base}/embeddings（OpenAI 兼容协议）。
 *
 * <p>配置策略：<b>惰性校验</b>——未配置 EMBEDDING_BASE_URL 时应用照常启动，
 * 只有真正调用向量检索（near_vector / hybrid / 对照实验）时才抛出明确报错，
 * 保证"空配置冒烟"可用。
 */
public class EmbeddingClient {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper;
    private RestClient restClient;

    public EmbeddingClient(DifyProperties props, ObjectMapper objectMapper) {
        this.baseUrl = props.embedding().baseUrl() == null ? "" : props.embedding().baseUrl().trim();
        this.apiKey = props.embedding().apiKey();
        this.model = props.embedding().model();
        this.objectMapper = objectMapper;
    }

    /** 对单条文本生成向量。 */
    public float[] embed(String text) {
        List<float[]> result = embedBatch(List.of(text));
        return result.isEmpty() ? new float[0] : result.get(0);
    }

    /** 批量生成向量，返回顺序与输入一致。 */
    public List<float[]> embedBatch(List<String> texts) {
        RestClient client = client();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", texts);

        JsonNode resp = client.post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        List<float[]> vectors = new ArrayList<>();
        if (resp != null && resp.path("data").isArray()) {
            for (JsonNode item : resp.path("data")) {
                List<Double> raw = objectMapper.convertValue(item.path("embedding"),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Double.class));
                float[] v = new float[raw.size()];
                for (int i = 0; i < raw.size(); i++) {
                    v[i] = raw.get(i).floatValue();
                }
                vectors.add(v);
            }
        }
        return vectors;
    }

    /** 惰性初始化：仅在使用时校验并构建 HTTP 客户端。 */
    private RestClient client() {
        if (baseUrl.isEmpty()) {
            throw new IllegalStateException(
                    "未配置 dify.embedding.base-url（环境变量 EMBEDDING_BASE_URL）。"
                            + "对照实验需要与 Dify 相同的 Embedding 模型端点，"
                            + "请参考 docs/03 阶段 4 配置。");
        }
        if (restClient == null) {
            RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
            if (apiKey != null && !apiKey.isBlank()) {
                builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            }
            restClient = builder.build();
        }
        return restClient;
    }
}
