param(
  [ValidateSet('kind', 'ack')]
  [string]$Environment = 'kind'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$envFile = Join-Path $root '.env'
if (Test-Path -LiteralPath $envFile) {
  Get-Content -LiteralPath $envFile -Encoding utf8 | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
      $name = $matches[1]
      if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        [Environment]::SetEnvironmentVariable($name, $matches[2], 'Process')
      }
    }
  }
}
$required = @('OIDC_ISSUER', 'REDIS_KEY_PREFIX')
$oidcValue = [Environment]::GetEnvironmentVariable('OIDC_ISSUER')
$redisPrefixValue = [Environment]::GetEnvironmentVariable('REDIS_KEY_PREFIX')
if ($Environment -eq 'kind') {
  if ([string]::IsNullOrWhiteSpace($oidcValue)) { $oidcValue = 'http://localhost:3100' }
  if ([string]::IsNullOrWhiteSpace($redisPrefixValue)) { $redisPrefixValue = 'kind:im:' }
  [Environment]::SetEnvironmentVariable('OIDC_ISSUER', $oidcValue, 'Process')
  [Environment]::SetEnvironmentVariable('REDIS_KEY_PREFIX', $redisPrefixValue, 'Process')
}
$missing = @($required | Where-Object { [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_)) })
if ($missing.Count -gt 0) {
  throw "Missing required OIDC preflight variables: $($missing -join ', ')"
}

$issuer = [Environment]::GetEnvironmentVariable('OIDC_ISSUER')
if ($Environment -ne 'kind' -and $issuer -notmatch '^https://') {
  throw "OIDC_ISSUER must use HTTPS outside local development"
}

$prefix = [Environment]::GetEnvironmentVariable('REDIS_KEY_PREFIX')
if ($prefix -notmatch "^(kind|ack):") {
  throw "REDIS_KEY_PREFIX must be environment-scoped (kind: or ack:)"
}

foreach ($name in @('JWT_SECRET', 'INTERNAL_SERVICE_AUTH_SECRET')) {
  $value = [Environment]::GetEnvironmentVariable($name)
  if ($value -match '(^$|secret|changeme|default|saas-ktv-secret)') {
    throw "$name is missing or uses a default value"
  }
}

Write-Output "OIDC hybrid preflight passed for $Environment ($issuer)"
