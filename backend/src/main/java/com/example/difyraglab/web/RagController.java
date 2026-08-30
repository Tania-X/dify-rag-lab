package com.example.difyraglab.web;

import com.example.difyraglab.config.DifyProperties;
import com.example.difyraglab.dify.DifyApiClient;
import com.example.difyraglab.dify.dto.DifyRequests.DocumentUploadResult;
import com.example.difyraglab.dify.dto.DifyRequests.RetrieveHit;
import com.example.difyraglab.rag.RagService;
import com.example.difyraglab.rag.RagService.CompareResult;
import com.example.difyraglab.rewrite.QueryRewriteService;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 业务接口：检索 / 问答 / 入库 / 对照实验。
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;
    private final DifyApiClient difyApiClient;
    private final DifyProperties props;
    private final QueryRewriteService queryRewriteService;
    private final ObjectMapper objectMapper;

    public RagController(RagService ragService, DifyApiClient difyApiClient, DifyProperties props,
                         QueryRewriteService queryRewriteService, ObjectMapper objectMapper) {
        this.ragService = ragService;
        this.difyApiClient = difyApiClient;
        this.props = props;
        this.queryRewriteService = queryRewriteService;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 元数据
    // ------------------------------------------------------------------

    public record MetadataField(String id, String name, String type, int count) {
    }

    public record MetadataFilterItem(String name, String operator, Object value) {
    }

    public record DocumentMetadataRequest(
            @JsonAlias("document_id") @NotBlank String documentId,
            Map<String, Object> metadata
    ) {
    }

    // ------------------------------------------------------------------
    // 检索
    // ------------------------------------------------------------------

    public record RetrieveRequest(
            @NotBlank @Size(max = 250) String query,
            @JsonAlias("search_method") String searchMethod,
            @JsonAlias("top_k") Integer topK,
            @JsonAlias("score_threshold") Double scoreThreshold,
            @JsonAlias("dataset_id") String datasetId,
            @JsonAlias("api_key") String apiKey,
            @JsonAlias("metadata_filters") List<MetadataFilterItem> metadataFilters
    ) {
    }

    @PostMapping("/retrieve")
    public List<RetrieveHit> retrieve(@Valid @RequestBody RetrieveRequest req) {
        String method = req.searchMethod() == null ? "hybrid_search" : req.searchMethod();
        int topK = req.topK() == null ? 5 : req.topK();
        String datasetId = req.datasetId() == null ? props.datasetId() : req.datasetId();
        String apiKey = req.apiKey() == null ? props.datasetApiKey() : req.apiKey();
        QueryRewriteService.RewriteResult rewrite = queryRewriteService.rewriteWithMetadata(req.query());
        List<MetadataFilterItem> filters = combineMetadataFilters(
                rewrite.metadataConditions(), req.metadataFilters());
        return ragService.retrieve(datasetId, rewrite.query(), method, topK, req.scoreThreshold(), apiKey,
                toMetadataFilteringConditions(filters));
    }

    // ------------------------------------------------------------------
    // 问答
    // ------------------------------------------------------------------

    public record ChatRequest(
            @NotBlank String query,
            @JsonAlias("app_key") String appKey,
            @JsonAlias("conversation_id") String conversationId,
            @JsonAlias("metadata_filters") List<MetadataFilterItem> metadataFilters
    ) {
    }

    @PostMapping("/chat")
    public JsonNode chat(@Valid @RequestBody ChatRequest req) {
        String appKey = req.appKey() == null ? props.appApiKey() : req.appKey();
        QueryRewriteService.RewriteResult rewrite = queryRewriteService.rewriteWithMetadata(req.query());
        List<MetadataFilterItem> filters = combineMetadataFilters(
                rewrite.metadataConditions(), req.metadataFilters());
        return ragService.chat(rewrite.query(), appKey, req.conversationId(),
                toMetadataFilteringConditions(filters));
    }

    // ------------------------------------------------------------------
    // 元数据管理
    // ------------------------------------------------------------------

    @GetMapping("/metadata")
    public List<MetadataField> metadata() {
        String dsId = props.datasetId();
        String key = props.datasetApiKey();
        JsonNode resp = difyApiClient.listMetadataFields(dsId, key);
        List<MetadataField> fields = new ArrayList<>();
        JsonNode docMetadata = resp.path("doc_metadata");
        if (docMetadata.isArray()) {
            for (JsonNode field : docMetadata) {
                fields.add(new MetadataField(
                        field.path("id").asText(),
                        field.path("name").asText(),
                        field.path("type").asText(),
                        field.path("count").asInt(0)));
            }
        }
        return fields;
    }

    @PostMapping("/documents/metadata")
    public Map<String, Object> updateDocumentMetadata(@Valid @RequestBody DocumentMetadataRequest req) throws Exception {
        String dsId = props.datasetId();
        String key = props.datasetApiKey();
        JsonNode metadata = objectMapper.valueToTree(req.metadata());
        applyDocumentMetadata(dsId, req.documentId(), key, metadata);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("document_id", req.documentId());
        resp.put("metadata", req.metadata());
        return resp;
    }

    // ------------------------------------------------------------------
    // 入库（上传文档并等待索引完成）
    // ------------------------------------------------------------------

    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> ingest(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "dataset_id", required = false) String datasetId,
            @RequestParam(value = "doc_form", required = false) String docForm,
            @RequestParam(value = "api_key", required = false) String apiKey,
            @RequestParam(value = "wait", defaultValue = "true") boolean wait,
            @RequestParam(value = "metadata", required = false) String metadataJson) throws Exception {

        String dsId = datasetId == null ? props.datasetId() : datasetId;
        String key = apiKey == null ? props.datasetApiKey() : apiKey;

        // data JSON：与 Dify create-by-file 契约一致（docs/02 第 7 节）
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("indexing_technique", "high_quality");
        data.put("doc_form", docForm == null ? "text_model" : docForm);
        data.put("doc_language", "Chinese");

        Map<String, Object> processRule = new LinkedHashMap<>();
        processRule.put("mode", "custom");
        Map<String, Object> rules = new LinkedHashMap<>();
        Map<String, Object> segmentation = new LinkedHashMap<>();
        segmentation.put("separator", "\n");
        segmentation.put("max_tokens", 500);
        segmentation.put("chunk_overlap", 50);
        rules.put("segmentation", segmentation);
        processRule.put("rules", rules);
        data.put("process_rule", processRule);

        DocumentUploadResult result = difyApiClient.uploadDocument(
                dsId, file.getBytes(), file.getOriginalFilename(), data, key);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("document_id", result.documentId());
        resp.put("batch", result.batch());
        if (wait) {
            JsonNode status = difyApiClient.waitForIndexing(dsId, result.documentId(), key, 180);
            resp.put("indexing_status", status.path("indexing_status").asText());
            resp.put("indexing_status_detail", status);
        }

        if (metadataJson != null && !metadataJson.isBlank()) {
            JsonNode metadata = objectMapper.readTree(metadataJson);
            if (!metadata.isObject()) {
                throw new IllegalArgumentException("metadata must be a JSON object");
            }
            applyDocumentMetadata(dsId, result.documentId(), key, metadata);
            resp.put("metadata", metadata);
        }

        return resp;
    }

    // ------------------------------------------------------------------
    // 对照实验
    // ------------------------------------------------------------------

    @GetMapping("/compare")
    public ResponseEntity<CompareResult> compare(
            @RequestParam @NotBlank String query,
            @RequestParam(value = "top_k", defaultValue = "5") int topK,
            @RequestParam(value = "dataset_id", required = false) String datasetId) {
        String dsId = datasetId == null ? props.datasetId() : datasetId;
        if (dsId == null || dsId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        QueryRewriteService.RewriteResult rewrite = queryRewriteService.rewriteWithMetadata(query);
        List<MetadataFilterItem> filters = combineMetadataFilters(rewrite.metadataConditions(), null);
        return ResponseEntity.ok(ragService.compare(dsId, rewrite.query(), topK,
                toMetadataFilteringConditions(filters)));
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    /** 合并 QueryRewrite 自动提取的条件和前端显式选择的过滤条件（AND 关系）。 */
    private List<MetadataFilterItem> combineMetadataFilters(
            List<QueryRewriteService.MetadataCondition> rewriteConditions,
            List<MetadataFilterItem> requestConditions) {
        List<MetadataFilterItem> filters = new ArrayList<>();
        if (rewriteConditions != null) {
            for (QueryRewriteService.MetadataCondition c : rewriteConditions) {
                filters.add(new MetadataFilterItem(c.name(), c.operator(), c.value()));
            }
        }
        if (requestConditions != null) {
            filters.addAll(requestConditions);
        }
        return filters;
    }

    /** 把过滤条件转成 Dify retrieval_model.metadata_filtering_conditions。 */
    private Map<String, Object> toMetadataFilteringConditions(List<MetadataFilterItem> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> conditionList = new ArrayList<>();
        for (MetadataFilterItem c : conditions) {
            if (c.name() == null || c.name().isBlank() || c.operator() == null || c.operator().isBlank()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", c.name());
            item.put("comparison_operator", c.operator());
            item.put("value", c.value());
            conditionList.add(item);
        }
        if (conditionList.isEmpty()) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("logical_operator", "and");
        body.put("conditions", conditionList);
        return body;
    }

    /** 文档入库后，把 metadata JSON 对象写入 Dify 文档元数据。 */
    private void applyDocumentMetadata(String datasetId, String documentId, String apiKey,
                                       JsonNode metadata) throws Exception {
        Map<String, String> fieldIds = new LinkedHashMap<>();
        JsonNode fieldsResp = difyApiClient.listMetadataFields(datasetId, apiKey);
        JsonNode docMetadata = fieldsResp.path("doc_metadata");
        if (docMetadata.isArray()) {
            for (JsonNode field : docMetadata) {
                String id = field.path("id").asText("");
                String name = field.path("name").asText("");
                if (!id.isBlank() && !name.isBlank()) {
                    fieldIds.put(name, id);
                }
            }
        }

        List<Map<String, Object>> metadataList = new ArrayList<>();
        var names = metadata.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            JsonNode valueNode = metadata.get(name);
            String id = fieldIds.get(name);
            if (id == null) {
                String type = valueNode.isNumber() ? "number" : "string";
                JsonNode created = difyApiClient.createMetadataField(datasetId, apiKey, name, type);
                id = created.path("id").asText("");
                if (id.isBlank()) {
                    // 部分 Dify 响应直接返回 id，而不是嵌套；再尝试从 doc_metadata 列表刷新
                    JsonNode refreshed = difyApiClient.listMetadataFields(datasetId, apiKey);
                    for (JsonNode field : refreshed.path("doc_metadata")) {
                        if (name.equals(field.path("name").asText(""))) {
                            id = field.path("id").asText("");
                            break;
                        }
                    }
                }
                if (id.isBlank()) {
                    throw new IllegalStateException("Failed to create metadata field: " + name);
                }
                fieldIds.put(name, id);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("name", name);
            if (valueNode != null && valueNode.isNumber()) {
                item.put("value", valueNode.numberValue());
            } else if (valueNode != null && !valueNode.isNull()) {
                // Dify 元数据字段类型仅支持 string/number/time，布尔值统一存成字符串
                item.put("value", valueNode.asText());
            } else {
                item.put("value", null);
            }
            metadataList.add(item);
        }

        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("document_id", documentId);
        operation.put("metadata_list", metadataList);
        operation.put("partial_update", true);
        List<Map<String, Object>> operationData = new ArrayList<>();
        operationData.add(operation);
        difyApiClient.updateDocumentsMetadata(datasetId, apiKey, operationData);
    }
}
