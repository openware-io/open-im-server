# Silent catch validator (G-06).
#
# ENGINEERING_RULES: silently swallowing exceptions is forbidden; when a flow must continue, it must log at warn level and state the degradation semantics.
# The existing java-exception-logging gate only greps for printStackTrace(), so silently swallowed
# exceptions were never detected.
#
# Heuristic: for every catch block, extract the brace-balanced body and require it to contain a log
# call, a (re)throw, an addSuppressed, or an explicit degradation marker.
#
# Degradation marker: the line immediately above the catch keyword must be
#     // degradation: <reason>
# with a non-empty reason. This is the documented escape hatch for a deliberate fallback that must not
# log at warn per occurrence (for example returning a default when an optional value cannot be parsed).
# Requiring a reason keeps it honest; requiring the exact position keeps it tied to one catch block, so
# it cannot be sprinkled around to silence the gate. A marker above a catch that is already handled by
# a log or a throw is reported as stale, so the opt-outs can only shrink.
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

      # The line immediately above the catch keyword, used for the degradation marker.
      # The prefix ends at the catch keyword, so the segment after its last newline is only the catch
      # line's indentation; the line above it is therefore the second-to-last segment.
      $prefix = $text.Substring(0, $catchMatch.Index)
      $prefixLines = $prefix -split "`n"
      $prevLine = ''
      if ($prefixLines.Count -ge 2) { $prevLine = $prefixLines[$prefixLines.Count - 2].Trim() }
      elseif ($prefixLines.Count -eq 1) { $prevLine = $prefixLines[0].Trim() }
      $hasMarker = $prevLine -match '^//\s*degradation:\s*\S'

      if ($body -match $handlingPattern) {
        # A marker above an already-handled catch is redundant bookkeeping; report it so the opt-out
        # list cannot grow silently.
        if ($hasMarker) {
          $violations.Add("stale degradation marker (this catch already logs or throws): ${relativePath}:$lineNumber")
        }
        continue
      }
      if ($hasMarker) { continue }

      $compact = ($body -replace '\s+', ' ').Trim()
      $label = if ($compact.Length -eq 0) { 'empty catch block' } else { 'catch block without log/throw' }
      $violations.Add("$label`: ${relativePath}:$lineNumber")
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -eq 0) {
  # See validate-tenant-sql.ps1: a passing gate must not leave a stale worklist claiming work remains.
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
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
