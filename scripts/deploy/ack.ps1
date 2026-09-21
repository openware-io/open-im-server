[CmdletBinding()]
param(
  [Parameter(Mandatory)] [string]$ReleaseManifestPath,
  [string]$Namespace = 'im-business',
  [string]$Registry = 'crpi-2xbf44rg544imbew.cn-hangzhou.personal.cr.aliyuncs.com',
  [string]$RepositoryNamespace = 'meta-cogni',
  [ValidateRange(120, 1800)] [int]$StartupTimeoutSeconds = 900
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$delegate = Join-Path $PSScriptRoot 'ack-saas-candidate.ps1'
if (!(Test-Path -LiteralPath $delegate)) {
  throw "ACK deployment script is missing: $delegate"
}

& $delegate `
  -ReleaseManifestPath $ReleaseManifestPath `
  -Namespace $Namespace `
  -Registry $Registry `
  -RepositoryNamespace $RepositoryNamespace `
  -StartupTimeoutSeconds $StartupTimeoutSeconds

if ($LASTEXITCODE -ne 0) {
  throw 'ACK deployment failed.'
}
