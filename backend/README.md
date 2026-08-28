# backend — Spring Boot 网关服务

Dify RAG Lab 的 Java 侧实践工程（Spring Boot 3.3 / Java 17 / Maven）。

## 运行

```powershell
# 方式一：本地有 JDK 17 + Maven
mvn spring-boot:run

# 方式二：本机无 JDK/Maven，用 Docker 编译运行
docker run --rm -v "${PWD}:/build" -w /build maven:3.9-eclipse-temurin-17 mvn -q -DskipTests package
docker build -t dify-rag-lab:0.0.1 .   # 或直接 java -jar target/dify-rag-lab-0.0.1.jar
```

连接配置全部走环境变量（见 `src/main/resources/application.yml`）：| 环境变量 | 默认 | 说明 |
|---|---|---|
| `DIFY_BASE_URL` | `http://localhost:8080` | Dify API 入口（你的 nginx） |
| `DIFY_DATASET_API_KEY` | 空 | 数据集 API Key |
| `DIFY_APP_API_KEY` | 空 | 聊天助手 App API Key（/api/rag/chat 用） |
| `DIFY_DATASET_ID` | 空 | 演练知识库 ID |
| `EMBEDDING_BASE_URL` | 空 | OpenAI 兼容 Embedding 端点（对照实验必须与 Dify 一致） |
| `EMBEDDING_MODEL` | `text-embedding-3-small` | Embedding 模型名 |
| `WEAVIATE_URL` | `http://localhost:8090` | Weaviate 宿主地址（脚本 01 发布的代理） |
| `WEAVIATE_API_KEY` | 空（必填才可用直查） | Weaviate API Key，见下方「密钥从哪来」 |

> **所有环境变量都可缺省启动**：`DIFY_DATASET_ID` / `DIFY_DATASET_API_KEY` 缺省时，
> 问答/检索接口会返回明确的配置报错（不影响服务启动，适合先冒烟验证）；
> `EMBEDDING_BASE_URL` 缺省时，仅"对照实验 / near_vector / hybrid 直查"不可用。

## 密钥从哪来（Weaviate / Dify）

Weaviate 的 API Key **属于 Dify 部署自身的配置**（其 `.env` 中的
`WEAVIATE_AUTHENTICATION_APIKEY_ALLOWED_KEYS`），Dify 不提供任何 API 对外暴露该凭据。
网关的 `WEAVIATE_API_KEY` 按部署形态从以下途径注入：

| 场景 | 途径 | 说明 |
|---|---|---|
| 与 Dify **同一 compose 项目 / 同一 .env** | 编排层共享变量 | 最推荐：Dify 与网关共用一份 `.env`，实例启动时自动就位，无需手工复制 |
| 与 Dify **同机部署**（docker 可达） | 引导脚本读取 | `scripts/11-get-weaviate-key.ps1` / `.sh`：`docker exec` 从 Dify api 容器 env 读取并注入当前会话（仅引导用，非运行时依赖） |
| 生产/跨机 | 密钥管理注入 | 密钥进 Vault / 云 KMS / 部署平台 secret，与 Dify 部署引用同一密钥源 |

> 注意：`WEAVIATE_API_KEY` 只服务于**向量库直查与对照实验**；纯 RAG 使用
> （检索/问答/入库）只需要 `DIFY_DATASET_API_KEY`。跨机部署时若 Weaviate 不可达，
> 直查类功能自动不可用，核心功能不受影响——这正是网关与 Dify 解耦的价值。

## 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/rag/retrieve` | 检索：`{query, search_method, top_k, score_threshold}` |
| POST | `/api/rag/chat` | 问答：`{query, app_key, conversation_id}` |
| POST | `/api/rag/ingest` | 上传文档（multipart `file`），阻塞等待索引完成 |
| GET | `/api/rag/compare?query=..&top_k=5` | **对照实验**：Dify vs 自实现 |
| GET | `/api/vdb/collections` | 列出 Weaviate 全部 collection（含对象数） |
| GET | `/api/vdb/collections/{name}` | collection schema + 对象数 |
| GET | `/api/vdb/collections/{name}/objects?limit=3` | 看 chunk 正文与元数据 |
| POST | `/api/vdb/collections/{name}/search` | 自实现检索：`{query, method: near_vector\|bm25\|hybrid, top_k}` |

## 源码导读（学习顺序）

1. `config/DifyProperties.java` / `WeaviateProperties.java` — 配置模型
2. `dify/DifyApiClient.java` — Dify Service API 封装（multipart、鉴权、轮询）
3. `embedding/EmbeddingClient.java` — "向量从哪来"：OpenAI 兼容 /embeddings
4. `weaviate/WeaviateClient.java` — Weaviate REST：schema / GraphQL nearVector / bm25
5. `rag/RagService.java` — 自实现 semantic/hybrid，与 Dify 结果对照
6. `web/*` — 对外接口

> 对照实验的意义：Dify 与自实现使用同一个 Embedding 模型、同一个 collection、
> 同样的 score 口径（vector score = 1 − cosine distance），差异即 Dify 的
> 额外加工（rerank、元数据过滤、参数默认值），可逐一开关验证。

## 国内网络加速（可选）

- **Docker Hub 拉 maven 镜像失败/慢**：改用加速镜像
  ```powershell
  docker run --rm -v "${PWD}:/build" -w /build `
    docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17 mvn -q -DskipTests package
  ```
- **Maven 依赖下载慢**：在 `~/.m2/settings.xml` 配置阿里云镜像
  ```xml
  <mirror><id>aliyun</id><mirrorOf>central</mirrorOf>
  <url>https://maven.aliyun.com/repository/public</url></mirror>
  ```

