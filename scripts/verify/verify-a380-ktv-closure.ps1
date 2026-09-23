[CmdletBinding()]
param(
  [string]$Gateway = 'http://127.0.0.1:30002',
  [string]$H5Base = 'http://127.0.0.1:30082',
  [string]$Context = 'kind-open-im-local',
  [string]$Namespace = 'open-im-local',
  [string]$Kubeconfig = '',
  [string]$DatabaseSecret = 'open-im-env',
  [string]$DatabaseSecretKey = 'DB_PASSWORD',
  [string]$RedisSecret = $DatabaseSecret,
  [string]$RootDatabaseSecretKey = $DatabaseSecretKey
)

$ErrorActionPreference = 'Stop'
$script:context = $Context; $script:namespace = $Namespace; $script:kubeconfig = $Kubeconfig
function Invoke-Cluster([string[]]$arguments) { $prefix = @(); if ($script:kubeconfig) { $prefix += @('--kubeconfig', $script:kubeconfig) }; $prefix += @('--context', $script:context, '-n', $script:namespace); for($attempt=1;$attempt -le 4;$attempt++){ $result=& kubectl @prefix @arguments; if($LASTEXITCODE -eq 0){return $result}; if($attempt -lt 4){Start-Sleep -Seconds ([int][Math]::Pow(2,$attempt))} }; return $result }
function MySqlTarget { $deployment = Invoke-Cluster @('get','deployment','mysql','--ignore-not-found','-o','name'); if ($deployment) { return 'deployment/mysql' }; $statefulSet = Invoke-Cluster @('get','statefulset','mysql','--ignore-not-found','-o','name'); if ($statefulSet) { return 'statefulset/mysql' }; throw 'MySQL workload was not found.' }
function RedisTarget { $deployment = Invoke-Cluster @('get','deployment','redis','--ignore-not-found','-o','name'); if ($deployment) { return 'deployment/redis' }; $statefulSet = Invoke-Cluster @('get','statefulset','redis','--ignore-not-found','-o','name'); if ($statefulSet) { return 'statefulset/redis' }; throw 'Redis workload was not found.' }
function DbPassword { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String((Invoke-Cluster @('get','secret',$DatabaseSecret,'-o',"jsonpath={.data.$RootDatabaseSecretKey}")).Trim())) }
function RedisPassword { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String((Invoke-Cluster @('get','secret',$RedisSecret,'-o','jsonpath={.data.REDIS_PASSWORD}')).Trim())) }
function Sql([string]$query) { Invoke-Cluster @('exec',(MySqlTarget),'--','mysql','-u','root',"-p$script:dbPassword",'open_im','-e',$query) | Out-Null; if ($LASTEXITCODE) { throw 'MySQL cleanup failed.' } }
function SqlValue([string]$query) { $out = Invoke-Cluster @('exec',(MySqlTarget),'--','mysql','-u','root',"-p$script:dbPassword",'open_im','-N','-B','-e',$query); if ($LASTEXITCODE) { throw 'MySQL assertion failed.' }; return ($out | Select-Object -Last 1).ToString().Trim() }
function SqlIm([string]$query) { Invoke-Cluster @('exec',(MySqlTarget),'--','mysql','-u','root',"-p$script:dbPassword",'im_server','-e',$query) | Out-Null; if ($LASTEXITCODE) { throw 'MySQL identity command failed.' } }
function Remove-SaasSession([string]$cookie) {
  if ([string]::IsNullOrWhiteSpace($cookie)) { return }
  $sessionId = ($cookie -split '=', 2)[1]
  if ([string]::IsNullOrWhiteSpace($sessionId)) { return }
  Invoke-Cluster @('exec',(RedisTarget),'--','redis-cli','-a',$script:redisPassword,'DEL',"saas:user-session:$sessionId","saas:user-csrf:$sessionId") | Out-Null
  if ($LASTEXITCODE) { throw 'Redis SaaS session cleanup failed.' }
}
function Esc([string]$value) { [Uri]::EscapeDataString($value) }
function Challenge([string]$verifier) { $sha = [Security.Cryptography.SHA256]::Create(); try { $bytes = $sha.ComputeHash([Text.Encoding]::ASCII.GetBytes($verifier)) } finally { $sha.Dispose() }; [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_') }
function Location([string[]]$headers) { (($headers | Where-Object { $_ -match '^location:' } | Select-Object -First 1) -replace '^location:\s*','') }
function Code([string]$redirect) {
  $verifier = "ktv-$script:suffix"; $state = "state-$script:suffix"; $nonce = "nonce-$script:suffix"
  $query = "appId=saas-a380-c&client_id=saas-a380-c&response_type=code&redirect_uri=$(Esc $redirect)&scope=profile.basic&code_challenge=$(Esc (Challenge $verifier))&code_challenge_method=S256&state=$state&nonce=$nonce"
  $auth = & curl.exe -sS -D - -o NUL --write-out 'HTTP_STATUS:%{http_code}' --max-redirs 0 -H "Authorization: Bearer $script:imToken" "$Gateway/oauth/authorize?$query"
  $authorizeLocation = Location $auth; if (!$authorizeLocation) { $status = ($auth | Where-Object { $_ -like 'HTTP_STATUS:*' } | Select-Object -Last 1); throw "OAuth authorize did not return a redirect ($status)." }
  $requestId = [Uri]::UnescapeDataString(([regex]::Match($authorizeLocation,'request_id=([^&]+)').Groups[1].Value)); if (!$requestId) { throw 'OAuth authorize did not reach consent.' }
  $approved = & curl.exe -sS -D - -o NUL --write-out 'HTTP_STATUS:%{http_code}' --max-redirs 0 -X POST -H "Authorization: Bearer $script:imToken" "$Gateway/oauth/consent/approve?request_id=$(Esc $requestId)&appId=saas-a380-c&client_id=saas-a380-c&scope=profile.basic"
  $approvedLocation = Location $approved; if (!$approvedLocation) { $status = ($approved | Where-Object { $_ -like 'HTTP_STATUS:*' } | Select-Object -Last 1); throw "OAuth consent approval did not return a redirect ($status)." }
  $code = [Uri]::UnescapeDataString(([regex]::Match($approvedLocation,'code=([^&]+)').Groups[1].Value)); if (!$code) { throw 'OAuth consent did not issue code.' }
  @{ code=$code; verifier=$verifier; state=$state; nonce=$nonce }
}
function Json([string]$method, [string]$path, [object]$body, [hashtable]$headers) {
  $uri = "$Gateway$path"
  $requestHeaders = @{}
  if ($headers) { $headers.GetEnumerator() | ForEach-Object { $requestHeaders[$_.Key] = $_.Value } }
  $args = @{ Method=$method; Uri=$uri; Headers=$requestHeaders; UseBasicParsing=$true }
  if ($requestHeaders.ContainsKey('Cookie')) {
    $cookieName, $cookieValue = $requestHeaders['Cookie'] -split '=', 2
    if (!$cookieName -or !$cookieValue) { throw "Invalid Cookie header for $path" }
    $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $session.Cookies.Add([System.Net.Cookie]::new($cookieName, $cookieValue, '/', ([Uri]$uri).Host))
    $requestHeaders.Remove('Cookie')
    $args.WebSession = $session
  }
  if ($null -ne $body) { $args.ContentType='application/json'; $args.Body=($body | ConvertTo-Json -Compress -Depth 8) }
  try {
    $result = Invoke-WebRequest @args
  } catch {
    $response = $_.Exception.Response
    $status = if ($response) { [int]$response.StatusCode } else { 'transport-error' }
    $detail = [string]$_.ErrorDetails.Message
    if ([string]::IsNullOrWhiteSpace($detail) -and $response -and $response.PSObject.Methods['GetResponseStream']) {
      $reader = [IO.StreamReader]::new($response.GetResponseStream())
      try { $detail = $reader.ReadToEnd() } finally { $reader.Dispose() }
    }
    throw "Request $method $path failed with HTTP ${status}: $detail"
  }
  if (!$result.Content) { return $null }
  $content = if ($result.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($result.Content) } else { [string]$result.Content }
  return ($content | ConvertFrom-Json)
}

$script:dbPassword = DbPassword; $script:redisPassword = RedisPassword; $script:suffix = (Get-Date).ToUniversalTime().ToString('HHmmssfff'); $script:reservationId = $null; $script:orderId = $null; $script:memberId = $null
$username = "ktvgate$script:suffix"; $password = "Kt!$script:suffix-gate"
$registration = Json 'POST' '/api/v1/auth/register' @{username=$username;password=$password;nickname='KTV Gate';email="$username@example.invalid"} @{}
$script:imToken = $registration.access_token; if (!$script:imToken) { throw 'Disposable IM registration failed.' }
try {
  $grant = Code "$H5Base/a380/"
  $callback = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$Gateway/api/v1/identity/oauth/im/callback" -ContentType 'application/json' -Body (@{code=$grant.code;code_verifier=$grant.verifier;redirect_uri="$H5Base/a380/";app_id='saas-a380-c';appId='saas-a380-c';state=$grant.state;nonce=$grant.nonce}|ConvertTo-Json)
  $cCookie = @($callback.BaseResponse.Headers.GetValues('Set-Cookie') | ForEach-Object { ($_ -split ';')[0] } |
    Where-Object { $_ -match '^(__Host-)?saas_[bc]_session=' } | Select-Object -First 1)[0]
  if ($cCookie -notmatch '^(__Host-)?saas_[bc]_session=') { throw "OAuth callback did not return a SaaS session cookie. status=$($callback.StatusCode) body=$($callback.Content)" }
  $cCsrf = (Json 'GET' '/api/v1/auth/csrf' $null @{Cookie=$cCookie}).csrfToken
  $contexts = Json 'GET' '/api/v1/auth/contexts' $null @{Cookie=$cCookie}; $contextId = $contexts.items[0].contextId; if (!$contextId) { throw "Consumer context was not issued. response=$($contexts | ConvertTo-Json -Compress -Depth 6)" }
  Json 'POST' '/api/v1/auth/context/select' @{contextId=$contextId} @{Cookie=$cCookie;'X-CSRF-Token'=$cCsrf}|Out-Null
  $member = Json 'GET' '/api/v1/business/members/me' $null @{Cookie=$cCookie}
  $script:memberId = $member.memberId; if (!$script:memberId) { throw 'C member initialization failed.' }
  $rooms = Json 'GET' '/api/v1/business/resources?resourceType=KTV_ROOM' $null @{Cookie=$cCookie}; $room = @($rooms)[0]; if (!$room.id) { throw 'No real KTV room returned.' }
  $start = [DateTimeOffset]::Now.AddHours(2); $created = Json 'POST' '/api/v1/business/reservations' @{businessType='KTV';resourceId=$room.id;startAt=$start.ToString('o');endAt=$start.AddHours(2).ToString('o');partySize=2;contact='KTV Gate 13800138000'} @{Cookie=$cCookie;'X-CSRF-Token'=$cCsrf;'Idempotency-Key'="ktv-$script:suffix"}
  $script:reservationId = $created.id; if (!$script:reservationId -or $created.status -ne 'PENDING') { throw 'C reservation creation failed.' }
  $adminLogin = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$Gateway/api/v1/admin/auth/login" -ContentType 'application/json' -Body (@{username='a380-admin';password='e8280ac0d25d4bc0a1e1';ttlHours=1}|ConvertTo-Json)
  $adminCookie = ($adminLogin.Headers['Set-Cookie'] -split ';')[0]; $adminCsrf = (Json 'GET' '/api/v1/admin/auth/csrf' $null @{Cookie=$adminCookie}).csrfToken; $adminHeaders=@{Cookie=$adminCookie;'X-CSRF-Token'=$adminCsrf}
  $adminContext = Json 'POST' '/api/v1/admin/context/select' @{contextId='100::'} $adminHeaders
  if ($adminContext.permissions -notcontains 'order.add_item') { throw 'A380 context did not receive order.add_item after IAM migration.' }
  $reservation = @((Json 'GET' '/api/v1/admin/reservations' $null $adminHeaders) | Where-Object {$_.id -eq $script:reservationId})[0]; if (!$reservation) { throw 'Admin list did not return C reservation.' }
  $confirmed = Json 'POST' "/api/v1/admin/reservations/$script:reservationId/confirm" @{expectedVersion=$reservation.version} $adminHeaders; if ($confirmed.status -ne 'CONFIRMED') { throw 'Confirm failed.' }
  $arrived = Json 'POST' "/api/v1/admin/reservations/$script:reservationId/arrival" @{} $adminHeaders; if ($arrived.status -ne 'ARRIVED') { throw 'Arrival failed.' }
  $order = Json 'POST' "/api/v1/admin/reservations/$script:reservationId/open-table" @{} $adminHeaders; $script:orderId=$order.id; if (!$script:orderId -or $order.status -ne 'SERVING') { throw 'Open table failed.' }
  $catalog = @((Json 'GET' "/api/v1/business/catalog/items?storeId=$($order.storeId)" $null $adminHeaders) | Where-Object { $_.status -eq 'ACTIVE' })[0]
  if (!$catalog.id) { throw 'No active catalog item is available for the payment gate.' }
  $item = Json 'POST' "/api/v1/business/orders/$script:orderId/items" @{catalogItemId=$catalog.id;quantity=1;source='MERCHANT'} $adminHeaders
  if (!$item.id -or $item.status -ne 'ACTIVE') { throw 'Admin item addition failed.' }
  $session = Json 'GET' "/api/v1/business/orders/$script:orderId/session" $null $adminHeaders; $closed = Json 'POST' "/api/v1/business/ktv/sessions/$($session.id)/close" @{} $adminHeaders; if ($closed.status -ne 'CLOSED') { throw 'Close table failed.' }
  $settlementOrder = @((Json 'GET' '/api/v1/business/orders' $null $adminHeaders) | Where-Object {$_.id -eq $script:orderId})[0]
  if (!$settlementOrder -or $settlementOrder.status -ne 'WAITING_SETTLEMENT') { throw 'Close table did not produce a settleable order.' }
  $settled = Json 'POST' "/api/v1/business/orders/$script:orderId/settle" @{expectedVersion=$settlementOrder.version} $adminHeaders
  if ($settled.status -ne 'WAITING_PAYMENT') { throw 'Admin settlement did not produce a waiting-payment order.' }
  $bill = Json 'GET' "/api/v1/business/orders/$script:orderId/bill" $null @{Cookie=$cCookie}; if (!$bill -or $bill.status -ne 'WAITING_PAYMENT') { throw 'C bill is unavailable after settlement.' }
  $payable = [int64]([decimal]$bill.totalAmount - [decimal]$bill.paidAmount)
  if ($payable -le 0) { throw 'C bill has no payable amount for the payment gate.' }
  $payment = Json 'POST' "/api/v1/business/orders/$script:orderId/collect" @{currencyCode='CNY';payable=$payable;payments=@(@{method='CASH';amount=$payable})} @{Cookie=$cCookie;'X-CSRF-Token'=$cCsrf;'Idempotency-Key'="ktv-pay-$script:suffix"}
  if ($payment.remainingAmount -ne 0) { throw 'C payment did not settle the full bill.' }
  $completedBill = Json 'GET' "/api/v1/business/orders/$script:orderId/bill" $null @{Cookie=$cCookie}
  if (!$completedBill -or $completedBill.status -ne 'COMPLETED' -or [decimal]$completedBill.paidAmount -ne [decimal]$completedBill.totalAmount) { throw 'C receipt did not reach COMPLETED.' }
  $paymentRows = SqlValue "SELECT CONCAT((SELECT COUNT(*) FROM pay_collect WHERE order_id=$script:orderId AND state='CONFIRMED'), '|',(SELECT COUNT(*) FROM pay_intent WHERE order_id=$script:orderId AND status='SUCCEEDED'), '|',(SELECT COUNT(*) FROM pay_transaction t JOIN pay_intent i ON i.id=t.payment_intent_id WHERE i.order_id=$script:orderId AND t.status='SUCCEEDED'), '|',(SELECT COALESCE(SUM(i.amount),0) FROM pay_intent i WHERE i.order_id=$script:orderId AND i.status='SUCCEEDED'), '|',(SELECT COALESCE(SUM(t.amount),0) FROM pay_transaction t JOIN pay_intent i ON i.id=t.payment_intent_id WHERE i.order_id=$script:orderId AND t.status='SUCCEEDED'));"
  $parts = $paymentRows -split '\|'
  if ($parts.Count -ne 5 -or [int]$parts[0] -ne 1 -or [int]$parts[1] -ne 1 -or [int]$parts[2] -ne 1 -or [decimal]$parts[3] -ne [decimal]$completedBill.totalAmount -or [decimal]$parts[4] -ne [decimal]$completedBill.totalAmount) { throw "Payment ledger assertion failed: $paymentRows" }
  Write-Output "PASS: payment ledger confirmed (collect=$($parts[0]), intent=$($parts[1]), transaction=$($parts[2]), amount=$($parts[3]))"
  Write-Output "PASS: C OAuth reservation -> admin fulfill/settle -> C payment -> completed receipt. reservation=$script:reservationId order=$script:orderId"
} finally {
  if ($script:orderId) { Sql "DELETE FROM pay_transaction WHERE payment_intent_id IN (SELECT id FROM pay_intent WHERE order_id=$script:orderId); DELETE FROM pay_intent WHERE order_id=$script:orderId; DELETE FROM pay_collect WHERE order_id=$script:orderId; DELETE FROM ord_ktv_server_session WHERE order_id=$script:orderId; DELETE FROM ord_ktv_session WHERE order_id=$script:orderId; DELETE FROM ord_order_item WHERE order_id=$script:orderId; DELETE FROM ord_order WHERE id=$script:orderId;" }
  if ($script:reservationId) { Sql "DELETE FROM ord_reservation WHERE id=$script:reservationId;" }
  if ($script:memberId) { Sql "DELETE FROM cst_wallet_ledger WHERE wallet_account_id IN (SELECT id FROM cst_wallet_account WHERE customer_id=$script:memberId); DELETE FROM cst_point_ledger WHERE account_id IN (SELECT id FROM cst_point_account WHERE customer_id=$script:memberId); DELETE FROM cst_wallet_account WHERE customer_id=$script:memberId; DELETE FROM cst_point_account WHERE customer_id=$script:memberId; DELETE FROM cst_member WHERE id=$script:memberId;" }
  Remove-SaasSession $cCookie
  try { Invoke-WebRequest -UseBasicParsing -Method Delete -Uri "$Gateway/api/v1/users/me" -ContentType 'application/json' -Headers @{Authorization="Bearer $script:imToken"} -Body (@{password=$password}|ConvertTo-Json)|Out-Null } catch { Write-Warning 'Disposable account cleanup failed.' }
}
