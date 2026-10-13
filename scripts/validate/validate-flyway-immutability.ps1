# Flyway migration immutability gate (G-07).
#
# Freeze semantics (DC-10): a migration set is frozen once an explicit baseline tag exists.
# Until then migrations may be rebuilt and this gate only reports that state.
#
# A baseline tag is either:
#   - a tag whose annotation message contains the word "frozen" (as written by the documented
#     baseline command), or
#   - a tag named baseline-* .
#
# After freezing, modifying or deleting a migration that exists in the tag FAILS the gate;
# adding new migrations is always allowed.
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate/validate-flyway-immutability.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\flyway-immutability-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()

$baselineTag = $null
foreach ($tag in (git -C $root tag --list)) {
  if ($tag -like 'baseline-*') { $baselineTag = $tag; break }
  $message = (git -C $root tag -l --format='%(contents)' $tag) -join ' '
  if ($message -match '(?i)frozen') { $baselineTag = $tag; break }
}

if (-not $baselineTag) {
  Write-Host 'Flyway immutability: not frozen (no baseline tag with a freeze statement); baseline rebuild is allowed (DC-10).'
  exit 0
}

$changed = git -C $root diff --name-status "$baselineTag..HEAD" -- '*db/migration/*.sql'
foreach ($line in $changed) {
  if ([string]::IsNullOrWhiteSpace($line)) { continue }
  $parts = $line -split "`t"
  $status = $parts[0]
  $file = $parts[-1]
  if ($status -match '^(M|D)') {
    $violations.Add("${status}: $file (migration content changed after baseline tag $baselineTag)")
  }
}

if ($violations.Count -eq 0) {
  Write-Host "Flyway immutability: frozen at $baselineTag, no rewritten migrations detected."
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "Flyway immutability: $($violations.Count) rewritten migration(s) after $baselineTag. Worklist: $worklistPath"
  exit 0
}

$violations | ForEach-Object { Write-Error $_ }
exit 1
