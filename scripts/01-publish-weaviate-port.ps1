#requires -Version 7.0
<#
.SYNOPSIS
    阶段 0.3：把 Weaviate 端口发布到宿主机 127.0.0.1:8090。
.DESCRIPTION
    原理：在 docker_default 网络里跑一个 alpine/socat 透明代理，
    把 weaviate:8080 映射到宿主 8090。不修改、不重启任何 Dify 容器。
.NOTES
    需要 docker 可用。重复执行幂等。
#>
$ErrorActionPreference = 'Stop'
$proxyName = 'weaviate-host-proxy'
$hostPort = 8090

$existing = docker ps -a --filter "name=^/${proxyName}$" --format '{{.Names}}'
if ($existing) {
    Write-Host "代理容器已存在，直接启动: $proxyName"
    docker start $proxyName | Out-Null
} else {
    Write-Host "创建代理容器 $proxyName (socat) ..."
    docker run -d --name $proxyName --restart unless-stopped `
        --network docker_default `
        -p "127.0.0.1:${hostPort}:8080" `
        alpine/socat "TCP-LISTEN:${hostPort},fork,reuseaddr" "TCP:weaviate:8080" | Out-Null
}

# 验证连通性
Start-Sleep -Seconds 2
try {
    $meta = Invoke-RestMethod -Uri "http://127.0.0.1:${hostPort}/v1/meta" -TimeoutSec 5
    Write-Host "OK: Weaviate 已可通过宿主访问 http://127.0.0.1:${hostPort} (version=$($meta.version))"
} catch {
    Write-Warning "代理已启动，但 Weaviate 未响应：$($_.Exception.Message)"
    Write-Warning "请确认 Dify 容器组在运行（docker ps 应能看到 docker-weaviate-1）。"
}
