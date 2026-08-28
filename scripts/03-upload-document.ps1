#requires -Version 7.0
<#
.SYNOPSIS
    阶段 1：上传文档到知识库并等待索引完成（create-by-file + 轮询）。
.DESCRIPTION
    演示 Dify 知识库 API 的两步异步模型：
      1) POST /datasets/{id}/document/create-by-file  → 立即返回 document_id
      2) GET  /datasets/{id}/documents/{doc_id}/indexing-status → 轮询到 completed/error
    索引完成后，chunk 向量已写入 Weaviate（见 05-inspect-weaviate.ps1）。
.NOTES
    环境变量：DIFY_BASE_URL / DIFY_DATASET_ID / DIFY_DATASET_API_KEY
    依赖：PowerShell 7（-Form 参数）。
#>
param(
    [string]$DocumentPath,
    [string]$DocForm = 'text_model',        # text_model | hierarchical_model | qa_model
    [string]$DatasetId = $env:DIFY_DATASET_ID,
    [string]$ApiKey = $env:DIFY_DATASET_API_KEY,
    [string]$BaseUrl = $env:DIFY_BASE_URL,
    [int]$TimeoutSec = 180
)
$ErrorActionPreference = 'Stop'
if (-not $BaseUrl) { $BaseUrl = 'http://localhost:8080' }

if (-not $DocumentPath) { throw '缺少 -DocumentPath（要上传的文件）' }
if (-not $DatasetId) { throw '缺少 -DatasetId（知识库 ID）' }
if (-not $ApiKey) { throw '缺少 -ApiKey（数据集 API Key）' }
if (-not (Test-Path $DocumentPath)) { throw "文件不存在: $DocumentPath" }

# multipart 里的 data JSON：与 Dify 1.17 create-by-file 契约一致（docs/02 第 7 节）
$data = @{
    indexing_technique = 'high_quality'
    doc_form           = $DocForm
    doc_language       = 'Chinese'
    process_rule       = @{
        mode  = 'custom'
        rules = @{
            segmentation = @{
                separator     = "`n"
                max_tokens    = 500
                chunk_overlap = 50
            }
        }
    }
} | ConvertTo-Json -Depth 10

Write-Host "上传文档: $DocumentPath → dataset=$DatasetId"
$resp = Invoke-RestMethod -Method Post `
    -Uri "$BaseUrl/v1/datasets/$DatasetId/document/create-by-file" `
    -Headers @{ Authorization = "Bearer $ApiKey" } `
    -Form @{ file = Get-Item $DocumentPath; data = $data }

$docId = $resp.document.id
Write-Host "Document created: document_id=$docId batch=$($resp.batch)"

# 轮询索引状态
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$status = ''
do {
    Start-Sleep -Seconds 3
    $st = Invoke-RestMethod -Method Get `
        -Uri "$BaseUrl/v1/datasets/$DatasetId/documents/$docId/indexing-status" `
        -Headers @{ Authorization = "Bearer $ApiKey" }
    $status = $st.indexing_status
    Write-Host "  indexing_status=$status"
} while ($status -in @('waiting', 'parsing', 'cleaning', 'splitting', 'indexing') -and $sw.Elapsed.TotalSeconds -lt $TimeoutSec)

if ($status -eq 'completed') {
    $segCount = $st.segments_count
    if ($null -eq $segCount) { $segCount = '可在 UI 分段列表或 05 号脚本中查看' }
    Write-Host "✅ 索引完成。分段数: $segCount"
} elseif ($status -eq 'error') {
    Write-Host "❌ 索引失败。错误信息: $($st.error | ConvertTo-Json -Compress)"
    Write-Host "  常见原因：Embedding 模型未配置 / 模型供应商 Key 失效 / 文件格式不支持。"
} else {
    Write-Host "⏳ 索引未在 ${TimeoutSec}s 内完成（当前状态 $status），可重跑本脚本查看。"
}
