param(
    [switch]$IncludeJavaDependencyCheck
)

$ErrorActionPreference = "Stop"
if ($PSVersionTable.PSVersion.Major -ge 7) {
    $PSNativeCommandUseErrorActionPreference = $true
}

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

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

function Invoke-NpmAudit([string]$Path) {
    Push-Location $Path
    try {
        for ($attempt = 1; $attempt -le 3; $attempt++) {
            try {
                npm audit --audit-level=moderate --registry=https://registry.npmjs.org
                if ($LASTEXITCODE -eq 0) {
                    return
                }
                throw "npm audit failed with exit code $LASTEXITCODE."
            } catch {
                if ($attempt -eq 3) {
                    throw
                }
                Write-Warning "npm audit failed on attempt $attempt; retrying."
                Start-Sleep -Seconds (5 * $attempt)
            }
        }
    } finally {
        Pop-Location
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

function Invoke-GoWithRepoCache([scriptblock]$Command) {
    $previousGoCache = $env:GOCACHE
    try {
        if ([string]::IsNullOrWhiteSpace($env:GOCACHE)) {
            $env:GOCACHE = Join-Path $repoRoot ".gocache"
        }
        New-Item -ItemType Directory -Path $env:GOCACHE -Force | Out-Null
        & $Command
    } finally {
        if ([string]::IsNullOrEmpty($previousGoCache)) {
            Remove-Item Env:GOCACHE -ErrorAction SilentlyContinue
        } else {
            $env:GOCACHE = $previousGoCache
        }
    }
}

Run-Step "admin-web npm audit" {
    Invoke-NpmAudit "apps/admin-web"
}

Run-Step "dashboard-screen npm audit" {
    Invoke-NpmAudit "apps/dashboard-screen"
}

Run-Step "backend OSV SBOM scan" {
    Push-Location "services/backend"
    try {
        Invoke-Native "CycloneDX SBOM generation" {
            mvn org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom -DskipTests -DoutputFormat=json -DoutputName=bom
        }
        $osvScanner = Get-Command osv-scanner -ErrorAction SilentlyContinue
        if ($null -eq $osvScanner) {
            Invoke-GoWithRepoCache {
                Invoke-Native "osv-scanner install" { go install github.com/google/osv-scanner/v2/cmd/osv-scanner@latest }
            }
            $env:Path = "$env:USERPROFILE\go\bin;$env:Path"
        }
        Invoke-Native "OSV SBOM scan" { osv-scanner scan source --sbom target/bom.json }
    } finally {
        Pop-Location
    }
}

Run-Step "node-executor govulncheck" {
    Invoke-NodeExecutorGo {
        $govulncheck = Get-Command govulncheck -ErrorAction SilentlyContinue
        if ($null -eq $govulncheck) {
            Invoke-Native "govulncheck install" { go install golang.org/x/vuln/cmd/govulncheck@latest }
            $env:Path = "$env:USERPROFILE\go\bin;$env:Path"
        }
        Invoke-Native "govulncheck" { govulncheck ./... }
    }
}

Run-Step "risk keyword scan" {
    $useNativeStrict = $false
    if ($PSVersionTable.PSVersion.Major -ge 7) {
        $useNativeStrict = $PSNativeCommandUseErrorActionPreference
        $PSNativeCommandUseErrorActionPreference = $false
    }
    try {
        $matches = & rg -n 'TODO|FIXME|not implemented|panic\(|eval\(|innerHTML|permitAll|password\s*=\s*["''][^"'']{4,}|apiKey\s*[:=]\s*["''][^"'']{4,}|secret\s*[:=]\s*["''][^"'']{4,}|静态演示|假数据|伪装' `
            services/backend/src/main `
            agents/node-executor `
            apps/admin-web/src `
            apps/dashboard-screen/src `
            scripts `
            --glob '!**/*_test.go' `
            --glob '!**/*.md' `
            --glob '!scripts/security-scan.ps1' `
            -S
        $seedHashAllowlist = @(
            'V1__init_schema.sql',
            'V16__upgrade_seed_password_hashes.sql',
            'V37__use_sm3_password_hashes.sql'
        )
        $effectiveMatches = @($matches | Where-Object {
                $line = $_
                if ($line -notmatch "password\s*=\s*'(pbkdf2_sm3\$\d+\$|pbkdf2_sha256\$\d+\$|[0-9a-f]{64})") {
                    return $true
                }
                foreach ($allowed in $seedHashAllowlist) {
                    if ($line -match [regex]::Escape($allowed)) {
                        return $false
                    }
                }
                return $true
            })
        if ($LASTEXITCODE -eq 0) {
            if ($effectiveMatches.Count -gt 0) {
                $effectiveMatches
                throw "Risk keyword scan found matches."
            }
            return
        }
        if ($LASTEXITCODE -ne 1) {
            throw "Risk keyword scan failed."
        }
    } finally {
        if ($PSVersionTable.PSVersion.Major -ge 7) {
            $PSNativeCommandUseErrorActionPreference = $useNativeStrict
        }
    }
    $global:LASTEXITCODE = 0
}

if ($IncludeJavaDependencyCheck) {
    Run-Step "backend OWASP Dependency-Check" {
        Push-Location "services/backend"
        try {
            Invoke-Native "OWASP Dependency-Check" {
                mvn org.owasp:dependency-check-maven:check -Dformat=HTML -DfailBuildOnCVSS=7
            }
        } finally {
            Pop-Location
        }
    }
}
