[CmdletBinding()]
param(
  [string]$Gateway = 'http://127.0.0.1:30002',
  [string]$KubeContext = 'kind-open-im-local',
  [string]$Namespace = 'open-im-local',
  [string]$Database = 'open_im',
  [string]$Username,
  [string]$Password
)
# Verifies the effect of the backend feature flags on the unread badge (the bug#8 scenario):
# with the secret/secret_group flags ON the rows count toward the msg_secret_unread projection; with them OFF the server excludes them (count=0 and an empty by-conversation list).
# The one-off rows and config are cleaned up and restored in the finally block, so reruns are idempotent and repeatable. Needs the root MySQL password (read from the open-im-env secret).
$ErrorActionPreference = 'Stop'
if (!$Username -or !$Password) { throw 'Provide -Username/-Password of a disposable account.' }

function MySql([string]$Query) {
  $pwd = kubectl --context $KubeContext -n $Namespace get secret open-im-env -o jsonpath='{.data.DB_PASSWORD}' |
    ForEach-Object { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($_)) }
  kubectl --context $KubeContext -n $Namespace exec deploy/mysql -- mysql -uroot "-p$pwd" $Database -N -e $Query 2>$null
  if ($LASTEXITCODE -ne 0) { throw "mysql failed: $Query" }
}
function UnreadCount([string]$Token) {
  return (Invoke-WebRequest -UseBasicParsing -Headers @{ Authorization = "Bearer $Token" } "$Gateway/api/v1/messages/unread-count" -TimeoutSec 15).Content
}
function ByConversation([string]$Token) {
  return (Invoke-WebRequest -UseBasicParsing -Headers @{ Authorization = "Bearer $Token" } "$Gateway/api/v1/messages/unread-by-conversation" -TimeoutSec 15).Content
}

$login = Invoke-RestMethod -Method Post -Uri "$Gateway/api/v1/auth/login" -ContentType 'application/json' -Body (@{ username = $Username; password = $Password } | ConvertTo-Json)
$token = $login.access_token
$uid = [long]$login.user.id
$nowUtc = (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss.fff')
$fail = $false
try {
  # 1) Insert two secret-message unread projections (secret / secret_group).
  MySql "DELETE FROM msg_secret_unread WHERE user_id=$uid AND conversation_id IN (99999901,99999902);"
  MySql "INSERT INTO msg_secret_unread(chat_type,conversation_id,user_id,msg_id,seq,created_at) VALUES ('secret',99999901,$uid,'toggle-u1',1,NOW(3)),('secret_group',99999902,$uid,'toggle-u2',1,NOW(3));"

  # 2) Force both flags to true, wait out the adapter TTL, then assert the rows are counted.
  MySql "INSERT INTO adm_system_config(config_key,config_value,config_group,description,created_at,updated_at) VALUES ('feature.secretChatEnabled','true','feature','unread-toggle-verify','$nowUtc','$nowUtc'),('feature.secretGroupChatEnabled','true','feature','unread-toggle-verify','$nowUtc','$nowUtc') ON DUPLICATE KEY UPDATE config_value='true',updated_at='$nowUtc';"
  Start-Sleep -Seconds 65
  $on = UnreadCount $token
  if ($on -notmatch '"count"\s*:\s*2') { Write-Output "FAIL expected count 2 with flags ON, got $on"; $fail = $true }

  # 3) Set both flags to false, wait out the TTL, then assert the rows are excluded.
  MySql "UPDATE adm_system_config SET config_value='false',updated_at='$nowUtc' WHERE config_key IN ('feature.secretChatEnabled','feature.secretGroupChatEnabled');"
  Start-Sleep -Seconds 65
  $off = UnreadCount $token
  $byConv = ByConversation $token
  if ($off -notmatch '"count"\s*:\s*0') { Write-Output "FAIL expected count 0 with flags OFF, got $off"; $fail = $true }
  if ($byConv.Trim() -ne '[]') { Write-Output "FAIL expected [] with flags OFF, got $byConv"; $fail = $true }
  Write-Output "PASS flags ON => $on ; OFF => $off ; by-conv => $byConv"
}
finally {
  # 4) Remove the temporary rows and restore the flags (default true).
  MySql "UPDATE adm_system_config SET config_value='true',updated_at='$nowUtc' WHERE config_key IN ('feature.secretChatEnabled','feature.secretGroupChatEnabled');"
  MySql "DELETE FROM msg_secret_unread WHERE user_id=$uid AND conversation_id IN (99999901,99999902);"
  Write-Output 'cleanup done (rows removed, flags restored to true).'
}
if ($fail) { exit 1 }
Write-Output 'UNREAD_TOGGLE_VERIFY_PASSED'
