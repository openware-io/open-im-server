# Reservation-by-room-type acceptance (kind / ACK): C-end books a room TYPE -> store assigns a concrete
# room on arrival -> opens the table.
#
# Coverage (docs/renovation/KTV_RESERVATION_ROOM_TYPE.md):
#   1) C-end (customer session) creates a reservation carrying only roomTypeId; the response echoes
#      roomTypeName/roomTypeCode and leaves resourceId empty;
#   2) Admin cannot open the table before arrival (RESERVATION_STATUS_INVALID);
#   3) Admin assign-room picks a concrete room: status becomes ARRIVED and resourceId/resourceName are filled;
#   4) Admin open-table creates the order + KTV session.
#
# NOTE: this script WRITES data (one reservation, one order, one session, and it occupies one room).
# After running it you MUST delete exactly the ids it prints (reservation / order / session / occupation)
# or you will leave test documents and a held room behind.
# NOTE: keep this file ASCII-only: Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI and non-ASCII
# literals can break parsing (see repo guideline).
param(
  [string]$AdminBaseUrl = 'http://192.168.31.91:30081',
  [string]$ConsumerBaseUrl = 'http://192.168.31.91:30082',
  [string]$AdminUser = 'admin',
  [string]$AdminPassword = 'e8280ac0d25d4bc0a1e1',
  [string]$AdminContextId = '100:100:100',
  [string]$ConsumerAppId = 'saas-a380-c',
  [long]$RoomTypeId = 7,
  [long]$RoomResourceId = 1001,
  [int]$DaysAhead = 7
)

$ErrorActionPreference = 'Continue'
function Api($session, $base, $method, $path, $body, $csrf) {
  $headers = @{ 'Content-Type' = 'application/json' }
  if ($csrf -and $method -ne 'GET') { $headers['X-CSRF-Token'] = $csrf }
  $p = @{ Method = $method; Uri = ($base + $path); Headers = $headers; WebSession = $session; UseBasicParsing = $true }
  if ($body) { $p.Body = ($body | ConvertTo-Json -Compress -Depth 8) }
  try {
    $r = Invoke-WebRequest @p
    $text = [System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
    $json = if ($text) { $text | ConvertFrom-Json } else { $null }
    # Platform envelope: several BFF endpoints wrap the payload as {data:...,requestId:...}.
    if ($json -and $json.PSObject.Properties['data']) { $json = $json.data }
    return @{ ok = $true; status = [int]$r.StatusCode; json = $json; text = $text }
  } catch {
    $s = 0; $detail = $_.Exception.Message
    if ($_.Exception.Response) {
      $s = [int]$_.Exception.Response.StatusCode
      try { $detail = (New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())).ReadToEnd() } catch { }
    }
    return @{ ok = $false; status = $s; json = $null; text = $detail }
  }
}
function Assert($cond, $msg) { if ($cond) { Write-Host ("  PASS " + $msg) -ForegroundColor Green } else { Write-Host ("  FAIL " + $msg) -ForegroundColor Red } }

# ---------- C-end (customer) session: books a room TYPE ----------
Write-Host '== 1. C-end books a room type'
$cSess = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$dev = Api $cSess $ConsumerBaseUrl 'POST' ("/api/v1/dev/saas/session?appId=" + $ConsumerAppId) $null $null
Write-Host ("   dev session -> HTTP " + $dev.status + " accountId=" + $dev.json.accountId)
$cCsrf = (Api $cSess $ConsumerBaseUrl 'GET' '/api/v1/auth/csrf' $null $null).json.csrfToken
$ctxs = (Api $cSess $ConsumerBaseUrl 'GET' '/api/v1/auth/contexts' $null $cCsrf).json
$ctxList = @($ctxs.items); if ($ctxList.Count -eq 0) { $ctxList = @($ctxs) }
$ctxId = ($ctxList | Where-Object { $_ } | Select-Object -First 1).contextId
$sel = Api $cSess $ConsumerBaseUrl 'POST' '/api/v1/auth/context/select' @{ contextId = $ctxId } $cCsrf
Write-Host ("   context select -> HTTP " + $sel.status + " tenantId=" + $sel.json.tenantId + " currencyCode=" + $sel.json.currencyCode)

$stamp = [DateTime]::Now.AddDays($DaysAhead).ToString('yyyy-MM-dd')
$res = Api $cSess $ConsumerBaseUrl 'POST' '/api/v1/business/reservations' @{
  businessType = 'KTV'; roomTypeId = $RoomTypeId
  startAt = ($stamp + 'T19:00:00+08:00'); endAt = ($stamp + 'T21:00:00+08:00')
  partySize = 4; contact = 'e2e-c-end'
} $cCsrf
Assert ($res.ok -and $res.json.roomTypeId -eq $RoomTypeId) ("create reservation by roomTypeId -> HTTP " + $res.status + " id=" + $res.json.id + " roomTypeName=" + $res.json.roomTypeName)
Assert ($res.ok -and -not $res.json.resourceId) 'reservation carries no concrete room yet (resourceId empty)'
$resId = $res.json.id

# ---------- Admin session: assign a concrete room, then open the table ----------
Write-Host '== 2. Admin assigns the room and opens the table'
$aSess = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$null = Api $aSess $AdminBaseUrl 'POST' '/api/v1/admin/auth/login' @{ username = $AdminUser; password = $AdminPassword; ttlHours = 8 } $null
$aCsrf = (Api $aSess $AdminBaseUrl 'GET' '/api/v1/admin/auth/csrf' $null $null).json.csrfToken
$null = Api $aSess $AdminBaseUrl 'POST' '/api/v1/admin/context/select' @{ contextId = $AdminContextId } $aCsrf

$pre = Api $aSess $AdminBaseUrl 'POST' ("/api/v1/admin/reservations/" + $resId + '/open-table') $null $aCsrf
Assert (-not $pre.ok) ("open-table before arrival rejected: HTTP " + $pre.status + " " + $pre.text)

$assign = Api $aSess $AdminBaseUrl 'POST' ("/api/v1/admin/reservations/" + $resId + '/assign-room') @{ resourceId = $RoomResourceId; override = $false } $aCsrf
Assert ($assign.ok -and $assign.json.resourceId -eq $RoomResourceId) ("assign room " + $RoomResourceId + " -> HTTP " + $assign.status + " status=" + $assign.json.status + " resourceName=" + $assign.json.resourceName)

$open = Api $aSess $AdminBaseUrl 'POST' ("/api/v1/admin/reservations/" + $resId + '/open-table') $null $aCsrf
Assert ($open.ok -and $open.json.id) ("open table -> HTTP " + $open.status + " orderId=" + $open.json.id + " sessionId=" + $open.json.sessionId)

Write-Host ''
Write-Host ("CREATED reservationId=" + $resId + " orderId=" + $open.json.id + " sessionId=" + $open.json.sessionId + " roomResourceId=" + $RoomResourceId) -ForegroundColor DarkYellow
Write-Host 'CLEANUP: delete exactly the ids above (order + items + session + occupation + reservation), then confirm the room is free.' -ForegroundColor DarkYellow
