param(
  [string]$GatewayUrl = 'http://127.0.0.1:3002',
  [Parameter(Mandatory = $true)][string]$DocumentationUsername,
  [Parameter(Mandatory = $true)][string]$DocumentationPassword,
  [string]$OutputDirectory = 'docs/contracts/openapi'
)

$ErrorActionPreference = 'Stop'
$credential = [System.Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(
  "$DocumentationUsername`:$DocumentationPassword"))
$headers = @{ Authorization = "Basic $credential" }
$resolvedOutput = Join-Path $PSScriptRoot "..\$OutputDirectory"
New-Item -ItemType Directory -Force -Path $resolvedOutput | Out-Null

foreach ($service in 'user', 'message', 'conversation', 'admin') {
  $response = Invoke-WebRequest -UseBasicParsing -Headers $headers "$GatewayUrl/_docs/$service/api-docs"
  if ($response.StatusCode -ne 200) { throw "$service OpenAPI endpoint returned $($response.StatusCode)" }
  $json = $response.Content | ConvertFrom-Json
  if ([string]::IsNullOrWhiteSpace($json.openapi) -or $null -eq $json.paths) {
    throw "$service OpenAPI endpoint did not return a valid specification"
  }
  [IO.File]::WriteAllText((Join-Path $resolvedOutput "$service.json"), $response.Content + [Environment]::NewLine,
    [Text.UTF8Encoding]::new($false))
  Write-Output "Exported $service OpenAPI snapshot"
}
