# Service API / layering boundary validator (G-04).
#
# Replaces the previous version, which EXCLUDED platform-services entirely (its own comment said
# "not in scope yet") and referenced a non-existent exemption note. Per DC-03/DC-14 this gate now
# covers every domain root with no exemption list.
#
# Checks:
#   1. *-api modules hold contracts only: no entity/mapper/repository/service/controller/startup
#      class, no Flyway dependency, no db/migration directory
#   2. *-service controllers must not import persistence implementations (mapper / po / repository)
#   3. *-service application layer must not import persistence implementations (mapper / po)
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate/validate-service-api-boundaries.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\service-api-boundaries-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()
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

  # --- 1) API modules ---
  $apiImplementationPattern = '@TableName|@TableId|@Mapper|@Service|@RestController|@SpringBootApplication|BaseMapper|Repository<|com\.baomidou|org\.flywaydb'
  foreach ($domain in $domainRoots) {
    foreach ($file in ($tracked | Where-Object { $_ -like "$domain/*/*-api/src/main/java/*" })) {
      if (Test-FileContains $file $apiImplementationPattern) {
        $violations.Add("business implementation in API module: $file")
      }
    }
    foreach ($file in ($tracked | Where-Object { $_ -like "$domain/*/*-api/*" -and $_ -like '*/db/migration/*' })) {
      $violations.Add("database migration inside API module: $file")
    }
    foreach ($pom in ($tracked | Where-Object { $_ -like "$domain/*/*-api/pom.xml" })) {
      if (Test-FileContains $pom 'spring-boot-starter-flyway|flyway-mysql|spring-boot-maven-plugin') {
        $violations.Add("executable/migration plugin or Flyway dependency in API module: $pom")
      }
    }
  }

  # --- 2) controllers must not touch persistence implementations ---
  # Persistence *implementations* only: MyBatis mappers, persistent objects and repository adapters under
  # an infra.persistence tree.
  #
  # The previous pattern `import [^;]*\.(mapper|po|repository)\.` also matched `...domain.repository.*`
  # and `...domain.<context>.repository.*`, which are the domain-facing interfaces a DDD application
  # layer is supposed to depend on. Of its 138 findings, 78 were those interfaces rather than
  # implementations, so it flagged correct code. Matching the infrastructure segment keeps the real
  # finding - an application service or controller importing a PO or a MyBatis mapper - without
  # punishing the intended dependency direction.
  $persistenceImportPattern = 'import\s+[^;]*\.infra\.persistence\.(mapper|po|repository)\.'
  foreach ($domain in $domainRoots) {
    foreach ($file in ($tracked | Where-Object { $_ -like "$domain/*/*-service/src/main/java/*/controller/*" })) {
      if (Test-FileContains $file $persistenceImportPattern) {
        $violations.Add("controller depends on a persistence implementation: $file")
      }
    }

    # --- 3) application layer must not touch persistence implementations ---
    foreach ($file in ($tracked | Where-Object { $_ -like "$domain/*/*-service/src/main/java/*/application/*" })) {
      if (Test-FileContains $file $persistenceImportPattern) {
        $violations.Add("application layer depends on a persistence implementation: $file")
      }
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -eq 0) {
  Write-Host 'Service API boundary validation passed (all domain roots, no exemptions).'
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "Service API boundaries: $($violations.Count) pending violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | Select-Object -First 30 | ForEach-Object { Write-Error $_ }
Write-Error "Service API boundaries: $($violations.Count) violation(s) in total. Worklist: $worklistPath"
exit 1
