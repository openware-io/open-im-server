# Flyway / secret configuration validator.
#
# Replaces the previous version, which (a) hard-coded 5 services and (b) scanned a NON-EXISTENT
# "services" directory for DDL problems, so that whole block was a silent no-op (finding H-11).
# DDL conventions are now enforced by validate-flyway-conventions.ps1; this gate keeps the
# per-service configuration and dependency assertions, discovered from the filesystem.
#
# Checks for every module that holds db/migration:
#   1. application.yml enables Flyway and declares a history table
#   2. pom.xml declares spring-boot-starter-flyway AND flyway-mysql
#   3. no module declares Flyway dependencies without holding migrations
#   4. no secret-typed config value may carry a literal default (DC-01/G-13)
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate/validate-security-and-flyway-configuration.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\flyway-configuration-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()
$domainRoots = @('im-services', 'platform-services', 'common-services', 'group-services')

Push-Location $root
try {
  $tracked = git -C $root ls-files
  $servicePoms = $tracked | Where-Object { $_ -match "^($($domainRoots -join '|'))/.+/-service/pom\.xml$" }

  $migrationHolders = [System.Collections.Generic.HashSet[string]]::new()
  foreach ($file in ($tracked | Where-Object { $_ -match '^[^/]+/.+/src/main/resources/db/migration' })) {
    $modulePath = ($file -split '/src/main/resources/')[0]
    [void]$migrationHolders.Add($modulePath)
  }

  foreach ($modulePath in ($migrationHolders | Sort-Object)) {
    $yamlPath = "$modulePath/src/main/resources/application.yml"
    if (-not (Test-Path -LiteralPath (Join-Path $root $yamlPath) -PathType Leaf)) {
      $violations.Add("migration-holding module without application.yml: $modulePath")
      continue
    }
    $yaml = [System.IO.File]::ReadAllText((Join-Path $root $yamlPath), [System.Text.UTF8Encoding]::new($false, $true))
    if ($yaml -notmatch '(?m)^\s+enabled:\s*true\s*$') { $violations.Add("Flyway is not enabled: $yamlPath") }
    if ($yaml -notmatch '(?m)^\s+table:\s*\S+') { $violations.Add("Flyway history table is not declared: $yamlPath") }

    $pomPath = "$modulePath/pom.xml"
    $pom = [System.IO.File]::ReadAllText((Join-Path $root $pomPath), [System.Text.UTF8Encoding]::new($false, $true))
    if ($pom -notmatch '<artifactId>spring-boot-starter-flyway</artifactId>') { $violations.Add("missing spring-boot-starter-flyway: $pomPath") }
    if ($pom -notmatch '<artifactId>flyway-mysql</artifactId>') { $violations.Add("missing flyway-mysql: $pomPath") }
  }

  # 3) Flyway dependencies without migrations
  foreach ($pomPath in $servicePoms) {
    $modulePath = $pomPath.Substring(0, $pomPath.Length - '/pom.xml'.Length)
    if ($migrationHolders.Contains($modulePath)) { continue }
    $pom = [System.IO.File]::ReadAllText((Join-Path $root $pomPath), [System.Text.UTF8Encoding]::new($false, $true))
    if ($pom -match 'flyway-mysql|spring-boot-starter-flyway|flyway-core') {
      $violations.Add("Flyway dependency without a migration directory: $pomPath")
    }
  }

  # 4) secret-typed literals defaults anywhere in main configuration
  $secretDefaultPattern = '\$\{(?<key>[A-Za-z_][A-Za-z0-9_]*)\s*:\s*(?<value>[^}]+)\}'
  $secretKeyPattern = '(?i)(password|passwd|secret|token|api[_-]?key|credential|hmac)'
  foreach ($yamlPath in ($tracked | Where-Object { $_ -match '^[^/]+/.+/src/main/resources/application.*\.ya?ml$' })) {
    $lineNumber = 0
    foreach ($line in [System.IO.File]::ReadLines((Join-Path $root $yamlPath), [System.Text.UTF8Encoding]::new($false, $true))) {
      $lineNumber++
      foreach ($match in [regex]::Matches($line, $secretDefaultPattern)) {
        if ($match.Groups['key'].Value -notmatch $secretKeyPattern) { continue }
        if ($match.Groups['value'].Value.TrimStart().StartsWith('?')) { continue }   # ${VAR:?msg} = required
        $violations.Add("secret config default: ${yamlPath}:$lineNumber")
      }
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -eq 0) {
  Write-Host 'Security and Flyway configuration validation passed.'
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "Flyway/secret configuration: $($violations.Count) pending violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | Select-Object -First 30 | ForEach-Object { Write-Error $_ }
Write-Error "Flyway/secret configuration: $($violations.Count) violation(s) in total. Worklist: $worklistPath"
exit 1
