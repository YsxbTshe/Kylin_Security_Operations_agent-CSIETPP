param(
    [string]$Image = "bash:5.2",
    [switch]$IncludeRuntime,
    [string]$NodeExecutorToken = "docker-runtime-smoke-token"
)

$ErrorActionPreference = "Stop"
if ($PSVersionTable.PSVersion.Major -ge 7) {
    $PSNativeCommandUseErrorActionPreference = $true
}

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

function Invoke-Native([string]$Name, [scriptblock]$Command) {
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE."
    }
}

$nodeExecutorDir = Join-Path $repoRoot "agents/node-executor"
if ($IncludeRuntime) {
    Invoke-Native "node executor linux-amd64 build" {
        powershell -ExecutionPolicy Bypass -File (Join-Path $nodeExecutorDir "build.ps1") -Targets linux-amd64
    }
}

$linuxAmd64Binary = Join-Path $nodeExecutorDir "dist/kylin-node-executor-linux-amd64"
if (-not (Test-Path -LiteralPath $linuxAmd64Binary)) {
    throw "Missing linux-amd64 Node Executor binary. Run agents/node-executor/build.ps1 first."
}

Invoke-Native "docker info" { docker info *> $null }

$mountPath = ($nodeExecutorDir -replace "\\", "/")
Invoke-Native "linux container smoke" {
    docker run --rm `
        -v "${mountPath}:/work" `
        -w /work `
        $Image `
        bash -lc "set -euo pipefail; chmod +x dist/kylin-node-executor-linux-amd64 deploy/verify-kylin-node.sh deploy/verify-kylin-runtime.sh deploy/verify-kylin-stability.sh deploy/verify-linux-container-runtime-smoke.sh; ./dist/kylin-node-executor-linux-amd64 --help >/tmp/node-executor-help.txt 2>&1 || true; test -s /tmp/node-executor-help.txt; bash -n deploy/verify-kylin-node.sh; bash -n deploy/verify-kylin-runtime.sh; bash -n deploy/verify-kylin-stability.sh; bash -n deploy/verify-linux-container-runtime-smoke.sh"
}

Write-Host "Linux container smoke verification passed."

if (-not $IncludeRuntime) {
    return
}

$backendDir = Join-Path $repoRoot "services/backend"
$port = Get-Random -Minimum 21000 -Maximum 29999
$stdoutLog = Join-Path $backendDir "target/docker-runtime-backend-$port.out.log"
$stderrLog = Join-Path $backendDir "target/docker-runtime-backend-$port.err.log"
$nodeExecutorStdoutLog = Join-Path $backendDir "target/docker-runtime-daemon-$port.out.log"
$nodeExecutorStderrLog = Join-Path $backendDir "target/docker-runtime-daemon-$port.err.log"
$nodeId = "docker-linux-smoke-$port"
$adminPassword = if ($env:DEMO_ADMIN_PASSWORD) { $env:DEMO_ADMIN_PASSWORD } else { "admin123" }

$env:HOST_AGENT_SHARED_TOKEN = $NodeExecutorToken
$env:APP_HOST_AGENT_SHARED_TOKEN = $NodeExecutorToken
$env:APP_SCREEN_SHARED_TOKEN = "docker-runtime-screen-token"
$backendProcess = Start-Process `
    -FilePath "mvn" `
    -ArgumentList @(
        "spring-boot:run",
        "-Dspring-boot.run.useTestClasspath=true",
        "-Dspring-boot.run.profiles=test",
        "-Dspring-boot.run.arguments=--server.port=$port"
    ) `
    -WorkingDirectory $backendDir `
    -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog `
    -WindowStyle Hidden `
    -PassThru

try {
    $deadline = (Get-Date).AddSeconds(90)
    do {
        Start-Sleep -Seconds 2
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/v1/system/health" -Method Get -TimeoutSec 2
            if ($health.success -eq $true -and $health.data.status -eq "UP") {
                break
            }
        } catch {
            if ($backendProcess.HasExited) {
                Get-Content $stdoutLog -Tail 100
                Get-Content $stderrLog -Tail 100
                throw "Backend process exited before health check. ExitCode=$($backendProcess.ExitCode)"
            }
        }
    } while ((Get-Date) -lt $deadline)

    if ((Get-Date) -ge $deadline) {
        Get-Content $stdoutLog -Tail 100
        Get-Content $stderrLog -Tail 100
        throw "Timed out waiting for backend health."
    }

    $nodeExecutorProcess = Start-Process `
        -FilePath "docker" `
        -ArgumentList @(
            "run",
            "--rm",
            "-v", "`"${mountPath}:/work`"",
            "-w", "/work",
            "-e", "KYLIN_ENROLLMENT_TOKEN=$NodeExecutorToken",
            "-e", "KYLIN_CONTROL_PLANE_URL=http://host.docker.internal:$port",
            "-e", "KYLIN_NODE_ID=$nodeId",
            "-e", "KYLIN_HEARTBEAT_PERIOD=2s",
            $Image,
            "bash",
            "-lc",
            "`"set -euo pipefail; chmod +x dist/kylin-node-executor-linux-amd64; timeout 90s ./dist/kylin-node-executor-linux-amd64`""
        ) `
        -WorkingDirectory $nodeExecutorDir `
        -RedirectStandardOutput $nodeExecutorStdoutLog `
        -RedirectStandardError $nodeExecutorStderrLog `
        -WindowStyle Hidden `
        -PassThru

    try {
        $deadline = (Get-Date).AddSeconds(45)
        do {
            Start-Sleep -Seconds 1
            $registered = (Select-String -Path $nodeExecutorStdoutLog -Pattern "node executor enrolled with control plane" -Quiet -ErrorAction SilentlyContinue) `
                -or (Select-String -Path $nodeExecutorStderrLog -Pattern "node executor enrolled with control plane" -Quiet -ErrorAction SilentlyContinue)
            if ($registered) {
                break
            }
            if ($nodeExecutorProcess.HasExited) {
                Get-Content $nodeExecutorStdoutLog -Tail 100 -ErrorAction SilentlyContinue
                Get-Content $nodeExecutorStderrLog -Tail 100 -ErrorAction SilentlyContinue
                throw "Node Executor container exited before registration. ExitCode=$($nodeExecutorProcess.ExitCode)"
            }
        } while ((Get-Date) -lt $deadline)

        if ((Get-Date) -ge $deadline) {
            Get-Content $nodeExecutorStdoutLog -Tail 100 -ErrorAction SilentlyContinue
            Get-Content $nodeExecutorStderrLog -Tail 100 -ErrorAction SilentlyContinue
            throw "Timed out waiting for Node Executor registration."
        }

        $login = Invoke-RestMethod `
            -Uri "http://127.0.0.1:$port/api/v1/auth/login" `
            -Method Post `
            -ContentType "application/json" `
            -Body (@{
                username = "admin"
                password = $adminPassword
            } | ConvertTo-Json -Compress) `
            -TimeoutSec 5
        if ($login.success -ne $true -or [string]::IsNullOrWhiteSpace($login.data.token)) {
            throw "Admin login failed during runtime smoke."
        }

        $headers = @{ Authorization = "Bearer $($login.data.token)" }
        $hosts = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/v1/hosts" -Headers $headers -TimeoutSec 5
        $hostItem = @($hosts.data | Where-Object { $_.nodeId -eq $nodeId } | Select-Object -First 1)
        if (-not $hostItem) {
            throw "Registered Node Executor node was not visible via /api/v1/hosts."
        }
        if (-not (@($hostItem.supportedTools) -contains "config_permissions_snapshot")) {
            $reportedTools = @($hostItem.supportedTools) -join ","
            throw "Registered Node Executor did not report config_permissions_snapshot. tools=$reportedTools"
        }

        $task = Invoke-RestMethod `
            -Uri "http://127.0.0.1:$port/api/v1/host-agent/tasks" `
            -Method Post `
            -Headers $headers `
            -ContentType "application/json" `
            -Body (@{
                targetHost = $nodeId
                prompt = "container smoke: run config_permissions_snapshot and verify Node Executor executor loop"
                executionMode = "react"
            } | ConvertTo-Json -Compress) `
            -TimeoutSec 5
        if ($task.success -ne $true -or [string]::IsNullOrWhiteSpace($task.data.taskId)) {
            throw "Node Executor task creation failed during runtime smoke."
        }

        $taskId = $task.data.taskId
        Write-Host "Linux container runtime task created. taskId=$taskId targetNodeId=$nodeId"
        $createdDetail = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/v1/host-agent/tasks/$taskId" -Headers $headers -TimeoutSec 5
        if ($createdDetail.success -ne $true -or $createdDetail.data.targetHost -ne $nodeId) {
            throw "Node Executor task target mismatch during runtime smoke."
        }

        $deadline = (Get-Date).AddSeconds(90)
        do {
            Start-Sleep -Seconds 2
            $detail = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/v1/host-agent/tasks/$taskId" -Headers $headers -TimeoutSec 5
            $toolTurn = @($detail.data.turns | Where-Object { $_.role -eq "tool" -and $_.turnType -eq "tool_observation" -and $_.toolName -eq "config_permissions_snapshot" })
            $executionTrace = @($detail.data.traces | Where-Object { $_.stage -eq "execution" -and $_.toolName -eq "config_permissions_snapshot" -and $_.status -eq "COMPLETED" })
            if ($detail.data.status -eq "WAITING_USER" -and $toolTurn.Count -gt 0 -and $executionTrace.Count -gt 0) {
                Write-Host "Linux container runtime task loop passed. taskId=$taskId"
                break
            }
            if ($nodeExecutorProcess.HasExited) {
                $lastStatus = if ($detail -and $detail.data) { $detail.data.status } else { "<missing>" }
                $lastTurns = if ($detail -and $detail.data -and $detail.data.turns) {
                    @($detail.data.turns | Select-Object role, turnType, toolName, status)
                } else {
                    @()
                }
                $lastTraces = if ($detail -and $detail.data -and $detail.data.traces) {
                    @($detail.data.traces | Select-Object actor, stage, toolName, status)
                } else {
                    @()
                }
                Write-Host "Last task status before daemon exit: $lastStatus"
                Write-Host "Last task turns:"
                $lastTurns | Format-Table | Out-String | Write-Host
                Write-Host "Last task traces:"
                $lastTraces | Format-Table | Out-String | Write-Host
                Get-Content $nodeExecutorStdoutLog -Tail 100 -ErrorAction SilentlyContinue
                Get-Content $nodeExecutorStderrLog -Tail 100 -ErrorAction SilentlyContinue
                throw "Node Executor container exited before task loop evidence was recorded. ExitCode=$($nodeExecutorProcess.ExitCode)"
            }
        } while ((Get-Date) -lt $deadline)

        if ((Get-Date) -ge $deadline) {
            $lastStatus = if ($detail -and $detail.data) { $detail.data.status } else { "<missing>" }
            $lastTurns = if ($detail -and $detail.data -and $detail.data.turns) {
                @($detail.data.turns | Select-Object role, turnType, toolName, status)
            } else {
                @()
            }
            $lastTraces = if ($detail -and $detail.data -and $detail.data.traces) {
                @($detail.data.traces | Select-Object actor, stage, toolName, status)
            } else {
                @()
            }
            Write-Host "Last task status: $lastStatus"
            Write-Host "Last task turns:"
            $lastTurns | Format-Table | Out-String | Write-Host
            Write-Host "Last task traces:"
            $lastTraces | Format-Table | Out-String | Write-Host
            Get-Content $nodeExecutorStdoutLog -Tail 100 -ErrorAction SilentlyContinue
            Get-Content $nodeExecutorStderrLog -Tail 100 -ErrorAction SilentlyContinue
            throw "Timed out waiting for Node Executor task loop evidence."
        }

        if (-not $nodeExecutorProcess.HasExited) {
            Wait-Process -Id $nodeExecutorProcess.Id -Timeout 20 -ErrorAction SilentlyContinue
        }
        if (-not $nodeExecutorProcess.HasExited) {
            Stop-Process -Id $nodeExecutorProcess.Id -Force -ErrorAction SilentlyContinue
        }
    } finally {
        if ($nodeExecutorProcess -and -not $nodeExecutorProcess.HasExited) {
            Stop-Process -Id $nodeExecutorProcess.Id -Force -ErrorAction SilentlyContinue
        }
    }

    Invoke-Native "linux container runtime postcheck" {
        docker run --rm `
            -v "${mountPath}:/work" `
            -w /work `
            -e "KYLIN_ENROLLMENT_TOKEN=$NodeExecutorToken" `
            -e "KYLIN_CONTROL_PLANE_URL=http://host.docker.internal:$port" `
            -e "KYLIN_NODE_ID=$nodeId-postcheck" `
            -e "KYLIN_HEARTBEAT_PERIOD=5s" `
            $Image `
            bash deploy/verify-linux-container-runtime-smoke.sh
    }
} finally {
    if ($nodeExecutorProcess -and -not $nodeExecutorProcess.HasExited) {
        Stop-Process -Id $nodeExecutorProcess.Id -Force -ErrorAction SilentlyContinue
    }
    if ($backendProcess -and -not $backendProcess.HasExited) {
        Stop-Process -Id $backendProcess.Id -Force -ErrorAction SilentlyContinue
    }
    try {
        $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($listener -and $listener.OwningProcess) {
            Stop-Process -Id $listener.OwningProcess -Force -ErrorAction SilentlyContinue
        }
    } catch {
        # Ignore cleanup best-effort failures.
    }
}
