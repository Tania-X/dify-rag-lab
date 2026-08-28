#requires -Version 7.0
<#
.SYNOPSIS
    阶段 1（批量）：把整个目录下的文档批量上传到知识库并等待索引完成。
.DESCRIPTION
    对 sample-data/ 这类多文档语料，逐个执行 create-by-file + 轮询索引状态，
    最后输出汇总表。演示语料包含 8 份文档，跑完后可在 Weaviate 里看到
    collection 对象数 ≈ 全部分段之和（见 05 号脚本）。
.NOTES
    环境变量：DIFY_BASE_URL / DIFY_DATASET_ID / DIFY_DATASET_API_KEY
    依赖：PowerShell 7（-Form 参数）。跳过已存在的文档？不——重复上传会走
    Dify 去重（uuid5），文档 API 默认 duplicate=false 时同名文档会失败，
    重复执行请使用 -SkipExisting 或换 -ProcessRule。
#>
param(
    [string]$Directory = (Join-Path $PSScriptRoot '..\sample-data'),
    [string]$DocForm = 'text_model',          # text_model | hierarchical_model | qa_model
    [string]$DatasetId = $env:DIFY_DATASET_ID,
    [string]$ApiKey = $env:DIFY_DATASET_API_KEY,
    [string]$BaseUrl = $env:DIFY_BASE_URL,
    [int]$TimeoutSec = 180
)
$ErrorActionPreference = 'Stop'
if (-not $BaseUrl) { $BaseUrl = 'http://localhost:8080' }
if (-not $DatasetId) { throw '缺少 -DatasetId' }
if (-not $ApiKey) { throw '缺少 -ApiKey' }
if (-not (Test-Path $Directory)) { throw "目录不存在: $Directory" }

$files = Get-ChildItem $Directory -File |
    Where-Object { $_.Extension -in '.md', '.txt', '.pdf', '.docx' } |
    Sort-Object Name
if ($files.Count -eq 0) { throw "目录中没有可上传的文档: $Directory" }
Write-Host "发现 $($files.Count) 个文档，开始批量上传到知识库 $DatasetId ...`n"

$results = foreach ($f in $files) {
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

    try {
        $resp = Invoke-RestMethod -Method Post `
            -Uri "$BaseUrl/v1/datasets/$DatasetId/document/create-by-file" `
            -Headers @{ Authorization = "Bearer $ApiKey" } `
            -Form @{ file = $f; data = $data }

        $docId = $resp.document.id
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $status = ''
        do {
            Start-Sleep -Seconds 2
            $st = Invoke-RestMethod -Method Get `
                -Uri "$BaseUrl/v1/datasets/$DatasetId/documents/$docId/indexing-status" `
                -Headers @{ Authorization = "Bearer $ApiKey" }
            $status = $st.indexing_status
        } while ($status -in @('waiting', 'parsing', 'cleaning', 'splitting', 'indexing') `
                 -and $sw.Elapsed.TotalSeconds -lt $TimeoutSec)

        [PSCustomObject]@{
            File       = $f.Name
            DocumentId = $docId
            Status     = $status
            Segments   = $st.segments_count
        }
    } catch {
        [PSCustomObject]@{
            File       = $f.Name
            DocumentId = '-'
            Status     = "ERROR: $($_.Exception.Message)"
            Segments   = $null
        }
    }
}

$results | Format-Table -AutoSize
$ok = @($results | Where-Object Status -eq 'completed').Count
Write-Host "批量入库完成: 成功 $ok / $($results.Count)。"
Write-Host "下一步：\scripts\05-inspect-weaviate.ps1 查看向量库；\scripts\04b-evaluate.ps1 跑评测集。"
