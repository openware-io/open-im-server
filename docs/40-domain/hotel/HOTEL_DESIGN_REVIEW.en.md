# Hotel Design Standards Review

[中文](HOTEL_DESIGN_REVIEW.md) | [English](HOTEL_DESIGN_REVIEW.en.md)

> Status: `draft` (`develop/2.4.0-hotel`).  
> Purpose: review the hotel design against existing engineering standards before coding.  
> Conclusion: the direction is viable, but five items must be closed before S1; otherwise do not start backend implementation.

## 1. Review scope

Documentation, architecture boundary/ADR, API contract/versioning/idempotency, data naming/migrations/money/tenant isolation, security/privacy/audit, observability, testing, release, gates.

## 2. Findings

| Area | Result | Note |
| --- | --- | --- |
| Documentation | conditional pass | bilingual, statused and reachable; OTA contract still draft |
| Architecture | ADR-0008 proposed | resource + order modular boundary recorded; awaiting acceptance |
| API contract | partial | `hotel-channel-v1.yaml` added; `hotel-booking-v1.yaml` pending |
| Data model | review required | table names, unique keys and migrations not frozen |
| Security | review required | PII, tenant isolation and channel credentials specified, not checked |
| Observability | review required | metric names proposed, not wired to the standard |
| Testing | incomplete | no concurrency/payment/OTA contract cases |
| Gates | pending | map G18/G24/G25/G27/G32/G43/G45/G52 |
| Prototype | partial | C/B web done; mobile front desk not built |

## 3. Five required items

1. **ADR-0008** is submitted as proposed; review it to accepted (resource inventory, order booking, extraction triggers).
2. **OpenAPI contracts**: `hotel-booking-v1.yaml` and `hotel-channel-v1.yaml`.
3. **Data model review**: `res_*`/`ord_*` prefixes, inventory and external-booking unique keys, money in minor units, Flyway rules.
4. **Security checklist**: tenant/store isolation, PII encryption/redaction, channel secrets, no card data, audit coverage, cross-tenant tests.
5. **Test and observability plan**: last-room concurrency, duplicate/out-of-order payment callbacks, cancellation/refund, stay-deposit collect/deduct/refund, OTA contract cases, overbooking metric equals zero.

## 4. Already compliant

Bilingual/statused/reachable docs; money and currency direction; service boundaries; MVP scope; OTA contract intent; invoice/guest-registration exclusion; C/B web prototypes inside code.

## 5. Gate mapping

G18 API boundaries, G24 port contract, G25 PO containment, G27/G28 Flyway, G32 tenant SQL, G43 service layout, G45 controller mapping, G52 docs impact.

## 6. S1 checklist

```text
[x] ADR-0008 submitted (proposed; awaiting accepted)
[ ] hotel-booking-v1.yaml reviewed
[x] hotel-channel-v1.yaml created (review pending)
[ ] table list + ER diagram reviewed
[ ] security/tenant-isolation checklist confirmed
[ ] concurrency/payment/OTA test cases listed
[ ] mobile front-desk prototype built (KTV B-end reference)
[ ] invoice/guest registration explicitly out of scope, fields reserved
```

## 7. Related

- [Implementation plan](HOTEL_IMPLEMENTATION_PLAN.en.md)
- [OTA contract](HOTEL_OTA_INTEGRATION.en.md)
- [Decisions](HOTEL_DECISIONS.en.md)
- [Standards map](../../80-development/standards/STANDARDS_MAP.en.md)
- [Gate catalog](../../80-development/governance/GATE_CATALOG.md)

## 8. Incremental review (2026-10-22)

| Change | Conclusion | Action |
| --- | --- | --- |
| C-end orders across group/store | platform capability, not a hotel endpoint | ADR-0009 `proposed`; add `ord_user_order_index`, backfill and reconciliation |
| OTA not live, no sandbox | contract and internal channel model only | contract first; real adapters later |
| Membership/coupons | not in hotel MVP | points earn + redemption; membership/coupons are a separate initiative |
| Currency | default USD, support USD/CNY | `HotelPolicy.currency`, store-configurable |
| Mobile front desk | in MVP | KTV B-end reference; prototype pending |
| Invoice/guest registration | not connected | fields reserved; outside MVP acceptance |
| Inventory platform | shared stock + capacity kernels; per-business policies | ADR-0010 proposed; KTV adapter and cross-store tests pending |
