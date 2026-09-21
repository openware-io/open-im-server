[CmdletBinding()]
param(
  [switch]$ResetData,
  [switch]$Stop,
  [switch]$SkipBuild,
  [string]$ReleaseManifestPath,
  [string]$Registry = 'crpi-2xbf44rg544imbew.cn-hangzhou.personal.cr.aliyuncs.com',
  [string]$RepositoryNamespace = 'meta-cogni',
  [ValidateRange(30, 600)]
  [int]$StartupTimeoutSeconds = 240
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $PSScriptRoot 'approved-images.ps1')
if (!$ReleaseManifestPath) { throw 'ReleaseManifestPath is required in every environment. Build approved ACR images with build-saas-release.ps1 first.' }
$releaseManifest = Get-Content -Raw -LiteralPath $ReleaseManifestPath | ConvertFrom-Json
$approvedImages = @{}
foreach ($name in @('im-user-service', 'im-message-service', 'im-conversation-service', 'common-media-service', 'im-admin-service', 'im-access-ws', 'gateway')) {
  $property = $releaseManifest.services.PSObject.Properties[$name]
  if (!$property) { throw "Release manifest missing service: $name" }
  $approvedImages[$name] = Get-ApprovedReleaseImage -Name $name -Entry $property.Value -Registry $Registry -RepositoryNamespace $RepositoryNamespace
  $variableName = 'GV_IMAGE_' + $name.Replace('-', '_').ToUpperInvariant()
  [Environment]::SetEnvironmentVariable($variableName, $approvedImages[$name], 'Process')
}
$envFile = Join-Path $root '.env'
$composeFile = Join-Path $root 'docker-compose.yml'

if (-not (Test-Path -LiteralPath $envFile)) {
  throw ".env not found: $envFile"
}

$environmentValues = @{}
Get-Content -LiteralPath $envFile -Encoding utf8 | ForEach-Object {
  $line = $_.Trim()
  if ($line -and -not $line.StartsWith('#') -and $line -match '=') {
    $name, $value = $line -split '=', 2
    $environmentValues[$name.Trim()] = $value.Trim()
  }
}

# CI and local shells may provide secrets without persisting them in .env.
Get-ChildItem Env: | ForEach-Object {
  $environmentValues[$_.Name] = $_.Value
}

foreach ($name in @(
    'DB_USERNAME', 'DB_PASSWORD', 'DB_DATABASE', 'REDIS_PASSWORD',
    'MONGODB_DATABASE', 'MONGODB_USERNAME', 'MONGODB_PASSWORD',
    'JWT_SECRET', 'INTERNAL_SERVICE_AUTH_SECRET', 'IM_ACCESS_WS_ALLOWED_ORIGINS',
    'IM_DOCUMENTATION_USERNAME', 'IM_DOCUMENTATION_PASSWORD')) {
  if ([string]::IsNullOrWhiteSpace($environmentValues[$name])) {
    throw ".env must define $name for Docker deployment."
  }
}

$composeCommand = @('compose', '--progress', 'quiet', '--env-file', $envFile, '-f', $composeFile, '--profile', 'media-local')
function Invoke-Compose {
  param([string[]]$Arguments)

  & docker @composeCommand @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "docker compose $($Arguments -join ' ') failed."
  }
}

function Get-GatewayHealthBody {
  param([int]$Port)

  $request = [System.Net.WebRequest]::Create("http://127.0.0.1:$Port/actuator/health")
  $request.Timeout = 5000
  try {
    $response = $request.GetResponse()
  } catch [System.Net.WebException] {
    $response = $_.Exception.Response
    if ($null -eq $response) {
      throw
    }
  }
  try {
    $reader = [System.IO.StreamReader]::new($response.GetResponseStream())
    try {
      return $reader.ReadToEnd()
    } finally {
      $reader.Dispose()
    }
  } finally {
    $response.Dispose()
  }
}

if ($Stop) {
  Invoke-Compose -Arguments @('down', '--remove-orphans')
  exit 0
}

if ($ResetData) {
  Invoke-Compose -Arguments @('down', '--volumes', '--remove-orphans')
}

Invoke-Compose -Arguments @('config', '--quiet')
Invoke-Compose -Arguments @('up', '--detach', '--no-build', '--pull', 'always', '--remove-orphans')

$gatewayPort = 3002
if ($environmentValues.ContainsKey('GATEWAY_PORT') -and -not [string]::IsNullOrWhiteSpace($environmentValues['GATEWAY_PORT'])) {
  $gatewayPort = [int]$environmentValues['GATEWAY_PORT']
}
$deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
$lastHealthBody = 'No gateway health response received.'
while ([DateTime]::UtcNow -lt $deadline) {
  try {
    $lastHealthBody = Get-GatewayHealthBody -Port $gatewayPort
    if ($lastHealthBody -match '"status"\s*:\s*"UP"') {
      foreach ($name in $approvedImages.Keys) {
        $containerId = (& docker @composeCommand ps --quiet $name | Out-String).Trim()
        if ($LASTEXITCODE -ne 0 -or !$containerId) { throw "Application container missing: $name" }
        $imageId = (& docker inspect $containerId --format '{{.Image}}' | Out-String).Trim()
        if ($LASTEXITCODE -ne 0) { throw "Cannot inspect application container: $name" }
        $digests = & docker image inspect $imageId --format '{{json .RepoDigests}}' | ConvertFrom-Json
        if ($LASTEXITCODE -ne 0 -or $digests -notcontains $releaseManifest.services.PSObject.Properties[$name].Value.imageDigest) {
          throw "Compose runtime image digest mismatch: $name"
        }
      }
      Write-Host "Docker deployment is ready: http://127.0.0.1:$gatewayPort"
      exit 0
    }
  } catch {
    $lastHealthBody = $_.Exception.Message
  }
  Start-Sleep -Seconds 2
}

& docker @composeCommand logs --tail 100
throw "Gateway did not become healthy within $StartupTimeoutSeconds seconds. Last response: $lastHealthBody"
