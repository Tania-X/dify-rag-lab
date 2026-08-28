# frontend — React 前端

研发知识库问答助手前端（React 19 + Ant Design 6 + Vite 8 + TypeScript）。
**前端不直连 Dify，所有请求走 Java 网关（backend，默认 8081）的 `/api`**，由 Vite 开发代理转发。

## 运行

```powershell
# 1) 先启动 Java 网关（见 ../backend/README.md），默认 8081
# 2) 安装依赖并启动
cd frontend
npm install
npm run dev          # http://localhost:5173
```

## 三个面板

| 面板 | 调用 | 说明 |
|---|---|---|
| 问答 | `POST /api/rag/chat` | 走 Dify 聊天助手 App，回答带引用溯源 |
| 检索对比 | `POST /api/rag/retrieve` | semantic / full_text / hybrid 三方式，观察 score 口径 |
| 向量库直查 | `GET/POST /api/vdb/*` | 绕过 Dify 直查 Weaviate：collections / objects / near_vector / bm25 |

## 配置

- 开发：`vite.config.ts` 已代理 `/api → http://localhost:8081`；
- 构建：`VITE_API_BASE` 可覆盖（默认 `/api`），示例见 `.env.example`；
- 后端鉴权：App/数据集 API Key 在后端配置（环境变量），前端不持有密钥。

## 结构

```
src/
├── api/client.ts        # axios 实例 + 类型化接口（唯一 API 入口）
├── types.ts             # 与后端 DTO 对齐的类型
├── components/
│   ├── ChatPanel.tsx    # 问答
│   ├── RetrievePanel.tsx# 检索对比
│   └── VdbPanel.tsx     # 向量库直查
└── App.tsx              # 布局 + Tabs
```
