[CmdletBinding()]
param(
  [ValidateSet('kind', 'ack')]
  [string]$Environment,
  [string]$Context,
  [string]$Namespace,
  [string]$Kubeconfig = '',
  [string]$DatabaseSecret,
  [string]$RootDatabaseSecretKey,
  [string]$H5Base,
  [string[]]$AdditionalH5Origins = @(),
  [string[]]$AdditionalViteCOrigins = @(),
  [string[]]$AdditionalViteBOrigins = @()
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($Environment) -or [string]::IsNullOrWhiteSpace($Context) -or [string]::IsNullOrWhiteSpace($Namespace) -or [string]::IsNullOrWhiteSpace($DatabaseSecret) -or [string]::IsNullOrWhiteSpace($RootDatabaseSecretKey) -or [string]::IsNullOrWhiteSpace($H5Base)) {
  throw 'Environment, Context, Namespace, DatabaseSecret, RootDatabaseSecretKey and H5Base are required.'
}

try { $baseUri = [Uri]$H5Base } catch { throw "H5Base is not a valid URI: $H5Base" }
if ($Environment -eq 'kind' -and $baseUri.Scheme -ne 'http') { throw 'Kind H5Base must use HTTP.' }
if ($Environment -eq 'ack' -and $baseUri.Scheme -ne 'https') { throw 'ACK H5Base must use HTTPS.' }
if ($baseUri.Query -or $baseUri.Fragment -or $baseUri.AbsolutePath -ne '/') { throw 'H5Base must be an origin without a path, query or fragment.' }

function Invoke-Cluster([string[]]$Arguments) {
  $prefix = @()
  if ($Kubeconfig) { $prefix += @('--kubeconfig', $Kubeconfig) }
  $prefix += @('--context', $Context, '--namespace', $Namespace)
  # Windows PowerShell 5.1 promotes native stderr to an ErrorRecord; under `$ErrorActionPreference='Stop'`
  # the in-pod `mysql` password warning ("Using a password on the command line interface can be insecure")
  # would abort the whole synchronization. Keep the same pattern as Invoke-Docker in build-saas-release.ps1:
  # lower the preference locally, drop stderr, and decide purely on the exit code.
  $saved = $ErrorActionPreference
  try {
    $ErrorActionPreference = 'Continue'
    $result = & kubectl @prefix @Arguments 2>$null
    $code = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $saved
  }
  if ($code -ne 0) { throw "kubectl $($Arguments -join ' ') failed (exit $code)." }
  return $result
}

function Get-MySqlTarget {
  $deployment = Invoke-Cluster @('get', 'deployment', 'mysql', '--ignore-not-found', '-o', 'name')
  if ($deployment) { return 'deployment/mysql' }
  $statefulSet = Invoke-Cluster @('get', 'statefulset', 'mysql', '--ignore-not-found', '-o', 'name')
  if ($statefulSet) { return 'statefulset/mysql' }
  throw 'MySQL workload was not found.'
}

function SqlLiteral([string]$value) { return "'" + $value.Replace("'", "''") + "'" }
function Values([string]$clientId, [string[]]$uris) {
  return (($uris | ForEach-Object { "($(SqlLiteral $clientId),$(SqlLiteral $_))" }) -join ',')
}

$credentialPayload = (Invoke-Cluster @('get', 'secret', $DatabaseSecret, '-o', "jsonpath={.data.$RootDatabaseSecretKey}")).Trim()
if (!$credentialPayload) { throw "Secret $DatabaseSecret does not contain $RootDatabaseSecretKey." }
$mysqlAuthArgument = '-p' + [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($credentialPayload))
$target = Get-MySqlTarget
$origin = $baseUri.GetLeftPart([UriPartial]::Authority)
$cUris = @("$origin/a380/")
$bUris = @("$origin/b/")
foreach ($additionalOrigin in $AdditionalH5Origins) {
  try { $additionalUri = [Uri]$additionalOrigin } catch { throw "AdditionalH5Origins contains an invalid URI: $additionalOrigin" }
  if ($additionalUri.Scheme -ne $baseUri.Scheme -or $additionalUri.Query -or $additionalUri.Fragment -or $additionalUri.AbsolutePath -ne '/') {
    throw "AdditionalH5Origins entry must be a $($baseUri.Scheme) origin without a path, query or fragment: $additionalOrigin"
  }
  $additionalAuthority = $additionalUri.GetLeftPart([UriPartial]::Authority)
  $cUris += "$additionalAuthority/a380/"
  $bUris += "$additionalAuthority/b/"
}
foreach ($additionalOrigin in $AdditionalViteCOrigins) {
  try { $additionalUri = [Uri]$additionalOrigin } catch { throw "AdditionalViteCOrigins contains an invalid URI: $additionalOrigin" }
  if ($additionalUri.Scheme -ne $baseUri.Scheme -or $additionalUri.Query -or $additionalUri.Fragment -or $additionalUri.AbsolutePath -ne '/') {
    throw "AdditionalViteCOrigins entry must be a $($baseUri.Scheme) origin without a path, query or fragment: $additionalOrigin"
  }
  $additionalAuthority = $additionalUri.GetLeftPart([UriPartial]::Authority)
  $cUris += "$additionalAuthority/"
}
foreach ($additionalOrigin in $AdditionalViteBOrigins) {
  try { $additionalUri = [Uri]$additionalOrigin } catch { throw "AdditionalViteBOrigins contains an invalid URI: $additionalOrigin" }
  if ($additionalUri.Scheme -ne $baseUri.Scheme -or $additionalUri.Query -or $additionalUri.Fragment -or $additionalUri.AbsolutePath -ne '/') {
    throw "AdditionalViteBOrigins entry must be a $($baseUri.Scheme) origin without a path, query or fragment: $additionalOrigin"
  }
  $additionalAuthority = $additionalUri.GetLeftPart([UriPartial]::Authority)
  $bUris += "$additionalAuthority/index.b.html"
}
if ($Environment -eq 'kind') {
  $cUris += @('http://127.0.0.1:30082/a380/', 'http://localhost:30082/a380/', 'http://127.0.0.1:5175/', 'http://localhost:5175/')
  $bUris += @('http://127.0.0.1:30082/b/', 'http://localhost:30082/b/', 'http://127.0.0.1:5176/index.b.html', 'http://localhost:5176/index.b.html')
}
$cUris = @($cUris | Select-Object -Unique)
$bUris = @($bUris | Select-Object -Unique)

$sql = @"
UPDATE open_application SET callback_url = CASE app_id
  WHEN 'saas-a380-c' THEN $(SqlLiteral $cUris[0])
  WHEN 'saas-a380-h5' THEN $(SqlLiteral $bUris[0])
END
WHERE app_id IN ('saas-a380-c', 'saas-a380-h5');
DELETE FROM user_oidc_redirect_uri WHERE client_id IN ('saas-a380-c', 'saas-a380-h5');
INSERT INTO user_oidc_redirect_uri (client_id, redirect_uri) VALUES $(Values 'saas-a380-c' $cUris),$(Values 'saas-a380-h5' $bUris);
"@
Invoke-Cluster @('exec', $target, '--', 'mysql', '-u', 'root', $mysqlAuthArgument, 'im_server', '-e', $sql) | Out-Null

$registered = Invoke-Cluster @('exec', $target, '--', 'mysql', '-u', 'root', $mysqlAuthArgument, '-N', '-B', 'im_server', '-e', "SELECT client_id, redirect_uri FROM user_oidc_redirect_uri WHERE client_id IN ('saas-a380-c', 'saas-a380-h5') ORDER BY client_id, redirect_uri;")
$actual = @($registered | Where-Object { $_ } | ForEach-Object { $_.Trim() })
$expected = @(
  $cUris | ForEach-Object { "saas-a380-c`t$_" }
  $bUris | ForEach-Object { "saas-a380-h5`t$_" }
) | Sort-Object
if ((@($actual | Sort-Object) -join "`n") -ne ($expected -join "`n")) {
  throw "OIDC redirect registry mismatch for ${Environment}. expected=$($expected -join ', ') actual=$($actual -join ', ')"
}

Write-Output "OIDC client registry synchronized for ${Environment}: C=$($cUris -join ', '); B=$($bUris -join ', ')."
