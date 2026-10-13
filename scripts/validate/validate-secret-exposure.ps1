# Secret exposure validator.
#
# Detects committed credentials in tracked files. Replaces the previous assignment-shape-only
# check, which missed inline YAML values (k8s manifests) and PowerShell parameter defaults --
# that is why the ACK JWT secret and the verify-script passwords went unnoticed.
#
# Detection covers:
#   1. key/value assignments whose key looks secret-ish (password|secret|token|api-key|...)
#   2. inline Kubernetes env entries  - { name: X_SECRET, value: <literal> }
#   3. PowerShell assignments and parameter defaults  $Password = 'literal' / [string]$P = 'literal'
#   4. secret-typed config defaults  ${SOME_SECRET:literal-default}   (${VAR:?msg} is REQUIRED, not a default)
#   5. known historical values listed in a gitignored file (-RotatedCredentialFile)
#
# Classified non-credentials (Secret names, command substitutions, template placeholders,
# GitHub secrets references, test-only literals) are skipped by rule; anything left that is still
# a false positive gets an entry with a written reason in secret-exposure-allowlist.txt.
#
# NOTE: .java is intentionally NOT scanned. Field/parameter declarations produce too many false
# positives for a line-based heuristic; Java-side credential fallbacks are enforced separately
# (the DEV_FALLBACK_* check in batch 1).
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage:
#   powershell -File scripts/validate/validate-secret-exposure.ps1 [-ReportPath <file>]
#              [-RotatedCredentialFile <gitignored file>] [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly,
  [string]$ReportPath,
  [string]$RotatedCredentialFile
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if ([string]::IsNullOrWhiteSpace($ReportPath)) { $ReportPath = Join-Path $root '.outputs\quality\secret-exposure-report.txt' }
if ([string]::IsNullOrWhiteSpace($RotatedCredentialFile)) { $RotatedCredentialFile = Join-Path $root '.outputs\quality\rotated-credentials.txt' }

$candidateExtensions = @('.env', '.properties', '.yml', '.yaml', '.ps1', '.bat', '.sh', '.md', '.txt', '.json', '.tf', '.conf', '.sql')
$notes = [System.Collections.Generic.List[string]]::new()
$violations = [System.Collections.Generic.List[string]]::new()

# --- classified non-credentials (path:line # reason) ---
$allowlist = [System.Collections.Generic.HashSet[string]]::new()
$allowlistPath = Join-Path $PSScriptRoot 'secret-exposure-allowlist.txt'
if (Test-Path -LiteralPath $allowlistPath -PathType Leaf) {
  foreach ($line in [System.IO.File]::ReadAllLines($allowlistPath, [System.Text.UTF8Encoding]::new($false, $true))) {
    $entry = ($line -split '#')[0].Trim()
    if ($entry) { [void]$allowlist.Add($entry) }
  }
}

# --- historical values (optional, gitignored) ---
$historical = [System.Collections.Generic.List[string]]::new()
if (Test-Path -LiteralPath $RotatedCredentialFile -PathType Leaf) {
  foreach ($line in [System.IO.File]::ReadAllLines($RotatedCredentialFile, [System.Text.UTF8Encoding]::new($false, $true))) {
    $value = $line.Trim()
    if (-not $value -or $value.StartsWith('#') -or $value.Contains('=')) { continue }
    if ($value.Length -ge 8) { $historical.Add($value) }
  }
  $notes.Add("historical credential values loaded: $($historical.Count)")
} else {
  $notes.Add("historical credential file not found, historical-value comparison skipped: $RotatedCredentialFile")
}

$secretNamePattern = '(?i)(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credential|hmac|salt)'
# Keys that only carry a duration/size/limit are not credentials (for example JWT_EXPIRATION_MS).
$nonSecretKeySuffix = '(?i)_(MS|SEC|SECONDS|MINUTES|HOURS|DAYS|SIZE|COUNT|LIMIT|TTL|TIMEOUT|EXPIRATION|LENGTH|BYTES)$'
$nameLikeKeyPattern = '(?i)(secret|key|token)?(name|ref|path|file|type|provider|header|prefix|key|id)$'
$identifierValuePattern = '^[A-Za-z][A-Za-z0-9_\-]*$'
$safeValuePattern = '^(?:true|false|null|none|0|[0-9]+)$' +
  '|^\$\{[^}]+\}$' +
  '|^\$env:[A-Za-z_][A-Za-z0-9_]*$' +
  '|^\$[A-Za-z_][A-Za-z0-9_]*$' +
  '|^\$\{\{\s*secrets\.[^}]+\}\}$' +
  '|^\$\(.*$|^`.*$' +
  '|^(?:your|my|example|sample|test|dummy|placeholder|change[-_]?me|todo|xxx|redacted|masked|empty|unset|local)[\w\-\.]*$' +
  '|^(?:0123456789abcdef)+$' +
  '|^<[^>]+>$|^\*+$|^-+$|^""$'
$inlineValuePattern = '(?i)name\s*[:=]\s*["'']?(?<key>[A-Za-z_][A-Za-z0-9_.\-]*)["'']?\s*[,;]?\s*(?:value|stringData|password|token|secret)\s*[:=]\s*(?<value>[^,}\r\n]+)'
$assignmentPattern = '(?i)^\s*(?:-\s*)?(?<key>[A-Za-z_][A-Za-z0-9_.\-]*)\s*[:=]\s*(?<value>.+?)\s*$'
$powerShellPattern = '(?i)^\s*\[[^\]]+\]\s*\$?(?<key>[A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?<value>.+?)\s*$'
$defaultPattern = '\$\{(?<key>[A-Za-z_][A-Za-z0-9_]*)\s*:\s*(?<value>[^}]+)\}'

function Get-NormalizedValue([string]$value) {
  $trimmed = $value.Trim().Trim(',').Trim().Trim('"').Trim("'").Trim()
  # ${VAR:?message} means REQUIRED (fail-fast) and ${VAR:-default} means default: strip the marker.
  if ($trimmed.StartsWith('?')) { return '' }
  if ($trimmed.StartsWith('-')) { $trimmed = $trimmed.Substring(1).Trim() }
  return $trimmed
}

function Test-SafeValue([string]$value) {
  $trimmed = Get-NormalizedValue $value
  if ([string]::IsNullOrWhiteSpace($trimmed)) { return $true }
  if ($trimmed -match $safeValuePattern) { return $true }
  if ($trimmed -match $identifierValuePattern) { return $true }   # names, env keys, resource ids
  if ($trimmed.Length -lt 8) { return $true }
  return $false
}

Push-Location $root
try {
  foreach ($relativePath in (git -C $root ls-files)) {
    $extension = [System.IO.Path]::GetExtension($relativePath).ToLowerInvariant()
    if ($extension -notin $candidateExtensions -and $relativePath -ne '.env.example') { continue }
    $path = Join-Path $root $relativePath
    # Robustness (N-10): the index can list files deleted in the work tree; never crash on them.
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }

    $lineNumber = 0
    foreach ($line in [System.IO.File]::ReadLines($path, [System.Text.UTF8Encoding]::new($false, $true))) {
      $lineNumber++
      $reference = "${relativePath}:$lineNumber"
      if ($allowlist.Contains($reference)) { continue }

      # 5) known historical values
      foreach ($value in $historical) {
        if ($line.Contains($value)) { $violations.Add("Historical (rotated) credential value: $reference") }
      }

      # 4) secret-typed defaults: ${SECRET:literal}  (not ${SECRET:?required})
      foreach ($default in [regex]::Matches($line, $defaultPattern)) {
        $key = $default.Groups['key'].Value
        if ($key -notmatch $secretNamePattern) { continue }
        if ($key -match $nonSecretKeySuffix) { continue }
        if ($default.Groups['value'].Value.TrimStart().StartsWith('?')) { continue }
        if (Test-SafeValue $default.Groups['value'].Value) { continue }
        $violations.Add("Secret config default: $reference")
      }

      $matched = $false
      # 2) inline YAML/env entries
      foreach ($inline in [regex]::Matches($line, $inlineValuePattern)) {
        $key = $inline.Groups['key'].Value
        if ($key -notmatch $secretNamePattern) { continue }
        if ($key -match $nonSecretKeySuffix) { continue }
        if (Test-SafeValue $inline.Groups['value'].Value) { continue }
        $violations.Add("Inline secret value: $reference")
        $matched = $true
      }
      if ($matched) { continue }

      # 1) key/value assignment
      $assignment = [regex]::Match($line, $assignmentPattern)
      if (-not $assignment.Success -and $extension -eq '.ps1') { $assignment = [regex]::Match($line, $powerShellPattern) }
      if (-not $assignment.Success) { continue }
      $key = $assignment.Groups['key'].Value
      if ($key -in @('imagePullSecrets', 'secretKeyRef', 'secretName', 'secretRef')) { continue }
      if ($key -notmatch $secretNamePattern) { continue }
      if ($key -match $nonSecretKeySuffix) { continue }
      $value = $assignment.Groups['value'].Value
      # Secret/key/name variables hold identifiers, not credentials.
      if ($key -match $nameLikeKeyPattern -and $value.Trim().Trim('"').Trim("'") -match $identifierValuePattern) { continue }
      if (Test-SafeValue $value) { continue }
      $violations.Add("Potential credential assignment: $reference")
    }
  }
} finally {
  Pop-Location
}

$reportDirectory = Split-Path -Parent $ReportPath
[void](New-Item -ItemType Directory -Force -Path $reportDirectory)
$report = [System.Collections.Generic.List[string]]::new()
$report.Add('Secret exposure validation report')
$report.Add("Notes: $($notes -join '; '); allowlisted lines: $($allowlist.Count)")
$report.Add("Potential credential assignments: $($violations.Count)")
foreach ($violation in $violations) { $report.Add($violation) }
[System.IO.File]::WriteAllLines($ReportPath, $report, [System.Text.UTF8Encoding]::new($false))

if ($violations.Count -eq 0) {
  # A passing gate must not leave a stale report behind. This path used to be the report-only
  # worklist, so the file still listed "4 potential credential assignments" long after the last one
  # was cleared, because the success branch never touched it. See validate-tenant-sql.ps1.
  if (Test-Path -LiteralPath $ReportPath) { Remove-Item -LiteralPath $ReportPath -Force }
  Write-Host "Secret exposure validation passed."
  exit 0
}

if ($ReportOnly) {
  Write-Warning "Secret exposure: $($violations.Count) pending finding(s). Report: $ReportPath"
  exit 0
}

$violations | Select-Object -First 30 | ForEach-Object { Write-Error $_ }
Write-Error "Secret exposure: $($violations.Count) finding(s) in total. Report: $ReportPath"
exit 1
