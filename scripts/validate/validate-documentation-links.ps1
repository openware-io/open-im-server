# Documentation link and index validator (G-10).
#
# Enforces two objective rules from standards/00:
#   1. every relative Markdown link in a tracked .md file must resolve to an existing path
#      (rule 37: references to repository documents/scripts/directories must be clickable)
#   2. the root README documentation index must link every file under docs/standards/
#
# Anchors, absolute URLs and mailto links are ignored. Paths inside code spans are NOT checked
# here (that rule is heuristic and tracked separately in the remediation plan).
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate/validate-documentation-links.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\documentation-links-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()

Push-Location $root
try {
  $markdownFiles = git -C $root ls-files '*.md'

  # --- 1) relative link resolution ---
  foreach ($relativePath in $markdownFiles) {
    $path = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $directory = Split-Path -Parent $path
    $lineNumber = 0
    foreach ($line in [System.IO.File]::ReadLines($path, [System.Text.UTF8Encoding]::new($false, $true))) {
      $lineNumber++
      foreach ($match in [regex]::Matches($line, '\]\((?<target>[^)\s]+)\)')) {
        $target = $match.Groups['target'].Value
        if ($target -match '^(https?:|mailto:|#|tel:)') { continue }
        $clean = ($target -split '#')[0]
        if ([string]::IsNullOrWhiteSpace($clean)) { continue }
        $clean = $clean.Trim('<', '>')
        $resolved = Join-Path $directory $clean
        if (-not (Test-Path -LiteralPath $resolved)) {
          $violations.Add("broken relative link: ${relativePath}:$lineNumber -> $target")
        }
      }
    }
  }

  # --- 2) README documentation index completeness ---
  $readmePath = Join-Path $root 'README.md'
  if (Test-Path -LiteralPath $readmePath -PathType Leaf) {
    $readme = [System.IO.File]::ReadAllText($readmePath, [System.Text.UTF8Encoding]::new($false, $true))
    foreach ($standard in (git -C $root ls-files 'docs/standards/*.md')) {
      $fileName = Split-Path -Leaf $standard
      if ($readme -notmatch [regex]::Escape($fileName)) {
        $violations.Add("README.md documentation index does not link $standard")
      }
    }
  } else {
    $violations.Add('README.md is missing')
  }
} finally {
  Pop-Location
}

if ($violations.Count -eq 0) {
  # See validate-tenant-sql.ps1: a passing gate must not leave a stale worklist claiming work remains.
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host 'Documentation link and index validation passed.'
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "Documentation links/index: $($violations.Count) pending violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | Select-Object -First 30 | ForEach-Object { Write-Error $_ }
Write-Error "Documentation links/index: $($violations.Count) violation(s) in total. Worklist: $worklistPath"
exit 1
