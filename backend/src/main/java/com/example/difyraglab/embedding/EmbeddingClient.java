package com.example.difyraglab.embedding;

import com.example.difyraglab.config.DifyProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 */
public class EmbeddingClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;

    public EmbeddingClient(DifyProperties props, ObjectMapper objectMapper) {
        String baseUrl = props.embedding().baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "未配置 dify.embedding.base-url（环境变量 EMBEDDING_BASE_URL）。"
                            + "对照实验需要与 Dify 相同的 Embedding 模型端点，"
                            + "请参考 docs/03 阶段 4 配置。");
        }
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.model = props.embedding().model();
    }

    /** 对单条文本生成向量。 */
    public float[] embed(String text) {
        List<float[]> result = embedBatch(List.of(text));
        return result.isEmpty() ? new float[0] : result.get(0);
    }

    /** 批量生成向量，返回顺序与输入一致。 */
    public List<float[]> embedBatch(List<String> texts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", texts);

        JsonNode resp = restClient.post()
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
}
