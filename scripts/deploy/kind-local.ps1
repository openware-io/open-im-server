[CmdletBinding()]
param(
  [switch]$SkipBuild,
  [switch]$ForceRedeploy,
  [switch]$ForceRebuild,
  [switch]$Stop,
  [switch]$ValidateOnly,
  [switch]$PruneImages,
  [switch]$Recover,
  [string]$ReleaseManifestPath,
  [string]$Namespace = 'open-im-local',
  [string]$KindClusterName = 'open-im-local',
  [string]$LocalBindAddress = '0.0.0.0',
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

function Test-HealthyKindWorkload {
  if ($ForceRedeploy) { return $false }
  $previousErrorActionPreference = $ErrorActionPreference
  try {
    $ErrorActionPreference = 'SilentlyContinue'
    $namespaceExists = (& kubectl get namespace $Namespace --ignore-not-found -o name 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or !$namespaceExists) { return $false }
    $deploymentResult = & kubectl get deployments --namespace $Namespace -o json 2>$null
    if ($LASTEXITCODE -ne 0) { return $false }
    $deployments = @($deploymentResult | ConvertFrom-Json).items
    if ($deployments.Count -lt 10) { return $false }
  } finally {
    $ErrorActionPreference = $previousErrorActionPreference
  }
  foreach ($deployment in $deployments) {
    $desired = [int]$deployment.spec.replicas
    $available = if ($null -eq $deployment.status -or $null -eq $deployment.status.PSObject.Properties['availableReplicas']) { 0 } else { [int]$deployment.status.availableReplicas }
    if ($desired -lt 1 -or $available -lt $desired) { return $false }
  }
  try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:30080/saas/' -TimeoutSec 5
    if ($response.StatusCode -ne 200) { return $false }
  } catch {
    return $false
  }
  return $true
}

function Start-KindNodeContainers {
  $nodes = @(& docker ps -a --filter "label=io.x-k8s.kind.cluster=$KindClusterName" --format '{{.Names}}|{{.State}}' 2>$null)
  if ($nodes.Count -eq 0) { return }
  foreach ($node in $nodes) {
    $parts = $node -split '\|', 2
    if ($parts.Count -lt 2) { continue }
    if ($parts[1] -ne 'running') {
      Write-Host "Starting stopped Kind node $($parts[0])..."
      & docker start $parts[0] | Out-Null
      if ($LASTEXITCODE -ne 0) { throw "Unable to start Kind node $($parts[0])." }
    }
  }
  $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
  do {
    $notReady = @(& kubectl get nodes --no-headers 2>$null | Where-Object { $_ -notmatch '\sReady\s' })
    if ($LASTEXITCODE -eq 0 -and $notReady.Count -eq 0) { return }
    Start-Sleep -Seconds 3
  } while ([DateTime]::UtcNow -lt $deadline)
  throw 'Kind nodes did not become Ready before the startup timeout.'
}

function Restart-KindWorkloads {
  # Restart application deployments; keep stateful stores (mysql/mongodb/redis/minio/rocketmq) running.
  $names = @(& kubectl get deployments --namespace $Namespace -o name 2>$null |
    ForEach-Object { ($_ -split '/')[-1] } |
    Where-Object { $_ -and $_ -notmatch '^(mysql|mongodb|redis|minio|rocketmq)' })
  foreach ($name in $names) {
    Write-Host "Restarting deployment/$name..."
    & kubectl --namespace $Namespace rollout restart "deployment/$name" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Unable to restart deployment/$name." }
  }
  & kubectl --namespace $Namespace wait --for=condition=available deployment --all --timeout="$($StartupTimeoutSeconds)s" | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'SaaS workloads did not become available before the startup timeout.' }
  # Allow a short window for Ingress reconciliation after deployments become available.
  for ($attempt = 1; $attempt -le 10; $attempt++) {
    if (Test-HealthyKindWorkload) { return }
    Start-Sleep -Seconds 2
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

Start-KindNodeContainers

if ($Recover) {
  if (-not (Test-HealthyKindWorkload)) {
    Restart-KindWorkloads
  }
  if (-not (Test-HealthyKindWorkload)) { throw 'SaaS recovery did not reach a healthy state.' }
  Write-Host "Recovered existing Kind workload in namespace '$Namespace'."
  & kubectl get pods --namespace $Namespace
  exit 0
}

if (Test-HealthyKindWorkload) {
  Write-Host "Healthy Kind workload found in namespace '$Namespace'; reusing existing Pods."
  & kubectl get pods --namespace $Namespace
  if ($LASTEXITCODE -ne 0) { throw 'Existing Kind workload could not be inspected.' }
  Write-Host 'Existing deployment reused. Use -ForceRedeploy to apply manifests again.'
  exit 0
}

if ($ForceRebuild) { $SkipBuild = $false }

if (!$ReleaseManifestPath) {
  if ($SkipBuild) { throw '-SkipBuild requires -ReleaseManifestPath.' }
  $releaseDirectory = Join-Path $root '.outputs\releases'
  if (!(Test-Path -LiteralPath $releaseDirectory)) { New-Item -ItemType Directory -Path $releaseDirectory | Out-Null }
  $ReleaseManifestPath = Join-Path $releaseDirectory ("kind-local-{0}.json" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
  Write-Host 'Building local development images and release manifest...'
  $buildArguments = @{
    ReleaseManifestPath = $ReleaseManifestPath
    SkipPush = $true
  }
  if ($PcAdminProjectPath) { $buildArguments.PcAdminProjectPath = $PcAdminProjectPath }
  if ($SaasAdminProjectPath) { $buildArguments.SaasAdminProjectPath = $SaasAdminProjectPath }
  if ($SaasMobileProjectPath) { $buildArguments.SaasMobileProjectPath = $SaasMobileProjectPath }
  & $buildScript @buildArguments
  if ($LASTEXITCODE -ne 0) { throw 'Local release build failed.' }
}

if (!(Test-Path -LiteralPath $ReleaseManifestPath)) { throw "Release manifest not found: $ReleaseManifestPath" }

$previousOffline = $env:OPEN_IM_OFFLINE_LOCAL
$previousPrivate = $env:OPEN_IM_KIND_PRIVATE_LOCAL
try {
  $env:OPEN_IM_OFFLINE_LOCAL = '1'
  $env:OPEN_IM_KIND_PRIVATE_LOCAL = '1'
  $deployArguments = @{
    SaasReleaseManifestPath = $ReleaseManifestPath
    Namespace = $Namespace
    KindClusterName = $KindClusterName
    LocalBindAddress = $LocalBindAddress
    StartupTimeoutSeconds = $StartupTimeoutSeconds
  }
  if ($LocalAdvertiseAddress) { $deployArguments.LocalAdvertiseAddress = $LocalAdvertiseAddress }
  if ($ValidateOnly) { $deployArguments.ValidateOnly = $true }
  Write-Host "Deploying local release to Kind cluster '$KindClusterName'..."
  & $k8sScript @deployArguments
  if ($LASTEXITCODE -ne 0) { throw 'Kind deployment failed.' }
} finally {
  $env:OPEN_IM_OFFLINE_LOCAL = $previousOffline
  $env:OPEN_IM_KIND_PRIVATE_LOCAL = $previousPrivate
}

Write-Host "Kind deployment completed. Release manifest: $ReleaseManifestPath"

if ($PruneImages) {
  & (Join-Path $PSScriptRoot 'prune-local-images.ps1') -ReleaseManifestPath $ReleaseManifestPath
  if ($LASTEXITCODE -ne 0) { throw 'Local image cleanup failed.' }
}
