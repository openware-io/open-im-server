# Controller / repository boundary validator (standards/10 layering).
#
# Rule: a controller receives HTTP input, validates parameters, extracts the authorization context,
# converts DTOs and calls an application service. The repository abstraction belongs to the
# application layer. A controller that holds a *Repository or *Mapper field is therefore a layering
# violation regardless of whether the repository is a domain interface or an infrastructure adapter.
#
# Why this is separate from service-api-boundaries: that gate matches imports of
# `infra.persistence.(mapper|po|repository)`. A controller injecting a DOMAIN repository imports
# `domain.<ctx>.repository.*`, so it was never matched - and that is the shape this repository
# actually has. The clearest example is IamController: it holds RoleRepository, PermissionRepository
# and UserRoleRepository while its own class Javadoc states the controller only does protocol
# conversion and audit. The boundary is the dependency, not the package the type lives in.
#
# Why report mode: 14 controllers hold 72 repository call sites today, one of them 1088 lines. The fix
# is to move the orchestration into application services, which is a refactor per controller, not a
# mechanical edit. This gate is listed in report-only-gates.txt until the worklist reaches zero, at
# which point the entry is deleted so the gate becomes blocking - the same path po-containment and
# service-api-boundaries took.
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate/validate-controller-repository-boundary.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\controller-repository-boundary-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()

# Field or constructor parameter holding a repository/mapper. Matching on the declared type name keeps
# this independent of which package the type lives in; the `(Repository|Mapper)` suffix is this
# repository's naming convention for both domain ports and persistence adapters.
$repositoryFieldPattern = '\b(private|protected|public)[ \t]+(final[ \t]+)?[A-Za-z0-9_]+(Repository|Mapper)(<[^>]*>)?[ \t]+[a-z]'
$domainRoots = @('im-services', 'platform-services', 'common-services', 'group-services')

function Test-FileContains([string]$relativePath, [string]$pattern) {
  $path = Join-Path $root $relativePath
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $false }
  $text = [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false, $true))
  return $text -match $pattern
}

Push-Location $root
try {
  $tracked = git -C $root ls-files
  foreach ($domain in $domainRoots) {
    foreach ($file in ($tracked | Where-Object { $_ -like "$domain/*/*-service/src/main/java/*/controller/*" })) {
      if (Test-FileContains $file $repositoryFieldPattern) {
        $violations.Add("controller depends on a repository: $file")
      }
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -eq 0) {
  # A passing gate must not leave a stale worklist claiming work remains.
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host 'Controller/repository boundary validation passed.'
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "Controller/repository boundary: $($violations.Count) pending violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | Select-Object -First 30 | ForEach-Object { Write-Error $_ }
Write-Error "Controller/repository boundary: $($violations.Count) violation(s) in total. Worklist: $worklistPath"
exit 1
