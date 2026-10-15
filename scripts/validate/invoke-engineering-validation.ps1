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

# Cross-platform entry (G-02): prefer pwsh when present, and use the POSIX wrapper outside Windows.
$isWindowsHost = $env:OS -eq 'Windows_NT'
$shellCommand = if (Get-Command pwsh -ErrorAction SilentlyContinue) { 'pwsh' } else { 'powershell' }
$shellArguments = @('-NoProfile')
if ($isWindowsHost) { $shellArguments += @('-ExecutionPolicy', 'Bypass') }
$mavenWrapper = if ($isWindowsHost) { Join-Path $root 'mvnw.cmd' } else { Join-Path $root 'mvnw' }
Write-Host "Gate entry: shell=$shellCommand, mavenWrapper=$mavenWrapper"

# Gates that are still in REPORT mode live in report-only-gates.txt with their clearing batch.
# They must be removed from that file as soon as their worklist reaches zero.
$reportOnlyGates = [System.Collections.Generic.HashSet[string]]::new()
$reportOnlyFilePath = Join-Path $PSScriptRoot 'report-only-gates.txt'
if (Test-Path -LiteralPath $reportOnlyFilePath -PathType Leaf) {
  foreach ($line in [System.IO.File]::ReadAllLines($reportOnlyFilePath, [System.Text.UTF8Encoding]::new($false, $true))) {
    $entry = ($line -split '\|')[0].Trim()
    if ($entry -and -not $entry.StartsWith('#')) { [void]$reportOnlyGates.Add($entry) }
  }
}

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
  @{ Name = 'powershell-ascii-only'; Arguments = @() },
  @{ Name = 'java-comment-quality'; Arguments = @() },
  @{ Name = 'java-deprecated-api-usage'; Arguments = @() },
  @{ Name = 'java-exception-logging'; Arguments = @() },
  @{ Name = 'java-logging-conventions'; Arguments = @() },
  @{ Name = 'java-silent-catch'; Arguments = @() },
  @{ Name = 'java-fully-qualified-names'; Arguments = @() },
  @{ Name = 'exception-handling'; Arguments = @() },
  @{ Name = 'maven-version-ownership'; Arguments = @() },
  @{ Name = 'persistence-dependency-boundaries'; Arguments = @() },
  @{ Name = 'rocketmq-contracts'; Arguments = @() },
  @{ Name = 'mq-registry'; Arguments = @() },
  @{ Name = 'secret-exposure'; Arguments = @('-ReportPath', (Join-Path $logDirectory "$timestamp-secret-exposure.txt")) },
  @{ Name = 'security-and-flyway-configuration'; Arguments = @() },
  @{ Name = 'service-api-boundaries'; Arguments = @() },
  @{ Name = 'domain-model-purity'; Arguments = @() },
  @{ Name = 'port-contract'; Arguments = @() },
  @{ Name = 'po-containment'; Arguments = @() },
  @{ Name = 'service-dependency-graph'; Arguments = @() },
  @{ Name = 'flyway-conventions'; Arguments = @() },
  @{ Name = 'flyway-immutability'; Arguments = @() },
  @{ Name = 'documentation-links'; Arguments = @() },
  @{ Name = 'standards-governance'; Arguments = @() },
  @{ Name = 'repository-hygiene'; Arguments = @() },
  @{ Name = 'tenant-sql'; Arguments = @() }
)

foreach ($validator in $validators) {
  $scriptPath = Join-Path $PSScriptRoot ("validate-$($validator.Name).ps1")
  $arguments = @($validator.Arguments)
  if ($reportOnlyGates.Contains($validator.Name)) {
    $arguments += '-ReportOnly'
    Write-Host "Gate '$($validator.Name)' is in REPORT mode (see scripts/validate/report-only-gates.txt)."
  }
  Invoke-LoggedCommand -Name $validator.Name -Command {
    & $shellCommand @shellArguments -File $scriptPath @arguments
  }
}

$mavenGoal = if ($Scope -eq 'Full') { 'clean verify' } else { 'validate' }
Invoke-LoggedCommand -Name ("maven-$Scope".ToLowerInvariant()) -Command {
  $arguments = @('-B', '-ntp') + ($mavenGoal -split ' ')
  & $mavenWrapper @arguments
}

Write-Host "Engineering validation passed for scope $Scope."
