[CmdletBinding()]
param(
  [Parameter(Mandatory)] [string]$ReleaseManifestPath,
  [string]$Namespace = 'im-business',
  [string]$Registry = 'ghcr.io/openware-io',
  [string]$RepositoryNamespace = 'openware',
  [ValidateRange(120, 1800)]
  [int]$StartupTimeoutSeconds = 900
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$manifest = Get-Content -Raw -LiteralPath $ReleaseManifestPath | ConvertFrom-Json
if (!$manifest.services -or !$manifest.deploymentTargets) { throw 'Release manifest has no deployment targets.' }
$releaseType = [string]$manifest.releaseType
if ($releaseType -notin @('development', 'formal')) {
  throw "Invalid ACK release type: $releaseType"
}
if ($releaseType -eq 'formal' -and [bool]$manifest.allowTagOverwrite) {
  throw 'Formal ACK release manifests must disable tag overwrite.'
}
if ($releaseType -eq 'development' -and -not [bool]$manifest.allowTagOverwrite) {
  throw 'Development ACK release manifests must allow tag overwrite.'
}
$settingsPath = Join-Path $env:APPDATA 'Code\User\settings.json'
$settings = Get-Content -Raw -Encoding utf8 -LiteralPath $settingsPath
$match = [regex]::Match($settings, '"vs-kubernetes\.kubeconfig"\s*:\s*"([^"]+)"')
if (!$match.Success) { throw 'VS Code Kubernetes kubeconfig is not configured.' }
$kubeconfig = $match.Groups[1].Value -replace '\\\\', '\'
if (!(Test-Path -LiteralPath $kubeconfig)) { throw "Kubeconfig not found: $kubeconfig" }

function Invoke-Kubectl([string[]]$Arguments) {
  & kubectl --kubeconfig $kubeconfig @Arguments
  if ($LASTEXITCODE -ne 0) { throw "kubectl $($Arguments -join ' ') failed." }
}

function Get-ReleaseImage([string]$Name, [object]$Entry, [string]$ReleaseType) {
  if (!$Entry.tag -or !$Entry.digest -or !$Entry.imageDigest) { throw "Release manifest lacks ACR fields for $Name" }
  $tag = [string]$Entry.tag
  $expectedDigest = [string]$Entry.digest
  $digestImage = [string]$Entry.imageDigest
  $repository = "$($Registry.TrimEnd('/'))/$($RepositoryNamespace.Trim('/'))/$Name"
  $tagPattern = if ($ReleaseType -eq 'formal') { '^\d+\.\d+\.\d+$' } else { '^\d+\.\d+\.\d+-SNAPSHOT$' }
  if ($tag -notmatch $tagPattern -or $digestImage -notmatch '^' + [regex]::Escape($repository) + '@sha256:[a-f0-9]{64}$') { throw "Invalid $ReleaseType ACR image for ${Name}: $digestImage" }
  if (!$digestImage.EndsWith("@$expectedDigest")) { throw "Manifest digest mismatch for $Name" }

  function Get-RegistryDigest([string]$Image) {
    for ($queryAttempt = 1; $queryAttempt -le 3; $queryAttempt++) {
      $savedErrorActionPreference = $ErrorActionPreference
      try {
        $ErrorActionPreference = 'Continue'
        $manifestJson = & docker buildx imagetools inspect $Image --format '{{json .Manifest}}' 2>$null
        $manifestExitCode = $LASTEXITCODE
      } finally { $ErrorActionPreference = $savedErrorActionPreference }
      if ($manifestExitCode -eq 0) {
        $manifest = ($manifestJson | Out-String | ConvertFrom-Json)
        $digestValue = [string]$manifest.digest
        if ($digestValue -match '^sha256:[a-f0-9]{64}$') {
          return $digestValue
        }
      }
      if ($queryAttempt -lt 3) { Start-Sleep -Seconds ([int][Math]::Pow(2, $queryAttempt)) }
    }
    return ''
  }

  $remote = "$repository`:$tag"
  $actualDigest = Get-RegistryDigest $remote
  if (!$actualDigest -or $actualDigest -ne $expectedDigest) { throw "ACR tag/digest mismatch for $remote. Expected $expectedDigest, got $actualDigest" }
  return [ordered]@{ image = $remote; digest = $expectedDigest }
}

$ackContext = (& kubectl --kubeconfig $kubeconfig config current-context).Trim()
if ($ackContext -ne '209277982874572694-c8629a647dce749afbec6d16972bc6ec7') { throw "Refusing to deploy to unexpected context: $ackContext" }

$knownServices = @(
  'im-user-service',
  'im-message-service', 'im-conversation-service', 'common-media-service', 'im-admin-service', 'im-access-ws', 'gateway',
  'platform-identity-service', 'platform-tenant-service', 'platform-resource-service', 'platform-order-service',
  'common-payment-service', 'platform-admin-service', 'platform-customer-service', 'platform-marketing-service',
  'common-payment-channel-service', 'common-audit-service', 'common-sms-service', 'common-mail-service',
  'group-idaas-service', 'saas-admin', 'saas-mobile', 'unified-portal', 'pc-admin'
)
$services = @($manifest.deploymentTargets | ForEach-Object { [string]$_ } | Select-Object -Unique)
if ($services.Count -eq 0 -or @($services | Where-Object { $_ -notin $knownServices }).Count -gt 0) {
  throw 'Release manifest has invalid deployment targets.'
}

$images = @{}; $digests = @{}; $tags = @{}
foreach ($service in $services) {
  $entry = $manifest.services.PSObject.Properties[$service].Value
  if (!$entry) { throw "Release manifest missing service: $service" }
  $tags[$service] = [string]$entry.tag
  $releaseImage = Get-ReleaseImage $service $entry $releaseType
  # 必须用 digest 固定引用下发，不能用 tag：Development 发版按规范「不升版本、同 tag 覆盖」，
  # deployment 里若写的是同一个 tag，kubectl set image 的字符串没有变化 → spec 未变更 → 不触发滚动，
  # rollout status 直接返回成功、Pod 仍是上一版镜像，最终被下面的 digest 校验拦下（实测 im-user-service
  # 期望 1c1917a5… 实际仍是上一版的 7b3781f0…）。digest 固定引用同时让 Pod imageID 校验变成精确比对。
  $images[$service] = [string]$entry.imageDigest
  $digests[$service] = $releaseImage.digest
}

foreach ($service in $services) {
  Invoke-Kubectl @('get', 'deployment', $service, '-n', $Namespace)
  Invoke-Kubectl @('set', 'image', "deployment/$service", "$service=$($images[$service])", '-n', $Namespace)
}
foreach ($service in $services) { Invoke-Kubectl @('rollout', 'status', "deployment/$service", '-n', $Namespace, "--timeout=$($StartupTimeoutSeconds)s") }
foreach ($service in $services) {
  $actual = (& kubectl --kubeconfig $kubeconfig get deployment $service -n $Namespace -o 'jsonpath={.spec.template.spec.containers[0].image}').Trim()
  if ($actual -ne $images[$service]) { throw "ACK image reference drift for $service. Expected '$($images[$service])', got '$actual'." }
  $pods = ((& kubectl --kubeconfig $kubeconfig get pods -n $Namespace -l "app=$service" -o json) | ConvertFrom-Json).items
  if (!$pods) { throw "ACK has no ready Pod for $service after rollout." }
  foreach ($pod in $pods) {
    $container = @($pod.status.containerStatuses | Where-Object { $_.name -eq $service }) | Select-Object -First 1
    if (!$container -or [string]$container.imageID -notmatch [regex]::Escape($digests[$service])) {
      throw "ACK image digest drift for $service. Expected '$($digests[$service])', got '$($container.imageID)'."
    }
  }
}

$record = [ordered]@{ schemaVersion = 1; manifest = (Resolve-Path $ReleaseManifestPath).Path; releaseType = $releaseType; deployedAt = (Get-Date).ToUniversalTime().ToString('o'); images = $images; digests = $digests; tags = $tags }
$recordPath = Join-Path $root ".outputs\releases\ack-saas-$($manifest.buildIdentity).json"
$record | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 -LiteralPath $recordPath
Write-Output "ACK release deployed by tag and verified by digest. Record: $recordPath"
