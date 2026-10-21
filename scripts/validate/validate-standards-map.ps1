# Standards map validator (G49).
#
# Ensures the standards map is a real map, not a stale table:
#   - every standard appears in the map
#   - every map row points to an existing standard
#   - each row has a layer (L0-L2), a domain and gate/evidence
#   - domains and layers use the declared vocabulary
#
# IMPORTANT: keep this file ASCII-only.

[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$standardsRoot = Join-Path $root 'docs\80-development\standards'
$mapPath = Join-Path $standardsRoot 'STANDARDS_MAP.md'
$worklistPath = Join-Path $root '.outputs\quality\standards-map-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()
$domains = @('foundation', 'governance', 'architecture', 'build', 'data', 'delivery', 'quality', 'security', 'collaboration', 'domain', 'messaging', 'media')

if (-not (Test-Path -LiteralPath $mapPath -PathType Leaf)) {
  Write-Error 'Standards map is missing: docs/80-development/standards/STANDARDS_MAP.md'
  exit 1
}
$mapText = [System.IO.File]::ReadAllText($mapPath, [System.Text.UTF8Encoding]::new($false, $true))

$mapFiles = @{}
$rowCount = 0
foreach ($line in ($mapText -split "`n")) {
  if ($line -notmatch '^\|\s*(\d{2})\s*\|\s*\[[^\]]+\]\(([^)]+)\)\s*\|\s*(L[0-2])\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|') {
    continue
  }
  $rowCount++
  $id = $Matches[1]
  $file = $Matches[2].Trim()
  $layer = $Matches[3]
  $domain = $Matches[4].Trim()
  $evidence = $Matches[5].Trim()
  if ($file -notmatch '\.md$') {
    $violations.Add("map row $id must reference a markdown standard: $file")
    continue
  }
  $full = Join-Path $standardsRoot $file
  if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
    $violations.Add("map row $id references a missing standard: $file")
  }
  if ($domain -notin $domains) {
    $violations.Add("map row $id has unknown domain '$domain'")
  }
  if ([string]::IsNullOrWhiteSpace($evidence)) {
    $violations.Add("map row $id has no gate/evidence")
  }
  if ($mapFiles.ContainsKey($file)) {
    $violations.Add("standard appears more than once in the map: $file")
  } else {
    $mapFiles[$file] = $id
  }
}

$standards = git -C $root ls-files 'docs/80-development/standards/*.md' | Where-Object {
  $_ -notmatch '/README(\.en)?\.md$' -and
  $_ -notmatch '/STANDARDS_MAP(\.en)?\.md$' -and
  $_ -notmatch '\.en\.md$'
}
foreach ($relative in $standards) {
  $name = Split-Path -Leaf $relative
  if (-not $mapFiles.ContainsKey($name)) {
    $violations.Add("standard is missing from STANDARDS_MAP.md: $name")
  }
}

if ($rowCount -eq 0) {
  $violations.Add('STANDARDS_MAP.md has no standard rows')
}

if ($violations.Count -eq 0) {
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host "Standards map validation passed ($rowCount rows)."
  exit 0
}

if ($ReportOnly) {
  $violations | ForEach-Object { Write-Warning $_ }
  Write-Warning "Standards map: $($violations.Count) pending violation(s)."
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))
$violations | Select-Object -First 40 | ForEach-Object { Write-Error $_ }
Write-Error "Standards map: $($violations.Count) violation(s) in total."
exit 1
