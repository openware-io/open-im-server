# im-access-ws

[中文](README.md) | [English](README.en.md)

> English summary; the Chinese document is normative.

## Responsibility

- WebSocket access: connection authentication, presence, cross-instance broadcast and message dispatch.

## Boundary

- Owns no authoritative store; presence/cross-instance coordination uses Redis, commands use RocketMQ.

## Related

- [Service directory](../../README.en.md)
- [Engineering topology](../../../20-architecture/TOPOLOGY.en.md)
- [Architecture overview](../../../20-architecture/OVERVIEW.en.md)
