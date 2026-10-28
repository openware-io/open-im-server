# 08 Order / Reservation / KTV State Machines

[中文](08-order-reservation-state.md) | [English](08-order-reservation-state.en.md)

> Diagram revision: `2026-10-21` · C4 semantics / grouped layout

> View: State Machine  
> Answers: which transitions are allowed for orders, reservations and KTV sessions, and which states are terminal.  
> State strings follow code and Flyway; this diagram does not replace business rule docs.

## 1. Order `ord_order`

```mermaid
%%{init: {"theme":"base","themeVariables":{"fontSize":"14px","primaryColor":"#E8F0FE","primaryTextColor":"#172B4D","primaryBorderColor":"#2563EB","lineColor":"#5E6C84","clusterBkg":"#FAFBFC","clusterBorder":"#C1C7D0","edgeLabelBackground":"#FFFFFF"}}}%%
stateDiagram-v2
  [*] --> DRAFT

  DRAFT --> WAITING_SETTLEMENT: confirm order
  DRAFT --> SERVING: open table
  WAITING_ARRIVAL --> SERVING: arrive and open
  SERVING --> WAITING_SETTLEMENT: close table / items complete
  WAITING_SETTLEMENT --> WAITING_PAYMENT: settle
  WAITING_PAYMENT --> COMPLETED: fully paid

  DRAFT --> CANCELLED: cancel
  WAITING_ARRIVAL --> CANCELLED: cancel
  WAITING_SETTLEMENT --> CANCELLED: cancel
  SERVING --> CANCELLED: cancel
  WAITING_PAYMENT --> CANCELLED: cancel

  DRAFT --> VOIDED: void approved
  SERVING --> VOIDED: void approved
  WAITING_SETTLEMENT --> VOIDED: void approved

  COMPLETED --> PARTIAL_REFUNDED: partial refund
  COMPLETED --> REFUNDED: full refund

  COMPLETED --> [*]
  CANCELLED --> [*]
  VOIDED --> [*]
  REFUNDED --> [*]
  PARTIAL_REFUNDED --> [*]

  note right of WAITING_SETTLEMENT
    Amount snapshot is fixed on settlement;
    payment service moves it to COMPLETED only when fully paid
  end note
```

### Order states

| State | Meaning | Terminal |
| --- | --- | --- |
| `DRAFT` | draft / to confirm | no |
| `WAITING_ARRIVAL` | waiting for arrival | no |
| `SERVING` | serving / table open | no |
| `WAITING_SETTLEMENT` | waiting for settlement | no |
| `WAITING_PAYMENT` | waiting for payment | no |
| `COMPLETED` | completed | yes |
| `CANCELLED` | cancelled | yes |
| `VOIDED` | voided | yes |
| `REFUNDED` | fully refunded | yes |
| `PARTIAL_REFUNDED` | partially refunded | yes |

## 2. Reservation `ktv_reservation`

```mermaid
stateDiagram-v2
  [*] --> PENDING

  PENDING --> CONFIRMED: store confirms
  PENDING --> CANCELLED: cancel
  CONFIRMED --> ARRIVED: arrived
  CONFIRMED --> CANCELLED: cancel
  PENDING --> NO_SHOW: no-show
  CONFIRMED --> NO_SHOW: no-show
  CONFIRMED --> CONVERTED: direct open table
  ARRIVED --> CONVERTED: open table

  CANCELLED --> [*]
  NO_SHOW --> [*]
  CONVERTED --> [*]

  note right of CONVERTED
    Once converted to a KTV session,
    room occupancy belongs to the resource context
  end note
```

### Reservation rules

- `PENDING` / `CONFIRMED` are pre-arrival: confirm, cancel, reschedule, mark no-show.
- `ARRIVED`: can open a table; cancellation must go through "cancel order" to avoid a dangling timed order.
- `CONVERTED`: reservation no longer occupies a reservation slot; room occupancy moves to the resource context.
- `CANCELLED` / `NO_SHOW` / `CONVERTED` are terminal.

## 3. KTV session `ord_ktv_session`

```mermaid
stateDiagram-v2
  [*] --> RESERVED

  RESERVED --> OPEN: open
  OPEN --> PAUSED: pause
  PAUSED --> OPEN: resume
  OPEN --> CLOSED: close
  PAUSED --> CLOSED: close
  RESERVED --> CANCELLED: cancel before billing
  OPEN --> CANCELLED: abnormal termination

  CLOSED --> [*]
  CANCELLED --> [*]
```

## Sources of truth

- Order: `platform-services/order/platform-order-service/.../domain/order/model/Order.java`
- Reservation: `.../domain/reservation/model/ReservationStatus.java`
- KTV session: `.../domain/ktv/model/KtvSession.java`
- Schema/comments: `.../src/main/resources/db/migration/V1__ord_order_baseline.sql`

## Related

- [Domain docs](../../40-domain/README.en.md)
- [Order service](../../50-services/platform/platform-order-service/README.en.md)
- [Database migration](../../80-development/standards/23_DATABASE_MIGRATION_STANDARD.en.md)
