package com.example.difyraglab.dify.dto;

/**
 * Dify Service API 请求/响应 DTO（字段名与 Dify 1.17 源码对齐）。
 *
 * <p>参考源码：controllers/service_api/dataset/dataset.py（DatasetCreatePayload）、
 * hit_testing.py（HitTestingPayload）、services/entities/knowledge_entities/
 * knowledge_entities.py（RetrievalModel / RerankingModel / WeightModel）。
 */
public final class DifyRequests {

    private DifyRequests() {
    }

    /**
     * 创建知识库。
     *
     * @param name                 知识库名称（1-40 字符）
     * @param description          描述
     * @param indexingTechnique    high_quality（向量索引）/ economy（关键词索引）
     * @param permission           only_me / all_team_members / partial_members
     * @param embeddingModel       Embedding 模型名（模型供应商中配置的 model）
     * @param embeddingModelProvider Embedding 模型供应商
     */
    public record DatasetCreateReq(
            String name,
            String description,
            String indexingTechnique,
            String permission,
            String embeddingModel,
            String embeddingModelProvider
    ) {
    }

    /**
     * 检索模型配置（POST /datasets/{id}/retrieve 的 retrieval_model 字段）。
     * 与 Dify 1.17 的 RetrievalModel 一一对应。
     */
    public record RetrievalModelReq(
            String searchMethod,          // semantic_search | full_text_search | hybrid_search
            boolean rerankingEnable,
            String rerankingMode,         // reranking_model | weighted_score
            String rerankingProviderName, // reranking_model 模式下必填
            String rerankingModelName,
            int topK,
            boolean scoreThresholdEnabled,
            Double scoreThreshold,
            Double weights               // weighted_score 模式下语义权重 (0,1)
    ) {
    }

    /**
     * 检索请求体。
     */
    public record RetrieveReq(String query, RetrievalModelReq retrievalModel) {
    }

    /**
     * 问答请求体（POST /chat-messages，走 App API Key）。
     */
    public record ChatReq(String query, String appKey, String conversationId) {
    }

    /**
     * 检索命中的 chunk（records[] 中提取）。
     */
    public record RetrieveHit(
            String segmentId,
            String content,
            String documentId,
            String documentName,
            double score
    ) {
    }

    /**
     * 文档上传结果（create-by-file 响应）。
     */
    public record DocumentUploadResult(String documentId, String batch) {
    }
}
