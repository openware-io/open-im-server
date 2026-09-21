# E8 预发布：KTV 全链路 E2E 冒烟脚本（登录链路 + 租户上下文 + 真实 DTO 字段 + 真实会话链路）
# 覆盖：登录链路 -> 租户初始化 -> 资源 -> 开台(订单) -> 计时 -> 点服务人员 -> 结台 -> 组合收款 -> 日结
#       -> 现金对账 -> 权限隔离 -> 租户隔离
#
# 【登录链路结论（identity 服务，网关 /api/v1/**）】
#   POST /api/v1/identity/accounts   注册  body {loginType, loginIdentifier, credential} -> 账号 {id}（无 JWT）
#   POST /api/v1/identity/login       登录  body {loginType, loginIdentifier, credential} -> {accountId, accessToken(JWT), expiresInSeconds}
#   GET  /api/v1/auth/contexts        header Authorization: Bearer <accessToken> -> {items:[{contextId,tenantId,organizationId,storeId,roles,scopeType}]}
#   POST /api/v1/auth/context/select  header Authorization: Bearer <accessToken> + body {contextId} -> {tenantContextToken(JWT/不透明占位), tenantId, ...}
#   ★ 骨架不一致：select 返回的是 JWT/不透明 token，但各业务服务 TenantContextFilter 把 X-Tenant-Context 当 JSON 解析
#     （{tenantId,organizationId,storeId,accountId,authorizationVersion,permissions}），故冒烟直接构造 JSON 上下文头。
#   ★ 多租户表 INSERT/SELECT 由 TenantLineInnerInterceptor 强制要求 X-Tenant-Context（缺失抛「租户上下文缺失」）。
#
# 【权限/租户隔离 + 现金对账真实结论（读代码确认，非占位）】
#   - 权限（步骤 11）：TenantContext.permissions 已接入，PermissionGuard.require(code) 缺权限抛 403 PERMISSION_DENIED。
#     payment.collect 挂 require("payment.collect")；order.settle 挂 require("order.settle")，/orders/{id}/confirm 与
#     /orders/{id}/settle 均受守卫。故断言：低角色 collect / settle 均 → 403 PERMISSION_DENIED。
#   - 租户隔离（步骤 12）：TenantLineInnerInterceptor 对 tenant_id 列自动追加租户过滤，跨租户 selectById 返回 null。
#     读路径按 selectTenantIdById（@InterceptorIgnore(tenantLine="true") 跳过租户拦截）区分「不存在」vs「跨租户」：
#     id 命中但 tenant_id 非当前租户 → ApiException(403, TENANT_SCOPE_DENIED)；否则 404 ORDER_NOT_FOUND。
#   - 现金对账（步骤 10）：CASH 收款走 CollectApplicationService.recordCash 写 pay_intent + pay_transaction
#     (provider=CASH, SUCCEEDED)；/admin/reconciliations/summary 汇总 pay_transaction 按 provider 分组，
#     断言 CASH 行 grossAmount 增量=5000（金额一致）。交班 pay_shift.difference_amount = actual_cash - expected_cash，
#     但 expected_cash 不随收款累计（closeShift 未接 collect）→「difference_amount=0」无法经对账断言（记录 GAP）。
param(
  [string]$GatewayUrl = 'http://127.0.0.1:3002',
  [string]$Authz = '',          # 可选：已登录 Bearer（骨架各业务服务不校验 Authorization，仅透传占位）
  [switch]$SkipLogin = $false,  # 跳过注册/登录（改用已有账号）
  [string]$AccountId = ''        # SkipLogin 时使用的已有账号 ID（配合 -Authz 传入已有 Bearer）
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

# 记录骨架缺口（未接通但已按真实行为断言；不计入 FAIL）
function Note-Gap([string]$msg) {
  $script:gaps += $msg
  Write-Host ('  [GAP] ' + $msg) -ForegroundColor DarkYellow
}

# 解包统一响应 {data,requestId}；无 data 时返回整个 body
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

# 原始调用：返回 @{ status; body; ok }，用于负面断言（不抛异常）
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

# 构造 X-Tenant-Context JSON 头（TenantContextFilter 按 JSON 解析：含 permissions 数组）
function Set-TenantContext([long]$tenantId, [long]$accountId, $organizationId, [long]$storeId, [string[]]$permissions = @()) {
  $ctx = @{ tenantId = $tenantId; organizationId = $organizationId; storeId = $storeId; accountId = $accountId; authorizationVersion = 1; permissions = @($permissions) }
  $json = $ctx | ConvertTo-Json -Compress
  $headers['X-Tenant-Context'] = $json
  Write-Host ('  X-Tenant-Context=' + $json) -ForegroundColor DarkGray
}

# 查询对账摘要（payment /admin/reconciliations/summary，读 pay_transaction SUCCEEDED）
function Get-ReconSummary([long]$tenantId, [datetime]$from, [datetime]$to) {
  $fromS = [System.Uri]::EscapeDataString($from.ToString('yyyy-MM-ddTHH:mm:ss'))
  $toS = [System.Uri]::EscapeDataString($to.ToString('yyyy-MM-ddTHH:mm:ss'))
  return Invoke-Json 'GET' ("/admin/reconciliations/summary?tenantId=$tenantId&from=$fromS&to=$toS")
}

# ------------------------------------------------------------------ 登录链路
Step '0. SaaS 登录链路（identity /identity/** + /auth/**）'
$accountId = $null
$jwt = $null
if (-not $SkipLogin) {
  $loginIdentifier = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999).ToString()
  $cred = @{ loginType = 'phone'; loginIdentifier = $loginIdentifier; credential = 'Smoke@1234' }

  $reg = Invoke-Json 'POST' '/identity/accounts' $cred
  $regId = (Data $reg).id
  Write-Host ('  注册 account.id=' + $regId + '  loginIdentifier=' + $loginIdentifier)

  $login = Invoke-Json 'POST' '/identity/login' $cred
  $accountId = (Data $login).accountId
  $jwt = (Data $login).accessToken
  Assert ($null -ne $accountId -and -not [string]::IsNullOrWhiteSpace($jwt)) '登录返回 accountId + 签名 JWT accessToken'
  if (-not [string]::IsNullOrWhiteSpace($jwt)) { $headers['Authorization'] = "Bearer $jwt" }
} else {
  $accountId = $AccountId
  $jwt = $Authz
}

# 真实上下文选择链路（/auth/contexts + /auth/context/select 需 Authorization: Bearer；无 IAM user-role 时 items 为空；select 回退不透明 token）
$contexts = Invoke-Json 'GET' '/auth/contexts'
$items = $null
if ($null -ne $contexts) { $items = (Data $contexts).items }
$contextId = $null; $tenantFromCtx = $null
if ($null -ne $items -and $items.Count -gt 0) {
  $contextId = $items[0].contextId
  $tenantFromCtx = $items[0].tenantId
  Write-Host ('  可用上下文 ' + $items.Count + ' 个，首个 contextId=' + $contextId + ' tenantId=' + $tenantFromCtx)
} else {
  Write-Host '  可用上下文为空（未分配 IAM user-role；contexts 由 tenant-service /internal/iam 聚合）' -ForegroundColor DarkYellow
}
if ($null -ne $accountId) {
  $sel = Invoke-Json 'POST' '/auth/context/select' @{ contextId = $contextId }
  $selData = (Data $sel)
  $tc = $selData.tenantContextToken
  Write-Host ('  select.tenantContextToken=' + $(if ($tc) { $tc.Substring(0, [Math]::Min(24, $tc.Length)) + '...' } else { '<null>' }))
  Write-Host '  注：该 token 为 JWT/不透明占位，业务服务按 JSON 解析 X-Tenant-Context，故下方用 Set-TenantContext 构造 JSON 头' -ForegroundColor DarkGray
}

# ------------------------------------------------------------------ 租户初始化
Step '1. 租户初始化（幂等）'
$tenant = Invoke-Json 'POST' '/admin/platform/tenants' @{ tenantCode = 'e2e-smoke'; name = 'E2E冒烟租户'; defaultLocale = 'zh-CN'; defaultTimezone = 'Asia/Shanghai' }
$tenantId = (Data $tenant).id
Assert ($null -ne $tenantId) 'tenant.id 非空'
$storeId = 1   # 骨架无门店/组织创建端点，storeId 占位为 1
Set-TenantContext -tenantId $tenantId -accountId $accountId -organizationId 1 -storeId $storeId -permissions @('payment.collect','order.settle')

# ------------------------------------------------------------------ 资源
Step '2. 创建资源（KTV_ROOM A01）'
$room = Invoke-Json 'POST' '/admin/resources' @{ tenantId = $tenantId; storeId = $storeId; resourceType = 'KTV_ROOM'; resourceCode = 'A01'; name = 'A01包厢'; capacity = 8 }
$roomId = (Data $room).id
Assert ($null -ne $roomId) 'room.id 非空'

# ------------------------------------------------------------------ 开台（订单）
Step '3. 快速开台（创建订单 + RESERVED 会话）'
$order = Invoke-Json 'POST' '/business/orders' @{ tenantId = $tenantId; organizationId = 1; storeId = $storeId; businessType = 'KTV'; currencyCode = 'CNY'; resourceId = $roomId }
$orderId = (Data $order).id
$sessionId = (Data $order).sessionId
Assert ($null -ne $orderId) 'order.id 非空'
Assert ($null -ne $sessionId) 'createOrder 返回 sessionId（已同步创建 ord_ktv_session）'

# ------------------------------------------------------------------ 会话（真实链路：开台→计时→点服务人员→结台）
Step '4. 开台（session RESERVED → OPEN）'
$session = Invoke-Json 'POST' ("/business/ktv/sessions/$sessionId/open") @{ freeWaitMinutes = 0 }
Assert ((Data $session).status -eq 'OPEN') '开台后 session.status=OPEN'

Step '5. 计时（暂停/恢复）'
$paused = Invoke-Json 'POST' ("/business/ktv/sessions/$sessionId/pause")
Assert ((Data $paused).status -eq 'PAUSED') '暂停后 session.status=PAUSED'
$resumed = Invoke-Json 'POST' ("/business/ktv/sessions/$sessionId/resume")
Assert ((Data $resumed).status -eq 'OPEN') '恢复后 session.status=OPEN'

Step '6. 点服务人员（S01）'
$serverRes = Invoke-Json 'POST' '/admin/resources' @{ tenantId = $tenantId; storeId = $storeId; resourceType = 'KTV_SERVER'; resourceCode = 'S01'; name = 'S01服务'; capacity = 1 }
$serverResourceId = (Data $serverRes).id
$server = Invoke-Json 'POST' ("/business/orders/$orderId/servers") @{ tenantId = $tenantId; ktvSessionId = $sessionId; serverResourceId = $serverResourceId; catalogItemId = 1 }
Assert ($null -ne (Data $server)) '点服务人员响应非空'

Step '7. 结台（session OPEN → CLOSED）'
$closed = Invoke-Json 'POST' ("/business/ktv/sessions/$sessionId/close")
Assert ((Data $closed).status -eq 'CLOSED') '结台后 session.status=CLOSED'

# ------------------------------------------------------------------ 组合收款（真实实现，字段已修正）
Step '8. 组合收款（现金，真实实现；Idempotency-Key + X-Tenant-Context + payment.collect 权限必需）'
# 现金对账基线（collect 前）：用于步骤 10 断言 cash 落 pay_transaction 金额一致（增量=5000）
$baseNow = (Get-Date).ToUniversalTime()
$reconBefore = Get-ReconSummary -tenantId $tenantId -from ($baseNow.AddMinutes(-30)) -to ($baseNow.AddMinutes(30))
$grossBefore = 0
if ($null -ne (Data $reconBefore) -and $null -ne (Data $reconBefore).total) { $grossBefore = [decimal](Data $reconBefore).total.grossAmount }
Write-Host ('  对账基线 total.grossAmount=' + $grossBefore) -ForegroundColor DarkGray

$collectBody = @{
  customerId = $null; currencyCode = 'CNY'; payable = 5000
  payments = @( @{ method = 'CASH'; amount = 5000 } )
}
$idemKey = 'e2e-ktv-smoke-' + [guid]::NewGuid().ToString()
$collect = Invoke-Json 'POST' ("/business/orders/$orderId/collect") $collectBody @{ 'Idempotency-Key' = $idemKey }
Assert ($null -ne (Data $collect)) '组合收款响应非空（CASH 渠道默认可用；POINT/WALLET 需会员 customerId + customer 内部端点）'

# ------------------------------------------------------------------ 日结（真实实现，字段已修正）
Step '9. 日结（submit）'
$closing = Invoke-Json 'POST' '/admin/daily-closings/1/submit' @{ tenantId = $tenantId; storeId = $storeId; businessDate = '2025-01-01'; submittedBy = $accountId }
Assert ((Data $closing).status -eq 'SUBMITTED') '日结 status=SUBMITTED'

# ------------------------------------------------------------------ 现金对账（真实实现：cash 走 pay_transaction）
Step '10. 现金对账（组合收款后查 reconciliation summary，断言 CASH 金额一致）'
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
Assert (($delta -eq 5000)) ("现金收款 5000 落入 pay_transaction（对账摘要 gross 增量=" + $delta + "）")
Assert ($null -ne $cashLine) '对账摘要存在 CASH 渠道行（provider=CASH）'
if ($null -ne $cashLine) {
  Assert (([decimal]$cashLine.netAmount -eq [decimal]$cashLine.grossAmount)) ('CASH 无手续费 netAmount==grossAmount（' + $cashLine.grossAmount + '）')
}
Note-Gap '交班对账 difference_amount=0 骨架未接通：pay_shift.expected_cash 不随收款累计（closeShift difference=actualCash-expectedCash=actualCash-0），故按 pay_transaction 渠道摘要断言金额一致'

# ------------------------------------------------------------------ 权限隔离（低角色上下文）
Step '11. 权限隔离（低角色上下文调 order.settle / payment.collect）'
# 结论（读代码）：TenantContext.permissions 已接入；PermissionGuard.require 缺权限抛 403 PERMISSION_DENIED。
#   payment.collect 挂 require("payment.collect")；order.settle 挂 require("order.settle")（confirm 与 settle 均受守卫）。
$waiterAccountId = 900001   # 占位「服务员」账号：permissions 为空（低角色）
Set-TenantContext -tenantId $tenantId -accountId $waiterAccountId -organizationId 1 -storeId $storeId -permissions @()

# payment.collect：低角色上下文（无 payment.collect 权限）→ 应 403 PERMISSION_DENIED
$collectLow = Invoke-Raw 'POST' ("/business/orders/$orderId/collect") @{ customerId = $null; currencyCode = 'CNY'; payable = 0; payments = @() } @{ 'Idempotency-Key' = 'e2e-perm-low-' + [guid]::NewGuid().ToString() }
$collectLowCode = $null
if ($null -ne $collectLow.body -and $collectLow.body.PSObject.Properties['code']) { $collectLowCode = $collectLow.body.code }
Write-Host ('  低角色 collect → HTTP ' + $collectLow.status + '  code=' + $(if ($collectLowCode) { $collectLowCode } else { '<none>' }))
Assert (($collectLow.status -eq 403 -and $collectLowCode -eq 'PERMISSION_DENIED')) '低角色 collect 返回 403 PERMISSION_DENIED（PermissionGuard 真实拒绝）'

# order.settle：低角色上下文（无 order.settle 权限）→ 应 403 PERMISSION_DENIED
$settleLow = Invoke-Raw 'POST' ("/business/orders/$orderId/settle") @{ expectedVersion = 0 }
$settleLowCode = $null
if ($null -ne $settleLow.body -and $settleLow.body.PSObject.Properties['code']) { $settleLowCode = $settleLow.body.code }
Write-Host ('  低角色 settle → HTTP ' + $settleLow.status + '  code=' + $(if ($settleLowCode) { $settleLowCode } else { '<none>' }))
Assert (($settleLow.status -eq 403 -and $settleLowCode -eq 'PERMISSION_DENIED')) '低角色 settle 返回 403 PERMISSION_DENIED（PermissionGuard 真实拒绝）'

# 恢复租户 A 主上下文
Set-TenantContext -tenantId $tenantId -accountId $accountId -organizationId 1 -storeId $storeId -permissions @('payment.collect','order.settle')

# ------------------------------------------------------------------ 租户隔离
Step '12. 租户隔离（租户 A 上下文访问租户 B 订单）'
# 结论（读代码）：TenantLineInnerInterceptor 对 tenant_id 列自动追加租户过滤，跨租户 selectById 返回 null；
#   读路径按 selectTenantIdById（跳过租户拦截）区分：id 命中但 tenant_id 非当前租户 → 403 TENANT_SCOPE_DENIED。
$tenantB = Invoke-Json 'POST' '/admin/platform/tenants' @{ tenantCode = 'e2e-smoke-b'; name = 'E2E冒烟租户B'; defaultLocale = 'zh-CN'; defaultTimezone = 'Asia/Shanghai' }
$tenantBId = (Data $tenantB).id
Assert ($null -ne $tenantBId) 'tenantB.id 非空'

Set-TenantContext -tenantId $tenantBId -accountId $accountId -organizationId 1 -storeId $storeId -permissions @('payment.collect','order.settle')
$orderB = Invoke-Json 'POST' '/business/orders' @{ tenantId = $tenantBId; organizationId = 1; storeId = $storeId; businessType = 'KTV'; currencyCode = 'CNY'; resourceId = $null }
$orderBId = (Data $orderB).id
Assert ($null -ne $orderBId) '租户 B 订单创建成功'

# 切回租户 A 上下文，settle 租户 B 订单（selectById 被 tenant_id=A 过滤为 null，selectTenantIdById 命中 → 403）
Set-TenantContext -tenantId $tenantId -accountId $accountId -organizationId 1 -storeId $storeId -permissions @('payment.collect','order.settle')
$crossTenant = Invoke-Raw 'POST' ("/business/orders/$orderBId/settle") @{ expectedVersion = 0 }
$crossCode = $null
if ($null -ne $crossTenant.body -and $crossTenant.body.PSObject.Properties['code']) { $crossCode = $crossTenant.body.code }
Write-Host ('  租户 A settle 租户 B 订单 → HTTP ' + $crossTenant.status + '  code=' + $(if ($crossCode) { $crossCode } else { '<none>' }))
Assert (($crossTenant.status -eq 403 -and $crossCode -eq 'TENANT_SCOPE_DENIED')) '跨租户访问返回 403 TENANT_SCOPE_DENIED（selectTenantIdById 区分跨租户）'

# ------------------------------------------------------------------ 汇总
Write-Host ''
if ($script:gaps.Count -gt 0) {
  Write-Host '记录的骨架缺口（未接通但已按真实行为断言，不计入 FAIL）：' -ForegroundColor DarkYellow
  foreach ($g in $script:gaps) { Write-Host ('  - ' + $g) -ForegroundColor DarkYellow }
  Write-Host ''
}
if ($script:failed -gt 0) { Write-Host ('E2E-KTV-SMOKE 完成，失败 ' + $script:failed + ' 项。') -ForegroundColor Red; exit 1 }
else { Write-Host 'E2E-KTV-SMOKE 全部通过（开台→计时→点服务人员→结台→收款→日结→对账→权限/租户隔离为真实断言）。' -ForegroundColor Green; exit 0 }
