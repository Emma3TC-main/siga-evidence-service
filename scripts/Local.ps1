param([ValidateSet('Init','Start','Stop','Status')][string]$Action = 'Status')

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$infra = [IO.Path]::GetFullPath((Join-Path $root '../siga-infrastructure'))
$local = Join-Path $root '.local'
$configPath = Join-Path $local 'evidence.json'
$minioAccessKey = 'siga-evidence-local'
$minioPolicy = 'siga-evidence-rw'
$bucket = 'siga-evidences'
$compose = @(
    'compose', '--project-name', 'siga-local',
    '--env-file', (Join-Path $infra 'compose/.env.local'),
    '-f', (Join-Path $infra 'compose/compose.local.yml')
)

function Assert-ExitCode {
    if ($LASTEXITCODE -ne 0) {
        throw 'Command failed; existing data and credentials were preserved.'
    }
}

function Assert-ExistingStack {
    docker @compose config --quiet
    Assert-ExitCode

    foreach ($containerName in @('siga-local-postgres-1', 'siga_rabbitmq', 'siga_minio')) {
        $container = docker inspect $containerName | ConvertFrom-Json
        Assert-ExitCode
        if ($container[0].Config.Labels.'com.docker.compose.project' -ne 'siga-local') {
            throw "Unexpected Compose ownership for $containerName."
        }
        if ($container[0].State.Status -ne 'running') {
            throw "$containerName is not running."
        }
    }
}

function Invoke-AdminSql([string]$query) {
    $query | docker exec -i siga-local-postgres-1 psql -X -v ON_ERROR_STOP=1 -U postgres -d siga
    Assert-ExitCode
}

function Read-InfraEnvironment {
    $values = @{}
    foreach ($line in Get-Content (Join-Path $infra 'compose/.env.local')) {
        if ($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
            $values[$Matches[1]] = $Matches[2]
        }
    }
    return $values
}

function New-RandomHex([int]$byteCount) {
    $bytes = New-Object byte[] $byteCount
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}

function Import-EvidenceEnvironment {
    if (!(Test-Path $configPath)) {
        throw 'Run scripts/Local.ps1 -Action Init first.'
    }

    $config = Get-Content $configPath -Raw | ConvertFrom-Json
    if ($config.dbPassword -notmatch '^[a-f0-9]{64}$' -or
        $config.minioSecretKey -notmatch '^[a-f0-9]{64}$') {
        throw 'Invalid stored Evidence configuration.'
    }

    $infraEnvironment = Read-InfraEnvironment
    if (!$infraEnvironment['LOCAL_RABBITMQ_USER'] -or !$infraEnvironment['LOCAL_RABBITMQ_PASSWORD']) {
        throw 'RabbitMQ credentials are missing from the existing local Compose configuration.'
    }

    $env:SPRING_PROFILES_ACTIVE = 'local'
    $env:EVIDENCE_PORT = '8084'
    $env:EVIDENCE_DB_URL = "jdbc:postgresql://127.0.0.1:$($config.dbPort)/siga"
    $env:EVIDENCE_DB_USER = 'siga_evidence'
    $env:EVIDENCE_DB_PASSWORD = $config.dbPassword
    $env:EVIDENCE_RABBIT_HOST = '127.0.0.1'
    $env:EVIDENCE_RABBIT_PORT = '5672'
    $env:EVIDENCE_RABBIT_USER = $infraEnvironment['LOCAL_RABBITMQ_USER']
    $env:EVIDENCE_RABBIT_PASSWORD = $infraEnvironment['LOCAL_RABBITMQ_PASSWORD']
    $env:EVIDENCE_MINIO_ENDPOINT = 'http://127.0.0.1:9000'
    $env:EVIDENCE_MINIO_ACCESS_KEY = $minioAccessKey
    $env:EVIDENCE_MINIO_SECRET_KEY = $config.minioSecretKey
    $env:EVIDENCE_MINIO_BUCKET = $bucket
    $env:EVIDENCE_JWKS_URI = 'http://127.0.0.1:8081/.well-known/jwks.json'
    $env:EVIDENCE_JWT_ISSUER = 'siga-identity'
    $env:EVIDENCE_JWT_AUDIENCE = 'siga-api'
    $env:EVIDENCE_INVENTORY_URL = 'http://127.0.0.1:8083'
}

function Invoke-MinioRoot([string]$command) {
    docker exec siga_minio sh -c ('export MC_HOST_local="http://${MINIO_ROOT_USER}:${MINIO_ROOT_PASSWORD}@127.0.0.1:9000"; ' + $command)
    Assert-ExitCode
}

Push-Location $root
try {
    switch ($Action) {
        'Init' {
            Assert-ExistingStack
            New-Item -ItemType Directory -Force $local | Out-Null

            if (!(Test-Path $configPath)) {
                $existingDatabase = @'
SELECT
    (SELECT count(*) FROM pg_roles WHERE rolname = 'siga_evidence')
  + (SELECT count(*) FROM pg_namespace WHERE nspname = 'evidence');
'@ | docker exec -i siga-local-postgres-1 psql -X -At -v ON_ERROR_STOP=1 -U postgres -d siga
                Assert-ExitCode

                $existingMinioUser = $true
                docker exec siga_minio sh -c 'export MC_HOST_local="http://${MINIO_ROOT_USER}:${MINIO_ROOT_PASSWORD}@127.0.0.1:9000"; mc admin user info local siga-evidence-local >/dev/null 2>&1'
                if ($LASTEXITCODE -ne 0) { $existingMinioUser = $false }

                if ([int]$existingDatabase -ne 0 -or $existingMinioUser) {
                    throw 'Evidence resources exist without their local credentials. Recover them; do not rotate automatically.'
                }

                $port = (docker inspect siga-local-postgres-1 | ConvertFrom-Json)[0].HostConfig.PortBindings.'5432/tcp'[0].HostPort
                Assert-ExitCode
                @{
                    dbPort = $port
                    dbPassword = New-RandomHex 32
                    minioSecretKey = New-RandomHex 32
                } | ConvertTo-Json | Set-Content $configPath
            }

            $config = Get-Content $configPath -Raw | ConvertFrom-Json
            if ($config.dbPassword -notmatch '^[a-f0-9]{64}$' -or
                $config.minioSecretKey -notmatch '^[a-f0-9]{64}$') {
                throw 'Invalid stored Evidence configuration.'
            }

            $sql = @'
SELECT format(
    'CREATE ROLE siga_evidence LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD %L',
    '__PASSWORD__'
) WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'siga_evidence')
\gexec
GRANT CONNECT ON DATABASE siga TO siga_evidence;
SELECT 'CREATE SCHEMA evidence AUTHORIZATION siga_evidence'
WHERE NOT EXISTS (SELECT FROM pg_namespace WHERE nspname = 'evidence')
\gexec
REVOKE ALL ON SCHEMA evidence FROM PUBLIC;
'@
            Invoke-AdminSql ($sql.Replace('__PASSWORD__', $config.dbPassword))

            Invoke-MinioRoot "mc mb --ignore-existing local/$bucket"
            Invoke-MinioRoot "mc anonymous set none local/$bucket"

            $policyDocument = @'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetBucketLocation", "s3:GetBucketPolicy", "s3:ListBucket"],
      "Resource": ["arn:aws:s3:::__BUCKET__"]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
      "Resource": ["arn:aws:s3:::__BUCKET__/*"]
    }
  ]
}
'@
            $policyDocument = $policyDocument.Replace('__BUCKET__', $bucket)
            $policyPath = Join-Path $local 'evidence-minio-policy.json'
            [IO.File]::WriteAllText($policyPath, $policyDocument, [Text.UTF8Encoding]::new($false))
            docker cp $policyPath 'siga_minio:/tmp/siga-evidence-policy.json'
            Assert-ExitCode
            try {
                Invoke-MinioRoot "mc admin policy create local $minioPolicy /tmp/siga-evidence-policy.json"
            } finally {
                docker exec siga_minio rm -f /tmp/siga-evidence-policy.json | Out-Null
                Remove-Item -LiteralPath $policyPath -Force -ErrorAction SilentlyContinue
            }

            docker exec -e "EVIDENCE_LOCAL_SECRET=$($config.minioSecretKey)" siga_minio sh -c ('export MC_HOST_local="http://${MINIO_ROOT_USER}:${MINIO_ROOT_PASSWORD}@127.0.0.1:9000"; ' +
                'mc admin user info local siga-evidence-local >/dev/null 2>&1 || mc admin user add local siga-evidence-local "$EVIDENCE_LOCAL_SECRET"')
            Assert-ExitCode
            Invoke-MinioRoot "mc admin policy attach local $minioPolicy --user $minioAccessKey"

            Write-Host 'Evidence local database and private object storage are ready; no existing credentials were rotated.'
        }
        'Start' {
            Assert-ExistingStack
            Import-EvidenceEnvironment

            $history = docker exec siga-local-postgres-1 psql -X -At -v ON_ERROR_STOP=1 -U postgres -d siga -c "SELECT to_regclass('evidence.flyway_schema_history');"
            Assert-ExitCode
            $temporaryCreateGranted = [string]::IsNullOrWhiteSpace(($history | Out-String).Trim())
            if ($temporaryCreateGranted) {
                Invoke-AdminSql 'GRANT CREATE ON DATABASE siga TO siga_evidence;'
            }

            try {
                $pidFile = Join-Path $local 'evidence.pid'
                if (Test-Path $pidFile) {
                    $running = Get-Process -Id ([int](Get-Content $pidFile)) -ErrorAction SilentlyContinue
                    if ($running) { throw 'Recorded Evidence PID is running.' }
                }

                $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 8084)
                try { $listener.Start() } finally { $listener.Stop() }

                $env:MAVEN_USER_HOME = Join-Path $env:USERPROFILE '.m2'
                $previousPreference = $ErrorActionPreference
                $ErrorActionPreference = 'Continue'
                ./mvnw.cmd -B -DskipTests package
                $buildExitCode = $LASTEXITCODE
                $ErrorActionPreference = $previousPreference
                if ($buildExitCode -ne 0) {
                    throw 'Evidence package failed; no runtime was started.'
                }

                $built = Join-Path $root 'target/siga-evidence-service-0.0.1-SNAPSHOT.jar'
                if (!(Test-Path $built)) { throw 'Evidence JAR was not produced.' }
                $hash = (Get-FileHash $built -Algorithm SHA256).Hash
                $runtime = Join-Path $local 'runtime'
                New-Item -ItemType Directory -Force $runtime | Out-Null
                $jar = Join-Path $runtime "evidence-$hash.jar"
                if (!(Test-Path $jar)) { Copy-Item $built $jar }

                $process = Start-Process -FilePath (Get-Command java).Source `
                    -ArgumentList @('-jar', ('"{0}"' -f $jar)) `
                    -WorkingDirectory $root -WindowStyle Hidden -PassThru `
                    -RedirectStandardOutput (Join-Path $local 'evidence.stdout.log') `
                    -RedirectStandardError (Join-Path $local 'evidence.stderr.log')
                $process.Id | Set-Content $pidFile
                $jar | Set-Content (Join-Path $local 'evidence.jar')

                $deadline = [DateTime]::UtcNow.AddSeconds(60)
                do {
                    $process.Refresh()
                    if ($process.HasExited) { throw 'Evidence exited; inspect the private .local logs.' }
                    try {
                        $health = Invoke-RestMethod 'http://127.0.0.1:8084/actuator/health/readiness' -TimeoutSec 2
                        if ($health.status -eq 'UP') {
                            Write-Host 'Evidence UP on 127.0.0.1:8084'
                            return
                        }
                    } catch { }
                    Start-Sleep -Milliseconds 500
                } while ([DateTime]::UtcNow -lt $deadline)
                throw 'Evidence readiness timeout; process and private logs were preserved.'
            } finally {
                if ($temporaryCreateGranted) {
                    Invoke-AdminSql 'REVOKE CREATE ON DATABASE siga FROM siga_evidence;'
                }
            }
        }
        'Stop' {
            $pidFile = Join-Path $local 'evidence.pid'
            if (!(Test-Path $pidFile)) { return }
            $evidencePid = [int](Get-Content $pidFile)
            $process = Get-CimInstance Win32_Process -Filter "ProcessId=$evidencePid"
            if (!$process) { return }
            $jar = Get-Content (Join-Path $local 'evidence.jar')
            if (!$process.CommandLine.Contains($jar)) {
                throw 'Evidence PID does not match the recorded runtime JAR.'
            }
            Stop-Process -Id $evidencePid
        }
        'Status' {
            Assert-ExistingStack
            try {
                $health = Invoke-RestMethod 'http://127.0.0.1:8084/actuator/health/readiness' -TimeoutSec 2
                Write-Host "Evidence readiness: $($health.status)"
            } catch {
                Write-Host 'Evidence readiness: DOWN'
            }
        }
    }
} finally {
    Pop-Location
}
