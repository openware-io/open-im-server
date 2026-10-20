# E2E verify-script inventory validator (standards/43 section 3).
#
# The standard's section 3 table is the only index of scripts/verify/*.ps1. This gate keeps
# the directory and the documented list in sync: a new script must be documented, and a
# documented script must still exist. It does not run the scripts (they need external
# endpoints/devices) and does not replace layer-order, real-device or cleanup checks.
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate/validate-verify-inventory.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$docPath = Join-Path $root 'docs\development\standards\43_E2E_TESTING_CONVENTIONS.md'
$verifyDir = Join-Path $root 'scripts\verify'
$worklistPath = Join-Path $root '.outputs\quality\verify-inventory-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()

if (-not (Test-Path -LiteralPath $docPath)) {
  Write-Error 'E2E standard is missing: docs/development/standards/43_E2E_TESTING_CONVENTIONS.md'
  exit 1
}

$doc = [System.IO.File]::ReadAllText($docPath, [System.Text.UTF8Encoding]::new($false, $true))
$documented = [System.Collections.Generic.HashSet[string]]::new()
foreach ($match in [regex]::Matches($doc, '(?<![\w/-])([a-z0-9][a-z0-9-]*\.ps1)')) {
  [void]$documented.Add($match.Groups[1].Value)
}

$actual = @(Get-ChildItem -LiteralPath $verifyDir -Filter '*.ps1' -File | Select-Object -ExpandProperty Name)

foreach ($script in $actual) {
  if (-not $documented.Contains($script)) {
    $violations.Add("verify script is not listed in standards/43 section 3: $script")
  }
}
foreach ($script in $documented) {
  if ($script -notin $actual) {
    $violations.Add("standards/43 lists a verify script that does not exist: $script")
  }
}

if ($violations.Count -eq 0) {
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host "Verify script inventory validation passed ($($actual.Count) scripts)."
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "Verify inventory: $($violations.Count) violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | ForEach-Object { Write-Error $_ }
Write-Error "Verify inventory: $($violations.Count) violation(s). Worklist: $worklistPath"
exit 1
