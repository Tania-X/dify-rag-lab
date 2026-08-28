package com.example.difyraglab.web;

import com.example.difyraglab.config.DifyProperties;
import com.example.difyraglab.dify.DifyApiClient;
import com.example.difyraglab.dify.dto.DifyRequests.DocumentUploadResult;
import com.example.difyraglab.dify.dto.DifyRequests.RetrieveHit;
import com.example.difyraglab.rag.RagService;
import com.example.difyraglab.rag.RagService.CompareResult;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;
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

    public RagController(RagService ragService, DifyApiClient difyApiClient, DifyProperties props) {
        this.ragService = ragService;
        this.difyApiClient = difyApiClient;
        this.props = props;
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
            @JsonAlias("api_key") String apiKey
    ) {
    }

    @PostMapping("/retrieve")
    public List<RetrieveHit> retrieve(@Valid @RequestBody RetrieveRequest req) {
        String method = req.searchMethod() == null ? "hybrid_search" : req.searchMethod();
        int topK = req.topK() == null ? 5 : req.topK();
        String datasetId = req.datasetId() == null ? props.datasetId() : req.datasetId();
        String apiKey = req.apiKey() == null ? props.datasetApiKey() : req.apiKey();
        return ragService.retrieve(datasetId, req.query(), method, topK, req.scoreThreshold(), apiKey);
    }

    // ------------------------------------------------------------------
    // 问答
    // ------------------------------------------------------------------

    public record ChatRequest(
            @NotBlank String query,
            @JsonAlias("app_key") String appKey,
            @JsonAlias("conversation_id") String conversationId
    ) {
    }

    @PostMapping("/chat")
    public JsonNode chat(@Valid @RequestBody ChatRequest req) {
        String appKey = req.appKey() == null ? props.appApiKey() : req.appKey();
        return ragService.chat(req.query(), appKey, req.conversationId());
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
            @RequestParam(value = "wait", defaultValue = "true") boolean wait) throws Exception {

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
        return ResponseEntity.ok(ragService.compare(dsId, query, topK));
    }
}
