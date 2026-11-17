param(
    [int]$Port = 0
)

$ErrorActionPreference = "Stop"

$scriptPath = Join-Path $PSScriptRoot "verify-llm-openai-compatible.ps1"
if ($Port -eq 0) {
    $probe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $probe.Start()
    $Port = $probe.LocalEndpoint.Port
    $probe.Stop()
}

$prefix = "http://127.0.0.1:$Port/"
$counterPath = Join-Path ([System.IO.Path]::GetTempPath()) ("kylin-llm-mock-$PID-$Port.count")
Set-Content -Path $counterPath -Value "0"

$mockJob = Start-Job -ArgumentList $prefix, $counterPath -ScriptBlock {
    param($Prefix, $CounterPath)
    $ErrorActionPreference = "Stop"
    $listener = [System.Net.HttpListener]::new()
    $listener.Prefixes.Add($Prefix)
    $listener.Start()
    try {
        $running = $true
        while ($listener.IsListening -and $running) {
            $context = $listener.GetContext()
            $request = $context.Request
            $response = $context.Response

            if ($request.HttpMethod -eq "GET" -and $request.Url.AbsolutePath -eq "/__shutdown") {
                $response.StatusCode = 204
                $response.Close()
                $running = $false
                continue
            }
            if ($request.HttpMethod -ne "POST" -or $request.Url.AbsolutePath -ne "/chat/completions") {
                $response.StatusCode = 404
                $response.Close()
                continue
            }
            if ($request.Headers["Authorization"] -ne "Bearer test-key") {
                $response.StatusCode = 401
                $response.Close()
                continue
            }

            $reader = [System.IO.StreamReader]::new($request.InputStream, $request.ContentEncoding)
            $body = $reader.ReadToEnd()
            $reader.Dispose()
            if ($body -notmatch '"model"\s*:\s*"test-model"') {
                $response.StatusCode = 400
                $response.Close()
                continue
            }

            Set-Content -Path $CounterPath -Value "1"
            $payload = '{"choices":[{"message":{"content":"OK"}}]}'
            $bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
            $response.ContentType = "application/json"
            $response.ContentLength64 = $bytes.Length
            $response.OutputStream.Write($bytes, 0, $bytes.Length)
            $response.Close()
        }
    } finally {
        $listener.Stop()
        $listener.Close()
    }
}

try {
    Start-Sleep -Milliseconds 500
    if ($mockJob.State -ne "Running") {
        Receive-Job $mockJob
        throw "Mock LLM server did not start."
    }

    & $scriptPath -BaseUrl $prefix -ApiKey "test-key" -Model "test-model" -TimeoutSeconds 5
    $requestCount = (Get-Content -Raw $counterPath).Trim()
    if ($requestCount -ne "1") {
        throw "Expected mock LLM request count to be 1, got $requestCount."
    }

    $failedAsExpected = $false
    try {
        & $scriptPath -BaseUrl $prefix -ApiKey "" -Model "test-model" -TimeoutSeconds 5
    } catch {
        if ($_.Exception.Message -like "*LLM_API_KEY is required*") {
            $failedAsExpected = $true
        } else {
            throw
        }
    }
    if (-not $failedAsExpected) {
        throw "Missing API key validation did not fail."
    }

    Write-Host "LLM verification script self-test passed."
} finally {
    try {
        Invoke-WebRequest -Uri "$prefix`__shutdown" -UseBasicParsing -TimeoutSec 2 | Out-Null
        Wait-Job $mockJob -Timeout 2 | Out-Null
    } catch {
    }
    if ($mockJob.State -eq "Running") {
        Stop-Job $mockJob -ErrorAction SilentlyContinue
    }
    Remove-Job $mockJob -Force -ErrorAction SilentlyContinue
    Remove-Item $counterPath -ErrorAction SilentlyContinue
}
