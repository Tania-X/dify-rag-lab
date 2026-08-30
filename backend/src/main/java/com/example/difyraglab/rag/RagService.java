package com.example.difyraglab.rag;

import com.example.difyraglab.config.DifyProperties;
import com.example.difyraglab.config.RewriteProperties;
import com.example.difyraglab.dify.DifyApiClient;
import com.example.difyraglab.dify.dto.DifyRequests.RetrieveHit;
import com.example.difyraglab.embedding.EmbeddingClient;
import com.example.difyraglab.weaviate.WeaviateClient;
import com.example.difyraglab.weaviate.WeaviateClient.SearchHit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * RAG 编排服务。
 *
 * <p>核心价值是 {@link #compare} 对照实验：同一个 query，分别走
 * <b>Dify 检索</b> 与 <b>Java 自实现检索</b>（Embedding → nearVector / BM25 →
 * 加权融合），把向量数据库的"黑盒"变成"白盒"。融合公式与 Dify 的
 * weighted_score 模式一致：
 * <pre>
 *   final_score = w × normalize(vector_score) + (1-w) × normalize(bm25_score)
 * </pre>
 */
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private static final String COLLECTION_PREFIX = "Vector_index";

    private final DifyApiClient difyApiClient;
    private final WeaviateClient weaviateClient;
    private final EmbeddingClient embeddingClient;
    private final DifyProperties props;
    private final RestClient llmRestClient;
    private final RewriteProperties rewriteProps;
    private final ObjectMapper objectMapper;

    public RagService(DifyApiClient difyApiClient, WeaviateClient weaviateClient,
                      EmbeddingClient embeddingClient, DifyProperties props,
                      RestClient llmRestClient, RewriteProperties rewriteProps, ObjectMapper objectMapper) {
        this.difyApiClient = difyApiClient;
        this.weaviateClient = weaviateClient;
        this.embeddingClient = embeddingClient;
        this.props = props;
        this.llmRestClient = llmRestClient;
        this.rewriteProps = rewriteProps;
        this.objectMapper = objectMapper;
    }

    /** 自实现检索的命中项。 */
    public record SelfHit(String id, String text, String source, double score) {
    }

    /** 对照实验结果。 */
    public record CompareResult(
            String collectionName,
            int vectorDim,
            List<RetrieveHit> difyHits,
            List<SelfHit> selfSemanticHits,
            List<SelfHit> selfFullTextHits,
            List<SelfHit> selfHybridHits,
            String note
    ) {
    }

    /** 直查向量库做检索（near_vector / bm25 / 混合）。 */
    public List<SelfHit> searchCollection(String className, String query, String method,
                                          int topK, Double embeddingWeight) {
        if ("bm25".equalsIgnoreCase(method)) {
            return toSelfHits(weaviateClient.bm25Search(className, query, topK), "full_text");
        }
        if ("near_vector".equalsIgnoreCase(method)) {
            return toSelfHits(weaviateClient.nearVectorSearch(className, embeddingClient.embed(query), topK, null),
                    "vector");
        }
        // hybrid
        List<SearchHit> near = weaviateClient.nearVectorSearch(className, embeddingClient.embed(query), topK * 2, null);
        List<SearchHit> bm = weaviateClient.bm25Search(className, query, topK * 2);
        double w = embeddingWeight == null ? 0.7 : embeddingWeight;
        return fuse(near, bm, w, topK);
    }

    /** Dify 检索（透传）。 */
    public List<RetrieveHit> retrieve(String datasetId, String query, String searchMethod,
                                      int topK, Double scoreThreshold, String apiKey) {
        return retrieve(datasetId, query, searchMethod, topK, scoreThreshold, apiKey, null);
    }

    /** Dify 检索（透传），支持元数据过滤条件。 */
    public List<RetrieveHit> retrieve(String datasetId, String query, String searchMethod,
                                      int topK, Double scoreThreshold, String apiKey,
                                      Map<String, Object> metadataFilteringConditions) {
        return difyApiClient.retrieve(datasetId, query, searchMethod, topK, scoreThreshold, apiKey,
                metadataFilteringConditions);
    }

    /** Dify 问答（透传）。 */
    public JsonNode chat(String query, String appKey, String conversationId) {
        return chat(query, appKey, conversationId, null);
    }

    /**
     * 问答。无元数据过滤时走 Dify App；有过滤时先用 Dify 检索出符合过滤条件的上下文，
     * 再调用 LLM 生成回答，保证前端筛选条件真正生效。
     */
    public JsonNode chat(String query, String appKey, String conversationId,
                         Map<String, Object> metadataFilteringConditions) {
        if (metadataFilteringConditions == null || metadataFilteringConditions.isEmpty()) {
            return difyApiClient.chat(query, appKey, conversationId);
        }
        return chatWithRetrievedContext(query, appKey, conversationId, metadataFilteringConditions);
    }

    /**
     * 对照实验：Dify（hybrid） vs Java 自实现（semantic / full_text / hybrid）。
     *
     * @param datasetId 知识库 ID
     * @param query     查询文本
     * @param topK      返回条数
     * @param metadataFilteringConditions Dify 元数据过滤条件，可传 null
     */
    public CompareResult compare(String datasetId, String query, int topK,
                                 Map<String, Object> metadataFilteringConditions) {
        String collection = findCollection(datasetId);
        String apiKey = props.datasetApiKey();
        List<RetrieveHit> difyHits = difyApiClient.retrieve(datasetId, query, "hybrid_search", topK, null, apiKey,
                metadataFilteringConditions);

        List<SelfHit> semantic = new ArrayList<>();
        List<SelfHit> fullText = new ArrayList<>();
        List<SelfHit> hybrid = new ArrayList<>();
        int dim = 0;
        String note;
        try {
            float[] vector = embeddingClient.embed(query);
            dim = vector.length;
            semantic = toSelfHits(
                    weaviateClient.nearVectorSearch(collection, vector, topK, null), "vector");
            fullText = toSelfHits(
                    weaviateClient.bm25Search(collection, query, topK), "full_text");
            hybrid = fuse(
                    weaviateClient.nearVectorSearch(collection, vector, topK * 2, null),
                    weaviateClient.bm25Search(collection, query, topK * 2),
                    0.7, topK);
            String metadataNote = (metadataFilteringConditions == null || metadataFilteringConditions.isEmpty())
                    ? ""
                    : "本次 Dify 侧已应用元数据过滤，自实现检索未应用相同过滤，对比结果包含过滤因素差异。";
            note = "自实现检索使用与 Dify 相同的 Embedding 模型（" + props.embedding().model() + "），"
                    + "score 口径：vector = 1 - cosine_distance；hybrid = 0.7×norm(vector) + 0.3×norm(bm25)。"
                    + "两套结果差异主要来自 Dify 的预处理/参数（如 rerank、元数据过滤）。"
                    + metadataNote;
        } catch (IllegalStateException e) {
            note = "未配置 Embedding 端点（EMBEDDING_BASE_URL），仅展示 Dify 检索结果。";
            log.warn(note);
        }
        return new CompareResult(collection, dim, difyHits, semantic, fullText, hybrid, note);
    }

    // ------------------------------------------------------------------
    // 问答辅助
    // ------------------------------------------------------------------

    private JsonNode chatWithRetrievedContext(String query, String appKey, String conversationId,
                                              Map<String, Object> metadataFilteringConditions) {
        List<RetrieveHit> hits = difyApiClient.retrieve(
                props.datasetId(), query, "hybrid_search", 5, null,
                props.datasetApiKey(), metadataFilteringConditions);

        if (hits.isEmpty()) {
            log.info("metadata-filtered chat got no retrieval hits");
            ObjectNode resp = objectMapper.createObjectNode();
            resp.put("answer", "未检索到符合当前筛选条件的资料，请调整筛选条件后重试。");
            resp.put("conversation_id", conversationId == null ? "" : conversationId);
            resp.put("message_id", UUID.randomUUID().toString());
            resp.putArray("records");
            return resp;
        }

        String context = hits.stream()
                .map(h -> "【文档：" + h.documentName() + "】\n" + h.content())
                .collect(Collectors.joining("\n\n"));

        String answer = callLlm(query, context);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("answer", answer);
        resp.put("conversation_id", conversationId == null ? "" : conversationId);
        resp.put("message_id", UUID.randomUUID().toString());

        ArrayNode records = resp.putArray("records");
        for (RetrieveHit h : hits) {
            ObjectNode record = records.addObject();
            record.put("score", h.score());
            ObjectNode segment = record.putObject("segment");
            segment.put("content", h.content());
            segment.put("document_id", h.documentId());
            ObjectNode document = segment.putObject("document");
            document.put("name", h.documentName());
        }
        return resp;
    }

    private String callLlm(String query, String context) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", rewriteProps.model());
        body.put("temperature", 0.2);
        body.put("messages", List.of(
                Map.of("role", "system", "content",
                        "你是一个金融知识库问答助手。请只根据提供的资料回答，不要编造；如果资料不足，请明确说明。"),
                Map.of("role", "user", "content", "资料：\n" + context + "\n\n问题：" + query)
        ));

        JsonNode resp = llmRestClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (resp == null) {
            throw new IllegalStateException("LLM chat returned null response");
        }
        JsonNode content = resp.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("LLM chat response missing content");
        }
        return content.asText().trim();
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private String findCollection(String datasetId) {
        String derived = derivedCollectionName(datasetId);
        List<String> all = weaviateClient.listCollections();
        if (all.contains(derived)) {
            return derived;
        }
        String sanitized = datasetId.replace("-", "");
        return all.stream()
                .filter(c -> c.contains(sanitized))
                .findFirst()
                .orElse(derived); // 找不到时仍返回推导名，便于报错定位
    }

    /** 与 Dify models/dataset.py::gen_collection_name_by_id 一致。 */
    static String derivedCollectionName(String datasetId) {
        return COLLECTION_PREFIX + "_" + datasetId.replace("-", "_") + "_Node";
    }

    private List<SelfHit> toSelfHits(List<SearchHit> hits, String source) {
        List<SelfHit> out = new ArrayList<>();
        for (SearchHit h : hits) {
            out.add(new SelfHit(h.id(), h.text(), source, h.score()));
        }
        return out;
    }

    /** 加权融合：先各自 min-max 归一化，再按权重合成。 */
    private List<SelfHit> fuse(List<SearchHit> near, List<SearchHit> bm, double w, int topK) {
        Map<String, SearchHit> nearById = new LinkedHashMap<>();
        for (SearchHit h : near) {
            nearById.put(h.id(), h);
        }
        Map<String, SearchHit> bmById = new LinkedHashMap<>();
        for (SearchHit h : bm) {
            bmById.put(h.id(), h);
        }

        Map<String, Double> nearNorm = minMaxNormalize(near, SearchHit::score);
        Map<String, Double> bmNorm = minMaxNormalize(bm, SearchHit::score);

        List<SelfHit> out = new ArrayList<>();
        for (String id : nearById.keySet()) {
            double ns = nearNorm.getOrDefault(id, 0.0);
            double bs = bmNorm.getOrDefault(id, 0.0);
            SearchHit h = nearById.get(id);
            out.add(new SelfHit(id, h.text(), "hybrid", w * ns + (1 - w) * bs));
        }
        // 仅出现在 bm 侧的对象
        for (String id : bmById.keySet()) {
            if (!nearById.containsKey(id)) {
                double bs = bmNorm.getOrDefault(id, 0.0);
                SearchHit h = bmById.get(id);
                out.add(new SelfHit(id, h.text(), "hybrid", (1 - w) * bs));
            }
        }
        out.sort(Comparator.comparingDouble(SelfHit::score).reversed());
        return out.size() > topK ? out.subList(0, topK) : out;
    }

    private Map<String, Double> minMaxNormalize(List<SearchHit> hits, java.util.function.ToDoubleFunction<SearchHit> fn) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (hits.isEmpty()) {
            return out;
        }
        double min = hits.stream().mapToDouble(fn).min().orElse(0);
        double max = hits.stream().mapToDouble(fn).max().orElse(0);
        double range = max - min;
        for (SearchHit h : hits) {
            double v = fn.applyAsDouble(h);
            out.put(h.id(), range > 1e-9 ? (v - min) / range : 0.5);
        }
        return out;
    }
}
