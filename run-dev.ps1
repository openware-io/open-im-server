param(
  [ValidateSet("gateway", "user", "message", "conversation", "support", "order", "admin", "access-ws")]
  [string]$Service = "gateway"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$envFile = Join-Path $root ".env"

if (-not (Test-Path $envFile)) {
  & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'scripts\initialize-local-env.ps1')
  if (-not (Test-Path $envFile)) {
    throw 'Unable to initialize .env. Run scripts\initialize-local-env.ps1 manually.'
  }
}

Get-Content $envFile -Encoding utf8 | Where-Object { $_ -match '^\s*[^#]' -and $_ -match '=' } | ForEach-Object {
  $name, $value = $_ -split '=', 2
  Set-Item -Path "Env:$($name.Trim())" -Value $value.Trim()
}

$serviceConfig = @{
  "gateway" = @{
    Port = "3002"
    Pom = "gateways/gateway/pom.xml"
    AppName = "gateway"
  }
  "user" = @{
    Port = "3100"
    Pom = "im-services/user/im-user-service/pom.xml"
    AppName = "im-user-service"
  }
  "message" = @{
    Port = "3200"
    Pom = "im-services/message/im-message-service/pom.xml"
    AppName = "im-message-service"
  }
  "conversation" = @{
    Port = "3300"
    Pom = "im-services/conversation/im-conversation-service/pom.xml"
    AppName = "im-conversation-service"
  }
  "support" = @{
    Port = "3600"
    Pom = "common-services/media/common-media-service/pom.xml"
    AppName = "common-media-service"
  }
  "admin" = @{
    Port = "3400"
    Pom = "im-services/admin/im-admin-service/pom.xml"
    AppName = "im-admin-service"
  }
  "access-ws" = @{
    Port = "3001"
    Pom = "gateways/im-access-ws/pom.xml"
    AppName = "im-access-ws"
  }
}

$currentService = $serviceConfig[$Service]
if (-not $env:PORT) {
  $env:PORT = $currentService.Port
}

$outputRoot = Join-Path $root ".outputs"
$serviceLogDir = Join-Path $outputRoot "logs/im-services/$($currentService.AppName)"
New-Item -ItemType Directory -Force -Path $serviceLogDir | Out-Null

$env:IM_OUTPUT_ROOT = $outputRoot
$env:LOG_FILE = Join-Path $serviceLogDir "application.log"
$env:LOG_FILE_PATTERN = Join-Path $serviceLogDir "application.%d{yyyy-MM-dd}.%i.log.gz"

Write-Host "Loaded env from .env (DB=$env:DB_DATABASE@$env:DB_HOST)"
Write-Host "Starting service=$Service (PORT=$env:PORT)"
Write-Host "Application log: $env:LOG_FILE"

& (Join-Path $root "mvnw.cmd") "-f" (Join-Path $root $currentService.Pom) "spring-boot:run"
exit $LASTEXITCODE
