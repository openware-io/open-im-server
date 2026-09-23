[CmdletBinding()]
param(
  [string]$Namespace = 'open-im-local',
  [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not (Get-Command kubectl -ErrorAction SilentlyContinue)) { throw 'kubectl is required.' }

function Get-AnnotationValue {
  param([object]$Annotations, [string]$Name)
  if ($null -eq $Annotations) { return '' }
  $property = $Annotations.PSObject.Properties[$Name]
  if ($null -eq $property) { return '' }
  return [string]$property.Value
}

function Get-LabelValue {
  param([object]$Labels, [string]$Name)
  if ($null -eq $Labels) { return '' }
  $property = $Labels.PSObject.Properties[$Name]
  if ($null -eq $property) { return '' }
  return [string]$property.Value
}

function Test-PodMatchesSelector {
  param([object]$PodLabels, [object]$Selector)
  if ($null -eq $PodLabels -or $null -eq $Selector) { return $false }
  foreach ($entry in $Selector.PSObject.Properties) {
    if ((Get-LabelValue -Labels $PodLabels -Name $entry.Name) -ne [string]$entry.Value) { return $false }
  }
  return $true
}

$deployments = (& kubectl get deployment --namespace $Namespace --output json | ConvertFrom-Json).items
$pods = (& kubectl get pod --namespace $Namespace --output json | ConvertFrom-Json).items
if ($LASTEXITCODE -ne 0) { throw "Unable to query namespace: $Namespace" }

$rows = foreach ($deployment in $deployments) {
  $template = $deployment.spec.template
  $version = Get-LabelValue -Labels $template.metadata.labels -Name 'app.kubernetes.io/version'
  if ([string]::IsNullOrWhiteSpace($version)) { continue }

  $selector = $deployment.spec.selector.matchLabels
  $matchingPods = @($pods | Where-Object { Test-PodMatchesSelector -PodLabels $_.metadata.labels -Selector $selector })
  $container = @($template.spec.containers | Select-Object -First 1)[0]
  $pod = @($matchingPods | Sort-Object { $_.metadata.creationTimestamp } -Descending | Select-Object -First 1)[0]
  $containerStatus = if ($null -eq $pod) { $null } else { @($pod.status.containerStatuses | Where-Object { $_.name -eq $container.name } | Select-Object -First 1)[0] }
  $ready = if ($null -eq $containerStatus) { $false } else { [bool]$containerStatus.ready }
  $annotations = $template.metadata.annotations

  [pscustomobject][ordered]@{
    Service = $deployment.metadata.name
    MavenVersion = Get-AnnotationValue -Annotations $annotations -Name 'org.opencontainers.image.version'
    ReleaseId = Get-AnnotationValue -Annotations $annotations -Name 'release.open-im.local/id'
    GitCommit = Get-AnnotationValue -Annotations $annotations -Name 'release.open-im.local/code-sha'
    ArtifactSha256 = Get-AnnotationValue -Annotations $annotations -Name 'release.open-im.local/artifact-sha256'
    Image = $container.image
    ImageDigest = if ($null -eq $containerStatus) { '' } else { [string]$containerStatus.imageID }
    Pod = if ($null -eq $pod) { '' } else { [string]$pod.metadata.name }
    DeployedAt = Get-AnnotationValue -Annotations $annotations -Name 'release.open-im.local/deployed-at'
    Ready = $ready
  }
}

$rows = @($rows | Sort-Object Service)
if ($OutputPath) {
  $parent = Split-Path -Parent $OutputPath
  if ($parent) { [void](New-Item -ItemType Directory -Force -Path $parent) }
  $rows | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $OutputPath -Encoding utf8
  Write-Host "Release record written: $OutputPath"
}

if ($rows.Count -eq 0) { throw "No application release metadata found in namespace: $Namespace" }
$rows | Format-Table Service, MavenVersion, GitCommit, ReleaseId, Image, Pod, Ready -AutoSize
Write-Host ''
Write-Host 'Use -OutputPath to persist the complete artifact hashes and image digests as JSON.'
