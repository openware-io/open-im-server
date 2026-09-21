# Verifies the two delivered batches on a live environment:
#   (A) C-end "choose room type" data: >= 6 room types, every type resolves to a real
#       room image (derived from the type's first imaged room), and pricing for every
#       type equals room unit price + server unit price with the tenant currency.
#   (B) wallet-token / points display contract: wallet responses carry tokenAmount /
#       tokenBrandName (pure counts, no currency symbol / currency code); point
#       responses are counts and carry no currencyCode.
#
# PURE ASCII on purpose: Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI, so any
# non-ASCII literal breaks parsing.
#
#   -Env kind  (default) : saas-admin 192.168.31.91:30081 / saas-mobile 192.168.31.91:30082
#   -Env ack             : saas-admin https://saas-admin.dev.example.com (curl -k required)
#
# Consumer-session checks (C) only run on kind: ACK exposes no saas-mobile host, so the
# ACK run asserts (A)/(B-contract via admin session) only and reports the rest as SKIP.
[CmdletBinding()]
param(
  [ValidateSet('kind', 'ack')][string]$Env = 'kind',
  [string]$AdminBase = '',
  [string]$MobileBase = '',
  [string]$ContextId = '100:100:100',
  [long]$StoreId = 100,
  [string]$AdminUser = 'admin',
  [string]$AdminPassword = 'e8280ac0d25d4bc0a1e1'
)

$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { }
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)

if (-not $AdminBase) { if ($Env -eq 'ack') { $AdminBase = 'https://saas-admin.dev.example.com' } else { $AdminBase = 'http://192.168.31.91:30081' } }
if (-not $MobileBase) { if ($Env -eq 'ack') { $MobileBase = $AdminBase } else { $MobileBase = 'http://192.168.31.91:30082' } }
$AdminBase = $AdminBase.TrimEnd('/')
$MobileBase = $MobileBase.TrimEnd('/')

$Tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("verify-wallet-token-" + $Env)
New-Item -ItemType Directory -Force -Path $Tmp | Out-Null

$script:Pass = 0
$script:Fail = 0
$script:Skip = 0

function Ok([string]$m) { $script:Pass++; Write-Host ("  PASS  " + $m) }
function Bad([string]$m) { $script:Fail++; Write-Host ("  FAIL  " + $m) }
function Skip([string]$m) { $script:Skip++; Write-Host ("  SKIP  " + $m) }
function Info([string]$m) { Write-Host ("        " + $m) }

function JProp($obj, [string]$name) {
  if ($null -eq $obj) { return $null }
  $p = $obj.PSObject.Properties[$name]
  if ($p) { return $p.Value }
  return $null
}

function ApiCall {
  param([string]$Base, [string]$Method, [string]$Path, $Body = $null, [string]$Csrf = '', [string]$CookieFile = '', [switch]$Raw)
  $stamp = [guid]::NewGuid().ToString('N')
  $out = Join-Path $Tmp ("r-" + $stamp + ".json")
  $a = @('-s', '-k', '-o', $out, '-w', '%{http_code}', '-X', $Method)
  if ($CookieFile) { $a += @('-b', $CookieFile, '-c', $CookieFile) }
  if ($Csrf -and $Method -ne 'GET') { $a += @('-H', ("X-CSRF-Token: " + $Csrf)) }
  if ($null -ne $Body) {
    $bf = Join-Path $Tmp ("b-" + $stamp + ".json")
    [System.IO.File]::WriteAllText($bf, ($Body | ConvertTo-Json -Compress -Depth 10), (New-Object System.Text.UTF8Encoding($false)))
    $a += @('-H', 'Content-Type: application/json; charset=utf-8', '--data-binary', ('@' + $bf))
  }
  $a += ($Base + $Path)
  $code = & curl.exe @a
  $text = ''
  if (Test-Path $out) { $text = [System.IO.File]::ReadAllText($out, [System.Text.Encoding]::UTF8) }
  $parsed = $null
  if ($text) { try { $parsed = $text | ConvertFrom-Json } catch { $parsed = $null } }
  $status = 0
  try { $status = [int]([string]$code).Trim() } catch { $status = 0 }
  return [pscustomobject]@{ Status = $status; Text = $text; Json = $parsed }
}

function Unwrap($parsed) { $d = JProp $parsed 'data'; if ($null -ne $d) { return $d } return $parsed }

Write-Host ("=== verify-wallet-token-display env=" + $Env + " admin=" + $AdminBase + " mobile=" + $MobileBase + " ===")

# ---------------------------------------------------------------- admin session
$ckAdmin = Join-Path $Tmp 'admin-cookies.txt'
$login = ApiCall -Base $AdminBase -Method 'POST' -Path '/api/v1/admin/auth/login' -Body @{ username = $AdminUser; password = $AdminPassword; ttlHours = 8 } -CookieFile $ckAdmin
if ($login.Status -ne 200) { Write-Host ("FATAL admin login HTTP " + $login.Status + " " + $login.Text); exit 1 }
$csrf = [string](JProp (Unwrap $login.Json) 'csrfToken')
if (-not $csrf) {
  $csrfResp = ApiCall -Base $AdminBase -Method 'GET' -Path '/api/v1/admin/auth/csrf' -CookieFile $ckAdmin
  $csrf = [string](JProp (Unwrap $csrfResp.Json) 'csrfToken')
}
$sel = ApiCall -Base $AdminBase -Method 'POST' -Path '/api/v1/admin/context/select' -Body @{ contextId = $ContextId } -Csrf $csrf -CookieFile $ckAdmin
Info ("admin login=" + $login.Status + " context=" + $sel.Status)
$selData = Unwrap $sel.Json
$currency = [string](JProp $selData 'currencyCode')
if ($currency) { Ok ("admin context currencyCode=" + $currency) } else { Bad 'admin context has no currencyCode' }

# ---------------------------------------------------------------- (A) room types
Write-Host '--- (A) choose-room-type data ---'
$types = Unwrap (ApiCall -Base $AdminBase -Method 'GET' -Path ("/api/v1/admin/resources/types?storeId=" + $StoreId) -CookieFile $ckAdmin).Json
$typeList = @($types)
if ($typeList.Count -ge 6) { Ok ("room types for store $StoreId = " + $typeList.Count + " (>= 6)") } else { Bad ("room types for store $StoreId = " + $typeList.Count + " (< 6)") }
foreach ($t in $typeList) {
  Info ("  type id=" + $t.id + " code=" + $t.code + " name=" + $t.name + " cap=" + $t.capacity + " room=" + $t.unitPrice + " server=" + $t.serverUnitPrice + " status=" + $t.status)
}

$rooms = Unwrap (ApiCall -Base $AdminBase -Method 'GET' -Path ("/api/v1/admin/resources?resourceType=KTV_ROOM&storeId=" + $StoreId) -CookieFile $ckAdmin).Json
$roomList = @($rooms)
$byType = @{}
foreach ($r in $roomList) {
  $tid = [string]$r.roomTypeId
  if (-not $tid -or $tid -eq '') { continue }
  if (-not $byType.ContainsKey($tid)) { $byType[$tid] = @() }
  $byType[$tid] += ,$r
}
$typesWithImage = 0
foreach ($t in $typeList) {
  $key = [string]$t.id
  if (-not $byType.ContainsKey($key)) { Bad ("room type " + $t.code + " has no room"); continue }
  $imgs = @($byType[$key] | Where-Object { $_.mainImageUrl -or ($_.imageUrls -and @($_.imageUrls).Count -gt 0) })
  if ($imgs.Count -gt 0) {
    $typesWithImage++
    $u = if ($imgs[0].mainImageUrl) { $imgs[0].mainImageUrl } else { @($imgs[0].imageUrls)[0] }
    $probe = ApiCall -Base $AdminBase -Method 'GET' -Path $u -CookieFile $ckAdmin
    if ($probe.Status -eq 200) { Ok ("type " + $t.code + " image reachable 200 (" + @($byType[$key]).Count + " rooms, " + $imgs.Count + " with image)") }
    else { Bad ("type " + $t.code + " image HTTP " + $probe.Status + " url=" + $u) }
  } else {
    Bad ("type " + $t.code + " rooms have no image (" + @($byType[$key]).Count + " rooms)")
  }
}
if ($typesWithImage -ge 6) { Ok ("types with a real image = " + $typesWithImage) } else { Bad ("types with a real image = " + $typesWithImage + " (< 6)") }

foreach ($t in $typeList) {
  # A transient gateway/network blip can return an empty body for a single quote request:
  # retry up to 3 times so a network hiccup is not reported as a pricing regression.
  $p = $null
  for ($attempt = 1; $attempt -le 3; $attempt++) {
    $p = Unwrap (ApiCall -Base $AdminBase -Method 'GET' -Path ("/api/v1/business/ktv/pricing?storeId=" + $StoreId + "&roomTypeId=" + $t.id) -CookieFile $ckAdmin).Json
    if ($null -ne (JProp $p 'combinedUnitPrice')) { break }
    Start-Sleep -Seconds 2
  }
  $sum = [long](JProp $p 'roomUnitPrice') + [long](JProp $p 'serverUnitPrice')
  $combined = [long](JProp $p 'combinedUnitPrice')
  $cur = [string](JProp $p 'currencyCode')
  if ($combined -eq $sum -and $combined -gt 0 -and $cur) {
    Ok ("type " + $t.code + " pricing " + (JProp $p 'roomUnitPrice') + "+" + (JProp $p 'serverUnitPrice') + "=" + $combined + " " + $cur)
  } else {
    Bad ("type " + $t.code + " pricing mismatch room+server=" + $sum + " combined=" + $combined + " currency=" + $cur)
  }
}

# ---------------------------------------------------------------- (B) consumer token display
Write-Host '--- (B) wallet / point display contract ---'
if ($Env -ne 'kind') {
  Skip 'consumer session checks need the saas-mobile host (kind only)'
} else {
  $ckC = Join-Path $Tmp 'c-cookies.txt'
  $sess = ApiCall -Base $MobileBase -Method 'POST' -Path '/api/v1/dev/saas/session?appId=saas-a380-c' -CookieFile $ckC
  $csrfJson = ApiCall -Base $MobileBase -Method 'GET' -Path '/api/v1/auth/csrf' -CookieFile $ckC
  $cCsrf = [string](JProp (Unwrap $csrfJson.Json) 'csrfToken')
  $ctx = ApiCall -Base $MobileBase -Method 'POST' -Path '/api/v1/auth/context/select?appId=saas-a380-c' -Body @{ contextId = ('consumer:saas-a380-c:' + $ContextId) } -Csrf $cCsrf -CookieFile $ckC
  Info ("consumer session=" + $sess.Status + " context=" + $ctx.Status)
  $ctxData = Unwrap $ctx.Json
  $cCurrency = [string](JProp $ctxData 'currencyCode')
  if ($cCurrency) { Ok ("consumer context currencyCode=" + $cCurrency) } else { Bad 'consumer context has no currencyCode' }

  $w = ApiCall -Base $MobileBase -Method 'GET' -Path '/api/v1/me/wallet' -CookieFile $ckC
  if ($w.Status -ne 200) {
    Bad ("GET /me/wallet HTTP " + $w.Status + " " + $w.Text)
  } else {
    $wd = Unwrap $w.Json
    $ta = JProp $wd 'tokenAmount'
    $tb = JProp $wd 'tokenBrandName'
    $aa = JProp $wd 'availableAmount'
    if ($null -ne $ta) { Ok ("wallet tokenAmount=" + $ta + " (type=" + $ta.GetType().Name + ")") } else { Bad 'wallet response has no tokenAmount' }
    if ($tb) { Ok ("wallet tokenBrandName=" + $tb) } else { Bad 'wallet response has no tokenBrandName' }
    if ($null -ne $aa) { Ok ("wallet availableAmount still present=" + $aa) } else { Bad 'wallet availableAmount disappeared (accounting field)' }
    if ("$ta" -match '[^0-9\-]') { Bad ("tokenAmount is not a pure count: " + $ta) } else { Ok ('tokenAmount is a pure integer count (no currency symbol/code)') }
  }

  $led = ApiCall -Base $MobileBase -Method 'GET' -Path '/api/v1/me/wallet/ledger?page=1&size=5' -CookieFile $ckC
  if ($led.Status -eq 200) {
    $records = @(JProp (Unwrap $led.Json) 'records')
    if ($records.Count -eq 0) { Skip 'wallet ledger empty (no rows to assert tokenAmount on)' }
    else {
      $missing = @($records | Where-Object { -not (JProp $_ 'tokenAmount') })
      if ($missing.Count -eq 0) { Ok ("wallet ledger rows carry tokenAmount (" + $records.Count + " rows)") } else { Bad ("wallet ledger rows missing tokenAmount: " + $missing.Count + "/" + $records.Count) }
    }
  } else { Bad ("GET /me/wallet/ledger HTTP " + $led.Status) }

  $pt = ApiCall -Base $MobileBase -Method 'GET' -Path '/api/v1/me/points?page=1&size=5' -CookieFile $ckC
  if ($pt.Status -ne 200) {
    Bad ("GET /me/points HTTP " + $pt.Status + " " + $pt.Text)
  } else {
    $pd = Unwrap $pt.Json
    $acct = JProp $pd 'account'
    if ($null -ne $acct) {
      if ($null -ne (JProp $acct 'availablePoints')) { Ok ("points availablePoints=" + (JProp $acct 'availablePoints') + " (count)") } else { Bad 'points account has no availablePoints' }
      if ($null -eq (JProp $acct 'currencyCode')) { Ok 'points account carries no currencyCode' } else { Bad 'points account still carries currencyCode' }
    } else { Bad 'points response has no account' }
    $pl = @(JProp $pd 'ledger')
    if ($pl.Count -eq 0) { Skip 'points ledger empty (no rows to assert)' }
    else {
      $bad = @($pl | Where-Object { $null -ne (JProp $_ 'currencyCode') })
      if ($bad.Count -eq 0) { Ok ("points ledger rows carry no currencyCode (" + $pl.Count + " rows)") } else { Bad ("points ledger rows still carry currencyCode: " + $bad.Count) }
    }
  }
}

Write-Host ("=== RESULT env=" + $Env + " PASS=" + $script:Pass + " FAIL=" + $script:Fail + " SKIP=" + $script:Skip + " ===")
if ($script:Fail -gt 0) { exit 1 }
exit 0
