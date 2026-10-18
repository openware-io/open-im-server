# Observability redaction validator (standards/40 "log redaction", static part).
#
# Rule: log statements must not print credentials or signature material. This catches the
# common direct shape `log.info(..., x.token(), ...)` / password()/secret()/signature().
# It is intentionally narrow: masked/prefixed/hash values are not flagged, and a runtime
# log-sampling check remains a manual item.
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate-observability-redaction.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\observability-redaction-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()

# A log call whose argument list reaches a credential accessor.
$logPattern = 'log\.(trace|debug|info|warn|error)\s*\((?s:.*?)\)'
$credentialAccessor = '\.\s*(token|password|secret|accessKey|secretKey|signature|authorization)\s*\(\s*\)'

Push-Location $root
try {
  foreach ($relativePath in (git -C $root ls-files '*.java')) {
    if ($relativePath -notmatch '/src/main/java/') { continue }
    $path = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $text = [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false, $true))
    foreach ($match in [regex]::Matches($text, $logPattern)) {
      if ($match.Value -match $credentialAccessor) {
        $violations.Add("Log statement prints a credential/signature accessor: $relativePath")
      }
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -eq 0) {
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host 'Observability redaction validation passed.'
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "Observability redaction: $($violations.Count) violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | Select-Object -First 30 | ForEach-Object { Write-Error $_ }
Write-Error "Observability redaction: $($violations.Count) violation(s) in total. Worklist: $worklistPath"
exit 1
