# C-side / OTA / channel-payment E2E fixture (LOCAL KIND ONLY: open-im-local).
#
# Purpose:
#   1) Ensure the C-side dev account 101 (SAAS_DEV_SESSION_CONSUMER_ACCOUNT_ID=101) has
#      an ACTIVE CUSTOMER membership plus a cst_member profile, so that
#      GET /api/v1/auth/contexts?appId=saas-c can resolve a consumer context.
#   2) Switch the saas-c consumer app installation (iam_consumer_application) between two
#      verification profiles:
#         C-Hotel : store_id=102 (HOTEL store), permissions include hotel.booking.view/create
#         C-Ktv   : store_id=100 (KTV store),  permissions include reservation.view/create
#      The original row is backed up (once) to
#         <OutDir>/c-channel-fixture-backup.json
#      and restored by -Mode Restore, so other workstreams' C-side cases are not affected.
#
# Boundary: no business-code change, no Flyway change; only test data
# (idempotent membership/member inserts + temporary app-installation switch).
# Safe default is -Mode Status, so scripts/verify/run-all.ps1 cannot accidentally leave
# the app installation switched.
#
# Usage:
#   powershell -File scripts/verify/prepare-e2e-c-hotel-fixture.ps1 -Mode Status
#   powershell -File scripts/verify/prepare-e2e-c-hotel-fixture.ps1 -Mode Prepare -Profile C-Hotel
#   powershell -File scripts/verify/prepare-e2e-c-hotel-fixture.ps1 -Mode Restore
[CmdletBinding()]
param(
  [string]$Namespace = 'open-im-local',
  [ValidateSet('Status', 'Prepare', 'Restore')][string]$Mode = 'Status',
  [ValidateSet('C-Hotel', 'C-Ktv')][string]$Profile = 'C-Hotel',
  [string]$OutDir = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$dir = if ([string]::IsNullOrWhiteSpace($OutDir)) { Join-Path $root '.outputs\releases\architect-ii' }
       elseif ([System.IO.Path]::IsPathRooted($OutDir)) { $OutDir }
       else { Join-Path $root $OutDir }
[void](New-Item -ItemType Directory -Force -Path $dir)

if ($Namespace -ne 'open-im-local') { throw "Only the local Kind namespace open-im-local is allowed, got: $Namespace" }

$script:backupPath = Join-Path $dir 'c-channel-fixture-backup.json'
$script:dbPassword = $null

function Invoke-Cluster([string[]]$arguments) {
  $prefix = @('--context', 'kind-open-im-local', '-n', $script:Namespace)
  $previous = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $out = & kubectl @prefix @arguments 2>&1
    $code = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $previous
  }
  $lines = @($out | ForEach-Object { [string]$_ } | Where-Object { $_ -notmatch 'mysql: \[Warning\]' })
  if ($code -ne 0) { throw ("kubectl failed: {0}`n{1}" -f ($arguments -join ' '), ($lines -join "`n")) }
  return $lines
}

function Get-DbPassword {
  if ($script:dbPassword) { return $script:dbPassword }
  $b64 = (Invoke-Cluster @('get', 'secret', 'open-im-env', '-o', 'jsonpath={.data.DB_PASSWORD}')).Trim()
  $script:dbPassword = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($b64))
  return $script:dbPassword
}

function Invoke-Sql([string]$sql) {
  $pwd = Get-DbPassword
  $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($sql))
  $remote = "echo $b64 | base64 -d | mysql -uroot -p`"$pwd`" open_saas -N -B"
  return (Invoke-Cluster @('exec', 'deploy/mysql', '--', 'sh', '-c', $remote))
}

function Get-SqlValue([string]$sql) {
  $rows = Invoke-Sql $sql
  $value = @($rows | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Last 1)
  if ($value.Count -eq 0) { return '' }
  return ([string]$value[0]).Trim()
}

function Ensure-CustomerFixture {
  $sql = @"
INSERT INTO tnt_membership (tenant_id, account_id, organization_id, membership_type, status, member_no,
                            authorization_version, created_at, updated_at)
SELECT 100, 101, 100, 'CUSTOMER', 'ACTIVE', 'C101E2E', 1, NOW(3), NOW(3)
 WHERE NOT EXISTS (SELECT 1 FROM tnt_membership
                    WHERE tenant_id = 100 AND account_id = 101 AND membership_type = 'CUSTOMER');
INSERT INTO cst_member (tenant_id, origin_store_id, account_id, member_no, name_cipher, phone_cipher,
                        phone_digest, status, joined_at, version, created_at, updated_at)
SELECT 100, 102, 101, 'M101E2E', 'v1:e2e', 'v1:e2e', 'e2e-101', 'ACTIVE', NOW(3), 0, NOW(3), NOW(3)
 WHERE NOT EXISTS (SELECT 1 FROM cst_member WHERE tenant_id = 100 AND account_id = 101);
"@
  [void](Invoke-Sql $sql)
}

function Get-ConsumerAppRow {
  $line = Get-SqlValue "SELECT CONCAT_WS('|', tenant_id, organization_id, COALESCE(store_id, 0), REPLACE(permissions_json, '|', ' '), status) FROM iam_consumer_application WHERE app_id = 'saas-c';"
  if ([string]::IsNullOrWhiteSpace($line)) { throw 'iam_consumer_application has no saas-c row; cannot prepare the C app installation' }
  $parts = $line -split '\|', 5
  return [pscustomobject]@{
    appId = 'saas-c'; tenantId = [long]$parts[0]; organizationId = [long]$parts[1];
    storeId = [long]$parts[2]; permissionsJson = $parts[3]; status = $parts[4]
  }
}

function Get-FixtureState {
  $membership = Get-SqlValue "SELECT CONCAT_WS('|', id, status) FROM tnt_membership WHERE tenant_id = 100 AND account_id = 101 AND membership_type = 'CUSTOMER' LIMIT 1;"
  $member = Get-SqlValue "SELECT CONCAT_WS('|', id, status) FROM cst_member WHERE tenant_id = 100 AND account_id = 101 LIMIT 1;"
  $app = Get-ConsumerAppRow
  return [pscustomobject]@{ customerMembership = $membership; memberProfile = $member; consumerApp = $app }
}

function Save-OriginalBackup([object]$row) {
  if (Test-Path -LiteralPath $script:backupPath) { return }
  $backup = [ordered]@{
    appId = $row.appId; tenantId = $row.tenantId; organizationId = $row.organizationId;
    storeId = $row.storeId; permissionsJson = $row.permissionsJson; status = $row.status;
    savedAt = (Get-Date).ToString('o')
  }
  [System.IO.File]::WriteAllText($script:backupPath, ($backup | ConvertTo-Json -Depth 5),
    [System.Text.UTF8Encoding]::new($false))
}

function Set-ConsumerApp([long]$storeId, [string]$permissionsJson) {
  $escaped = $permissionsJson.Replace("'", "''")
  [void](Invoke-Sql ("UPDATE iam_consumer_application SET store_id = $storeId, permissions_json = '$escaped', updated_at = NOW(3) WHERE app_id = 'saas-c';"))
}

function Get-ProfileSettings([string]$name) {
  if ($name -eq 'C-Ktv') {
    return @{ storeId = 100; permissionsJson = '["reservation.view","reservation.create"]' }
  }
  return @{ storeId = 102; permissionsJson = '["reservation.view","reservation.create","hotel.booking.view","hotel.booking.create"]' }
}

$result = [ordered]@{ mode = $Mode; profile = $Profile; at = (Get-Date).ToString('o'); namespace = $Namespace }
switch ($Mode) {
  'Status' {
    $result.state = Get-FixtureState
  }
  'Prepare' {
    Ensure-CustomerFixture
    $current = Get-ConsumerAppRow
    $wanted = Get-ProfileSettings $Profile
    if ($current.storeId -ne $wanted.storeId -or $current.permissionsJson -ne $wanted.permissionsJson) {
      Save-OriginalBackup $current
      Set-ConsumerApp $wanted.storeId $wanted.permissionsJson
    }
    $result.state = Get-FixtureState
    $result.backupFile = $script:backupPath
  }
  'Restore' {
    if (Test-Path -LiteralPath $script:backupPath) {
      $backup = Get-Content -LiteralPath $script:backupPath -Raw | ConvertFrom-Json
      Set-ConsumerApp ([long]$backup.storeId) ([string]$backup.permissionsJson)
      Remove-Item -LiteralPath $script:backupPath -Force
      $result.restoredFrom = $backup.savedAt
    } else {
      # No backup: the row already is the clean-Kind baseline (store 100 + reservation perms).
      Set-ConsumerApp 100 '["reservation.view","reservation.create"]'
      $result.restoredFrom = 'default'
    }
    $result.state = Get-FixtureState
  }
}

$result | ConvertTo-Json -Depth 8
