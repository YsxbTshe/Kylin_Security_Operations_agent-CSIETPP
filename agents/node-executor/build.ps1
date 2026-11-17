param(
    [string]$OutputDir = "dist",
    [string[]]$Targets = @("linux-amd64", "linux-arm64", "linux-loong64"),
    [string]$BinaryName = "kylin-node-executor"
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

$previousGoCache = $env:GOCACHE
if ([string]::IsNullOrWhiteSpace($env:GOCACHE)) {
    $env:GOCACHE = Join-Path $scriptDir ".gocache"
}
New-Item -ItemType Directory -Path $env:GOCACHE -Force | Out-Null

$goCommand = Get-Command go -ErrorAction SilentlyContinue
if ($null -ne $goCommand) {
    $goExe = $goCommand.Source
} elseif (Test-Path "C:\Program Files\Go\bin\go.exe") {
    $goExe = "C:\Program Files\Go\bin\go.exe"
} else {
    throw "Go executable was not found. Please add Go to PATH or install it to C:\Program Files\Go."
}

$goVersionOutput = & $goExe version
if ($goVersionOutput -notmatch "go(\d+)\.(\d+)\.(\d+)") {
    throw "Unable to parse Go version: $goVersionOutput"
}

$goVersion = [version]::new([int]$Matches[1], [int]$Matches[2], [int]$Matches[3])
$minimumGoVersion = [version]::new(1, 26, 3)
if ($goVersion -lt $minimumGoVersion) {
    throw "Go $minimumGoVersion or newer is required because earlier Go 1.26 standard libraries have known vulnerabilities. Current version: $goVersionOutput"
}

$targetMatrix = @{
    "linux-amd64" = @{ GOOS = "linux"; GOARCH = "amd64"; GOAMD64 = "v1" }
    "linux-arm64" = @{ GOOS = "linux"; GOARCH = "arm64" }
    "linux-loong64" = @{ GOOS = "linux"; GOARCH = "loong64" }
}

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

$originalEnv = @{
    CGO_ENABLED = $env:CGO_ENABLED
    GOOS        = $env:GOOS
    GOARCH      = $env:GOARCH
    GOAMD64     = $env:GOAMD64
    GOCACHE     = $previousGoCache
}

try {
    foreach ($target in $Targets) {
        if (-not $targetMatrix.ContainsKey($target)) {
            throw "Unsupported target: $target"
        }

        $config = $targetMatrix[$target]
        $env:CGO_ENABLED = "0"
        $env:GOOS = $config.GOOS
        $env:GOARCH = $config.GOARCH

        if ($config.ContainsKey("GOAMD64")) {
            $env:GOAMD64 = $config.GOAMD64
        } else {
            Remove-Item Env:GOAMD64 -ErrorAction SilentlyContinue
        }

        $outputPath = Join-Path $OutputDir ("{0}-{1}" -f $BinaryName, $target)
        Write-Host ("Building {0}" -f $target)
        & $goExe build -trimpath -ldflags="-s -w" -o $outputPath ./cmd/node-executor
        if ($LASTEXITCODE -ne 0) {
            throw "go build failed for $target with exit code $LASTEXITCODE"
        }
    }
}
finally {
    foreach ($name in $originalEnv.Keys) {
        $value = $originalEnv[$name]
        if ([string]::IsNullOrEmpty($value)) {
            Remove-Item ("Env:{0}" -f $name) -ErrorAction SilentlyContinue
        } else {
            Set-Item ("Env:{0}" -f $name) $value
        }
    }
}
