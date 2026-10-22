# Public Support Domain Blueprint

[中文](PLATFORM_DOMAINS.md) | [English](PLATFORM_DOMAINS.en.md)

> Status: `draft` (`develop/2.4.0-hotel`).  
> Purpose: normalize transaction, inventory, user and order concepts, and define a three-horizon evolution.  
> Principle: separate concepts first, then services; modularize first, extract on triggers; do not build platforms early.

## 1. Separate the concepts

### User

| Concept | Meaning | Not equal to |
| --- | --- | --- |
| `platformUser` | global identity, stable across tenants | IM user or tenant customer |
| `account` | login credential | business customer |
| `imUser` | OpenChat profile/social identity | platform account |
| `customer` | tenant-scoped customer/member profile | global user |
| `tenant` | group/tenant | store |
| `store` | store | tenant |

```text
platformUserId 1 - N account
platformUserId 1 - N imUser
platformUserId 1 - N customer (per tenant)
tenantId 1 - N store
```

### Transaction

| Concept | Meaning | Not equal to |
| --- | --- | --- |
| `transaction` | end-to-end trade process | order table |
| `order` | transaction document/aggregate | payment |
| `payment` | funds movement/refund | order status |
| `settlement` | merchant/channel settlement and reconciliation | payment |
| `fulfillment` | check-in/session/table/class | order base status |

### Inventory

| Concept | Meaning | Owner |
| --- | --- | --- |
| `stock` | SKU/warehouse/quantity/cost | supporting platform |
| `capacity` | room/slot/table/class availability | business core |

`stock` and `capacity` never share tables or models.

### Price and promotion

| Concept | Meaning | Owner |
| --- | --- | --- |
| `rate/price` | base price | business core |
| `promotion` | coupon/campaign/discount | marketing support |
| `order snapshot` | price/discount at order time | order kernel |

## 2. Support domains

| Domain | Responsibility | Implementation | Horizon |
| --- | --- | --- | --- |
| Identity & Access | login, session, OAuth/OIDC, permission snapshot | `platform-identity-service` | H1 |
| Platform User | global identity/account mapping | planned; identity/customer composition | H1 |
| Tenant & Store | tenant, store, organization, roles | `platform-tenant-service` | H1 |
| Customer / Member | profile, points, wallet | `platform-customer-service` | H1 |
| Order Kernel | header/line/amount/payment/base lifecycle | `platform-order-service` | H1 |
| Payment | payment/refund/split collection/channel | `common-payment-service` | H1 |
| Stock | procurement-sales inventory | planned | H1/H2 |
| Marketing | coupon/campaign/member benefits | `platform-marketing-service` (partial) | H2 |
| Settlement | merchant/OTA/channel settlement | planned | H2 |
| Channel Integration | OTA/channel-manager framework | planned | H2/H3 |
| Notification | SMS/email/push | `common-sms-service` / `common-mail-service` | H1 |
| Media | images/video/files | `common-media-service` | H1 |
| Audit | audit/tracing | `common-audit-service` | H1 |
| Search | cross-business search | planned | H3 |
| Workflow / Approval | refund/void/manual approval | planned | H3 |

## 3. Invariants

- order amount is the snapshot at order time; payment/refund never mutate history;
- payment owns payment status; the order references it;
- settlement owns payout/reconciliation; payment does not split;
- stock owns quantities; business cores never write stock tables;
- capacity is owned by each business core; neither stock nor order shares it;
- customer owns points/wallet ledger; orders reference it;
- platformUser is cross-tenant identity; customer is tenant-scoped.

## 4. Three horizons

### H1: MVP / modular (current)

Order Kernel (inside order service), Stock (module/design), Identity + Platform User, Tenant + Customer, Payment, Media/Audit/Notification.

No new platform services; no generic Settlement/Search/Workflow; capacity stays in business cores.

### H2: multi-business reuse

Trigger: hotel + KTV + at least one more vertical depend on the same capability.

Extract `platform-inventory-service`, add Settlement, complete Marketing, add Channel Integration, extract `platform-trade-service` from order service.

Capacity stays in business cores; business extension tables stay with business.

### H3: platform scale

Trigger: multiple businesses, regions and teams release independently.

Search, Workflow, a unified event bus, data/analytics and multi-region deployment.

## 5. What to reserve now

- `platformUserId` now, to avoid a later cross-tenant order refactor;
- separate `orderId` / `paymentId` / `settlementId`;
- price/discount/currency snapshot on the order;
- outbox events: `order.created`, `order.paid`, `order.cancelled`, `payment.succeeded`, `stock.low`;
- versioned contracts and idempotency keys;
- business extension tables, never universal tables;
- no cross-database joins or shared tables;
- consistent tenant/audit/soft-delete fields.

## 6. Anti-patterns

- one `user` field for everything;
- order table carrying payment/refund/settlement status;
- stock managing room capacity;
- capacity inside the public platform;
- one order system per business;
- ten platform services before the first user;
- shared database tables as integration.

## 7. Related

- [Domain map](DOMAIN_MAP.en.md)
- [Order platform](order/README.en.md)
- [Inventory platform](inventory/README.en.md)
- [Domain classification standard](../80-development/standards/05_DOMAIN_CLASSIFICATION.en.md)
- [ADR-0012: order kernel](../20-architecture/adr/0012-order-kernel.md)
