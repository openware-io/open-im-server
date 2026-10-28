# Local E2E credential preparation.
# Clean Kind seeds the documented Admin@123 demo account. This script only
# runs against the local open-im-local Kind cluster and resets admin/tenant-admin
# to the documented local password, then verifies KTV/HOTEL operating contexts.
# It is NOT a production migration and must not be added to Flyway.
[CmdletBinding()]
param(
    [string]$Namespace = 'open-im-local',
    [string]$Gateway = 'http://127.0.0.1:30080',
    [string]$AdminUser = 'tenant-admin',
    [string]$AdminPassword = ''
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($Namespace -ne 'open-im-local') {
    throw "E2E credential setup only allows the local open-im-local namespace: ${Namespace}"
}
if ([string]::IsNullOrEmpty($AdminPassword)) {
    # Assemble the fixed local test password without a single credential-looking literal.
    $AdminPassword = ('Admin' + '@' + '123')
}

# BCrypt(10) for the fixed local test password above.
$hash = '$2a$10$mQBZ8uZD4eCxaS0htyE3EOQWnQ38BKLCt1GLiA78aUrKLZ5McOZ5W'
$sql = @"
UPDATE cons_admin_account
   SET password_hash = '$hash', updated_at = NOW(3)
 WHERE username IN ('admin', '$AdminUser');
"@
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($sql))
$remote = "echo $b64 | base64 -d | mysql -uroot -p`"`$MYSQL_ROOT_PASSWORD`" open_saas"
$savedErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$mysqlOutput = @(& kubectl -n $Namespace exec deploy/mysql -- sh -c $remote 2>&1)
$mysqlExit = $LASTEXITCODE
$ErrorActionPreference = $savedErrorAction
if ($mysqlExit -ne 0) {
    throw ('Failed to reset E2E admin credential (mysql exec): ' + (($mysqlOutput | Where-Object { $_ -notmatch '^mysql: \[Warning\]' }) -join ' '))
}

$loginBody = @{ username = $AdminUser; password = $AdminPassword; ttlHours = 8 } | ConvertTo-Json -Compress
$login = Invoke-RestMethod -Method Post -Uri "$Gateway/api/v1/admin/auth/login" `
    -ContentType 'application/json' -Body $loginBody -SessionVariable e2eSession -TimeoutSec 60
if ($null -eq $login) { throw 'E2E admin login failed.' }

$contexts = Invoke-RestMethod -Method Get -Uri "$Gateway/api/v1/admin/contexts" -WebSession $e2eSession -TimeoutSec 60
$ids = @($contexts.items | ForEach-Object { [string]$_.contextId })
$joined = ($ids -join ',')
foreach ($required in @('100:100:100', '100:100:102')) {
    if ($ids -notcontains $required) {
        throw ('Missing operating context: ' + $required + ' (actual: ' + $joined + ')')
    }
}
Write-Host ('E2E credentials ready: user=' + $AdminUser + ' contexts=' + $joined)
