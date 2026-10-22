# Order Kernel Domain

[中文](README.md) | [English](README.en.md)

> Status: `draft` (`develop/2.4.0-hotel`).  
> Position: the order transaction kernel is a supporting domain; reservation/check-in/session fulfillment belongs to each business core.  
> Principle: share order header/line/amount/payment/base lifecycle, not one universal order table.

## 1. Classification

| Type | Examples | Owner |
| --- | --- | --- |
| Core | hotel booking/availability/rates, KTV operations, restaurant turns | each business core |
| Supporting | order kernel, stock, payment, customer, audit | shared platform |
| Generic | identity, messaging, files | generic capability |

An order is a transaction document; its fulfillment semantics differ by business.

## 2. Shared model

```text
ord_order
  orderNo / tenantId / storeId / customerId / platformUserId
  orderType / businessType
  status / paymentStatus / fulfillmentStatus
  amountMinor / currency
  source / idempotencyKey / createdAt / updatedAt

ord_order_line
  orderId / lineNo / itemType / itemRef
  title / quantity / unitPriceMinor / amountMinor
```

Shared capabilities: order number, idempotency, amount/currency/lines, payment/refund state, base lifecycle, tenant/store isolation, audit/outbox, C-end order index, query permissions.

## 3. Business extensions

```text
HotelBookingDetail
  hotelId / roomTypeId / ratePlanId / checkIn / checkOut
  roomCount / guestCount / stayDeposit...

KtvOrderDetail
  resourceId / sessionId / packageId / serviceStaff...

RestaurantOrderDetail
  tableId / diningSlot / turn...

GymOrderDetail
  classId / venueId / memberId...
```

Rules:

- the shared order header contains no `roomTypeId`, `resourceId` or `tableId`;
- business fields live in a 1:1 extension table;
- business states live in `fulfillmentStatus` or the extension, not one shared enum;
- a reservation is an order type/fulfillment type, not a separate order system.

## 4. Order type vs business type

```text
orderType     transaction shape: SALE / RESERVATION / BOOKING / SUBSCRIPTION
businessType  domain: HOTEL / KTV / RESTAURANT / GYM / RETAIL
```

| Order | orderType | businessType |
| --- | --- | --- |
| Hotel booking | BOOKING | HOTEL |
| KTV reservation | RESERVATION | KTV |
| KTV open session | SALE / SESSION | KTV |
| Restaurant table reservation | RESERVATION | RESTAURANT |
| Gym class purchase | SALE | GYM |

## 5. State model

Shared transaction states:

```text
DRAFT / PENDING_PAYMENT / PAID / CONFIRMED / COMPLETED / CANCELLED / REFUNDING / REFUNDED
```

Fulfillment states are business-owned:

```text
Hotel: PENDING_ARRIVAL / CHECKED_IN / CHECKED_OUT / NO_SHOW
KTV: RESERVED / OPEN / CLOSED / CANCELLED
Restaurant: RESERVED / SEATED / FINISHED / NO_SHOW
Gym: BOOKED / ATTENDED / NO_SHOW
```

Do not put every business state into one `status` enum.

## 6. Relationship to KTV orders/reservations

Existing KTV has `Reservation`, `Order` and `KtvSession`. Do not rewrite in one step:

- the common kernel serves the hotel first;
- KTV maps to the common header through an adapter;
- `Reservation` remains a KTV fulfillment extension;
- no big-bang migration.

## 7. Dependencies

```text
Hotel / KTV / Restaurant / Gym
        │ create/pay/query order
        ▼
Order Kernel
        │ payment/refund
        ▼
Payment Service
```

Order Kernel does not depend on a business domain. Business domains use an ACL to create orders and extensions. Payment does not know hotel/KTV fields.

## 8. Isolation and permissions

- every order carries `tenant_id + store_id`;
- a business only sees its own store orders;
- backend resolves context from the session;
- C-end "My orders" uses the unified index by `platformUserId`;
- admin queries filter by store/permission;
- cross-tenant/store access returns 403/404.

## 9. Anti-patterns

- one order system per business;
- one order table with every business field;
- every business state in one enum;
- separate systems for reservations and orders;
- Order Kernel depending on hotel/KTV;
- business domains writing shared order tables directly.

## 10. Related

- [ADR-0012: order as a shared transaction kernel](../../20-architecture/adr/0012-order-kernel.md)
- [Hotel implementation plan](../hotel/HOTEL_IMPLEMENTATION_PLAN.en.md)
- [Order service](../../50-services/platform/platform-order-service/README.en.md)
- [DDD service conventions](../../80-development/standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.en.md)
