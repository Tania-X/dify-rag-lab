#requires -Version 7.0
<#
.SYNOPSIS
    引导工具：从同机部署的 Dify api 容器读取 WEAVIATE_API_KEY 并注入当前会话。
.DESCRIPTION
    适用场景：网关与 Dify 部署在同一台机器（docker 可达），需要直查向量库，
    但不想手工复制密钥。本脚本从 Dify api 容器环境变量读取该 Key。
    注意：
      - 仅用于“同机引导”，生产建议走密钥管理/同一 .env 注入；
      - 不修改任何容器，只读环境变量；
      - Dify 无 API 暴露该凭据，这是唯一“自动获取”的途径之一。
.NOTES
    环境变量：无（参数指定容器名，默认 docker-api-1）
#>
param(
    [string]$ContainerName = 'docker-api-1'
)
$ErrorActionPreference = 'Stop'

$key = docker exec $ContainerName printenv WEAVIATE_API_KEY 2>$null
if (-not $key) {
    throw "未能在容器 $ContainerName 中找到 WEAVIATE_API_KEY。请确认：该容器是 Dify api 容器，且 .env 配置了 VECTOR_STORE=weaviate。"
}

# 注入当前 PowerShell 会话（供后续启动容器/脚本使用）
$env:WEAVIATE_API_KEY = $key
Write-Host "已从容器 $ContainerName 读取 WEAVIATE_API_KEY 并注入当前会话：$key"
Write-Host "提示：仅用于同机引导；生产环境请从密钥管理/同一 .env 注入。"
