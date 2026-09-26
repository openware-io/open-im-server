[CmdletBinding(SupportsShouldProcess)]
param(
  [ValidatePattern('^(?:\d{1,3}\.){3}\d{1,3}/(?:[0-9]|[12][0-9]|3[0-2])$')]
  [string]$RemoteSubnet = '192.168.31.0/24',
  [ValidateRange(1, 65535)]
  [int[]]$Ports = @(30080),
  [string]$RuleName = 'OpenIM Kind LAN saas-mobile'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$existingRule = Get-NetFirewallRule -DisplayName $RuleName -ErrorAction SilentlyContinue
if ($existingRule) {
  if ($PSCmdlet.ShouldProcess($RuleName, 'Replace existing firewall rule')) {
    $existingRule | Remove-NetFirewallRule
  } else {
    return
  }
}

if ($PSCmdlet.ShouldProcess($RuleName, "Allow TCP $($Ports -join ', ') from $RemoteSubnet")) {
  New-NetFirewallRule -DisplayName $RuleName -Description 'Restricts Kind frontend access to the trusted LAN.' `
    -Direction Inbound -Action Allow -Enabled True -Profile Private -Protocol TCP -LocalPort $Ports `
    -RemoteAddress $RemoteSubnet | Out-Null
}
