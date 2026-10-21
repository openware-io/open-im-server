# Hotel Decisions and Open Questions

[中文](HOTEL_DECISIONS.md) | [English](HOTEL_DECISIONS.en.md)

> Status: `draft` (`develop/2.4.0-hotel`).  
> Purpose: separate recommended defaults from decisions that product/business must make.

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
| H-Q01 | Single property, multi-property or chain? | single / multi-property / chain | central reservation and inventory scope |
| H-Q02 | Payment model? | prepay / deposit / pay-at-hotel / guarantee | order state machine, refunds, no-show |
| H-Q03 | OTA/channel manager integration? | none / Ctrip-Meituan / own channels | inventory sync, mapping, oversell policy |
| H-Q04 | Front-desk client? | admin web / mobile / both | BFF and UI scope |
| H-Q05 | Invoice, deposit and China guest registration? | yes / no / phase 2 | fields, compliance, finance |
| H-Q06 | Membership, points and coupons? | none / coupon / full | integration with customer and marketing |
| H-Q07 | Multi-currency in MVP? | CNY only / multi-currency | pricing and settlement |
| H-Q08 | Are there prototype screens not shown? | yes / no | avoid modeling from an incomplete prototype |

## Next

1. Answer H-Q01–H-Q08.
2. Confirm prototype link, recording or hidden screens.
3. Freeze MVP scope.
4. Split S1–S5 tasks and migrations.
