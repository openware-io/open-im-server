[CmdletBinding()]
param(
  [switch]$ReportOnly,
  [string]$ReportPath
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
  $ReportPath = Join-Path $root '.outputs\quality\secret-exposure-report.txt'
}

$candidateExtensions = @('.env', '.properties', '.yml', '.yaml', '.ps1', '.bat', '.md', '.txt')
$configurationAssignmentPattern = '(?i)^\s*(?!#)(?<key>[A-Za-z_][A-Za-z0-9_.-]*(?:password|secret|token|api[_-]?key)[A-Za-z0-9_.-]*)\s*[:=]\s*(?<value>.+?)\s*$'
$powerShellAssignmentPattern = '(?i)^\s*\$(?<key>[A-Za-z_][A-Za-z0-9_]*(?:password|secret|token|api[_-]?key)[A-Za-z0-9_]*)\s*=\s*(?<value>.+?)\s*$'
$safeValuePattern = '^(?:\$\{[^}]+\}|\$env:[A-Za-z_][A-Za-z0-9_]*|\$\$[A-Za-z_][A-Za-z0-9_]*|\$[A-Za-z_][A-Za-z0-9_]*(?:\[[^\]]+\])?|your-[\w-]+|change-me|example|todo|<[^>]+>)$'
$powerShellCommandExpressionPattern = '^[A-Za-z][A-Za-z0-9-]*(?:\s+-[A-Za-z][A-Za-z0-9-]*\s+\$[A-Za-z_][A-Za-z0-9_]*)+$'
$violations = [System.Collections.Generic.List[string]]::new()

git -C $root ls-files | ForEach-Object {
  $relativePath = $_
  $extension = [System.IO.Path]::GetExtension($relativePath).ToLowerInvariant()
  if ($extension -notin $candidateExtensions -and $relativePath -ne '.env.example') {
    return
  }
  $path = Join-Path $root $relativePath
  $lineNumber = 0
  foreach ($line in [System.IO.File]::ReadLines($path, [System.Text.UTF8Encoding]::new($false, $true))) {
    $lineNumber++
    $match = [regex]::Match($line, $configurationAssignmentPattern)
    if (-not $match.Success -and $extension -eq '.ps1') {
      $match = [regex]::Match($line, $powerShellAssignmentPattern)
    }
    if (-not $match.Success) {
      continue
    }
    # imagePullSecrets is a Kubernetes Secret reference, not a credential assignment.
    if ($match.Groups['key'].Value -ieq 'imagePullSecrets') {
      continue
    }
    $value = $match.Groups['value'].Value.Trim().Trim('"', "'")
    # A command consuming a variable is not a credential literal (for example, SecureString conversion).
    if ($extension -eq '.ps1' -and $value -match $powerShellCommandExpressionPattern) {
      continue
    }
    if ($value -notmatch $safeValuePattern) {
      $violations.Add("${relativePath}:$lineNumber")
    }
  }
}

$reportDirectory = Split-Path -Parent $ReportPath
New-Item -ItemType Directory -Force $reportDirectory | Out-Null
$report = @(
  'Secret exposure validation report'
  "Potential credential assignments: $($violations.Count)"
  $violations
)
[System.IO.File]::WriteAllLines($ReportPath, $report, [System.Text.UTF8Encoding]::new($false))

if ($violations.Count -eq 0) {
  Write-Host "Secret exposure validation passed. Report: $ReportPath"
  exit 0
}

if ($ReportOnly) {
  Write-Warning "Secret exposure validation found $($violations.Count) potential credential assignment(s). Report: $ReportPath"
  exit 0
}

$violations | ForEach-Object { Write-Error "Potential credential assignment: $_" }
exit 1
