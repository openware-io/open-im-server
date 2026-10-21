# Contract Snapshots

[中文](README.md) | [English](README.en.md)

`openapi/` stores versioned REST specifications served by the gateway documentation route. Entry point: [OpenAPI snapshot index](openapi/README.md).

Run `scripts/export-openapi-snapshots.ps1` in a local environment to regenerate snapshots. Credentials must come from untracked local configuration and must never be committed.

The WebSocket v1 contract lives in [Protocol](../PROTOCOL.md); event names are defined by `sdk/protocol-ws`.
