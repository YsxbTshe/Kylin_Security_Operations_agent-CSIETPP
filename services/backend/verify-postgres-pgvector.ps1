param(
    [string]$ContainerName = "kylinops-postgres-it",
    [string]$Image = "pgvector/pgvector:pg16",
    [int]$Port = 0,
    [string]$Database = "kylinops",
    [string]$Username = "kylinops",
    [string]$Password = "kylinops"
)

$ErrorActionPreference = "Stop"
if ($PSVersionTable.PSVersion.Major -ge 7) {
    $PSNativeCommandUseErrorActionPreference = $true
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

function Invoke-Native([string]$Name, [scriptblock]$Command) {
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE."
    }
}

function Invoke-NativeOutput([string]$Name, [scriptblock]$Command) {
    $output = & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE."
    }
    return $output
}

Invoke-Native "docker info" { docker info *> $null }

function Wait-Postgres {
    for ($i = 0; $i -lt 40; $i++) {
        docker exec $ContainerName pg_isready -U $Username -d $Database | Out-Null
        if ($LASTEXITCODE -eq 0) {
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "PostgreSQL container did not become ready."
}

$existing = Invoke-NativeOutput "docker ps" { docker ps -a --filter "name=^/$ContainerName$" --format "{{.Names}}" }
if ($existing -eq $ContainerName) {
    Invoke-Native "docker rm existing postgres container" { docker rm -f $ContainerName | Out-Null }
}

try {
    Invoke-Native "docker run postgres pgvector" {
        docker run --rm -d `
            --name $ContainerName `
            -e POSTGRES_DB=$Database `
            -e POSTGRES_USER=$Username `
            -e POSTGRES_PASSWORD=$Password `
            -p "${Port}:5432" `
            $Image | Out-Null
    }

    Wait-Postgres

    if ($Port -eq 0) {
        $portMapping = Invoke-NativeOutput "docker port postgres pgvector" { docker port $ContainerName 5432/tcp } | Select-Object -First 1
        $Port = [int]($portMapping -replace '^.*:', '')
    }

    $env:POSTGRES_IT_JDBC_URL = "jdbc:postgresql://127.0.0.1:${Port}/${Database}"
    $env:POSTGRES_IT_USERNAME = $Username
    $env:POSTGRES_IT_PASSWORD = $Password

    Invoke-Native "mvn postgres pgvector migration test" { mvn test "-Dtest=PostgresPgvectorMigrationIT" }

    $vectorExtension = Invoke-NativeOutput "verify pgvector extension" {
        docker exec $ContainerName psql -U $Username -d $Database -tAc "select extname from pg_extension where extname = 'vector';"
    }
    if (($vectorExtension | Select-Object -First 1).Trim() -ne "vector") {
        throw "pgvector extension was not installed."
    }

    $embeddingColumnCount = Invoke-NativeOutput "verify rag embedding column" {
        docker exec $ContainerName psql -U $Username -d $Database -tAc "select count(*) from information_schema.columns where table_name = 'app_rag_document' and column_name = 'embedding';"
    }
    if (($embeddingColumnCount | Select-Object -First 1).Trim() -ne "1") {
        throw "app_rag_document.embedding column was not created."
    }
}
finally {
    docker rm -f $ContainerName 2>$null | Out-Null
    Remove-Item Env:POSTGRES_IT_JDBC_URL -ErrorAction SilentlyContinue
    Remove-Item Env:POSTGRES_IT_USERNAME -ErrorAction SilentlyContinue
    Remove-Item Env:POSTGRES_IT_PASSWORD -ErrorAction SilentlyContinue
}
