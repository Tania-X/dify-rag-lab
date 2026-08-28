#!/usr/bin/env bash
# 阶段 6：清理演练资源。
# 1) 删除端口代理容器 weaviate-host-proxy；
# 2) （可选）DELETE_DATASET=true 时删除演练知识库（Dify 同步 drop 对应 Weaviate collection）。
# 环境变量：DIFY_BASE_URL / DIFY_DATASET_ID / DIFY_DATASET_API_KEY / DELETE_DATASET
set -euo pipefail

: "${DIFY_BASE_URL:=http://localhost:8080}"
: "${DIFY_DATASET_ID:=}"
: "${DIFY_DATASET_API_KEY:=}"
DELETE_DATASET="${DELETE_DATASET:-false}"

echo "删除端口代理容器 weaviate-host-proxy ..."
docker rm -f weaviate-host-proxy >/dev/null 2>&1 || true
echo "  done."

if [ "${DELETE_DATASET}" = "true" ]; then
  [ -n "${DIFY_DATASET_ID}" ] || { echo "未设置 DIFY_DATASET_ID，跳过删除知识库" >&2; exit 0; }
  [ -n "${DIFY_DATASET_API_KEY}" ] || { echo "未设置 DIFY_DATASET_API_KEY，跳过删除知识库" >&2; exit 0; }
  if curl -fsS -X DELETE "${DIFY_BASE_URL}/v1/datasets/${DIFY_DATASET_ID}" \
    -H "Authorization: Bearer ${DIFY_DATASET_API_KEY}" --max-time 15 >/dev/null; then
    echo "知识库 ${DIFY_DATASET_ID} 已删除（对应 Weaviate collection 已 drop）"
  else
    echo "删除知识库失败，可在 UI 中手动删除，或在控制台删除 Weaviate collection：" >&2
    echo "  curl -X DELETE <weaviate>/v1/schema/Vector_index_<dataset_id>_Node -H 'Authorization: Bearer <key>'" >&2
  fi
fi

echo "清理完成。"
