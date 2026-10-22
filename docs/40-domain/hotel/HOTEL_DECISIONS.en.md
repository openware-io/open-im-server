# Hotel Decisions and Open Questions

[中文](HOTEL_DECISIONS.md) | [English](HOTEL_DECISIONS.en.md)

> Status: `draft` (`develop/2.4.0-hotel`).  
> Purpose: separate recommended defaults from decisions that product/business must make.

## Confirmed (2026-10-22)

| ID | Decision | Conclusion |
| --- | --- | --- |
| H-C01 | Business shape | Support single-store, multi-store and group; build **group-internal first** with one domain and `HotelScope` / `SalesEntry` |
| H-C02 | Single-store entry | Enter from Leisure; show only that store |
| H-C03 | Group entry | Enter from OpenChat Service Center; show group hotels/stores |
| H-C04 | Payment modes | Prepay, pay-at-hotel and deposit; configured in **store management**, never hard-coded |
| H-C05 | OTA | **Contract only**; no live channel and no sandbox in this phase; implement the external contract and internal channel model |
| H-C06 | Prototype scope | C-end service-center flow exists; build the B-end prototype inside `open-saas-admin` on the 2.4.0 branch, not a separate prototype project |
| H-C07 | Invoice / guest registration | **Not in MVP**; fields and interfaces reserved for finance/tax and public-security integration |
| H-C08 | Front-desk client | **Admin Web + mobile**; mobile follows the KTV B-end workbench |
| H-C09 | Group mapping | `tenantId=group`, `storeId=hotel store`; no `groupId` in phase 1 |
| H-C10 | Currency | Default USD; support USD/CNY; configurable per store |
| H-C11 | User order list | Cross-group/store unified view, platform read model; see ADR-0009 |
| H-C12 | OTA sandbox | Not built; contract and internal channel model only |
| H-C13 | Marketing | Points earn + redemption; no membership levels or coupons |

## OTA and channel manager

- **OTA**: external sales channels such as Ctrip, Meituan, Fliggy or Booking.
- **Channel manager**: a hub that syncs availability, rates and inventory to multiple OTAs and pulls OTA bookings back into the PMS, e.g. SiteMinder or DerbySoft.
- OTA is required; a channel manager is an optional aggregation layer. MVP defines the contract and sandbox first; real channel scope is a business decision.

## Recommended defaults

| ID | Decision | Reason | Status |
| --- | --- | --- | --- |
| H-D01 | Sell inventory by room type; assign a physical room at check-in | Matches mainstream OTA/PMS behavior | proposed |
| H-D02 | Extend `platform-resource-service`; do not create a hotel service for MVP | Limits distributed complexity; keeps extraction triggers | proposed |
| H-D03 | Put hotel bookings in `platform-order-service`; do not reuse KTV `Reservation` | Hotel is date-range + room-type inventory | proposed |
| H-D04 | Store money in minor units plus currency | Follows the currency standard | proposed |
| H-D05 | Hold inventory on booking, consume on payment success | Prevents overselling and unpaid holds | proposed |
| H-D06 | Require `Idempotency-Key` on writes | Payment callbacks and retries must be idempotent | proposed |
| H-D07 | Configure cancellation policy per rate plan | Policies differ by rate | proposed |
| H-D08 | Encrypt guest ID data and redact logs | Security and compliance baseline | required |

## Decisions required

| ID | Question | Options | Impact |
| --- | --- | --- | --- |
| H-Q08 | Damage deposit / pre-authorization in MVP? | yes / no | separate from booking deposit; affects check-in and refunds |

## Marketing judgement

**Membership and coupons do not belong in the hotel MVP.** Reasons:

- member levels affect pricing, benefits, settlement and permissions;
- coupons require issuance, budget, redemption, refunds, reconciliation and anti-abuse;
- both are marketing-domain capabilities with no implementation today; bundling them turns the hotel MVP into a marketing-domain rewrite.

MVP points scope:

- points earn on paid/completed events, idempotent;
- points redemption through the `common-payment-service` POINT leg and customer redeem/release;
- a no-op `MarketingBenefitPort` for future coupon/member-price integration.

## Next

1. Answer H-Q08 (damage deposit/pre-authorization).
2. Confirm real OTA launch timing (contract only in this phase).
3. Freeze MVP scope and close ADR, OpenAPI, data model, security and test items.
4. Split S1–S5 tasks and migrations.
