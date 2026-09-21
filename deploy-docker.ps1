# Backward-compatible Docker Compose deployment entry point.
& (Join-Path $PSScriptRoot 'scripts\deploy\docker.ps1') @args
exit $LASTEXITCODE
