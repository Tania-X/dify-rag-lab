# Minor 问题收集

> 这里汇总历次 AI Review / 讨论中已识别但暂不阻塞合并的 minor 问题。
> 后续找一个专门节点统一修复。

## 来自 PR #2（dockerize）

- [ ] `docker-compose.yml` 中 `REWRITE_BASE_URL` 空串可能覆盖后端 `application.yml` 的默认值
  - 位置：`docker-compose.yml`
  - 建议：空值时不要注入，或使用 `${REWRITE_BASE_URL:-}` 并在后端做空值回退
- [ ] 外部网络名 `docker_default` 硬编码
  - 位置：`docker-compose.yml`
  - 建议：做成可配置项，例如 `DIFY_NETWORK_NAME`

## 来自 PR #3（RAG eval）

- [ ] `scripts/evaluate_query_rewrite.py` 的 `urlopen` 未设置 timeout，且未捕获 HTTP/网络异常
  - 位置：`scripts/evaluate_query_rewrite.py`
  - 影响：Dify 不可用或单条失败时，整批评测可能挂死/中断
  - 建议：`urllib.request.urlopen(req, timeout=30)`，捕获 `HTTPError/URLError` 后跳过该条并汇总失败数
- [ ] `expected_keyword` 使用子串匹配，容易误命中
  - 位置：`scripts/evaluate_query_rewrite.py`
  - 影响：关键词过泛时（如“支付”“超时”）会污染 hit@1/MRR
  - 建议：改为更严格的匹配策略，或在评测集设计时要求关键词为文档独有标识
- [ ] 行内元数据不完整时静默跳过过滤
  - 位置：`scripts/evaluate_query_rewrite.py`
  - 影响：某行只填了 `metadata_name` 没填 `metadata_value` 时，会在无过滤条件下评测且无告警
  - 建议：校验 name/value 必须成对，不完整则打印告警并跳过

## 来自 PR #4（query rewrite metadata）

- [ ] LLM 提取的 `metadata_filters` 未校验直接透传 Dify
  - 位置：`backend/src/main/java/com/example/difyraglab/web/RagController.java`
  - 影响：LLM 幻觉出不存在的字段名或不支持的 operator 时，检索可能 500 或返回空结果
  - 建议：调用 `listMetadataFields` 做字段存在性校验，operator 做白名单校验，非法条件丢弃并降级为不过滤
- [ ] `/api/rag/ingest` 的 `wait=false` 时，可能在索引完成前打元数据 tag
  - 位置：`backend/src/main/java/com/example/difyraglab/web/RagController.java`
  - 影响：`wait=false` 时上传后立即打标，文档可能尚未索引完成，导致 tag 丢失或失败
  - 建议：`wait=false` 时也先确保索引完成，或提供单独的“索引完成后打标”接口

## 来自 PR #5（metadata frontend）

- [ ] 带元数据过滤的问答会绕过 Dify App，`appKey` 参数实际未使用
  - 位置：`backend/src/main/java/com/example/difyraglab/rag/RagService.java`
  - 影响：过滤问答固定使用默认 `DIFY_DATASET_ID`/`DIFY_DATASET_API_KEY`，用户自定义 `app_key` 在该路径下无效
  - 建议：明确该路径仅支持默认知识库并在接口注释/前端说明；或让 `ChatRequest` 支持 `dataset_id`，按请求指定知识库
- [ ] 前端所有元数据字段硬编码 `operator: 'is'`
  - 位置：`frontend/src/components/ChatPanel.tsx`、`frontend/src/components/RetrievePanel.tsx`
  - 影响：对 `number` / `time` 类型字段，`is` 可能不合适，导致过滤失效或报错
  - 建议：根据 `MetadataField.type` 选择操作符和输入控件；至少对 number 类型传数值
- [ ] 带过滤问答的 LLM 生成复用 rewrite 配置且无降级
  - 位置：`backend/src/main/java/com/example/difyraglab/rag/RagService.java`
  - 影响：LLM 端点异常时过滤问答直接 500，未过滤路径走 Dify App 却正常
  - 建议：为答案生成单独配置 LLM，或在失败时降级到 Dify App 并记录日志

## 待办说明

修复时建议按“后端健壮性 → 前端类型正确性 → 脚本稳定性”分批处理。
