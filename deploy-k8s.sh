#!/usr/bin/env bash
#
# Cross-platform local Kind entry point.
#
# The deployment implementation is PowerShell so it can share the exact same
# recovery/state-machine code on Windows, Linux and macOS. Linux/macOS users
# need PowerShell 7 (`pwsh`) on PATH; see docs/60-deployment/LOCAL.md.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if ! command -v pwsh >/dev/null 2>&1; then
  echo "PowerShell 7 (pwsh) is required: https://learn.microsoft.com/powershell/scripting/install/installing-powershell" >&2
  exit 1
fi

exec pwsh -NoProfile -File "$ROOT/deploy-k8s.ps1" "$@"
