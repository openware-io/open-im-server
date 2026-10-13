# Tenant isolation SQL validator (G-11).
#
# Replaces the previous hard-coded prefix whitelist. Under that design every table outside the
# list (for example all ord_* tables except ord_order/ord_order_item, plus saa_* and iam_*) was
# silently unchecked, and the script only asserted that the string "tenant_id" appeared somewhere
# in the CREATE TABLE body -- never that queries actually filter by tenant.
#
# Rules:
#   1. table level: in platform-services / common-services every CREATE TABLE must declare a
#      tenant_id column unless the table is registered in tenant-platform-tables.txt with a reason
#   2. query level: raw SQL in mapper files that touches a tenant-scoped table must reference
#      tenant_id in the same statement
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate/validate-tenant-sql.ps1 [-Path <root>] [-ReportOnly]
[CmdletBinding()]
param(
  [string]$Path,
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\tenant-sql-worklist.txt'
$violations = [System.Collections.Generic.List[string]]::new()

if ([string]::IsNullOrWhiteSpace($Path)) {
  $roots = @('platform-services', 'common-services')
} else {
  $roots = @($Path)
}

# --- registry ---
$registry = [System.Collections.Generic.HashSet[string]]::new()
$registryPath = Join-Path $PSScriptRoot 'tenant-platform-tables.txt'
if (Test-Path -LiteralPath $registryPath -PathType Leaf) {
  foreach ($line in [System.IO.File]::ReadAllLines($registryPath, [System.Text.UTF8Encoding]::new($false, $true))) {
    $entry = ($line -split '\|')[0].Trim()
    if ($entry -and -not $entry.StartsWith('#')) { [void]$registry.Add($entry) }
  }
}

# --- 1) table level ---
$tenantScopedTables = [System.Collections.Generic.HashSet[string]]::new()
foreach ($domain in $roots) {
  $domainPath = Join-Path $root $domain
  if (-not (Test-Path -LiteralPath $domainPath)) { continue }
  foreach ($dir in Get-ChildItem -LiteralPath $domainPath -Recurse -Directory -Filter 'migration*' -ErrorAction SilentlyContinue) {
    if ($dir.FullName -notmatch '[\\/]src[\\/]main[\\/]resources[\\/]db[\\/]migration') { continue }
    $relative = $dir.FullName.Substring($root.Length + 1).Replace('\', '/')
    foreach ($file in Get-ChildItem -LiteralPath $dir.FullName -File -Filter '*.sql') {
      $text = [System.IO.File]::ReadAllText($file.FullName, [System.Text.UTF8Encoding]::new($false, $true))
      foreach ($match in [regex]::Matches($text, '(?is)CREATE\s+TABLE\s+`?(?<table>[a-z0-9_]+)`?\s*\((?<body>.*?)\)\s*(?:ENGINE|;)')) {
        $table = $match.Groups['table'].Value
        $body = $match.Groups['body'].Value
        $hasTenant = $body -match '(?i)tenant_id'
        if ($hasTenant) { [void]$tenantScopedTables.Add($table); continue }
        if ($registry.Contains($table)) { continue }
        $violations.Add("table without tenant_id and not registered as platform-level: ${relative}/$($file.Name):$table")
      }
    }
  }
}

# --- 2) query level (raw SQL in mappers) ---
#
# Only statements that OPT OUT of the tenant interceptor need an explicit predicate.
# TenantLineInnerInterceptor rewrites the SQL of every statement that does not carry
# @InterceptorIgnore, injecting the tenant predicate at runtime; that predicate is
# necessarily absent from the source text, so flagging those statements was a false
# positive (and it made the marker the wrong fix: the query is tenant-scoped, not
# tenant-agnostic). A statement is therefore "opted out" when it carries @InterceptorIgnore,
# and that annotation also governs every continuation line of a multi-line @Select/@Update
# or of a text block, until the statement ends.
#
# Consequence: an @InterceptorIgnore statement must either carry the predicate itself or
# declare what it is doing with an explicit platform-wide / tenant-agnostic marker.
Push-Location $root
try {
  $mapperFiles = git -C $root ls-files '*/src/main/java/*/mapper/*.java' '*/src/main/resources/mapper/*.xml'
  foreach ($relativePath in $mapperFiles) {
    $path = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $lineNumber = 0
    $statementOptedOut = $false
    foreach ($line in [System.IO.File]::ReadLines($path, [System.Text.UTF8Encoding]::new($false, $true))) {
      $lineNumber++
      if ($line -match '@InterceptorIgnore') { $statementOptedOut = $true; continue }
      $isStatementLine = $line -match '(?i)\b(SELECT|UPDATE|DELETE)\b.*\b(FROM|UPDATE|INTO)\b'
      if (-not $isStatementLine) {
        # A line that terminates a statement resets the opt-out for the next one.
        if ($line -match ';\s*$' -or $line -match '^\s*\)\s*;?\s*$' -or $line -match '"""?\s*\)\s*;?\s*$') { $statementOptedOut = $false }
        continue
      }
      if (-not $statementOptedOut) { continue }
      if ($line -match 'tenant_id') {
        $statementOptedOut = $false
        continue
      }
      if ($line -match '(?i)platform-wide|tenant-agnostic') { continue }
      foreach ($table in $tenantScopedTables) {
        if ($line -match ('(?i)\b' + [regex]::Escape($table) + '\b')) {
          $violations.Add("raw SQL without a tenant predicate: ${relativePath}:$lineNumber (table $table)")
          break
        }
      }
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -eq 0) {
  # A passing gate must not leave a stale worklist behind: the file is what operators and follow-up
  # checks read to decide whether work remains, so a leftover from an earlier failing run would report
  # violations that no longer exist.
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host "Tenant isolation validation passed. Tenant-scoped tables: $($tenantScopedTables.Count)."
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "Tenant isolation: $($violations.Count) pending violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | Select-Object -First 30 | ForEach-Object { Write-Error $_ }
Write-Error "Tenant isolation: $($violations.Count) violation(s) in total. Worklist: $worklistPath"
exit 1
