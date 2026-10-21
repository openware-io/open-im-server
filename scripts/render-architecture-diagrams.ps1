# Render the D2 architecture diagrams to SVG.
# Usage: pwsh -File scripts/render-architecture-diagrams.ps1

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$d2 = Get-Command d2 -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
if (-not $d2) {
  $candidate = 'C:\Program Files\D2\d2.exe'
  if (Test-Path -LiteralPath $candidate) { $d2 = $candidate }
}
if (-not $d2) {
  throw 'D2 CLI not found. Install it with: winget install --id Terrastruct.D2 -e'
}

$diagrams = Join-Path $root 'docs\20-architecture\diagrams'
$jobs = @(
  @{ Name = '01-system-context'; Layout = 'dagre' },
  @{ Name = '02-container'; Layout = 'elk' },
  @{ Name = '03-deployment'; Layout = 'dagre' },
  @{ Name = '04-data-ownership'; Layout = 'tala' },
  @{ Name = '05-context-map'; Layout = 'elk' },
  @{ Name = '07-mq-topology'; Layout = 'elk' }
)

foreach ($job in $jobs) {
  $source = Join-Path $diagrams ('src\' + $job.Name + '.d2')
  $output = Join-Path $diagrams ($job.Name + '.svg')
  if (-not (Test-Path -LiteralPath $source)) { throw "Missing source: $source" }
  & $d2 --layout $job.Layout --theme 5 --pad 40 $source $output
  if ($LASTEXITCODE -ne 0) { throw "D2 failed for $($job.Name)" }
  Write-Host "Rendered $($job.Name).svg ($($job.Layout))"
}
