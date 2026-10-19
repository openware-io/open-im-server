# gateway

[中文](README.md) | [English](README.en.md)

> English summary; the Chinese document is normative.

## Responsibility

- Edge REST gateway: routing, auth pass-through, rate limiting and audit. It performs no business orchestration.

## Boundary

- Public REST entry at `/api/v1/**`; service-to-service HTTP uses Kubernetes DNS.

## Related

- [Service directory](../README.en.md)
- [Engineering topology](../../architecture/TOPOLOGY.en.md)
- [Architecture overview](../../ARCHITECTURE.en.md)
