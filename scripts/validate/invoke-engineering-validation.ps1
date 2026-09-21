[CmdletBinding()]
param(
  [ValidateSet('Changed', 'Full')]
  [string]$Scope = 'Changed'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logDirectory = Join-Path $root ('.outputs\logs\build\' + (Get-Date -Format 'yyyyMMdd'))
[void](New-Item -ItemType Directory -Force -Path $logDirectory)

function Invoke-LoggedCommand {
  param(
    [string]$Name,
    [scriptblock]$Command
  )

  $logPath = Join-Path $logDirectory ("$timestamp-$Name.log")
  Write-Host "Running $Name. Log: $logPath"
  $previousErrorActionPreference = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    & $Command 2>&1 | Tee-Object -FilePath $logPath
  } finally {
    $ErrorActionPreference = $previousErrorActionPreference
  }
  if ($LASTEXITCODE -ne 0) {
    throw "$Name failed with exit code $LASTEXITCODE. See $logPath."
  }
}

$validators = @(
  @{ Name = 'text-encoding'; Arguments = @('-Strict') },
  @{ Name = 'powershell-utf8-safety'; Arguments = @() },
  @{ Name = 'java-comment-quality'; Arguments = @() },
  @{ Name = 'java-deprecated-api-usage'; Arguments = @() },
  @{ Name = 'java-exception-logging'; Arguments = @() },
  @{ Name = 'java-logging-conventions'; Arguments = @() },
  @{ Name = 'exception-handling'; Arguments = @() },
  @{ Name = 'maven-version-ownership'; Arguments = @() },
  @{ Name = 'persistence-dependency-boundaries'; Arguments = @() },
  @{ Name = 'rocketmq-contracts'; Arguments = @() },
  @{ Name = 'secret-exposure'; Arguments = @('-ReportPath', (Join-Path $logDirectory "$timestamp-secret-exposure.txt")) },
  @{ Name = 'security-and-flyway-configuration'; Arguments = @() },
  @{ Name = 'service-api-boundaries'; Arguments = @() },
  @{ Name = 'service-dependency-graph'; Arguments = @() },
  @{ Name = 'flyway-module-boundaries'; Arguments = @() },
  @{ Name = 'tenant-sql'; Arguments = @('-Path', (Join-Path $root 'platform-services')) }
)

foreach ($validator in $validators) {
  $scriptPath = Join-Path $PSScriptRoot ("validate-$($validator.Name).ps1")
  Invoke-LoggedCommand -Name $validator.Name -Command {
    & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath @($validator.Arguments)
  }
}

$mavenGoal = if ($Scope -eq 'Full') { 'clean verify' } else { 'validate' }
Invoke-LoggedCommand -Name ("maven-$Scope".ToLowerInvariant()) -Command {
  $arguments = @('-B', '-ntp') + ($mavenGoal -split ' ')
  & (Join-Path $root 'mvnw.cmd') @arguments
}

Write-Host "Engineering validation passed for scope $Scope."
