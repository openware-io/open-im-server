# Controller transaction-boundary validator (standards/10 layering).
#
# Rule: a controller receives HTTP input, validates parameters, converts DTOs and calls an
# application service. Transaction boundaries belong to the application layer; a controller
# annotated with @Transactional makes the HTTP adapter own atomicity, which then cannot be
# reused by schedulers, MQ consumers or other application callers.
#
# This gate is blocking from the start because the V1 worklist (12 methods across 6 controllers)
# is already at zero. A violation means a regression, not pending work.
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate/validate-controller-transaction-boundary.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\controller-transaction-boundary-worklist.txt'

$violations = [System.Collections.Generic.List[string]]::new()
$annotationPattern = '@Transactional\b'
$domainRoots = @('im-services', 'platform-services', 'common-services', 'group-services')

function Test-FileContains([string]$relativePath, [string]$pattern) {
  $path = Join-Path $root $relativePath
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $false }
  $text = [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false, $true))
  return $text -match $pattern
}

Push-Location $root
try {
  $tracked = git -C $root ls-files
  foreach ($domain in $domainRoots) {
    foreach ($file in ($tracked | Where-Object { $_ -like "$domain/*/*-service/src/main/java/*/controller/*" })) {
      if (Test-FileContains $file $annotationPattern) {
        $violations.Add("controller holds @Transactional: $file")
      }
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -eq 0) {
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host 'Controller/transaction boundary validation passed.'
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "Controller/transaction boundary: $($violations.Count) pending violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | Select-Object -First 30 | ForEach-Object { Write-Error $_ }
Write-Error "Controller/transaction boundary: $($violations.Count) violation(s) in total. Worklist: $worklistPath"
exit 1
