#requires -Version 7.0
<#
.SYNOPSIS
    阶段 2：对比三种检索方式（semantic / full_text / hybrid）。
.DESCRIPTION
    同一 query 分别走 semantic_search、full_text_search、hybrid_search，
    打印各方法 top3 的 score 与命中内容。
    注意 score 口径不同：
      - semantic/hybrid 的 score ≈ 1 - 余弦距离（[-1,1]，越大越相似）
      - full_text 的 score 是 BM25 原始分（量纲不同，不可跨方法比较）
.NOTES
    环境变量：DIFY_BASE_URL / DIFY_DATASET_ID / DIFY_DATASET_API_KEY
#>
param(
    [string]$Query = '支付网关调用超时应该怎么办',
    [string]$DatasetId = $env:DIFY_DATASET_ID,
    [string]$ApiKey = $env:DIFY_DATASET_API_KEY,
    [string]$BaseUrl = $env:DIFY_BASE_URL,
    [int]$TopK = 3
)
$ErrorActionPreference = 'Stop'
if (-not $BaseUrl) { $BaseUrl = 'http://localhost:8080' }
if (-not $DatasetId) { throw '缺少 -DatasetId' }
if (-not $ApiKey) { throw '缺少 -ApiKey' }

$methods = @('semantic_search', 'full_text_search', 'hybrid_search')
foreach ($m in $methods) {
    $body = @{
        query           = $Query
        retrieval_model = @{
            search_method           = $m
            reranking_enable        = $false   # 1.17 必填字段
            top_k                   = $TopK
            score_threshold_enabled = $false
        }
    } | ConvertTo-Json -Depth 6

    $resp = Invoke-RestMethod -Method Post `
        -Uri "$BaseUrl/v1/datasets/$DatasetId/retrieve" `
        -Headers @{ Authorization = "Bearer $ApiKey" } `
        -ContentType 'application/json' -Body $body

    Write-Host "`n[$m]  query=$Query"
    if (-not $resp.records -or $resp.records.Count -eq 0) {
        Write-Host "  (无命中)"
        continue
    }
    $i = 1
    foreach ($r in $resp.records) {
        $content = ($r.segment.content -replace "`r?`n", ' ')
        if ($content.Length -gt 70) { $content = $content.Substring(0, 70) + '...' }
        Write-Host ("  top{0}  score={1,6:N3}  {2}" -f $i, [double]$r.score, $content)
        $i++
    }
}

Write-Host "`n提示：score 口径不同——semantic/hybrid 是 1-余弦距离（≈[-1,1]），full_text 是 BM25 原始分。"
