# Domain map validator (G53).
#
# The domain map must classify every bounded context as core, supporting,
# generic or edge, and every deployable service must be named there.
#
# IMPORTANT: keep this file ASCII-only.

[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$mapPath = Join-Path $root 'docs\40-domain\DOMAIN_MAP.md'
$worklistPath = Join-Path $root '.outputs\quality\domain-map-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()
$allowed = @('core', 'supporting', 'generic', 'edge')

if (-not (Test-Path -LiteralPath $mapPath -PathType Leaf)) {
  Write-Error 'Domain map is missing: docs/40-domain/DOMAIN_MAP.md'
  exit 1
}

$text = [System.IO.File]::ReadAllText($mapPath, [System.Text.UTF8Encoding]::new($false, $true))
$rows = 0
$coreRows = 0
foreach ($line in $text -split "`r?`n") {
  if ($line -notmatch '^\|\s*([^|]+?)\s*\|\s*`?(core|supporting|generic|edge)`?\s*\|') {
    continue
  }
  $rows++
  $context = $Matches[1].Trim()
  $type = $Matches[2].Trim()
  if ($type -eq 'core') { $coreRows++ }
  $columns = @($line.Trim().Trim('|').Split('|') | ForEach-Object { $_.Trim() })
  if ($columns.Count -lt 7) {
    $violations.Add("domain row has fewer than 7 columns: $context")
    continue
  }
  $owner = $columns[2]
  $module = $columns[3]
  $depends = $columns[4]
  $consumers = $columns[5]
  if ([string]::IsNullOrWhiteSpace($owner)) { $violations.Add("missing owner: $context") }
  if ([string]::IsNullOrWhiteSpace($module)) { $violations.Add("missing module: $context") }
  if ([string]::IsNullOrWhiteSpace($depends)) { $violations.Add("missing dependencies: $context") }
  if ([string]::IsNullOrWhiteSpace($consumers)) { $violations.Add("missing consumers: $context") }
}

if ($rows -lt 10) {
  $violations.Add("domain map has too few contexts: $rows")
}
if ($coreRows -eq 0) {
  $violations.Add('domain map has no core context')
}

$services = @()
foreach ($group in @('platform-services', 'common-services', 'im-services')) {
  $rootDir = Join-Path $root $group
  if (-not (Test-Path -LiteralPath $rootDir)) { continue }
  foreach ($dir in Get-ChildItem $rootDir -Directory) {
    foreach ($service in Get-ChildItem $dir.FullName -Directory -Filter '*-service') {
      $services += $service.Name
    }
  }
}
foreach ($gateway in @('gateways\gateway', 'gateways\im-access-ws')) {
  if (Test-Path -LiteralPath (Join-Path $root $gateway)) { $services += (Split-Path -Leaf $gateway) }
}
foreach ($service in ($services | Sort-Object -Unique)) {
  if (-not $text.Contains($service)) {
    $violations.Add("deployable service is not named in the domain map: $service")
  }
}

if ($violations.Count -eq 0) {
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host "Domain map validation passed ($rows contexts, $coreRows core, $($services.Count) services)."
  exit 0
}

if ($ReportOnly) {
  $violations | ForEach-Object { Write-Warning $_ }
  Write-Warning "Domain map: $($violations.Count) pending violation(s)."
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))
$violations | Select-Object -First 40 | ForEach-Object { Write-Error $_ }
Write-Error "Domain map: $($violations.Count) violation(s) in total."
exit 1
