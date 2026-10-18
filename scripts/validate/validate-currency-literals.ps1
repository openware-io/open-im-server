# Currency literal validator (standards/16 currency conventions, "literal scan").
#
# Rule: business code must not hard-code currency codes or symbols. The only source is
# sdk/infrastructure's Currency enum and CurrencyResolver. Tests, docs, the enum itself and
# the gateway protocol fallback are out of scope.
#
# Scope: tracked Java under service modules' src/main/java. Comments are stripped before
# scanning so Javadoc examples do not count.
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate/validate-currency-literals.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\currency-literals-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()

# CNY / RMB / yen sign / the Chinese word for yuan, as double- or single-quoted literals.
# Unicode escapes keep this script ASCII-only.
$literalPattern = '"(?:CNY|RMB|\u00A5|\u5143)"|''(?:CNY|RMB|\u00A5|\u5143)'''

function Remove-JavaComments([string]$text) {
  $withoutBlock = [regex]::Replace($text, '(?s)/\*.*?\*/', ' ')
  return [regex]::Replace($withoutBlock, '(?m)//.*$', '')
}

Push-Location $root
try {
  $javaFiles = git -C $root ls-files 'im-services/*/*/src/main/java/*.java' 'platform-services/*/*/src/main/java/*.java' 'common-services/*/*/src/main/java/*.java' 'group-services/*/*/src/main/java/*.java'
  foreach ($relativePath in $javaFiles) {
    if ($relativePath -notmatch '/src/main/java/') { continue }
    $path = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $text = [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false, $true))
    $code = Remove-JavaComments $text
    if ($code -match $literalPattern) {
      $violations.Add("Hard-coded currency literal: $relativePath")
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -eq 0) {
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host 'Currency literal validation passed.'
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "Currency literals: $($violations.Count) violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | Select-Object -First 30 | ForEach-Object { Write-Error $_ }
Write-Error "Currency literals: $($violations.Count) violation(s) in total. Worklist: $worklistPath"
exit 1
