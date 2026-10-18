# Reserved identifier validator (standards/10 "reserved field names").
#
# `version` is a semantic reserved word in this project: if it exists it must be an
# optimistic-lock token (framework @Version or explicit CAS). Non-lock uses must be renamed
# to revision/rowVersion. Two reviewed legacy exceptions are recorded but not treated as locks:
#   BUSINESS_VERSION  release version string; a separate row_version column is the lock
#   COUNTER           atomic condition update guards concurrency; version is only a revision
# Every current PENDING model is recorded debt; new version fields must be reviewed first.
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate/validate-reserved-identifiers.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$reviewPath = Join-Path $root 'scripts\validate\reserved-identifiers-reviewed.txt'
$worklistPath = Join-Path $root '.outputs\quality\reserved-identifiers-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()

$reviewed = @{}
if (Test-Path -LiteralPath $reviewPath) {
  foreach ($line in [System.IO.File]::ReadAllLines($reviewPath, [System.Text.UTF8Encoding]::new($false))) {
    $trimmed = $line.Trim()
    if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
    $parts = $trimmed -split '\|'
    if ($parts.Count -lt 2) { continue }
    $status = $parts[1].Trim()
    $reason = if ($parts.Count -ge 3) { $parts[2].Trim() } else { '' }
    $reviewed[$parts[0].Trim().Replace('\', '/')] = [pscustomobject]@{
      Status = $status
      Reason = $reason
    }
  }
}

$versionPos = [System.Collections.Generic.HashSet[string]]::new()
$pending = [System.Collections.Generic.List[string]]::new()

Push-Location $root
try {
  foreach ($relativePath in (git -C $root ls-files '*.java')) {
    if ($relativePath -notmatch '/infra/.*/po/.*Po\.java$') { continue }
    $path = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $text = [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false, $true))
    if ($text -notmatch 'private\s+\w+\s+version\s*;|Integer\s+version|Long\s+version') { continue }

    $normalized = $relativePath.Replace('\', '/')
    [void]$versionPos.Add($normalized)
    if (-not $reviewed.ContainsKey($normalized)) {
      $violations.Add("New version field must be reviewed and registered in reserved-identifiers-reviewed.txt: $normalized")
    }
    elseif ($reviewed[$normalized].Status -eq 'PENDING') {
      $pending.Add($normalized)
    }
    elseif ($reviewed[$normalized].Status -eq 'CAS') {
      $evidenceMatch = [regex]::Match($reviewed[$normalized].Reason, 'evidence=(\S+)')
      if (-not $evidenceMatch.Success) {
        $violations.Add("CAS entry must cite an evidence file: $normalized")
      }
      else {
        $evidencePath = Join-Path $root $evidenceMatch.Groups[1].Value
        if (-not (Test-Path -LiteralPath $evidencePath -PathType Leaf)) {
          $violations.Add("CAS evidence file does not exist: $($evidenceMatch.Groups[1].Value)")
        }
        else {
          $evidenceCode = [System.IO.File]::ReadAllText($evidencePath, [System.Text.UTF8Encoding]::new($false, $true))
          if ($evidenceCode -notmatch 'getVersion|version\s*=\s*#\{|version\s*=\s*version\s*\+') {
            $violations.Add("CAS evidence file has no version predicate: $($evidenceMatch.Groups[1].Value)")
          }
        }
      }
    }
  }

  foreach ($entry in $reviewed.Keys) {
    if (-not $versionPos.Contains($entry)) {
      $violations.Add("Stale reservation entry (PO no longer has version): $entry")
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -eq 0) {
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host "Reserved identifier validation passed ($($pending.Count) PENDING version model(s))."
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "Reserved identifiers: $($violations.Count) violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | Select-Object -First 30 | ForEach-Object { Write-Error $_ }
Write-Error "Reserved identifiers: $($violations.Count) violation(s) in total. Worklist: $worklistPath"
exit 1
