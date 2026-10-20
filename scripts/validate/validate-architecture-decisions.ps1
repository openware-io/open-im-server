# Architecture Decision Record validator (G47).
#
# ADRs are historical decisions under docs/20-architecture/adr/. This gate
# enforces the ADR standard: index/template presence, file naming, required
# sections, status values, unique numbers and index coverage.
#
# IMPORTANT: keep this file ASCII-only.

[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$adrRoot = Join-Path $root 'docs\20-architecture\adr'
$indexPath = Join-Path $adrRoot 'README.md'
$templatePath = Join-Path $adrRoot '0000-template.md'
$worklistPath = Join-Path $root '.outputs\quality\architecture-decisions-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()
$statuses = @('proposed', 'accepted', 'rejected', 'deprecated', 'superseded', 'withdrawn')

if (-not (Test-Path -LiteralPath $adrRoot -PathType Container)) {
  Write-Error 'ADR directory is missing: docs/20-architecture/adr'
  exit 1
}
if (-not (Test-Path -LiteralPath $indexPath -PathType Leaf)) {
  $violations.Add('missing ADR index: docs/20-architecture/adr/README.md')
}
if (-not (Test-Path -LiteralPath $templatePath -PathType Leaf)) {
  $violations.Add('missing ADR template: docs/20-architecture/adr/0000-template.md')
}

$indexText = if (Test-Path -LiteralPath $indexPath) {
  [System.IO.File]::ReadAllText($indexPath, [System.Text.UTF8Encoding]::new($false, $true))
} else { '' }

$numbers = @{}
$adrFiles = Get-ChildItem $adrRoot -File -Filter '*.md' | Where-Object {
  $_.Name -notin @('README.md', 'README.en.md', '0000-template.md')
}
foreach ($file in $adrFiles) {
  $relative = $file.FullName.Substring($root.Length + 1)
  if ($file.Name -notmatch '^(\d{4})-[a-z0-9-]+\.md$') {
    $violations.Add("ADR file name must be NNNN-kebab-case.md: $relative")
    continue
  }
  $number = $Matches[1]
  if ($numbers.ContainsKey($number)) {
    $violations.Add("duplicate ADR number ${number}: $relative and $($numbers[$number])")
  } else {
    $numbers[$number] = $relative
  }

  $text = [System.IO.File]::ReadAllText($file.FullName, [System.Text.UTF8Encoding]::new($false, $true))
  foreach ($heading in @('Status', 'Context', 'Decision', 'Consequences')) {
    if ($text -notmatch ('(?m)^##\s+' + $heading + '\b')) {
      $violations.Add("missing heading '$heading': $relative")
    }
  }
  $statusMatch = [regex]::Match($text, '(?ims)^##\s+Status[^\r\n]*\r?\n\s*(?:[*_`]+\s*)?(?<status>[a-z]+)')
  if (-not $statusMatch.Success -or $statusMatch.Groups['status'].Value -notin $statuses) {
    $violations.Add("invalid ADR status (expected $($statuses -join ', ')): $relative")
  }
  if ($indexText -notmatch [regex]::Escape($file.Name)) {
    $violations.Add("ADR is not listed in adr/README.md: $relative")
  }
}

if (Test-Path -LiteralPath $templatePath) {
  $template = [System.IO.File]::ReadAllText($templatePath, [System.Text.UTF8Encoding]::new($false, $true))
  foreach ($heading in @('Status', 'Context', 'Decision', 'Consequences')) {
    if ($template -notmatch ('(?m)^##\s+' + $heading + '\b')) {
      $violations.Add("missing heading '$heading': docs/20-architecture/adr/0000-template.md")
    }
  }
}

if ($violations.Count -eq 0) {
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host "Architecture decision validation passed ($(@($adrFiles).Count) ADRs)."
  exit 0
}

if ($ReportOnly) {
  $violations | ForEach-Object { Write-Warning $_ }
  Write-Warning "Architecture decisions: $($violations.Count) pending violation(s)."
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))
$violations | Select-Object -First 40 | ForEach-Object { Write-Error $_ }
Write-Error "Architecture decisions: $($violations.Count) violation(s) in total."
exit 1
