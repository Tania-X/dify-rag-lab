# 02 · Dify 向量数据库机制详解（源码级）

> 本章所有结论均取自你本机正在运行的 **Dify 1.17.0** 容器内源码（`docker-api-1`）与 **Weaviate 1.27.0** 容器配置，逐条与你部署对应。读完你就能"白盒"地讲清楚 Dify 怎么用向量库。

## 1. 你的部署快照（已实测）

| 项 | 值 |
|---|---|
| Dify 镜像 | `langgenius/dify-api:1.17.0`（api / worker / web 三容器） |
| 向量库 | Weaviate `1.27.0`，容器 `docker-weaviate-1`，网络内地址 `http://weaviate:8080`（gRPC `50051`） |
| 向量库认证 | `AUTHENTICATION_ANONYMOUS_ACCESS_ENABLED=true` + API Key（Dify 官方 compose 默认值，见部署 `.env`，仅内网可达） |
| 向量库端口 | 未映射到宿主机 → 宿主直查需走临时代理（演练 01 号脚本） |
| 关键环境变量 | `VECTOR_STORE=weaviate`、`WEAVIATE_ENDPOINT`、`WEAVIATE_GRPC_ENDPOINT`、`WEAVIATE_API_KEY`、`WEAVIATE_BATCH_SIZE`(默认100)、`WEAVIATE_TOKENIZATION`(默认word) |
| 元数据库 | PostgreSQL 15（`docker-db_postgres-1`，Dify 元数据） |
| 队列/缓存 | Redis 6（`docker-redis-1`） |
| 对外入口 | nginx `8080`(HTTP)/`443`(HTTPS)，API 前缀 `/v1` |

## 2. 分层：Dify 的"两个库"各存什么

```
┌─────────────────────────── Dify API (1.17) ───────────────────────────┐
│  数据集(datasets) 文档(documents) 分段(segments) 索引状态 命中记录      │
│  这些表的元数据 → PostgreSQL                                          │
│                                                                       │
│  切分后的 chunk 向量 + chunk 正文 + 检索属性 → Weaviate               │
└───────────────────────────────────────────────────────────────────────┘
```

- **PostgreSQL**：`datasets` / `documents` / `segments` / `dataset_process_rules` 等。记录"有哪些文档、切了几段、每段内容、索引状态、index_struct（collection 名）"。
- **Weaviate**：真正干活的向量库。每个知识库一个 collection，存**切分后的 chunk**（正文 + 向量 + 元数据），不存原始文件。
- **分工本质**：PostgreSQL 是"目录"，Weaviate 是"仓库"。检索时先（或同时）查 Weaviate 拿命中的 chunk，再从 PostgreSQL 补文档信息。

## 3. Dify 1.17 的向量库插件化架构

1.17 起，向量库后端从"内置模块"重构为**独立包 + 入口点注册**（源码：`api/core/rag/datasource/vdb/vector_backend_registry.py`）：

```
dify.vector_backends entry point (importlib.metadata)
        │ 按 VECTOR_STORE 值查找
        ▼
dify_vdb_weaviate 包  (容器内: /app/api/providers/vdb/vdb-weaviate/src/dify_vdb_weaviate/weaviate_vector.py)
        │ 实现 AbstractVectorFactory
        ▼
WeaviateVectorFactory.init_vector(dataset, attributes, embeddings)
        → WeaviateVector(collection_name, WeaviateConfig, attributes)
```

- 支持的引擎枚举：`VectorType`（weaviate / qdrant / milvus / pgvector / chroma / opensearch / elasticsearch / oracle / tencent / ...）。
- 容器内已安装 `dify_vdb_*` 全家桶（`.venv/lib/python3.12/site-packages/dify_vdb_weaviate/` 等），所以切换 `VECTOR_STORE` 只需改 `.env` 并重启 api/worker。

## 4. Collection 命名与 Schema（核心知识点）

### 4.1 命名规则

源码 `api/models/dataset.py`：

```python
@staticmethod
def gen_collection_name_by_id(dataset_id: str) -> str:
    normalized_dataset_id = dataset_id.replace("-", "_")
    return f"{dify_config.VECTOR_INDEX_NAME_PREFIX}_{normalized_dataset_id}_Node"
```

- 前缀默认 `Vector_index`（env `VECTOR_INDEX_NAME_PREFIX`，`configs/middleware/__init__.py`）。
- 结论：**一个知识库 = Weaviate 里的一个 Class**，名如：

```
Vector_index_8f3a2b1c_9d8e_4f7a_9b2c_1d2e3f4a5b6c_Node
（dataset_id 的 "-" 全部替换为 "_"，尾缀 _Node）
```

> 你可以在 Weaviate 里用 `GET /v1/schema` 直接看到它（演练 05 号脚本）。`_Node` 尾缀是历史沿袭（早期基于 LlamaIndex），1.17 仍保留。

### 4.2 属性（Property）设计

创建 collection 时（`weaviate_vector.py::_create_collection`）定义：

| 属性 | 类型 | 含义 |
|---|---|---|
| `text` | TEXT（tokenization=word） | **chunk 正文**，全文检索（BM25）的检索对象 |
| `document_id` | TEXT | 所属文档 ID（批量删除/过滤用） |
| `doc_id` | TEXT | 文档级索引 ID（`text_exists` 幂等检查用） |
| `doc_type` | TEXT | 文档类型 |
| `chunk_index` | INT | 该 chunk 在文档内的序号 |
| `is_summary` | BOOL | 是否为摘要分段（doc_form=qa/hierarchical 相关） |
| `original_chunk_id` | TEXT | 父 chunk ID（父子切分） |

**外加：动态元数据**。写入时 `Document.metadata` 里的键会**直接成为 property**（`_json_serializable` 处理 datetime 等），实际入库时通常还会带上 `dataset_id`、`document_id`、`doc_id`、`doc_hash` 等。因此你直查时会看到比上表更多的属性——这正是 Dify 做 `metadata_filtering_conditions`（按元数据过滤检索）的物理基础。

### 4.3 向量配置

```python
vector_config=wc.Configure.Vectors.self_provided()   # 向量名 "default"
```

- Weaviate 容器环境 `DEFAULT_VECTORIZER_MODULE=none` —— **Weaviate 不自己算向量**。
- 向量由 Dify 调用 Embedding 模型生成后随对象写入（`vector={"default": [0.01, ...]}`）。

## 5. 入库（索引）链路源码拆解

路径：`POST /v1/datasets/{id}/document/create-by-file` → worker 异步执行 `DocumentService.save_document_with_dataset_id` → `IndexProcessor` 切分 → `WeaviateVector.add_texts`。

关键点（对应源码行）：

1. **切分**（`core/rag/entities/processing_entities.py`）：
   - `ProcessRule.mode`：`automatic`（内置规则）/ `custom`（自定义）/ `hierarchical`（父子切分）。
   - `Segmentation`：`separator`（默认 `\n`）、`max_tokens`（每段最大 token）、`chunk_overlap`（重叠 token）。
   - `pre_processing_rules`：`remove_stopwords` / `remove_extra_spaces` / `remove_urls_emails`。
   - 父子切分：`parent_mode`（`full-doc`/`paragraph`）+ `subchunk_segmentation`（子段规则），对应 `doc_form: hierarchical_model`。

2. **确定性 UUID**（`weaviate_vector.py::_get_uuids`）：
   ```python
   uuid5(UUID("6ba7b811-9dad-11d1-80b4-00c04fd430c8"), doc.page_content)
   ```
   同一段内容永远生成同一 UUID → 重复入库天然去重、更新可精确删除。

3. **建 collection 的并发控制**（`_create_collection`）：
   - Redis 锁 `vector_indexing_lock_{collection}`（timeout 20s）+ 缓存标记 `vector_indexing_{collection}`（1h）；
   - collection 已存在则 `_ensure_properties()` 补齐缺失属性（兼容旧数据）。

4. **批量写入**（`add_texts`）：
   ```python
   with col.batch.dynamic() as batch:   # WeaviateConfig.batch_size 默认 100
       for obj in objs: batch.add_object(properties=..., uuid=..., vector=...)
   ```
   每个对象 = `{uuid, properties(含 text+元数据), vector: {"default": [...]}}`。

## 6. 检索链路源码拆解

### 6.1 三种检索方法（`core/rag/retrieval/retrieval_methods.py`）

| search_method | 底层调用 | 语义 |
|---|---|---|
| `semantic_search` | Weaviate `near_vector`（`target_vector="default"`） | 向量最近邻 |
| `full_text_search` | Weaviate `bm25`（检索 `text` 属性） | 关键词 BM25 |
| `hybrid_search` | 两路召回 → 权重融合或 Rerank 模型 | 语义+关键词 |

### 6.2 向量检索的打分（`weaviate_vector.py::search_by_vector`）

```python
res = col.query.near_vector(near_vector=query_vector, limit=top_k,
                            return_metadata=MetadataQuery(distance=True),
                            filters=where, target_vector="default")
...
score = 1.0 - distance          # distance 为余弦距离
if score > score_threshold:     # 阈值过滤（score_threshold 默认 0）
    properties["score"] = score
docs.sort(key=lambda d: d.metadata.get("score", 0.0), reverse=True)   # 降序
```

- **score = 1 − 余弦距离**。余弦距离 ∈ [0, 2]，所以 score ∈ [-1, 1]，越高越相似。
- `top_k` 在 Weaviate 端限制召回数，`score_threshold` 在 Dify 端过滤。
- `where` 支持按 `document_ids_filter` 等元数据过滤（`Filter.by_property(...)`）。

### 6.3 混合检索与重排（`core/rag/data_post_processor/`）

- `reranking_mode = weighted_score`：`score = vector_score × weights + keyword_score × (1 − weights)`，`weights.weight_type` 支持 `semantic_first` / `keyword_first` / `customized`。
- `reranking_mode = reranking_model`：两路候选合并后交给 Rerank 模型（如 bge-reranker）重新打分。
- 最终统一做 `top_k` 截断 + `score_threshold_enabled` 过滤，再交给下游组装上下文。

### 6.4 更新与删除语义

| 操作 | 实现 |
|---|---|
| 更新文档 | 按 doc_id 删除旧 chunk（`delete_by_metadata_field("doc_id", ...)`）再重写 |
| 删除文档 | 同上按 `document_id` 批量删（`delete_by_metadata_field`） |
| 删除知识库 | `delete()` 直接 drop 整个 collection |
| 幂等检查 | `text_exists(doc_id)` → `fetch_objects(filters=doc_id, limit=1)` |

## 7. 容器内源码位置清单（可自行继续深挖）

```text
/api/api/core/rag/datasource/vdb/vector_backend_registry.py     # 后端注册/加载
/api/api/core/rag/datasource/vdb/vector_type.py                 # 引擎枚举
/api/api/providers/vdb/vdb-weaviate/src/dify_vdb_weaviate/weaviate_vector.py  # ★ Weaviate 适配器全文
/api/api/models/dataset.py                                      # gen_collection_name_by_id 等
/api/api/controllers/service_api/dataset/dataset.py             # 建库 API 载荷
/api/api/controllers/service_api/dataset/document.py            # create-by-file 载荷
/api/api/controllers/service_api/dataset/hit_testing.py         # retrieve 端点
/api/api/core/rag/entities/processing_entities.py               # 切分规则
/api/api/core/rag/entities/retrieval_settings.py                # 检索配置
/api/api/services/entities/knowledge_entities/knowledge_entities.py  # RetrievalModel 载荷定义
/api/api/core/rag/data_post_processor/data_post_processor.py    # 重排/融合
```

> 快速查看（无需进容器）：
> ```powershell
> docker exec docker-api-1 python -c "print(open('/app/api/providers/vdb/vdb-weaviate/src/dify_vdb_weaviate/weaviate_vector.py').read())"
> ```

## 8. 常见误区澄清

1. ❌ "Weaviate 负责把文本变成向量" → ✅ 向量是 Dify 调 Embedding 模型生成后"自备"写入（`self_provided`），Weaviate 只存和查。
2. ❌ "一个知识库可能对应多个 collection" → ✅ 一个知识库对应**一个** collection；`_Node` 尾缀只是命名规范。
3. ❌ "向量库里存的是原始文档" → ✅ 存的是**切分后的 chunk**（+元数据），原始文件在 Dify 的文件存储里。
4. ❌ "economy 模式也走向量库" → ✅ `indexing_technique: economy` 走关键词索引，不生成向量、不写 Weaviate（无法语义检索）；`high_quality` 才用向量库。
5. ❌ "检索阈值 score_threshold 是相似度" → ✅ 对 Weaviate 而言 score = 1 − 余弦距离，**不是余弦相似度**（值域不同），调参时要按 [−1, 1] 理解。
