import axios from 'axios';
import type {
  ChatRequest,
  ChatResponse,
  CollectionInfo,
  RetrieveHit,
  RetrieveRequest,
  SelfHit,
  VdbSearchRequest,
  WeaviateObject,
} from '../types';

const client = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 120_000, // 问答/入库可能较慢
});

/** 统一的错误文案提取（axios 优先取后端 {message}） */
export function errorText(e: unknown): string {
  if (axios.isAxiosError(e)) {
    const msg = (e.response?.data as { message?: string } | undefined)?.message;
    return msg || e.message;
  }
  return e instanceof Error ? e.message : String(e);
}

// ---------------- RAG ----------------

export async function retrieve(payload: RetrieveRequest): Promise<RetrieveHit[]> {
  const { data } = await client.post<RetrieveHit[]>('/rag/retrieve', payload);
  return data;
}

export async function chat(payload: ChatRequest): Promise<ChatResponse> {
  const { data } = await client.post<ChatResponse>('/rag/chat', payload);
  return data;
}

// ---------------- 向量库直查 ----------------

export async function listCollections(): Promise<CollectionInfo[]> {
  const { data } = await client.get<CollectionInfo[]>('/vdb/collections');
  return data;
}

export async function listObjects(className: string, limit = 5): Promise<WeaviateObject[]> {
  const { data } = await client.get<WeaviateObject[]>(`/vdb/collections/${encodeURIComponent(className)}/objects`, {
    params: { limit },
  });
  return data;
}

export async function vdbSearch(className: string, payload: VdbSearchRequest): Promise<SelfHit[]> {
  const { data } = await client.post<SelfHit[]>(
    `/vdb/collections/${encodeURIComponent(className)}/search`,
    payload,
  );
  return data;
}
