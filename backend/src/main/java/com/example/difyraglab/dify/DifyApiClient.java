package com.example.difyraglab.dify;

import com.example.difyraglab.config.DifyProperties;
import com.example.difyraglab.dify.dto.DifyRequests.DocumentUploadResult;
import com.example.difyraglab.dify.dto.DifyRequests.RetrieveHit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Dify Service API 客户端（v1）。
 *
 * <p>覆盖知识库生命周期中最常用的 5 个接口：
 * <ul>
 *   <li>POST /v1/datasets                          —— 创建知识库</li>
 *   <li>POST /v1/datasets/{id}/document/create-by-file —— 上传文档（multipart）</li>
 *   <li>GET  /v1/datasets/{id}/documents/{doc}/indexing-status —— 索引状态</li>
 *   <li>POST /v1/datasets/{id}/retrieve            —— 检索分段</li>
 *   <li>POST /v1/chat-messages                     —— 聊天助手问答（App Key）</li>
 * </ul>
 *
 * <p>鉴权说明：知识库类接口用「数据集 API Key」，问答接口用「App API Key」，
 * 二者由调用方传入，互不混用。
 */
public class DifyApiClient {

    private static final Logger log = LoggerFactory.getLogger(DifyApiClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final DifyProperties props;

    public DifyApiClient(RestClient difyRestClient, ObjectMapper objectMapper, DifyProperties props) {
        this.restClient = difyRestClient;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    // ------------------------------------------------------------------
    // 1. 知识库
    // ------------------------------------------------------------------

    /**
     * 创建知识库。返回 Dify 的完整响应（含 id）。
     * 注意：数据集 API Key 是否能建库取决于 Dify 版本/权限，失败时请走 UI 创建。
     */
    public JsonNode createDataset(Map<String, Object> payload, String apiKey) {
        return restClient.post()
                .uri("/v1/datasets")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(JsonNode.class);
    }

    // ------------------------------------------------------------------
    // 2. 文档
    // ------------------------------------------------------------------

    /**
     * 上传文档并触发索引（异步）。
     *
     * @param datasetId 知识库 ID
     * @param fileBytes 文件内容
     * @param filename  文件名（决定格式解析，支持 .md/.txt/.pdf/.docx 等）
     * @param data      multipart 中的 data JSON：indexing_technique / doc_form /
     *                  process_rule / retrieval_model 等（见 docs/02 第 7 节）
     * @param apiKey    数据集 API Key
     */
    public DocumentUploadResult uploadDocument(String datasetId, byte[] fileBytes, String filename,
                                               Map<String, Object> data, String apiKey) {
        ByteArrayResource fileResource = new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", fileResource);
        try {
            form.add("data", objectMapper.writeValueAsString(data));
        } catch (Exception e) {
            throw new IllegalStateException("序列化 data JSON 失败", e);
        }

        JsonNode resp = restClient.post()
                .uri("/v1/datasets/{dataset_id}/document/create-by-file", datasetId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(JsonNode.class);

        if (resp == null) {
            throw new IllegalStateException("create-by-file 返回空响应");
        }
        return new DocumentUploadResult(
                resp.path("document").path("id").asText(),
                resp.path("batch").asText());
    }

    /** 查询文档索引状态。 */
    public JsonNode indexingStatus(String datasetId, String documentId, String apiKey) {
        return restClient.get()
                .uri("/v1/datasets/{dataset_id}/documents/{document_id}/indexing-status",
                        datasetId, documentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .retrieve()
                .body(JsonNode.class);
    }

    /**
     * 轮询等待索引完成。
     *
     * @return 最终状态 JSON（completed / error / paused 等）
     */
    public JsonNode waitForIndexing(String datasetId, String documentId, String apiKey,
                                    long timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            JsonNode status = indexingStatus(datasetId, documentId, apiKey);
            String s = status.path("indexing_status").asText("");
            log.info("document {} indexing_status={}", documentId, s);
            if (!"waiting".equals(s) && !"parsing".equals(s) && !"cleaning".equals(s)
                    && !"splitting".equals(s) && !"indexing".equals(s)) {
                return status;
            }
            Thread.sleep(3000);
        }
        throw new IllegalStateException("索引超时（" + timeoutSeconds + "s）");
    }

    // ------------------------------------------------------------------
    // 3. 检索
    // ------------------------------------------------------------------

    /**
     * 检索知识库分段（POST /datasets/{id}/retrieve）。
     *
     * @param datasetId   知识库 ID
     * @param query       查询文本（≤250 字符）
     * @param searchMethod semantic_search | full_text_search | hybrid_search
     * @param topK        返回条数
     * @param scoreThreshold 相似度阈值（可空 = 不过滤）
     * @param apiKey      数据集 API Key
     */
    public List<RetrieveHit> retrieve(String datasetId, String query, String searchMethod,
                                      int topK, Double scoreThreshold, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "未配置数据集 API Key（环境变量 DIFY_DATASET_API_KEY，或请求体 api_key）。"
                            + "请在 Dify 知识库 → 设置 → API 访问 生成。");
        }
        Map<String, Object> retrievalModel = new LinkedHashMap<>();
        retrievalModel.put("search_method", searchMethod);
        retrievalModel.put("reranking_enable", false); // 1.17 必填字段
        retrievalModel.put("top_k", topK);
        if (scoreThreshold != null) {
            retrievalModel.put("score_threshold_enabled", true);
            retrievalModel.put("score_threshold", scoreThreshold);
        } else {
            retrievalModel.put("score_threshold_enabled", false);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("retrieval_model", retrievalModel);

        JsonNode resp = restClient.post()
                .uri("/v1/datasets/{dataset_id}/retrieve", datasetId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        List<RetrieveHit> hits = new ArrayList<>();
        if (resp != null && resp.path("records").isArray()) {
            for (JsonNode r : resp.path("records")) {
                JsonNode seg = r.path("segment");
                hits.add(new RetrieveHit(
                        seg.path("id").asText(),
                        seg.path("content").asText(),
                        seg.path("document_id").asText(),
                        docName(r, seg),
                        r.path("score").asDouble()));
            }
        }
        return hits;
    }

    // ------------------------------------------------------------------
    // 4. 问答（聊天助手 App）
    // ------------------------------------------------------------------

    /**
     * 调用聊天助手 App（知识库已在 App 中关联）。
     *
     * @param query          用户问题
     * @param appKey         App API Key
     * @param conversationId 会话 ID（续聊时传，新会话传 null）
     */
    public JsonNode chat(String query, String appKey, String conversationId) {
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalArgumentException(
                    "未配置 App API Key（环境变量 DIFY_APP_API_KEY，或请求体 app_key）。"
                            + "请在 Dify 应用 → API 访问 生成。");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("response_mode", "blocking");
        body.put("user", "dify-rag-lab");
        if (conversationId != null && !conversationId.isBlank()) {
            body.put("conversation_id", conversationId);
        }

        JsonNode resp = restClient.post()
                .uri("/v1/chat-messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + appKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        if (resp == null) {
            throw new RestClientException("chat-messages 返回空响应");
        }
        return resp;
    }

    /** 兼容不同版本的响应结构，尽力取到文档名。 */
    private String docName(JsonNode record, JsonNode segment) {
        String name = record.path("document").path("name").asText("");
        if (name.isEmpty()) {
            name = segment.path("document").path("name").asText("");
        }
        return name;
    }

    /** 检查 Dify 是否可达（供健康检查/启动提示）。 */
    public boolean ping() {
        try {
            JsonNode resp = restClient.get().uri("/v1/datasets?page=1&limit=1").retrieve().body(JsonNode.class);
            return resp != null && resp.path("data").isArray();
        } catch (RestClientException e) {
            log.warn("Dify 不可达: {}", e.getMessage());
            return false;
        }
    }
}
