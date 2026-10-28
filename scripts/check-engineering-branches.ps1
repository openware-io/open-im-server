# Verify that every im-saas product repository uses the same version branch.
# Run from anywhere; the script resolves the parent workspace from its own path.
#
# IMPORTANT: keep this file ASCII-only.

[CmdletBinding()]
param(
  [string]$Branch = 'develop/2.4.0-hotel'
)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$repos = @(
  'open-im-server',
  'open-chat-app',
  'open-chat-desktop',
  'open-chat-admin',
  'open-saas-admin',
  'open-saas-mobile',
  'open-chat-turn',
  'open-website'
)

$failed = $false
$rows = @()
foreach ($name in $repos) {
  $path = Join-Path $workspace $name
  if (-not (Test-Path -LiteralPath (Join-Path $path '.git'))) {
    $rows += [pscustomobject]@{ Repo = $name; Current = '-'; Head = '-'; Remote = '-'; Clean = 'no'; Result = 'FAIL (missing repo)' }
    $failed = $true
    continue
  }
  $current = (git -C $path branch --show-current 2>$null)
  $head = (git -C $path rev-parse --short HEAD 2>$null)
  $dirty = @(git -C $path status --porcelain 2>$null).Count -gt 0
  $localExists = @(git -C $path branch --list $Branch).Count -gt 0
  $remoteLine = git -C $path ls-remote --heads origin "refs/heads/$Branch" 2>$null
  $remoteHead = if ($remoteLine) { ($remoteLine -split '\s+')[0].Substring(0, 7) } else { '-' }
  $result = 'PASS'
  if (-not $localExists) { $result = 'FAIL (no local branch)'; $failed = $true }
  elseif ($current -ne $Branch) { $result = 'FAIL (not checked out)'; $failed = $true }
  elseif ($dirty) { $result = 'FAIL (dirty tree)'; $failed = $true }
  elseif ($remoteHead -eq '-') { $result = 'FAIL (no remote branch)'; $failed = $true }
  elseif ($remoteHead -ne $head) { $result = 'FAIL (local/remote differ)'; $failed = $true }
  $rows += [pscustomobject]@{
    Repo = $name
    Current = $current
    Head = $head
    Remote = $remoteHead
    Clean = if ($dirty) { 'no' } else { 'yes' }
    Result = $result
  }
}

$rows | Format-Table -AutoSize
if ($failed) { exit 1 }
Write-Host "All product repositories are on $Branch and synced with origin."
