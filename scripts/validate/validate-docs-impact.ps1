# High-impact code -> documentation impact validator (G52).
#
# A code change that changes a public contract, data migration, deployment
# artifact or gate must carry a corresponding documentation change. Pure
# implementation/bugfix changes are intentionally not covered.
#
# IMPORTANT: keep this file ASCII-only.

[CmdletBinding()]
param(
  [string]$Base = '',
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\docs-impact-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()

Push-Location $root
try {
  if ([string]::IsNullOrWhiteSpace($Base)) {
    if ($env:GITHUB_BASE_REF) {
      $Base = "origin/$($env:GITHUB_BASE_REF)"
    } else {
      $Base = 'HEAD~1'
    }
  }
  if (-not (git rev-parse --verify $Base 2>$null)) {
    Write-Warning "G52: base ref '$Base' is unavailable; skipping diff-based docs impact check."
    exit 0
  }
  $changed = @(git diff --name-only "$Base...HEAD")
  if ($LASTEXITCODE -ne 0) { throw "git diff failed for base $Base" }
  if ($changed.Count -eq 0) {
    if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
    Write-Host 'Docs impact validation passed (no changed files).'
    exit 0
  }

  $docsChanged = @($changed | Where-Object { $_ -like 'docs/*.md' })
  $rules = @(
    @{ Name = 'public API controller'; Pattern = '(^|/)src/main/java/.*/api/controller/.*\.java$'; Docs = @('docs/30-reference/', 'docs/50-services/', 'docs/20-architecture/') },
    @{ Name = 'gateway route config'; Pattern = '^gateways/gateway/src/main/resources/application\.yml$'; Docs = @('docs/30-reference/', 'docs/20-architecture/', 'docs/50-services/') },
    @{ Name = 'database migration'; Pattern = '(^|/)src/main/resources/db/migration/.*\.sql$'; Docs = @('docs/40-domain/', 'docs/50-services/', 'docs/30-reference/') },
    @{ Name = 'gate script'; Pattern = '^scripts/validate/validate-.*\.ps1$'; Docs = @('docs/80-development/governance/', 'docs/80-development/standards/') },
    @{ Name = 'deployment artifact'; Pattern = '^k8s/.*\.ya?ml$|(^|/)Dockerfile$|(^|/)docker-compose.*\.ya?ml$'; Docs = @('docs/60-deployment/', 'docs/20-architecture/') }
  )

  foreach ($rule in $rules) {
    $matches = @($changed | Where-Object { $_ -match $rule.Pattern })
    if ($matches.Count -eq 0) { continue }
    $documented = $false
    foreach ($doc in $docsChanged) {
      foreach ($prefix in $rule.Docs) {
        if ($doc.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { $documented = $true; break }
      }
      if ($documented) { break }
    }
    if (-not $documented) {
      $violations.Add("$($rule.Name) changed without matching docs under $($rule.Docs -join ', '): $($matches -join ', ')")
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -eq 0) {
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host 'Docs impact validation passed.'
  exit 0
}

if ($ReportOnly) {
  $violations | ForEach-Object { Write-Warning $_ }
  Write-Warning "Docs impact: $($violations.Count) pending violation(s)."
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))
$violations | ForEach-Object { Write-Error $_ }
Write-Error "Docs impact: $($violations.Count) violation(s) in total."
exit 1
