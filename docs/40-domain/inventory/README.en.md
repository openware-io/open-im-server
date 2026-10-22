# Inventory Platform Domain (Stock)

[中文](README.md) | [English](README.en.md)

> Status: `draft` (`develop/2.4.0-hotel`).
> Position: **stock/procurement-sales inventory** as a supporting domain; not a capacity platform.
> Boundary: Stock is a platform capability; Capacity belongs to each business core domain and must not share tables, models or services.

## 1. Domain classification

| Type | Examples | Meaning |
| --- | --- | --- |
| Core | hotel booking/availability, KTV operations, restaurant turns, massage scheduling, gym classes | business-differentiating rules, owned per domain |
| Supporting | stock inventory, payment, customer, audit | shared capabilities |
| Generic | identity, messaging, files | reusable/purchasable |

This document covers **Stock** only. Capacity/availability belongs to the business core domains.

## 2. Why capacity is not shared stock

A hotel cannot "procure rooms" when rooms are sold out. The same applies to KTV rooms, restaurant tables and gym classes.

```text
hotel room inventory = hotel core (rates, availability, oversell, cancellation, check-in)
KTV room capacity    = KTV core (slots, open/close, service)
restaurant tables    = restaurant core (dining slots, turns, reservations)
massage/gym capacity = their own scheduling core
```

They share a **pattern** (availability), not a domain model.

Forbidden:

```text
shared inv_capacity_* tables
a cross-business capacity service
inventory platform depending on hotel/KTV
```

Allowed:

```text
shared technical libraries: calendar math, hold state machine, optimistic lock, idempotency, test fixtures
shared UI components: date pickers, capacity cells, status tags
the same design pattern, not the same model
```

## 3. Stock responsibilities

```text
SKU / item
warehouse / store location
stock balance
receipt / issue / count / transfer
reservation / release
cost
batch / expiry
safety stock
stock movement
```

Consumers: hotel minibar/consumables, KTV goods, restaurant ingredients, gym goods, retail.

## 4. Context map

```text
Procurement
    │ receipt
    ▼
Stock Platform
    ▲
    │ item stock query, reservation, receipt/issue
    │
Hotel / KTV / Restaurant / Gym / Retail
```

Stock is upstream; business domains are downstream. Stock does not know hotel/KTV concepts. Business domains never write `inv_stock_*` directly; they use a versioned contract and an ACL.

## 5. Capacity ownership

| Business | Core capacity model | Owner |
| --- | --- | --- |
| Hotel | room-type daily inventory, multi-night hold, room status, room assignment | hotel core module |
| KTV | room time-slot occupancy, session, open/close | KTV core module |
| Massage | therapist/room slots, service duration | massage core module |
| Restaurant | table/area slots, turns | restaurant core module |
| Gym | class/venue slots, capacity, waitlist | gym core module |

Business cores may reuse technical components, never capacity tables.

## 6. Dependency rules

Allowed: Hotel/KTV/Restaurant/Gym/Procurement -> Stock Platform; Stock Platform -> Audit/MQ.

Forbidden:

- Stock Platform -> Hotel/KTV.
- Business core -> another business core.
- Business domains writing `inv_stock_*` tables.
- `hotel_id` / `room_type_id` / `ktv_room_id` inside stock tables.

## 7. Isolation and permissions

- All stock rows carry `tenant_id + store_id`.
- A business only sees its own store inventory.
- Backend resolves tenant/store from the session; never trusts client IDs.
- Frontend route/menu/button hiding is UX, not security.
- Cross-store/cross-tenant access returns 403/404.
- Group read models use separate permissions.

## 8. Evolution

```text
phase 1: stock module (inside platform-resource-service or standalone module)
phase 2: extract platform-inventory-service
phase 3: each business capacity evolves in its own core; never moved into Stock
```

## 9. Related

- [ADR-0010: stock as a supporting domain](../../20-architecture/adr/0010-stock-supporting-domain.md)
- [ADR-0011: inventory context map](../../20-architecture/adr/0011-inventory-context-map.md)
- [ADR-0008: hotel domain boundary](../../20-architecture/adr/0008-hotel-domain-boundary.md)
- [Hotel implementation plan](../hotel/HOTEL_IMPLEMENTATION_PLAN.en.md)
- [DDD service conventions](../../80-development/standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.en.md)
