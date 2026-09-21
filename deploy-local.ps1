# Backward-compatible local process deployment entry point.
& (Join-Path $PSScriptRoot 'scripts\deploy\local.ps1') @args
exit $LASTEXITCODE
