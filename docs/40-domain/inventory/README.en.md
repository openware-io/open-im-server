# Inventory Platform Domain

[中文](README.md) | [English](README.en.md)

> Status: `draft` (`develop/2.4.0-hotel`).  
> Position: a supporting/platform domain, not a hotel-private domain and not a shared kernel between business contexts.  
> Direction: inventory is upstream; hotel, KTV, massage, restaurant and gym are downstream consumers.

## 1. Domain classification

| Type | Examples | Meaning |
| --- | --- | --- |
| Core | hotel booking, KTV operations, restaurant table turns | business-differentiating rules |
| Supporting | inventory, payment, customer, audit | required but not differentiating |
| Generic | identity, messaging, files | reusable/purchasable |

Inventory is a supporting domain with two subdomains:

```text
Stock       SKU, warehouse, balance, receipts/issues, count, cost, batch
Capacity    resource/time/date capacity, hold, occupancy, release
```

"Middle platform" may be an organizational label; architecturally this is a shared **supporting platform**, not a place for business rules.

## 2. Context map

```text
Procurement
    │ receipt
    ▼
Inventory Platform
  ├─ Stock
  └─ Capacity
    ▲
    │ reserve / hold / release / availability
    │
 Hotel / KTV / Massage / Restaurant / Gym
```

- Inventory is upstream/supplier.
- Business contexts are downstream/customers.
- Integration uses a published language plus an anti-corruption layer.
- Inventory does not import hotel/KTV classes and does not depend on them.
- Business contexts never read or write `inv_*` tables directly.
- This is not a DDD Shared Kernel; the shared thing is the contract and platform capability, not the business model.

## 3. Ownership

Inventory owns:

```text
inv_stock_item / warehouse / balance / movement / reservation / batch
inv_capacity_unit / slot / hold
```

Business domains own:

| Domain | Own model | Inventory usage |
| --- | --- | --- |
| Hotel | Hotel, RoomType, RatePlan, HotelBooking, StayDeposit | daily room-type capacity, hold/release; minibar/consumables via Stock |
| KTV | room, session, pricing, service staff | room time-slot capacity; goods via Stock |
| Massage | therapist/room, service, appointment | therapist/room slots |
| Restaurant | table/area, menu, turn | table slots; ingredients via Stock |
| Gym | class/equipment/venue, member | class/venue capacity; goods via Stock |
| Procurement | supplier, purchase order, receipt | receipt into Stock |

Inventory does not know hotel/KTV concepts. It knows only tenant/store, inventory unit, business type, granularity, slot and quantity.

## 4. Policy vs execution

The platform executes invariants; business domains supply policy parameters:

```text
CapacityPolicy
  granularity        DAY / SLOT
  slotDuration       30m / 60m / 1d
  rangeMode          SINGLE_SLOT / DATE_RANGE
  capacitySource     FIXED / CALENDAR
  allowOverbook      false
  releaseRule        ON_EXPIRE / ON_CANCEL / MANUAL
  allocationRule     ROOM_TYPE_THEN_ROOM / RESOURCE_ONLY
```

The platform guarantees no oversell, idempotent holds, expiry release, version conflict detection, audit and tenant/store isolation. Business domains interpret the data; they do not reimplement these invariants.

## 5. Dependency rules

Allowed: Hotel/KTV/Procurement -> Inventory; Inventory -> Audit/MQ.

Forbidden:

- Inventory -> Hotel/KTV.
- Business domains reading/writing `inv_*` tables.
- `hotel_id` / `room_type_id` / `ktv_room_id` columns inside inventory tables.
- Copying hold/oversell logic into each business.

Writes require tenant/store, idempotency key, source document and expected version.

## 6. Isolation and permissions

- All inventory rows carry `tenant_id + store_id`.
- No cross-store query for business pages.
- Group/tenant read models have separate permissions.
- Backend resolves tenant/store from the session; never trusts client IDs.
- Frontend hides routes/menus/buttons but is not the security boundary.

## 7. Evolution

```text
phase 1: inventory modules inside platform-resource-service
phase 2: extract platform-inventory-service when multiple businesses depend on it
phase 3: adapt KTV res_occupation to capacity without a forced migration
```

Phase 1 avoids distributed transactions; the domain boundary is still a platform boundary, not a hotel-private module.

## 8. Anti-patterns

- One universal inventory table.
- Inventory importing hotel/KTV classes.
- Business domains writing `inv_*` tables.
- Per-business copies of oversell/hold logic.
- Hiding business rules inside a "middle platform".
- Frontend-only permission checks.

## 9. Related

- [ADR-0010: inventory as a supporting platform domain](../../20-architecture/adr/0010-shared-inventory-kernel.md)
- [ADR-0011: inventory context map and dependency rules](../../20-architecture/adr/0011-inventory-context-map.md)
- [Hotel implementation plan](../hotel/HOTEL_IMPLEMENTATION_PLAN.en.md)
- [Resource service](../../50-services/platform/platform-resource-service/README.en.md)
- [DDD service conventions](../../80-development/standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.en.md)
