[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$migration = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'im-services\user\im-user-service\src\main\resources\db\migration\V1__init.sql'
$text = [System.IO.File]::ReadAllText($migration, [System.Text.UTF8Encoding]::new($false, $true))
$expected = [System.Collections.Generic.HashSet[string]]::new([string[]]@(
    'user', 'user_device_token', 'user_friend_request', 'user_friend', 'user_point_account', 'user_point_ledger', 'user_sticker', 'user_outbox', 'user_sticker_quota', 'user_admin_status_operation'))
$actual = [System.Collections.Generic.HashSet[string]]::new()

foreach ($match in [regex]::Matches($text, 'CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`([^`]+)`', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
  [void]$actual.Add($match.Groups[1].Value)
}

$violations = [System.Collections.Generic.List[string]]::new()
foreach ($table in $expected) {
  if (-not $actual.Contains($table)) {
    $violations.Add("Missing user-domain table: $table")
  }
}
foreach ($table in $actual) {
  if (-not $expected.Contains($table)) {
    $violations.Add("Cross-domain table in user V1: $table")
  }
  if ($table.EndsWith('s')) {
    $violations.Add("Plural table name in user V1: $table")
  }
  $definitionPattern = 'CREATE\s+TABLE\s+`' + [regex]::Escape($table) + '`\s*\((?<definition>[\s\S]*?)\)\s*ENGINE='
  $definitionMatch = [regex]::Match(
      $text,
      $definitionPattern,
      [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
  if (-not $definitionMatch.Success) {
    $violations.Add("Unable to inspect user-domain table definition: $table")
    continue
  }
  $requiredColumns = if ($table -eq 'user_sticker_quota') {
    @('user_id', 'created_at', 'updated_at')
  } elseif ($table -eq 'user_admin_status_operation') {
    @('id', 'idempotency_key', 'user_id', 'created_at')
  } else {
    @('id', 'created_by', 'created_at', 'updated_by', 'updated_at')
  }
  foreach ($column in $requiredColumns) {
    $columnPattern = [regex]::Escape('`' + $column + '`')
    if ($definitionMatch.Groups['definition'].Value -notmatch $columnPattern) {
      $violations.Add("Missing required audit column in ${table}: $column")
    }
  }
}
if ($text -match 'TypeORM synchronize') {
  $violations.Add('Legacy TypeORM description remains in user V1.')
}

if ($violations.Count -gt 0) {
  $violations | ForEach-Object { Write-Error $_ }
  exit 1
}

Write-Host 'Flyway module boundary validation passed.'
