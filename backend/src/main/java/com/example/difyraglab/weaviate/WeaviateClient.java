package com.example.difyraglab.weaviate;

import com.example.difyraglab.config.WeaviateProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Weaviate REST 直查客户端 —— 学习"Dify 到底在向量库里存了什么"。
 *
 * <p>覆盖三类操作（全部走 REST，无需引入 weaviate-java 客户端，利于理解协议）：
 * <ul>
 *   <li>schema：GET /v1/schema（collection 列表与属性）</li>
 *   <li>对象：GET 查询 / GraphQL Get（看 chunk 正文与元数据）</li>
 *   <li>检索：GraphQL nearVector（语义）/ bm25（全文）</li>
 * </ul>
 *
 * <p>对照 docs/02：Dify 的 collection 命名 = Vector_index_{dataset_id}_Node，
 * 向量由 Dify 自备（self_provided，向量名 "default"），score = 1 - distance。
 */
public class WeaviateClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public WeaviateClient(RestClient weaviateRestClient, ObjectMapper objectMapper) {
        this.restClient = weaviateRestClient;
        this.objectMapper = objectMapper;
    }

    /** 命中结果（nearVector / bm25 统一结构）。 */
    public record SearchHit(String id, String text, double score, Map<String, Object> properties) {
    }

    /** 向量库中的一个对象（chunk）。 */
    public record WeaviateObject(String id, Map<String, Object> properties) {
    }

    // ------------------------------------------------------------------
    // schema
    // ------------------------------------------------------------------

    /** 列出全部 collection（class）名称。 */
    public List<String> listCollections() {
        JsonNode resp = restClient.get()
                .uri("/v1/schema")
                .retrieve()
                .body(JsonNode.class);
        List<String> names = new ArrayList<>();
        if (resp != null && resp.path("classes").isArray()) {
            for (JsonNode c : resp.path("classes")) {
                names.add(c.path("class").asText());
            }
        }
        return names;
    }

    /** 查看某个 collection 的完整 schema（属性、向量配置）。 */
    public JsonNode collectionSchema(String className) {
        return restClient.get()
                .uri("/v1/schema/{class}", className)
                .retrieve()
                .body(JsonNode.class);
    }

    // ------------------------------------------------------------------
    // 对象
    // ------------------------------------------------------------------

    /** 统计 collection 中的对象数（分段数）。 */
    public long countObjects(String className) {
        String query = String.format("{ Aggregate { %s { meta { count } } } }", className);
        JsonNode resp = graphql(query);
        return resp.path("data").path("Aggregate").path(className).path(0).path("meta").path("count").asLong(0);
    }

    /** 拉取前 limit 个对象（不含向量，看正文与元数据）。 */
    public List<WeaviateObject> listObjects(String className, int limit) {
        String query = String.format(
                "{ Get { %s(limit: %d) { text doc_id document_id chunk_index is_summary _additional { id } } } }",
                className, limit);
        return parseGetObjects(graphql(query), className);
    }

    // ------------------------------------------------------------------
    // 检索
    // ------------------------------------------------------------------

    /**
     * 语义检索：nearVector（对应 Dify 的 semantic_search）。
     * score = 1 - distance（与 Dify weaviate_vector.py 完全一致）。
     *
     * @param maxDistance 可选：最大余弦距离（等价于 score >= 1 - maxDistance）
     */
    public List<SearchHit> nearVectorSearch(String className, float[] vector, int topK, Double maxDistance) {
        String vectorJson = toJsonArray(vector);
        String distanceFilter = maxDistance == null ? "" : ", distance: " + maxDistance;
        String query = String.format(
                "{ Get { %s(limit: %d, nearVector: { vector: %s%s }) "
                        + "{ text doc_id document_id chunk_index is_summary _additional { id distance } } } }",
                className, topK, vectorJson, distanceFilter);
        JsonNode resp = graphql(query);
        List<SearchHit> hits = new ArrayList<>();
        for (JsonNode obj : resp.path("data").path("Get").path(className)) {
            double distance = obj.path("_additional").path("distance").asDouble(1.0);
            hits.add(new SearchHit(
                    obj.path("_additional").path("id").asText(),
                    obj.path("text").asText(""),
                    1.0 - distance,
                    flatten(obj)));
        }
        return hits;
    }

    /** 全文检索：bm25（对应 Dify 的 full_text_search）。score 为 BM25 原始分。 */
    public List<SearchHit> bm25Search(String className, String queryText, int topK) {
        String query = String.format(
                "{ Get { %s(limit: %d, bm25: { query: \"%s\" }) "
                        + "{ text doc_id document_id chunk_index is_summary _additional { id score } } } }",
                className, topK, escapeGraphQl(queryText));
        JsonNode resp = graphql(query);
        List<SearchHit> hits = new ArrayList<>();
        for (JsonNode obj : resp.path("data").path("Get").path(className)) {
            hits.add(new SearchHit(
                    obj.path("_additional").path("id").asText(),
                    obj.path("text").asText(""),
                    obj.path("_additional").path("score").asDouble(0.0),
                    flatten(obj)));
        }
        return hits;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private JsonNode graphql(String query) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        return restClient.post()
                .uri("/v1/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    private List<WeaviateObject> parseGetObjects(JsonNode resp, String className) {
        List<WeaviateObject> out = new ArrayList<>();
        JsonNode arr = resp.path("data").path("Get").path(className);
        if (arr.isArray()) {
            for (JsonNode obj : arr) {
                out.add(new WeaviateObject(
                        obj.path("_additional").path("id").asText(),
                        flatten(obj)));
            }
        }
        return out;
    }

    /** 把 GraphQL 返回的对象拍平成 Map（排除 _additional）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> flatten(JsonNode obj) {
        Map<String, Object> map = objectMapper.convertValue(obj, Map.class);
        map.remove("_additional");
        return map;
    }

    private String toJsonArray(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private String escapeGraphQl(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
