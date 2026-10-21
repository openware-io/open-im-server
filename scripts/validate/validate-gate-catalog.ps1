# Gate catalog validator (G51).
#
# The registry is the numeric ledger; the catalog is the governance map.
# Every registry gate must appear exactly once in the catalog with a valid
# category, owner, stage and documentation/evidence reference.
#
# IMPORTANT: keep this file ASCII-only.

[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$registryPath = Join-Path $root 'docs\80-development\governance\GATE_REGISTRY.md'
$catalogPath = Join-Path $root 'docs\80-development\governance\GATE_CATALOG.md'
$worklistPath = Join-Path $root '.outputs\quality\gate-catalog-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()
$categories = @('meta', 'code-quality', 'architecture', 'security', 'data', 'domain', 'quality', 'media', 'delivery')
$owners = @('governance', 'backend', 'architecture', 'security', 'data', 'domain', 'quality', 'platform', 'delivery')
$stages = @('static', 'unit', 'integration', 'docs', 'release')

if (-not (Test-Path -LiteralPath $registryPath -PathType Leaf)) { throw 'Gate registry is missing.' }
if (-not (Test-Path -LiteralPath $catalogPath -PathType Leaf)) {
  Write-Error 'Gate catalog is missing: docs/80-development/governance/GATE_CATALOG.md'
  exit 1
}

$registryIds = @{}
foreach ($line in [System.IO.File]::ReadAllLines($registryPath, [System.Text.UTF8Encoding]::new($false, $true))) {
  if ($line -match '^\|\s*(G\d{2})\s*\|\s*([^|]+?)\s*\|') {
    $registryIds[$Matches[1]] = $Matches[2].Trim()
  }
}

$catalogIds = @{}
$catalogRows = 0
foreach ($line in [System.IO.File]::ReadAllLines($catalogPath, [System.Text.UTF8Encoding]::new($false, $true))) {
  if ($line -notmatch '^\|\s*(G\d{2})\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|') {
    continue
  }
  $catalogRows++
  $id = $Matches[1]
  $gate = $Matches[2].Trim()
  $category = $Matches[3].Trim()
  $owner = $Matches[4].Trim()
  $stage = $Matches[5].Trim()
  $docs = $Matches[6].Trim()
  if ($catalogIds.ContainsKey($id)) {
    $violations.Add("duplicate gate in catalog: $id")
  } else {
    $catalogIds[$id] = $gate
  }
  if (-not $registryIds.ContainsKey($id)) {
    $violations.Add("catalog gate is not in GATE_REGISTRY.md: $id $gate")
  } elseif ($registryIds[$id] -ne $gate) {
    $violations.Add("catalog name differs from registry for ${id}: '$gate' vs '$($registryIds[$id])'")
  }
  if ($category -notin $categories) { $violations.Add("invalid category for ${id}: $category") }
  if ($owner -notin $owners) { $violations.Add("invalid owner for ${id}: $owner") }
  if ($stage -notin $stages) { $violations.Add("invalid stage for ${id}: $stage") }
  if ([string]::IsNullOrWhiteSpace($docs) -or $docs -eq '-') { $violations.Add("missing docs/evidence reference for $id") }
}

foreach ($id in $registryIds.Keys) {
  if (-not $catalogIds.ContainsKey($id)) {
    $violations.Add("registry gate is missing from catalog: $id $($registryIds[$id])")
  }
}

if ($violations.Count -eq 0) {
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host "Gate catalog validation passed ($catalogRows gates)."
  exit 0
}

if ($ReportOnly) {
  $violations | ForEach-Object { Write-Warning $_ }
  Write-Warning "Gate catalog: $($violations.Count) pending violation(s)."
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))
$violations | Select-Object -First 40 | ForEach-Object { Write-Error $_ }
Write-Error "Gate catalog: $($violations.Count) violation(s) in total."
exit 1
