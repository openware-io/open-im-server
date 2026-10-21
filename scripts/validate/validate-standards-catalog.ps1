# Standards catalog validator (G48).
#
# Enforces the standards numbering contract:
#   - every standard is NNNN-prefixed (two-digit category block + sequence)
#   - IDs are unique
#   - an English summary exists
#   - every standard is listed in standards/README.md
#   - categories are one of the reserved blocks 00-69
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
$readmePath = Join-Path $standardsRoot 'README.md'
$worklistPath = Join-Path $root '.outputs\quality\standards-catalog-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()
$allowedBlocks = @('0', '1', '2', '3', '4', '5', '6')

if (-not (Test-Path -LiteralPath $standardsRoot -PathType Container)) {
  Write-Error 'Standards directory is missing: docs/80-development/standards'
  exit 1
}
if (-not (Test-Path -LiteralPath $readmePath -PathType Leaf)) {
  $violations.Add('missing standards index: docs/80-development/standards/README.md')
}
$readmeText = if (Test-Path -LiteralPath $readmePath) {
  [System.IO.File]::ReadAllText($readmePath, [System.Text.UTF8Encoding]::new($false, $true))
} else { '' }

$ids = @{}
$standards = Get-ChildItem $standardsRoot -File -Filter '*.md' | Where-Object {
  $_.Name -notin @('README.md', 'README.en.md') -and $_.Name -notmatch '\.en\.md$'
}
foreach ($file in $standards) {
  $relative = $file.FullName.Substring($root.Length + 1)
  if ($file.Name -notmatch '^(\d{2})_[A-Z0-9_]+\.md$') {
    $violations.Add("standard must use NN_NAME.md: $relative")
    continue
  }
  $id = $Matches[1]
  if ($allowedBlocks -notcontains $id.Substring(0, 1)) {
    $violations.Add("standard category block is not reserved (00-69): $relative")
  }
  if ($ids.ContainsKey($id)) {
    $violations.Add("duplicate standard number ${id}: $relative and $($ids[$id])")
  } else {
    $ids[$id] = $relative
  }
  $english = Join-Path $file.DirectoryName ($file.BaseName + '.en.md')
  if (-not (Test-Path -LiteralPath $english -PathType Leaf)) {
    $violations.Add("missing English summary: $relative")
  }
  if ($readmeText -notmatch [regex]::Escape($file.Name)) {
    $violations.Add("standard is not listed in standards/README.md: $relative")
  }
}

if ($violations.Count -eq 0) {
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host "Standards catalog validation passed ($(@($standards).Count) standards)."
  exit 0
}

if ($ReportOnly) {
  $violations | ForEach-Object { Write-Warning $_ }
  Write-Warning "Standards catalog: $($violations.Count) pending violation(s)."
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))
$violations | Select-Object -First 40 | ForEach-Object { Write-Error $_ }
Write-Error "Standards catalog: $($violations.Count) violation(s) in total."
exit 1
