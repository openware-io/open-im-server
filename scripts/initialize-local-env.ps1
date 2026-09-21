[CmdletBinding()]
param(
  [string]$MediaHost,
  [switch]$Force
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = Split-Path -Parent $PSScriptRoot
$templatePath = Join-Path $root '.env.example'
$envPath = Join-Path $root '.env'

if (-not (Test-Path -LiteralPath $templatePath)) {
  throw ".env.example not found: $templatePath"
}

if ((Test-Path -LiteralPath $envPath) -and -not $Force) {
  Write-Host ".env already exists: $envPath"
  exit 0
}

function New-LocalSecret {
  $randomBytes = New-Object byte[] 32
  $randomGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  try {
    $randomGenerator.GetBytes($randomBytes)
  } finally {
    $randomGenerator.Dispose()
  }
  return [Convert]::ToBase64String($randomBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Resolve-LocalMediaHost {
  $candidate = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object {
      $_.IPAddress -notlike '127.*' -and
      $_.IPAddress -notlike '169.254.*' -and
      $_.PrefixOrigin -ne 'WellKnown'
    } |
    Select-Object -First 1 -ExpandProperty IPAddress

  if ([string]::IsNullOrWhiteSpace($candidate)) {
    return '127.0.0.1'
  }
  return $candidate
}

if ([string]::IsNullOrWhiteSpace($MediaHost)) {
  $MediaHost = Resolve-LocalMediaHost
}

$replacementValues = @{
  'DB_PASSWORD=change-me' = "DB_PASSWORD=$(New-LocalSecret)"
  'REDIS_PASSWORD=change-me' = "REDIS_PASSWORD=$(New-LocalSecret)"
  'MONGODB_PASSWORD=change-me' = "MONGODB_PASSWORD=$(New-LocalSecret)"
  'ROCKETMQ_ACCESS_KEY=change-me' = "ROCKETMQ_ACCESS_KEY=$(New-LocalSecret)"
  'ROCKETMQ_SECRET_KEY=change-me' = "ROCKETMQ_SECRET_KEY=$(New-LocalSecret)"
  'JPUSH_APP_KEY=change-me' = "JPUSH_APP_KEY=$(New-LocalSecret)"
  'JPUSH_MASTER_SECRET=change-me' = "JPUSH_MASTER_SECRET=$(New-LocalSecret)"
  'JWT_SECRET=change-me' = "JWT_SECRET=$(New-LocalSecret)"
  'INTERNAL_SERVICE_AUTH_SECRET=change-me' = "INTERNAL_SERVICE_AUTH_SECRET=$(New-LocalSecret)"
  'IM_DOCUMENTATION_PASSWORD=change-me' = "IM_DOCUMENTATION_PASSWORD=$(New-LocalSecret)"
  'MEDIA_SECRET_KEY=change-me' = "MEDIA_SECRET_KEY=$(New-LocalSecret)"
  'MINIO_ROOT_PASSWORD=change-me' = "MINIO_ROOT_PASSWORD=$(New-LocalSecret)"
  'MEDIA_PUBLIC_BASE_URL=http://192.168.1.100:9000' = "MEDIA_PUBLIC_BASE_URL=http://${MediaHost}:9000"
}

$content = Get-Content -LiteralPath $templatePath -Raw -Encoding utf8
foreach ($replacement in $replacementValues.GetEnumerator()) {
  $content = $content.Replace($replacement.Key, $replacement.Value)
}

[System.IO.File]::WriteAllText($envPath, $content, [System.Text.UTF8Encoding]::new($false))
Write-Host "Created local .env from .env.example (media host: $MediaHost)."
Write-Host 'Review MEDIA_PUBLIC_BASE_URL before testing from a physical device.'
