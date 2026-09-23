[CmdletBinding()]
param(
  [switch]$SkipBuild,
  [switch]$Stop,
  [string]$Namespace = 'open-im-local',
  [ValidatePattern('^[a-z0-9]([-a-z0-9]*[a-z0-9])?$')]
  [string]$KindClusterName = 'open-im-local',
  [string]$AdminProjectPath = 'D:\projects\cnb-oss\open-chat-admin',
  [ValidatePattern('^(0\.0\.0\.0|127\.0\.0\.1|localhost|(?:\d{1,3}\.){3}\d{1,3})$')]
  [string]$LocalBindAddress = '0.0.0.0',
  [string]$LocalAdvertiseAddress,
  [string[]]$AdditionalH5Origins = @(),
  [string[]]$AdditionalViteCOrigins = @(),
  [string[]]$AdditionalViteBOrigins = @(),
  [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$')]
  [string]$ImageTag,
  [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$')]
  [string]$SaasImageTag,
  [string]$SaasReleaseManifestPath,
  [switch]$ValidateOnly,
  [ValidatePattern('^[a-z0-9]([-a-z0-9.]*[a-z0-9])?$')]
  [string]$RegistryPullSecretName = 'acr-registry',
  [string]$Registry = 'ghcr.io/openware-io',
  [string]$RepositoryNamespace = '',
  [ValidateRange(60, 900)]
  [int]$StartupTimeoutSeconds = 600
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $PSScriptRoot 'approved-images.ps1')
if (!$Stop) {
  if ($ImageTag -or $SaasImageTag) { throw 'Image tag overrides are forbidden in every environment. Use SaasReleaseManifestPath.' }
  if (!$SaasReleaseManifestPath) { throw 'SaasReleaseManifestPath is required in every environment. Build approved ACR images with build-saas-release.ps1 first.' }
}
$envFile = Join-Path $root '.env'
$manifestDir = Join-Path $root 'k8s\local'
$kindConfigPath = Join-Path $root 'k8s\kind\local-cluster.yaml'
if (-not (Test-Path -LiteralPath $envFile)) { throw ".env not found: $envFile" }
if (-not (Test-Path -LiteralPath $manifestDir)) { throw "Kubernetes manifests not found: $manifestDir" }
if (-not (Test-Path -LiteralPath $kindConfigPath)) { throw "Kind cluster configuration not found: $kindConfigPath" }
if (-not (Get-Command kubectl -ErrorAction SilentlyContinue)) { throw 'kubectl is required.' }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'docker is required.' }
if (-not (Get-Command kind -ErrorAction SilentlyContinue)) { throw 'kind is required.' }

function Invoke-Kubectl {
  param([string[]]$Arguments)
  & kubectl @Arguments
  if ($LASTEXITCODE -ne 0) { throw "kubectl $($Arguments -join ' ') failed." }
}

function Apply-Manifest {
  param([string]$Name)
  $path = Join-Path $manifestDir $Name
  if (-not (Test-Path -LiteralPath $path)) { throw "Kubernetes manifest not found: $path" }
  Invoke-Kubectl -Arguments @('apply', '--namespace', $Namespace, '-f', $path)
}

function Apply-StrategicPatch {
  param([string]$ResourceType, [string]$TargetName, [string]$PatchName)
  $path = Join-Path $manifestDir $PatchName
  if (-not (Test-Path -LiteralPath $path)) { throw "Kubernetes patch not found: $path" }
  Invoke-Kubectl -Arguments @('patch', $ResourceType, $TargetName, '--namespace', $Namespace, '--type=strategic', '--patch-file', $path)
}

function Get-GitRevision {
  param([string]$ProjectPath)
  $revision = (& git -C $ProjectPath rev-parse --short HEAD 2>$null).Trim()
  if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($revision)) { return 'nogit' }
  $changes = & git -C $ProjectPath status --porcelain 2>$null
  if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace(($changes | Out-String).Trim())) {
    return "$revision-dirty"
  }
  return $revision
}

function Get-BuildIdentity {
  param([string]$ProjectPath)
  $revision = Get-GitRevision -ProjectPath $ProjectPath
  return "$(Get-Date -Format 'yyyyMMddHHmmss').$revision"
}

function Get-MavenProjectVersion {
  param([string]$PomPath)
  [xml]$pom = Get-Content -Raw -LiteralPath $PomPath
  $versionNode = $pom.SelectSingleNode('/*[local-name()="project"]/*[local-name()="version"]')
  if ($null -eq $versionNode) {
    $versionNode = $pom.SelectSingleNode('/*[local-name()="project"]/*[local-name()="parent"]/*[local-name()="version"]')
  }
  $version = if ($null -eq $versionNode) { '' } else { $versionNode.InnerText }
  if ([string]::IsNullOrWhiteSpace($version)) { throw "Maven project version not found: $PomPath" }
  return $version
}

function Get-ReleaseId {
  param([string]$BuildIdentity)
  return "local-$BuildIdentity"
}

function Import-KubernetesImage {
  param([string]$Image)
  $nodes = @(& docker ps --filter "label=io.x-k8s.kind.cluster=$KindClusterName" --format '{{.Names}}')
  if ($nodes.Count -eq 0) { throw "Kind nodes were not found for cluster: $KindClusterName" }
  $localImageId = (& docker image inspect $Image --format '{{.Id}}').Trim()
  if ($LASTEXITCODE -ne 0) { throw "Cannot inspect local image: $Image" }
  foreach ($node in $nodes) {
    $nodeImages = & docker exec $node crictl images --no-trunc
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect Kubernetes node images: $node" }
    if ($nodeImages -match [regex]::Escape($localImageId)) { continue }
    # Windows PowerShell 5 converts native pipeline output to text, corrupting image tar streams.
    & cmd.exe /d /c "docker image save $Image | docker exec -i $node ctr --namespace k8s.io images import -"
    if ($LASTEXITCODE -ne 0) { throw "Importing local image into Kubernetes failed: $Image on node: $node" }
  }
}

function Apply-RenderedManifest {
  param([string]$Name, [hashtable]$Replacements, [switch]$StartSuspended)
  $path = Join-Path $manifestDir $Name
  if (-not (Test-Path -LiteralPath $path)) { throw "Kubernetes manifest not found: $path" }
  $content = Get-Content -Raw -LiteralPath $path
  foreach ($entry in $Replacements.GetEnumerator()) { $content = $content.Replace($entry.Key, $entry.Value) }
  if ($StartSuspended) { $content = $content.Replace('replicas: 1', 'replicas: 0') }
  $content = $content.Replace('      containers:', "      imagePullSecrets:`n        - name: $RegistryPullSecretName`n      containers:")
  if ($content -match 'image:\s+(open-im/|open-local/|\S*__[A-Z_]+__)') { throw "Unresolved application image in $Name" }
  $tempPath = Join-Path ([System.IO.Path]::GetTempPath()) ("open-im-k8s-render-" + [guid]::NewGuid().ToString() + '.yaml')
  try {
    [System.IO.File]::WriteAllText($tempPath, $content, [System.Text.UTF8Encoding]::new($false))
    Invoke-Kubectl -Arguments @('apply', '--namespace', $Namespace, '-f', $tempPath)
  } finally {
    if (Test-Path -LiteralPath $tempPath) { Remove-Item -LiteralPath $tempPath -Force }
  }
}

function Import-KubernetesRegistryImage {
  param([string]$Image)
  $localId=(& docker image inspect $Image --format '{{.Id}}' 2>$null | Out-String).Trim()
  if($LASTEXITCODE -ne 0 -or !$localId){
    $pulled=$false
    for($a=1;$a -le 10 -and !$pulled;$a++){
      $saved=$ErrorActionPreference; $ErrorActionPreference='Continue'
      try{ $out=& docker pull $Image 2>&1; $code=$LASTEXITCODE }finally{ $ErrorActionPreference=$saved }
      foreach($l in $out){ if($l -is [System.Management.Automation.ErrorRecord]){ Write-Warning ([string]$l) }elseif($null -ne $l){ Write-Output ([string]$l) } }
      if($code -eq 0){$pulled=$true;break}
      $delay=[Math]::Min(30,[int][Math]::Pow(2,$a-1)); Write-Warning "Pull attempt $a failed for $Image; retrying in ${delay}s."; Start-Sleep -Seconds $delay
    }
    if(!$pulled){ throw "Pulling ACR image failed: $Image" }
  }
  $nodes = @(& docker ps --filter "label=io.x-k8s.kind.cluster=$KindClusterName" --filter 'label=io.x-k8s.kind.role=worker' --format '{{.Names}}')
  if ($nodes.Count -eq 0) { throw "Kind worker nodes were not found for cluster: $KindClusterName" }
  foreach ($node in $nodes) {
    $isDigestImage = $Image -match '@sha256:[a-f0-9]{64}$'
    if (-not $isDigestImage) {
      $nodeImages = & docker exec $node crictl images --no-trunc
      if ($LASTEXITCODE -ne 0) { throw "Cannot inspect Kubernetes node images: $node" }
      if ($nodeImages -match [regex]::Escape($localId)) { continue }
      & cmd.exe /d /c "docker image save $Image | docker exec -i $node ctr --namespace k8s.io images import -"
      if ($LASTEXITCODE -ne 0) { throw "Importing local image into Kubernetes failed: $Image on node: $node" }
      continue
    }
    $nodeRefs = @(& docker exec $node ctr --namespace k8s.io images ls --quiet 2>$null)
    if ($nodeRefs -contains $Image) { continue }
    $baseName = $Image -replace '@sha256:[a-f0-9]{64}$', ''
    & cmd.exe /d /c "docker image save $Image | docker exec -i $node ctr --namespace k8s.io images import --digests --base-name $baseName --platform linux/amd64 -"
    if ($LASTEXITCODE -ne 0) { throw "Importing Kubernetes registry image failed: $Image on node: $node" }
    $nodeRefs = @(& docker exec $node ctr --namespace k8s.io images ls --quiet 2>$null)
    if ($nodeRefs -notcontains $Image) { throw "Imported image does not expose the required ACR digest reference: $Image on node: $node" }
  }
}
function Stop-LocalPortForwards {
  $services = @('gateway', 'pc-admin', 'saas-admin', 'saas-mobile', 'unified-portal', 'minio-api', 'minio-console')
  Get-CimInstance Win32_Process -Filter "Name = 'kubectl.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match "port-forward service/($($services -join '|'))" -and $_.CommandLine -match [regex]::Escape("--namespace $Namespace") } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
  foreach ($service in $services) {
    $proxyName = "open-im-k8s-$service-proxy"
    $existingProxy = & docker container ls --all --filter "name=^/$proxyName`$" --quiet
    if ($existingProxy) { & docker rm --force $proxyName | Out-Null }
  }
}

function Start-LocalNodePortProxy {
  param([string]$Service, [int]$NodePort, [int]$LocalPort = $NodePort, [int]$ServicePort = $NodePort)
  $node = (& docker ps --filter 'label=io.x-k8s.kind.role=control-plane' --filter "label=io.x-k8s.kind.cluster=$KindClusterName" --format '{{.Names}}' | Select-Object -First 1)
  if ([string]::IsNullOrWhiteSpace($node)) { throw "Kind control-plane node was not found for cluster: $KindClusterName" }
  $proxyName = "open-im-k8s-$Service-proxy"
  $existingProxy = & docker container ls --all --filter "name=^/$proxyName`$" --quiet
  if ($existingProxy) { & docker rm --force $proxyName | Out-Null }
  & docker run --detach --name $proxyName --network kind -p "$LocalBindAddress`:$LocalPort`:$NodePort" alpine/socat "TCP-LISTEN:$NodePort,fork,reuseaddr" "TCP:$node`:$NodePort"
  if ($LASTEXITCODE -ne 0) { throw "Could not publish local port $LocalPort for $Service." }
  Start-Sleep -Seconds 1
}

function Get-LocalAdvertiseAddress {
  if (-not [string]::IsNullOrWhiteSpace($LocalAdvertiseAddress)) { return $LocalAdvertiseAddress }
  $candidates = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and $_.IPAddress -notlike '198.18.*' } |
    Where-Object { $_.IPAddress -like '10.*' -or $_.IPAddress -like '172.16.*' -or $_.IPAddress -like '172.17.*' -or $_.IPAddress -like '172.18.*' -or $_.IPAddress -like '172.19.*' -or $_.IPAddress -like '172.2*.*' -or $_.IPAddress -like '192.168.*' })
  $candidate = $candidates |
    Sort-Object @{ Expression = { if ($_.IPAddress -like '192.168.*') { 0 } else { 1 } } }, InterfaceIndex |
    Select-Object -First 1 -ExpandProperty IPAddress
  if ([string]::IsNullOrWhiteSpace($candidate)) { return '127.0.0.1' }
  return $candidate
}

function Get-HttpResponseBody {
  param([object]$Content)
  if ($Content -is [byte[]]) { return [System.Text.Encoding]::UTF8.GetString($Content) }
  return [string]$Content
}

if ($Stop) {
  Stop-LocalPortForwards
  Ensure-KindCluster
  & kubectl config use-context "kind-$KindClusterName"
  if ($LASTEXITCODE -ne 0) { throw "Unable to select Kind context: kind-$KindClusterName" }
  Invoke-Kubectl -Arguments @('delete', 'namespace', $Namespace, '--ignore-not-found=true')
  exit 0
}

function Ensure-KindCluster {
  $clusters = @(& kind get clusters 2>$null)
  if ($LASTEXITCODE -ne 0) { throw 'Unable to query Kind clusters.' }
  if ($clusters -contains $KindClusterName) { return }
  Write-Host "Creating Kind cluster: $KindClusterName"
  & kind create cluster --name $KindClusterName --config $kindConfigPath --wait 5m
  if ($LASTEXITCODE -ne 0) { throw "Creating Kind cluster failed: $KindClusterName" }
}

function Remove-InactiveReplicaSets {
  $replicaSets = ((& kubectl get replicasets --namespace $Namespace --output json | ConvertFrom-Json).items | Where-Object {
    $_.spec.replicas -eq 0 -and $_.metadata.ownerReferences.kind -contains 'Deployment'
  } | ForEach-Object { $_.metadata.name })
  if ($replicaSets.Count -eq 0) { return }
  Invoke-Kubectl -Arguments (@('delete', 'replicasets', '--namespace', $Namespace) + $replicaSets)
}

function Invoke-HttpGetWithRetry {
  param([string]$Uri, [int]$Attempts = 15)
  for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
    try { return Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 $Uri } catch {
      if ($attempt -eq $Attempts) { throw }
      Start-Sleep -Seconds 1
    }
  }
}

function Set-ApplicationImage {
  param([string]$Name, [string]$Tag, [string]$MavenVersion, [string]$Revision, [string]$CreatedAt, [string]$ArtifactHash, [string]$ReleaseId)
  $imageRef = $saasImages[$Name]
  if (!$imageRef) { throw "Approved release image missing: $Name" }
  Invoke-Kubectl -Arguments @('set', 'image', "deployment/$Name", "$Name=$imageRef", '--namespace', $Namespace)
  $patch = @{
    metadata = @{ labels = @{ 'app.kubernetes.io/version' = $Tag; 'app.kubernetes.io/managed-by' = 'local-k8s-deploy' } }
    spec = @{ template = @{ metadata = @{
      labels = @{ 'app.kubernetes.io/version' = $Tag; 'app.kubernetes.io/managed-by' = 'local-k8s-deploy' }
      annotations = @{
        'org.opencontainers.image.version' = $MavenVersion
        'org.opencontainers.image.revision' = $Revision
        'org.opencontainers.image.created' = $CreatedAt
        'release.open-im.local/id' = $ReleaseId
        'release.open-im.local/code-sha' = $Revision
        'release.open-im.local/artifact-sha256' = $ArtifactHash
        'release.open-im.local/deployed-at' = $CreatedAt
        'release.open-im.local/image-ref' = $imageRef
      }
    }; spec = @{ containers = @(@{ name = $Name; imagePullPolicy = if ($env:OPEN_IM_OFFLINE_LOCAL -eq '1') { 'IfNotPresent' } else { 'Always' }; env = @(
      @{ name = 'MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE'; value = 'health,info' },
      @{ name = 'MANAGEMENT_INFO_ENV_ENABLED'; value = 'true' },
      @{ name = 'INFO_BUILD_GIT_COMMIT'; value = $Revision },
      @{ name = 'INFO_BUILD_IMAGE'; value = $imageRef },
      @{ name = 'INFO_BUILD_ARTIFACT_SHA256'; value = $ArtifactHash }
    ) }) } } }
  } | ConvertTo-Json -Compress -Depth 8
  $patchFile = Join-Path ([System.IO.Path]::GetTempPath()) ("open-im-k8s-labels-" + [guid]::NewGuid().ToString() + '.json')
  try {
    [System.IO.File]::WriteAllText($patchFile, $patch, [System.Text.UTF8Encoding]::new($false))
    Invoke-Kubectl -Arguments @('patch', "deployment/$Name", '--namespace', $Namespace, '--type=strategic', '--patch-file', $patchFile)
  } finally {
    if (Test-Path -LiteralPath $patchFile) { Remove-Item -LiteralPath $patchFile -Force }
  }
}

$buildIdentity = Get-BuildIdentity -ProjectPath $root
$saasTags = @{}
$saasImages = @{}
if ($SaasReleaseManifestPath) {
  $saasManifest = Get-Content -Raw -LiteralPath $SaasReleaseManifestPath | ConvertFrom-Json
  foreach ($name in @('im-user-service','im-message-service','im-conversation-service','common-media-service','im-admin-service','im-access-ws','gateway','platform-identity-service','platform-tenant-service','platform-resource-service','platform-order-service','common-payment-service','platform-admin-service','platform-customer-service','platform-marketing-service','common-payment-channel-service','common-audit-service','common-sms-service','common-mail-service','group-idaas-service','saas-admin','saas-mobile','unified-portal','pc-admin')) {
    $property = $saasManifest.services.PSObject.Properties[$name]
    if (!$property) { throw "SaaS release manifest missing service: $name" }
    $entry = $property.Value
    [void](Get-ApprovedReleaseImage -Name $name -Entry $entry -Registry $Registry -RepositoryNamespace $RepositoryNamespace)
    $saasImages[$name] = if ($env:OPEN_IM_OFFLINE_LOCAL -eq '1') { [string]$entry.image } else { [string]$entry.imageDigest }
    $saasTags[$name] = [string]$entry.tag
  }
} elseif ($SaasImageTag) { throw 'SaasImageTag is deprecated. Use SaasReleaseManifestPath so each service keeps its approved module version.' }
if ($ValidateOnly) {
  Write-Host "Approved ACR release verified: $($saasImages.Count) services. No deployment changes made."
  exit 0
}
$releaseId = Get-ReleaseId -BuildIdentity $buildIdentity
$revision = Get-GitRevision -ProjectPath $root
$createdAt = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
Write-Host "Using immutable build identity: $buildIdentity"

Ensure-KindCluster
$expectedContext = "kind-$KindClusterName"
& kubectl config use-context $expectedContext
if ($LASTEXITCODE -ne 0) { throw "Unable to select Kind context: $expectedContext" }
$namespaceName = [string](& kubectl get namespace $Namespace --ignore-not-found --output name)
if ($LASTEXITCODE -ne 0) { throw "Unable to inspect Kubernetes namespace: $Namespace" }
$namespaceExists = -not [string]::IsNullOrWhiteSpace($namespaceName)
$pullResource = & kubectl get secret $RegistryPullSecretName --namespace $Namespace --ignore-not-found -o name
if ($LASTEXITCODE -ne 0 -or !$pullResource) { throw "Configure the ACR pull secret '$RegistryPullSecretName' in namespace '$Namespace' before deployment." }

$images = [ordered]@{
  'im-user-service' = @{ Jar = 'im-services/user/im-user-service/target/im-user-service-*.jar'; Pom = 'im-services/user/im-user-service/pom.xml' }
  'im-message-service' = @{ Jar = 'im-services/message/im-message-service/target/im-message-service-*.jar'; Pom = 'im-services/message/im-message-service/pom.xml' }
  'im-conversation-service' = @{ Jar = 'im-services/conversation/im-conversation-service/target/im-conversation-service-*.jar'; Pom = 'im-services/conversation/im-conversation-service/pom.xml' }
  'common-media-service' = @{ Jar = 'common-services/media/common-media-service/target/common-media-service-*.jar'; Pom = 'common-services/media/common-media-service/pom.xml' }
  'im-admin-service' = @{ Jar = 'im-services/admin/im-admin-service/target/im-admin-service-*.jar'; Pom = 'im-services/admin/im-admin-service/pom.xml' }
  'im-access-ws' = @{ Jar = 'gateways/im-access-ws/target/im-access-ws-*.jar'; Pom = 'gateways/im-access-ws/pom.xml' }
  'gateway' = @{ Jar = 'gateways/gateway/target/gateway-*.jar'; Pom = 'gateways/gateway/pom.xml' }
}
$imageDefinitions = @()
foreach ($image in $images.GetEnumerator()) {
  $tag = $saasTags[$image.Key]
  $mavenVersion = $tag
  $artifactHash = ''
  $imageDefinitions += [pscustomobject]@{ Name = $image.Key; Tag = $tag; MavenVersion = $mavenVersion; Revision = $revision; ReleaseId = $releaseId; ArtifactHash = $artifactHash }
}
$adminReleaseVersion = $saasTags['pc-admin']
$adminReleaseId = $releaseId
$adminRevision = [string]$saasManifest.services.'pc-admin'.sourceRevision
$adminTag = $adminReleaseVersion
$pcAdminImage = $saasImages['pc-admin']
$imageDefinitions += [pscustomobject]@{ Name = 'pc-admin'; Tag = $adminTag; MavenVersion = $adminReleaseVersion; Revision = $adminRevision; ReleaseId = $adminReleaseId; ArtifactHash = '' }
$infrastructureImages = @(
  'mysql:8.0',
  'redis:7-alpine',
  'mongo:7.0.12',
  'minio/minio:RELEASE.2024-10-13T13-34-11Z',
  'minio/mc:RELEASE.2024-10-08T09-37-26Z'
)
foreach ($image in $infrastructureImages) { Import-KubernetesRegistryImage -Image $image }
$rocketmqImage = 'apache/rocketmq:5.3.1'
# 本地已构建过就复用：基镜像 apache/rocketmq:5.3.1 只在首次构建时从 Docker Hub 拉取，
# 无外网/代理抖动时重建会让整个 Kind 部署在应用清单之前直接失败（该镜像不参与发布清单校验）。
$savedErrorActionPreference = $ErrorActionPreference
try {
  $ErrorActionPreference = 'Continue'
  & docker image inspect $rocketmqImage 2>$null | Out-Null
  $rocketmqImageExists = ($LASTEXITCODE -eq 0)
} finally { $ErrorActionPreference = $savedErrorActionPreference }
if (-not $rocketmqImageExists) {
  & docker build --quiet --pull=false --file (Join-Path $manifestDir 'rocketmq.Dockerfile') --tag $rocketmqImage $root
  if ($LASTEXITCODE -ne 0) { throw 'Docker image build failed: rocketmq' }
} else {
  Write-Host "Reusing local rocketmq image: $rocketmqImage"
}
Import-KubernetesImage -Image $rocketmqImage
$localSaasImageNames = @(
  'im-user-service', 'platform-identity-service', 'platform-tenant-service', 'platform-resource-service',
  'platform-order-service', 'common-payment-service', 'platform-admin-service',
  'platform-customer-service', 'platform-marketing-service', 'common-payment-channel-service',
  'common-audit-service', 'common-sms-service', 'common-mail-service', 'group-idaas-service',
  'saas-admin', 'saas-mobile', 'unified-portal'
)
foreach ($name in $saasImages.Keys) { Import-KubernetesRegistryImage -Image $saasImages[$name] }
foreach ($name in $localSaasImageNames | Where-Object { $_ -ne 'im-user-service' }) {
  if (!$saasTags[$name]) { throw "SaaS release manifest is required for $name." }
}

function Set-LocalRecreateStrategy {
  param([string]$Name)
  $patch = '{"spec":{"strategy":{"type":"Recreate","rollingUpdate":null}}}'
  $patchFile = Join-Path ([System.IO.Path]::GetTempPath()) ("open-im-k8s-strategy-" + [guid]::NewGuid().ToString() + '.json')
  try {
    [System.IO.File]::WriteAllText($patchFile, $patch, [System.Text.UTF8Encoding]::new($false))
    Invoke-Kubectl -Arguments @('patch', "deployment/$Name", '--namespace', $Namespace, '--type=strategic', '--patch-file', $patchFile)
  } finally {
    if (Test-Path -LiteralPath $patchFile) { Remove-Item -LiteralPath $patchFile -Force }
  }
}

function Remove-ReleaseBuildWaste {
  Write-Host 'Cleaning dangling local Docker images after deployment...'
  & docker image prune --force
  if ($LASTEXITCODE -ne 0) { throw 'Docker dangling-image cleanup failed.' }
}

if (-not (& kubectl get namespace $Namespace 2>$null)) { & kubectl create namespace $Namespace | Out-Null }
if ($LASTEXITCODE -ne 0) { throw 'Creating the Kubernetes namespace failed.' }

$secretEnvFile = Join-Path ([System.IO.Path]::GetTempPath()) ("open-im-k8s-" + [guid]::NewGuid().ToString() + '.env')
try {
  $secretEntries = Get-Content -LiteralPath $envFile | Where-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith('#') -or -not $line.Contains('=')) { return $false }
    return -not [string]::IsNullOrWhiteSpace(($line -split '=', 2)[1])
  }
  $secretValues = @{}
  foreach ($entry in $secretEntries) { $name, $value = $entry -split '=', 2; $secretValues[$name] = $value }
  [System.IO.File]::WriteAllLines($secretEnvFile, [string[]]$secretEntries, [System.Text.UTF8Encoding]::new($false))
  & kubectl create secret generic open-im-env --namespace $Namespace --from-env-file=$secretEnvFile --dry-run=client -o yaml | & kubectl apply -f -
  if ($LASTEXITCODE -ne 0) { throw 'Creating the Kubernetes environment secret failed.' }
  $turnUsername = $secretValues['IM_CONVERSATION_RTC_TURN_USERNAME']
  $turnPassword = $secretValues['IM_CONVERSATION_RTC_TURN_PASSWORD']
  if ([string]::IsNullOrWhiteSpace($turnUsername) -or [string]::IsNullOrWhiteSpace($turnPassword)) {
    throw 'IM_CONVERSATION_RTC_TURN_USERNAME and IM_CONVERSATION_RTC_TURN_PASSWORD are required for local Kubernetes deployment.'
  }
  & kubectl create secret generic open-chat-turn-credentials --namespace $Namespace --from-literal="TURN_USERNAME=$turnUsername" --from-literal="TURN_PASSWORD=$turnPassword" --dry-run=client -o yaml | & kubectl apply -f -
  if ($LASTEXITCODE -ne 0) { throw 'Creating the local TURN credentials secret failed.' }
} finally {
  if (Test-Path -LiteralPath $secretEnvFile) { Remove-Item -LiteralPath $secretEnvFile -Force }
}

Apply-Manifest -Name 'infrastructure.yaml'
$infrastructureDeployments = @('mysql', 'redis', 'mongodb', 'minio', 'rocketmq-namesrv', 'rocketmq-broker')
foreach ($deployment in $infrastructureDeployments) { Invoke-Kubectl -Arguments @('rollout', 'status', "deployment/$deployment", '--namespace', $Namespace, ("--timeout=$StartupTimeoutSeconds" + 's')) }
Invoke-Kubectl -Arguments @('delete', 'job/mysql-saas-init', '--namespace', $Namespace, '--ignore-not-found=true')
Apply-Manifest -Name 'mysql-saas-init-job.yaml'
Invoke-Kubectl -Arguments @('wait', '--for=condition=complete', 'job/mysql-saas-init', '--namespace', $Namespace, ("--timeout=$StartupTimeoutSeconds" + 's'))

$replacements = @{}
foreach ($name in $saasImages.Keys) {
  $replacements['__APP_IMAGE_' + $name.Replace('-', '_').ToUpperInvariant() + '__'] = $saasImages[$name]
}
$applicationDeployments = @('im-user-service', 'im-conversation-service', 'common-media-service', 'im-message-service', 'im-admin-service', 'im-access-ws', 'gateway', 'pc-admin', 'platform-identity-service', 'platform-tenant-service', 'platform-resource-service', 'platform-order-service', 'common-payment-service', 'platform-admin-service', 'platform-customer-service', 'platform-marketing-service', 'common-payment-channel-service', 'common-audit-service', 'common-sms-service', 'common-mail-service', 'group-idaas-service', 'saas-admin', 'saas-mobile', 'unified-portal')
Apply-RenderedManifest -Name 'applications.yaml' -Replacements $replacements -StartSuspended
if ($secretValues['MEDIA_ACCESS_KEY'] -eq $secretValues['MINIO_ROOT_USER']) {
  Apply-StrategicPatch -ResourceType 'deployment' -TargetName 'common-media-service' -PatchName 'media-root-credentials-patch.yaml'
}
Apply-RenderedManifest -Name 'gateway-admin.yaml' -Replacements $replacements -StartSuspended
Apply-RenderedManifest -Name 'saas.yaml' -Replacements $replacements -StartSuspended
$advertiseAddress = Get-LocalAdvertiseAddress
Invoke-Kubectl -Arguments @('set', 'env', 'deployment/im-user-service', "OIDC_ISSUER=http://$advertiseAddress`:30002", '--namespace', $Namespace)
$localOrigins = @(
  'http://127.0.0.1:5173', 'http://localhost:5173', 'http://127.0.0.1:30080', 'http://localhost:30080',
  'http://127.0.0.1:30081', 'http://localhost:30081', 'http://127.0.0.1:30082', 'http://localhost:30082',
  'http://10.0.2.2:30082',
  'http://127.0.0.1:30083', 'http://localhost:30083', "http://$advertiseAddress`:5173", "http://$advertiseAddress`:30080",
  "http://$advertiseAddress`:30081", "http://$advertiseAddress`:30082", "http://$advertiseAddress`:30083"
) -join ','
Invoke-Kubectl -Arguments @('set', 'env', 'deployment/gateway', "IM_GATEWAY_ALLOWED_ORIGINS=$localOrigins", '--namespace', $Namespace)
Invoke-Kubectl -Arguments @('set', 'env', 'deployment/im-access-ws', "IM_ACCESS_WS_ALLOWED_ORIGINS=$localOrigins", '--namespace', $Namespace)

foreach ($deployment in $applicationDeployments) { Set-LocalRecreateStrategy -Name $deployment }
foreach ($name in $applicationDeployments) {
  $entry = $saasManifest.services.PSObject.Properties[$name].Value
  Set-ApplicationImage -Name $name -Tag $entry.tag -MavenVersion $entry.moduleVersion -Revision $entry.sourceRevision -CreatedAt $createdAt -ArtifactHash '' -ReleaseId $releaseId
}

Invoke-Kubectl -Arguments @('scale', 'deployment/im-user-service', '--namespace', $Namespace, '--replicas=1')
Invoke-Kubectl -Arguments @('rollout', 'status', 'deployment/im-user-service', '--namespace', $Namespace, ("--timeout=$StartupTimeoutSeconds" + 's'))
& (Join-Path $root 'scripts\deploy\sync-oidc-client-registry.ps1') -Environment kind -Context "kind-$KindClusterName" -Namespace $Namespace -DatabaseSecret 'open-im-env' -RootDatabaseSecretKey 'DB_PASSWORD' -H5Base "http://$advertiseAddress`:30082" -AdditionalH5Origins $AdditionalH5Origins -AdditionalViteCOrigins $AdditionalViteCOrigins -AdditionalViteBOrigins $AdditionalViteBOrigins
if ($LASTEXITCODE -ne 0) { throw 'Kind OIDC client registry synchronization failed.' }
foreach ($deployment in ($applicationDeployments | Where-Object { $_ -ne 'im-user-service' })) {
  Invoke-Kubectl -Arguments @('scale', "deployment/$deployment", '--namespace', $Namespace, '--replicas=1')
  Invoke-Kubectl -Arguments @('rollout', 'status', "deployment/$deployment", '--namespace', $Namespace, ("--timeout=$StartupTimeoutSeconds" + 's'))
}
foreach ($name in ($saasImages.Keys | Sort-Object)) {
  $actualImage = (& kubectl get deployment $name --namespace $Namespace -o 'jsonpath={.spec.template.spec.containers[0].image}').Trim()
  if ($LASTEXITCODE -ne 0 -or $actualImage -ne $saasImages[$name]) { throw "Kind digest drift for $name. Expected '$($saasImages[$name])', got '$actualImage'." }
  $deployment = & kubectl get deployment $name --namespace $Namespace -o json | ConvertFrom-Json
  if ($LASTEXITCODE -ne 0) { throw "Cannot inspect deployment: $name" }
  $selector = ($deployment.spec.selector.matchLabels.PSObject.Properties | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ','
  $pods = & kubectl get pods --namespace $Namespace -l $selector -o json | ConvertFrom-Json
  if ($LASTEXITCODE -ne 0 -or !$pods.items) { throw "No running pods for $name" }
  foreach ($pod in $pods.items) {
    if ($pod.metadata.PSObject.Properties['deletionTimestamp']) { continue }
    $status = @($pod.status.containerStatuses | Where-Object { $_.name -eq $name })
    if ($status.Count -ne 1 -or !$status[0].ready) {
      throw "Kind runtime image digest mismatch: $($pod.metadata.name)"
    }
    if ([string]$status[0].imageID -ne $saasImages[$name]) {
      throw "Kind Pod image digest mismatch: $($pod.metadata.name)"
    }
    $containerId = ([string]$status[0].containerID) -replace '^containerd://', ''
    $nodeName = [string]$pod.spec.nodeName
    $runtimeRaw = (& docker exec $nodeName crictl inspect $containerId 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or !$runtimeRaw) { throw "Cannot inspect Kind runtime container: $($pod.metadata.name)" }
    try { $runtime = $runtimeRaw | ConvertFrom-Json } catch { throw "Invalid Kind runtime container document: $($pod.metadata.name)" }
    if ([string]$runtime.status.imageRef -ne $saasImages[$name]) {
      throw "Kind runtime image digest mismatch: $($pod.metadata.name)"
    }
  }
}
Remove-InactiveReplicaSets
Remove-ReleaseBuildWaste

Invoke-Kubectl -Arguments @('delete', 'job/minio-init', '--namespace', $Namespace, '--ignore-not-found=true')
Apply-Manifest -Name 'minio-init-job.yaml'
Invoke-Kubectl -Arguments @('wait', '--for=condition=complete', 'job/minio-init', '--namespace', $Namespace, ("--timeout=$StartupTimeoutSeconds" + 's'))

Stop-LocalPortForwards
Start-LocalNodePortProxy -Service 'gateway' -NodePort 30002 -ServicePort 3002
Start-LocalNodePortProxy -Service 'pc-admin' -NodePort 30080 -LocalPort 5173 -ServicePort 80
Start-LocalNodePortProxy -Service 'saas-admin' -NodePort 30081 -ServicePort 80
Start-LocalNodePortProxy -Service 'saas-mobile' -NodePort 30082 -ServicePort 80
Start-LocalNodePortProxy -Service 'unified-portal' -NodePort 30083 -ServicePort 80
Start-LocalNodePortProxy -Service 'minio-api' -NodePort 30900 -ServicePort 9000
Start-LocalNodePortProxy -Service 'minio-console' -NodePort 30901 -ServicePort 9001
$gatewayHealth = Invoke-HttpGetWithRetry -Uri 'http://127.0.0.1:30002/actuator/health'
$gatewayHealthBody = Get-HttpResponseBody -Content $gatewayHealth.Content
if ($gatewayHealthBody -notmatch '"status"\s*:\s*"UP"') { throw "Gateway health check failed: $gatewayHealthBody" }
$adminPage = Invoke-HttpGetWithRetry -Uri 'http://127.0.0.1:5173/'
$adminPageBody = Get-HttpResponseBody -Content $adminPage.Content
if ($adminPage.StatusCode -ne 200 -or $adminPageBody -notmatch 'id="app"') { throw 'Kubernetes admin page verification failed.' }
Invoke-Kubectl -Arguments @('get', 'pods,services', '--namespace', $Namespace)
Write-Host 'Kubernetes deployment is ready:'
Write-Host "  Bind:    $LocalBindAddress"
Write-Host '  Gateway: http://<host-ip>:30002'
Write-Host '  IM Admin: http://<host-ip>:5173'
Write-Host '  SaaS Admin: http://<host-ip>:30081'
Write-Host '  SaaS Mobile: http://<host-ip>:30082'
Write-Host '  Portal: http://<host-ip>:30083'
$releaseOutputDirectory = Join-Path $root '.outputs\releases'
$releaseOutputPath = Join-Path $releaseOutputDirectory ("$releaseId.json")
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'scripts\verify\release-status.ps1') -Namespace $Namespace -OutputPath $releaseOutputPath
if ($LASTEXITCODE -ne 0) { throw 'Kubernetes release status reporting failed.' }
