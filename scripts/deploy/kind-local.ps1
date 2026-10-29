[CmdletBinding()]
param(
  [switch]$SkipBuild,
  [switch]$ForceRedeploy,
  [switch]$ForceRebuild,
  [switch]$Stop,
  [switch]$Recover,
  [switch]$Status,
  [ValidateSet('auto', 'init', 'recover', 'redeploy', 'status', 'reset')]
  [string]$Mode = 'auto',
  [switch]$ConfirmReset,
  [ValidateSet('full', 'minimal')]
  [string]$Profile = 'full',
  [ValidateSet('ephemeral', 'pvc')]
  [string]$DataMode = 'ephemeral',
  [switch]$ValidateOnly,
  [switch]$PruneImages,
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
. (Join-Path $PSScriptRoot 'kind-recovery.ps1')

function Test-KindClusterExists {
  $previous = $ErrorActionPreference
  try {
    $ErrorActionPreference = 'SilentlyContinue'
    $clusters = @(& kind get clusters 2>$null)
  } finally {
    $ErrorActionPreference = $previous
  }
  return $clusters -contains $KindClusterName
}

function Test-KindNamespaceExists {
  $previous = $ErrorActionPreference
  try {
    $ErrorActionPreference = 'SilentlyContinue'
    $name = (& kubectl get namespace $Namespace --ignore-not-found -o name 2>$null | Out-String).Trim()
    return -not [string]::IsNullOrWhiteSpace($name)
  } finally {
    $ErrorActionPreference = $previous
  }
}

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
    if ($desired -lt 1) { continue }
    if ($available -lt $desired) { return $false }
  }
  try {
    if (-not (Test-KindLocalEndpoints -Attempts 2)) { return $false }
  } catch {
    return $false
  }
  return $true
}

function Start-KindNodeContainers {
  param([switch]$SkipWait)
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
  if ($SkipWait) { return }
  $deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
  do {
    $notReady = @(& kubectl get nodes --no-headers 2>$null | Where-Object { $_ -notmatch '\sReady\s' })
    if ($LASTEXITCODE -eq 0 -and $notReady.Count -eq 0) { return }
    Start-Sleep -Seconds 3
  } while ([DateTime]::UtcNow -lt $deadline)
  throw 'Kind nodes did not become Ready before the startup timeout.'
}

Assert-Command 'git' 'Install Git for Windows and reopen PowerShell.'
Assert-Command 'docker' 'Install and start Docker Desktop.'
Assert-Command 'kubectl' 'Install kubectl and ensure it is available on PATH.'
Assert-Command 'kind' 'Install Kind from https://kind.sigs.k8s.io/.'

$dockerReady = (& docker info --format '{{.ServerVersion}}' 2>$null | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or !$dockerReady) { throw 'Docker is installed but the Docker daemon is not running.' }

if ($Stop) {
  & $k8sScript -Stop -Namespace $Namespace -KindClusterName $KindClusterName
  exit $LASTEXITCODE
}

if ($Status) { $Mode = 'status' }
if ($Recover) { $Mode = 'recover' }
if ($ForceRebuild -and $Mode -eq 'auto') { $Mode = 'init' }
if ($ForceRedeploy -and $Mode -eq 'auto') { $Mode = 'redeploy' }

if ($Mode -eq 'status') {
  if (-not (Test-KindClusterExists)) {
    Write-Host "Kind cluster '$KindClusterName' does not exist." -ForegroundColor Yellow
    exit 2
  }
  $statusCode = Get-KindLocalStatus -ClusterName $KindClusterName -Namespace $Namespace
  exit $statusCode
}

if ($Mode -eq 'reset') {
  if (-not $ConfirmReset) {
    throw 'Reset deletes the local namespace and its data. Re-run with -Mode reset -ConfirmReset.'
  }
  Write-Host "Resetting local namespace '$Namespace' (data will be deleted)..." -ForegroundColor Yellow
  & $k8sScript -Stop -Namespace $Namespace -KindClusterName $KindClusterName
  exit $LASTEXITCODE
}

if ($Mode -eq 'recover' -and -not (Test-KindClusterExists)) {
  throw "Kind cluster '$KindClusterName' does not exist. Use -Mode init for first deployment."
}

# Explicit init/redeploy and -ForceRebuild must never be short-circuited by the
# healthy-workload reuse check.
if ($Mode -in @('init', 'redeploy') -or $ForceRebuild) { $ForceRedeploy = $true }

if ($Mode -eq 'recover') {
  Start-KindNodeContainers -SkipWait
  Invoke-KindRecovery -Root $root -ClusterName $KindClusterName -Namespace $Namespace -TimeoutSeconds $StartupTimeoutSeconds
  exit 0
}

if ($Mode -eq 'auto' -and (Test-KindClusterExists)) {
  # Do not wait on kubelet here: a stopped control plane may need its image
  # aliases repaired before the API can become ready.
  Start-KindNodeContainers -SkipWait
  Repair-KindSystemImages -ClusterName $KindClusterName
  Wait-KubectlApi -TimeoutSeconds 120
  if (Test-KindNamespaceExists) {
    if (Test-HealthyKindWorkload) {
      Write-Host "Healthy Kind workload found in namespace '$Namespace'; reusing existing Pods."
      & kubectl get pods --namespace $Namespace
      exit 0
    }
    Write-Host "Existing Kind workload is not healthy; running staged recovery (no rebuild)."
    Invoke-KindRecovery -Root $root -ClusterName $KindClusterName -Namespace $Namespace -TimeoutSeconds $StartupTimeoutSeconds
    exit 0
  }
  Wait-KindSystemReady -TimeoutSeconds 180
}

if ($Mode -in @('init', 'redeploy') -or -not (Test-KindClusterExists)) {
  Start-KindNodeContainers
}

if ($Mode -eq 'init' -or $ForceRebuild) { $SkipBuild = $false }

if (Test-HealthyKindWorkload) {
  Write-Host "Healthy Kind workload found in namespace '$Namespace'; reusing existing Pods."
  & kubectl get pods --namespace $Namespace
  if ($LASTEXITCODE -ne 0) { throw 'Existing Kind workload could not be inspected.' }
  Write-Host 'Existing deployment reused. Use -Mode redeploy to apply manifests again.'
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
  if ($Profile -eq 'minimal') {
    # Keep all backend services and the SaaS/portal entry; skip the optional
    # frontend image builds and their manifests during a minimal first init.
    $buildArguments.Targets = @(
      'im-user-service', 'im-message-service', 'im-conversation-service',
      'common-media-service', 'im-admin-service', 'im-access-ws', 'gateway',
      'platform-identity-service', 'platform-tenant-service', 'platform-resource-service',
      'platform-order-service', 'common-payment-service', 'platform-admin-service',
      'platform-customer-service', 'platform-marketing-service',
      'common-payment-channel-service', 'common-audit-service', 'common-sms-service',
      'common-mail-service', 'group-idaas-service', 'saas-admin', 'unified-portal'
    )
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
    DataMode = $DataMode
  }
  if ($Profile -eq 'minimal') { $deployArguments.MinimalProfile = $true }
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

Set-KindRuntimeProfile -Namespace $Namespace -Profile $Profile

if ($PruneImages) {
  & (Join-Path $PSScriptRoot 'prune-local-images.ps1') -ReleaseManifestPath $ReleaseManifestPath
  if ($LASTEXITCODE -ne 0) { throw 'Local image cleanup failed.' }
}
