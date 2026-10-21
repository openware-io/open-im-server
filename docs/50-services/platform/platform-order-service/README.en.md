# platform-order-service

[中文](README.md) | [English](README.en.md)

> Status: `current` (`develop/2.3.0`).  
> Runtime port: `4130`.  
> Data ownership: `ord_*` tables in MySQL `open_saas`.

## Responsibilities

Order and reservation write model and orchestration:

- orders: create, confirm, cancel, void, settle and payment-status handoff;
- reservations: confirm, arrive, no-show, cancel and convert to a KTV session;
- KTV sessions: open, pause/resume, close, timing and room fee;
- order items, bills, catalog and inventory occupation;
- publish order/settlement events through the Outbox.

## Boundaries

- Owns `ord_*` order/reservation/session/bill/catalog/inventory tables.
- Does not own customer, member, room resource or payment records; collaborates through those services.
- Never writes another service's tables; cross-context communication uses IDs or events.

## Entry points and dependencies

| Item | Notes |
| --- | --- |
| HTTP | Gateway routes to `platform-order-service:4130` |
| Database | MySQL `open_saas` |
| Messaging | RocketMQ; Outbox Relay publishes order events |
| Downstream | customer, resource, payment, audit |

## Key states

- [Order/reservation/KTV state machines](../../../20-architecture/diagrams/08-order-reservation-state.en.md)
- [Domain docs](../../../40-domain/README.en.md)

## Related

- [Service catalog](../README.en.md)
- [API contracts](../../../30-reference/contracts/README.md)
- [Database migration](../../../80-development/standards/23_DATABASE_MIGRATION_STANDARD.en.md)
