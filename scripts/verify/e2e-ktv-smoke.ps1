# E8 pre-release: KTV end-to-end smoke script (login chain + tenant context + real DTO fields + real session chain)
# Coverage: login chain -> tenant init -> resource -> open table (order) -> timing -> assign server -> close table
#           -> combined collection -> daily closing -> cash reconciliation -> permission isolation -> tenant isolation
#
# [Login chain conclusions (identity service, gateway /api/v1/**)]
#   POST /api/v1/identity/accounts   register  body {loginType, loginIdentifier, credential} -> account {id} (no JWT)
#   POST /api/v1/identity/login      login     body {loginType, loginIdentifier, credential} -> {accountId, accessToken(JWT), expiresInSeconds}
#   GET  /api/v1/auth/contexts       header Authorization: Bearer <accessToken> -> {items:[{contextId,tenantId,organizationId,storeId,roles,scopeType}]}
#   POST /api/v1/auth/context/select header Authorization: Bearer <accessToken> + body {contextId} -> {tenantContextToken(JWT/opaque placeholder), tenantId, ...}
#   * Skeleton mismatch: select returns a JWT/opaque token, but each business service's TenantContextFilter parses
#     X-Tenant-Context as JSON ({tenantId,organizationId,storeId,accountId,authorizationVersion,permissions}),
#     so the smoke test builds the JSON context header directly.
#   * Multi-tenant INSERT/SELECT is enforced by TenantLineInnerInterceptor, which requires X-Tenant-Context
#     (a missing header raises "tenant context missing").
#
# [Permission / tenant isolation + cash reconciliation conclusions (confirmed by reading the code, not placeholders)]
#   - Permissions (step 11): TenantContext.permissions is wired; PermissionGuard.require(code) throws
#     403 PERMISSION_DENIED when the permission is absent. payment.collect is guarded by
#     require("payment.collect"); order.settle by require("order.settle"), and both /orders/{id}/confirm and
#     /orders/{id}/settle are guarded. Hence the assertion: a low-privilege collect / settle both -> 403 PERMISSION_DENIED.
#   - Tenant isolation (step 12): TenantLineInnerInterceptor appends the tenant filter to the tenant_id column,
#     so a cross-tenant selectById returns null. The read path uses selectTenantIdById
#     (@InterceptorIgnore(tenantLine="true") skips tenant interception) to distinguish "absent" from "cross-tenant":
#     when the id hits but tenant_id is not the current tenant -> ApiException(403, TENANT_SCOPE_DENIED);
#     otherwise 404 ORDER_NOT_FOUND.
#   - Cash reconciliation (step 10): a CASH collection goes through CollectApplicationService.recordCash, writing
#     pay_intent + pay_transaction (provider=CASH, SUCCEEDED); /admin/reconciliations/summary aggregates
#     pay_transaction grouped by provider, and the assertion is that the CASH row grossAmount delta is 5000
#     (amounts agree). Shift handover sets pay_shift.difference_amount = actual_cash - expected_cash, but
#     expected_cash does not accumulate collections (closeShift is not wired to collect), so
#     "difference_amount=0" cannot be asserted through reconciliation (recorded as a GAP).
param(
  [string]$GatewayUrl = 'http://127.0.0.1:3002',
  [string]$Authz = '',          # optional: an existing logged-in Bearer (business services do not verify Authorization, only pass it through)
  [switch]$SkipLogin = $false,  # skip register/login (reuse an existing account)
  [string]$AccountId = ''       # existing account ID used with SkipLogin (paired with -Authz for an existing Bearer)
)

$ErrorActionPreference = 'Continue'
$Base = $GatewayUrl.TrimEnd('/') + '/api/v1'
$script:failed = 0
$script:gaps = @()

function Step([string]$Name) { Write-Host ''; Write-Host ('== ' + $Name + ' ==') -ForegroundColor Cyan }

$headers = @{ 'Content-Type' = 'application/json' }
if (-not [string]::IsNullOrWhiteSpace($Authz)) { $headers['Authorization'] = "Bearer $Authz" }

function Assert([bool]$cond, [string]$msg) {
  if ($cond) { Write-Host ('  PASS ' + $msg) -ForegroundColor Green }
  else { $script:failed++; Write-Host ('  FAIL ' + $msg) -ForegroundColor Red }
}

# Record a skeleton gap (not wired yet, but asserted against real behaviour; does not count as FAIL)
function Note-Gap([string]$msg) {
  $script:gaps += $msg
  Write-Host ('  [GAP] ' + $msg) -ForegroundColor DarkYellow
}

# Unwrap the unified response {data,requestId}; with no data return the whole body
function Data([object]$o) {
  if ($null -eq $o) { return $null }
  if ($o.PSObject.Properties['data']) { return $o.data }
  return $o
}

function Invoke-Json {
  param([string]$Method, [string]$Path, [object]$Body = $null, [hashtable]$ExtraHeaders = @{})
  try {
    $h = @{}
    foreach ($k in $headers.Keys) { $h[$k] = $headers[$k] }
    foreach ($k in $ExtraHeaders.Keys) { $h[$k] = $ExtraHeaders[$k] }
    $params = @{ Method = $Method; Uri = ($Base + $Path); Headers = $h; UseBasicParsing = $true }
    if ($null -ne $Body) { $params.ContentType = 'application/json'; $params.Body = ($Body | ConvertTo-Json -Compress -Depth 8) }
    $resp = Invoke-WebRequest @params
    Write-Host ("[HTTP " + [int]$resp.StatusCode + "] " + $Method + ' ' + $Path) -ForegroundColor Green
    if ($resp.Content) { return ($resp.Content | ConvertFrom-Json) }
    return $null
  } catch {
    Write-Host ("[HTTP ERR] " + $Method + ' ' + $Path) -ForegroundColor Red
    if ($_.Exception.Response) {
      $code = [int]$_.Exception.Response.StatusCode
      Write-Host ('  status=' + $code)
      $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
      Write-Host ('  body=' + $sr.ReadToEnd())
    } else { Write-Host ('  err=' + $_.Exception.Message) }
    return $null
  }
}

# Raw call: returns @{ status; body; ok } for negative assertions (does not throw)
function Invoke-Raw {
  param([string]$Method, [string]$Path, [object]$Body = $null, [hashtable]$ExtraHeaders = @{})
  try {
    $h = @{}
    foreach ($k in $headers.Keys) { $h[$k] = $headers[$k] }
    foreach ($k in $ExtraHeaders.Keys) { $h[$k] = $ExtraHeaders[$k] }
    $params = @{ Method = $Method; Uri = ($Base + $Path); Headers = $h; UseBasicParsing = $true }
    if ($null -ne $Body) { $params.ContentType = 'application/json'; $params.Body = ($Body | ConvertTo-Json -Compress -Depth 8) }
    $resp = Invoke-WebRequest @params
    $bodyObj = $null
    if ($resp.Content) { try { $bodyObj = ($resp.Content | ConvertFrom-Json) } catch { $bodyObj = $resp.Content } }
    Write-Host ("[HTTP " + [int]$resp.StatusCode + "] " + $Method + ' ' + $Path) -ForegroundColor Green
    return @{ status = [int]$resp.StatusCode; body = $bodyObj; ok = $true }
  } catch {
    $code = $null; $bodyObj = $null
    if ($_.Exception.Response) {
      $code = [int]$_.Exception.Response.StatusCode
      try {
        $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
        $raw = $sr.ReadToEnd()
        if ($raw) { try { $bodyObj = ($raw | ConvertFrom-Json) } catch { $bodyObj = $raw } }
      } catch {}
    }
    Write-Host ("[HTTP " + $code + "] " + $Method + ' ' + $Path) -ForegroundColor DarkYellow
    return @{ status = $code; body = $bodyObj; ok = $false }
  }
}

# Build the X-Tenant-Context JSON header (TenantContextFilter parses it as JSON and expects a permissions array)
function Set-TenantContext([long]$tenantId, [long]$accountId, $organizationId, [long]$storeId, [string[]]$permissions = @()) {
  $ctx = @{ tenantId = $tenantId; organizationId = $organizationId; storeId = $storeId; accountId = $accountId; authorizationVersion = 1; permissions = @($permissions) }
  $json = $ctx | ConvertTo-Json -Compress
  $headers['X-Tenant-Context'] = $json
  Write-Host ('  X-Tenant-Context=' + $json) -ForegroundColor DarkGray
}

# Query the reconciliation summary (payment /admin/reconciliations/summary, reads SUCCEEDED pay_transaction rows)
function Get-ReconSummary([long]$tenantId, [datetime]$from, [datetime]$to) {
  $fromS = [System.Uri]::EscapeDataString($from.ToString('yyyy-MM-ddTHH:mm:ss'))
  $toS = [System.Uri]::EscapeDataString($to.ToString('yyyy-MM-ddTHH:mm:ss'))
  return Invoke-Json 'GET' ("/admin/reconciliations/summary?tenantId=$tenantId&from=$fromS&to=$toS")
}

# ------------------------------------------------------------------ login chain
Step '0. SaaS login chain (identity /identity/** + /auth/**)'
$accountId = $null
$jwt = $null
if (-not $SkipLogin) {
  $loginIdentifier = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999).ToString()
  $cred = @{ loginType = 'phone'; loginIdentifier = $loginIdentifier; credential = 'Smoke@1234' }

  $reg = Invoke-Json 'POST' '/identity/accounts' $cred
  $regId = (Data $reg).id
  Write-Host ('  registered account.id=' + $regId + '  loginIdentifier=' + $loginIdentifier)

  $login = Invoke-Json 'POST' '/identity/login' $cred
  $accountId = (Data $login).accountId
  $jwt = (Data $login).accessToken
  Assert ($null -ne $accountId -and -not [string]::IsNullOrWhiteSpace($jwt)) 'login returns accountId + signed JWT accessToken'
  if (-not [string]::IsNullOrWhiteSpace($jwt)) { $headers['Authorization'] = "Bearer $jwt" }
} else {
  $accountId = $AccountId
  $jwt = $Authz
}

# Real context-selection chain (/auth/contexts + /auth/context/select need Authorization: Bearer; with no IAM
# user-role the items array is empty; select falls back to an opaque token)
$contexts = Invoke-Json 'GET' '/auth/contexts'
$items = $null
if ($null -ne $contexts) { $items = (Data $contexts).items }
$contextId = $null; $tenantFromCtx = $null
if ($null -ne $items -and $items.Count -gt 0) {
  $contextId = $items[0].contextId
  $tenantFromCtx = $items[0].tenantId
  Write-Host ('  available contexts: ' + $items.Count + ', first contextId=' + $contextId + ' tenantId=' + $tenantFromCtx)
} else {
  Write-Host '  available contexts are empty (no IAM user-role assigned; contexts are aggregated by tenant-service /internal/iam)' -ForegroundColor DarkYellow
}
if ($null -ne $accountId) {
  $sel = Invoke-Json 'POST' '/auth/context/select' @{ contextId = $contextId }
  $selData = (Data $sel)
  $tc = $selData.tenantContextToken
  Write-Host ('  select.tenantContextToken=' + $(if ($tc) { $tc.Substring(0, [Math]::Min(24, $tc.Length)) + '...' } else { '<null>' }))
  Write-Host '  note: that token is a JWT/opaque placeholder while business services parse X-Tenant-Context as JSON, so Set-TenantContext builds the JSON header below' -ForegroundColor DarkGray
}

# ------------------------------------------------------------------ tenant init
Step '1. Tenant init (idempotent)'
$tenant = Invoke-Json 'POST' '/admin/platform/tenants' @{ tenantCode = 'e2e-smoke'; name = 'E2E smoke tenant'; defaultLocale = 'zh-CN'; defaultTimezone = 'Asia/Shanghai' }
$tenantId = (Data $tenant).id
Assert ($null -ne $tenantId) 'tenant.id is not null'
$storeId = 1   # the skeleton exposes no store/organization creation endpoint, so storeId is a placeholder of 1
Set-TenantContext -tenantId $tenantId -accountId $accountId -organizationId 1 -storeId $storeId -permissions @('payment.collect','order.settle')

# ------------------------------------------------------------------ resource
Step '2. Create a resource (KTV_ROOM A01)'
$room = Invoke-Json 'POST' '/admin/resources' @{ tenantId = $tenantId; storeId = $storeId; resourceType = 'KTV_ROOM'; resourceCode = 'A01'; name = 'A01 room'; capacity = 8 }
$roomId = (Data $room).id
Assert ($null -ne $roomId) 'room.id is not null'

# ------------------------------------------------------------------ open table (order)
Step '3. Quick open table (create order + RESERVED session)'
$order = Invoke-Json 'POST' '/business/orders' @{ tenantId = $tenantId; organizationId = 1; storeId = $storeId; businessType = 'KTV'; currencyCode = 'CNY'; resourceId = $roomId }
$orderId = (Data $order).id
$sessionId = (Data $order).sessionId
Assert ($null -ne $orderId) 'order.id is not null'
Assert ($null -ne $sessionId) 'createOrder returns sessionId (ord_ktv_session was created synchronously)'

# ------------------------------------------------------------------ session (real chain: open -> timing -> assign server -> close)
Step '4. Open table (session RESERVED -> OPEN)'
$session = Invoke-Json 'POST' ("/business/ktv/sessions/$sessionId/open") @{ freeWaitMinutes = 0 }
Assert ((Data $session).status -eq 'OPEN') 'after opening, session.status=OPEN'

Step '5. Timing (pause / resume)'
$paused = Invoke-Json 'POST' ("/business/ktv/sessions/$sessionId/pause")
Assert ((Data $paused).status -eq 'PAUSED') 'after pausing, session.status=PAUSED'
$resumed = Invoke-Json 'POST' ("/business/ktv/sessions/$sessionId/resume")
Assert ((Data $resumed).status -eq 'OPEN') 'after resuming, session.status=OPEN'

Step '6. Assign a server (S01)'
$serverRes = Invoke-Json 'POST' '/admin/resources' @{ tenantId = $tenantId; storeId = $storeId; resourceType = 'KTV_SERVER'; resourceCode = 'S01'; name = 'S01 server'; capacity = 1 }
$serverResourceId = (Data $serverRes).id
$server = Invoke-Json 'POST' ("/business/orders/$orderId/servers") @{ tenantId = $tenantId; ktvSessionId = $sessionId; serverResourceId = $serverResourceId; catalogItemId = 1 }
Assert ($null -ne (Data $server)) 'assign-server response is not null'

Step '7. Close table (session OPEN -> CLOSED)'
$closed = Invoke-Json 'POST' ("/business/ktv/sessions/$sessionId/close")
Assert ((Data $closed).status -eq 'CLOSED') 'after closing, session.status=CLOSED'

# ------------------------------------------------------------------ combined collection (real implementation, field names corrected)
Step '8. Combined collection (cash, real implementation; Idempotency-Key + X-Tenant-Context + payment.collect permission required)'
# Cash reconciliation baseline (before collect): used by step 10 to assert the cash amount landed in pay_transaction (delta=5000)
$baseNow = (Get-Date).ToUniversalTime()
$reconBefore = Get-ReconSummary -tenantId $tenantId -from ($baseNow.AddMinutes(-30)) -to ($baseNow.AddMinutes(30))
$grossBefore = 0
if ($null -ne (Data $reconBefore) -and $null -ne (Data $reconBefore).total) { $grossBefore = [decimal](Data $reconBefore).total.grossAmount }
Write-Host ('  reconciliation baseline total.grossAmount=' + $grossBefore) -ForegroundColor DarkGray

$collectBody = @{
  customerId = $null; currencyCode = 'CNY'; payable = 5000
  payments = @( @{ method = 'CASH'; amount = 5000 } )
}
$idemKey = 'e2e-ktv-smoke-' + [guid]::NewGuid().ToString()
$collect = Invoke-Json 'POST' ("/business/orders/$orderId/collect") $collectBody @{ 'Idempotency-Key' = $idemKey }
Assert ($null -ne (Data $collect)) 'combined-collection response is not null (CASH is available by default; POINT/WALLET need a member customerId plus internal customer endpoints)'

# ------------------------------------------------------------------ daily closing (real implementation, field names corrected)
Step '9. Daily closing (submit)'
$closing = Invoke-Json 'POST' '/admin/daily-closings/1/submit' @{ tenantId = $tenantId; storeId = $storeId; businessDate = '2025-01-01'; submittedBy = $accountId }
Assert ((Data $closing).status -eq 'SUBMITTED') 'daily closing status=SUBMITTED'

# ------------------------------------------------------------------ cash reconciliation (real implementation: cash goes through pay_transaction)
Step '10. Cash reconciliation (query the reconciliation summary after collection and assert the CASH amount agrees)'
$now = (Get-Date).ToUniversalTime()
$reconAfter = Get-ReconSummary -tenantId $tenantId -from ($now.AddMinutes(-30)) -to ($now.AddMinutes(30))
$reconData = (Data $reconAfter)
$totalLine = $null
if ($null -ne $reconData) { $totalLine = $reconData.total }
$grossAfter = 0
if ($null -ne $totalLine) { $grossAfter = [decimal]$totalLine.grossAmount }
$cashLine = $null
if ($null -ne $reconData) {
  $cashLine = @($reconData.lines) | Where-Object { $_.provider -eq 'CASH' } | Select-Object -First 1
}
$delta = $grossAfter - $grossBefore
Assert (($delta -eq 5000)) ("cash collection of 5000 lands in pay_transaction (reconciliation summary gross delta=" + $delta + ")")
Assert ($null -ne $cashLine) 'the reconciliation summary has a CASH provider row (provider=CASH)'
if ($null -ne $cashLine) {
  Assert (([decimal]$cashLine.netAmount -eq [decimal]$cashLine.grossAmount)) ('CASH has no fee so netAmount==grossAmount (' + $cashLine.grossAmount + ')')
}
Note-Gap 'shift-handover reconciliation difference_amount=0 is not wired in the skeleton: pay_shift.expected_cash does not accumulate collections (closeShift difference=actualCash-expectedCash=actualCash-0), so the amount agreement is asserted from the pay_transaction provider summary'

# ------------------------------------------------------------------ permission isolation (low-privilege context)
Step '11. Permission isolation (low-privilege context calling order.settle / payment.collect)'
# Conclusion (from reading the code): TenantContext.permissions is wired; PermissionGuard.require throws
#   403 PERMISSION_DENIED when the permission is missing. payment.collect is guarded by
#   require("payment.collect") and order.settle by require("order.settle") (both confirm and settle are guarded).
$waiterAccountId = 900001   # placeholder "waiter" account: permissions are empty (low privilege)
Set-TenantContext -tenantId $tenantId -accountId $waiterAccountId -organizationId 1 -storeId $storeId -permissions @()

# payment.collect: low-privilege context (no payment.collect permission) -> expect 403 PERMISSION_DENIED
$collectLow = Invoke-Raw 'POST' ("/business/orders/$orderId/collect") @{ customerId = $null; currencyCode = 'CNY'; payable = 0; payments = @() } @{ 'Idempotency-Key' = 'e2e-perm-low-' + [guid]::NewGuid().ToString() }
$collectLowCode = $null
if ($null -ne $collectLow.body -and $collectLow.body.PSObject.Properties['code']) { $collectLowCode = $collectLow.body.code }
Write-Host ('  low-privilege collect -> HTTP ' + $collectLow.status + '  code=' + $(if ($collectLowCode) { $collectLowCode } else { '<none>' }))
Assert (($collectLow.status -eq 403 -and $collectLowCode -eq 'PERMISSION_DENIED')) 'low-privilege collect returns 403 PERMISSION_DENIED (PermissionGuard genuinely rejects)'

# order.settle: low-privilege context (no order.settle permission) -> expect 403 PERMISSION_DENIED
$settleLow = Invoke-Raw 'POST' ("/business/orders/$orderId/settle") @{ expectedVersion = 0 }
$settleLowCode = $null
if ($null -ne $settleLow.body -and $settleLow.body.PSObject.Properties['code']) { $settleLowCode = $settleLow.body.code }
Write-Host ('  low-privilege settle -> HTTP ' + $settleLow.status + '  code=' + $(if ($settleLowCode) { $settleLowCode } else { '<none>' }))
Assert (($settleLow.status -eq 403 -and $settleLowCode -eq 'PERMISSION_DENIED')) 'low-privilege settle returns 403 PERMISSION_DENIED (PermissionGuard genuinely rejects)'

# Restore tenant A's primary context
Set-TenantContext -tenantId $tenantId -accountId $accountId -organizationId 1 -storeId $storeId -permissions @('payment.collect','order.settle')

# ------------------------------------------------------------------ tenant isolation
Step '12. Tenant isolation (tenant A context accessing a tenant B order)'
# Conclusion (from reading the code): TenantLineInnerInterceptor appends the tenant filter to tenant_id, so a
#   cross-tenant selectById returns null; the read path uses selectTenantIdById (which skips tenant interception)
#   to distinguish: the id hits but tenant_id is not the current tenant -> 403 TENANT_SCOPE_DENIED.
$tenantB = Invoke-Json 'POST' '/admin/platform/tenants' @{ tenantCode = 'e2e-smoke-b'; name = 'E2E smoke tenant B'; defaultLocale = 'zh-CN'; defaultTimezone = 'Asia/Shanghai' }
$tenantBId = (Data $tenantB).id
Assert ($null -ne $tenantBId) 'tenantB.id is not null'

Set-TenantContext -tenantId $tenantBId -accountId $accountId -organizationId 1 -storeId $storeId -permissions @('payment.collect','order.settle')
$orderB = Invoke-Json 'POST' '/business/orders' @{ tenantId = $tenantBId; organizationId = 1; storeId = $storeId; businessType = 'KTV'; currencyCode = 'CNY'; resourceId = $null }
$orderBId = (Data $orderB).id
Assert ($null -ne $orderBId) 'tenant B order was created'

# Switch back to tenant A and settle tenant B's order (selectById is filtered to tenant A so it returns null,
# while selectTenantIdById hits -> 403)
Set-TenantContext -tenantId $tenantId -accountId $accountId -organizationId 1 -storeId $storeId -permissions @('payment.collect','order.settle')
$crossTenant = Invoke-Raw 'POST' ("/business/orders/$orderBId/settle") @{ expectedVersion = 0 }
$crossCode = $null
if ($null -ne $crossTenant.body -and $crossTenant.body.PSObject.Properties['code']) { $crossCode = $crossTenant.body.code }
Write-Host ('  tenant A settling tenant B order -> HTTP ' + $crossTenant.status + '  code=' + $(if ($crossCode) { $crossCode } else { '<none>' }))
Assert (($crossTenant.status -eq 403 -and $crossCode -eq 'TENANT_SCOPE_DENIED')) 'cross-tenant access returns 403 TENANT_SCOPE_DENIED (selectTenantIdById distinguishes cross-tenant)'

# ------------------------------------------------------------------ summary
Write-Host ''
if ($script:gaps.Count -gt 0) {
  Write-Host 'Recorded skeleton gaps (not wired yet but asserted against real behaviour; not counted as FAIL):' -ForegroundColor DarkYellow
  foreach ($g in $script:gaps) { Write-Host ('  - ' + $g) -ForegroundColor DarkYellow }
  Write-Host ''
}
if ($script:failed -gt 0) { Write-Host ('E2E-KTV-SMOKE finished with ' + $script:failed + ' failing assertion(s).') -ForegroundColor Red; exit 1 }
else { Write-Host 'E2E-KTV-SMOKE fully passed (open -> timing -> assign server -> close -> collect -> daily closing -> reconciliation -> permission/tenant isolation all asserted for real).' -ForegroundColor Green; exit 0 }
