param(
  [string]$GatewayUrl = 'http://127.0.0.1:3002',
  [string]$AdminUsername,
  [securestring]$AdminPassword,
  [string]$PcOrigin = 'http://127.0.0.1:5173'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
if ([string]::IsNullOrWhiteSpace($AdminUsername) -or $null -eq $AdminPassword) {
  throw 'AdminUsername and AdminPassword are required. Do not store credentials in this script.'
}

function ConvertTo-PlainText {
  param([securestring]$Value)
  $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
  try {
    return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
  } finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
  }
}

function Assert-Equal([object]$Actual, [object]$Expected, [string]$Name) {
  if ($Actual -ne $Expected) { throw "$Name expected $Expected but was $Actual" }
  Write-Output "PASS $Name ($Actual)"
}

function Assert-True([bool]$Value, [string]$Name) {
  if (-not $Value) { throw "$Name failed" }
  Write-Output "PASS $Name"
}

Assert-Equal (Invoke-WebRequest -UseBasicParsing "$GatewayUrl/actuator/health").StatusCode 200 'gateway health'

try {
  Invoke-WebRequest -UseBasicParsing "$GatewayUrl/api/v1/admin/stats/overview" | Out-Null
  throw 'unauthenticated admin request unexpectedly succeeded'
} catch [System.Net.WebException] {
  Assert-Equal ([int]$_.Exception.Response.StatusCode) 401 'v1 admin requires authentication'
}

try {
  Invoke-WebRequest -UseBasicParsing "$GatewayUrl/api/admin/stats/overview" | Out-Null
  throw 'legacy admin request unexpectedly succeeded'
} catch [System.Net.WebException] {
  Assert-Equal ([int]$_.Exception.Response.StatusCode) 404 'legacy admin route removed'
}

$cors = Invoke-WebRequest -UseBasicParsing -Method Options -Headers @{
  Origin = $PcOrigin
  'Access-Control-Request-Method' = 'GET'
} "$GatewayUrl/api/v1/admin/stats/overview"
Assert-Equal $cors.StatusCode 200 'gateway CORS preflight'
Assert-Equal $cors.Headers['Access-Control-Allow-Origin'] $PcOrigin 'gateway CORS origin'

$adminPasswordValue = ConvertTo-PlainText -Value $AdminPassword
$login = Invoke-RestMethod -Method Post -ContentType 'application/json' -Body (@{
  username = $AdminUsername
  password = $adminPasswordValue
} | ConvertTo-Json -Compress) -Uri "$GatewayUrl/api/v1/auth/login"
Assert-True (-not [string]::IsNullOrWhiteSpace($login.access_token)) 'admin login token'
$headers = @{ Authorization = "Bearer $($login.access_token)"; 'X-Client-Contract' = 'im-v1' }

$overview = Invoke-RestMethod -Headers $headers -Uri "$GatewayUrl/api/v1/admin/stats/overview"
Assert-True ($overview.PSObject.Properties.Name -contains 'updatedAt') 'overview updatedAt'
$violations = Invoke-RestMethod -Headers $headers -Uri "$GatewayUrl/api/v1/admin/security/violations?page=1&pageSize=20&action=warned"
Assert-True ($violations.PSObject.Properties.Name -contains 'updatedAt') 'list updatedAt'

$png = [Convert]::FromBase64String('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLq7wAAAABJRU5ErkJggg==')
$sha256Algorithm = [System.Security.Cryptography.SHA256]::Create()
try {
  $sha256 = ([System.BitConverter]::ToString($sha256Algorithm.ComputeHash($png))).Replace('-', '').ToLowerInvariant()
} finally {
  $sha256Algorithm.Dispose()
}
$mediaHeaders = @{ Authorization = "Bearer $($login.access_token)"; 'Idempotency-Key' = [guid]::NewGuid().ToString() }
$session = Invoke-RestMethod -Method Post -Headers $mediaHeaders -ContentType 'application/json' -Body (@{
  scope = 'avatar'
  mediaKind = 'image'
  contentType = 'image/png'
  fileName = 'smoke.png'
  size = $png.Length
  sha256 = $sha256
} | ConvertTo-Json -Compress) -Uri "$GatewayUrl/api/v1/media/upload-sessions"
Assert-True (-not [string]::IsNullOrWhiteSpace($session.uploadSessionId)) 'media upload session'
Assert-True ($session.uploadUrl -match '^https?://') 'media upload URL'

$sessionHeaders = @{ Authorization = "Bearer $($login.access_token)" }
$sessionStatus = Invoke-RestMethod -Headers $sessionHeaders -Uri "$GatewayUrl/api/v1/media/upload-sessions/$($session.uploadSessionId)"
Assert-Equal $sessionStatus.status 'uploading' 'media upload session status'
$cancelResponse = Invoke-WebRequest -UseBasicParsing -Method Delete -Headers $sessionHeaders -Uri "$GatewayUrl/api/v1/media/upload-sessions/$($session.uploadSessionId)"
Assert-True ([int]$cancelResponse.StatusCode -in @(200, 204)) 'media upload session cancellation'

$ticket = Invoke-RestMethod -Method Post -Headers $headers -Uri "$GatewayUrl/api/v1/auth/ws-ticket"
Assert-True (-not [string]::IsNullOrWhiteSpace($ticket.ticket)) 'websocket ticket issued'
$wsBase = $GatewayUrl -replace '^http:', 'ws:' -replace '^https:', 'wss:'
$socket = [System.Net.WebSockets.ClientWebSocket]::new()
try {
  $null = $socket.ConnectAsync([Uri]"$wsBase/ws/im/v1?ticket=$($ticket.ticket)", [Threading.CancellationToken]::None).GetAwaiter().GetResult()
  Assert-Equal $socket.State ([System.Net.WebSockets.WebSocketState]::Open) 'websocket ticket accepted once'
} finally {
  if ($socket.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
    $null = $socket.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, 'smoke complete', [Threading.CancellationToken]::None).GetAwaiter().GetResult()
  }
  $socket.Dispose()
}

$reused = [System.Net.WebSockets.ClientWebSocket]::new()
try {
  $null = $reused.ConnectAsync([Uri]"$wsBase/ws/im/v1?ticket=$($ticket.ticket)", [Threading.CancellationToken]::None).GetAwaiter().GetResult()
  $cancellation = [Threading.CancellationTokenSource]::new(1000)
  try {
    $buffer = [Array]::CreateInstance([byte], 32)
    $result = $reused.ReceiveAsync([ArraySegment[byte]]::new($buffer), $cancellation.Token).GetAwaiter().GetResult()
    Assert-Equal $result.MessageType ([System.Net.WebSockets.WebSocketMessageType]::Close) 'websocket ticket cannot be reused'
  } catch [System.OperationCanceledException] {
    throw 'reused websocket ticket remained connected'
  } finally {
    $cancellation.Dispose()
  }
} catch {
  if ($_.Exception.Message -eq 'reused websocket ticket remained connected' -or $_.Exception.Message -like 'websocket ticket cannot be reused expected*') { throw }
  Write-Output 'PASS websocket ticket cannot be reused'
} finally {
  $reused.Dispose()
}
