# Silent catch validator (G-06).
#
# ENGINEERING_RULES: "严禁静默吞异常；确实需要继续流程时，必须至少记录 warn 日志并说明降级语义".
# The existing java-exception-logging gate only greps for printStackTrace(), so silently swallowed
# exceptions were never detected.
#
# Heuristic: for every catch block, extract the brace-balanced body and require it to contain a log
# call, a (re)throw, or an explicit degradation marker comment. Nested braces are handled by counting.
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate/validate-java-silent-catch.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\java-silent-catch-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()
$handlingPattern = '(?i)\b(log|logger|LOG|LOGGER)\s*\.|throw\s+new|\bthrow\s+[a-zA-Z]|rethrow|\.addSuppressed\('

Push-Location $root
try {
  foreach ($relativePath in (git -C $root ls-files '*.java')) {
    if ($relativePath -notmatch '/src/main/java/') { continue }
    $path = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $text = [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false, $true))

    foreach ($catchMatch in [regex]::Matches($text, '(?i)catch\s*\([^)]*\)\s*\{')) {
      $openIndex = $catchMatch.Index + $catchMatch.Length - 1
      $depth = 0
      $closeIndex = -1
      for ($i = $openIndex; $i -lt $text.Length; $i++) {
        $char = $text[$i]
        if ($char -eq '{') { $depth++ }
        elseif ($char -eq '}') { $depth--; if ($depth -eq 0) { $closeIndex = $i; break } }
      }
      if ($closeIndex -lt 0) { continue }
      $bodyStart = $openIndex + 1
      $body = $text.Substring($bodyStart, $closeIndex - $bodyStart)
      $lineNumber = ($text.Substring(0, $catchMatch.Index) -split "`n").Count
      if ($body -notmatch $handlingPattern) {
        $compact = ($body -replace '\s+', ' ').Trim()
        $label = if ($compact.Length -eq 0) { 'empty catch block' } else { 'catch block without log/throw' }
        $violations.Add("$label`: ${relativePath}:$lineNumber")
      }
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -eq 0) {
  Write-Host 'Java silent catch validation passed.'
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "Java silent catch: $($violations.Count) pending violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | Select-Object -First 30 | ForEach-Object { Write-Error $_ }
Write-Error "Java silent catch: $($violations.Count) violation(s) in total. Worklist: $worklistPath"
exit 1
