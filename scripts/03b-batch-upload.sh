#!/usr/bin/env bash
# 阶段 1（批量）：把目录下所有文档批量上传到知识库并等待索引完成。
# 依赖：curl、jq。
# 用法：./03b-batch-upload.sh [目录] [doc_form] [dataset_id] [api_key]
# 环境变量：DIFY_BASE_URL / DIFY_DATASET_ID / DIFY_DATASET_API_KEY / TIMEOUT_SEC
set -euo pipefail

: "${DIFY_BASE_URL:=http://localhost:8080}"
: "${DIFY_DATASET_ID:=}"
: "${DIFY_DATASET_API_KEY:=}"
: "${TIMEOUT_SEC:=180}"

DIR="${1:-sample-data}"
DOC_FORM="${2:-text_model}"
DATASET_ID="${3:-${DIFY_DATASET_ID}}"
API_KEY="${4:-${DIFY_DATASET_API_KEY}}"

[ -n "${DATASET_ID}" ] || { echo "缺少 dataset_id" >&2; exit 1; }
[ -n "${API_KEY}" ] || { echo "缺少 api_key" >&2; exit 1; }
[ -d "${DIR}" ] || { echo "目录不存在: ${DIR}" >&2; exit 1; }

shopt -s nullglob
files=("${DIR}"/*.md "${DIR}"/*.txt "${DIR}"/*.pdf "${DIR}"/*.docx)
if [ ${#files[@]} -eq 0 ]; then
  echo "目录中没有可上传的文档: ${DIR}" >&2
  exit 1
fi
echo "发现 ${#files[@]} 个文档，开始批量上传到知识库 ${DATASET_ID} ..."

ok=0
for f in "${files[@]}"; do
  base=$(basename "${f}")
  data=$(jq -nc --arg doc_form "${DOC_FORM}" \
    '{indexing_technique:"high_quality", doc_form:$doc_form, doc_language:"Chinese",
      process_rule:{mode:"custom", rules:{segmentation:{separator:"\n", max_tokens:500, chunk_overlap:50}}}}')

  if resp=$(curl -fsS -X POST "${DIFY_BASE_URL}/v1/datasets/${DATASET_ID}/document/create-by-file" \
      -H "Authorization: Bearer ${API_KEY}" \
      -F "file=@${f}" \
      -F "data=${data}" 2>/dev/null); then
    doc_id=$(jq -r '.document.id' <<<"${resp}")

    status=""
    elapsed=0
    while :; do
      sleep 2
      elapsed=$((elapsed + 2))
      st=$(curl -fsS "${DIFY_BASE_URL}/v1/datasets/${DATASET_ID}/documents/${doc_id}/indexing-status" \
        -H "Authorization: Bearer ${API_KEY}")
      status=$(jq -r '.indexing_status' <<<"${st}")
      case "${status}" in
        waiting|parsing|cleaning|splitting|indexing)
          if [ "${elapsed}" -ge "${TIMEOUT_SEC}" ]; then status="timeout"; break; fi
          ;;
        completed) ok=$((ok + 1)); break ;;
        *) break ;;
      esac
    done
    printf '%-40s %-36s %-10s\n' "${base}" "${doc_id}" "${status}"
  else
    printf '%-40s %-36s %-10s\n' "${base}" "-" "ERROR"
  fi
done

echo ""
echo "批量入库完成: 成功 ${ok}/${#files[@]}"
echo "下一步：./05-inspect-weaviate.sh 查看向量库；./04b-evaluate.sh 跑评测集。"
