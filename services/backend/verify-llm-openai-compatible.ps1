param(
    [string]$BaseUrl = $env:LLM_BASE_URL,
    [string]$ApiKey = $env:LLM_API_KEY,
    [string]$Model = $env:LLM_MODEL,
    [int]$TimeoutSeconds = 30
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($BaseUrl)) {
    throw "LLM_BASE_URL is required. Example: https://api.deepseek.com/v1"
}
if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    throw "LLM_API_KEY is required."
}
if ([string]::IsNullOrWhiteSpace($Model)) {
    throw "LLM_MODEL is required. Example: deepseek-chat"
}

$endpoint = $BaseUrl.TrimEnd("/")
if (-not $endpoint.EndsWith("/chat/completions")) {
    $endpoint = "$endpoint/chat/completions"
}

$body = @{
    model = $Model
    temperature = 0
    messages = @(
        @{
            role = "user"
            content = "Reply with exactly: OK"
        }
    )
} | ConvertTo-Json -Depth 5

$response = Invoke-RestMethod `
    -Method Post `
    -Uri $endpoint `
    -Headers @{
        "Authorization" = "Bearer $ApiKey"
        "Content-Type" = "application/json"
    } `
    -Body $body `
    -TimeoutSec $TimeoutSeconds

$content = $response.choices[0].message.content
if ([string]::IsNullOrWhiteSpace($content)) {
    throw "LLM returned an empty response."
}

Write-Host "LLM connectivity verified. Model returned non-empty content."
