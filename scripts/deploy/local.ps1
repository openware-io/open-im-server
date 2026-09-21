[CmdletBinding()]
param(
  [switch]$ResetDatabase,
  [switch]$SkipBuild,
  [switch]$Stop,
  [ValidateRange(30, 600)]
  [int]$StartupTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$envFile = Join-Path $root '.env'
$outputRoot = Join-Path $root '.outputs\local-deployment'
$pidFile = Join-Path $outputRoot 'processes.json'
$logRoot = Join-Path $outputRoot 'logs'

function Import-EnvironmentFile {
  param([string]$Path)

  if (-not (Test-Path -LiteralPath $Path)) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'scripts\initialize-local-env.ps1')
    if (-not (Test-Path -LiteralPath $Path)) {
      throw "Unable to initialize .env: $Path"
    }
  }

  Get-Content -LiteralPath $Path -Encoding utf8 | ForEach-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith('#') -or $line -notmatch '=') {
      return
    }
    $name, $value = $line -split '=', 2
    [Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), 'Process')
  }
}

function Get-RequiredEnvironmentValue {
  param([string]$Name)

  $value = [Environment]::GetEnvironmentVariable($Name, 'Process')
  if ([string]::IsNullOrWhiteSpace($value)) {
    throw ".env must define $Name"
  }
  return $value
}

function Stop-ManagedServices {
  if (-not (Test-Path -LiteralPath $pidFile)) {
    return
  }

  $processes = Get-Content -LiteralPath $pidFile -Raw -Encoding utf8 | ConvertFrom-Json
  foreach ($processInfo in @($processes)) {
    $process = Get-Process -Id $processInfo.Id -ErrorAction SilentlyContinue
    if ($null -ne $process) {
      Stop-Process -Id $processInfo.Id -Force
      Write-Host "Stopped $($processInfo.Name) (PID $($processInfo.Id))."
    }
  }
  Remove-Item -LiteralPath $pidFile -Force
}

function Test-TcpPort {
  param([int]$Port)

  $client = [System.Net.Sockets.TcpClient]::new()
  try {
    $task = $client.ConnectAsync('127.0.0.1', $Port)
    return $task.Wait(500) -and $client.Connected
  } finally {
    $client.Dispose()
  }
}

function Wait-ForPort {
  param([string]$Name, [int]$Port, [System.Diagnostics.Process]$Process)

  $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
  while ([DateTime]::UtcNow -lt $deadline) {
    $Process.Refresh()
    if ($Process.HasExited) {
      throw "$Name exited with code $($Process.ExitCode). Review $logRoot\\$Name.err.log"
    }
    if (Test-TcpPort -Port $Port) {
      Write-Host "$Name is listening on port $Port."
      return
    }
    Start-Sleep -Milliseconds 500
  }
  throw "Timed out waiting for $Name on port $Port. Review $logRoot\\$Name.err.log"
}

function Get-GatewayHealth {
  $healthRequest = [System.Net.WebRequest]::Create('http://127.0.0.1:3002/actuator/health')
  $healthRequest.Timeout = 10000
  try {
    $healthResponse = $healthRequest.GetResponse()
  } catch [System.Net.WebException] {
    $healthResponse = $_.Exception.Response
    if ($null -eq $healthResponse) {
      throw
    }
  }
  try {
    $healthReader = [System.IO.StreamReader]::new($healthResponse.GetResponseStream())
    try {
      return $healthReader.ReadToEnd() | ConvertFrom-Json
    } finally {
      $healthReader.Dispose()
    }
  } finally {
    $healthResponse.Dispose()
  }
}

function Wait-ForGatewayHealth {
  $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
  $lastHealthDetails = 'No health response received.'
  while ([DateTime]::UtcNow -lt $deadline) {
    try {
      $health = Get-GatewayHealth
      $lastHealthDetails = $health | ConvertTo-Json -Depth 10 -Compress
      if ($health.status -eq 'UP') {
        return
      }
    } catch {
      $lastHealthDetails = $_.Exception.Message
    }
    Start-Sleep -Milliseconds 500
  }
  $lastHealthDetails | Set-Content -LiteralPath (Join-Path $outputRoot 'gateway-health.json') -Encoding utf8
  throw "Gateway did not become healthy within $StartupTimeoutSeconds seconds. Last health response: $lastHealthDetails"
}

function Reset-Database {
  $database = Get-RequiredEnvironmentValue 'DB_DATABASE'
  if ($database -notmatch '^[A-Za-z0-9_]+$') {
    throw 'DB_DATABASE may contain only letters, numbers, and underscores when -ResetDatabase is used.'
  }

  $dbHost = Get-RequiredEnvironmentValue 'DB_HOST'
  if ($dbHost -in @('localhost', '127.0.0.1', '::1')) {
    $dbHost = 'host.docker.internal'
  }
  $port = Get-RequiredEnvironmentValue 'DB_PORT'
  $username = Get-RequiredEnvironmentValue 'DB_USERNAME'
  $password = Get-RequiredEnvironmentValue 'DB_PASSWORD'
  $sql = "DROP DATABASE IF EXISTS ``$database``; CREATE DATABASE ``$database`` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

  & docker image inspect mysql:8.0 | Out-Null
  if ($LASTEXITCODE -ne 0) {
    & docker pull mysql:8.0
    if ($LASTEXITCODE -ne 0) {
      throw 'Unable to pull mysql:8.0 for database initialization.'
    }
  }
  & docker run --rm --env "MYSQL_PWD=$password" mysql:8.0 mysql --protocol=TCP "-h$dbHost" "-P$port" "-u$username" -e $sql
  if ($LASTEXITCODE -ne 0) {
    throw "Failed to recreate database $database."
  }
  Write-Host "Recreated initialization database $database."
}

Import-EnvironmentFile -Path $envFile
[Environment]::SetEnvironmentVariable('MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS', 'always', 'Process')
[Environment]::SetEnvironmentVariable('MANAGEMENT_ENDPOINT_HEALTH_SHOW_COMPONENTS', 'always', 'Process')

if ($Stop) {
  Stop-ManagedServices
  exit 0
}

$requiredVariables = @(
  'DB_HOST', 'DB_PORT', 'DB_USERNAME', 'DB_PASSWORD', 'DB_DATABASE',
  'REDIS_HOST', 'REDIS_PORT', 'REDIS_PASSWORD',
  'MONGODB_HOST', 'MONGODB_PORT', 'MONGODB_DATABASE', 'MONGODB_USERNAME', 'MONGODB_PASSWORD',
  'ROCKETMQ_NAMESRV_ADDR', 'JWT_SECRET', 'INTERNAL_SERVICE_AUTH_SECRET',
  'IM_ACCESS_WS_ALLOWED_ORIGINS'
)
foreach ($name in $requiredVariables) {
  [void](Get-RequiredEnvironmentValue $name)
}
if ((Get-RequiredEnvironmentValue 'INTERNAL_SERVICE_AUTH_SECRET').Length -lt 32) {
  throw 'INTERNAL_SERVICE_AUTH_SECRET must contain at least 32 characters.'
}

New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
Stop-ManagedServices

if ($ResetDatabase) {
  Reset-Database
}

if (-not $SkipBuild) {
  & (Join-Path $root 'mvnw.cmd') -B -ntp clean package
  if ($LASTEXITCODE -ne 0) {
    throw 'Full Maven clean package failed; services were not started.'
  }
}

$services = @(
  @{ Name = 'im-user-service'; Port = 3100; Jar = 'im-services/user/im-user-service/target/im-user-service-*.jar' },
  @{ Name = 'im-conversation-service'; Port = 3300; Jar = 'im-services/conversation/im-conversation-service/target/im-conversation-service-*.jar' },
  @{ Name = 'common-media-service'; Port = 3600; Jar = 'common-services/media/common-media-service/target/common-media-service-*.jar' },
  @{ Name = 'im-message-service'; Port = 3200; Jar = 'im-services/message/im-message-service/target/im-message-service-*.jar' },
  @{ Name = 'im-admin-service'; Port = 3400; Jar = 'im-services/admin/im-admin-service/target/im-admin-service-*.jar' },
  @{ Name = 'im-access-ws'; Port = 3001; Jar = 'gateways/im-access-ws/target/im-access-ws-*.jar' },
  @{ Name = 'gateway'; Port = 3002; Jar = 'gateways/gateway/target/gateway-*.jar' }
)

$started = [System.Collections.Generic.List[object]]::new()
try {
  foreach ($service in $services) {
    if (Test-TcpPort -Port $service.Port) {
      throw "Port $($service.Port) is already in use; cannot start $($service.Name)."
    }
    $jarMatches = @(Get-ChildItem -Path (Join-Path $root $service.Jar) -File)
    if ($jarMatches.Count -ne 1) {
      throw "Expected one application JAR matching '$($service.Jar)'; found $($jarMatches.Count)."
    }
    $jarPath = $jarMatches[0].FullName
    [Environment]::SetEnvironmentVariable('PORT', [string]$service.Port, 'Process')
    [Environment]::SetEnvironmentVariable('IM_OUTPUT_ROOT', $outputRoot, 'Process')
    $process = Start-Process -FilePath 'java' -ArgumentList @('-jar', $jarPath) -WorkingDirectory $root -RedirectStandardOutput (Join-Path $logRoot "$($service.Name).out.log") -RedirectStandardError (Join-Path $logRoot "$($service.Name).err.log") -PassThru
    $started.Add([pscustomobject]@{ Name = $service.Name; Id = $process.Id; Port = $service.Port })
    Wait-ForPort -Name $service.Name -Port $service.Port -Process $process
  }

  Wait-ForGatewayHealth
  $started | ConvertTo-Json | Set-Content -LiteralPath $pidFile -Encoding utf8
  Write-Host 'All services are running.'
  Write-Host 'Gateway: http://127.0.0.1:3002'
  Write-Host "Logs: $logRoot"
} catch {
  foreach ($service in $started) {
    Stop-Process -Id $service.Id -Force -ErrorAction SilentlyContinue
  }
  throw
}
