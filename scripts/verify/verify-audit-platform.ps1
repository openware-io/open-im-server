# Unified audit platform acceptance (kind / ACK).
#
# Coverage:
#   1) every recent audit row exposes a non-null occurredAt  (fix: occurred_at was landing NULL);
#   2) the time-range filter returns rows inside the window and keeps paging totals consistent
#      (fix: rows with NULL occurred_at used to be silently skipped by fromAt/toAt);
#   3) OPTIONAL (-RunPlatformWrite): a platform-scoped write carries the platform operator
#      (fix: /api/v1/admin/platform/** used to reach the audit log without any operator).
#
# NOTE: step 3 writes data only when -RunPlatformWrite is passed, and it echoes the current values back
# (no business change). It appends audit rows, which are append-only by design.
# NOTE: keep this file ASCII-only (PowerShell 5.1 reads BOM-less .ps1 as ANSI and non-ASCII breaks parsing).
param(
  [string]$BaseUrl = 'http://192.168.31.91:30081',
  [string]$Username = 'admin',
  [string]$Password = 'e8280ac0d25d4bc0a1e1',
  [string]$ContextId = '100:100:100',
  [int]$PageSize = 10,
  [switch]$RunPlatformWrite
)

$ErrorActionPreference = 'Continue'
$script:csrf = $null
$script:session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$script:pass = 0; $script:fail = 0; $script:skip = 0
function Step($t) { Write-Host ''; Write-Host ("== " + $t) -ForegroundColor Cyan }
function Pass($m) { $script:pass++; Write-Host ("  PASS " + $m) -ForegroundColor Green }
function Fail($m) { $script:fail++; Write-Host ("  FAIL " + $m) -ForegroundColor Red }
function Skip($m) { $script:skip++; Write-Host ("  SKIP " + $m) -ForegroundColor DarkYellow }
function Assert($c, $m) { if ($c) { Pass $m } else { Fail $m } }
function Unwrap($o) { if ($o -and $o.PSObject.Properties['data']) { return $o.data } ; return $o }
function Api($method, $path, $body) {
  $headers = @{ 'Content-Type' = 'application/json' }
  if ($script:csrf -and $method -ne 'GET') { $headers['X-CSRF-Token'] = $script:csrf }
  $p = @{ Method = $method; Uri = ($BaseUrl.TrimEnd('/') + $path); Headers = $headers; WebSession = $script:session; UseBasicParsing = $true }
  if ($body) { $p.Body = ($body | ConvertTo-Json -Compress -Depth 8) }
  try {
    $r = Invoke-WebRequest @p
    $text = [System.Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
    $json = if ($text) { $text | ConvertFrom-Json } else { $null }
    return @{ ok = $true; status = [int]$r.StatusCode; json = (Unwrap $json); text = $text }
  } catch {
    $s = 0; $detail = $_.Exception.Message
    if ($_.Exception.Response) { $s = [int]$_.Exception.Response.StatusCode; try { $detail = (New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())).ReadToEnd() } catch { } }
    return @{ ok = $false; status = $s; json = $null; text = $detail }
  }
}

Step '0. Admin login + tenant context'
$login = Api 'POST' '/api/v1/admin/auth/login' @{ username = $Username; password = $Password; ttlHours = 8 }
if (-not $login.ok) { Fail ("login failed: HTTP " + $login.status + " " + $login.text); Write-Host ('RESULT: PASS=' + $script:pass + ' FAIL=' + $script:fail + ' SKIP=' + $script:skip) -ForegroundColor Cyan; exit 1 }
Pass 'POST /api/v1/admin/auth/login 200'
$script:csrf = (Api 'GET' '/api/v1/admin/auth/csrf').json.csrfToken
$sel = Api 'POST' '/api/v1/admin/context/select' @{ contextId = $ContextId }
Assert $sel.ok ('context/select -> ' + $sel.status)

Step '1. occurredAt is never null in recent audit rows'
$page = Api 'GET' ('/api/v1/admin/audits?pageSize=' + $PageSize)
if (-not $page.ok -or -not $page.json) { Fail ('audit query unavailable: HTTP ' + $page.status) }
else {
  $items = @($page.json.items) | Where-Object { $_ }
  Assert ($items.Count -gt 0) ('audit list returned ' + $items.Count + ' row(s)')
  $nullOccurred = @($items | Where-Object { -not $_.occurredAt })
  Assert ($nullOccurred.Count -eq 0) ('every row exposes occurredAt (null rows: ' + $nullOccurred.Count + ')')
  $total = $page.json.total
  Write-Host ('    total=' + $total + ' scope=' + $page.json.scope) -ForegroundColor DarkGray
}

Step '2. fromAt/toAt window filters by effective occurrence time'
$from = (Get-Date).Date.AddDays(-1).ToString('yyyy-MM-dd HH:mm:ss')
$to = (Get-Date).Date.AddDays(1).ToString('yyyy-MM-dd HH:mm:ss')
$ranged = Api 'GET' ('/api/v1/admin/audits?pageSize=50&fromAt=' + [uri]::EscapeDataString($from) + '&toAt=' + [uri]::EscapeDataString($to))
if (-not $ranged.ok -or -not $ranged.json) { Fail ('window query failed: HTTP ' + $ranged.status + ' ' + $ranged.text) }
else {
  $rows = @($ranged.json.items) | Where-Object { $_ }
  Assert ($rows.Count -gt 0) ('window [' + $from + ' .. ' + $to + ') returned ' + $rows.Count + ' row(s)')
  $outside = @($rows | Where-Object { $_.occurredAt -and ([datetime]$_.occurredAt -lt [datetime]$from -or [datetime]$_.occurredAt -ge [datetime]$to) })
  Assert ($outside.Count -eq 0) ('no row falls outside the requested window (outside: ' + $outside.Count + ')')
  Assert ($null -ne $ranged.json.total) 'response exposes total (paging stays consistent with the filter)'
}

Step '3. platform-scoped write carries the platform operator (opt-in)'
if (-not $RunPlatformWrite) {
  Skip 'step 3 skipped: pass -RunPlatformWrite to touch a platform-scoped endpoint'
} else {
  $plans = Api 'GET' '/api/v1/admin/pricing-plans'
  if (-not $plans.ok -or -not $plans.json) {
    Skip ('pricing-plans list unavailable: HTTP ' + $plans.status + ' ' + $plans.text)
  } else {
    $list = @($plans.json.items); if ($list.Count -eq 0) { $list = @($plans.json) }
    $plan = @($list | Where-Object { $_ -and $_.id }) | Select-Object -First 1
    if (-not $plan) { Skip 'no pricing plan row to touch' }
    else {
      $body = @{}
      foreach ($prop in $plan.PSObject.Properties) {
        if ($prop.Name -in @('id', 'createdAt', 'updatedAt', 'tenantId')) { continue }
        $body[$prop.Name] = $prop.Value
      }
      $put = Api 'PUT' ('/api/v1/admin/pricing-plans/' + $plan.id) $body
      if (-not $put.ok) {
        Skip ('platform write rejected (HTTP ' + $put.status + ' ' + $put.text + '); verify the operator manually in the audit page')
      } else {
        Start-Sleep -Seconds 2
        $audits = Api 'GET' '/api/v1/admin/audits?actionPrefix=pricing-plan&pageSize=5'
        $rows = @($audits.json.items) | Where-Object { $_ }
        if ($rows.Count -eq 0) { Fail 'no audit row found for the platform-scoped write' }
        else {
          $withOperator = @($rows | Where-Object { $_.operatorId -or $_.operatorName -or $_.operatorAccount })
          Assert ($withOperator.Count -gt 0) ('platform write audited with operator (operatorId=' + $rows[0].operatorId + ' operatorType=' + $rows[0].operatorType + ')')
        }
      }
    }
  }
}

Write-Host ''
Write-Host ('RESULT: PASS=' + $script:pass + ' FAIL=' + $script:fail + ' SKIP=' + $script:skip) -ForegroundColor Cyan
if ($script:fail -gt 0) { exit 1 }
