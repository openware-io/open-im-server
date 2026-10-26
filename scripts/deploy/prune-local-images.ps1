# Remove old local Kind build images and build cache after a deployment.
#
# Current release images are kept (read from the latest kind-local manifest), as are images used by
# running containers. Everything else under ghcr.io/openware-io is removed.
#
# Usage:
#   powershell -File scripts/deploy/prune-local-images.ps1
#   powershell -File scripts/deploy/prune-local-images.ps1 -ReleaseManifestPath .outputs/releases/kind-local-....json
#   powershell -File scripts/deploy/prune-local-images.ps1 -NoBuildCache
[CmdletBinding()]
param(
  [string]$ReleaseManifestPath,
  [switch]$NoBuildCache
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $ReleaseManifestPath) {
  $latest = Get-ChildItem (Join-Path $root '.outputs\releases') -Filter 'kind-local-*.json' -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if ($latest) { $ReleaseManifestPath = $latest.FullName }
}

$keep = [System.Collections.Generic.HashSet[string]]::new()
if ($ReleaseManifestPath -and (Test-Path -LiteralPath $ReleaseManifestPath)) {
  $manifest = Get-Content -Raw -LiteralPath $ReleaseManifestPath | ConvertFrom-Json
  foreach ($service in $manifest.services.PSObject.Properties) {
    [void]$keep.Add([string]$service.Value.image)
  }
  Write-Host "Keeping $($keep.Count) image(s) from $ReleaseManifestPath"
} else {
  Write-Host 'No release manifest found; only dangling images and build cache will be pruned.'
}

$usedIds = [System.Collections.Generic.HashSet[string]]::new()
foreach ($container in (docker ps -q)) {
  $id = (docker inspect --format '{{.Image}}' $container).Trim()
  if ($id) { [void]$usedIds.Add($id) }
}
# Kind nodes use containerd; Pod images may not appear in docker ps.
# Keep every image referenced by a Pod so a prune cannot break a running service.
$podImages = [System.Collections.Generic.HashSet[string]]::new()
$previousErrorActionPreference = $ErrorActionPreference
try {
  $ErrorActionPreference = 'SilentlyContinue'
  $podJson = & kubectl get pods --all-namespaces -o json 2>$null | Out-String
  if ($podJson.Trim()) {
    $pods = $podJson | ConvertFrom-Json
    foreach ($pod in @($pods.items)) {
      foreach ($container in @($pod.spec.containers)) {
        if ($container.image) { [void]$podImages.Add([string]$container.image) }
      }
    }
  }
} finally {
  $ErrorActionPreference = $previousErrorActionPreference
}
foreach ($image in $podImages) {
  [void]$keep.Add($image)
}
Write-Host "Keeping $($podImages.Count) image reference(s) used by Kubernetes Pods"

$removed = 0
foreach ($line in (docker images --format '{{.Repository}}|{{.Tag}}|{{.ID}}')) {
  $parts = $line -split '\|'
  if ($parts.Count -lt 3) { continue }
  $repository = $parts[0]
  $reference = "$($parts[0]):$($parts[1])"
  $id = $parts[2]
  if ($repository -notlike 'ghcr.io/openware-io/*') { continue }
  if ($keep.Contains($reference)) { continue }
  if ($usedIds.Contains($id)) { continue }
  Write-Host "Removing old image $reference"
  docker image rm -f $id | Out-Null
  $removed++
}

docker image prune -f | Out-Null
if (-not $NoBuildCache) {
  docker builder prune -f | Out-Null
}

Write-Host "Removed $removed old image(s)."
docker system df
