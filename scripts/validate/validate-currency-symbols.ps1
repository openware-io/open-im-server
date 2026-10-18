# Currency symbol-table validator (standards/16 "single symbol table").
#
# Rule: symbols, Chinese display names and code-to-symbol mappings come only from
# sdk/infrastructure's Currency enum. Business, gateway or SDK code must not build another
# table. The enum itself is the single allowed definition; tests and docs are out of scope.
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate-currency-symbols.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\currency-symbols-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()

# Exact literals: $, yen sign, the Chinese word for yuan, and the two display names.
# Unicode escapes keep this script ASCII-only.
$symbolPattern = '"(?:\$|\u00A5|\u5143|\u4EBA\u6C11\u5E01|\u7F8E\u5143)"'
# A code-to-symbol table: put("USD", ...) / Map.of("CNY", ...) outside the enum.
$mappingPattern = '(?:put|Map\.of)\s*\(\s*"(?:USD|CNY)"\s*,'

function Remove-JavaComments([string]$text) {
  $withoutBlock = [regex]::Replace($text, '(?s)/\*.*?\*/', ' ')
  return [regex]::Replace($withoutBlock, '(?m)//.*$', '')
}

Push-Location $root
try {
  $javaFiles = git -C $root ls-files '*.java'
  foreach ($relativePath in $javaFiles) {
    if ($relativePath -notmatch '/src/main/java/') { continue }
    if ($relativePath -notmatch '^(im-services|platform-services|common-services|group-services|sdk|gateways)/') { continue }
    if ($relativePath -match '/infrastructure/currency/Currency\.java$') { continue }
    $path = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $text = [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false, $true))
    $code = Remove-JavaComments $text
    if ($code -match $symbolPattern) {
      $violations.Add("Currency symbol/display literal outside Currency enum: $relativePath")
    }
    if ($code -match $mappingPattern) {
      $violations.Add("Self-built currency symbol table outside Currency enum: $relativePath")
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -eq 0) {
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host 'Currency symbol table validation passed.'
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "Currency symbols: $($violations.Count) violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | Select-Object -First 30 | ForEach-Object { Write-Error $_ }
Write-Error "Currency symbols: $($violations.Count) violation(s) in total. Worklist: $worklistPath"
exit 1
