#requires -Version 7.0
<#
.SYNOPSIS
    阶段 1 前置：创建演练知识库。
.DESCRIPTION
    优先尝试用数据集 API Key 通过 API 创建（POST /v1/datasets）；
    若该 Key 无建库权限，则给出 UI 操作指引。
.NOTES
    环境变量：DIFY_BASE_URL / DIFY_DATASET_API_KEY
#>
param(
    [string]$Name = '研发知识库演练',
    [string]$ApiKey = $env:DIFY_DATASET_API_KEY,
    [string]$BaseUrl = $env:DIFY_BASE_URL
)
$ErrorActionPreference = 'Stop'
if (-not $BaseUrl) { $BaseUrl = 'http://localhost:8080' }

if (-not $ApiKey) {
    Write-Host "未提供数据集 API Key。请先完成 UI 操作："
    Write-Host "  1) 打开 $BaseUrl → 知识库 → 创建知识库"
    Write-Host "     - 名称: $Name"
    Write-Host "     - 索引方式: 高质量（High Quality，需要 Embedding 模型）"
    Write-Host "  2) 打开该知识库 → 设置 → API 访问 → 创建 API Key"
    Write-Host "  3) 把 Key 与知识库 ID 记下来，然后重跑本脚本："
    Write-Host "     .\scripts\02-create-dataset.ps1 -ApiKey <Key>"
    Write-Host "     或设置环境变量 DIFY_DATASET_API_KEY / DIFY_DATASET_ID"
    exit 0
}

$body = @{
    name                = $Name
    description         = 'Dify RAG Lab 演练知识库'
    indexing_technique  = 'high_quality'
    permission          = 'only_me'
} | ConvertTo-Json

try {
    $resp = Invoke-RestMethod -Method Post `
        -Uri "$BaseUrl/v1/datasets" `
        -Headers @{ Authorization = "Bearer $ApiKey" } `
        -ContentType 'application/json' -Body $body
    Write-Host "知识库创建成功: id=$($resp.id)"
    Write-Host "请设置环境变量后继续："
    Write-Host "  `$env:DIFY_DATASET_ID = '$($resp.id)'"
} catch {
    Write-Warning "API 创建失败（数据集 API Key 可能没有建库权限）：$($_.Exception.Message)"
    Write-Host "请改用 UI 创建（见上方输出），再执行 03-upload-document.ps1。"
}
