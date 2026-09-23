[CmdletBinding()]
param(
  [string]$Gateway = 'http://127.0.0.1:30002',
  [string]$KubeContext = 'kind-open-im-local',
  [string]$Namespace = 'open-im-local',
  [string]$Database = 'im_server',
  [string]$Username,
  [string]$Password
)
# 验证后台功能开关对未读角标的影响（bug#8 场景）：
# secret/secret_group 两开关 ON 时计入 msg_secret_unread 投影；OFF 时服务端排除（count=0 且按会话为空）。
# 一次性数据与配置在 finally 中清理恢复，幂等可重复。需 root MySQL 密码（读 open-im-env secret）。
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
  # 1) 写入两条秘密消息未读投影（secret / secret_group）
  MySql "DELETE FROM msg_secret_unread WHERE user_id=$uid AND conversation_id IN (99999901,99999902);"
  MySql "INSERT INTO msg_secret_unread(chat_type,conversation_id,user_id,msg_id,seq,created_at) VALUES ('secret',99999901,$uid,'toggle-u1',1,NOW(3)),('secret_group',99999902,$uid,'toggle-u2',1,NOW(3));"

  # 2) 两开关显式置 true，等待适配器 TTL 后断言计入
  MySql "INSERT INTO adm_system_config(config_key,config_value,config_group,description,created_at,updated_at) VALUES ('feature.secretChatEnabled','true','feature','unread-toggle-verify','$nowUtc','$nowUtc'),('feature.secretGroupChatEnabled','true','feature','unread-toggle-verify','$nowUtc','$nowUtc') ON DUPLICATE KEY UPDATE config_value='true',updated_at='$nowUtc';"
  Start-Sleep -Seconds 65
  $on = UnreadCount $token
  if ($on -notmatch '"count"\s*:\s*2') { Write-Output "FAIL expected count 2 with flags ON, got $on"; $fail = $true }

  # 3) 两开关置 false，等待 TTL 后断言排除
  MySql "UPDATE adm_system_config SET config_value='false',updated_at='$nowUtc' WHERE config_key IN ('feature.secretChatEnabled','feature.secretGroupChatEnabled');"
  Start-Sleep -Seconds 65
  $off = UnreadCount $token
  $byConv = ByConversation $token
  if ($off -notmatch '"count"\s*:\s*0') { Write-Output "FAIL expected count 0 with flags OFF, got $off"; $fail = $true }
  if ($byConv.Trim() -ne '[]') { Write-Output "FAIL expected [] with flags OFF, got $byConv"; $fail = $true }
  Write-Output "PASS flags ON => $on ; OFF => $off ; by-conv => $byConv"
}
finally {
  # 4) 清理临时数据并恢复开关（默认 true）
  MySql "UPDATE adm_system_config SET config_value='true',updated_at='$nowUtc' WHERE config_key IN ('feature.secretChatEnabled','feature.secretGroupChatEnabled');"
  MySql "DELETE FROM msg_secret_unread WHERE user_id=$uid AND conversation_id IN (99999901,99999902);"
  Write-Output 'cleanup done (rows removed, flags restored to true).'
}
if ($fail) { exit 1 }
Write-Output 'UNREAD_TOGGLE_VERIFY_PASSED'
