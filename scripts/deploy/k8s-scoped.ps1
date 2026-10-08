[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$SaasReleaseManifestPath,
  [string]$Namespace = 'open-im-local',
  [ValidatePattern('^[a-z0-9]([-a-z0-9]*[a-z0-9])?$')]
  [string]$KindClusterName = 'open-im-local',
  [string]$Registry = 'ghcr.io/openware-io',
  [string]$RepositoryNamespace = '',
  [ValidateRange(60, 900)]
  [int]$StartupTimeoutSeconds = 600
)

# Incremental Kind deployment: rolls only the services listed in the release manifest onto the ACR
# tags recorded there. Use it for day-to-day "release only what changed" iterations.
# A full deployment (first-time cluster setup, infrastructure/env changes, applying k8s manifests)
# still belongs to k8s.ps1. Both consume the same schema v2 release manifest and the same tag
# verification; manual `kubectl set image` or passing tags by hand stays forbidden.
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI, and a
# trailing multi-byte character before a newline can swallow that newline (see repo guideline).

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $PSScriptRoot 'approved-images.ps1')

if (!(Test-Path -LiteralPath $SaasReleaseManifestPath)) { throw "Release manifest not found: $SaasReleaseManifestPath" }
if (!(Get-Command kubectl -ErrorAction SilentlyContinue)) { throw 'kubectl is required.' }

$knownServices = @(
  'im-user-service', 'im-message-service', 'im-conversation-service', 'common-media-service', 'im-admin-service',
  'im-access-ws', 'gateway', 'platform-identity-service', 'platform-tenant-service', 'platform-resource-service',
  'platform-order-service', 'common-payment-service', 'platform-admin-service', 'platform-customer-service',
  'platform-marketing-service', 'common-payment-channel-service', 'common-audit-service', 'common-sms-service',
  'common-mail-service', 'group-idaas-service', 'saas-admin', 'saas-mobile', 'unified-portal', 'open-website', 'pc-admin'
)

$manifest = Get-Content -Raw -LiteralPath $SaasReleaseManifestPath | ConvertFrom-Json
if ([int]$manifest.schemaVersion -ne 2 -or !$manifest.services -or !$manifest.deploymentTargets) {
  throw 'Incremental deployment requires a schema v2 release manifest with deployment targets.'
}
$releaseType = [string]$manifest.releaseType
if ($releaseType -notin @('development', 'formal')) { throw "Invalid release type: $releaseType" }

$targets = @($manifest.deploymentTargets | ForEach-Object { [string]$_ } | Select-Object -Unique)
if ($targets.Count -eq 0) { throw 'Release manifest has no deployment targets.' }
$unknownTargets = @($targets | Where-Object { $_ -notin $knownServices })
if ($unknownTargets.Count -gt 0) { throw "Release manifest has invalid deployment targets: $($unknownTargets -join ', ')" }
if ($targets.Count -ge $knownServices.Count) {
  throw 'This manifest covers every service; use scripts/deploy/k8s.ps1 for a full deployment.'
}

$images = @{}; $digests = @{}; $tags = @{}; $moduleVersions = @{}; $revisions = @{}
foreach ($name in $targets) {
  $entry = $manifest.services.PSObject.Properties[$name]
  if (!$entry) { throw "Release manifest missing service: $name" }
  # Verify every target against ACR by tag and digest: tags may be overwritten, digests may not.
  [void](Get-ApprovedReleaseImage -Name $name -Entry $entry.Value -Registry $Registry -RepositoryNamespace $RepositoryNamespace)
  $images[$name] = [string]$entry.Value.image
  if ($images[$name] -match '@sha256:') { throw "Digest-suffixed image references are forbidden: $name" }
  $digests[$name] = [string]$entry.Value.digest
  $tags[$name] = [string]$entry.Value.tag
  $moduleVersions[$name] = [string]$entry.Value.moduleVersion
  $revisions[$name] = [string]$entry.Value.sourceRevision
}

function Invoke-Kubectl {
  param([string[]]$Arguments)
  & kubectl @Arguments
  if ($LASTEXITCODE -ne 0) { throw "kubectl $($Arguments -join ' ') failed." }
}

function Initialize-MissingDeployment {
  param([string]$Name)
  if ($Name -ne 'open-website') { return }
  $templatePath = Join-Path $root 'k8s\local\website.yaml'
  $renderedPath = Join-Path ([System.IO.Path]::GetTempPath()) ("open-im-k8s-website-" + [guid]::NewGuid().ToString() + '.yaml')
  try {
    $content = (Get-Content -Raw -LiteralPath $templatePath).Replace('__APP_IMAGE_OPEN_WEBSITE__', $images[$Name])
    [System.IO.File]::WriteAllText($renderedPath, $content, [System.Text.UTF8Encoding]::new($false))
    Invoke-Kubectl -Arguments @('apply', '--namespace', $Namespace, '--filename', $renderedPath)
  } finally {
    if (Test-Path -LiteralPath $renderedPath) { Remove-Item -LiteralPath $renderedPath -Force }
  }
}

function Set-LocalRecreateStrategy {
  param([string]$Name)
  $patch = '{"spec":{"strategy":{"type":"Recreate","rollingUpdate":null}}}'
  $patchFile = Join-Path ([System.IO.Path]::GetTempPath()) ("open-im-k8s-strategy-" + [guid]::NewGuid().ToString() + '.json')
  try {
    [System.IO.File]::WriteAllText($patchFile, $patch, [System.Text.UTF8Encoding]::new($false))
    Invoke-Kubectl -Arguments @('patch', "deployment/$Name", '--namespace', $Namespace, '--type=strategic', '--patch-file', $patchFile)
  } finally {
    if (Test-Path -LiteralPath $patchFile) { Remove-Item -LiteralPath $patchFile -Force }
  }
}

function Set-ApplicationImage {
  param([string]$Name)
  $imageRef = $images[$Name]
  Invoke-Kubectl -Arguments @('set', 'image', "deployment/$Name", "$Name=$imageRef", '--namespace', $Namespace)
  $imagePullPolicy = if ($env:OPEN_IM_KIND_PRIVATE_LOCAL -eq '1' -or $env:OPEN_IM_OFFLINE_LOCAL -eq '1') { 'IfNotPresent' } else { 'Always' }
  $labels = @{ 'app.kubernetes.io/version' = $tags[$Name]; 'app.kubernetes.io/managed-by' = 'local-k8s-incremental' }
  $serviceReleaseId = "local-scoped-$releaseType.$releaseTimestamp.$($revisions[$Name])"
  $annotations = @{
    'org.opencontainers.image.version' = $moduleVersions[$Name]
    'org.opencontainers.image.revision' = $revisions[$Name]
    'release.open-im.local/id' = $serviceReleaseId
    'release.open-im.local/code-sha' = $revisions[$Name]
    'release.open-im.local/deployed-at' = $deployedAt
    'release.open-im.local/image-ref' = $imageRef
  }
  # Keep the runtime build metadata aligned with the immutable manifest. A scoped
  # rollout must not leave INFO_BUILD_* values from the previous full deployment.
  $env = @(
    @{ name = 'MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE'; value = 'health,info' },
    @{ name = 'MANAGEMENT_INFO_ENV_ENABLED'; value = 'true' },
    @{ name = 'INFO_BUILD_GIT_COMMIT'; value = $revisions[$Name] },
    @{ name = 'INFO_BUILD_IMAGE'; value = $imageRef },
    @{ name = 'INFO_BUILD_ARTIFACT_SHA256'; value = '' }
  )
  $podTemplate = @{
    metadata = @{ labels = $labels; annotations = $annotations }
    spec = @{ containers = @(@{ name = $Name; imagePullPolicy = $imagePullPolicy; env = $env }) }
  }
  $patch = @{ metadata = @{ labels = $labels }; spec = @{ template = $podTemplate } } | ConvertTo-Json -Compress -Depth 8
  $patchFile = Join-Path ([System.IO.Path]::GetTempPath()) ("open-im-k8s-labels-" + [guid]::NewGuid().ToString() + '.json')
  try {
    [System.IO.File]::WriteAllText($patchFile, $patch, [System.Text.UTF8Encoding]::new($false))
    Invoke-Kubectl -Arguments @('patch', "deployment/$Name", '--namespace', $Namespace, '--type=strategic', '--patch-file', $patchFile)
  } finally {
    if (Test-Path -LiteralPath $patchFile) { Remove-Item -LiteralPath $patchFile -Force }
  }
}

function Remove-InactiveReplicaSets {
  $replicaSets = @((& kubectl get replicasets --namespace $Namespace --output json | ConvertFrom-Json).items | Where-Object {
    $_.spec.replicas -eq 0 -and $_.metadata.ownerReferences.kind -contains 'Deployment'
  } | ForEach-Object { $_.metadata.name })
  if ($replicaSets.Count -eq 0) { return }
  Invoke-Kubectl -Arguments (@('delete', 'replicasets', '--namespace', $Namespace) + $replicaSets)
}

$expectedContext = "kind-$KindClusterName"
& kubectl config use-context $expectedContext
if ($LASTEXITCODE -ne 0) { throw "Unable to select Kind context: $expectedContext" }

$releaseId = "local-scoped-$([string]$manifest.buildIdentity)"
$releaseTimestamp = ([string]$manifest.createdAt -replace '[^0-9]', '').Substring(0, 14)
$deployedAt = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
Write-Host "Incremental release: $($targets.Count) target(s) -> $($targets -join ', ')"
Write-Host "Build identity: $($manifest.buildIdentity) ($releaseType)"

# An incremental deployment expects an environment already created by a full deployment.
foreach ($name in $targets) {
  $existing = & kubectl get deployment $name --namespace $Namespace --ignore-not-found --output name
  if ($LASTEXITCODE -ne 0) { throw "Unable to inspect deployment: $name" }
  if ([string]::IsNullOrWhiteSpace([string]$existing)) {
    Initialize-MissingDeployment -Name $name
    $existing = & kubectl get deployment $name --namespace $Namespace --ignore-not-found --output name
  }
  if ([string]::IsNullOrWhiteSpace([string]$existing)) {
    throw "Deployment '$name' does not exist in namespace '$Namespace'. Run a full deployment (scripts/deploy/k8s.ps1) first."
  }
}

foreach ($name in $targets) { Set-LocalRecreateStrategy -Name $name }
foreach ($name in $targets) { Set-ApplicationImage -Name $name }
foreach ($name in $targets) {
  Invoke-Kubectl -Arguments @('rollout', 'status', "deployment/$name", '--namespace', $Namespace, "--timeout=$($StartupTimeoutSeconds)s")
}

# Verify every running container against the manifest tag.
foreach ($name in $targets) {
  $actualImage = (& kubectl get deployment $name --namespace $Namespace -o 'jsonpath={.spec.template.spec.containers[0].image}').Trim()
  if ($LASTEXITCODE -ne 0 -or $actualImage -ne $images[$name] -or $actualImage -match '@sha256:') { throw "Kind image tag drift for $name. Expected '$($images[$name])', got '$actualImage'." }
  $deployment = & kubectl get deployment $name --namespace $Namespace -o json | ConvertFrom-Json
  if ($LASTEXITCODE -ne 0) { throw "Cannot inspect deployment: $name" }
  $selector = ($deployment.spec.selector.matchLabels.PSObject.Properties | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ','
  $pods = & kubectl get pods --namespace $Namespace -l $selector -o json | ConvertFrom-Json
  if ($LASTEXITCODE -ne 0 -or !$pods.items) { throw "No running pods for $name" }
  foreach ($pod in $pods.items) {
    if ($pod.metadata.PSObject.Properties['deletionTimestamp']) { continue }
    $status = @($pod.status.containerStatuses | Where-Object { $_.name -eq $name })
    if ($status.Count -ne 1 -or !$status[0].ready) { throw "Kind runtime image digest mismatch: $($pod.metadata.name)" }
    $offlineLocal = $env:OPEN_IM_KIND_PRIVATE_LOCAL -eq '1' -or $env:OPEN_IM_OFFLINE_LOCAL -eq '1'
    if (!$offlineLocal -and [string]$status[0].imageID -notmatch '@sha256:[a-f0-9]{64}$') { throw "Kind Pod runtime image is not resolved: $($pod.metadata.name)" }
    $containerId = ([string]$status[0].containerID) -replace '^containerd://', ''
    $nodeName = [string]$pod.spec.nodeName
    $runtimeRaw = (& docker exec $nodeName crictl inspect $containerId 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or !$runtimeRaw) { throw "Cannot inspect Kind runtime container: $($pod.metadata.name)" }
    try { $runtime = $runtimeRaw | ConvertFrom-Json } catch { throw "Invalid Kind runtime container document: $($pod.metadata.name)" }
    # CRI records the resolved linux/amd64 platform descriptor here; imageID above is
    # the registry manifest digest pinned by the approved release manifest.
    $repository = $images[$name] -replace ':[^:]+$', ''
    $runtimeImageRef = [string]$runtime.status.imageRef
    if ($offlineLocal) {
      $localImageId = (& docker image inspect $images[$name] --format '{{.Id}}' 2>$null | Out-String).Trim()
      if (!$localImageId -or [string]$runtime.status.imageId -ne $localImageId) {
        throw "Kind offline runtime image mismatch: $($pod.metadata.name)"
      }
    } elseif ($runtimeImageRef -notlike "$repository@sha256:*") {
      throw "Kind runtime repository mismatch: $($pod.metadata.name)"
    }
  }
}
Remove-InactiveReplicaSets

$record = [ordered]@{
  schemaVersion = 1
  manifest = (Resolve-Path $SaasReleaseManifestPath).Path
  releaseType = $releaseType
  scope = 'incremental'
  buildIdentity = [string]$manifest.buildIdentity
  deployedAt = $deployedAt
  targets = $targets
  images = $images
  digests = $digests
  tags = $tags
}
$recordPath = Join-Path $root ".outputs\releases\$releaseId.json"
$record | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 -LiteralPath $recordPath
Write-Host "Incremental Kind deployment verified by immutable tag. Record: $recordPath"
