param(
    [switch]$IncludePgvector,
    [switch]$IncludeJavaDependencyCheck,
    [switch]$IncludeRealLlm,
    [switch]$IncludeLinuxContainerSmoke
)

$ErrorActionPreference = "Stop"
if ($PSVersionTable.PSVersion.Major -ge 7) {
    $PSNativeCommandUseErrorActionPreference = $true
}

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot
$powerShell = (Get-Process -Id $PID).Path
if ([string]::IsNullOrWhiteSpace($env:HOST_AGENT_SHARED_TOKEN)) {
    $env:HOST_AGENT_SHARED_TOKEN = "local-mvp-" + [Guid]::NewGuid().ToString("N")
}
if ([string]::IsNullOrWhiteSpace($env:KYLIN_ENROLLMENT_TOKEN)) {
    $env:KYLIN_ENROLLMENT_TOKEN = $env:HOST_AGENT_SHARED_TOKEN
}

function Run-Step([string]$Name, [scriptblock]$Command) {
    Write-Host "==> $Name"
    & $Command
}

function Invoke-Native([string]$Name, [scriptblock]$Command) {
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE."
    }
}

function Invoke-NodeExecutorGo([scriptblock]$Command) {
    Push-Location "agents/node-executor"
    $previousGoCache = $env:GOCACHE
    try {
        if ([string]::IsNullOrWhiteSpace($env:GOCACHE)) {
            $env:GOCACHE = Join-Path (Get-Location) ".gocache"
        }
        New-Item -ItemType Directory -Path $env:GOCACHE -Force | Out-Null
        & $Command
    } finally {
        if ([string]::IsNullOrEmpty($previousGoCache)) {
            Remove-Item Env:GOCACHE -ErrorAction SilentlyContinue
        } else {
            $env:GOCACHE = $previousGoCache
        }
        Pop-Location
    }
}

Run-Step "agent runtime contracts" {
    Invoke-Native "agent runtime contracts" { bash scripts/verify-agent-contracts.sh }
}

Run-Step "backend tests" {
    Push-Location "services/backend"
    try {
        Invoke-Native "mvn test" { mvn test }
    } finally {
        Pop-Location
    }
}

Run-Step "admin-web build" {
    Push-Location "apps/admin-web"
    try {
        Invoke-Native "admin-web build" { npm run build }
    } finally {
        Pop-Location
    }
}

Run-Step "dashboard-screen build" {
    Push-Location "apps/dashboard-screen"
    try {
        Invoke-Native "dashboard-screen build" { npm run build }
    } finally {
        Pop-Location
    }
}

Run-Step "node-executor tests" {
    Invoke-NodeExecutorGo {
        Invoke-Native "go test" { go test ./... }
    }
}

Run-Step "node-executor vet" {
    Invoke-NodeExecutorGo {
        Invoke-Native "go vet" { go vet ./... }
    }
}

Run-Step "node-executor build" {
    Push-Location "agents/node-executor"
    try {
        & $powerShell -ExecutionPolicy Bypass -File .\build.ps1
    } finally {
        Pop-Location
    }
}

Run-Step "node executor verification script syntax" {
    Invoke-Native "verify-kylin-node syntax" { bash -n agents/node-executor/deploy/verify-kylin-node.sh }
    Invoke-Native "verify-kylin-runtime syntax" { bash -n agents/node-executor/deploy/verify-kylin-runtime.sh }
    Invoke-Native "verify-kylin-stability syntax" { bash -n agents/node-executor/deploy/verify-kylin-stability.sh }
    Invoke-Native "verify-linux-container-runtime-smoke syntax" { bash -n agents/node-executor/deploy/verify-linux-container-runtime-smoke.sh }
}

if ($IncludeLinuxContainerSmoke) {
    Run-Step "linux container node executor runtime smoke" {
        & $powerShell -ExecutionPolicy Bypass -File .\scripts\verify-linux-container-smoke.ps1 -IncludeRuntime
    }
}

Run-Step "llm verification script self-test" {
    Push-Location "services/backend"
    try {
        & $powerShell -ExecutionPolicy Bypass -File .\verify-llm-script.ps1
    } finally {
        Pop-Location
    }
}

if ($IncludeRealLlm) {
    Run-Step "real llm connectivity verification" {
        Push-Location "services/backend"
        try {
            & $powerShell -ExecutionPolicy Bypass -File .\verify-llm-openai-compatible.ps1
        } finally {
            Pop-Location
        }
    }
}

Run-Step "security scan" {
    if ($IncludeJavaDependencyCheck) {
        & $powerShell -ExecutionPolicy Bypass -File .\scripts\security-scan.ps1 -IncludeJavaDependencyCheck
    } else {
        & $powerShell -ExecutionPolicy Bypass -File .\scripts\security-scan.ps1
    }
}

if ($IncludePgvector) {
    Run-Step "postgres pgvector verification" {
        Push-Location "services/backend"
        try {
            & $powerShell -ExecutionPolicy Bypass -File .\verify-postgres-pgvector.ps1
        } finally {
            Pop-Location
        }
    }
}

Run-Step "git diff check" {
    Invoke-Native "git diff --check" { git diff --check }
}

Write-Host "MVP verification passed."
