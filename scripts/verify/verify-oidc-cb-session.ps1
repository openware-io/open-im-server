[CmdletBinding()]
param(
  [string]$Gateway = 'http://127.0.0.1:30002',
  [string]$H5Base = 'http://127.0.0.1:30082'
)

$ErrorActionPreference = 'Stop'

function Get-DbPassword {
  $value = (kubectl --context kind-gv-im-local -n gv-im-local get secret gv-im-env -o jsonpath='{.data.DB_PASSWORD}').Trim()
  return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($value))
}

function Get-RedisPassword {
  $value = (kubectl --context kind-gv-im-local -n gv-im-local get secret gv-im-env -o jsonpath='{.data.REDIS_PASSWORD}').Trim()
  return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($value))
}

function Invoke-MySql([string]$sql) {
  kubectl --context kind-gv-im-local -n gv-im-local exec deploy/mysql -- mysql -u root "-p$script:dbPassword" im_server -e $sql | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'MySQL command failed.' }
}

function Escape([string]$value) { [Uri]::EscapeDataString($value) }

function Get-CodeChallenge([string]$verifier) {
  $algorithm = [Security.Cryptography.SHA256]::Create()
  try { $hash = $algorithm.ComputeHash([Text.Encoding]::ASCII.GetBytes($verifier)) } finally { $algorithm.Dispose() }
  return [Convert]::ToBase64String($hash).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Get-Location([string[]]$headers) {
  return (($headers | Where-Object { $_ -match '^location:' } | Select-Object -First 1) -replace '^location:\s*', '')
}

function New-AuthorizationCode([string]$appId, [string]$redirectUri, [string]$state, [string]$nonce, [string]$verifier) {
  $query = "client_id=$(Escape $appId)&response_type=code&redirect_uri=$(Escape $redirectUri)&scope=profile.basic&code_challenge=$(Escape (Get-CodeChallenge $verifier))&code_challenge_method=S256&state=$(Escape $state)&nonce=$(Escape $nonce)"
  $authorize = & curl.exe -sS -D - -o NUL --max-redirs 0 -H "Authorization: Bearer $script:imToken" "$Gateway/oauth/authorize?$query"
  $consent = Get-Location $authorize
  $requestId = [Uri]::UnescapeDataString(([regex]::Match($consent, 'request_id=([^&]+)').Groups[1].Value))
  if ([string]::IsNullOrWhiteSpace($requestId)) { throw 'OAuth authorize did not redirect to consent.' }
  $approval = & curl.exe -sS -D - -o NUL --max-redirs 0 -X POST -H "Authorization: Bearer $script:imToken" "$Gateway/oauth/consent/approve?request_id=$(Escape $requestId)&scope=profile.basic"
  $code = [Uri]::UnescapeDataString(([regex]::Match((Get-Location $approval), 'code=([^&]+)').Groups[1].Value))
  if ([string]::IsNullOrWhiteSpace($code)) { throw 'OAuth consent did not issue an authorization code.' }
  return $code
}

function New-SaasSession([string]$appId, [string]$redirectUri) {
  $verifier = "verifier-$appId-$script:suffix"
  $state = "state-$appId-$script:suffix"
  $nonce = "nonce-$appId-$script:suffix"
  $code = New-AuthorizationCode $appId $redirectUri $state $nonce $verifier
  $response = Invoke-WebRequest -UseBasicParsing "$Gateway/api/v1/identity/oauth/im/callback" -Method Post -ContentType 'application/json' -Body (@{
      code = $code; code_verifier = $verifier; redirect_uri = $redirectUri; app_id = $appId; state = $state; nonce = $nonce
    } | ConvertTo-Json)
  if ($response.StatusCode -ne 200) { throw "OAuth callback failed for ${appId}: $($response.StatusCode)" }
  return ($response.Headers['Set-Cookie'] -split ';')[0]
}

function Get-SaasSession([string]$cookie) {
  return ((& curl.exe -sS -H "Cookie: $cookie" "$Gateway/api/v1/auth/session") | ConvertFrom-Json)
}

function Get-SaasCsrf([string]$cookie) {
  return ((& curl.exe -sS -H "Cookie: $cookie" "$Gateway/api/v1/auth/csrf") | ConvertFrom-Json)
}

function Remove-SaasSession([string]$cookie) {
  if ([string]::IsNullOrWhiteSpace($cookie)) { return }
  $sessionId = ($cookie -split '=', 2)[1]
  if ([string]::IsNullOrWhiteSpace($sessionId)) { return }
  kubectl --context kind-gv-im-local -n gv-im-local exec deploy/redis -- redis-cli -a $script:redisPassword DEL "saas:user-session:$sessionId" "saas:user-csrf:$sessionId" | Out-Null
}

$script:dbPassword = Get-DbPassword
$script:redisPassword = Get-RedisPassword
$script:suffix = (Get-Date).ToUniversalTime().ToString('HHmmss')
$username = "cp6cb$script:suffix"
$password = "Cb!$script:suffix-oidc"
$registration = Invoke-RestMethod "$Gateway/api/v1/auth/register" -Method Post -ContentType 'application/json' -Body (@{ username = $username; password = $password; nickname = 'CP6 C/B'; email = "$username@example.invalid" } | ConvertTo-Json)
$script:imToken = $registration.access_token
if ([string]::IsNullOrWhiteSpace($script:imToken)) { throw 'Registration did not return an IM token.' }

try {
  $cCookie = New-SaasSession 'saas-a380-c' "$H5Base/a380/"
  $bCookie = New-SaasSession 'saas-a380-h5' "$H5Base/b/"
  $cCookieName = ($cCookie -split '=')[0]
  $bCookieName = ($bCookie -split '=')[0]
  Write-Output "Callback cookies received: C=$cCookieName($($cCookie.Length)), B=$bCookieName($($bCookie.Length))."
  if (($cCookie -split '=')[0] -eq ($bCookie -split '=')[0]) { throw 'C/B cookie names must differ.' }
  $cSession = Get-SaasSession $cCookie
  $bSession = Get-SaasSession $bCookie
  if (!$cSession.authenticated -or !$bSession.authenticated -or $cSession.appId -ne 'saas-a380-c' -or $bSession.appId -ne 'saas-a380-h5') {
    throw "C/B session isolation failed: c=$($cSession.authenticated)/$($cSession.appId), b=$($bSession.authenticated)/$($bSession.appId)."
  }
  $csrf = Get-SaasCsrf $cCookie
  $logoutStatus = & curl.exe -sS -o NUL -w '%{http_code}' -X POST -H "Cookie: $cCookie" -H "X-CSRF-Token: $($csrf.csrfToken)" "$Gateway/api/v1/auth/logout"
  if ($logoutStatus -ne '200') { throw "C logout failed: $logoutStatus" }
  if ((Get-SaasSession $cCookie).authenticated) { throw 'C logout did not revoke the session.' }
  $cReLogin = New-SaasSession 'saas-a380-c' "$H5Base/a380/"
  if (!(Get-SaasSession $cReLogin).authenticated) { throw 'C re-login failed.' }
  Write-Output 'C/B OIDC session isolation, logout and re-login passed.'
} finally {
  try { Invoke-WebRequest -UseBasicParsing "$Gateway/api/v1/users/me" -Method Delete -ContentType 'application/json' -Body (@{ password = $password } | ConvertTo-Json) -Headers @{ Authorization = "Bearer $script:imToken" } | Out-Null } catch { Write-Warning 'Disposable account cleanup failed.' }
  Remove-SaasSession $cCookie
  Remove-SaasSession $bCookie
  Remove-SaasSession $cReLogin
  Write-Output 'Disposable account deleted and OIDC client registry left unchanged.'
}
