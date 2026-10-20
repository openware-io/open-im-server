# Reference

[中文](README.md) | [English](README.en.md)

This directory holds lookup-oriented interface and protocol references; it does not carry product narrative or domain rules.

| Document | Purpose |
| --- | --- |
| [API documentation](API_DOCUMENTATION.md) | Gateway Swagger/OpenAPI entry and groups |
| [Protocol](PROTOCOL.md) | WebSocket events, REST conventions, error codes |
| [OpenAPI contracts](contracts/README.md) | External and cross-service contracts |
| [MQ registry](mq/REGISTRY.md) | Topics, producer/consumer groups, ordering and idempotency keys |

## Boundaries

- Architecture concepts and decisions live in `architecture/`;
- Domain rules and state machines live in `domain/`;
- Service-specific implementation notes live in `services/`.
