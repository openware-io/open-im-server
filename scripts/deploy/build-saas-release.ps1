[CmdletBinding()]
param(
  [string]$ReleaseManifestPath,
  [switch]$SkipPackage,
  [switch]$FormalRelease,
  [string[]]$FormalTargets = @(),
  # 增量开发发版：只构建/推送列出的服务，清单里也只列出这些服务（配合 k8s-scoped.ps1 部署）。
  # 留空表示按既有行为构建全部服务；不能与 -FormalRelease 同时使用（正式发版用 -FormalTargets）。
  [string[]]$Targets = @(),
  [string]$Registry = 'crpi-2xbf44rg544imbew.cn-hangzhou.personal.cr.aliyuncs.com',
  [string]$RepositoryNamespace = 'meta-cogni'
)
$ErrorActionPreference = 'Stop'; Set-StrictMode -Version Latest
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$projects = @{ 'pc-admin'='D:\projects\cnb\gv_chat_admin'; 'saas-admin'='D:\projects\cnb\gv_saas_admin'; 'saas-mobile'='D:\projects\cnb\gv_saas_mobile'; 'unified-portal'=(Join-Path $root 'portal') }
$createdAt=(Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'); $timestamp=(Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$revision=(& git -C $root rev-parse --short HEAD).Trim(); if(!$revision){throw 'Cannot resolve git revision.'}
$registryPrefix="$($Registry.TrimEnd('/'))/$($RepositoryNamespace.Trim('/'))"
$imageTagSuffix = if ($FormalRelease) { '' } else { '-SNAPSHOT' }
$allowTagOverwrite = -not $FormalRelease
function Assert-Clean([string]$p){$status=@(& git -C $p status --porcelain);$gitRoot=[IO.Path]::GetFullPath(((& git -C $p rev-parse --show-toplevel).Trim())).TrimEnd('\');$allowed=@();if($gitRoot -eq $root){$allowed=@(' M k8s/README.md',' M k8s/local/saas.yaml',' M scripts/deploy/build-saas-release.ps1',' M scripts/deploy/ack-saas-candidate.ps1',' M scripts/deploy/k8s.ps1')};$unexpected=@($status|Where-Object{$_ -and $_ -notin $allowed});if($unexpected.Count){throw "Refusing release from dirty worktree: $p`n$($unexpected -join "`n")"}}
function Get-MavenVersion([string]$p){[xml]$x=Get-Content -Raw -LiteralPath $p;$n=$x.SelectSingleNode('/*[local-name()="project"]/*[local-name()="version"]');if(!$n){$n=$x.SelectSingleNode('/*[local-name()="project"]/*[local-name()="parent"]/*[local-name()="version"]')};if(!$n){throw "Version missing: $p"};$n.InnerText.Trim()}
function Get-PackageVersion([string]$p){
  $packageFile=Join-Path $p 'package.json';$versionFile=Join-Path $p 'VERSION'
  $packageVersion=if(Test-Path $packageFile){[string]((Get-Content -Raw -Encoding utf8 $packageFile|ConvertFrom-Json).version)}else{''}
  $fileVersion=if(Test-Path $versionFile){(Get-Content -Raw -Encoding utf8 $versionFile).Trim()}else{''}
  if(!$packageVersion -and !$fileVersion){throw "Version source missing: $p"}
  if($packageVersion -and $fileVersion -and $packageVersion -ne $fileVersion){throw "Version sources differ: $packageFile=$packageVersion; $versionFile=$fileVersion"}
  if($fileVersion){return $fileVersion}
  return $packageVersion
}
function Get-RegistryDigest([string]$image,[int]$MaxAttempts=10){
  for($a=1;$a -le $MaxAttempts;$a++){
    $saved=$ErrorActionPreference
    try{$ErrorActionPreference='Continue';$json=& docker buildx imagetools inspect $image --format '{{json .Manifest}}' 2>$null;$code=$LASTEXITCODE}finally{$ErrorActionPreference=$saved}
    if($code -eq 0){
      try{$m=($json|Out-String|ConvertFrom-Json);$d=[string]$m.digest;if($d -match '^sha256:[a-f0-9]{64}$'){return $d}}catch{}
    }
    if($a -lt $MaxAttempts){$delay=[Math]::Min(30,[int][Math]::Pow(2,$a-1));Start-Sleep -Seconds $delay}
  }
  return $null
}
function Get-RegistryImageLabels([string]$image){
  $saved=$ErrorActionPreference
  try{$ErrorActionPreference='Continue';$raw=(& docker buildx imagetools inspect $image --format '{{json .Image.Config.Labels}}' 2>$null|Out-String).Trim();$code=$LASTEXITCODE}finally{$ErrorActionPreference=$saved}
  if($code -ne 0 -or !$raw){return $null}
  try{return $raw|ConvertFrom-Json}catch{return $null}
}
function Get-LocalImageRevision([string]$image,[string]$tag){
  # Windows PowerShell 5.1 会把原生命令的 stderr 提升为 ErrorRecord；在 $ErrorActionPreference='Stop'
  # 下 `docker image inspect` 查不到本地镜像会直接终止整个发布构建（bump 版本后的新 tag 必然查不到）。
  # 与文件内其它 docker 辅助函数保持一致，临时降级 ErrorActionPreference 并只按退出码判定。
  $saved=$ErrorActionPreference
  try{$ErrorActionPreference='Continue';$raw=(& docker image inspect $image --format '{{json .Config.Labels}}' 2>$null|Out-String).Trim();$code=$LASTEXITCODE}finally{$ErrorActionPreference=$saved}
  if($code -ne 0 -or !$raw){return ''}
  try{$labels=$raw|ConvertFrom-Json}catch{return ''}
  if([string]$labels.'org.opencontainers.image.version' -ne $tag){return ''}
  return [string]$labels.'org.opencontainers.image.revision'
}
function Get-ExistingReleaseImage([string]$name,[ValidatePattern('^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-SNAPSHOT)?$')][string]$tag,[int]$MaxAttempts=10){
  $image="$registryPrefix/$name`:$tag";$digest=Get-RegistryDigest $image $MaxAttempts
  if(!$digest){return $null}
  $pulled=$false
  for($attempt=1;$attempt -le 3 -and !$pulled;$attempt++){
    $saved=$ErrorActionPreference
    try{$ErrorActionPreference='Continue';& docker pull $image | Out-Null;$pullCode=$LASTEXITCODE}finally{$ErrorActionPreference=$saved}
    if($pullCode -eq 0){$pulled=$true;break}
    if($attempt -lt 3){Start-Sleep -Seconds (5*$attempt)}
  }
  if(!$pulled){throw "Existing release image cannot be pulled: $image"}
  $labelsJson=(& docker image inspect $image --format '{{json .Config.Labels}}' 2>$null|Out-String).Trim()
  try{$labels=$labelsJson|ConvertFrom-Json}catch{throw "Existing release image labels cannot be read: $image"}
  $version=[string]$labels.'org.opencontainers.image.version';$sourceRevision=[string]$labels.'org.opencontainers.image.revision'
  if($version -ne $tag -or !$sourceRevision){throw "Existing release image lacks valid immutable metadata: $image"}
  Write-Host "Reusing existing immutable image: $image@$digest"
  [ordered]@{tag=$tag;registry=$registryPrefix;image=$image;digest=$digest;imageDigest="$registryPrefix/$name@$digest";sourceRevision=$sourceRevision}
}
function Invoke-Docker([string[]]$DockerArgs){
  $saved=$ErrorActionPreference
  $ErrorActionPreference='Continue'
  try{
    $out=& docker @DockerArgs 2>&1
    $code=$LASTEXITCODE
  }finally{
    $ErrorActionPreference=$saved
  }
  foreach($l in $out){
    if($l -is [System.Management.Automation.ErrorRecord]){Write-Warning ([string]$l)}elseif($null -ne $l){Write-Output ([string]$l)}
  }
  return $code
}
function Publish-Image([string]$name,[string]$tag,[bool]$AllowOverwrite,[string]$SourceRevision){
  if($tag -eq 'latest' -or $tag -match 'dirty' -or $tag -match '-dev\.' -or $tag -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?$'){throw "Release image tag must be SemVer or SemVer-SNAPSHOT (no latest/dirty/dev suffix): $tag"}
  if(!$FormalRelease -and $tag -notmatch '-SNAPSHOT$'){throw "Development image tag must end with -SNAPSHOT: $tag"}
  if($FormalRelease -and $tag -match '-SNAPSHOT$'){throw "Formal release image tag must not end with -SNAPSHOT: $tag"}
  $image="$registryPrefix/$name`:$tag"
  if(!$AllowOverwrite -and (Get-RegistryDigest $image 1)){throw "Formal release image tag already exists and must not be overwritten: $image"}
  $id=(& docker image inspect $image --format '{{.Id}}').Trim()
  if($LASTEXITCODE -ne 0 -or !$id){throw "Built image is missing: $image"}
  $digest=$null
  for($a=1;$a -le 10 -and !$digest;$a++){
    $pushCode=Invoke-Docker -DockerArgs @('push',$image)
    $candidateDigest=Get-RegistryDigest $image 1
    $candidateLabels=if($candidateDigest){Get-RegistryImageLabels $image}else{$null}
    if($candidateLabels -and [string]$candidateLabels.'org.opencontainers.image.version' -eq $tag -and [string]$candidateLabels.'org.opencontainers.image.revision' -eq $SourceRevision){
      $digest=$candidateDigest
    }elseif($pushCode -ne 0){
      Write-Warning "Push attempt $a failed for $image; ACR does not yet expose the expected image labels."
    }elseif($candidateDigest){
      Write-Warning "Push attempt $a completed for $image, but ACR still exposes a different image revision; retrying."
    }
    if(!$digest){$delay=[Math]::Min(30,[int][Math]::Pow(2,$a-1));Write-Warning "Push attempt $a did not resolve for $image; retrying in ${delay}s.";Start-Sleep -Seconds $delay}
  }
  if(!$digest){throw "Docker push failed after 10 attempts: $image"}
  $digestImage="$registryPrefix/$name@$digest"
  $repoDigests=@((& docker image inspect $image --format '{{json .RepoDigests}}' 2>$null) | Out-String | ConvertFrom-Json)
  $remoteMatch=$false
  foreach($rd in $repoDigests){if([string]$rd -eq $digestImage){$remoteMatch=$true;break}}
  if(!$remoteMatch){
    if($digest -eq $id){$remoteMatch=$true}else{throw "ACR content differs from built image: $image (expected $digestImage)"}
  }
  if(!$remoteMatch){throw "ACR content differs from built image: $image"}
  [ordered]@{tag=$tag;registry=$registryPrefix;image=$image;digest=$digest;imageDigest=$digestImage}
}
Assert-Clean $root;foreach($p in $projects.Values){Assert-Clean $p};& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'scripts\validate\validate-maven-version-ownership.ps1');if($LASTEXITCODE){throw 'Maven version ownership validation failed.'}
$defs=[ordered]@{ 'im-user-service'=@('im-services/user/im-user-service/target/im-user-service-*.jar','im-services/user/pom.xml');'im-message-service'=@('im-services/message/im-message-service/target/im-message-service-*.jar','im-services/message/pom.xml');'im-conversation-service'=@('im-services/conversation/im-conversation-service/target/im-conversation-service-*.jar','im-services/conversation/pom.xml');'common-media-service'=@('common-services/media/common-media-service/target/common-media-service-*.jar','common-services/media/pom.xml');'im-admin-service'=@('im-services/admin/im-admin-service/target/im-admin-service-*.jar','im-services/admin/pom.xml');'im-access-ws'=@('gateways/im-access-ws/target/im-access-ws-*.jar','gateways/im-access-ws/pom.xml');'gateway'=@('gateways/gateway/target/gateway-*.jar','gateways/gateway/pom.xml');'platform-identity-service'=@('platform-services/identity/platform-identity-service/target/platform-identity-service-*.jar','platform-services/identity/pom.xml');'platform-tenant-service'=@('platform-services/tenant/platform-tenant-service/target/platform-tenant-service-*.jar','platform-services/tenant/pom.xml');'platform-resource-service'=@('platform-services/resource/platform-resource-service/target/platform-resource-service-*.jar','platform-services/resource/pom.xml');'platform-order-service'=@('platform-services/order/platform-order-service/target/platform-order-service-*.jar','platform-services/order/pom.xml');'common-payment-service'=@('common-services/payment/common-payment-service/target/common-payment-service-*.jar','common-services/payment/pom.xml');'platform-admin-service'=@('platform-services/admin/platform-admin-service/target/platform-admin-service-*.jar','platform-services/admin/pom.xml');'platform-customer-service'=@('platform-services/customer/platform-customer-service/target/platform-customer-service-*.jar','platform-services/customer/pom.xml');'platform-marketing-service'=@('platform-services/marketing/platform-marketing-service/target/platform-marketing-service-*.jar','platform-services/marketing/pom.xml');'common-payment-channel-service'=@('common-services/payment-channel/common-payment-channel-service/target/common-payment-channel-service-*.jar','common-services/payment-channel/pom.xml');'common-audit-service'=@('common-services/audit/common-audit-service/target/common-audit-service-*.jar','common-services/audit/pom.xml');'common-sms-service'=@('common-services/sms/common-sms-service/target/common-sms-service-*.jar','common-services/sms/pom.xml');'common-mail-service'=@('common-services/mail/common-mail-service/target/common-mail-service-*.jar','common-services/mail/pom.xml');'group-idaas-service'=@('group-services/idaas/group-idaas-service/target/group-idaas-service-*.jar','group-services/idaas/pom.xml') }
$knownFormalTargets = @($defs.Keys) + @($projects.Keys)
$FormalTargets = @($FormalTargets | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
$Targets = @($Targets | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
if ($FormalRelease -and $Targets.Count -gt 0) {
  throw '-Targets is the incremental development switch and cannot be combined with -FormalRelease; use -FormalTargets for formal releases.'
}
if ($FormalRelease -and $FormalTargets.Count -eq 0) {
  throw 'Formal release requires -FormalTargets. List only the services or frontends intentionally promoted in this release.'
}
# 本次实际构建/推送的服务集合：正式发版取 -FormalTargets，增量开发发版取 -Targets，留空表示全部。
# 外层 @() 不可去掉：StrictMode 下 Select-Object -Unique 单值结果会退化成字符串，.Count 会抛错。
$selectedTargets = @(@($FormalTargets) + @($Targets) | Select-Object -Unique)
$unknownFormalTargets = @($selectedTargets | Where-Object { $_ -notin $knownFormalTargets })
if ($unknownFormalTargets.Count -gt 0) {
  throw "Unknown release target(s): $($unknownFormalTargets -join ', '). Valid targets: $($knownFormalTargets -join ', ')."
}
if ($selectedTargets.Count -gt 0) {
  Write-Host "Incremental release targets: $($selectedTargets -join ', ')"
}
foreach($e in $defs.GetEnumerator()) {
  $name = $e.Key
  if($selectedTargets.Count -gt 0 -and $selectedTargets -notcontains $name) { continue }
  $leafPomRelative = $e.Value[0] -replace '/target/.*$', '/pom.xml'
  $leafPomVersion = Get-MavenVersion (Join-Path $root $leafPomRelative)
  $isFormalTarget = $FormalTargets -contains $name
  if($FormalRelease -and $isFormalTarget -and $leafPomVersion -match '-SNAPSHOT$') {
    throw "Formal target Maven leaf version must not end with -SNAPSHOT: ${name}:$leafPomVersion"
  }
  if(!$FormalRelease -and $leafPomVersion -notmatch '-SNAPSHOT$') {
    throw "Development Maven leaf version must end with -SNAPSHOT: ${name}:$leafPomVersion"
  }
}
if(!$SkipPackage){
  # Maven/JVM 会把提示（如 sun.misc.Unsafe 弃用）写到 stderr；本脚本顶部是
  # $ErrorActionPreference='Stop'，直接调用会让原生 stderr 变成终止性错误、把构建打断
  # （表现为「只看到一条 WARNING 就退出 1」）。与下方 docker 调用同样处理：临时 Continue，
  # 只以 $LASTEXITCODE 判定成败。
  $savedMvn=$ErrorActionPreference
  try{$ErrorActionPreference='Continue';& (Join-Path $root 'mvnw.cmd') -B -ntp clean package;$code=$LASTEXITCODE}finally{$ErrorActionPreference=$savedMvn}
  if($code){throw 'Maven package failed.'}
}
$out=[ordered]@{}
foreach($e in $defs.GetEnumerator()) {
  $name=$e.Key
  if($selectedTargets.Count -gt 0 -and $selectedTargets -notcontains $name) { continue }
  $ver=(Get-MavenVersion (Join-Path $root $e.Value[1])) -replace '-SNAPSHOT$',''
  if($ver -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$'){throw "Module version must be SemVer without a build suffix: ${name}:$ver"}
  $tag="$ver$imageTagSuffix"
  $leafPomRelative = $e.Value[0] -replace '/target/.*$', '/pom.xml'
  $leafPomVersion = Get-MavenVersion (Join-Path $root $leafPomRelative)
  $isFormalTarget = $FormalTargets -contains $name
  if($FormalRelease -and !$isFormalTarget) { continue }
  $p=$null
  if($FormalRelease) {
    if($isFormalTarget) {
      if($leafPomVersion -match '-SNAPSHOT$'){throw "Formal target Maven leaf version must not end with -SNAPSHOT: ${name}:$leafPomVersion"}
      $p=Get-ExistingReleaseImage $name $tag 1
      if($p -and $p.sourceRevision -ne $revision){throw "Formal target image belongs to a different source revision and cannot be reused: ${name}:$tag ($($p.sourceRevision) != $revision)"}
    }
  } elseif($leafPomVersion -notmatch '-SNAPSHOT$') {
    throw "Development Maven leaf version must end with -SNAPSHOT: ${name}:$leafPomVersion"
  }
  if(!$p){
    $jar=@(Get-ChildItem (Join-Path $root $e.Value[0]) -File | Where-Object { $_.Name -eq "$name-$tag.jar" })
    if($jar.Count -ne 1){throw "JAR count for ${name}: $($jar.Count)"}
    # The executable leaf JAR is the authoritative Maven version for the image.
    # Development builds must therefore come from a leaf POM carrying -SNAPSHOT;
    # a pure domain-parent version is rejected below instead of silently producing a dev image.
    $jarVersion=$jar[0].BaseName.Substring($name.Length + 1)
    if($jarVersion -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?$'){throw "JAR version is not SemVer or SemVer-SNAPSHOT: ${name}:$jarVersion"}
    if(!$FormalRelease -and $jarVersion -notmatch '-SNAPSHOT$'){throw "Development Maven leaf version must end with -SNAPSHOT: ${name}:$jarVersion"}
    if($FormalRelease -and $jarVersion -match '-SNAPSHOT$'){throw "Formal Maven leaf version must not end with -SNAPSHOT: ${name}:$jarVersion"}
    $rel=$jar[0].FullName.Substring($root.Length+1).Replace('\','/')
    $tag=$jarVersion
    $image="$registryPrefix/$name`:$tag"
    $imageRevision=Get-LocalImageRevision -image $image -tag $tag
    if($imageRevision -ne $revision) {
      $imageRevision=$revision
      & docker build --quiet --pull=false --build-arg "JAR_PATH=$rel" --build-arg "IMAGE_VERSION=$tag" --build-arg "IMAGE_REVISION=$imageRevision" --build-arg "IMAGE_CREATED=$createdAt" -t $image $root
      if($LASTEXITCODE){throw "Docker build failed: $name"}
    }
    $p=Publish-Image $name $tag $allowTagOverwrite $imageRevision
    $p['sourceRevision']=$imageRevision
  }
  $out[$name]=[ordered]@{moduleVersion=$tag;tag=$tag;sourceRevision=$p.sourceRevision;registry=$p.registry;image=$p.image;digest=$p.digest;imageDigest=$p.imageDigest}
}
foreach($e in $projects.GetEnumerator()) {
  $name=$e.Key
  if($selectedTargets.Count -gt 0 -and $selectedTargets -notcontains $name) { continue }
  $ver=Get-PackageVersion $e.Value
  if($ver -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$'){throw "Project version must be SemVer without a build suffix: ${name}:$ver"}
  $tag="$ver$imageTagSuffix"
  $isFormalTarget = $FormalTargets -contains $name
  if($FormalRelease -and !$isFormalTarget) { continue }
  $projectRevision=(& git -C $e.Value rev-parse --short HEAD).Trim()
  if(!$projectRevision){throw "Cannot resolve source revision: $name"}
  $p=$null
  if($FormalRelease){
    $p=Get-ExistingReleaseImage $name $tag 1
    if($p -and $p.sourceRevision -ne $projectRevision){throw "Formal target image belongs to a different source revision and cannot be reused: ${name}:$tag ($($p.sourceRevision) != $projectRevision)"}
  }
  if(!$p){
    $args=@()
    if($name -eq 'saas-admin'){$args+=@('--build-arg','VITE_API_BASE_URL=/')}
    $image="$registryPrefix/$name`:$tag"
    $imageRevision=Get-LocalImageRevision -image $image -tag $tag
    if($imageRevision -ne $projectRevision) {
      $imageRevision=$projectRevision
      & docker build --quiet --pull=false @args --build-arg "IMAGE_VERSION=$tag" --build-arg "IMAGE_REVISION=$imageRevision" --build-arg "IMAGE_CREATED=$createdAt" -t $image $e.Value
      if($LASTEXITCODE){throw "Docker build failed: $name"}
    }
    $p=Publish-Image $name $tag $allowTagOverwrite $imageRevision
    $p['sourceRevision']=$imageRevision
  }
  $out[$name]=[ordered]@{moduleVersion=$ver;tag=$tag;sourceRevision=$p.sourceRevision;registry=$p.registry;image=$p.image;digest=$p.digest;imageDigest=$p.imageDigest}
}
if(!$ReleaseManifestPath){$ReleaseManifestPath=Join-Path $root ".outputs\releases\saas-$timestamp-$revision.json"}
$dir=Split-Path -Parent $ReleaseManifestPath
if(!(Test-Path $dir)){New-Item -ItemType Directory $dir|Out-Null}
$releaseType=if($FormalRelease){'formal'}else{'development'}
$buildIdentity="$releaseType.$timestamp.$revision"
[ordered]@{schemaVersion=2;createdAt=$createdAt;buildIdentity=$buildIdentity;releaseType=$releaseType;allowTagOverwrite=$allowTagOverwrite;incremental=($selectedTargets.Count -gt 0);sourceRevision=$revision;registry=$registryPrefix;deploymentTargets=@($out.Keys);services=$out}|ConvertTo-Json -Depth 8|Set-Content -Encoding utf8 $ReleaseManifestPath
Write-Output "SAAS_RELEASE_MANIFEST=$ReleaseManifestPath"
