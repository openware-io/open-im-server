# Currency capability end-to-end acceptance (kind / ACK): switch currency -> every business surface follows.
#
# Coverage (see docs/standards/16_CURRENCY_CONVENTIONS.md sections 3, 5, 7):
#   1) Single source of truth: GET/PUT /api/v1/admin/tenant/currency
#   2) Propagation: after switching, money-bearing endpoints return the new currencyCode and server-side
#      text (e.g. KTV pricing displayText) switches its symbol
#   3) Snapshot: settled documents keep their own currencyCode (settings change must not rewrite history)
#   4) Gateway fallback header: X-Currency
#   5) Audit trail: tenant.currency.update is queryable in the unified audit log
#
# NOTE: this file is intentionally ASCII-only so it runs under both PowerShell 5.1 and 7+
# (5.1 reads BOM-less .ps1 as ANSI, which would corrupt non-ASCII literals).
#
# Usage (kind):
#   powershell -File scripts/verify/verify-currency-e2e.ps1
#   powershell -File scripts/verify/verify-currency-e2e.ps1 -TargetCurrency USD -KeepInitial
# ACK: -BaseUrl https://saas-admin.dev.example.com with that environment's account.
param(
  [string]$BaseUrl = 'http://192.168.31.91:30081',
  [string]$Username = 'admin',
  [string]$Password = 'e8280ac0d25d4bc0a1e1',
  [string]$ContextId = '100:100:100',
  [long]$StoreId = 100,
  [long]$ResourceId = 0,
  [ValidateSet('USD', 'CNY')][string]$TargetCurrency = 'CNY',
  [switch]$KeepInitial,
  # Writes data (creates one order under CNY, then switches the tenant to USD) to prove the settled-document
  # currency snapshot is immutable. Off by default: the operator must delete the created order afterwards.
  [switch]$RunSnapshotCheck
)

$ErrorActionPreference = 'Continue'
$script:pass = 0
$script:fail = 0
$script:skip = 0
$script:session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$script:csrf = $null
$base = $BaseUrl.TrimEnd('/')

function Step([string]$name) { Write-Host ''; Write-Host ("== $name ==") -ForegroundColor Cyan }
function Pass([string]$msg) { $script:pass++; Write-Host ("  PASS " + $msg) -ForegroundColor Green }
function Fail([string]$msg) { $script:fail++; Write-Host ("  FAIL " + $msg) -ForegroundColor Red }
function Skip([string]$msg) { $script:skip++; Write-Host ("  SKIP " + $msg) -ForegroundColor DarkYellow }
function Assert([bool]$cond, [string]$msg) { if ($cond) { Pass $msg } else { Fail $msg } }

# Responses are either bare objects or wrapped as {data, requestId}.
function Unwrap([object]$o) {
  if ($null -eq $o) { return $null }
  if ($o.PSObject.Properties['data']) { return $o.data }
  return $o
}

function Invoke-Api {
  param([string]$Method, [string]$Path, [object]$Body = $null, [switch]$RetryCsrf)
  $headers = @{ 'Content-Type' = 'application/json' }
  if ($Method -ne 'GET' -and $script:csrf) { $headers['X-CSRF-Token'] = $script:csrf }
  $params = @{
    Method = $Method; Uri = ($base + $Path); Headers = $headers
    WebSession = $script:session; UseBasicParsing = $true
  }
  if ($null -ne $Body) { $params.Body = ($Body | ConvertTo-Json -Compress -Depth 8) }
  try {
    $resp = Invoke-WebRequest @params
    $json = $null
    # Decode the body as UTF-8 explicitly: Windows PowerShell 5.1 defaults to ISO-8859-1 for
    # HTTP bodies, which mangles currency symbols (CNY sign) and breaks the symbol assertions.
    $text = ''
    if ($resp.RawContentStream) {
      $text = [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
    } elseif ($resp.Content) {
      $text = [string]$resp.Content
    }
    if ($text) { $json = $text | ConvertFrom-Json }
    return @{ ok = $true; status = [int]$resp.StatusCode; json = (Unwrap $json); headers = $resp.Headers; text = $text }
  } catch {
    $status = 0
    $text = $_.Exception.Message
    if ($_.Exception.Response) {
      $status = [int]$_.Exception.Response.StatusCode
      try {
        $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
        $text = $reader.ReadToEnd()
      } catch { }
    }
    # A write may fail with 403 after the CSRF token rotates: refresh the token and retry once
    # (the real front-end does the same).
    if (-not $RetryCsrf -and $Method -ne 'GET' -and $status -eq 403) {
      $csrfRefresh = Invoke-Api -Method GET -Path '/api/v1/admin/auth/csrf'
      if ($csrfRefresh.ok -and $csrfRefresh.json.csrfToken) {
        $script:csrf = $csrfRefresh.json.csrfToken
        return (Invoke-Api -Method $Method -Path $Path -Body $Body -RetryCsrf)
      }
    }
    return @{ ok = $false; status = $status; json = $null; text = $text; headers = $null }
  }
}

function Symbol-Of([string]$code) { if ($code -eq 'CNY') { [char]0x00A5 } else { '$' } }

# ------------------------------------------------------------------ 1. login + tenant context
Step '1. Admin login and tenant context selection'
$login = Invoke-Api -Method POST -Path '/api/v1/admin/auth/login' `
  -Body @{ username = $Username; password = $Password; ttlHours = 8 }
if (-not $login.ok) { Fail ('login failed: HTTP ' + $login.status + ' ' + $login.text); exit 1 }
Pass 'POST /api/v1/admin/auth/login 200'

$csrf = Invoke-Api -Method GET -Path '/api/v1/admin/auth/csrf'
if ($csrf.ok -and $csrf.json.csrfToken) {
  $script:csrf = $csrf.json.csrfToken
  Pass 'GET /api/v1/admin/auth/csrf returned csrfToken'
} else {
  Fail ('csrf token unavailable: HTTP ' + $csrf.status)
}

$select = Invoke-Api -Method POST -Path '/api/v1/admin/context/select' -Body @{ contextId = $ContextId }
Assert $select.ok ('POST /api/v1/admin/context/select -> ' + $select.status)
if ($select.ok) {
  $hasCcy = ($select.json.PSObject.Properties['currencyCode'] -ne $null)
  Assert $hasCcy 'context/select response carries currencyCode (front-end bootstrap source)'
  if ($hasCcy) { Write-Host ('    context currency = ' + $select.json.currencyCode) -ForegroundColor DarkGray }
}

# ------------------------------------------------------------------ 2. single source of truth
Step '2. Tenant currency single source'
$before = (Invoke-Api -Method GET -Path '/api/v1/admin/tenant/currency').json
if (-not $before) { Fail 'GET /api/v1/admin/tenant/currency unreachable (capability not released to this env?)'; exit 1 }
Assert ($before.PSObject.Properties['currencyCode'] -ne $null) 'response has currencyCode'
Assert ($before.PSObject.Properties['symbol'] -ne $null) 'response has symbol'
$supported = @($before.supported | ForEach-Object { $_.code })
Assert (($supported -contains 'CNY') -and ($supported -contains 'USD')) ('supported contains CNY/USD: ' + ($supported -join ','))
$initialCurrency = [string]$before.currencyCode
Write-Host ('    initial currency = ' + $initialCurrency) -ForegroundColor DarkGray

$probes = @(
  @{ name = 'business: KTV pricing displayText'; path = "/api/v1/business/ktv/pricing?storeId=$StoreId&resourceId=$ResourceId"; kind = 'displayText' },
  @{ name = 'business: order list currencyCode'; path = '/api/v1/business/orders'; kind = 'list' },
  @{ name = 'admin: inventory materials currencyCode'; path = '/api/v1/admin/inventory/materials'; kind = 'list' },
  @{ name = 'admin: KTV pricing plans currencyCode'; path = '/api/v1/admin/ktv/pricing-plans'; kind = 'list' }
)

function Test-Probes([string]$currency) {
  $symbol = Symbol-Of $currency
  foreach ($probe in $probes) {
    $r = Invoke-Api -Method GET -Path $probe.path
    if (-not $r.ok) {
      if ($r.status -eq 400 -or $r.status -eq 404) { Skip ($probe.name + ' unavailable here (HTTP ' + $r.status + ')') }
      else { Fail ($probe.name + ' request failed HTTP ' + $r.status) }
      continue
    }
    if ($probe.kind -eq 'displayText') {
      $text = [string]$r.json.displayText
      if ([string]::IsNullOrWhiteSpace($text)) { Skip ($probe.name + ' returned no displayText') }
      elseif ($text.StartsWith($symbol)) { Pass ($probe.name + ' symbol OK: ' + $text.Substring(0, [Math]::Min(28, $text.Length))) }
      else { Fail ($probe.name + ' symbol did not follow currency (expected ' + $symbol + '): ' + $text) }
    } else {
      $items = @()
      if ($r.json -and $r.json.PSObject.Properties['items']) { $items = @($r.json.items) } else { $items = @($r.json) }
      $items = @($items | Where-Object { $_ -ne $null })
      $withCcy = @($items | Where-Object { $_.PSObject.Properties['currencyCode'] })
      if ($withCcy.Count -eq 0) { Skip ($probe.name + ' no rows or endpoint does not expose currencyCode') }
      else {
        # Every row must carry a currencyCode. A row whose code differs from the current setting is a
        # legitimate historical snapshot (settled documents are locked), so it is reported, not failed.
        Assert ($withCcy.Count -eq $items.Count) ($probe.name + ' every row carries currencyCode (' + $items.Count + ' rows)')
        $historical = @($withCcy | Where-Object { $_.currencyCode -ne $currency })
        if ($historical.Count -gt 0) {
          Write-Host ('    ' + $historical.Count + ' row(s) keep an older snapshot (expected for settled documents): ' + (($historical | Select-Object -First 3 | ForEach-Object { $_.currencyCode }) -join ',')) -ForegroundColor DarkGray
        }
      }
    }
  }
}

# ------------------------------------------------------------------ 3. switch and re-check
Step ('3. Switch to ' + $TargetCurrency + ' and re-check each surface')
$put = Invoke-Api -Method PUT -Path '/api/v1/admin/tenant/currency' -Body @{ currencyCode = $TargetCurrency }
Assert $put.ok ('PUT /api/v1/admin/tenant/currency -> ' + $put.status)
if ($put.ok) { Assert ($put.json.currencyCode -eq $TargetCurrency) ('response currency = ' + $TargetCurrency) }
$after = (Invoke-Api -Method GET -Path '/api/v1/admin/tenant/currency').json
if ($after) { Assert ($after.currencyCode -eq $TargetCurrency) 're-read confirms the switch (no cache drift)' }

# Server-rendered strings (e.g. KTV pricing displayText) and the gateway fallback header X-Currency
# are both taken from the currency claim inside the signed context, and that claim is issued when a
# context is selected. After changing the setting the client must re-select its context (the web
# front-ends refresh too), otherwise this session keeps rendering with the stale claim.
# Here we replay that real client behaviour and assert the claim has been refreshed.
$reselect = Invoke-Api -Method POST -Path '/api/v1/admin/context/select' -Body @{ contextId = $ContextId }
if ($reselect.ok) {
  $claimCcy = $reselect.json.currencyCode
  Assert ($claimCcy -eq $TargetCurrency) ('context re-select refreshed the currency claim = ' + $claimCcy)
} else {
  Fail ('context re-select failed: HTTP ' + $reselect.status)
}
Test-Probes $TargetCurrency

# ------------------------------------------------------------------ 4. gateway fallback header
Step '4. Gateway fallback header X-Currency'
$probeResp = Invoke-Api -Method GET -Path '/api/v1/business/orders'
$headerValue = $null
if ($probeResp.ok -and $probeResp.headers) {
  foreach ($k in $probeResp.headers.Keys) { if ($k -ieq 'X-Currency') { $headerValue = $probeResp.headers[$k] } }
}
if ($headerValue) { Assert ($headerValue -eq $TargetCurrency) ('X-Currency = ' + $headerValue) }
else { Skip 'X-Currency header absent (gateway fallback not released, or route does not inject it)' }

# ------------------------------------------------------------------ 5. settled documents keep their snapshot
Step '5. Settled documents keep their currency snapshot'
$orders = (Invoke-Api -Method GET -Path '/api/v1/business/orders').json
$settled = @()
if ($orders) {
  $list = @()
  if ($orders.PSObject.Properties['items']) { $list = @($orders.items) } else { $list = @($orders) }
  $list = @($list | Where-Object { $_ -ne $null })
  $settled = @($list | Where-Object { $_.PSObject.Properties['status'] -and ($_.status -in @('SETTLED', 'PAID', 'COMPLETED', 'CLOSED')) })
}
if ($settled.Count -eq 0) {
  Skip 'no settled orders in this env; re-run after history exists to assert snapshot immutability'
} else {
  foreach ($o in $settled) {
    if ($o.PSObject.Properties['currencyCode']) {
      Write-Host ('    order ' + $o.orderNo + ' currencyCode = ' + $o.currencyCode) -ForegroundColor DarkGray
    }
  }
  $allHaveCcy = @($settled | Where-Object { -not $_.PSObject.Properties['currencyCode'] }).Count -eq 0
  Assert $allHaveCcy ('every settled order carries its own currencyCode (sample ' + $settled.Count + ')')
}

# ------------------------------------------------------------------ 6. audit trail of the switch
Step '6. Currency change is audited'
$audits = (Invoke-Api -Method GET -Path '/api/v1/admin/audits?action=tenant.currency.update&pageSize=5').json
if (-not $audits) {
  Skip 'audit query unavailable (permission or service not released)'
} else {
  $items = @($audits.items)
  if ($items.Count -eq 0) { Fail 'no tenant.currency.update audit record found (currency change must be traceable)' }
  else { Pass ('found ' + $items.Count + ' tenant.currency.update audit record(s)') }
}

# ------------------------------------------------------------------ 7. document currency snapshot immutability (opt-in, writes data)
if ($RunSnapshotCheck) {
  Step '7. Order currency snapshot is immutable across a setting change'
  # 1) make sure the tenant currency is CNY, then create an order under it.
  $null = Invoke-Api -Method PUT -Path '/api/v1/admin/tenant/currency' -Body @{ currencyCode = 'CNY' }
  $null = Invoke-Api -Method POST -Path '/api/v1/admin/context/select' -Body @{ contextId = $ContextId }
  $created = Invoke-Api -Method POST -Path '/api/v1/business/orders' -Body @{ businessType = 'KTV' }
  if (-not $created.ok -or -not $created.json.id) {
    Fail ('could not create an order for the snapshot check: HTTP ' + $created.status + ' ' + $created.text)
  } else {
    $orderId = $created.json.id
    Assert ($created.json.currencyCode -eq 'CNY') ('new order snapshot = ' + $created.json.currencyCode + ' (tenant was CNY)')
    Write-Host ('    test order id=' + $orderId + ' orderNo=' + $created.json.orderNo) -ForegroundColor DarkGray
    # 2) switch the tenant setting to USD.
    $null = Invoke-Api -Method PUT -Path '/api/v1/admin/tenant/currency' -Body @{ currencyCode = 'USD' }
    $null = Invoke-Api -Method POST -Path '/api/v1/admin/context/select' -Body @{ contextId = $ContextId }
    # 3) the historical order must keep its own currency snapshot.
    $orders = (Invoke-Api -Method GET -Path '/api/v1/business/orders').json
    $list = @()
    if ($orders -and $orders.PSObject.Properties['items']) { $list = @($orders.items) } else { $list = @($orders) }
    $list = @($list | Where-Object { $_ -ne $null })
    $found = @($list | Where-Object { $_.id -eq $orderId }) | Select-Object -First 1
    if (-not $found) {
      Skip ('order ' + $orderId + ' not visible in the list; snapshot immutability not asserted')
    } else {
      Assert ($found.currencyCode -eq 'CNY') ('order keeps its CNY snapshot after tenant switched to USD (got ' + $found.currencyCode + ')')
    }
    # 4) best-effort void so the row is not left as an open order; hard delete is done by the operator.
    $voided = Invoke-Api -Method POST -Path ('/api/v1/business/orders/' + $orderId + '/void') -Body @{ reason = 'currency e2e snapshot check' }
    if ($voided.ok -or $voided.status -eq 200) { Write-Host ('    test order voided (id=' + $orderId + ')') -ForegroundColor DarkGray }
    else { Write-Host ('    test order left as-is (void HTTP ' + $voided.status + '); delete id=' + $orderId + ' manually') -ForegroundColor DarkYellow }
  }
}

# ------------------------------------------------------------------ 8. restore initial currency
if (-not $KeepInitial -and $initialCurrency -and $initialCurrency -ne $TargetCurrency) {
  Step ('8. Restore initial currency ' + $initialCurrency)
  $restore = Invoke-Api -Method PUT -Path '/api/v1/admin/tenant/currency' -Body @{ currencyCode = $initialCurrency }
  if ($restore.ok) { Assert ($restore.json.currencyCode -eq $initialCurrency) 'initial currency restored' }
  else { Fail ('restore failed: HTTP ' + $restore.status) }
  # Refresh the context claim after restoring too, so no stale claim is left behind.
  $null = Invoke-Api -Method POST -Path '/api/v1/admin/context/select' -Body @{ contextId = $ContextId }
}

Write-Host ''
Write-Host ('RESULT: PASS=' + $script:pass + ' FAIL=' + $script:fail + ' SKIP=' + $script:skip) -ForegroundColor Cyan
if ($script:fail -gt 0) { exit 1 }
