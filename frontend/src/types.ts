// 与 backend 的 DTO 对齐（com.example.difyraglab.*）

/** 检索命中的 chunk（/api/rag/retrieve 返回） */
export interface RetrieveHit {
  segmentId: string;
  content: string;
  documentId: string;
  documentName: string;
  score: number;
}

export interface RetrieveRequest {
  query: string;
  searchMethod?: string; // semantic_search | full_text_search | hybrid_search
  topK?: number;
  scoreThreshold?: number | null;
  datasetId?: string;
  apiKey?: string;
}

export interface ChatRequest {
  query: string;
  appKey?: string;
  conversationId?: string;
}

/** Dify chat-messages 响应（blocking），records 为知识库引用（存在时） */
export interface ChatResponse {
  answer: string;
  conversation_id: string;
  message_id: string;
  records?: Array<{
    score?: number;
    segment?: {
      content?: string;
      document_id?: string;
      document?: { name?: string };
    };
  }>;
  [key: string]: unknown;
}

/** Weaviate collection 概览 */
export interface CollectionInfo {
  class: string;
  objectCount: number;
}

/** 自实现检索命中（/api/vdb/collections/{name}/search 返回） */
export interface SelfHit {
  id: string;
  text: string;
  source: string; // vector | full_text | hybrid
  score: number;
}

export interface VdbSearchRequest {
  query: string;
  method?: string; // near_vector | bm25 | hybrid
  topK?: number;
  embeddingWeight?: number | null;
}

/** Weaviate 对象（chunk） */
export interface WeaviateObject {
  id: string;
  properties: Record<string, unknown>;
}
