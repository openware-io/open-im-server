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
check-in -> check-out
admin rate and inventory management
order, payment, audit and permissions
```

Out of MVP: OTA/channel manager, dynamic yield, chain CRS, complex packages, multi-currency settlement, corporate rates.

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
6. Check in: assign physical room; check out: release room to cleaning.

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

Store management configures payment mode, deposit ratio (`depositRatioBps`), cancellation/no-show policy and invoice support. Group defaults may be overridden per store.

## 8. API draft

```text
GET  /api/v1/hotels?city=&checkIn=&checkOut=&guests=
GET  /api/v1/hotels/{hotelId}/room-types?checkIn=&checkOut=
POST /api/v1/hotel-bookings
GET  /api/v1/hotel-bookings/{bookingId}
POST /api/v1/hotel-bookings/{bookingId}/cancel

POST /api/v1/admin/hotels
PUT  /api/v1/admin/hotels/{hotelId}/inventory
POST /api/v1/admin/hotel-bookings/{bookingId}/check-in
POST /api/v1/admin/hotel-bookings/{bookingId}/check-out

POST /internal/hotels/holds
POST /internal/hotels/holds/{holdId}/consume
POST /internal/hotels/holds/{holdId}/release
```

## 9. Security and observability

- tenant/store isolation on every query;
- permissions: `hotel.view`, `hotel.manage`, `hotel.inventory.manage`, `hotel.booking.*`;
- guest PII encrypted/masked; no card data stored;
- audit hotel, rate, inventory, booking, check-in/out and refund;
- China guest registration interface reserved pending decision;
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

Confirmed: single-store, multi-store and group are all required; build group-internal first with the same domain through `HotelScope` / `SalesEntry`. Payment mode is store-configured: prepay, pay-at-hotel and deposit. OTA is deferred, with channel fields reserved. C-end service-center flow exists; the B-end prototype is built inside `open-saas-admin` on the 2.4.0 branch.

Remaining: front-desk client, invoice/deposit/China guest registration, loyalty, currency.

See [Hotel decisions](HOTEL_DECISIONS.en.md).

## 12. Acceptance

- 100 concurrent attempts for the last room never exceed inventory;
- duplicate payment callbacks do not double-confirm or double-consume;
- cancellation refunds and releases inventory correctly;
- cross-tenant access is denied without data leakage;
- every write is audited;
- `hotel.overbooking_count = 0`;
- prototype flow works end to end.
