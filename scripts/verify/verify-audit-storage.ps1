# Audit storage acceptance (independent schema + occurred_at monthly partitions + ledger + manifest).
#
# Coverage (plan: docs/renovation/AUDIT_STORAGE_01_SERVICE.md):
#   1) gv_audit schema exists (created by scripts/migration/audit-schema-bootstrap.sql)
#   2) iam_audit_log is RANGE-partitioned on occurred_at, with pmin and pmax fallbacks present
#   3) MySQL invariant: every unique index of a partitioned table includes the partition column
#   4) occurred_at is NOT NULL (partition key must never be NULL, otherwise rows land in pmin)
#   5) idempotency ledger carries the unique key (tenant_id, idempotency_key) - moved off the main table
#   6) archive manifest exists (its rows are the evidence required before any DROP PARTITION)
#   7) fallback partitions are empty (rows there never participate in retention -> must be alerted)
#   8) current month partition exists (the retention job pre-creates it; missing = job not running)
#
# NOTE: this file is intentionally ASCII-only so it runs under both PowerShell 5.1 and 7+
# (5.1 reads a BOM-less .ps1 as ANSI, which would corrupt non-ASCII literals).
#
# Usage (Kind environment; Docker Desktop provides the runtime, Compose is not used):
#   1) kubectl -n gv-im-local port-forward svc/mysql 13306:3306     # cluster MySQL -> local 13306
#   2) powershell -File scripts/verify/verify-audit-storage.ps1 -User root -Password <DB_PASSWORD>
# ACK: pass -DbHost / -Port / -User / -Password of that environment (or run against a port-forward).
param(
  [string]$MysqlExe = 'mysql',
  [string]$DbHost = '127.0.0.1',
  [int]$Port = 13306,
  [string]$User = 'root',
  [string]$Password = '',
  [string]$AuditDatabase = 'gv_audit',
  [switch]$AllowFallbackRows
)

$ErrorActionPreference = 'Continue'
$script:pass = 0
$script:fail = 0

function Step([string]$name) { Write-Host ''; Write-Host ("== $name ==") -ForegroundColor Cyan }
function Pass([string]$msg) { $script:pass++; Write-Host ("  PASS " + $msg) -ForegroundColor Green }
function Fail([string]$msg) { $script:fail++; Write-Host ("  FAIL " + $msg) -ForegroundColor Red }
function Assert([bool]$cond, [string]$msg) { if ($cond) { Pass $msg } else { Fail $msg } }

# Single scalar query, tab-separated columns, no headers. Returns $null when the client cannot run.
function Sql([string]$query) {
  $args = @("--host=$DbHost", "--port=$Port", "--user=$User", '--batch', '--skip-column-names', '--raw')
  if ($Password -ne '') { $args += "--password=$Password" }
  $args += @('-e', $query)
  $output = & $MysqlExe @args 2>&1
  if ($LASTEXITCODE -ne 0) {
    Write-Host ("  mysql client error: " + ($output -join ' ')) -ForegroundColor DarkYellow
    return $null
  }
  return ($output | Where-Object { $_ -ne $null }) -join "`n"
}

Step '0. MySQL client reachable'
$probe = Sql 'SELECT VERSION();'
if ($probe -eq $null) {
  Write-Host 'mysql client unavailable or server unreachable; nothing verified.' -ForegroundColor Red
  exit 1
}
Pass ("server version " + ($probe.Trim()))

Step '1. audit schema exists'
$schema = Sql ("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = '" + $AuditDatabase + "';")
Assert ([int]$schema -eq 1) ("schema " + $AuditDatabase + " exists")

Step '2. main table is RANGE-partitioned on occurred_at with pmin/pmax'
$method = Sql ("SELECT PARTITION_METHOD FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA='" + $AuditDatabase + "' AND TABLE_NAME='iam_audit_log' AND PARTITION_NAME IS NOT NULL LIMIT 1;")
$expr = Sql ("SELECT PARTITION_EXPRESSION FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA='" + $AuditDatabase + "' AND TABLE_NAME='iam_audit_log' AND PARTITION_NAME IS NOT NULL LIMIT 1;")
$partitions = Sql ("SELECT PARTITION_NAME FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA='" + $AuditDatabase + "' AND TABLE_NAME='iam_audit_log' AND PARTITION_NAME IS NOT NULL;")
Assert ($method -eq 'RANGE COLUMNS') ("partition method is RANGE COLUMNS (actual: " + $method + ")")
Assert ($expr -match 'occurred_at') ("partition expression uses occurred_at (actual: " + $expr + ")")
Assert (($partitions -split "`n") -contains 'pmin') 'pmin fallback partition exists'
Assert (($partitions -split "`n") -contains 'pmax') 'pmax fallback partition exists'

Step '3. MySQL invariant: unique indexes include the partition column'
$badKeys = Sql ("SELECT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='" + $AuditDatabase + "' AND TABLE_NAME='iam_audit_log' AND NON_UNIQUE=0 GROUP BY INDEX_NAME HAVING SUM(COLUMN_NAME='occurred_at')=0;")
Assert ([string]::IsNullOrWhiteSpace($badKeys)) 'no unique index lacks occurred_at (a violating key makes the DDL invalid on MySQL)'

Step '4. occurred_at is NOT NULL'
$nullable = Sql ("SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='" + $AuditDatabase + "' AND TABLE_NAME='iam_audit_log' AND COLUMN_NAME='occurred_at';")
Assert ($nullable -eq 'NO') ("occurred_at is NOT NULL (actual: " + $nullable + ")")

Step '5. idempotency ledger carries the unique key'
$ledgerKey = Sql ("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='" + $AuditDatabase + "' AND TABLE_NAME='iam_audit_idempotency' AND NON_UNIQUE=0;")
Assert ($ledgerKey -like '*tenant_id,idempotency_key*') ("ledger unique key is (tenant_id, idempotency_key) (actual: " + $ledgerKey + ")")

Step '6. archive manifest exists (evidence required before any DROP PARTITION)'
$manifest = Sql ("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='" + $AuditDatabase + "' AND TABLE_NAME='iam_audit_archive_manifest';")
Assert ([int]$manifest -eq 1) 'iam_audit_archive_manifest exists'

Step '7. fallback partitions are empty (rows there never participate in retention)'
$minRows = [int](Sql ("SELECT COUNT(*) FROM " + $AuditDatabase + ".iam_audit_log PARTITION (pmin);"))
$maxRows = [int](Sql ("SELECT COUNT(*) FROM " + $AuditDatabase + ".iam_audit_log PARTITION (pmax);"))
if ($AllowFallbackRows) {
  Write-Host ("  INFO pmin=" + $minRows + " pmax=" + $maxRows + " (rows tolerated by -AllowFallbackRows)") -ForegroundColor DarkYellow
  Pass 'fallback partition inspection executed'
} else {
  Assert ($minRows -eq 0) ("pmin is empty (actual: " + $minRows + " rows; non-zero means out-of-range timestamps)")
  Assert ($maxRows -eq 0) ("pmax is empty (actual: " + $maxRows + " rows; non-zero means partitions were not pre-created)")
}

Step '8. current month partition exists (retention job pre-creates it)'
$current = (Get-Date).ToUniversalTime().ToString('yyyyMM')
$expected = 'p' + $current
Assert (($partitions -split "`n") -contains $expected) ("partition " + $expected + " exists for the current UTC month")

Step '9. archive manifest state distribution (informational)'
$states = Sql ("SELECT state, COUNT(*) FROM " + $AuditDatabase + ".iam_audit_archive_manifest GROUP BY state;")
if ([string]::IsNullOrWhiteSpace($states)) {
  Write-Host '  INFO manifest is empty (nothing archived yet - expected for a fresh schema)' -ForegroundColor DarkYellow
} else {
  Write-Host ("  INFO " + ($states -replace "`n", ' | ')) -ForegroundColor DarkYellow
}

Write-Host ''
Write-Host ("Result: " + $script:pass + " passed, " + $script:fail + " failed") -ForegroundColor ($(if ($script:fail -eq 0) { 'Green' } else { 'Red' }))
if ($script:fail -gt 0) { exit 1 }
exit 0
