# Hotel Implementation Plan

[中文](HOTEL_IMPLEMENTATION_PLAN.md) | [English](HOTEL_IMPLEMENTATION_PLAN.en.md)

> Status: `draft` (`develop/2.4.0-hotel`).  
> Technical baseline for hotel booking; align boundaries and model before task breakdown.

## 1. MVP scope

```text
hotel / property profile
room type + physical room
rate plan + daily rate
date-range inventory calendar
search -> quote -> booking -> payment -> confirmation
cancel / payment timeout / no-show
check-in -> check-out (admin web + mobile front desk)
stay deposit collect / settle / refund
admin rate and inventory management
OTA external contract (no live channel, no sandbox)
order, payment, audit and permissions
C-end unified order center (cross-group/store)
```

The mobile front desk follows the KTV B-end workbench:

```text
today arrivals / in-house / departures
check-in / assign room
check-out / move room to cleaning
booking lookup / room status
```

### 1.1 User order center (platform capability)

Users do not care which group/store an order belongs to. C-end "My orders" must be unified:

```text
GET /api/v1/my/orders
GET /api/v1/my/orders/{orderType}/{orderId}
```

- The list does not require `X-Tenant-Context`; it queries by the authenticated global `platformUserId`.
- Order tables remain the write model; add `ord_user_order_index` as the unified read model.
- Detail first checks index ownership, then restores the order's tenant context for reads.
- Hotel orders, KTV orders and future order types write to the same index.
- This is a platform capability, not a hotel-private page; see ADR-0009.

### 1.2 Marketing scope

Only points exist today; membership and coupons are not implemented. MVP does not refactor the marketing domain, but **points redemption is in scope**:

| Capability | MVP |
| --- | --- |
| Points earn | yes: on paid/completed event, idempotent |
| Points redemption | yes: reuse the `POINT` leg in `common-payment-service` |
| Membership levels/benefits | no |
| Coupon issue/redeem | no |
| Member/negotiated rates | no |

No new points service:

```text
quote: customer amount-to-points provides the redeemable cap
pay: common-payment-service collects with method=POINT
deduct: customer /internal/customer/points/redeem with idempotency key
failure: common-payment-service calls /release in reverse order
success: payment.collect.confirmed triggers points earn
```

Key semantics:

- the `POINT` leg amount is the **bill amount offset**, not the points count; the count lives in the customer ledger;
- points redemption does not convert currency; it follows the order currency snapshot;
- store policy controls `pointsRedeemEnabled` and the maximum redemption ratio;
- membership and coupons remain out; `MarketingBenefitPort` stays a no-op for later marketing work.

Out of MVP: dynamic yield, chain CRS, complex packages, multi-currency settlement beyond USD/CNY, corporate rates. OTA contract is in MVP; real channel adapters and sandbox are not.

## 1.1 Access modes: one hotel domain, two entries

```text
single store: Leisure -> Hotel -> local room types/inventory
group:        OpenChat Service Center -> Hotel -> group hotels/stores
```

Do not fork the business by entry point. Use:

```text
HotelScope:   SINGLE_STORE | GROUP
SalesEntry:   SERVICE_CENTER | LEISURE | GROUP_INTERNAL
Visibility:   STORE | GROUP
```

- Build **group-internal hotel first**: `HotelScope=GROUP`, entered from OpenChat Service Center, group stores share hotel profiles, room types and inventory views.
- Add single-store later with the same domain: `HotelScope=SINGLE_STORE`, entered from Leisure, inventory visible only in that store.
- Entry point is sales-channel configuration, not a domain boundary; orders, inventory and payment stay in the same services.
- Every query carries `tenantId` + `storeId`/`groupId`; `Visibility` decides the scope.

The current SaaS model already carries the group: `tenantId` is the group/tenant, `storeId` is the hotel store. For phase 1:

```text
GROUP         = tenant-level hotel catalog + store inventory
SINGLE_STORE  = store-level hotel catalog + local inventory
```

No new `groupId`; only add it if a group needs sub-groups.

OTA later becomes another `SalesEntry=OTA` plus channel mapping, not a new domain.

## 2. Prototype gap

Prototype: `open-chat-app/web/miniservice/a380`.

| Screen | Covered | Missing |
| --- | --- | --- |
| Hotel list | search, sort, distance, rating, from-price | city/date filters, live inventory and rates |
| Hotel detail | images, tags, address, packages | policies, facilities, room amenities, cancellation rules |
| Room package | room name, bed, area, breakfast, price, stock | rate plan, nightly price, tax, inventory calendar |
| Booking form | check-in date, arrival time, check-out date, rooms, guests, name/phone | live inventory, quote breakdown, payment, cancellation policy |
| Order | local mock | payment, confirmation, cancellation, check-in/out, invoice |
| B-end admin | `open-saas-admin` prototype: profile, payment policy, room types/rates, calendar, bookings, check-in/out | real BFF/API; invoice, guest registration, channel sync |

## 3. Domain boundary

Options:

| Option | Approach | Trade-off |
| --- | --- | --- |
| A | Put hotel inventory, rates and booking into order service | quick but mixes order and inventory |
| B | Extend resource service for hotel inventory/rates; order service for booking | reuses order/payment/customer, clear boundary |
| C | New hotel service | strongest isolation, highest MVP cost |

**Recommendation: B**, with hotel packages inside resource and order, and documented extraction triggers.

```text
platform-resource-service
  domain/hotel: Hotel, RoomType, Room, RatePlan, InventoryCalendar, Hold
platform-order-service
  domain/hotelbooking: HotelBooking, BookingNight, GuestSnapshot
platform-admin-service: hotel, rate, inventory, booking, check-in/out BFF
common-payment-service: payment, refund, reconciliation
platform-customer-service: member and guest profile
common-audit-service: hotel/rate/inventory/booking audit
```

Extraction triggers: independent hotel team, inventory QPS/data volume, independent DB/SLA, or OTA sync as a subsystem.

### 3.1 Reuse boundary with KTV inventory

**Decision: reuse the inventory kernel, not the inventory tables.**

| Layer | Reuse | Separate |
| --- | --- | --- |
| Infrastructure | tenant/store scope, versioning, transactions, audit, outbox, expiry scan | — |
| Resource | `res_resource` catalog, room type/status | — |
| Inventory semantics | availability, hold, release, conflict control | KTV time-slot occupation vs hotel daily quota |
| Tables | — | `res_occupation` (KTV) vs `res_inventory_calendar` / `res_inventory_hold` (hotel) |
| Application | technical components | `KtvInventoryApplicationService` / `HotelInventoryApplicationService` |
| API / UI | shared components | `/business/ktv/**` and `/business/hotel/**`; separate pages |

Reason: KTV sells a resource for a time interval; a hotel sells a room-type quota per night. A shared table would require many nullable fields, wrong unique keys and two state machines. A shared kernel keeps concurrency, audit and isolation without sacrificing semantics.

See [ADR-0010](../../20-architecture/adr/0010-shared-inventory-kernel.md).

## 4. Core model

Resource (`gv_saas`, `res_*`):

| Model | Key fields |
| --- | --- |
| `Hotel` | tenant, store, name, city, address, star, check-in/out time, status |
| `HotelRoomType` | hotel, code, name, bed type, area, max occupancy, amenities, images |
| `HotelRoom` | room type, room number, floor, status |
| `RatePlan` | room type, code, name, meal plan, cancel policy, guarantee |
| `DailyRate` | rate plan, stay date, price minor, currency, min stay, closed |
| `InventoryCalendar` | room type, stay date, total, sold, held, stop-sell, version |
| `InventoryHold` | booking, room type, check-in/out, quantity, expiry, status |

Order (`open_saas`, `ord_*`):

| Model | Key fields |
| --- | --- |
| `HotelBooking` | booking no, tenant/store, hotel, room type, rate plan, check-in/out, rooms, guests, status, amount/currency |
| `HotelBookingNight` | booking, stay date, price, tax, currency |
| `HotelGuest` | booking, name, phone, ID type, masked ID |
| `StayDeposit` | booking, amount/currency, mode, status, collected/refunded timestamps |
| `StayDepositLedger` | deposit, entry type (COLLECT/DEDUCT/REFUND), amount, reason, operator, idempotency key |
| `UserOrderIndex` | platform user, tenant, store, order type/id/no, status, amount/currency |

The stay deposit is a liability, not revenue: collect/refund never enter `HotelBooking.amountMinor` or revenue reports. Only actual deductions become revenue/receivable.

Do not reuse KTV `Reservation`; its slot semantics conflict with date-range room inventory.

## 5. State machine

```text
PENDING_PAYMENT -> CONFIRMED -> CHECKED_IN -> CHECKED_OUT -> COMPLETED
        |               |
     CANCELLED       CANCELLED / NO_SHOW

HELD -> CONSUMED -> RELEASED
  |
RELEASED (expired)
```

## 6. Booking flow

1. Search by city/hotel, dates, rooms and guests.
2. Check `total - sold - held` in `InventoryCalendar`.
3. Quote nightly rates, tax and cancellation policy; no inventory change.
4. Create booking + inventory hold in one transaction.
5. Pay; callback is idempotent; confirm booking and consume hold.
6. Check in: collect the stay deposit, assign a physical room and mark `OCCUPIED`.
7. Check out: post deductions, refund the deposit balance, move the room to `CLEANING` and then `AVAILABLE`.
8. Mark `COMPLETED` after settlement.

## 7. Inventory concurrency

Unique key `(tenant_id, room_type_id, stay_date)`; atomic update:

```sql
UPDATE res_inventory_calendar
SET held = held + :qty, version = version + 1
WHERE tenant_id = :tenantId
  AND room_type_id = :roomTypeId
  AND stay_date BETWEEN :checkIn AND :checkOut - 1
  AND total - sold - held >= :qty
  AND stop_sell = 0;
```

Any failed night rolls back the whole range. Holds expire; payment callback uses `bookingId + paymentId` idempotency. `hotel.overbooking_count` must remain zero.

### Payment policy (store configuration)

`HotelPolicy.paymentMode`:

| Mode | Behavior | Initial booking state |
| --- | --- | --- |
| `PREPAY` | full payment before confirmation | `PENDING_PAYMENT` |
| `PAY_AT_HOTEL` | confirm and hold inventory; pay at desk | `CONFIRMED` (unpaid) |
| `DEPOSIT` | pay deposit now, balance later | `PENDING_DEPOSIT` → `CONFIRMED` |

Store management configures payment mode, deposit ratio (`depositRatioBps`), cancellation/no-show policy and invoice support. Group defaults may be overridden per store. The scope follows the KTV config model: tenant default → business default → store override.

### Stay deposit policy (store configuration)

The stay deposit is not the booking deposit and not revenue. It is collected at check-in and settled/refunded at check-out; only actual deductions become revenue.

`StayDeposit.mode`:

| Mode | Description |
| --- | --- |
| `COLLECT` | MVP default: cash/WeChat/Alipay/card collection; refund to original channel or cash |
| `PRE_AUTH` | reserved: card pre-authorization only, enabled per channel capability |

Store config: `stayDepositRequired`, `stayDepositMode`, `stayDepositMinor`, `stayDepositRatioBps`, `stayDepositCapMinor`, `depositRefundMethod`.

States: `COLLECTED → PARTIALLY_DEDUCTED → REFUNDED`; `COLLECTED → REFUNDED`; `COLLECTED → VOID`.

Deductions (minibar, damage) go through `StayDepositLedger(DEDUCT)` with reason, operator and evidence. Refunds prefer the original channel; cash refunds require front-desk authorization.

## 8. API draft

```text
GET  /api/v1/hotels?city=&checkIn=&checkOut=&guests=
GET  /api/v1/hotels/{hotelId}/room-types?checkIn=&checkOut=
POST /api/v1/hotel-bookings
GET  /api/v1/hotel-bookings/{bookingId}
POST /api/v1/hotel-bookings/{bookingId}/cancel

GET  /api/v1/my/orders?type=&status=&page=
GET  /api/v1/my/orders/{orderType}/{orderId}

POST /api/v1/admin/hotels
PUT  /api/v1/admin/hotels/{hotelId}/inventory
POST /api/v1/admin/hotel-bookings/{bookingId}/check-in
POST /api/v1/admin/hotel-bookings/{bookingId}/check-out

POST /internal/hotels/holds
POST /internal/hotels/holds/{holdId}/consume
POST /internal/hotels/holds/{holdId}/release
```

## 9. Security and observability

- tenant/store isolation on every query; no cross-tenant or cross-store access;
- backend authority: resolve tenant/store from the server session; never trust client-supplied IDs; verify store membership; deny with 403/404;
- frontend: route/menu/button hiding is UX only, not a security boundary;
- API client: inject store context from `contextStore`; never hard-code `storeId` in pages;
- permissions: `hotel.view`, `hotel.manage`, `hotel.inventory.manage`, `hotel.booking.view`, `hotel.booking.checkin`, `hotel.booking.cancel`, `hotel.deposit.manage`;
- guest PII encrypted/masked; no card data stored;
- stay deposit has a separate ledger; deductions require reason and operator;
- audit hotel, rate, inventory, booking, check-in/out, deposit and refund;
- invoice and China guest-registration interfaces are reserved, not in MVP;
- metrics: search latency, bookings, inventory conflicts, expired holds, payment lag, overbooking (must be 0).

## 10. Delivery

| Stage | Output |
| --- | --- |
| S0 | decisions, prototype confirmation, scope freeze |
| S1 | hotel/room-type/rate/inventory/booking model and migrations |
| S2 | availability, quote, hold, concurrency and expiry |
| S3 | booking, payment callback, cancel/refund |
| S4 | admin check-in/out and room status |
| S5 | gray release, monitoring, reconciliation, SOP |

## 11. Confirmed and remaining decisions

Confirmed: single-store, multi-store and group are all required; build group-internal first with the same domain through `HotelScope` / `SalesEntry`. Payment mode is store-configured: prepay, pay-at-hotel and booking deposit. The stay deposit is also in MVP, tracked separately from order revenue. OTA contract only — no live channel and no sandbox. Invoice and guest registration are not connected; fields are reserved. Front desk is Admin Web + mobile (KTV B-end reference). Default currency is USD; USD/CNY supported. C-end orders are unified across group/store via ADR-0009. Marketing: points earn and redemption via payment POINT leg; no membership/coupons.

Remaining: marketing-domain expansion (membership/coupons) and real OTA launch timing.

See [Hotel decisions](HOTEL_DECISIONS.en.md).

## 12. Acceptance

- 100 concurrent attempts for the last room never exceed inventory;
- duplicate payment callbacks do not double-confirm or double-consume;
- cancellation refunds and releases inventory correctly;
- cross-tenant access is denied without data leakage;
- every write is audited;
- `hotel.overbooking_count = 0`;
- prototype flow works end to end.
