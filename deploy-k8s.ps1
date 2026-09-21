# Backward-compatible Docker Desktop Kubernetes deployment entry point.
& (Join-Path $PSScriptRoot 'scripts\deploy\k8s.ps1') @args
exit $LASTEXITCODE
