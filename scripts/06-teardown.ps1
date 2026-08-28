#requires -Version 7.0
<#
.SYNOPSIS
    阶段 6：清理演练资源。
.DESCRIPTION
    1) 删除端口代理容器 weaviate-host-proxy；
    2) （可选，-DeleteDataset）删除演练知识库——Dify 会同步 drop 对应的
       Weaviate collection 与 Postgres 元数据。
.NOTES
    环境变量：DIFY_BASE_URL / DIFY_DATASET_ID / DIFY_DATASET_API_KEY
#>
param(
    [switch]$DeleteDataset,
    [string]$DatasetId = $env:DIFY_DATASET_ID,
    [string]$ApiKey = $env:DIFY_DATASET_API_KEY,
    [string]$BaseUrl = $env:DIFY_BASE_URL
)
$ErrorActionPreference = 'Continue'
if (-not $BaseUrl) { $BaseUrl = 'http://localhost:8080' }

# 1) 删除代理容器
Write-Host "删除端口代理容器 weaviate-host-proxy ..."
docker rm -f weaviate-host-proxy 2>$null
Write-Host "  done."

# 2) 可选：删除知识库
if ($DeleteDataset) {
    if (-not $DatasetId) { Write-Warning "未提供 -DatasetId，跳过删除知识库"; exit 0 }
    if (-not $ApiKey) { Write-Warning "未提供 -ApiKey，跳过删除知识库"; exit 0 }
    try {
        Invoke-RestMethod -Method Delete -Uri "$BaseUrl/v1/datasets/$DatasetId" `
            -Headers @{ Authorization = "Bearer $ApiKey" } -TimeoutSec 15 | Out-Null
        Write-Host "知识库 $DatasetId 已删除（对应 Weaviate collection 已 drop）"
    } catch {
        Write-Warning "删除知识库失败：$($_.Exception.Message)"
        Write-Host "可在 UI 中手动删除，或在控制台直接删除 Weaviate collection："
        Write-Host "  Invoke-RestMethod -Method Delete -Uri '<weaviate>/v1/schema/Vector_index_<dataset_id>_Node' -Headers @{'X-API-Key'='<key>'}"
    }
}

Write-Host "清理完成。"
