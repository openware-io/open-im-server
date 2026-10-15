# Repository port contract validator (stands/10 "port naming and contracts").
#
# The standard requires ports to express business intent and forbids leaking technical query syntax.
# Nothing checked it, so ports exposing Wrapper, PO or IPage would have been accepted silently.
#
# Scope: tracked Java files under /domain/repository/ or /domain/port/ inside service modules.
#
# Reported:
#   1. forbidden types in signatures: MyBatis Wrapper/QueryWrapper/LambdaQueryWrapper/IPage/Example,
#      persistence objects (identifiers ending in Po), Mapper types, MyBatis-Plus page/plugin classes
#   2. imports of infrastructure or persistence packages
#   3. a stale domain.port package: ports belong in domain.repository
#
# Not reported: read-model projections, domain models, values and primitives, which are the intended
# shapes.
#
# Usage: powershell -File scripts/validate/validate-port-contract.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\port-contract-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()

# Types that must not appear in a port signature or body.
$forbiddenTypePattern = '\b(Wrapper|QueryWrapper|LambdaQueryWrapper|UpdateWrapper|LambdaUpdateWrapper|IPage|Page|Example|Mapper)\b|Wrapper<|\b[A-Za-z0-9]+Po\b'
$infraImportPattern = 'import\s+(io\.openware\.infrastructure|com\.baomidou|org\.apache\.ibatis|org\.springframework|redis\.clients|org\.apache\.rocketmq)\b'
$persistenceImportPattern = 'import\s+[^;]*\.persistence\.'

Push-Location $root
try {
  foreach ($relativePath in (git -C $root ls-files '*.java')) {
    if ($relativePath -notmatch '/domain/(repository|port)/') { continue }
    if ($relativePath -notmatch '/src/main/java/') { continue }
    $path = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $text = [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false, $true))

    if ($relativePath -match '/domain/port/') {
      $violations.Add("port lives in domain.port; ports belong in domain.repository: $relativePath")
    }

    $lineNumber = 0
    foreach ($line in ($text -split "`n")) {
      $lineNumber++
      $trimmed = $line.Trim()
      if ($trimmed.StartsWith('*') -or $trimmed.StartsWith('//') -or $trimmed.StartsWith('/*')) { continue }
      if ($trimmed -match '^\s*import\s') {
        if ($trimmed -match $infraImportPattern) {
          $violations.Add("port imports infrastructure: ${relativePath}:$lineNumber")
        } elseif ($trimmed -match $persistenceImportPattern) {
          $violations.Add("port imports persistence: ${relativePath}:$lineNumber")
        }
        continue
      }
      if ($trimmed -match $forbiddenTypePattern) {
        $violations.Add("port exposes a technical persistence type: ${relativePath}:$lineNumber")
      }
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -eq 0) {
  # See validate-tenant-sql.ps1: a passing gate must not leave a stale worklist claiming work remains.
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host 'Repository port contract validation passed.'
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "Repository port contract: $($violations.Count) violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | Select-Object -First 30 | ForEach-Object { Write-Error $_ }
Write-Error "Repository port contract: $($violations.Count) violation(s) in total. Worklist: $worklistPath"
exit 1
