[CmdletBinding()]
param(
  [string]$Kubeconfig = '',
  [string]$Context = '209277982874572694-c8629a647dce749afbec6d16972bc6ec7',
  [string]$Namespace = 'im-business',
  [string]$GatewayBaseUrl = 'https://admin.dev.example.com',
  [string]$ExpectedIdentityImage = ''
)

$ErrorActionPreference = 'Stop'

function Invoke-KubectlJson([string[]]$Arguments) {
  $argsWithContext = @()
  if ($Kubeconfig) { $argsWithContext += @('--kubeconfig', $Kubeconfig) }
  $argsWithContext += @('--context', $Context)
  $argsWithContext += $Arguments
  $json = & kubectl @argsWithContext
  if ($LASTEXITCODE -ne 0) { throw "kubectl failed: $($Arguments -join ' ')" }
  return ($json | ConvertFrom-Json)
}

$deployment = Invoke-KubectlJson @('-n', $Namespace, 'get', 'deployment', 'platform-identity-service', '-o', 'json')
$deploymentImage = [string]$deployment.spec.template.spec.containers[0].image
if ($ExpectedIdentityImage -and $deploymentImage -ne $ExpectedIdentityImage) {
  throw "platform-identity-service deployment image mismatch: expected '$ExpectedIdentityImage', got '$deploymentImage'"
}

$pods = Invoke-KubectlJson @('-n', $Namespace, 'get', 'pods', '-l', 'app=platform-identity-service', '-o', 'json')
$running = @($pods.items | Where-Object { $_.status.phase -eq 'Running' })
if ($running.Count -eq 0) { throw 'platform-identity-service has no running pod' }
foreach ($pod in $running) {
  $podImage = [string]$pod.spec.containers[0].image
  if ($podImage -ne $deploymentImage) {
    throw "platform-identity-service pod spec image mismatch: deployment '$deploymentImage', pod '$podImage'"
  }
  $imageId = [string]$pod.status.containerStatuses[0].imageID
  if ($imageId -notmatch '(^sha256:|@sha256:)[a-f0-9]{64}$') {
    throw "platform-identity-service pod has no resolved runtime image ID: '$imageId'"
  }
}

foreach ($path in @('/api/v1/auth/session', '/api/v1/auth/csrf', '/api/v1/auth/contexts')) {
  $uri = $GatewayBaseUrl.TrimEnd('/') + $path
  $statusCode = (& curl.exe --silent --show-error --output NUL --write-out '%{http_code}' --max-time 20 $uri).Trim()
  if ($LASTEXITCODE -ne 0 -or $statusCode -notmatch '^\d{3}$') {
    throw "authentication contract endpoint request failed: $path (curl exit=$LASTEXITCODE)"
  }
  if ([int]$statusCode -eq 404) {
    throw "authentication contract endpoint is missing: $path"
  }
}

Write-Host "ACK auth contract verified. image=$deploymentImage endpoints=session,csrf,contexts"
