# Fully-qualified name validator (G-05).
#
# ENGINEERING_RULES: fully-qualified type/constant/static-member references are forbidden; use imports.
# Nothing enforced this before, and config/checkstyle/checkstyle.xml only enables UnusedImports --
# which actively encourages writing fully-qualified names to dodge unused-import warnings.
#
# Heuristic: a code line (not package/import/comment) that references a known root package chain
# (io.openware, java, javax, jakarta, org, com) with at least two dots and a final UpperCamelCase
# segment is reported. Add/remove roots and exceptions below when the stack changes.
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate/validate-java-fully-qualified-names.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\java-fully-qualified-names-worklist.txt'
$exceptionsPath = Join-Path $root 'scripts\validate\java-fully-qualified-exceptions.txt'
$violations = [System.Collections.Generic.List[string]]::new()

# Documented, reasoned exceptions: an import is impossible, not merely inconvenient.
# A stale entry (one that no longer matches a real occurrence) is itself reported, so the list can
# only shrink and can never become a dumping ground.
$exceptions = @{}
if (Test-Path -LiteralPath $exceptionsPath) {
  foreach ($raw in [System.IO.File]::ReadAllLines($exceptionsPath, [System.Text.UTF8Encoding]::new($false, $true))) {
    $line = $raw.Trim()
    if ($line -eq '' -or $line.StartsWith('#')) { continue }
    $parts = $line -split '\|'
    if ($parts.Count -lt 3) { continue }
    $key = ($parts[0].Trim() -replace '\\', '/') + ' -> ' + $parts[1].Trim()
    $exceptions[$key] = $parts[2].Trim()
  }
}
$usedExceptions = @{}

$qualifiedPattern = '\b(?:io\.openware|java|javax|jakarta|org|com)(?:\.[a-z][A-Za-z0-9_]*){2,}\.[A-Z][A-Za-z0-9_]*'

Push-Location $root
try {
  foreach ($relativePath in (git -C $root ls-files '*.java')) {
    if ($relativePath -notmatch '/src/main/java/') { continue }
    $path = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $lineNumber = 0
    $inBlockComment = $false
    foreach ($line in [System.IO.File]::ReadLines($path, [System.Text.UTF8Encoding]::new($false, $true))) {
      $lineNumber++
      $trimmed = $line.Trim()
      if ($inBlockComment) { if ($trimmed.Contains('*/')) { $inBlockComment = $false }; continue }
      if ($trimmed.StartsWith('/*')) { if (-not $trimmed.Contains('*/')) { $inBlockComment = $true }; continue }
      if ($trimmed.StartsWith('*') -or $trimmed.StartsWith('//') -or $trimmed.StartsWith('package ') -or $trimmed.StartsWith('import ')) { continue }
      foreach ($match in [regex]::Matches($line, $qualifiedPattern)) {
        $key = ($relativePath -replace '\\', '/') + ' -> ' + $match.Value
        if ($exceptions.ContainsKey($key)) { $usedExceptions[$key] = $true; continue }
        $violations.Add("fully-qualified reference: ${relativePath}:$lineNumber -> $($match.Value)")
      }
    }
  }
} finally {
  Pop-Location
}

foreach ($key in $exceptions.Keys) {
  if (-not $usedExceptions.ContainsKey($key)) {
    $violations.Add("stale exception (no longer matches any occurrence, remove it): $key")
  }
}

if ($violations.Count -eq 0) {
  # See validate-tenant-sql.ps1: a passing gate must not leave a stale worklist claiming work remains.
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host 'Java fully-qualified name validation passed.'
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "Java fully-qualified names: $($violations.Count) pending violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | Select-Object -First 30 | ForEach-Object { Write-Error $_ }
Write-Error "Java fully-qualified names: $($violations.Count) violation(s) in total. Worklist: $worklistPath"
exit 1
