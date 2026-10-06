[CmdletBinding()]
param(
  [switch]$SkipBuild,
  [switch]$Stop,
  [switch]$ValidateOnly,
  [string]$ReleaseManifestPath,
  [string]$Namespace = 'open-im-local',
  [string]$KindClusterName = 'open-im-local',
  [string]$LocalBindAddress = '127.0.0.1',
  [string]$LocalAdvertiseAddress,
  [string]$PcAdminProjectPath,
  [string]$SaasAdminProjectPath,
  [string]$SaasMobileProjectPath,
  [ValidateRange(60, 900)]
  [int]$StartupTimeoutSeconds = 600
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$k8sScript = Join-Path $PSScriptRoot 'k8s.ps1'
$buildScript = Join-Path $PSScriptRoot 'build-saas-release.ps1'

function Assert-Command([string]$Name, [string]$InstallHint) {
  if (!(Get-Command $Name -ErrorAction SilentlyContinue)) {
    throw "$Name is required. $InstallHint"
  }
}

if ($Stop) {
  & $k8sScript -Stop -Namespace $Namespace -KindClusterName $KindClusterName
  exit $LASTEXITCODE
}

Assert-Command 'git' 'Install Git for Windows and reopen PowerShell.'
Assert-Command 'docker' 'Install and start Docker Desktop.'
Assert-Command 'kubectl' 'Install kubectl and ensure it is available on PATH.'
Assert-Command 'kind' 'Install Kind from https://kind.sigs.k8s.io/.'

$dockerReady = (& docker info --format '{{.ServerVersion}}' 2>$null | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or !$dockerReady) { throw 'Docker is installed but the Docker daemon is not running.' }

if (!$ReleaseManifestPath) {
  if ($SkipBuild) { throw '-SkipBuild requires -ReleaseManifestPath.' }
  $releaseDirectory = Join-Path $root '.outputs\releases'
  if (!(Test-Path -LiteralPath $releaseDirectory)) { New-Item -ItemType Directory -Path $releaseDirectory | Out-Null }
  $ReleaseManifestPath = Join-Path $releaseDirectory ("kind-local-{0}.json" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
  $buildArguments = @('-ReleaseManifestPath', $ReleaseManifestPath, '-SkipPush')
  if ($PcAdminProjectPath) { $buildArguments += @('-PcAdminProjectPath', $PcAdminProjectPath) }
  if ($SaasAdminProjectPath) { $buildArguments += @('-SaasAdminProjectPath', $SaasAdminProjectPath) }
  if ($SaasMobileProjectPath) { $buildArguments += @('-SaasMobileProjectPath', $SaasMobileProjectPath) }
  Write-Host 'Building local development images and release manifest...'
  & $buildScript @buildArguments
  if ($LASTEXITCODE -ne 0) { throw 'Local release build failed.' }
}

if (!(Test-Path -LiteralPath $ReleaseManifestPath)) { throw "Release manifest not found: $ReleaseManifestPath" }

$previousOffline = $env:OPEN_IM_OFFLINE_LOCAL
$previousPrivate = $env:OPEN_IM_KIND_PRIVATE_LOCAL
try {
  $env:OPEN_IM_OFFLINE_LOCAL = '1'
  $env:OPEN_IM_KIND_PRIVATE_LOCAL = '1'
  $deployArguments = @(
    '-SaasReleaseManifestPath', $ReleaseManifestPath,
    '-Namespace', $Namespace,
    '-KindClusterName', $KindClusterName,
    '-LocalBindAddress', $LocalBindAddress,
    '-StartupTimeoutSeconds', $StartupTimeoutSeconds
  )
  if ($LocalAdvertiseAddress) { $deployArguments += @('-LocalAdvertiseAddress', $LocalAdvertiseAddress) }
  if ($ValidateOnly) { $deployArguments += '-ValidateOnly' }
  Write-Host "Deploying local release to Kind cluster '$KindClusterName'..."
  & $k8sScript @deployArguments
  if ($LASTEXITCODE -ne 0) { throw 'Kind deployment failed.' }
} finally {
  $env:OPEN_IM_OFFLINE_LOCAL = $previousOffline
  $env:OPEN_IM_KIND_PRIVATE_LOCAL = $previousPrivate
}

Write-Host "Kind deployment completed. Release manifest: $ReleaseManifestPath"
