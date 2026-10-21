# Hotel Business

[中文](README.md) | [English](README.en.md)

> Status: `draft` (`develop/2.4.0-hotel`).  
> Scope: hotel booking, room types and rates, inventory calendar, payment, check-in/out, cancellation and settlement.  
> This is a design baseline, not an implemented contract; code, migrations and OpenAPI remain authoritative.

## Documents

| Document | Description |
| --- | --- |
| [Implementation plan](HOTEL_IMPLEMENTATION_PLAN.en.md) | Scope, domain boundary, model, flows, APIs, inventory concurrency and rollout |
| [Decisions and open questions](HOTEL_DECISIONS.en.md) | Recommended defaults and product decisions still required |

## Current state

- Prototype: hotel H5 demo under `open-chat-app/web/miniservice/a380`.
- Covered: hotel list/detail, room package, check-in date, arrival time, room count, guest contact, mock order.
- Missing: check-out date, guest count, live inventory, price breakdown, payment, cancellation policy, check-in/out, invoice and China guest-registration compliance.

## Related

- [Domain documentation](../README.en.md)
- [Order service](../../50-services/platform/platform-order-service/README.en.md)
- [Resource service](../../50-services/platform/platform-resource-service/README.en.md)
- [Payment service](../../50-services/common/common-payment-service/README.en.md)
- [Order/reservation state machines](../../20-architecture/diagrams/08-order-reservation-state.en.md)
