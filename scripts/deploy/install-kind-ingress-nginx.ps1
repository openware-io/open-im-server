[CmdletBinding()]
param(
  [string]$Context = 'kind-open-im-local',
  [ValidateRange(30000, 32767)]
  [int]$HttpNodePort = 30080,
  [ValidateRange(30000, 32767)]
  [int]$HttpsNodePort = 30443
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$controllerVersion = 'v1.12.1'
$manifestUrl = "https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-$controllerVersion/deploy/static/provider/kind/deploy.yaml"
$manifestSha256 = 'CB84B0EA747C9149CCE08AEF4E95B1E55F183F07299F40E7009043E960A0133F'
$tempPath = Join-Path ([System.IO.Path]::GetTempPath()) ("open-im-ingress-nginx-$controllerVersion.yaml")
$bundledManifestPath = Join-Path $PSScriptRoot "ingress-nginx-kind-$controllerVersion.yaml"

try {
  $controlPlane = (& kubectl --context $Context get nodes -o name | Where-Object { $_ -match 'control-plane$' } | Select-Object -First 1)
  if ([string]::IsNullOrWhiteSpace($controlPlane)) { throw "Kind control-plane node was not found for context $Context." }
  & kubectl --context $Context label $controlPlane ingress-ready=true --overwrite
  if ($LASTEXITCODE -ne 0) { throw 'Failed to label the Kind control-plane for ingress-nginx.' }
  $installed = (& kubectl --context $Context --namespace ingress-nginx get deployment ingress-nginx-controller --ignore-not-found -o name 2>$null | Out-String).Trim()
  if (!$installed) {
    if (Test-Path -LiteralPath $bundledManifestPath) {
      Copy-Item -LiteralPath $bundledManifestPath -Destination $tempPath -Force
      Write-Host "Using bundled ingress-nginx $controllerVersion manifest."
    } else {
      Invoke-WebRequest -UseBasicParsing -Uri $manifestUrl -OutFile $tempPath
    }
    $actualSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $tempPath).Hash
    if ($actualSha256 -ne $manifestSha256) {
      throw "Ingress-nginx manifest checksum mismatch. Expected $manifestSha256, got $actualSha256."
    }
    & kubectl --context $Context apply --filename $tempPath
    if ($LASTEXITCODE -ne 0) { throw 'Failed to apply the pinned ingress-nginx manifest.' }
  } else {
    Write-Host "Reusing installed ingress-nginx controller in context $Context."
  }

  $patch = @{ spec = @{ ports = @(
    @{ name = 'http'; port = 80; protocol = 'TCP'; targetPort = 'http'; nodePort = $HttpNodePort }
    @{ name = 'https'; port = 443; protocol = 'TCP'; targetPort = 'https'; nodePort = $HttpsNodePort }
  ) } } | ConvertTo-Json -Compress -Depth 6
  $patchFile = Join-Path ([System.IO.Path]::GetTempPath()) ("open-im-ingress-nodeports-" + [guid]::NewGuid().ToString() + '.json')
  [System.IO.File]::WriteAllText($patchFile, $patch, [System.Text.UTF8Encoding]::new($false))
  & kubectl --context $Context --namespace ingress-nginx patch service ingress-nginx-controller --type merge --patch-file $patchFile
  $patchExitCode = $LASTEXITCODE
  if (Test-Path -LiteralPath $patchFile) { Remove-Item -LiteralPath $patchFile -Force }
  if ($patchExitCode -ne 0) { throw 'Failed to pin the ingress-nginx HTTP/HTTPS NodePorts.' }
  & kubectl --context $Context --namespace ingress-nginx rollout status deployment/ingress-nginx-controller --timeout=180s
  if ($LASTEXITCODE -ne 0) { throw 'Ingress-nginx controller did not become ready.' }
  Write-Output "ingress-nginx $controllerVersion is ready on HTTP NodePort $HttpNodePort and HTTPS NodePort $HttpsNodePort."
}
finally {
  if (Test-Path -LiteralPath $tempPath) { Remove-Item -LiteralPath $tempPath -Force }
}
