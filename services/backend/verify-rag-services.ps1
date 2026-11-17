param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$Username = "admin",
    [string]$Password = "admin123"
)

$ErrorActionPreference = "Stop"

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = ""
    )

    $headers = @{}
    if ($Token) {
        $headers["Authorization"] = "Bearer $Token"
    }
    $params = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        Headers = $headers
    }
    if ($null -ne $Body) {
        $params["ContentType"] = "application/json"
        $params["Body"] = ($Body | ConvertTo-Json -Depth 8)
    }
    Invoke-RestMethod @params
}

$login = Invoke-Api -Method Post -Path "/api/v1/auth/login" -Body @{
    username = $Username
    password = $Password
}
$token = $login.data.token
if (-not $token) {
    throw "login did not return token"
}

$health = Invoke-Api -Method Get -Path "/api/v1/rag/health" -Token $token
if (-not $health.data.healthy -or -not $health.data.externalIndexEnabled) {
    throw "external RAG services are not healthy or not enabled"
}
Write-Host "RAG health passed"

$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$document = Invoke-Api -Method Post -Path "/api/v1/rag/documents" -Token $token -Body @{
    title = "RAG external acceptance $stamp"
    sourceType = "RUNBOOK"
    content = "KYSEC KACF KARMA AppArmor Elasticsearch Milvus Redis acceptance marker $stamp"
}
Write-Host "RAG document created: $($document.data.id)"

$query = Invoke-Api -Method Post -Path "/api/v1/rag/query" -Token $token -Body @{
    question = "acceptance marker $stamp Milvus Elasticsearch Redis"
}
$matchedReferenceCount = @($query.data.references | Where-Object { $_.id -eq $document.data.id }).Count
if ($matchedReferenceCount -lt 1) {
    throw "created document was not returned by RAG query"
}
Write-Host "RAG query passed"

$memory = Invoke-Api -Method Post -Path "/api/v1/rag/documents/$($document.data.id)/deposit" -Token $token
if (-not $memory.data.id) {
    throw "deposit did not create memory"
}
Write-Host "RAG deposit passed: $($memory.data.id)"

$edge = Invoke-Api -Method Post -Path "/api/v1/rag/graph-edges" -Token $token -Body @{
    sourceNode = "KYSEC"
    relation = "PROTECTS"
    targetNode = "Kylin host"
    evidence = "acceptance marker $stamp"
    sourceDocumentId = $document.data.id
}
if (-not $edge.data.id) {
    throw "graph edge was not created"
}

Write-Host "RAG services acceptance passed: document=$($document.data.id), memory=$($memory.data.id), edge=$($edge.data.id)"
