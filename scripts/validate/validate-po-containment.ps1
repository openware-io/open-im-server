# PO containment validator (stands/10 "object isolation and conversion").
#
# The standard's object-isolation table states that infra.persistence.po must not leak into the API,
# application or domain layers. service-api-boundaries covers controllers and the application layer with
# one pattern, and domain-model-purity covers the domain layer; this gate states the rule once, for all
# three layers, so a PO import has exactly one obvious home to fail.
#
# Scope: tracked Java files under /api/, /application/ or /domain/ inside service modules.
#
# Reported: any import of a type under a .infra.persistence.po package.
#
# Usage: powershell -File scripts/validate/validate-po-containment.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\po-containment-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()

Push-Location $root
try {
  foreach ($relativePath in (git -C $root ls-files '*.java')) {
    if ($relativePath -notmatch '/src/main/java/') { continue }
    if ($relativePath -notmatch '/(api|application|domain)/') { continue }
    $path = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $text = [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false, $true))

    $lineNumber = 0
    foreach ($line in ($text -split "`n")) {
      $lineNumber++
      $trimmed = $line.Trim()
      if ($trimmed -notmatch '^\s*import\s') { continue }
      if ($trimmed -match '\.infra\.persistence\.po\.') {
        $violations.Add("layer imports a persistence object: ${relativePath}:$lineNumber")
      }
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -eq 0) {
  # See validate-tenant-sql.ps1: a passing gate must not leave a stale worklist claiming work remains.
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host 'PO containment validation passed.'
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "PO containment: $($violations.Count) violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | Select-Object -First 30 | ForEach-Object { Write-Error $_ }
Write-Error "PO containment: $($violations.Count) violation(s) in total. Worklist: $worklistPath"
exit 1
