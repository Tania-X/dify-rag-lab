#requires -Version 7.0
<#
.SYNOPSIS
    阶段 3：直查 Weaviate，亲眼验证 Dify 在向量库里存了什么。
.DESCRIPTION
    输出：
      1) 全部 collection（class）列表 + 对象数
      2) 每个 collection 的属性（对应 docs/02 的 schema 设计）
      3) 每个 collection 的 1 个样本对象（chunk 正文 + doc_id + chunk_index）
    对照 docs/02 验证：
      - collection 命名 = Vector_index_{dataset_id}_Node
      - 属性含 text / document_id / doc_id / chunk_index / is_summary 等
.NOTES
    依赖：先运行 01-publish-weaviate-port.ps1（宿主 8090 端口）。
    环境变量：WEAVIATE_URL / WEAVIATE_API_KEY
#>
param(
    [string]$WeaviateUrl = $env:WEAVIATE_URL,
    [string]$ApiKey = $env:WEAVIATE_API_KEY
)
$ErrorActionPreference = 'Stop'
if (-not $WeaviateUrl) { $WeaviateUrl = 'http://localhost:8090' }
if (-not $ApiKey) { $ApiKey = 'WVF5YThaHlkYwhGUSmCRgsX3tD5ngdN8pkih' }
$headers = @{ 'X-API-Key' = $ApiKey }

# 1) schema
$schema = Invoke-RestMethod -Uri "$WeaviateUrl/v1/schema" -Headers $headers -TimeoutSec 10
if (-not $schema.classes -or $schema.classes.Count -eq 0) {
    Write-Host "向量库为空：还没有任何 collection（先执行 03-upload-document.ps1 入库）。"
    exit 0
}

Write-Host "=== Weaviate collections ==="
foreach ($c in $schema.classes) {
    $className = $c.class

    # 对象数（GraphQL Aggregate）
    $gql = @{ query = "{ Aggregate { $className { meta { count } } } }" } | ConvertTo-Json
    $agg = Invoke-RestMethod -Method Post -Uri "$WeaviateUrl/v1/graphql" -Headers $headers `
        -ContentType 'application/json' -Body $gql -TimeoutSec 10
    $count = $agg.data.Aggregate.$className[0].meta.count

    $props = ($c.properties | ForEach-Object { $_.name }) -join ', '
    Write-Host "`n--- class=$className  objects=$count ---"
    Write-Host "properties: $props"

    # 样本对象
    $g2 = @{ query = "{ Get { $className(limit: 1) { text doc_id document_id chunk_index is_summary _additional { id } } } }" } | ConvertTo-Json
    $objResp = Invoke-RestMethod -Method Post -Uri "$WeaviateUrl/v1/graphql" -Headers $headers `
        -ContentType 'application/json' -Body $g2 -TimeoutSec 10
    $objs = $objResp.data.Get.$className
    if ($objs) {
        $o = $objs[0]
        $text = [string]$o.text
        if ($text.Length -gt 100) { $text = $text.Substring(0, 100) + '...' }
        Write-Host "sample: id=$($o._additional.id)"
        Write-Host "        doc_id=$($o.doc_id) document_id=$($o.document_id) chunk_index=$($o.chunk_index) is_summary=$($o.is_summary)"
        Write-Host "        text=$text"
    }
}

Write-Host "`n提示：对象数 ≈ 文档分段数；doc_id 是 uuid5(内容) 的确定性 ID（重复内容不会重复入库）。"
