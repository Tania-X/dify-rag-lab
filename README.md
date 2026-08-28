# Dify RAG Lab — 向量数据库实战项目

> 面向高级 Java 开发者的 **Dify 向量数据库** 生产级实战方案。
> 基于你本机正在运行的 **Dify 1.17.0 + Weaviate 1.27.0**（Docker 部署）定制，所有机制说明均取自该版本容器内源码，可直接在你的环境上逐步演练。

## 一、这是什么

一个可落地的应用方向 + 一套可实践的解决方案：

- **应用方向**：企业内部「研发知识库智能问答助手」（RAG）
- **技术栈**：Dify（RAG 编排）+ Weaviate（向量数据库）+ PostgreSQL（Dify 元数据）+ Spring Boot（Java 网关/检索服务）
- **学习主线**：搞懂 Dify 的向量数据库到底"怎么用"——从建库、切分、Embedding、写入、检索、重排到 Java 侧自实现向量检索并与 Dify 结果对照

## 二、目录结构

```
dify-rag-lab/
├── README.md                  # 本文件：总览与快速开始
├── docs/
│   ├── 01-应用方向与架构设计.md   # 方向选择论证 + 架构设计
│   ├── 02-Dify向量数据库机制详解.md # 源码级机制拆解（与你部署逐项对应）
│   ├── 03-分步演练指南.md         # 6 个阶段的上手实操
│   ├── 04-生产化落地清单.md       # 性能/安全/成本/可观测 checklist
│   └── 05-生产部署对标与前端方案.md # 当前环境 vs 生产差距 + 前端三形态选型
├── backend/                   # Spring Boot 3 工程（Java 17 + Maven）
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/difyraglab/
│       │   ├── DifyRagLabApplication.java
│       │   ├── config/       # Dify/Weaviate 配置与 HTTP 客户端
│       │   ├── dify/         # Dify Service API 封装（建库/传文档/检索/问答）
│       │   ├── weaviate/     # Weaviate 直查客户端（schema/对象/向量检索/BM25）
│       │   ├── embedding/    # OpenAI 兼容 Embedding 客户端（自实现检索用）
│       │   ├── rag/          # RAG 编排：Dify 检索 vs 自实现检索 对照实验
│       │   └── web/          # REST 控制器
│       └── resources/application.yml
├── frontend/                  # React 19 + Ant Design 6 + Vite 8（不直连 Dify，走网关）
│   ├── src/api/client.ts     # axios 唯一 API 入口
│   ├── src/components/       # 问答 / 检索对比 / 向量库直查 三个面板
│   └── vite.config.ts        # 开发代理 /api → 8081
├── scripts/                   # PowerShell 演练脚本（0x-编号顺序执行）
│   ├── 01-publish-weaviate-port.ps1   # 发布 Weaviate 端口到宿主机（便于直查）
│   ├── 02-create-dataset.ps1          # 创建知识库（UI 指引 + API 方式）
│   ├── 03-upload-document.ps1         # 上传单个文档 + 轮询索引状态
│   ├── 03b-batch-upload.ps1           # 批量入库整个目录（演示语料一键灌入）
│   ├── 04-retrieve-compare.ps1        # 三种检索方式效果对比
│   ├── 04b-evaluate.ps1               # 评测集打分：hit@1 / hit@5
│   ├── 05-inspect-weaviate.ps1        # 直查向量库：schema/数据量/向量/相似度
│   └── 06-teardown.ps1                # 清理演练产生的资源
└── sample-data/               # 演示语料：8 份研发中台文档 + 14 条评测题
    ├── 01~06-研发规范/运维/安全/产品手册.md
    ├── 研发运维规范.md / FAQ-常见问题.md
    └── 评测集-questions.csv   # 检索质量评测题（question + expected_keyword）
```

## 三、前提条件（已核实你满足）

| 项 | 状态 | 说明 |
|---|---|---|
| Dify 1.17.0 Docker 部署 | ✅ 运行中 | compose 位于 `C:\Users\001\WorkBuddy\2026-08-27-14-55-18\dify\docker`，nginx 暴露 `8080/443` |
| Weaviate 1.27.0 | ✅ 运行中 | 容器 `docker-weaviate-1`，内部地址 `http://weaviate:8080`，API Key `WVF5YThaHlkYwhGUSmCRgsX3tD5ngdN8pkih`（Dify 官方默认） |
| Embedding 模型 | ⚠️ 需确认 | 需在 Dify「设置 → 模型供应商」配置任意可用 Embedding 模型（如 OpenAI 兼容端点 text-embedding-3-small），你的 `.env` 中 `OPENAI_API_BASE` 已指向兼容端点 |
| JDK 17 + Maven | ❌ 本机未装 | 演练第 4 阶段用 `docker run maven` 编译，或直接在 IDEA 中打开 `backend/` 运行 |
| PowerShell 7+ | 建议 | 脚本用到 `Invoke-RestMethod -Form`（PS 7 专属），PS 5.1 用户见脚本内 curl.exe 备选 |

## 四、快速开始（5 分钟跑通主链路）

```powershell
# 1) 进入项目
cd C:\Users\001\dsh\dify-rag-lab

# 2) 发布 Weaviate 端口到宿主机 8090（不动你的 compose，只加一个透明代理容器）
.\scripts\01-publish-weaviate-port.ps1

# 3) 在 Dify Web 界面创建知识库（UI 操作 30 秒），并生成数据集 API Key，
#    然后填入脚本参数或环境变量（详见 docs/03 阶段 0）
#    控制台: http://localhost:8080
#    ⚠️ 首次入库前必须先配置 Embedding 模型（设置 → 模型供应商）

# 4) 一键批量入库（8 份演示语料，含 14 条评测题）
.\scripts\03b-batch-upload.ps1

# 5) 对比三种检索方式 + 用评测集打分
.\scripts\04-retrieve-compare.ps1 -DatasetId <你的知识库ID> -ApiKey <数据集API Key>
.\scripts\04b-evaluate.ps1

# 6) 直查向量库，看 Dify 到底存了什么
.\scripts\05-inspect-weaviate.ps1
```

> 完整的 6 阶段演练（含 Java 服务、问答应用、自实现检索对照实验）见 **docs/03-分步演练指南.md**。

## 五、没有绕过前端：UI 与 API 的职责划分

方案**不绕过** Dify 前端，而是按生产实践做职责分工——**管理面（一次性配置）走 UI，数据面（业务操作）走 API**：

| 环节 | 操作方式 | 原因 |
|---|---|---|
| 配置 Embedding / Rerank / LLM 模型供应商 | **必须 UI**（设置 → 模型供应商） | Dify 无公开的模型配置 Service API |
| 创建知识库 | **UI 为主**（02 号脚本会先尝试 API，无权限则自动回退 UI 指引） | 数据集 API Key 通常只能操作它绑定的那个知识库，建新库大多需要 UI |
| 生成数据集 API Key | **必须 UI**（知识库 → 设置 → API 访问） | 密钥只能在页面生成 |
| 上传文档 / 触发索引 / 轮询状态 | API（03 号脚本、Java `/api/rag/ingest`） | 业务操作，生产集成常态 |
| 三种方式检索 / 问答 | API（04 号脚本、Java `/api/rag/*`） | 同上 |
| 创建聊天助手 App、关联知识库、发布、取 App Key | **必须 UI**（docs/03 阶段 5） | App 是编排实体，需在页面搭建 |
| 向量库直查 | Weaviate REST（连 Dify UI 都不经过） | 学习机制用的旁路观察 |

**结论**：演练中你需要在 UI 做的只有 4 件事（配模型、建库、生成 Key、建 App），大约 5 分钟；其余入库、检索、问答、对照实验全部走 API——这正是生产环境中 Java 网关与 Dify 交互的真实形态。docs/03 阶段 0 和阶段 5 已给出每一步的 UI 点击路径。

> 如果你手头已有现成的知识库 + 数据集 API Key，从 03 号脚本开始可以全程不碰页面。

## 六、核心结论（先给你三个最有价值的认知）

1. **Dify 的向量库 = Weaviate 里的一个 Class**：每个知识库对应一个 collection，命名 `Vector_index_{dataset_id去掉横线}_Node`，里面存的不是原始文档，而是**切分后的 chunk**（正文 `text` 属性 + 向量 + 元数据）。
2. **向量不是 Dify 生成的，也不是 Weaviate 生成的**：是 Dify 调用你配置的 **Embedding 模型** 生成后"自备"写入（`self_provided`），Weaviate 只负责存储与近邻检索。
3. **Dify 检索的本质 = 向量召回 + 关键词召回 + 重排**：`semantic_search` 走 `near_vector`（score = 1 - 余弦距离），`full_text_search` 走 BM25，`hybrid_search` 两路召回后按权重融合或走 Rerank 模型。Java 侧完全可以用同样公式复现并与 Dify 结果对照。

## 七、学习路径建议

| 阶段 | 目标 | 产出 |
|---|---|---|
| 读 docs/01 | 理解方案与架构 | 知道每层组件干什么 |
| 读 docs/02 | 源码级搞懂向量库机制 | 能向同事讲清楚 Dify 怎么用向量库 |
| 演练 1-3 | 亲手建库、入库、检索 | 会调 Dify 知识库 API |
| 演练 4 | Java 直查向量库 | 理解存储结构与相似度计算 |
| 演练 5 | 自实现检索并对照 | 复现 semantic/hybrid，理解调参 |
| 读 docs/04 | 生产化视角 | 上线 checklist 与风险预案 |
