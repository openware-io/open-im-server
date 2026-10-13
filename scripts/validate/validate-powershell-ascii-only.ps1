# PowerShell ASCII-only validator (G-10).
#
# standards/30: "scripts/validate/*.ps1 must stay ASCII-only", because Windows PowerShell 5.1
# decodes a BOM-less .ps1 as ANSI (GBK): any non-ASCII byte inside a Chinese regex or literal is
# silently mangled, and the script then matches nothing or throws. This was the root cause of
# N-16 and N-20, and it recurred in batch 1/2 because the convention was not enforced by a gate.
#
# The rule as written covers scripts/validate, but every .ps1 in this repository is BOM-less and
# Windows CI runs them through powershell.exe, so the same hazard applies to scripts/verify,
# scripts/deploy and the root scripts. This gate therefore scans scripts/**/*.ps1 (the extended
# scope is recorded in docs/renovation/ENGINEERING_GOVERNANCE_03_REMEDIATION.md, N-21).
#
# Pre-existing offenders that predate the gate are listed in powershell-ascii-allowlist.txt with
# the batch that clears them. An allowlisted file that no longer contains non-ASCII bytes is
# itself a violation, so the list can only shrink.
#
# IMPORTANT: keep this file ASCII-only. This gate cannot be exempt from itself.
#
# Usage: powershell -File scripts/validate/validate-powershell-ascii-only.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$scanRoot = Join-Path $root 'scripts'
$allowlistPath = Join-Path $PSScriptRoot 'powershell-ascii-allowlist.txt'
$worklistPath = Join-Path $root '.outputs\quality\powershell-ascii-only-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()

$allowed = [System.Collections.Generic.Dictionary[string, string]]::new([System.StringComparer]::OrdinalIgnoreCase)
$allowedOrder = [System.Collections.Generic.List[string]]::new()
if (Test-Path -LiteralPath $allowlistPath -PathType Leaf) {
  foreach ($line in [System.IO.File]::ReadAllLines($allowlistPath, [System.Text.UTF8Encoding]::new($false, $true))) {
    $entry = $line.Trim()
    if (-not $entry -or $entry.StartsWith('#')) { continue }
    $parts = $entry -split '\|'
    if ($parts.Count -lt 3) {
      $violations.Add("malformed allowlist entry (expected: path | clearing batch | reason): $entry")
      continue
    }
    $relative = $parts[0].Trim().Replace('\', '/')
    $batch = $parts[1].Trim()
    $reason = (($parts[2..($parts.Count - 1)] -join '|')).Trim()
    if (-not $batch -or -not $reason) {
      $violations.Add("allowlist entry needs a clearing batch and a reason: $entry")
      continue
    }
    $allowed[$relative] = $batch
    [void]$allowedOrder.Add($relative)
  }
} else {
  $violations.Add("allowlist not found: $allowlistPath")
}

Write-Host "PowerShell ASCII-only scope: scripts/**/*.ps1 ($($allowed.Count) deferred file(s))."

$seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
foreach ($file in (Get-ChildItem -Path $scanRoot -Recurse -Filter '*.ps1' -File)) {
  $relativePath = $file.FullName.Substring($root.Length + 1).Replace('\', '/')
  $bytes = [System.IO.File]::ReadAllBytes($file.FullName)

  $firstNonAsciiLine = 0
  $nonAsciiChars = 0
  $lineNumber = 1
  $currentLineHasNonAscii = $false
  foreach ($byte in $bytes) {
    if ($byte -eq 0x0A) {
      if ($currentLineHasNonAscii -and $firstNonAsciiLine -eq 0) { $firstNonAsciiLine = $lineNumber }
      $lineNumber++
      $currentLineHasNonAscii = $false
      continue
    }
    if ($byte -gt 0x7F) {
      $nonAsciiChars++
      $currentLineHasNonAscii = $true
    }
  }
  if ($currentLineHasNonAscii -and $firstNonAsciiLine -eq 0) { $firstNonAsciiLine = $lineNumber }

  if ($nonAsciiChars -eq 0) { continue }
  [void]$seen.Add($relativePath)

  $deferredTo = $null
  if ($allowed.TryGetValue($relativePath, [ref]$deferredTo)) {
    Write-Host "Deferred ($deferredTo): $relativePath ($nonAsciiChars non-ASCII byte(s), first at line $firstNonAsciiLine)"
    continue
  }

  $violations.Add("non-ASCII bytes in a BOM-less .ps1: ${relativePath}:$firstNonAsciiLine ($nonAsciiChars byte(s))")
}

foreach ($relative in $allowedOrder) {
  if (-not $seen.Contains($relative)) {
    $violations.Add("stale allowlist entry (file is missing or already ASCII-only, remove it): $relative")
  }
}

if ($violations.Count -eq 0) {
  Write-Host 'PowerShell ASCII-only validation passed.'
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "PowerShell ASCII-only: $($violations.Count) pending violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | Select-Object -First 30 | ForEach-Object { Write-Error $_ }
Write-Error "PowerShell ASCII-only: $($violations.Count) violation(s) in total. Worklist: $worklistPath"
exit 1
