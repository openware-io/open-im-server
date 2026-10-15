# Flyway migration immutability gate (G-07).
#
# Freeze semantics (DC-10): a migration set is frozen once an explicit baseline tag exists.
# Until then migrations may be rebuilt and this gate only reports that state.
#
# A baseline tag is either:
#   - a tag whose annotation contains a standalone "freeze: true" line (written by the documented
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
  # Structured marker only: a loose word match also catches annotations that DISCLAIM a freeze
  # (for example a note saying "this tag is not a freeze point"), which silently blocks rebuilds.
  $message = (git -C $root tag -l --format='%(contents)' $tag) -join "`n"
  if ($message -match '(?m)^\s*freeze:\s*true\s*$') { $baselineTag = $tag; break }
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

# Committed history alone is not enough. The most common way to break a freeze is to edit a migration
# and not commit yet, and a "$baselineTag..HEAD" diff cannot see the working tree at all - a probe that
# appended a line to a frozen migration passed this gate before the check below existed. Compare the
# working tree against HEAD as well, so both committed and uncommitted rewrites are caught.
$worktree = git -C $root diff --name-status HEAD -- '*db/migration/*.sql'
foreach ($line in $worktree) {
  if ([string]::IsNullOrWhiteSpace($line)) { continue }
  $parts = $line -split "`t"
  $status = $parts[0]
  $file = $parts[-1]
  if ($status -match '^(M|D)') {
    $violations.Add("${status}: $file (uncommitted migration change while frozen at $baselineTag)")
  }
}
$untracked = git -C $root ls-files --others --exclude-standard -- '*db/migration/*.sql'
foreach ($file in $untracked) {
  if ([string]::IsNullOrWhiteSpace($file)) { continue }
  # New migrations are allowed; they are listed for visibility only, not as violations.
  Write-Host "New migration (allowed while frozen): $file"
}

if ($violations.Count -eq 0) {
  # See validate-tenant-sql.ps1: a passing gate must not leave a stale worklist claiming work remains.
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
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
