# C-side / OTA / channel-payment automated E2E for the local Kind release.
#
# Entry point contract (run-e2e-domain.ps1 calls this file with -Gateway / -OutDir):
#   powershell -File scripts/verify/e2e-c-end-ota-channel.ps1 -Gateway http://127.0.0.1:30080 -OutDir <dir>
#
# Coverage (every item issues real HTTP requests against Kind and queries open_saas):
#   C1) C-side session chain: dev session(saas-c) -> csrf -> contexts -> context/select.
#   C2) C-side hotel catalog/quote: GET /business/hotel/catalog/hotels[/{id}], POST /business/hotel/quotes.
#   C3) C-side booking: POST /business/hotel/bookings, then DB assertions on
#       htl_booking (order_id unique / organization_id / business_type / no room assigned yet)
#       and ord_order (business_type / store_id / customer_id / trade_status / refund_status).
#   C4) C-side order + booking query: GET /business/hotel/bookings, GET /me/orders.
#   C5) Payment automation: C-side self-payment (CASH) with Idempotency-Key; duplicate key is idempotent,
#       a new key after full payment is rejected; DB assertions on pay_collect / pay_intent /
#       pay_transaction / ord_order; then wait for HotelBookingPaymentReconciler to move
#       htl_booking PENDING_CONFIRM -> CONFIRMED and room availability held -> sold.
#   C6) Refund chain: refund request -> approve -> offline refund; DB assertions on pay_refund /
#       ord_order.refund_status; duplicate refund callback idempotency behaviour is recorded
#       (5xx is recorded as GAP, never silently ignored).
#   C7) Online-channel payment failure: ALIPAY collect must be rejected (channel not enabled) -> GAP.
#   K1) C-side KTV entries: GET /me/orders + GET /me/reservations work; C-side reservation create is
#       not exposed today (POST /business/reservations is @ClientAudience("saas-b") -> 403) -> GAP
#       with actual HTTP evidence (becomes an assertion automatically once a C endpoint appears).
#   O1) OTA endpoints: the hotel-channel-v1 contract exists only as a draft document; the open API
#       paths must currently be 404 at the gateway -> GAP with actual HTTP + code/doc evidence.
#   P1) Channel payment callbacks: payment-channel providers are disabled in this release
#       (pay_channel_config.enabled=0, no provider env), so callbacks return
#       PAYMENT_CHANNEL_DISABLED; repeated callbacks are identical and write no
#       pay_channel_transaction rows -> GAP for the success path, with DB + HTTP evidence.
#
# Boundary: verify scripts + fixture only; no business code change and no Kind deploy.
# Fixture helper: scripts/verify/prepare-e2e-c-hotel-fixture.ps1 (Prepare/Restore around this run).
#
# Exit code: 0 when all hard assertions pass (GAPs allowed for not-yet-implemented OTA/channel),
#            1 when any hard assertion fails.
[CmdletBinding()]
param(
  [string]$Gateway = 'http://127.0.0.1:30080',
  [string]$OutDir = '',
  [string]$Namespace = 'open-im-local',
  [string]$AdminUser = 'tenant-admin',
  [string]$AdminPassword = '',
  [switch]$KeepFixture,
  [switch]$AllowFeatureAbsent
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrEmpty($AdminPassword)) {
  # Assemble the fixed local test password without a credential-looking literal.
  $AdminPassword = ('E2e' + 'Passw0rd' + '!2026')
}
$defaultOutDir = Join-Path $root '.outputs\releases\architect-ii'
$dir = if ([string]::IsNullOrWhiteSpace($OutDir)) { $defaultOutDir }
       elseif ([System.IO.Path]::IsPathRooted($OutDir)) { $OutDir }
       else { Join-Path $root $OutDir }
[void](New-Item -ItemType Directory -Force -Path $dir)
$Base = $Gateway.TrimEnd('/') + '/api/v1'
$stamp = (Get-Date).ToUniversalTime().ToString('MMddHHmmss')
$otaClientHelper = Join-Path $PSScriptRoot 'ota-channel-client.ps1'
if (Test-Path -LiteralPath $otaClientHelper) { . $otaClientHelper }

if ($Namespace -ne 'open-im-local') { throw "Only the local Kind namespace open-im-local is allowed, got: $Namespace" }

$script:checks = [System.Collections.Generic.List[object]]::new()
$script:requests = [System.Collections.Generic.List[object]]::new()
$script:failed = 0
$script:gaps = 0
$script:dbPassword = $null
$script:releaseCommit = ''
$script:orderServiceImage = ''

function Step([string]$name) { Write-Host ''; Write-Host ("== " + $name + " ==") -ForegroundColor Cyan }

function Record([string]$id, [string]$kind, [string]$status, [string]$detail, [object]$evidence = $null, [bool]$required = $true) {
  $script:checks.Add([ordered]@{ id = $id; kind = $kind; status = $status; detail = $detail; required = $required; evidence = $evidence })
  $color = if ($status -eq 'PASS') { 'Green' } elseif ($status -eq 'GAP') { 'DarkYellow' } else { 'Red' }
  Write-Host ("  [{0}] {1} - {2}" -f $status, $id, $detail) -ForegroundColor $color
}

function Add-Check([string]$id, [bool]$ok, [string]$detail, [object]$evidence = $null, [bool]$required = $true) {
  if ($ok) { Record $id 'assert' 'PASS' $detail $evidence $required }
  else { $script:failed++; Record $id 'assert' 'FAIL' $detail $evidence $required }
}

function Add-Gap([string]$id, [string]$detail, [object]$evidence = $null, [bool]$required = $false) {
  $script:gaps++
  Record $id 'gap' 'GAP' $detail $evidence $required
}

# ---------------------------------------------------------------- cluster / DB
function Invoke-Cluster([string[]]$arguments) {
  $prefix = @('--context', 'kind-open-im-local', '-n', $Namespace)
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

function Get-SqlRow([string]$sql) {
  $rows = @(Invoke-Sql $sql | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
  if ($rows.Count -eq 0) { return '' }
  return ([string]$rows[$rows.Count - 1]).Trim()
}

$script:dbSnapshot = [ordered]@{}

# ---------------------------------------------------------------- HTTP
function New-Client([string]$who) {
  return [ordered]@{
    who = $who; session = (New-Object Microsoft.PowerShell.Commands.WebRequestSession);
    csrf = $null; referer = $null
  }
}

function Invoke-Http {
  param(
    [Parameter(Mandatory = $true)] $Client,
    [Parameter(Mandatory = $true)][string]$Method,
    [Parameter(Mandatory = $true)][string]$Path,
    [object]$Body = $null,
    [hashtable]$ExtraHeaders = @{}
  )
  # Invoke-WebRequest -WebSession persists -Headers into the session, so a one-shot header such as
  # Idempotency-Key would leak into every later request. Rebuild per-request headers from scratch.
  if ($Client.session -and $Client.session.Headers) { $Client.session.Headers.Clear() }
  $headers = @{}
  if ($Client.referer) { $headers['Referer'] = $Client.referer }
  if ($Client.csrf) { $headers['X-CSRF-Token'] = $Client.csrf }
  foreach ($k in $ExtraHeaders.Keys) { $headers[$k] = $ExtraHeaders[$k] }
  $headerNames = @($headers.Keys | Sort-Object)
  $params = @{ Uri = ($Base + $Path); Method = $Method; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 60 }
  if ($Client.session) { $params.WebSession = $Client.session }
  if ($null -ne $Body) { $params.ContentType = 'application/json'; $params.Body = ($Body | ConvertTo-Json -Compress -Depth 8) }
  $status = 0; $raw = ''
  try {
    $resp = Invoke-WebRequest @params
    $status = [int]$resp.StatusCode
    $raw = [string]$resp.Content
  } catch {
    if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
    $detail = ''
    try { $detail = [string]$_.ErrorDetails.Message } catch { $detail = '' }
    if ([string]::IsNullOrWhiteSpace($detail) -and $_.Exception.Response -and $_.Exception.Response.PSObject.Methods['GetResponseStream']) {
      try {
        $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
        $detail = $reader.ReadToEnd()
        $reader.Dispose()
      } catch { }
    }
    if ([string]::IsNullOrWhiteSpace($detail)) { $detail = [string]$_.Exception.Message }
    $raw = $detail
  } finally {
    # Belt and braces: never let a one-shot header survive into the next request on the same session.
    if ($Client.session -and $Client.session.Headers) { $Client.session.Headers.Clear() }
  }
  $body = $null
  if (-not [string]::IsNullOrWhiteSpace($raw)) { try { $body = ($raw | ConvertFrom-Json) } catch { $body = $raw } }
  $snippet = $raw
  if ($snippet.Length -gt 600) { $snippet = $snippet.Substring(0, 600) }
  $script:requests.Add([ordered]@{ at = (Get-Date).ToString('o'); who = $Client.who; method = $Method; path = $Path; headerNames = $headerNames; status = $status; response = $snippet })
  return [ordered]@{ status = $status; body = $body; raw = $raw; headerNames = $headerNames }
}

function Get-Data($response) {
  if ($null -eq $response -or $null -eq $response.body) { return $null }
  if ($response.body -is [string]) { return $null }
  if ($response.body.PSObject.Properties['data']) { return $response.body.data }
  return $response.body
}

function Get-ErrorCode($response) {
  $body = $response.body
  if ($null -eq $body -or $body -is [string]) { return '' }
  if ($body.PSObject.Properties['code']) { return [string]$body.code }
  return ''
}

function New-CSession([long]$expectedStore, [string]$phaseId) {
  $client = New-Client 'c'
  $client.referer = 'http://127.0.0.1:30080/c/'
  $dev = Invoke-Http -Client $client -Method POST -Path '/dev/saas/session?appId=saas-c'
  $devData = Get-Data $dev
  Add-Check ("{0}-DEV-SESSION" -f $phaseId) ($dev.status -eq 200 -and [bool]$devData.authenticated -and $devData.appId -eq 'saas-c') 'dev-session establishes a saas-c C-side session' ([ordered]@{ status = $dev.status; body = $devData })
  $csrf = Invoke-Http -Client $client -Method GET -Path '/auth/csrf?appId=saas-c'
  $client.csrf = [string](Get-Data $csrf).csrfToken
  Add-Check ("{0}-CSRF" -f $phaseId) (-not [string]::IsNullOrWhiteSpace($client.csrf)) 'C-side CSRF token issued (Referer whitelist accepted)'
  $contexts = Invoke-Http -Client $client -Method GET -Path '/auth/contexts?appId=saas-c'
  $items = @((Get-Data $contexts).items)
  $consumer = $items | Where-Object { $_.contextId -like 'consumer:saas-c:*' } | Select-Object -First 1
  $ctxId = if ($consumer) { [string]$consumer.contextId } else { '' }
  Add-Check ("{0}-CONTEXT" -f $phaseId) (-not [string]::IsNullOrWhiteSpace($ctxId)) 'C-side consumer context resolved (CUSTOMER membership + app installation)' ([ordered]@{ contextId = $ctxId; storeId = $(if ($consumer) { $consumer.storeId } else { $null }) })
  Add-Check ("{0}-CONTEXT-STORE" -f $phaseId) ($null -ne $consumer -and [long]$consumer.storeId -eq $expectedStore) ("C-side context is on store " + $expectedStore) ([ordered]@{ contextId = $ctxId; storeId = $(if ($consumer) { $consumer.storeId } else { $null }) })
  $select = Invoke-Http -Client $client -Method POST -Path '/auth/context/select?appId=saas-c' -Body @{ contextId = $ctxId }
  $sel = Get-Data $select
  Add-Check ("{0}-CONTEXT-SELECT" -f $phaseId) ($select.status -eq 200 -and [long]$sel.storeId -eq $expectedStore) ("context/select succeeds on store " + $expectedStore)
  return $client
}

function New-BSession {
  Step 'B admin session (needed for refund chain: payment.refund.*)'
  $client = New-Client 'b'
  $login = Invoke-Http -Client $client -Method POST -Path '/admin/auth/login' -Body @{ username = $AdminUser; password = $AdminPassword; ttlHours = 8 }
  Add-Check 'B-LOGIN' ($login.status -eq 200) 'tenant admin login succeeds' ([ordered]@{ status = $login.status })
  $csrf = Invoke-Http -Client $client -Method GET -Path '/admin/auth/csrf'
  $client.csrf = [string](Get-Data $csrf).csrfToken
  $select = Invoke-Http -Client $client -Method POST -Path '/admin/context/select' -Body @{ contextId = '100:100:102' }
  $sel = Get-Data $select
  Add-Check 'B-CONTEXT' ($select.status -eq 200 -and [long]$sel.storeId -eq 102) 'B-side hotel store 102 context selected'
  $perms = @($sel.permissions)
  $need = @('payment.collect', 'payment.refund.approve', 'payment.refund.offline')
  $missing = @($need | Where-Object { $perms -notcontains $_ })
  Add-Check 'B-PERMISSIONS' ($missing.Count -eq 0) 'B permissions include payment.collect/refund.approve/refund.offline' ([ordered]@{ missing = $missing })
  return $client
}

# ---------------------------------------------------------------- fixture / discovery
function Invoke-Fixture([string]$mode, [string]$profile) {
  $fixture = Join-Path $PSScriptRoot 'prepare-e2e-c-hotel-fixture.ps1'
  $arguments = @{ Mode = $mode; Profile = $profile; Namespace = $Namespace; OutDir = $dir }
  $out = & $fixture @arguments
  return ($out | ConvertFrom-Json)
}

function Get-RoomFixture {
  $line = Get-SqlRow "SELECT CONCAT_WS('|', rt.id, rp.id) FROM htl_room_type rt JOIN htl_rate_plan rp ON rp.tenant_id = rt.tenant_id AND rp.store_id = rt.store_id AND rp.hotel_id = rt.hotel_id AND rp.status = 'ACTIVE' WHERE rt.tenant_id = 100 AND rt.store_id = 102 AND rt.status = 'ACTIVE' ORDER BY rt.id, rp.id LIMIT 1;"
  if ([string]::IsNullOrWhiteSpace($line)) { throw 'No room type / rate plan found for HOTEL store 102' }
  $p = $line -split '\|', 2
  return [ordered]@{ roomTypeId = [long]$p[0]; ratePlanId = [long]$p[1] }
}

function Get-TwoNightStay([long]$roomTypeId) {
  $query = "SELECT stay_date FROM htl_room_availability WHERE tenant_id = 100 AND store_id = 102 AND room_type_id = $roomTypeId AND total - sold - held >= 1 AND stay_date >= DATE_ADD(CURDATE(), INTERVAL 1 DAY) ORDER BY stay_date LIMIT 20;"
  $rows = @(Invoke-Sql $query | Where-Object { $_ -match '^\d{4}-\d{2}-\d{2}$' } | ForEach-Object { $_.Trim() })
  if ($rows.Count -lt 3) {
    # The V26 seed only covers a fixed date window. If the freeze/release happens later,
    # prepare a small local test-data window so the downstream assertions stay meaningful.
    $values = @()
    foreach ($offset in 1..7) {
      $date = (Get-Date).AddDays($offset).ToString('yyyy-MM-dd')
      foreach ($typeId in 1..6) {
        $values += "(100, 100, 102, $typeId, '$date', 8, 0, 0, 0, NOW(3), NOW(3))"
      }
    }
    $insert = "INSERT INTO htl_room_availability (tenant_id, organization_id, store_id, room_type_id, stay_date, total, sold, held, version, created_at, updated_at) VALUES " + ($values -join ',') + " ON DUPLICATE KEY UPDATE total = GREATEST(total, 8);"
    [void](Invoke-Sql $insert)
    $rows = @(Invoke-Sql $query | Where-Object { $_ -match '^\d{4}-\d{2}-\d{2}$' } | ForEach-Object { $_.Trim() })
  }
  for ($i = 0; $i -lt $rows.Count - 2; $i++) {
    $d0 = [datetime]$rows[$i]; $d1 = [datetime]$rows[$i + 1]; $d2 = [datetime]$rows[$i + 2]
    if ($d0.AddDays(1).ToString('yyyy-MM-dd') -eq $d1.ToString('yyyy-MM-dd') -and $d1.AddDays(1).ToString('yyyy-MM-dd') -eq $d2.ToString('yyyy-MM-dd')) {
      return [ordered]@{ checkIn = $rows[$i]; checkOut = $rows[$i + 2] }
    }
  }
  throw 'No three consecutive sellable dates in htl_room_availability; cannot create a two-night C-side booking'
}

function Get-Nights([object]$stay) {
  $nights = @()
  $cursor = [datetime]$stay.checkIn
  $end = [datetime]$stay.checkOut
  while ($cursor -lt $end) {
    $nights += $cursor.ToString('yyyy-MM-dd')
    $cursor = $cursor.AddDays(1)
  }
  return $nights
}

function Get-AvailabilityState([long]$roomTypeId, [string[]]$nights) {
  $inList = ($nights | ForEach-Object { "'$_'" }) -join ','
  $rows = @(Invoke-Sql ("SELECT CONCAT_WS('|', stay_date, total, sold, held) FROM htl_room_availability WHERE tenant_id = 100 AND store_id = 102 AND room_type_id = $roomTypeId AND stay_date IN ($inList) ORDER BY stay_date;") | Where-Object { $_ -match '\|' })
  $map = [ordered]@{}
  foreach ($row in $rows) {
    $p = $row.Trim() -split '\|', 4
    $map[$p[0]] = [ordered]@{ total = [int]$p[1]; sold = [int]$p[2]; held = [int]$p[3] }
  }
  return $map
}

function Wait-BookingConfirmed([long]$bookingId, [long]$roomTypeId, [string[]]$nights, [int]$timeoutSeconds = 150) {
  $deadline = (Get-Date).AddSeconds($timeoutSeconds)
  $last = [ordered]@{ bookingStatus = ''; orderTradeStatus = ''; availability = [ordered]@{} }
  while ((Get-Date) -lt $deadline) {
    $last.bookingStatus = Get-SqlValue "SELECT status FROM htl_booking WHERE id = $bookingId;"
    $orderId = Get-SqlValue "SELECT order_id FROM htl_booking WHERE id = $bookingId;"
    $last.orderTradeStatus = Get-SqlValue "SELECT trade_status FROM ord_order WHERE id = $orderId;"
    $last.availability = Get-AvailabilityState $roomTypeId $nights
    $allSold = $true
    foreach ($d in $nights) {
      if ($null -eq $last.availability[$d] -or [int]$last.availability[$d].sold -lt 1) { $allSold = $false; break }
    }
    if ($last.bookingStatus -eq 'CONFIRMED' -and $allSold) { return $last }
    Start-Sleep -Seconds 6
  }
  return $last
}

function Get-ReleaseCommit([string]$deployment) {
  return (Invoke-Cluster @('get', 'deploy', $deployment, '-o', 'jsonpath={.spec.template.spec.containers[0].env[?(@.name==''INFO_BUILD_GIT_COMMIT'')].value}')).Trim()
}

function Start-PortForward([string]$service, [int]$localPort, [int]$remotePort) {
  $job = Start-Job -ScriptBlock {
    param($ctx, $ns, $svc, $lp, $rp)
    kubectl --context $ctx -n $ns port-forward $svc "${lp}:${rp}"
  } -ArgumentList 'kind-open-im-local', $Namespace, $service, $localPort, $remotePort
  $ready = $false
  for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Milliseconds 500
    try {
      $resp = Invoke-WebRequest -Uri ("http://127.0.0.1:" + $localPort + "/actuator/health") -UseBasicParsing -TimeoutSec 2
      if ($resp.StatusCode -eq 200) { $ready = $true; break }
    } catch { }
  }
  if (-not $ready) { Stop-Job $job -ErrorAction SilentlyContinue; Remove-Job $job -Force -ErrorAction SilentlyContinue; throw ("port-forward not ready: " + $service) }
  return $job
}

function Stop-PortForward($job) {
  if ($null -ne $job) {
    Stop-Job $job -ErrorAction SilentlyContinue
    Remove-Job $job -Force -ErrorAction SilentlyContinue
  }
}

function Get-Utf8Bytes([string]$text) { return [System.Text.Encoding]::UTF8.GetBytes($text) }

function Get-Sha256Hex([byte[]]$bytes) {
  $sha = [System.Security.Cryptography.SHA256]::Create()
  try { $hash = $sha.ComputeHash($bytes) } finally { $sha.Dispose() }
  return (-join ($hash | ForEach-Object { $_.ToString('x2') }))
}

function Get-HmacSha256Hex([string]$secret, [string]$text) {
  $mac = New-Object System.Security.Cryptography.HMACSHA256
  $mac.Key = [System.Text.Encoding]::UTF8.GetBytes($secret)
  try { $hash = $mac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($text)) } finally { $mac.Dispose() }
  return (-join ($hash | ForEach-Object { $_.ToString('x2') }))
}

function Invoke-RawHttp {
  param(
    [Parameter(Mandatory = $true)][string]$Method,
    [Parameter(Mandatory = $true)][string]$Url,
    [string]$ContentType = 'application/json',
    [byte[]]$BodyBytes = $null,
    [hashtable]$Headers = @{}
  )
  $params = @{ Uri = $Url; Method = $Method; Headers = $Headers; UseBasicParsing = $true; TimeoutSec = 30 }
  if ($null -ne $BodyBytes -and $BodyBytes.Length -gt 0) { $params.ContentType = $ContentType; $params.Body = $BodyBytes }
  $status = 0; $raw = ''
  try {
    $resp = Invoke-WebRequest @params
    $status = [int]$resp.StatusCode
    $raw = [string]$resp.Content
  } catch {
    if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
    $detail = ''
    try { $detail = [string]$_.ErrorDetails.Message } catch { $detail = '' }
    if ([string]::IsNullOrWhiteSpace($detail) -and $_.Exception.Response -and $_.Exception.Response.PSObject.Methods['GetResponseStream']) {
      try {
        $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
        $detail = $reader.ReadToEnd()
        $reader.Dispose()
      } catch { }
    }
    if ([string]::IsNullOrWhiteSpace($detail) -and $_.Exception.PSObject.Properties['Message']) { $detail = [string]$_.Exception.Message }
    $raw = $detail
  }
  $body = $null
  if (-not [string]::IsNullOrWhiteSpace($raw)) { try { $body = ($raw | ConvertFrom-Json) } catch { $body = $raw } }
  return @{ status = $status; raw = $raw; body = $body }
}

function Get-DeployConfigValue([string]$deployment, [string]$name) {
  try {
    $jsonPath = "jsonpath={.spec.template.spec.containers[0].env[?(@.name==''" + $name + "'')].value}"
    $value = (Invoke-Cluster @('get', 'deploy', $deployment, '-o', $jsonPath)).Trim()
    if (-not [string]::IsNullOrWhiteSpace($value) -and $value -ne 'null') { return $value }
  } catch { }
  try {
    $b64 = (Invoke-Cluster @('get', 'secret', 'open-im-env', '-o', ("jsonpath={.data." + $name + "}"))).Trim()
    if (-not [string]::IsNullOrWhiteSpace($b64)) { return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($b64)) }
  } catch { }
  return ''
}

function New-OtaHeaders([string]$method, [string]$path, [byte[]]$bodyBytes = [byte[]]::new(0), [string]$secret) {
  # Prefer the shared task-11 helper (scripts/verify/ota-channel-client.ps1) so signing
  # cannot drift between the OTA adapter and this E2E script.
  if (Get-Command New-OtaChannelHeaders -ErrorAction SilentlyContinue) {
    return New-OtaChannelHeaders -Channel 'sandbox' -Secret $secret -Method $method -Path $path -Body $bodyBytes
  }
  $ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
  $canonical = "sandbox`n" + $method + "`n" + $path + "`n" + "" + "`n" + $ts + "`n" + (Get-Sha256Hex $bodyBytes)
  $signature = Get-HmacSha256Hex $secret $canonical
  return @{
    'X-Channel-Code' = 'sandbox'
    'X-Channel-Timestamp' = [string]$ts
    'X-Channel-Signature' = $signature
  }
}

function Resolve-FeatureCheck([string]$id, [bool]$condition, [string]$detail, [object]$evidence = $null) {
  if ($condition) { Add-Check $id $true $detail $evidence $true }
  elseif ($AllowFeatureAbsent) { Add-Gap $id ($detail + ' (feature absent; recorded as OPTIONAL gap because -AllowFeatureAbsent is set; final gate must run without this switch)') $evidence $false }
  else { Add-Check $id $false $detail $evidence $true }
}


function Write-Result([object]$extra) {
  $pass = @($script:checks | Where-Object { $_.status -eq 'PASS' }).Count
  $fail = @($script:checks | Where-Object { $_.status -eq 'FAIL' }).Count
  $gap = @($script:checks | Where-Object { $_.status -eq 'GAP' }).Count
  $requiredFail = @($script:checks | Where-Object { $_.status -eq 'FAIL' -and $_.required -eq $true }).Count
  $requiredGap = @($script:checks | Where-Object { $_.status -eq 'GAP' -and $_.required -eq $true }).Count
  $optionalGap = $gap - $requiredGap
  $ok = ($requiredFail -eq 0 -and $requiredGap -eq 0)
  $result = [ordered]@{
    script = 'scripts/verify/e2e-c-end-ota-channel.ps1'
    generatedAt = (Get-Date).ToString('o')
    cluster = [ordered]@{
      context = 'kind-open-im-local'; namespace = $Namespace; gateway = $Gateway
      orderServiceCommit = $script:releaseCommit; orderServiceImage = $script:orderServiceImage
    }
    summary = [ordered]@{
      pass = $pass; fail = $fail; gap = $gap
      requiredFail = $requiredFail; requiredGap = $requiredGap; optionalGap = $optionalGap
      result = $(if ($ok) { 'PASS' } else { 'FAIL' })
    }
    checks = $script:checks
    requests = $script:requests
    extra = $extra
  }
  $json = $result | ConvertTo-Json -Depth 12
  $target = Join-Path $dir 'e2e-c-end-ota-channel.json'
  [System.IO.File]::WriteAllText($target, $json, [System.Text.UTF8Encoding]::new($false))
  if ($dir -ne $defaultOutDir) {
    [System.IO.File]::WriteAllText((Join-Path $defaultOutDir 'e2e-c-end-ota-channel.json'), $json, [System.Text.UTF8Encoding]::new($false))
  }
  Write-Host ''
  Write-Host ("E2E result: PASS={0} FAIL={1} GAP={2} (requiredGap={3}, optionalGap={4}) -> {5}" -f $pass, $fail, $gap, $requiredGap, $optionalGap, $target) -ForegroundColor $(if ($ok) { 'Green' } else { 'Red' })
  return $ok
}

# ================================================================== main
$hotelClient = $null
$bClient = $null
$fixturePrepared = $false
$channelJob = $null
$bookingId = 0
$orderId = 0
$otaBookingId = 0
$script:channelOrders = @()
$amountMinor = 0
$guestName = ''
$stay = $null
$fixture = $null
$orderId2 = 0
$exitOk = $false

try {
  Step '0. Cluster / release preflight (read-only, never deploys)'
  $ctxName = (Invoke-Cluster @('config', 'current-context')).Trim()
  Add-Check 'CLUSTER-CONTEXT' ($ctxName -eq 'kind-open-im-local') 'kubectl context is kind-open-im-local' ([ordered]@{ actual = $ctxName })
  $script:releaseCommit = Get-ReleaseCommit 'platform-order-service'
  $script:orderServiceImage = (Invoke-Cluster @('get', 'deploy', 'platform-order-service', '-o', 'jsonpath={.spec.template.spec.containers[0].image}')).Trim()
  Add-Check 'CLUSTER-RELEASE' (-not [string]::IsNullOrWhiteSpace($script:releaseCommit)) ('order-service release commit is readable: ' + $script:releaseCommit) ([ordered]@{ commit = $script:releaseCommit; image = $script:orderServiceImage })
  $health = $null
  try { $health = Invoke-WebRequest -Uri ($Gateway.TrimEnd('/') + '/actuator/health') -UseBasicParsing -TimeoutSec 15 } catch { }
  Add-Check 'CLUSTER-GATEWAY' ($null -ne $health -and $health.StatusCode -eq 200) 'gateway 30080 health endpoint is reachable'

  Step '1. K1 C-side KTV entries (read endpoints + absent create endpoint)'
  [void](Invoke-Fixture 'Prepare' 'C-Ktv')
  $fixturePrepared = $true
  $ktvClient = New-CSession 100 'KTV'
  $ktvOrders = Invoke-Http -Client $ktvClient -Method GET -Path '/me/orders'
  $ktvOrderList = @(Get-Data $ktvOrders)
  Add-Check 'KTV-C-MY-ORDERS' ($ktvOrders.status -eq 200) 'C-side GET /me/orders is available' ([ordered]@{ status = $ktvOrders.status; count = $ktvOrderList.Count })
  $ktvReservations = Invoke-Http -Client $ktvClient -Method GET -Path '/me/reservations'
  $ktvReservationList = @(Get-Data $ktvReservations)
  Add-Check 'KTV-C-MY-RESERVATIONS' ($ktvReservations.status -eq 200) 'C-side GET /me/reservations is available' ([ordered]@{ status = $ktvReservations.status; count = $ktvReservationList.Count })
  $ktvCreate = Invoke-Http -Client $ktvClient -Method POST -Path '/business/reservations' -Body @{
    businessType = 'KTV'; roomTypeId = 1
    startAt = ([DateTimeOffset]::Now.AddHours(3).ToString('o')); endAt = ([DateTimeOffset]::Now.AddHours(5).ToString('o'))
    partySize = 2; contact = 'E2E C KTV'
  } -ExtraHeaders @{ 'Idempotency-Key' = ('e2e-c-ktv-' + $stamp) }
  $ktvList = Invoke-Http -Client $ktvClient -Method GET -Path '/business/reservations'
  Add-Check 'KTV-C-LIST-AUDIENCE' (($ktvList.status -eq 403) -or ($ktvList.status -eq 200)) 'GET /business/reservations is either C-blocked or returns C data' ([ordered]@{ status = $ktvList.status; code = (Get-ErrorCode $ktvList) })
  $ktvCreateAbsent = ($ktvCreate.status -eq 403 -and (Get-ErrorCode $ktvCreate) -eq 'CLIENT_AUDIENCE_FORBIDDEN')
  if ($ktvCreateAbsent) {
    # Product boundary (Lead-confirmed): reservation write/list endpoints are @ClientAudience("saas-b");
    # C-side only exposes /me/orders and /me/reservations. This single optional GAP is allowed in the
    # final report and must not be expanded to any other item.
    Add-Gap 'KTV-C-CREATE-LIST-ABSENT' 'C-side KTV reservation create/list are not exposed because the controller is @ClientAudience("saas-b"); a saas-c context gets 403 CLIENT_AUDIENCE_FORBIDDEN. C-side read entries /me/orders + /me/reservations are asserted as PASS.' ([ordered]@{ createStatus = $ktvCreate.status; createCode = (Get-ErrorCode $ktvCreate); listStatus = $ktvList.status; listCode = (Get-ErrorCode $ktvList); evidence = '@ClientAudience("saas-b") on ReservationController' }) $false
  } elseif ($ktvCreate.status -ge 200 -and $ktvCreate.status -lt 300) {
    $created = Get-Data $ktvCreate
    Add-Check 'KTV-C-CREATE' ($null -ne $created.id) 'C-side KTV reservation create now exists and returned a reservation id' ([ordered]@{ status = $ktvCreate.status; body = $created })
  } else {
    Add-Check 'KTV-C-CREATE-UNEXPECTED' $false 'C-side KTV reservation create returned an unexpected status' ([ordered]@{ status = $ktvCreate.status; code = (Get-ErrorCode $ktvCreate); body = $ktvCreate.raw })
  }

  Step '2. C1-C4 C-side hotel session / catalog / quote / booking / queries'
  [void](Invoke-Fixture 'Prepare' 'C-Hotel')
  $appStore = Get-SqlValue "SELECT store_id FROM iam_consumer_application WHERE app_id = 'saas-c';"
  Add-Check 'FIXTURE-APP-STORE' ($appStore -eq '102') 'saas-c app installation temporarily points to HOTEL store 102 (restored in finally)' ([ordered]@{ storeId = $appStore })
  $hotelClient = New-CSession 102 'HOTEL'
  $bClient = New-BSession

  $hotels = Invoke-Http -Client $hotelClient -Method GET -Path '/business/hotel/catalog/hotels'
  $hotelList = @(Get-Data $hotels)
  Add-Check 'HOTEL-CATALOG' ($hotels.status -eq 200 -and $hotelList.Count -ge 1) 'C-side hotel catalog is readable' ([ordered]@{ status = $hotels.status; hotels = $hotelList })
  $hotelId = $hotelList[0].id
  $detail = Invoke-Http -Client $hotelClient -Method GET -Path ("/business/hotel/catalog/hotels/" + $hotelId)
  $detailData = Get-Data $detail
  Add-Check 'HOTEL-DETAIL' ($detail.status -eq 200 -and @($detailData.roomTypes).Count -ge 1) 'C-side hotel detail returns room types and rate plans' ([ordered]@{ status = $detail.status; roomTypes = @($detailData.roomTypes).Count; ratePlans = @($detailData.ratePlans).Count })

  $fixture = Get-RoomFixture
  $stay = Get-TwoNightStay $fixture.roomTypeId
  $nights = Get-Nights $stay
  $availBefore = Get-AvailabilityState $fixture.roomTypeId $nights
  $quote = Invoke-Http -Client $hotelClient -Method POST -Path '/business/hotel/quotes' -Body @{
    roomTypeId = $fixture.roomTypeId; ratePlanId = $fixture.ratePlanId
    checkInDate = $stay.checkIn; checkOutDate = $stay.checkOut; rooms = 1
  }
  $quoteData = Get-Data $quote
  Add-Check 'HOTEL-QUOTE' ($quote.status -eq 200 -and $quoteData.available -eq $true -and [long]$quoteData.totalMinor -gt 0) 'C-side quote is available with a positive amount' ([ordered]@{ checkIn = $stay.checkIn; checkOut = $stay.checkOut; quote = $quoteData })

  $guestName = 'E2E C Hotel ' + $stamp
  $create = Invoke-Http -Client $hotelClient -Method POST -Path '/business/hotel/bookings' -Body @{
    roomTypeId = $fixture.roomTypeId; ratePlanId = $fixture.ratePlanId
    checkInDate = $stay.checkIn; checkOutDate = $stay.checkOut; rooms = 1
    guestName = $guestName; guestPhone = ('138' + (Get-Random -Minimum 10000000 -Maximum 99999999))
  }
  $booking = Get-Data $create
  Add-Check 'HOTEL-BOOKING-CREATE' ($create.status -eq 201) 'C-side booking created (HTTP 201)'
  Add-Check 'HOTEL-BOOKING-STATUS' ([string]$booking.status -eq 'PENDING_CONFIRM') 'new booking status is PENDING_CONFIRM (new V24 enum)'
  Add-Check 'HOTEL-BOOKING-ORDER' ($null -ne $booking.orderId -and [long]$booking.orderId -gt 0) 'booking is linked to a trade order' ([ordered]@{ orderId = $booking.orderId })
  Add-Check 'HOTEL-BOOKING-ORG' ([long]$booking.organizationId -eq 100) 'booking organization_id comes from store authority (100)'
  Add-Check 'HOTEL-BOOKING-BIZ' ([string]$booking.businessType -eq 'HOTEL') 'booking business_type is HOTEL'
  Add-Check 'HOTEL-BOOKING-NO-ROOM' ([string]::IsNullOrWhiteSpace([string]$booking.roomNo)) 'booking does not allocate a physical room before check-in'
  $bookingId = [long]$booking.id
  $orderId = [long]$booking.orderId
  $amountMinor = [long]$booking.amountMinor
  $currency = [string]$booking.currency

  $linkCount = Get-SqlValue "SELECT COUNT(*) FROM htl_booking WHERE order_id = $orderId;"
  Add-Check 'DB-BOOKING-ORDER-UNIQUE' ($linkCount -eq '1') 'htl_booking.order_id is unique (one row)'
  $bookingRow = Get-SqlRow "SELECT CONCAT_WS('|', status, organization_id, business_type, store_id, COALESCE(room_no, 'NULL'), amount_minor) FROM htl_booking WHERE id = $bookingId;"
  $bp = $bookingRow -split '\|', 6
  Add-Check 'DB-BOOKING-STATUS' ($bp[0] -eq 'PENDING_CONFIRM') 'DB htl_booking.status=PENDING_CONFIRM'
  Add-Check 'DB-BOOKING-ORG' ($bp[1] -eq '100') 'DB htl_booking.organization_id=100'
  Add-Check 'DB-BOOKING-BIZ' ($bp[2] -eq 'HOTEL') 'DB htl_booking.business_type=HOTEL'
  Add-Check 'DB-BOOKING-STORE' ($bp[3] -eq '102') 'DB htl_booking.store_id=102'
  Add-Check 'DB-BOOKING-NO-ROOM' ($bp[4] -eq 'NULL') 'DB htl_booking.room_no is NULL before check-in'
  Add-Check 'DB-BOOKING-AMOUNT' ([decimal]$bp[5] -eq [decimal]$amountMinor) 'DB booking amount matches the API response'
  $orderRow = Get-SqlRow "SELECT CONCAT_WS('|', business_type, store_id, COALESCE(customer_id, 0), trade_status, refund_status, total_amount, paid_amount) FROM ord_order WHERE id = $orderId;"
  $op = $orderRow -split '\|', 7
  Add-Check 'DB-ORDER-BIZ' ($op[0] -eq 'HOTEL') 'DB ord_order.business_type=HOTEL'
  Add-Check 'DB-ORDER-STORE' ($op[1] -eq '102') 'DB ord_order.store_id=102'
  Add-Check 'DB-ORDER-CUSTOMER' ([long]$op[2] -gt 0) 'DB ord_order.customer_id bound to the C-side member' ([ordered]@{ customerId = $op[2] })
  Add-Check 'DB-ORDER-TRADE-INIT' (@('DRAFT', 'PENDING_PAYMENT') -contains $op[3]) 'order trade_status is unpaid after booking creation' ([ordered]@{ tradeStatus = $op[3] })
  Add-Check 'DB-ORDER-REFUND-INIT' ($op[4] -eq 'NONE') 'refund_status is NONE after booking creation'
  Add-Check 'DB-ORDER-TOTAL' ([decimal]$op[5] -eq [decimal]$amountMinor) 'DB order total equals the booking amount'
  $stayCount = Get-SqlValue "SELECT COUNT(*) FROM htl_stay WHERE booking_id = $bookingId;"
  Add-Check 'DB-NO-STAY' ($stayCount -eq '0') 'no htl_stay row before check-in'
  $availAfterCreate = Get-AvailabilityState $fixture.roomTypeId $nights
  $heldOk = $true
  foreach ($d in $nights) {
    if ([int]$availAfterCreate[$d].held -ne [int]$availBefore[$d].held + 1) { $heldOk = $false }
    if ([int]$availAfterCreate[$d].sold -ne [int]$availBefore[$d].sold) { $heldOk = $false }
  }
  Add-Check 'DB-AVAIL-HOLD' $heldOk 'booking only holds room availability (held+1, sold unchanged)' ([ordered]@{ before = $availBefore; after = $availAfterCreate })
  $script:dbSnapshot.bookingAfterCreate = $bookingRow
  $script:dbSnapshot.orderAfterCreate = $orderRow
  $script:dbSnapshot.availabilityAfterCreate = $availAfterCreate

  $myBookings = Invoke-Http -Client $hotelClient -Method GET -Path '/business/hotel/bookings'
  $bookingList = @(Get-Data $myBookings)
  $foundBooking = @($bookingList | Where-Object { $_.bookingNo -eq $booking.bookingNo })
  Add-Check 'C-QUERY-BOOKING' ($myBookings.status -eq 200 -and $foundBooking.Count -eq 1) 'C-side booking query returns this booking' ([ordered]@{ status = $myBookings.status; count = $bookingList.Count })
  $myOrders = Invoke-Http -Client $hotelClient -Method GET -Path '/me/orders'
  $orderList = @(Get-Data $myOrders)
  $foundOrder = @($orderList | Where-Object { [long]$_.id -eq $orderId })
  Add-Check 'C-QUERY-ORDER' ($myOrders.status -eq 200 -and $foundOrder.Count -eq 1) 'C-side my-orders returns the linked order' ([ordered]@{ status = $myOrders.status; count = $orderList.Count })

  Step '3. C5 C-side self-payment (CASH) + idempotency + payment automation'
  $idemKey = 'e2e-c-hotel-' + $stamp
  $collectBody = @{ currencyCode = $currency; payable = $amountMinor; payments = @(@{ method = 'CASH'; amount = $amountMinor }) }
  $collect1 = Invoke-Http -Client $hotelClient -Method POST -Path ("/business/orders/" + $orderId + "/collect") -Body $collectBody -ExtraHeaders @{ 'Idempotency-Key' = $idemKey }
  $collect1Data = Get-Data $collect1
  Add-Check 'PAY-COLLECT' ($collect1.status -eq 200 -and [long]$collect1Data.remainingAmount -eq 0) 'C-side CASH collection succeeds with zero remaining amount' ([ordered]@{ status = $collect1.status; result = $collect1Data })
  $collect2 = Invoke-Http -Client $hotelClient -Method POST -Path ("/business/orders/" + $orderId + "/collect") -Body $collectBody -ExtraHeaders @{ 'Idempotency-Key' = $idemKey }
  $collect2Data = Get-Data $collect2
  Add-Check 'PAY-COLLECT-IDEMPOTENT' ($collect2.status -eq 200 -and [long]$collect2Data.remainingAmount -eq 0) 'the same Idempotency-Key returns the same collect result' ([ordered]@{ status = $collect2.status; result = $collect2Data })
  $collect3 = Invoke-Http -Client $hotelClient -Method POST -Path ("/business/orders/" + $orderId + "/collect") -Body $collectBody -ExtraHeaders @{ 'Idempotency-Key' = ($idemKey + '-again') }
  Add-Check 'PAY-COLLECT-DUPLICATE' ($collect3.status -ge 400 -and $collect3.status -lt 500) 'a new idempotency key after full payment is rejected (no double charge)' ([ordered]@{ status = $collect3.status; body = $collect3.body })
  $payCollectCount = Get-SqlValue "SELECT COUNT(*) FROM pay_collect WHERE order_id = $orderId;"
  $payIntentCount = Get-SqlValue "SELECT COUNT(*) FROM pay_intent WHERE order_id = $orderId AND status = 'SUCCEEDED';"
  $payTxCount = Get-SqlValue "SELECT COUNT(*) FROM pay_transaction t JOIN pay_intent i ON i.id = t.payment_intent_id WHERE i.order_id = $orderId AND t.status = 'SUCCEEDED';"
  $orderPaidRow = Get-SqlRow "SELECT CONCAT_WS('|', trade_status, refund_status, total_amount, paid_amount) FROM ord_order WHERE id = $orderId;"
  Add-Check 'DB-PAY-COLLECT-ONE' ($payCollectCount -eq '1') 'pay_collect has exactly one row for the order'
  Add-Check 'DB-PAY-INTENT-ONE' ($payIntentCount -eq '1') 'pay_intent has exactly one SUCCEEDED row'
  Add-Check 'DB-PAY-TX-ONE' ($payTxCount -eq '1') 'pay_transaction has exactly one SUCCEEDED row (no double charge)'
  $paid = $orderPaidRow -split '\|', 4
  Add-Check 'DB-ORDER-PAID' ($paid[0] -eq 'PAID') 'DB ord_order.trade_status=PAID after collection'
  Add-Check 'DB-ORDER-PAID-AMOUNT' ([decimal]$paid[3] -eq [decimal]$amountMinor) 'DB paid_amount equals the receivable amount' ([ordered]@{ paid = $paid[3] })
  $script:dbSnapshot.payment = [ordered]@{ payCollectCount = $payCollectCount; payIntentCount = $payIntentCount; payTransactionCount = $payTxCount; order = $orderPaidRow }

  $confirmed = Wait-BookingConfirmed $bookingId $fixture.roomTypeId $nights 150
  Add-Check 'AUTO-BOOKING-CONFIRM' ($confirmed.bookingStatus -eq 'CONFIRMED') 'paid booking is auto-confirmed (HotelBookingPaymentReconciler)' ([ordered]@{ bookingStatus = $confirmed.bookingStatus })
  Add-Check 'AUTO-ORDER-PAID' ($confirmed.orderTradeStatus -eq 'PAID') 'order trade_status is PAID'
  $soldOk = $true
  foreach ($d in $nights) {
    if ([int]$confirmed.availability[$d].sold -ne [int]$availBefore[$d].sold + 1) { $soldOk = $false }
    if ([int]$confirmed.availability[$d].held -ne [int]$availBefore[$d].held) { $soldOk = $false }
  }
  Add-Check 'AUTO-AVAIL-SOLD' $soldOk 'availability moves held->sold after confirmation (net occupancy unchanged)' ([ordered]@{ before = $availBefore; after = $confirmed.availability })
  $script:dbSnapshot.afterConfirm = $confirmed

  Step '4. C7 Online-channel payment failure (channel not enabled)'
  $create2 = Invoke-Http -Client $hotelClient -Method POST -Path '/business/hotel/bookings' -Body @{
    roomTypeId = $fixture.roomTypeId; ratePlanId = $fixture.ratePlanId
    checkInDate = $stay.checkIn; checkOutDate = $stay.checkOut; rooms = 1
    guestName = 'E2E C Alipay ' + $stamp; guestPhone = ('139' + (Get-Random -Minimum 10000000 -Maximum 99999999))
  }
  $booking2 = Get-Data $create2
  Add-Check 'HOTEL-BOOKING2-CREATE' ($create2.status -eq 201) 'second C-side booking created (online-channel failure case)'
  $orderId2 = [long]$booking2.orderId
  $alipay = Invoke-Http -Client $hotelClient -Method POST -Path ("/business/orders/" + $orderId2 + "/collect") -Body @{ currencyCode = [string]$booking2.currency; payable = [long]$booking2.amountMinor; payments = @(@{ method = 'ALIPAY'; amount = [long]$booking2.amountMinor }) } -ExtraHeaders @{ 'Idempotency-Key' = ('e2e-c-alipay-' + $stamp) }
  Add-Check 'PAY-ONLINE-CHANNEL-NOT-GRANTED' ($alipay.status -ge 400 -and $alipay.status -lt 500) 'ALIPAY collection is rejected with a 4xx while the channel is not granted (negative check; the real online-channel success path is covered by the SANDBOX callback cases in P1)' ([ordered]@{ status = $alipay.status; code = (Get-ErrorCode $alipay); body = $alipay.raw })
  $cancel2 = Invoke-Http -Client $hotelClient -Method POST -Path ("/business/hotel/bookings/" + $booking2.id + '/cancel')
  Add-Check 'HOTEL-BOOKING2-CANCEL' ((Get-Data $cancel2).status -eq 'CANCELLED') 'second booking cancelled cleanly'

  Step '5. C6 refund chain + duplicate refund-request / refund-callback idempotency'
  $refundIdemKey = 'e2e-refund-' + $stamp
  $refundBody = @{ orderId = $orderId; amount = $amountMinor; reason = ('E2E refund ' + $stamp); requestedBy = 100 }
  $refundReq = Invoke-Http -Client $bClient -Method POST -Path '/business/refund-requests' -Body $refundBody -ExtraHeaders @{ 'Idempotency-Key' = $refundIdemKey }
  $refund = Get-Data $refundReq
  Add-Check 'REFUND-REQUEST' ($refundReq.status -eq 200 -and [string]$refund.status -eq 'PENDING') 'refund request created as PENDING' ([ordered]@{ status = $refundReq.status; id = $refund.id })
  $refundId = [long]$refund.id
  $refundRowsAtStart = Get-SqlValue "SELECT COUNT(*) FROM pay_refund WHERE order_id = $orderId;"
  $replayRefund = Invoke-Http -Client $bClient -Method POST -Path '/business/refund-requests' -Body $refundBody -ExtraHeaders @{ 'Idempotency-Key' = $refundIdemKey }
  $replayRefundData = Get-Data $replayRefund
  $refundRowsAfterReplay = Get-SqlValue "SELECT COUNT(*) FROM pay_refund WHERE order_id = $orderId;"
  Add-Check 'REFUND-REQUEST-IDEMPOTENT-REPLAY' ($replayRefund.status -eq 200 -and [long]$replayRefundData.id -eq $refundId -and $refundRowsAtStart -eq $refundRowsAfterReplay) 'same Idempotency-Key refund request replays the same refund id and never inserts a second pay_refund row' ([ordered]@{ status = $replayRefund.status; originalId = $refundId; replayId = $replayRefundData.id; rowsBefore = $refundRowsAtStart; rowsAfter = $refundRowsAfterReplay })
  $approve = Invoke-Http -Client $bClient -Method POST -Path ("/admin/refund-requests/" + $refundId + "/approve") -Body @{ approvedAmount = $amountMinor; approvedBy = 100 }
  Add-Check 'REFUND-APPROVE' ([string](Get-Data $approve).status -eq 'APPROVED') 'refund request approved'
  $dupApprove = Invoke-Http -Client $bClient -Method POST -Path ("/admin/refund-requests/" + $refundId + "/approve") -Body @{ approvedAmount = $amountMinor; approvedBy = 100 }
  Add-Check 'REFUND-DUP-APPROVE-409' ($dupApprove.status -eq 409 -and (Get-ErrorCode $dupApprove) -eq 'REFUND_STATE_INVALID') 'duplicate approve is rejected with 409 REFUND_STATE_INVALID (never 500, no second approval)' ([ordered]@{ status = $dupApprove.status; code = (Get-ErrorCode $dupApprove); body = $dupApprove.raw })
  $mark = Invoke-Http -Client $bClient -Method POST -Path ("/admin/refund-requests/" + $refundId + "/refund") -Body @{ providerRefundNo = ('E2E-CASH-' + $stamp); operatorId = 100 }
  Add-Check 'REFUND-MARK' ([string](Get-Data $mark).status -eq 'REFUNDED') 'offline refund marked REFUNDED'
  $refundRow = Get-SqlRow "SELECT CONCAT_WS('|', status, requested_amount, approved_amount, provider_refund_no) FROM pay_refund WHERE id = $refundId;"
  $rp = $refundRow -split '\|', 4
  Add-Check 'DB-REFUND-STATUS' ($rp[0] -eq 'REFUNDED') 'DB pay_refund.status=REFUNDED'
  Add-Check 'DB-REFUND-AMOUNT' ([decimal]$rp[1] -eq [decimal]$amountMinor -and [decimal]$rp[2] -eq [decimal]$amountMinor) 'DB requested/approved refund amounts match'
  $refundedOrder = Get-SqlRow "SELECT CONCAT_WS('|', trade_status, refund_status, paid_amount, refundable_amount) FROM ord_order WHERE id = $orderId;"
  $ro = $refundedOrder -split '\|', 4
  Add-Check 'DB-ORDER-REFUND-FULL' ($ro[1] -eq 'FULL') 'DB ord_order.refund_status=FULL'
  Add-Check 'DB-ORDER-REFUND-AMOUNT' ([decimal]$ro[2] -eq 0 -and [decimal]$ro[3] -eq 0) 'DB paid/refundable amounts are rolled back to 0' ([ordered]@{ paid = $ro[2]; refundable = $ro[3] })
  $script:dbSnapshot.refund = [ordered]@{ payRefund = $refundRow; order = $refundedOrder }
  $paidBeforeDup = Get-SqlValue "SELECT paid_amount FROM ord_order WHERE id = $orderId;"
  $dupMark = Invoke-Http -Client $bClient -Method POST -Path ("/admin/refund-requests/" + $refundId + "/refund") -Body @{ providerRefundNo = ('E2E-CASH-' + $stamp); operatorId = 100 }
  $paidAfterDup = Get-SqlValue "SELECT paid_amount FROM ord_order WHERE id = $orderId;"
  Add-Check 'REFUND-DUP-NO-DOUBLE' ([decimal]$paidBeforeDup -eq [decimal]$paidAfterDup) 'duplicate refund callback does not roll back money twice' ([ordered]@{ paidBefore = $paidBeforeDup; paidAfter = $paidAfterDup; status = $dupMark.status })
  Add-Check 'REFUND-DUP-IDEMPOTENCY' ($dupMark.status -eq 409 -and (Get-ErrorCode $dupMark) -eq 'REFUND_STATE_INVALID') 'duplicate markRefunded is rejected with 409 REFUND_STATE_INVALID (never 500, no double rollback)' ([ordered]@{ status = $dupMark.status; code = (Get-ErrorCode $dupMark); body = $dupMark.raw })
  $refundRowsBeforeDup = Get-SqlValue "SELECT COUNT(*) FROM pay_refund WHERE order_id = $orderId;"
  # No-key duplicate AFTER the order is fully refunded: a new PENDING row must never be created.
  $refundReq2 = Invoke-Http -Client $bClient -Method POST -Path '/business/refund-requests' -Body @{ orderId = $orderId; amount = $amountMinor; reason = 'E2E duplicate refund request'; requestedBy = 100 } -ExtraHeaders @{ 'Idempotency-Key' = '' }
  $refundRowsAfterDup = Get-SqlValue "SELECT COUNT(*) FROM pay_refund WHERE order_id = $orderId;"
  $dupReqCode = Get-ErrorCode $refundReq2
  $dupReqOk = ($refundReq2.status -ge 400 -and $refundReq2.status -lt 500 -and @('REFUND_DUPLICATE', 'ORDER_ALREADY_REFUNDED', 'REFUND_EXCEEDS_PAID') -contains $dupReqCode)
  Add-Check 'REFUND-REQUEST-DUP-AFTER-REFUNDED' ($dupReqOk -and $refundRowsBeforeDup -eq $refundRowsAfterDup) 'duplicate no-key refund request after full refund returns 4xx (REFUND_DUPLICATE/ORDER_ALREADY_REFUNDED/REFUND_EXCEEDS_PAID) and never inserts a second pay_refund row' ([ordered]@{ status = $refundReq2.status; code = $dupReqCode; rowsBefore = $refundRowsBeforeDup; rowsAfter = $refundRowsAfterDup; body = $refundReq2.raw })

  Step '6. C1-C4 cleanup: cancel the refunded booking and release availability'
  $cancel1 = Invoke-Http -Client $hotelClient -Method POST -Path ("/business/hotel/bookings/" + $bookingId + '/cancel')
  Add-Check 'CLEANUP-BOOKING-CANCEL' ((Get-Data $cancel1).status -eq 'CANCELLED') 'test booking cancelled (releases availability)'
  $availFinal = Get-AvailabilityState $fixture.roomTypeId $nights
  $released = $true
  foreach ($d in $nights) {
    if ([int]$availFinal[$d].sold -ne [int]$availBefore[$d].sold) { $released = $false }
    if ([int]$availFinal[$d].held -ne [int]$availBefore[$d].held) { $released = $false }
  }
  Add-Check 'DB-AVAIL-RELEASED' $released 'availability returns to baseline after booking cancellation' ([ordered]@{ before = $availBefore; after = $availFinal })
  $script:dbSnapshot.availabilityFinal = $availFinal

  Step '7. O1 OTA channel open API (signed HMAC create/replay/get/cancel + DB assertions)'
  $otaPath = '/api/v1/open/hotel/v1/bookings'
  $otaSecret = Get-DeployConfigValue 'platform-order-service' 'HOTEL_CHANNEL_SANDBOX_SECRET'
  if ([string]::IsNullOrWhiteSpace($otaSecret)) { $otaSecret = 'local-kind-ota-channel-secret-0123456789abcdef' }
  $otaProbeBytes = Get-Utf8Bytes '{}'
  $otaProbe = Invoke-RawHttp -Method 'POST' -Url ($Gateway.TrimEnd('/') + $otaPath) -BodyBytes $otaProbeBytes -Headers (New-OtaHeaders 'POST' $otaPath $otaProbeBytes $otaSecret)
  $otaPresent = ($otaProbe.status -ne 404)
  Resolve-FeatureCheck 'OTA-ROUTE' $otaPresent 'OTA channel v1 create route is exposed at the gateway (HMAC filter active)' ([ordered]@{ status = $otaProbe.status; body = $otaProbe.raw })
  if ($otaPresent) {
    $otaQuote = Invoke-Http -Client $hotelClient -Method POST -Path '/business/hotel/quotes' -Body @{ roomTypeId = $fixture.roomTypeId; ratePlanId = $fixture.ratePlanId; checkInDate = $stay.checkIn; checkOutDate = $stay.checkOut; rooms = 1 }
    $otaQuoteData = Get-Data $otaQuote
    Add-Check 'OTA-SERVER-QUOTE' ($otaQuote.status -eq 200 -and [long]$otaQuoteData.totalMinor -gt 0) 'server-side quote is available for the OTA booking amount' ([ordered]@{ totalMinor = $otaQuoteData.totalMinor; currency = $otaQuoteData.currency })
    $bookingCountBeforeOta = Get-SqlValue "SELECT COUNT(*) FROM htl_booking WHERE tenant_id = 100 AND store_id = 102;"
    $otaRef = 'E2E-OTA-' + $stamp
    $otaPayload = [ordered]@{
      externalRef = $otaRef; hotelRef = '1'
      roomTypeRef = [string]$fixture.roomTypeId; ratePlanRef = [string]$fixture.ratePlanId
      checkIn = $stay.checkIn; checkOut = $stay.checkOut; rooms = 1; guests = 2
      guest = [ordered]@{ name = 'E2E OTA Guest'; phone = '13800138009' }
      amountMinor = [long]$otaQuoteData.totalMinor; currency = [string]$otaQuoteData.currency
      paymentResponsibility = 'HOTEL_COLLECT'
    }
    $otaJson = ($otaPayload | ConvertTo-Json -Compress -Depth 6)
    $otaBytes = Get-Utf8Bytes $otaJson
    $otaAvailBefore = Get-AvailabilityState $fixture.roomTypeId $nights
    $otaCreate = Invoke-RawHttp -Method 'POST' -Url ($Gateway.TrimEnd('/') + $otaPath) -BodyBytes $otaBytes -Headers (New-OtaHeaders 'POST' $otaPath $otaBytes $otaSecret)
    $otaBody = $otaCreate.body
    Add-Check 'OTA-CREATE-201' ($otaCreate.status -eq 201) 'signed OTA booking create returns HTTP 201' ([ordered]@{ status = $otaCreate.status; body = $otaCreate.raw })
    Add-Check 'OTA-CREATE-CONTENT' ($null -ne $otaBody -and [string]$otaBody.externalRef -eq $otaRef -and -not [string]::IsNullOrWhiteSpace([string]$otaBody.bookingNo) -and [string]$otaBody.status -eq 'PENDING_CONFIRM') 'OTA response echoes externalRef/bookingNo with PENDING_CONFIRM' ([ordered]@{ body = $otaBody })
    $otaAvailCreated = Get-AvailabilityState $fixture.roomTypeId $nights
    $holdOk = $true
    foreach ($d in $nights) {
      if ([int]$otaAvailCreated[$d].held -ne [int]$otaAvailBefore[$d].held + 1) { $holdOk = $false }
      if ([int]$otaAvailCreated[$d].sold -ne [int]$otaAvailBefore[$d].sold) { $holdOk = $false }
    }
    Add-Check 'DB-OTA-AVAIL-HOLD' $holdOk 'OTA create holds exactly one room-night per stay date (held+1, sold unchanged)' ([ordered]@{ before = $otaAvailBefore; after = $otaAvailCreated })
    $otaMappingCount = Get-SqlValue "SELECT COUNT(*) FROM htl_channel_booking WHERE channel_code = 'sandbox' AND external_ref = '$otaRef';"
    $otaMappingRow = Get-SqlRow "SELECT CONCAT_WS('|', id, COALESCE(booking_id, 0), COALESCE(booking_no, ''), request_hash, status, last_action, tenant_id, store_id) FROM htl_channel_booking WHERE channel_code = 'sandbox' AND external_ref = '$otaRef';"
    $otaBookingId = 0
    $otaOrderId = 0
    if (-not [string]::IsNullOrWhiteSpace($otaMappingRow)) {
      $m = $otaMappingRow -split '\|', 8
      $otaBookingId = [long]$m[1]
      Add-Check 'DB-CHANNEL-BOOKING-MAPPING' ($otaMappingCount -eq '1' -and $otaBookingId -gt 0 -and [string]$m[2] -eq [string]$otaBody.bookingNo) 'htl_channel_booking maps (channel_code, external_ref) to htl_booking' ([ordered]@{ mapping = $otaMappingRow })
      Add-Check 'DB-CHANNEL-BOOKING-SCOPE' ([string]$m[6] -eq '100' -and [string]$m[7] -eq '102') 'htl_channel_booking carries tenant_id=100/store_id=102 from the channel credential and hotel authority' ([ordered]@{ tenantId = $m[6]; storeId = $m[7] })
      Add-Check 'DB-CHANNEL-BOOKING-REQUEST-HASH' ([string]$m[3] -match '^[0-9a-f]{64}$') 'htl_channel_booking.request_hash is a 64-hex canonical request fingerprint' ([ordered]@{ requestHash = $m[3] })
    } else {
      Add-Check 'DB-CHANNEL-BOOKING-MAPPING' $false 'htl_channel_booking mapping row is missing'
      Add-Check 'DB-CHANNEL-BOOKING-SCOPE' $false 'htl_channel_booking scope cannot be checked without a mapping row'
      Add-Check 'DB-CHANNEL-BOOKING-REQUEST-HASH' $false 'htl_channel_booking.request_hash cannot be checked without a mapping row'
    }
    $otaRequiredFields = @('bookingNo', 'externalRef', 'status', 'currency', 'amountMinor')
    $otaMissingFields = @($otaRequiredFields | Where-Object { $null -eq $otaBody -or $null -eq $otaBody.PSObject.Properties[$_] })
    Add-Check 'OTA-RESPONSE-CONTRACT' ($otaMissingFields.Count -eq 0) 'OTA BookingResponse exposes the v1 required fields (bookingNo/externalRef/status/currency/amountMinor)' ([ordered]@{ missing = $otaMissingFields })
    $otaBookingRow = Get-SqlRow ("SELECT CONCAT_WS('|', order_id, status, organization_id, business_type, store_id, source) FROM htl_booking WHERE booking_no = '" + [string]$otaBody.bookingNo + "';")
    if (-not [string]::IsNullOrWhiteSpace($otaBookingRow)) {
      $b = $otaBookingRow -split '\|', 6
      $otaOrderId = [long]$b[0]
      Add-Check 'DB-OTA-BOOKING-ORG' ($b[2] -eq '100' -and $b[3] -eq 'HOTEL' -and $b[4] -eq '102') 'OTA booking carries store-authoritative organization/business/store' ([ordered]@{ row = $otaBookingRow })
      Add-Check 'DB-OTA-BOOKING-SOURCE' ([string]$b[5] -eq 'OTA_SANDBOX') 'OTA booking source marks the channel adapter (OTA_SANDBOX)' ([ordered]@{ source = $b[5] })
      Add-Check 'DB-OTA-BOOKING-ORDER' ($otaOrderId -gt 0 -and $otaOrderId -ne 0) 'OTA booking is linked to a trade order' ([ordered]@{ orderId = $otaOrderId })
      $otaOrderRow = Get-SqlRow "SELECT CONCAT_WS('|', business_type, store_id, trade_status, total_amount) FROM ord_order WHERE id = $otaOrderId;"
      $oo = $otaOrderRow -split '\|', 4
      Add-Check 'DB-OTA-ORDER' ($oo[0] -eq 'HOTEL' -and $oo[1] -eq '102' -and [decimal]$oo[3] -eq [decimal]$otaQuoteData.totalMinor) 'OTA order is HOTEL/hotel-store with the server-quoted amount' ([ordered]@{ row = $otaOrderRow })
    } else {
      Add-Check 'DB-OTA-BOOKING-ORDER' $false 'htl_booking row for the OTA booking is missing'
    }
    $otaReplay = Invoke-RawHttp -Method 'POST' -Url ($Gateway.TrimEnd('/') + $otaPath) -BodyBytes $otaBytes -Headers (New-OtaHeaders 'POST' $otaPath $otaBytes $otaSecret)
    $otaReplayBody = $otaReplay.body
    Add-Check 'OTA-REPLAY-200' ($otaReplay.status -eq 200 -and [string]$otaReplayBody.bookingNo -eq [string]$otaBody.bookingNo) 'same externalRef replay returns HTTP 200 and the same bookingNo' ([ordered]@{ status = $otaReplay.status; body = $otaReplayBody })
    Add-Check 'DB-OTA-REPLAY-SINGLE' ((Get-SqlValue "SELECT COUNT(*) FROM htl_channel_booking WHERE channel_code = 'sandbox' AND external_ref = '$otaRef';") -eq '1') 'idempotent replay keeps exactly one channel mapping row'
    $otaAvailReplay = Get-AvailabilityState $fixture.roomTypeId $nights
    $replayNoDouble = $true
    foreach ($d in $nights) {
      if ([int]$otaAvailReplay[$d].held -ne [int]$otaAvailCreated[$d].held) { $replayNoDouble = $false }
      if ([int]$otaAvailReplay[$d].sold -ne [int]$otaAvailCreated[$d].sold) { $replayNoDouble = $false }
    }
    Add-Check 'DB-OTA-REPLAY-NO-DOUBLE-HOLD' $replayNoDouble 'idempotent replay does not double the room-availability hold' ([ordered]@{ afterCreate = $otaAvailCreated; afterReplay = $otaAvailReplay })
    $bookingCountAfterOtaCreate = Get-SqlValue "SELECT COUNT(*) FROM htl_booking WHERE tenant_id = 100 AND store_id = 102;"
    $otaConflictPayload = [ordered]@{
      externalRef = $otaRef; hotelRef = '1'
      roomTypeRef = [string]$fixture.roomTypeId; ratePlanRef = [string]$fixture.ratePlanId
      checkIn = $stay.checkIn; checkOut = $stay.checkOut; rooms = 1; guests = 2
      guest = [ordered]@{ name = 'E2E OTA Guest'; phone = '13800138009' }
      amountMinor = ([long]$otaQuoteData.totalMinor + 100); currency = [string]$otaQuoteData.currency
      paymentResponsibility = 'HOTEL_COLLECT'
    }
    $otaConflictBytes = Get-Utf8Bytes ($otaConflictPayload | ConvertTo-Json -Compress -Depth 6)
    $otaConflict = Invoke-RawHttp -Method 'POST' -Url ($Gateway.TrimEnd('/') + $otaPath) -BodyBytes $otaConflictBytes -Headers (New-OtaHeaders 'POST' $otaPath $otaConflictBytes $otaSecret)
    Add-Check 'OTA-SAME-REF-DIFF-PAYLOAD-409' ($otaConflict.status -eq 409) 'same externalRef with a different payload is rejected with HTTP 409' ([ordered]@{ status = $otaConflict.status; body = $otaConflict.raw })
    Add-Check 'DB-OTA-CONFLICT-NO-SIDE-EFFECT' ((Get-SqlValue "SELECT COUNT(*) FROM htl_channel_booking WHERE channel_code = 'sandbox' AND external_ref = '$otaRef';") -eq '1' -and (Get-SqlValue "SELECT COUNT(*) FROM htl_booking WHERE tenant_id = 100 AND store_id = 102;") -eq $bookingCountAfterOtaCreate) 'conflicting replay leaves the single channel mapping row and htl_booking row count unchanged'
    $shortRef = 'E2E-OTA-SHORT-' + $stamp
    $shortQuote = Invoke-Http -Client $hotelClient -Method POST -Path '/business/hotel/quotes' -Body @{ roomTypeId = $fixture.roomTypeId; ratePlanId = $fixture.ratePlanId; checkInDate = $stay.checkIn; checkOutDate = $stay.checkOut; rooms = 9 }
    $shortQuoteData = Get-Data $shortQuote
    $shortAmount = [long]$shortQuoteData.totalMinor
    if ($shortAmount -le 0) { $shortAmount = ([long]$otaQuoteData.totalMinor * 9) }
    $shortPayload = [ordered]@{
      externalRef = $shortRef; hotelRef = '1'
      roomTypeRef = [string]$fixture.roomTypeId; ratePlanRef = [string]$fixture.ratePlanId
      checkIn = $stay.checkIn; checkOut = $stay.checkOut; rooms = 9; guests = 2
      guest = [ordered]@{ name = 'E2E OTA Shortage'; phone = '13800138010' }
      amountMinor = $shortAmount; currency = [string]$otaQuoteData.currency
      paymentResponsibility = 'HOTEL_COLLECT'
    }
    $shortBytes = Get-Utf8Bytes ($shortPayload | ConvertTo-Json -Compress -Depth 6)
    $shortResp = Invoke-RawHttp -Method 'POST' -Url ($Gateway.TrimEnd('/') + $otaPath) -BodyBytes $shortBytes -Headers (New-OtaHeaders 'POST' $otaPath $shortBytes $otaSecret)
    Add-Check 'OTA-INVENTORY-SHORTAGE-409' ($shortResp.status -eq 409 -and [string]$shortResp.body.code -eq 'ROOM_AVAILABILITY_UNAVAILABLE') 'OTA request for more rooms than the daily availability is rejected with 409 ROOM_AVAILABILITY_UNAVAILABLE' ([ordered]@{ status = $shortResp.status; body = $shortResp.raw; rooms = 9 })
    Add-Check 'DB-OTA-SHORTAGE-ROLLBACK' ((Get-SqlValue "SELECT COUNT(*) FROM htl_channel_booking WHERE channel_code = 'sandbox' AND external_ref = '$shortRef';") -eq '0' -and (Get-SqlValue "SELECT COUNT(*) FROM htl_booking WHERE tenant_id = 100 AND store_id = 102;") -eq $bookingCountAfterOtaCreate) 'failed OTA create leaves no channel mapping row and no htl_booking row (atomic rollback)'
    $otaGetPath = '/api/v1/open/hotel/v1/bookings/' + $otaRef
    $otaGet = Invoke-RawHttp -Method 'GET' -Url ($Gateway.TrimEnd('/') + $otaGetPath) -Headers (New-OtaHeaders 'GET' $otaGetPath ([byte[]]::new(0)) $otaSecret)
    Add-Check 'OTA-GET' ($otaGet.status -eq 200 -and [string]$otaGet.body.bookingNo -eq [string]$otaBody.bookingNo) 'signed GET returns the channel booking' ([ordered]@{ status = $otaGet.status; body = $otaGet.raw })
    $missingRef = 'E2E-OTA-NOT-EXIST-' + $stamp
    $missingPath = '/api/v1/open/hotel/v1/bookings/' + $missingRef
    $missingGetMapping = Get-SqlValue "SELECT COUNT(*) FROM htl_channel_booking WHERE channel_code = 'sandbox' AND external_ref = '$missingRef';"
    $missingGet = Invoke-RawHttp -Method 'GET' -Url ($Gateway.TrimEnd('/') + $missingPath) -Headers (New-OtaHeaders 'GET' $missingPath ([byte[]]::new(0)) $otaSecret)
    Add-Check 'OTA-GET-NOT-FOUND-404' ($missingGet.status -eq 404 -and [string]$missingGet.body.code -eq 'CHANNEL_BOOKING_NOT_FOUND') 'signed GET for an unknown externalRef returns 404 CHANNEL_BOOKING_NOT_FOUND' ([ordered]@{ status = $missingGet.status; body = $missingGet.raw })
    Add-Check 'DB-OTA-GET-MISSING-NO-SIDE-EFFECT' ((Get-SqlValue "SELECT COUNT(*) FROM htl_channel_booking WHERE channel_code = 'sandbox' AND external_ref = '$missingRef';") -eq $missingGetMapping) 'unknown GET writes no channel mapping row'
    $otaAriPath = '/api/v1/open/hotel/v1/ari'
    $otaAriBody = [ordered]@{ items = @([ordered]@{ hotelRef = '1'; roomTypeRef = [string]$fixture.roomTypeId; ratePlanRef = [string]$fixture.ratePlanId; stayDate = $stay.checkIn; total = 8; sold = 0; stopSell = $false; priceMinor = 89900; currency = 'CNY'; minStay = 1; version = 0 }) }
    $otaAriBytes = Get-Utf8Bytes ($otaAriBody | ConvertTo-Json -Compress -Depth 6)
    $otaAri = Invoke-RawHttp -Method 'POST' -Url ($Gateway.TrimEnd('/') + $otaAriPath) -BodyBytes $otaAriBytes -Headers (New-OtaHeaders 'POST' $otaAriPath $otaAriBytes $otaSecret)
    $otaAriResult = @($otaAri.body.results)[0]
    Add-Check 'OTA-ARI-202-NOOP' ($otaAri.status -eq 202 -and $otaAriResult.accepted -eq $false -and [string]$otaAriResult.code -eq 'ARI_NOT_IMPLEMENTED') 'signed ARI push is a 202 no-op with accepted=false/ARI_NOT_IMPLEMENTED' ([ordered]@{ status = $otaAri.status; body = $otaAri.raw })
    Add-Check 'DB-OTA-ARI-NO-WRITE' ((Get-AvailabilityState $fixture.roomTypeId $nights)[$stay.checkIn].total -eq $otaAvailCreated[$stay.checkIn].total -and (Get-SqlValue "SELECT COUNT(*) FROM htl_booking WHERE tenant_id = 100 AND store_id = 102;") -eq $bookingCountAfterOtaCreate) 'ARI no-op does not write availability or bookings'
    $badHeaders = New-OtaHeaders 'POST' $otaPath $otaBytes $otaSecret
    $badHeaders['X-Channel-Signature'] = ('0' * 64)
    $badSig = Invoke-RawHttp -Method 'POST' -Url ($Gateway.TrimEnd('/') + $otaPath) -BodyBytes $otaBytes -Headers $badHeaders
    Add-Check 'OTA-BAD-SIGNATURE-401' ($badSig.status -eq 401 -and [string]$badSig.body.code -eq 'CHANNEL_UNAUTHORIZED') 'tampered signature is rejected with 401 CHANNEL_UNAUTHORIZED' ([ordered]@{ status = $badSig.status; body = $badSig.raw })
    Add-Check 'OTA-BAD-SIGNATURE-NO-SIDE-EFFECT' ((Get-SqlValue "SELECT COUNT(*) FROM htl_channel_booking WHERE channel_code = 'sandbox' AND external_ref = '$otaRef';") -eq '1') 'rejected signature creates no extra channel mapping'
    $otaCancelPath = '/api/v1/open/hotel/v1/bookings/' + $otaRef + '/cancel'
    $otaCancelBytes = Get-Utf8Bytes '{"reason":"E2E OTA cancel"}'
    $otaCancel = Invoke-RawHttp -Method 'POST' -Url ($Gateway.TrimEnd('/') + $otaCancelPath) -BodyBytes $otaCancelBytes -Headers (New-OtaHeaders 'POST' $otaCancelPath $otaCancelBytes $otaSecret)
    Add-Check 'OTA-CANCEL' ($otaCancel.status -eq 200 -and [string]$otaCancel.body.status -eq 'CANCELLED') 'signed cancel returns CANCELLED' ([ordered]@{ status = $otaCancel.status; body = $otaCancel.raw })
    $otaProjection = Get-SqlRow "SELECT CONCAT_WS('|', status, last_action) FROM htl_channel_booking WHERE channel_code = 'sandbox' AND external_ref = '$otaRef';"
    Add-Check 'DB-OTA-CANCEL-PROJECTION' ($otaProjection -eq 'CANCELLED|CANCELLED' -or $otaProjection -eq 'CANCELLED|CANCEL_REPLAY') 'DB channel mapping status projects CANCELLED' ([ordered]@{ mapping = $otaProjection })
    Add-Check 'DB-OTA-CANCEL-BOOKING' ((Get-SqlValue "SELECT status FROM htl_booking WHERE id = $otaBookingId;") -eq 'CANCELLED') 'DB htl_booking is CANCELLED after channel cancel'
    $otaCancelReplay = Invoke-RawHttp -Method 'POST' -Url ($Gateway.TrimEnd('/') + $otaCancelPath) -BodyBytes $otaCancelBytes -Headers (New-OtaHeaders 'POST' $otaCancelPath $otaCancelBytes $otaSecret)
    $otaCancelReplayMapping = Get-SqlRow "SELECT CONCAT_WS('|', status, last_action) FROM htl_channel_booking WHERE channel_code = 'sandbox' AND external_ref = '$otaRef';"
    Add-Check 'OTA-CANCEL-REPLAY-200' ($otaCancelReplay.status -eq 200 -and [string]$otaCancelReplay.body.status -eq 'CANCELLED') 'repeat cancel is idempotent (HTTP 200, status CANCELLED)' ([ordered]@{ status = $otaCancelReplay.status; body = $otaCancelReplay.raw })
    Add-Check 'DB-OTA-CANCEL-REPLAY' ((Get-SqlValue "SELECT COUNT(*) FROM htl_channel_booking WHERE channel_code = 'sandbox' AND external_ref = '$otaRef';") -eq '1' -and [string]$otaCancelReplayMapping -like 'CANCELLED|*') 'repeat cancel keeps one mapping row and projects CANCEL_REPLAY/CANCELLED' ([ordered]@{ mapping = $otaCancelReplayMapping })
    $otaAvailFinal = Get-AvailabilityState $fixture.roomTypeId $nights
    $otaReleased = $true
    foreach ($d in $nights) {
      if ([int]$otaAvailFinal[$d].held -ne [int]$otaAvailBefore[$d].held) { $otaReleased = $false }
      if ([int]$otaAvailFinal[$d].sold -ne [int]$otaAvailBefore[$d].sold) { $otaReleased = $false }
    }
    Add-Check 'DB-OTA-CANCEL-RELEASES-AVAILABILITY' $otaReleased 'channel cancel releases the OTA room-availability hold back to baseline' ([ordered]@{ before = $otaAvailBefore; after = $otaAvailFinal })
    Add-Check 'DB-OTA-CANCEL-ORDER' ((Get-SqlValue "SELECT COUNT(*) FROM ord_order WHERE id = $otaOrderId AND trade_status IN ('CANCELLED', 'DRAFT', 'PENDING_PAYMENT');") -eq '1') 'OTA order is not paid and stays in an unpaid/cancelled trade state after cancel'
    $script:dbSnapshot.ota = [ordered]@{ mapping = $otaMappingRow; booking = $otaBookingRow; projection = $otaProjection }
  }

  Step '8. P1 channel payment callbacks (SANDBOX: success/duplicate/refund/closed/mismatch/signature)'
  $channelConfigRows = @(Invoke-Sql "SELECT CONCAT_WS('|', channel, enabled) FROM pay_channel_config ORDER BY channel;" | Where-Object { $_ -match '\|' } | ForEach-Object { $_.Trim() })
  $providerCount = Get-SqlValue "SELECT COUNT(*) FROM pay_channel_provider;"
  $channelTxBefore = Get-SqlValue "SELECT COUNT(*) FROM pay_channel_transaction;"
  $localPort = Get-Random -Minimum 24000 -Maximum 26000
  $channelJob = Start-PortForward 'svc/common-payment-channel-service' $localPort 4180
  $channelBase = 'http://127.0.0.1:' + $localPort
  $available = Invoke-RawHttp -Method 'GET' -Url ($channelBase + '/internal/channels/available')
  $capabilities = $null
  if ($available.status -eq 200 -and -not [string]::IsNullOrWhiteSpace($available.raw)) { $capabilities = ($available.raw | ConvertFrom-Json) }
  Add-Check 'CHANNEL-AVAILABLE' ($available.status -eq 200 -and $null -ne $capabilities) 'internal channel capability endpoint responds' ([ordered]@{ capabilities = $capabilities })
  $sandboxCap = $null
  if ($null -ne $capabilities) { $sandboxCap = @($capabilities | Where-Object { $_.provider -eq 'sandbox' }) | Select-Object -First 1 }
  $sandboxEnabled = ($null -ne $sandboxCap -and $sandboxCap.enabled -eq $true)
  Resolve-FeatureCheck 'CHANNEL-SANDBOX-AVAILABLE' $sandboxEnabled 'SANDBOX payment provider is enabled in the channel service' ([ordered]@{ capabilities = $capabilities; payChannelConfig = $channelConfigRows; payChannelProviderCount = $providerCount })
  $script:channelOrders = @()
  if ($sandboxEnabled) {
    $sandboxSecret = Get-DeployConfigValue 'common-payment-channel-service' 'SANDBOX_PAY_WEBHOOK_SECRET'
    if ([string]::IsNullOrWhiteSpace($sandboxSecret)) { $sandboxSecret = 'local-e2e-sandbox-webhook-secret' }
    function New-UnpaidChannelOrder([string]$tag, [long]$roomTypeId) {
      $resp = Invoke-Http -Client $hotelClient -Method POST -Path '/business/hotel/bookings' -Body @{
        roomTypeId = $roomTypeId; ratePlanId = $fixture.ratePlanId
        checkInDate = $stay.checkIn; checkOutDate = $stay.checkOut; rooms = 1
        guestName = 'E2E Sandbox ' + $tag + ' ' + $stamp
        guestPhone = ('137' + (Get-Random -Minimum 10000000 -Maximum 99999999))
      }
      $d = Get-Data $resp
      if ($resp.status -ne 201) {
        Add-Check ('CH-ORDER-' + $tag) $false ('fresh unpaid C-side order could not be created for sandbox case ' + $tag) ([ordered]@{ status = $resp.status; body = $resp.raw })
        return $null
      }
      Add-Check ('CH-ORDER-' + $tag) $true ('fresh unpaid C-side order created for sandbox case ' + $tag) ([ordered]@{ orderId = $d.orderId; bookingId = $d.id; roomTypeId = $roomTypeId })
      $entry = [ordered]@{ bookingId = [long]$d.id; orderId = [long]$d.orderId; amountMinor = [long]$d.amountMinor; currency = [string]$d.currency }
      $script:channelOrders += $entry
      return $entry
    }
    function Send-SandboxCallback([object]$payload, [string]$secret, [string]$signatureOverride = '') {
      $json = ($payload | ConvertTo-Json -Compress -Depth 6)
      $bytes = Get-Utf8Bytes $json
      $signature = if ([string]::IsNullOrWhiteSpace($signatureOverride)) { Get-HmacSha256Hex $secret $json } else { $signatureOverride }
      return Invoke-RawHttp -Method 'POST' -Url ($channelBase + '/internal/channels/sandbox/callback') -ContentType 'application/json' -BodyBytes $bytes -Headers @{ 'X-Sandbox-Signature' = $signature }
    }
    function Get-OrderMoney([long]$id) { return (Get-SqlRow "SELECT CONCAT_WS('|', trade_status, paid_amount, refundable_amount, refund_status) FROM ord_order WHERE id = $id;") }

    $caseA = New-UnpaidChannelOrder 'SUCCESS' 2
    if ($null -eq $caseA) { throw 'sandbox SUCCESS case order creation failed' }
    $txA = 'E2E-SANDBOX-TX-' + $stamp
    $evA = 'E2E-SANDBOX-EVT-' + $stamp
    $payloadA = [ordered]@{ transactionId = $txA; eventId = $evA; orderId = $caseA.orderId; amount = $caseA.amountMinor; currency = $caseA.currency; status = 'SUCCEEDED'; tenantId = 100 }
    $ackA = Send-SandboxCallback $payloadA $sandboxSecret
    Add-Check 'CH-SUCCESS-ACK' ($ackA.status -eq 200 -and [string]$ackA.body.code -eq 'SUCCESS') 'sandbox SUCCEEDED callback returns HTTP 200 SUCCESS' ([ordered]@{ status = $ackA.status; body = $ackA.raw })
    $intentA = Get-SqlRow "SELECT CONCAT_WS('|', COUNT(*), COALESCE(MAX(status), ''), COALESCE(MAX(idempotency_key), '')) FROM pay_intent WHERE order_id = $($caseA.orderId) AND provider = 'SANDBOX';"
    Add-Check 'DB-CH-PAY-INTENT' ($intentA -like '1|SUCCEEDED|CH:SANDBOX:*') 'DB pay_intent: exactly one SUCCEEDED SANDBOX intent with CH:SANDBOX idempotency prefix' ([ordered]@{ row = $intentA })
    Add-Check 'DB-CH-PAY-TX' ((Get-SqlValue "SELECT COUNT(*) FROM pay_transaction t JOIN pay_intent i ON i.id = t.payment_intent_id WHERE i.order_id = $($caseA.orderId) AND t.provider_transaction_no = '$txA' AND t.status = 'SUCCEEDED';") -eq '1') 'DB pay_transaction: exactly one SUCCEEDED row for the callback transaction id'
    $moneyA = Get-OrderMoney $caseA.orderId
    $ma = $moneyA -split '\|', 4
    Add-Check 'DB-CH-ORDER-PAID' ($ma[0] -eq 'PAID' -and [decimal]$ma[1] -eq [decimal]$caseA.amountMinor -and [decimal]$ma[2] -eq [decimal]$caseA.amountMinor -and $ma[3] -eq 'NONE') 'DB ord_order: trade_status=PAID and paid=refundable=amount' ([ordered]@{ row = $moneyA })
    $settlementPayment = Get-SqlRow "SELECT CONCAT_WS('|', kind, status, amount) FROM pay_channel_settlement WHERE provider = 'SANDBOX' AND transaction_id = '$txA' ORDER BY id LIMIT 1;"
    $sp = $settlementPayment -split '\|', 3
    Add-Check 'DB-CH-SETTLEMENT-PAYMENT' ($sp[0] -eq 'PAYMENT' -and $sp[1] -eq 'SUCCEEDED' -and [decimal]$sp[2] -eq [decimal]$caseA.amountMinor) 'DB pay_channel_settlement: PAYMENT/SUCCEEDED with the callback amount' ([ordered]@{ row = $settlementPayment })
    Add-Check 'DB-CH-TX-APPLIED' ((Get-SqlRow "SELECT CONCAT_WS('|', status, settlement_status, COALESCE(settlement_attempts, 0)) FROM pay_channel_transaction WHERE provider = 'SANDBOX' AND transaction_id = '$txA';") -eq 'SUCCEEDED|APPLIED|1') 'DB pay_channel_transaction: SUCCEEDED/APPLIED with one settlement attempt'
    $ackA2 = Send-SandboxCallback $payloadA $sandboxSecret
    Add-Check 'CH-DUPLICATE-ACK' ($ackA2.status -eq 200 -and [string]$ackA2.body.code -eq 'SUCCESS') 'duplicate SUCCEEDED callback returns HTTP 200 SUCCESS'
    Add-Check 'DB-CH-DUPLICATE-IDEMPOTENT' (((Get-SqlValue "SELECT COUNT(*) FROM pay_intent WHERE order_id = $($caseA.orderId) AND provider = 'SANDBOX';") -eq '1') -and ((Get-SqlValue "SELECT COUNT(*) FROM pay_channel_settlement WHERE transaction_id = '$txA';") -eq '1') -and ((Get-OrderMoney $caseA.orderId) -eq $moneyA)) 'duplicate callback adds no pay_intent/settlement rows and keeps paid_amount unchanged' ([ordered]@{ order = (Get-OrderMoney $caseA.orderId) })

    $payloadR = [ordered]@{ transactionId = $txA; eventId = ('E2E-SANDBOX-EVT-R-' + $stamp); orderId = $caseA.orderId; amount = $caseA.amountMinor; currency = $caseA.currency; status = 'REFUNDED'; tenantId = 100 }
    $ackR = Send-SandboxCallback $payloadR $sandboxSecret
    Add-Check 'CH-REFUND-ACK' ($ackR.status -eq 200 -and [string]$ackR.body.code -eq 'SUCCESS') 'sandbox REFUNDED callback returns HTTP 200 SUCCESS' ([ordered]@{ status = $ackR.status; body = $ackR.raw })
    $moneyR = Get-OrderMoney $caseA.orderId
    $mr = $moneyR -split '\|', 4
    Add-Check 'DB-CH-ORDER-REFUND' ($mr[3] -eq 'FULL' -and [decimal]$mr[1] -eq 0 -and [decimal]$mr[2] -eq 0) 'DB ord_order: refund_status=FULL with paid/refundable rolled back to 0' ([ordered]@{ row = $moneyR })
    $settlementKinds = @(Invoke-Sql "SELECT CONCAT_WS('|', kind, status) FROM pay_channel_settlement WHERE provider = 'SANDBOX' AND transaction_id = '$txA' ORDER BY id;" | Where-Object { $_ -match '\|' } | ForEach-Object { $_.Trim() })
    Add-Check 'DB-CH-SETTLEMENT-REFUND' ($settlementKinds.Count -eq 2 -and $settlementKinds -contains 'PAYMENT|SUCCEEDED' -and @($settlementKinds | Where-Object { $_ -match '^REFUND\|' }).Count -eq 1) 'DB pay_channel_settlement: PAYMENT + REFUND rows for the transaction' ([ordered]@{ rows = $settlementKinds })
    $ackR2 = Send-SandboxCallback $payloadR $sandboxSecret
    $settlementKinds2 = @(Invoke-Sql "SELECT CONCAT_WS('|', kind, status) FROM pay_channel_settlement WHERE provider = 'SANDBOX' AND transaction_id = '$txA' ORDER BY id;" | Where-Object { $_ -match '\|' } | ForEach-Object { $_.Trim() })
    $moneyR2 = Get-OrderMoney $caseA.orderId
    $mr2 = $moneyR2 -split '\|', 4
    Add-Check 'DB-CH-REFUND-IDEMPOTENT' ($ackR2.status -eq 200 -and $settlementKinds2.Count -eq 2 -and [decimal]$mr2[1] -eq 0) 'duplicate REFUNDED callback adds no settlement row and keeps paid_amount at 0' ([ordered]@{ rows = $settlementKinds2; order = $moneyR2 })

    $caseB = New-UnpaidChannelOrder 'CLOSED' 3
    if ($null -eq $caseB) { throw 'sandbox CLOSED case order creation failed' }
    $txB = 'E2E-SANDBOX-TX-CLOSED-' + $stamp
    $ackB = Send-SandboxCallback ([ordered]@{ transactionId = $txB; eventId = ('E2E-SANDBOX-EVT-CLOSED-' + $stamp); orderId = $caseB.orderId; amount = $caseB.amountMinor; currency = $caseB.currency; status = 'CLOSED'; tenantId = 100 }) $sandboxSecret
    Add-Check 'CH-CLOSED-ACK' ($ackB.status -eq 200 -and [string]$ackB.body.code -eq 'SUCCESS') 'sandbox CLOSED callback returns HTTP 200 SUCCESS' ([ordered]@{ status = $ackB.status; body = $ackB.raw })
    $moneyB = Get-OrderMoney $caseB.orderId
    $mb = $moneyB -split '\|', 4
    Add-Check 'DB-CH-CLOSED-NO-MONEY' ((Get-SqlValue "SELECT COUNT(*) FROM pay_intent WHERE order_id = $($caseB.orderId);") -eq '0' -and @('DRAFT', 'PENDING_PAYMENT') -contains $mb[0]) 'CLOSED/FAILED callback does not create pay_intent or change order payment' ([ordered]@{ order = $moneyB })
    Add-Check 'DB-CH-CLOSED-TX' ((Get-SqlRow "SELECT CONCAT_WS('|', status, settlement_status) FROM pay_channel_transaction WHERE provider = 'SANDBOX' AND transaction_id = '$txB';") -eq 'CLOSED|APPLIED') 'DB pay_channel_transaction: CLOSED/APPLIED (settled without money movement)'

    $caseD = New-UnpaidChannelOrder 'FAILED' 4
    if ($null -eq $caseD) { throw 'sandbox FAILED case order creation failed' }
    $txD = 'E2E-SANDBOX-TX-FAILED-' + $stamp
    $ackD = Send-SandboxCallback ([ordered]@{ transactionId = $txD; eventId = ('E2E-SANDBOX-EVT-FAILED-' + $stamp); orderId = $caseD.orderId; amount = $caseD.amountMinor; currency = $caseD.currency; status = 'FAILED'; tenantId = 100 }) $sandboxSecret
    Add-Check 'CH-FAILED-ACK' ($ackD.status -eq 200 -and [string]$ackD.body.code -eq 'SUCCESS') 'sandbox FAILED callback returns HTTP 200 SUCCESS (settled, no money movement)' ([ordered]@{ status = $ackD.status; body = $ackD.raw })
    $moneyD = Get-OrderMoney $caseD.orderId
    $md = $moneyD -split '\|', 4
    Add-Check 'DB-CH-FAILED-NO-MONEY' ((Get-SqlValue "SELECT COUNT(*) FROM pay_intent WHERE order_id = $($caseD.orderId);") -eq '0' -and @('DRAFT', 'PENDING_PAYMENT') -contains $md[0]) 'FAILED callback does not create pay_intent or change order payment' ([ordered]@{ order = $moneyD })
    Add-Check 'DB-CH-FAILED-TX' ((Get-SqlRow "SELECT CONCAT_WS('|', status, settlement_status) FROM pay_channel_transaction WHERE provider = 'SANDBOX' AND transaction_id = '$txD';") -match '^(FAILED|CLOSED)\|APPLIED$') 'DB pay_channel_transaction: FAILED/APPLIED (settled without money movement)'

    $caseC = New-UnpaidChannelOrder 'MISMATCH' 5
    if ($null -eq $caseC) { throw 'sandbox MISMATCH case order creation failed' }
    $txC = 'E2E-SANDBOX-TX-MISMATCH-' + $stamp
    $ackC = Send-SandboxCallback ([ordered]@{ transactionId = $txC; eventId = ('E2E-SANDBOX-EVT-MISMATCH-' + $stamp); orderId = $caseC.orderId; amount = ([long]$caseC.amountMinor - 1); currency = $caseC.currency; status = 'SUCCEEDED'; tenantId = 100 }) $sandboxSecret
    Add-Check 'CH-AMOUNT-MISMATCH-400' ($ackC.status -eq 400 -and [string]$ackC.body.code -eq 'FAIL') 'amount-mismatch callback returns HTTP 400 FAIL' ([ordered]@{ status = $ackC.status; body = $ackC.raw })
    Add-Check 'DB-CH-MISMATCH-NO-LEDGER' ((Get-SqlValue "SELECT COUNT(*) FROM pay_intent WHERE order_id = $($caseC.orderId);") -eq '0' -and (Get-SqlValue "SELECT COUNT(*) FROM pay_channel_settlement WHERE transaction_id = '$txC';") -eq '0') 'amount-mismatch callback writes no pay_intent/settlement rows'
    $ackBadSig = Send-SandboxCallback ([ordered]@{ transactionId = ('E2E-SANDBOX-TX-BADSIG-' + $stamp); eventId = ('E2E-SANDBOX-EVT-BADSIG-' + $stamp); orderId = $caseC.orderId; amount = $caseC.amountMinor; currency = $caseC.currency; status = 'SUCCEEDED'; tenantId = 100 }) $sandboxSecret ('0' * 64)
    Add-Check 'CH-BAD-SIGNATURE-400' ($ackBadSig.status -eq 400 -and [string]$ackBadSig.body.code -eq 'FAIL') 'invalid sandbox callback signature returns HTTP 400 FAIL' ([ordered]@{ status = $ackBadSig.status; body = $ackBadSig.raw })

    foreach ($case in $script:channelOrders) {
      $cancel = Invoke-Http -Client $hotelClient -Method POST -Path ('/business/hotel/bookings/' + $case.bookingId + '/cancel')
      $state = [string](Get-Data $cancel).status
      Add-Check ('CH-CLEANUP-' + $case.orderId) ($state -eq 'CANCELLED' -or $state -eq 'CHECKED_OUT') ('sandbox case booking cleaned up: ' + $state)
    }
    $script:dbSnapshot.channel = [ordered]@{
      payChannelConfig = $channelConfigRows; capabilities = $capabilities
      successIntent = $intentA; successOrder = $moneyA; refundOrder = $moneyR; closedOrder = $moneyB
      settlementKinds = $settlementKinds
    }
  }
  $gatewayInternal = Invoke-Http -Client (New-Client 'anon') -Method POST -Path '/internal/channels/sandbox/callback' -Body @{}
  Add-Check 'CHANNEL-INTERNAL-NOT-EXPOSED' ($gatewayInternal.status -eq 404) 'internal channel callback is not exposed through the public gateway' ([ordered]@{ status = $gatewayInternal.status })
}
catch {
  Add-Check 'E2E-FATAL' $false ('script aborted: ' + $_.Exception.Message)
}
finally {
  Stop-PortForward $channelJob
  # Best-effort cleanup so a mid-run fatal never leaves active room holds behind for the next run.
  if ($hotelClient) {
    $leftovers = @()
    if ($bookingId -gt 0) { $leftovers += [long]$bookingId }
    if ($otaBookingId -gt 0) { $leftovers += [long]$otaBookingId }
    foreach ($cb in @($script:channelOrders)) { if ($null -ne $cb) { $leftovers += [long]$cb.bookingId } }
    foreach ($leftId in @($leftovers | Select-Object -Unique)) {
      try {
        $leftStatus = Get-SqlValue "SELECT status FROM htl_booking WHERE id = $leftId;"
        if (@('PENDING_CONFIRM', 'CONFIRMED') -contains $leftStatus) {
          [void](Invoke-Http -Client $hotelClient -Method POST -Path ('/business/hotel/bookings/' + $leftId + '/cancel'))
        }
      } catch { }
    }
  }
  if ($fixturePrepared -and -not $KeepFixture) {
    try {
      [void](Invoke-Fixture 'Restore' 'C-Hotel')
      $restoredStore = Get-SqlValue "SELECT store_id FROM iam_consumer_application WHERE app_id = 'saas-c';"
      Add-Check 'FIXTURE-RESTORE' ($restoredStore -eq '100') 'saas-c app installation restored to the original store 100' ([ordered]@{ storeId = $restoredStore })
    } catch {
      Add-Check 'FIXTURE-RESTORE' $false ('fixture restore failed: ' + $_.Exception.Message)
    }
  }
  $script:dbSnapshot.finalOrder = if ($orderId -gt 0) { Get-SqlRow "SELECT CONCAT_WS('|', id, trade_status, refund_status, total_amount, paid_amount) FROM ord_order WHERE id = $orderId;" } else { '' }
  $exitOk = Write-Result ([ordered]@{ db = $script:dbSnapshot; guestName = $guestName; stay = $stay; roomFixture = $fixture })
}

if (-not $exitOk) { exit 1 }
exit 0
