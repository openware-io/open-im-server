# Gate registry validator (documentation maintenance).
#
# The registry in docs/GATE_REGISTRY.md is the numbered index of every validator.
# This meta-gate keeps four views consistent:
#   1. scripts/validate/validate-*.ps1 files
#   2. the registry rows (ID, gate, script, mode)
#   3. the validator list in invoke-engineering-validation.ps1
#   4. report-only-gates.txt
#
# Adding a gate without updating the registry, or registering a gate that is not invoked,
# fails here. IDs must be sequential G01, G02, ... so the registry stays ordered.
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate/validate-gate-registry.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$registryPath = Join-Path $root 'docs\GATE_REGISTRY.md'
$reportOnlyPath = Join-Path $root 'scripts\validate\report-only-gates.txt'
$invokePath = Join-Path $root 'scripts\validate\invoke-engineering-validation.ps1'
$worklistPath = Join-Path $root '.outputs\quality\gate-registry-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()

if (-not (Test-Path -LiteralPath $registryPath)) {
  Write-Error 'Gate registry is missing: docs/GATE_REGISTRY.md'
  exit 1
}

$text = [System.IO.File]::ReadAllText($registryPath, [System.Text.UTF8Encoding]::new($false, $true))
$rowPattern = '(?m)^\|\s*(G\d{2})\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|'
$rows = [regex]::Matches($text, $rowPattern)
if ($rows.Count -eq 0) {
  Write-Error 'Gate registry contains no rows.'
  exit 1
}

$registeredGates = @{}
$registeredScripts = @{}
$expectedId = 1
foreach ($row in $rows) {
  $id = $row.Groups[1].Value
  $gate = $row.Groups[2].Value.Trim()
  $script = $row.Groups[3].Value.Trim()
  $mode = $row.Groups[4].Value.Trim().ToLowerInvariant()

  $expected = 'G{0:D2}' -f $expectedId
  if ($id -ne $expected) {
    $violations.Add("Registry ID '$id' is out of order; expected '$expected'.")
  }
  $expectedId++

  if ($registeredGates.ContainsKey($gate)) {
    $violations.Add("Duplicate gate name in registry: $gate")
  }
  $registeredGates[$gate] = $script

  if ($registeredScripts.ContainsKey($script)) {
    $violations.Add("Duplicate script in registry: $script")
  }
  $registeredScripts[$script] = $gate

  if (-not (Test-Path -LiteralPath (Join-Path $root "scripts/validate/$script") -PathType Leaf)) {
    $violations.Add("Registry references a missing script: $script")
  }
  if ($mode -notin @('blocking', 'report-only')) {
    $violations.Add("Registry mode must be blocking or report-only: $gate -> $mode")
  }
}

$actualScripts = Get-ChildItem -LiteralPath (Join-Path $root 'scripts\validate') -Filter 'validate-*.ps1' -File |
  Select-Object -ExpandProperty Name
foreach ($script in $actualScripts) {
  if (-not $registeredScripts.ContainsKey($script)) {
    $violations.Add("Validator script is not registered in GATE_REGISTRY.md: $script")
  }
}

$invokeText = [System.IO.File]::ReadAllText($invokePath, [System.Text.UTF8Encoding]::new($false, $true))
$invoked = [System.Collections.Generic.List[string]]::new()
$inValidators = $false
foreach ($line in ($invokeText -split "`n")) {
  if ($line -match '^\s*\$validators\s*=\s*@\(') { $inValidators = $true; continue }
  if ($inValidators -and $line -match '^\s*\)\s*$') { break }
  if ($inValidators -and $line -match "Name\s*=\s*'([^']+)'") { $invoked.Add($Matches[1]) }
}

if ($invoked.Count -eq 0) {
  $violations.Add('Cannot find the $validators block in invoke-engineering-validation.ps1.')
}
foreach ($gate in $invoked) {
  if (-not $registeredGates.ContainsKey($gate)) {
    $violations.Add("Invoked gate is not registered in GATE_REGISTRY.md: $gate")
  }
}
foreach ($gate in $registeredGates.Keys) {
  if ($gate -notin $invoked) {
    $violations.Add("Registered gate is not invoked by invoke-engineering-validation.ps1: $gate")
  }
}

$reportOnlyGates = [System.Collections.Generic.HashSet[string]]::new()
if (Test-Path -LiteralPath $reportOnlyPath) {
  foreach ($line in [System.IO.File]::ReadAllLines($reportOnlyPath, [System.Text.UTF8Encoding]::new($false))) {
    if ($line -match '^\s*([a-z0-9-]+)\s*\|') { [void]$reportOnlyGates.Add($Matches[1]) }
  }
}
foreach ($row in $rows) {
  $gate = $row.Groups[2].Value.Trim()
  $mode = $row.Groups[4].Value.Trim().ToLowerInvariant()
  $expectedMode = if ($reportOnlyGates.Contains($gate)) { 'report-only' } else { 'blocking' }
  if ($mode -ne $expectedMode) {
    $violations.Add("Registry mode mismatch for '$gate': registry=$mode, report-only-gates=$expectedMode")
  }
}

if ($violations.Count -eq 0) {
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host "Gate registry validation passed ($($rows.Count) gates)."
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "Gate registry: $($violations.Count) violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | Select-Object -First 30 | ForEach-Object { Write-Error $_ }
Write-Error "Gate registry: $($violations.Count) violation(s) in total. Worklist: $worklistPath"
exit 1
