# KTV smoke through the real H5 session chain (standards/43).
#
# Flow: dev SaaS session -> CSRF -> select tenant context -> gateway Cookie session
#       -> existing KTV room -> create order -> open/pause/resume/close.
# The gateway turns the Cookie session into a signed X-Tenant-Context; this script never forges
# a tenant header and never uses /admin/** for test setup.
#
# Local Kind only: /dev/saas/session is disabled outside saas.dev-session.enabled=true.
[CmdletBinding()]
param(
  [string]$GatewayUrl = 'http://127.0.0.1:30080',
  [string]$AppId = 'saas-a380-h5',
  [string]$ContextId = '100:100:100',
  [long]$StoreId = 100,
  [long]$TenantId = 100
)

$ErrorActionPreference = 'Stop'
$Base = $GatewayUrl.TrimEnd('/') + '/api/v1'
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$failed = 0

function Assert([bool]$cond, [string]$msg) {
  if ($cond) { Write-Host "PASS $msg" -ForegroundColor Green }
  else { $script:failed++; Write-Host "FAIL $msg" -ForegroundColor Red }
}

function Invoke-Api([string]$Method, [string]$Path, [object]$Body = $null, [hashtable]$ExtraHeaders = @{}) {
  $headers = @{}
  foreach ($k in $ExtraHeaders.Keys) { $headers[$k] = $ExtraHeaders[$k] }
  $params = @{ Method = $Method; Uri = ($Base + $Path); WebSession = $script:session; Headers = $headers; UseBasicParsing = $true }
  if ($null -ne $Body) {
    $params.ContentType = 'application/json'
    $params.Body = ($Body | ConvertTo-Json -Compress -Depth 8)
  }
  try {
    $resp = Invoke-WebRequest @params
    Write-Host "[HTTP $([int]$resp.StatusCode)] $Method $Path"
    if ($resp.Content) { return ($resp.Content | ConvertFrom-Json) }
    return $null
  } catch {
    $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    Write-Host "[HTTP $code] $Method $Path" -ForegroundColor Red
    if ($_.Exception.Response) {
      $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
      Write-Host $reader.ReadToEnd()
    }
    throw
  }
}

Write-Host '== 0. H5 session + tenant context =='
$created = Invoke-Api 'POST' "/dev/saas/session?appId=$AppId" @{}
Assert ([bool]$created.authenticated) 'dev SaaS session created'
$csrf = Invoke-Api 'GET' "/auth/csrf?appId=$AppId"
Assert (-not [string]::IsNullOrWhiteSpace($csrf.csrfToken)) 'CSRF token issued'
$selected = Invoke-Api 'POST' "/auth/context/select?appId=$AppId" @{ contextId = $ContextId } @{ 'X-CSRF-Token' = $csrf.csrfToken }
Assert ([bool]$selected.selected) 'tenant context selected'
Assert ($selected.storeId -eq $StoreId) "selected storeId=$StoreId"
$currency = if ($selected.currencyCode) { $selected.currencyCode } else { 'USD' }

Write-Host '== 1. Reuse a seeded KTV room =='
$resources = Invoke-Api 'GET' "/business/resources?tenantId=$TenantId&storeId=$StoreId&resourceType=KTV_ROOM"
$room = @($resources) | Where-Object { $_.resourceType -eq 'KTV_ROOM' -or $_.resourceCode } | Select-Object -First 1
Assert ($null -ne $room) 'seeded KTV room found'
$roomId = $room.id

Write-Host '== 2. Create order + open =='
$order = Invoke-Api 'POST' '/business/orders' @{
  tenantId = $TenantId; organizationId = 100; storeId = $StoreId; businessType = 'KTV'
  currencyCode = $currency; resourceId = $roomId
}
Assert ($null -ne $order.id) 'order created'
Assert ($null -ne $order.sessionId) 'order returned sessionId'
$orderId = $order.id
$sessionId = $order.sessionId
$opened = Invoke-Api 'POST' "/business/ktv/sessions/$sessionId/open" @{ freeWaitMinutes = 0 }
Assert ($opened.status -eq 'OPEN') 'session opened'

Write-Host '== 3. Pause / resume / close =='
$paused = Invoke-Api 'POST' "/business/ktv/sessions/$sessionId/pause" $null
Assert ($paused.status -eq 'PAUSED') 'session paused'
$resumed = Invoke-Api 'POST' "/business/ktv/sessions/$sessionId/resume" $null
Assert ($resumed.status -eq 'OPEN') 'session resumed'
$closed = Invoke-Api 'POST' "/business/ktv/sessions/$sessionId/close" $null
Assert ($closed.status -eq 'CLOSED') 'session closed'

Write-Host ''
if ($failed -gt 0) { throw "$failed assertion(s) failed" }
Write-Host 'KTV H5 session smoke passed.' -ForegroundColor Green
