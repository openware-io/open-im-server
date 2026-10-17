# Audit-field validator (standards/10 L299).
#
# Rule: createdBy/createdAt/updatedBy/updatedAt are bookkeeping, not domain facts.
# A domain model under domain/**/model/** must not declare them. Values needed by a
# response are carried by a Persisted/AuditSnapshot or a named read model.
#
# Allowlist (explicit, with a reason):
#   - ClientReleaseOperation / ReleaseAuditLog: append-only operation/audit payloads;
#     createdBy/createdAt are "who acted and when", i.e. the record's own data.
#   - UserSyncIndex: createdAt is the sync instant used by the sync protocol.
#
# IMPORTANT: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).
#
# Usage: powershell -File scripts/validate/validate-audit-fields.ps1 [-ReportOnly]
[CmdletBinding()]
param(
  [switch]$ReportOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$worklistPath = Join-Path $root '.outputs\quality\audit-fields-worklist.txt'

$allowlist = @(
  'im-services/admin/im-admin-service/src/main/java/io/openware/im/admin/domain/clientrelease/model/ClientReleaseOperation.java',
  'im-services/admin/im-admin-service/src/main/java/io/openware/im/admin/domain/clientrelease/model/ReleaseAuditLog.java',
  'im-services/message/im-message-service/src/main/java/io/openware/im/message/domain/message/model/UserSyncIndex.java'
)

$violations = [System.Collections.Generic.List[string]]::new()
$recordPattern = '(?s)public\s+record\s+\w+\s*\([^)]*\b(createdBy|createdAt|updatedBy|updatedAt)\b'
$fieldPattern = '(?m)^\s*(private|protected|public)\s+(static\s+)?(final\s+)?[\w<>\[\], ?]+\s+(createdBy|createdAt|updatedBy|updatedAt)\s*[;=]'

Push-Location $root
try {
  $tracked = git -C $root ls-files
  $modelFiles = $tracked | Where-Object {
    $_ -match '(^|/)(im-services|platform-services|common-services|group-services)/.*/src/main/java/.*/domain/.*/model/.*\.java$'
  }
  foreach ($file in $modelFiles) {
    if ($allowlist -contains $file) { continue }
    $path = Join-Path $root $file
    $text = [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false, $true))
    if ([regex]::IsMatch($text, $recordPattern) -or [regex]::IsMatch($text, $fieldPattern)) {
      $violations.Add("audit field in domain model: $file")
    }
  }
} finally {
  Pop-Location
}

if ($violations.Count -eq 0) {
  if (Test-Path -LiteralPath $worklistPath) { Remove-Item -LiteralPath $worklistPath -Force }
  Write-Host 'Audit-field validation passed.'
  exit 0
}

[void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $worklistPath))
[System.IO.File]::WriteAllLines($worklistPath, [string[]]$violations, [System.Text.UTF8Encoding]::new($false))

if ($ReportOnly) {
  Write-Warning "Audit-field validation: $($violations.Count) pending violation(s). Worklist: $worklistPath"
  exit 0
}

$violations | Select-Object -First 30 | ForEach-Object { Write-Error $_ }
Write-Error "Audit-field validation: $($violations.Count) violation(s) in total. Worklist: $worklistPath"
exit 1
