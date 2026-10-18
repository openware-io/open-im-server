# Observability metric validator (standards/40 "single metric vocabulary", static part).
#
# The codebase has no custom metrics yet; this gate makes the first ones obey the contract:
#   - a metric name may be defined once (no duplicate builder names across modules)
#   - high-cardinality identifiers (userId/sessionId/messageId/requestId) must not be tags
#
# It does not invent metrics or replace runtime metric review.
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate-observability-metrics.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\observability-metrics-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()
$names = @{}

function Remove-JavaComments([string]$text) {
  $withoutBlock = [regex]::Replace($text, '(?s)/\*.*?\*/', ' ')
  return [regex]::Replace($withoutBlock, '(?m)//.*$', '')
}

Push-Location $root
try {
  foreach ($relativePath in (git -C $root ls-files '*.java')) {
    if ($relativePath -notmatch '/src/main/java/') { continue }
    $path = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $code = Remove-JavaComments ([System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false, $true)))

    foreach ($match in [regex]::Matches($code, '(?:Counter|Timer|Gauge|DistributionSummary)\.builder\s*\(\s*"([^"]+)"')) {
      $name = $match.Groups[1].Value
      if ($names.ContainsKey($name)) {
        $violations.Add("Metric name '$name' is defined twice: $($names[$name]) and $relativePath")
      }
      else {
        $names[$name] = $relativePath
      }
    }

    if ($code -match '\.(?:tag|tags)\s*\(\s*"(?:userId|sessionId|messageId|requestId)"') {
      $violations.Add("High-cardinality identifier used as a metric tag: $relativePath")
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -eq 0) {
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host "Observability metric validation passed ($($names.Count) custom metric(s))."
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "Observability metrics: $($violations.Count) violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | ForEach-Object { Write-Error $_ }
Write-Error "Observability metrics: $($violations.Count) violation(s). Worklist: $worklistPath"
exit 1
