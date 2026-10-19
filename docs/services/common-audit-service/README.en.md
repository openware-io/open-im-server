# common-audit-service

[中文](README.md) | [English](README.en.md)

> English summary; the Chinese document is normative.

## Responsibility

- Shared audit: audit event ingestion and query.

## Boundary

- MySQL `open_audit` is a separate domain; both IM and SaaS lines may call it.

## Related

- [Service directory](../README.en.md)
- [Engineering topology](../../architecture/TOPOLOGY.en.md)
- [Architecture overview](../../ARCHITECTURE.en.md)
