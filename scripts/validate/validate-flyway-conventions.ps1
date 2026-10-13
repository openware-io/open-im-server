# Flyway DDL convention validator: enforces the database rules of docs/ENGINEERING_RULES.md
# (and docs/standards/21_PERSISTENCE_STACK.md) across EVERY migration-holding module.
# It replaces the previous single-file user-V1 boundary check.
#
# IMPORTANT: keep this file ASCII-only. Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI,
# so any non-ASCII literal (regex anchors, messages) is corrupted at parse time. Use the
# ASCII mirror scripts/validate/table-prefixes.txt for registry data.
#
# Checks per CREATE TABLE:
#   1. table prefix is registered (table-prefixes.txt, asserted to still exist in the rule doc)
#   2. singular table name (allowlist for names that are singular but end with 's')
#   3. ENGINE=InnoDB + DEFAULT CHARSET=utf8mb4 + COLLATE=utf8mb4_0900_ai_ci
#   4. PK is exactly `id` bigint unsigned AUTO_INCREMENT; partitioned tables must include the
#      partition column in the PK (engine constraint, DC-17)
#   5. the four audit columns exist (DC-11: no exceptions)
#   6. index names follow uk_<table>_<semantic> / idx_<table>_<semantic>
#   7. no physical FOREIGN KEY / REFERENCES
#   8. no CREATE TABLE IF NOT EXISTS / DROP TABLE / dynamic DDL
#   9. table and column COMMENT present
#  10. audit/timestamp columns use datetime(3)
#
# Usage: powershell -File scripts/validate/validate-flyway-conventions.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\flyway-conventions-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()

# Names that are singular although they end with 's'.
$singularAllowlist = @('msg_read_status', 'iam_audit_idempotency')

# --- registered prefixes ---
$prefixFilePath = Join-Path $PSScriptRoot 'table-prefixes.txt'
$rulesPath = Join-Path $root 'docs\ENGINEERING_RULES.md'
$registered = [System.Collections.Generic.HashSet[string]]::new()
foreach ($line in [System.IO.File]::ReadAllLines($prefixFilePath, [System.Text.UTF8Encoding]::new($false, $true))) {
  $token = $line.Trim()
  if (-not $token -or $token.StartsWith('#')) { continue }
  [void]$registered.Add($token.TrimEnd('_'))
}
if ($registered.Count -eq 0) { throw "No table prefixes were loaded from $prefixFilePath." }

# The rule document must still mention every registered prefix (prevents silent drift).
$rules = [System.IO.File]::ReadAllText($rulesPath, [System.Text.UTF8Encoding]::new($false, $true))
foreach ($prefix in $registered) {
  if ($rules -notmatch ('`' + [regex]::Escape($prefix) + '_?`')) {
    $violations.Add("docs/ENGINEERING_RULES.md: registered prefix '$prefix' is not documented in the prefix registry")
  }
}

# --- migration roots ---
$domainRoots = @('im-services', 'platform-services', 'common-services', 'group-services')
$migrationDirs = [System.Collections.Generic.List[string]]::new()
foreach ($domain in $domainRoots) {
  $domainPath = Join-Path $root $domain
  if (-not (Test-Path -LiteralPath $domainPath)) { continue }
  foreach ($dir in Get-ChildItem -LiteralPath $domainPath -Recurse -Directory -Filter 'migration*' -ErrorAction SilentlyContinue) {
    if ($dir.FullName -match '[\\/]src[\\/]main[\\/]resources[\\/]db[\\/]migration') { $migrationDirs.Add($dir.FullName) }
  }
}
if ($migrationDirs.Count -eq 0) { throw 'No Flyway migration directories were found.' }

$tableCount = 0
foreach ($dir in $migrationDirs) {
  $relative = $dir.Substring($root.Length + 1).Replace('\', '/')
  foreach ($file in Get-ChildItem -LiteralPath $dir -File -Filter '*.sql') {
    $text = [System.IO.File]::ReadAllText($file.FullName, [System.Text.UTF8Encoding]::new($false, $true))
    $name = $file.Name
    $fileRef = "${relative}/${name}"

    if ($text -match '(?i)CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS') { $violations.Add("${fileRef}: CREATE TABLE IF NOT EXISTS is forbidden") }
    if ($text -match '(?i)\bDROP\s+TABLE\b') { $violations.Add("${fileRef}: DROP TABLE is forbidden") }
    if ($text -match '(?i)\bFOREIGN\s+KEY\b') { $violations.Add("${fileRef}: physical foreign keys are forbidden") }
    if ($text -match '(?i)\b(PREPARE|EXECUTE|DEALLOCATE)\b') { $violations.Add("${fileRef}: dynamic DDL is forbidden") }

    foreach ($tableMatch in [regex]::Matches($text, '(?is)CREATE\s+TABLE\s+`?(?<table>[a-z0-9_]+)`?\s*\((?<body>.*?)\)\s*(?<tail>ENGINE[^;]*;|;)')) {
      $tableCount++
      $table = $tableMatch.Groups['table'].Value
      $body = $tableMatch.Groups['body'].Value
      $tail = $tableMatch.Groups['tail'].Value
      $where = "${fileRef}:${table}"

      $prefix = ($table -split '_')[0]
      if (-not $registered.Contains($prefix)) { $violations.Add("${where}: unregistered table prefix '$prefix'") }
      if ($table.EndsWith('s') -and $singularAllowlist -notcontains $table) { $violations.Add("${where}: plural table name") }

      if ($tail -notmatch 'ENGINE\s*=\s*InnoDB') { $violations.Add("${where}: ENGINE=InnoDB missing") }
      if ($tail -notmatch 'CHARSET\s*=\s*utf8mb4') { $violations.Add("${where}: DEFAULT CHARSET=utf8mb4 missing") }
      if ($tail -notmatch 'COLLATE\s*=\s*utf8mb4_0900_ai_ci') { $violations.Add("${where}: COLLATE=utf8mb4_0900_ai_ci missing") }

      $isPartitioned = $tail -match '(?i)PARTITION\s+BY'
      $pkMatch = [regex]::Match($body, '(?i)PRIMARY\s+KEY\s*\((?<cols>[^)]*)\)')
      if (-not $pkMatch.Success) {
        $violations.Add("${where}: PRIMARY KEY is missing")
      } else {
        $pkCols = ($pkMatch.Groups['cols'].Value -replace '[`\s]', '')
        if ($isPartitioned) {
          if ($pkCols -notmatch '^id,') { $violations.Add("${where}: partitioned table PK must be 'id,<partition column>' (DC-17)") }
        } elseif ($pkCols -ne 'id') {
          $violations.Add("${where}: PK must be exactly 'id' (found '$pkCols')")
        }
      }
      if ($body -notmatch '(?i)`?id`?\s+bigint\s+unsigned\s+NOT\s+NULL\s+AUTO_INCREMENT') {
        $violations.Add("${where}: 'id' must be bigint unsigned NOT NULL AUTO_INCREMENT")
      }

      foreach ($audit in @('created_by', 'created_at', 'updated_by', 'updated_at')) {
        if ($body -notmatch ('(?i)`?' + $audit + '`?')) { $violations.Add("${where}: audit column '$audit' is missing") }
      }
      foreach ($timeColumn in @('created_at', 'updated_at')) {
        $timeMatch = [regex]::Match($body, ('(?i)`' + $timeColumn + '`\s+(?<type>[a-z]+(\(\d+\))?)'))
        if ($timeMatch.Success -and $timeMatch.Groups['type'].Value.ToLowerInvariant() -ne 'datetime(3)') {
          $violations.Add("${where}: '$timeColumn' must be datetime(3) (found $($timeMatch.Groups['type'].Value))")
        }
      }

      foreach ($indexMatch in [regex]::Matches($body, '(?i)(?<kind>UNIQUE\s+KEY|KEY)\s+`(?<name>[a-z0-9_]+)`')) {
        $indexName = $indexMatch.Groups['name'].Value
        $expectedPrefix = if ($indexMatch.Groups['kind'].Value -match '(?i)UNIQUE') { 'uk_' } else { 'idx_' }
        if (-not $indexName.StartsWith($expectedPrefix)) {
          $violations.Add("${where}: index '$indexName' must start with '$expectedPrefix'")
        } elseif ($indexName -notmatch ('^' + $expectedPrefix + [regex]::Escape($table) + '_[a-z0-9_]+$')) {
          $violations.Add("${where}: index '$indexName' must follow ${expectedPrefix}<table>_<semantic>")
        }
      }

      if ($file -and $body -notmatch 'COMMENT') { $violations.Add("${where}: column COMMENTs are missing") }
      if ($tail -notmatch '(?i)COMMENT\s*=') { $violations.Add("${where}: table COMMENT is missing") }
    }
  }
}

if ($violations.Count -eq 0) {
  Write-Host "Flyway convention validation passed. Tables checked: $tableCount, migration roots: $($migrationDirs.Count)."
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "Flyway conventions: $($violations.Count) pending violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | Select-Object -First 40 | ForEach-Object { Write-Error $_ }
Write-Error "Flyway conventions: $($violations.Count) violation(s) in total. Worklist: $worklistPath"
exit 1
