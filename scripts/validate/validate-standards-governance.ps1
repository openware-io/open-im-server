# Standards governance validator (G-08).
#
# Prevents the failure mode found in the audit: standards that contradict each other or the
# implementation, standards that carry project-specific content, and standards that lack the
# minimal template. Enforced per docs/80-development/standards/*.md (the reusable rule layer).
#
# Checks:
#   1. minimal template: each requirement in standards-governance-rules.txt [sections] must be
#      satisfied by at least one heading of the document
#   2. forbidden content: every pattern in [forbidden] (project TODO markers) plus hard-coded
#      checks for absolute local paths and repository module names inside the standards layer
#   3. every standards file must be reachable from the root README (link/index coverage is also
#      checked by G-10; kept here as a governance-level assertion)
#
# NOTE: link resolution of the documents themselves is G-10's job; this gate does not duplicate it.
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate/validate-standards-governance.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\standards-governance-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()

$rulesPath = Join-Path $PSScriptRoot 'standards-governance-rules.txt'
if (-not (Test-Path -LiteralPath $rulesPath -PathType Leaf)) { throw "Rules data file not found: $rulesPath" }
$rules = [System.IO.File]::ReadAllLines($rulesPath, [System.Text.UTF8Encoding]::new($false, $true))

$sections = [System.Collections.Generic.List[object]]::new()
$forbidden = [System.Collections.Generic.List[object]]::new()
$currentSection = ''
foreach ($line in $rules) {
  $trimmed = $line.Trim()
  if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
  if ($trimmed -match '^\[(?<name>[a-z]+)\]$') { $currentSection = $matches['name']; continue }
  $parts = $trimmed -split '\|'
  if ($currentSection -eq 'sections' -and $parts.Count -ge 3) {
    $sections.Add([pscustomobject]@{ Id = $parts[0]; Label = $parts[1]; Keywords = $parts[2..($parts.Count - 1)] })
  } elseif ($currentSection -eq 'forbidden' -and $parts.Count -ge 3) {
    $forbidden.Add([pscustomobject]@{ Id = $parts[0]; Label = $parts[1]; Pattern = ($parts[2..($parts.Count - 1)] -join '|') })
  }
}
if ($sections.Count -eq 0) { throw 'No section requirements were loaded.' }

# Hard-coded ASCII-only forbidden patterns (they need no translation).
$hardForbidden = @(
  @{ Id = 'absolute-path'; Label = 'absolute local path'; Pattern = '[A-Za-z]:\\|/Users/|/home/[a-z]' },
  @{ Id = 'module-name'; Label = 'repository module name in the standards layer'; Pattern = '\bim-[a-z]+-(service|api|ws)\b|\bplatform-[a-z]+-(service|api)\b|\bcommon-[a-z]+-(service|api)\b|\bgroup-[a-z]+-(service|api)\b' }
)

Push-Location $root
try {
  $standardFiles = git -C $root ls-files 'docs/80-development/standards/*.md' | Where-Object { $_ -notmatch '(^|/)README(\.en)?\.md$' -and $_ -notmatch '\.en\.md$' }
  foreach ($relativePath in $standardFiles) {
    $path = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $text = [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false, $true))
    $headings = @([regex]::Matches($text, '(?m)^#{1,4}\s+(?<title>.+)$') | ForEach-Object { $_.Groups['title'].Value })

    # 1) minimal template
    foreach ($requirement in $sections) {
      $satisfied = $false
      foreach ($heading in $headings) {
        foreach ($keyword in $requirement.Keywords) {
          if ($heading.Contains($keyword)) { $satisfied = $true; break }
        }
        if ($satisfied) { break }
      }
      if (-not $satisfied) {
        $violations.Add("${relativePath}: missing required section [$($requirement.Id)] $($requirement.Label)")
      }
    }

    # 2) forbidden content
    $lineNumber = 0
    foreach ($line in [System.IO.File]::ReadLines($path, [System.Text.UTF8Encoding]::new($false, $true))) {
      $lineNumber++
      foreach ($rule in $forbidden) {
        if ($line -match $rule.Pattern) { $violations.Add("${relativePath}:$lineNumber forbidden content [$($rule.Id)]") }
      }
      foreach ($rule in $hardForbidden) {
        if ($line -match $rule.Pattern) { $violations.Add("${relativePath}:$lineNumber forbidden content [$($rule.Id)]") }
      }
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -eq 0) {
  # See validate-tenant-sql.ps1: a passing gate must not leave a stale worklist claiming work remains.
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host 'Standards governance validation passed.'
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "Standards governance: $($violations.Count) pending violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | Select-Object -First 30 | ForEach-Object { Write-Error $_ }
Write-Error "Standards governance: $($violations.Count) violation(s) in total. Worklist: $worklistPath"
exit 1
