# Repository hygiene gate (G-14 + G-15).
#
# G-14: the repository must have exactly one engineering validation entry point.
#       A second entry (a standalone verify batch, or a bundled release tool) is a violation,
#       because it silently runs a different subset of checks.
# G-15: the workspace root must contain repository directories only (no editor settings,
#       no stray reports, no generated files).
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate/validate-repository-hygiene.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$workspaceRoot = Split-Path -Parent $root
$worklistPath = Join-Path $root '.outputs\quality\repository-hygiene-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()

# --- G-14: single gate entry point ---
$secondEntries = @('release_manager', 'start_release_manager.bat')
foreach ($candidate in $secondEntries) {
  if (Test-Path -LiteralPath (Join-Path $root $candidate)) {
    $violations.Add("second gate/tool entry inside the repository: $candidate")
  }
}
foreach ($verifyBatch in (git -C $root ls-files '*verify.bat')) {
  if ($verifyBatch -ne 'scripts/validate/verify.bat') { $violations.Add("second validation batch: $verifyBatch") }
}

# --- G-15: workspace root allowlist ---
$allowedWorkspaceEntries = @(
  '.github',
  'open-im-server',
  'open-chat-app',
  'open-chat-admin',
  'open-chat-desktop',
  'open-chat-turn',
  'open-saas-admin',
  'open-saas-mobile',
  'open-website',
  'open-green-pass',
  '.outputs',
  '.vscode',
  'artifacts'
)
if (Test-Path -LiteralPath $workspaceRoot) {
  foreach ($entry in (Get-ChildItem -LiteralPath $workspaceRoot -Force)) {
    if ($allowedWorkspaceEntries -notcontains $entry.Name) {
      $violations.Add("unexpected workspace-root entry: $($entry.Name) (keep the root to repository directories only)")
    }
  }
}

# --- stray artifacts in this repository root ---
$strayPatterns = @('*_dump.txt', 'cursor.mark', '*.orig', '*.rej', '*.bak')
foreach ($pattern in $strayPatterns) {
  foreach ($file in (git -C $root ls-files $pattern)) {
    $violations.Add("stray tracked artifact: $file")
  }
}

if ($violations.Count -eq 0) {
  # See validate-tenant-sql.ps1: a passing gate must not leave a stale worklist claiming work remains.
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host 'Repository hygiene validation passed (single gate entry, clean workspace root).'
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "Repository hygiene: $($violations.Count) finding(s). Worklist: $worklistPath"
  exit 0
}

$violations | ForEach-Object { Write-Error $_ }
exit 1
