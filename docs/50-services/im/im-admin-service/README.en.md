# im-admin-service

[中文](README.md) | [English](README.en.md)

> English summary; the Chinese document is normative.

## Responsibility

- IM admin APIs: client configuration, release checks, reports and the mini-program service board.

## Boundary

- The service board (`consumer`/`operator` items) is configured here and consumed by the App.
- Service item links target the current C-end routes `/c/` and `/c/#/service/...`. The legacy
  `/a380/` route is no longer served; migration V7 rewrites historical links to current routes.

## Related

- [Service directory](../../README.en.md)
- [Engineering topology](../../../20-architecture/TOPOLOGY.en.md)
- [Architecture overview](../../../20-architecture/OVERVIEW.en.md)
