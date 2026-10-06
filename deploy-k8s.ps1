# Stable local Kind entry point. Without an explicit release manifest it performs
# the complete local build, image import and deployment workflow.
$hasManifest = @($args | Where-Object { [string]$_ -eq '-SaasReleaseManifestPath' }).Count -gt 0
$implementation = if ($hasManifest) { 'scripts\deploy\k8s.ps1' } else { 'scripts\deploy\kind-local.ps1' }
& (Join-Path $PSScriptRoot $implementation) @args
exit $LASTEXITCODE
