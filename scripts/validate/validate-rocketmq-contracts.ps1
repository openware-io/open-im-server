[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$producer = Join-Path $root 'sdk\infrastructure\src\main\java\io\openware\infrastructure\mq\remoting\RemotingMqProducer.java'
$consumerFactory = Join-Path $root 'sdk\infrastructure\src\main\java\io\openware\infrastructure\mq\remoting\RemotingMqConsumerFactory.java'
$violations = [System.Collections.Generic.List[string]]::new()

foreach ($path in @($producer, $consumerFactory)) {
  if (-not (Test-Path $path)) {
    $violations.Add("Missing RocketMQ contract implementation: $path")
    continue
  }
  $text = [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false, $true))
  if ($text -notmatch 'org\.apache\.rocketmq') {
    $violations.Add("RocketMQ implementation lacks RocketMQ dependency: $path")
  }
}

if ($violations.Count -gt 0) {
  $violations | ForEach-Object { Write-Error $_ }
  exit 1
}

Write-Host 'RocketMQ contract validation passed.'
