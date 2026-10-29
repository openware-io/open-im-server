#!/usr/bin/env pwsh
[CmdletBinding()]
param(
  [string]$Namespace = 'open-im-local',
  [string]$KindClusterName = 'open-im-local'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $PSScriptRoot 'kind-recovery.ps1')
$previous = $ErrorActionPreference
try {
  $ErrorActionPreference = 'SilentlyContinue'
  $clusters = @(& kind get clusters 2>$null)
} finally {
  $ErrorActionPreference = $previous
}
if ($clusters -notcontains $KindClusterName) {
  Write-Host "Kind cluster '$KindClusterName' does not exist." -ForegroundColor Yellow
  exit 2
}
exit (Get-KindLocalStatus -ClusterName $KindClusterName -Namespace $Namespace)
