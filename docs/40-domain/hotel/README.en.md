# Hotel Business

[中文](README.md) | [English](README.en.md)

> Status: `draft` (`develop/2.4.0-hotel`).  
> Scope: hotel booking, room types and rates, inventory calendar, payment, check-in/out, cancellation and settlement.  
> This is a design baseline, not an implemented contract; code, migrations and OpenAPI remain authoritative.

## Documents

| Document | Description |
| --- | --- |
| [Implementation plan](HOTEL_IMPLEMENTATION_PLAN.en.md) | Scope, domain boundary, model, flows, APIs, inventory concurrency and rollout |
| [OTA / channel contract](HOTEL_OTA_INTEGRATION.en.md) | External ARI, booking, cancellation, auth, idempotency and sandbox |
| [Standards review](HOTEL_DESIGN_REVIEW.en.md) | Pre-coding compliance findings and required actions |
| [Decisions and open questions](HOTEL_DECISIONS.en.md) | Confirmed decisions, terminology and remaining questions |
| [Prototype review guide](HOTEL_PROTOTYPE_GUIDE.en.md) | C-end, B-end web and mobile entries and review checklist |

## Current state

- C-end prototype: `open-chat-app/lib/screens/mock_venue_flow_screens.dart` (Service Center → Hotel → list/detail/room/booking; now includes check-out date, room count and guest count).
- B-end prototype: `open-saas-admin/src/views/tenant/hotel.vue`, route `/admin/tenant/hotel` (visible in the dev sidebar).
- B-end coverage: overview, hotel profile, access mode/sales entries, payment policy, room types/rates, inventory calendar, bookings, check-in/out.
- Data: local mock only; no backend calls. Replace the data source later without rebuilding the pages.
- Still missing: live inventory, payment, cancellation/refund, invoice and guest registration, backend BFF/API.

## Related

- [Domain documentation](../README.en.md)
- [Inventory platform](../inventory/README.en.md)
- [Order platform](../order/README.en.md)
- [Order service](../../50-services/platform/platform-order-service/README.en.md)
- [Resource service](../../50-services/platform/platform-resource-service/README.en.md)
- [Payment service](../../50-services/common/common-payment-service/README.en.md)
- [Order/reservation state machines](../../20-architecture/diagrams/08-order-reservation-state.en.md)
