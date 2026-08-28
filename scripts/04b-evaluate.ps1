#requires -Version 7.0
<#
.SYNOPSIS
    阶段 2（评测）：用评测集给检索效果打分（hit@1 / hit@5）。
.DESCRIPTION
    读取 sample-data/评测集-questions.csv（question + expected_keyword），
    对每题调用知识库 hybrid_search（top_k），检查命中的 chunk 正文是否包含
    期望关键词，统计：
      - hit@1：top1 命中率
      - hit@5：top5 命中率
    这是生产化 checklist（docs/04 第 1 节"建立评测集"）的最小落地版。
    提示：命中率低时，用 04-retrieve-compare.ps1 单题排查，调整切分参数或检索策略。
.NOTES
    环境变量：DIFY_BASE_URL / DIFY_DATASET_ID / DIFY_DATASET_API_KEY
    说明：expected_keyword 是"答案所在文档中的关键子串"，不同文档都可能包含
    同一关键词属正常现象（真实语料有重叠），因此本脚本衡量的是"答案能否被召回"。
#>
param(
    [string]$CsvPath = (Join-Path $PSScriptRoot '..\sample-data\评测集-questions.csv'),
    [string]$DatasetId = $env:DIFY_DATASET_ID,
    [string]$ApiKey = $env:DIFY_DATASET_API_KEY,
    [string]$BaseUrl = $env:DIFY_BASE_URL,
    [int]$TopK = 5
)
$ErrorActionPreference = 'Stop'
if (-not $BaseUrl) { $BaseUrl = 'http://localhost:8080' }
if (-not $DatasetId) { throw '缺少 -DatasetId' }
if (-not $ApiKey) { throw '缺少 -ApiKey' }
if (-not (Test-Path $CsvPath)) { throw "评测集不存在: $CsvPath" }

$rows = Import-Csv $CsvPath
Write-Host "评测集共 $($rows.Count) 题，检索方式 hybrid_search top_k=$TopK`n"

$hit1 = 0; $hit5 = 0
$details = foreach ($r in $rows) {
    $body = @{
        query           = $r.question
        retrieval_model = @{
            search_method           = 'hybrid_search'
            reranking_enable        = $false
            top_k                   = $TopK
            score_threshold_enabled = $false
        }
    } | ConvertTo-Json -Depth 6

    $resp = Invoke-RestMethod -Method Post `
        -Uri "$BaseUrl/v1/datasets/$DatasetId/retrieve" `
        -Headers @{ Authorization = "Bearer $ApiKey" } `
        -ContentType 'application/json' -Body $body

    $kw = $r.expected_keyword
    $h1 = $false; $h5 = $false; $top1 = ''
    if ($resp.records -and $resp.records.Count -gt 0) {
        for ($i = 0; $i -lt $resp.records.Count; $i++) {
            $content = [string]$resp.records[$i].segment.content
            if ($content -like "*$kw*") {
                if ($i -eq 0) { $h1 = $true }
                $h5 = $true
            }
        }
        $c0 = [string]$resp.records[0].segment.content
        if ($c0.Length -gt 40) { $c0 = $c0.Substring(0, 40) + '...' }
        $top1 = $c0 -replace "`r?`n", ' '
    }

    if ($h1) { $hit1++ }
    if ($h5) { $hit5++ }

    [PSCustomObject]@{
        ID      = $r.id
        Question = $r.question
        Keyword = $kw
        HitAt1  = $(if ($h1) { '✅' } else { '❌' })
        HitAt5  = $(if ($h5) { '✅' } else { '❌' })
        Top1    = $top1
    }
}

$details | Format-Table -AutoSize -Wrap
Write-Host ("评测结果: hit@1 = {0}/{1} ({2:P1})    hit@{3} = {4}/{1} ({5:P1})" -f `
    $hit1, $rows.Count, ($hit1 / $rows.Count), $TopK, $hit5, ($hit5 / $rows.Count))
