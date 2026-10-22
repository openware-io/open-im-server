# Domain Map

[中文](DOMAIN_MAP.md) | [English](DOMAIN_MAP.en.md)

> Status: `current` (2026-10-22).  
> Registers every bounded context with type, owner, implementation module, dependencies and consumers.  
> Rule: [Domain Classification Standard](../80-development/standards/05_DOMAIN_CLASSIFICATION.en.md).

## 1. Legend

| Type | Meaning |
| --- | --- |
| `core` | differentiating, revenue or critical experience |
| `supporting` | required and reusable, not differentiating |
| `generic` | commodity/infrastructure, no business rules |
| `edge` | BFF, gateway or ingress; owns no domain model |

## 2. Domains

| Context | Type | Owner | Module | Depends on | Consumers | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| IM Conversation | `core` | IM product/tech | `im-conversation-service` | Identity, Tenant, Audit | Message, IM Admin, C-end | conversation, members, permissions |
| IM Message | `core` | IM product/tech | `im-message-service` | Conversation, User, Media, MQ | C-end, IM Admin | messages, read, recall, sync |
| IM User / Social | `core` | IM product/tech | `im-user-service` | Identity, Tenant, Media | Conversation, Message, C-end | profile, relations, presence |
| IM Admin | `supporting` | IM platform | `im-admin-service` | Message, User, Audit | operations console | moderation, read models |
| Hotel Booking | `core` | Hotel product/tech | `platform-order-service` `domain/hotel` (planned) | Order Kernel, Payment, Customer, Stock, Audit | C-end, Admin BFF, OTA | room types, rates, availability, booking, deposit |
| KTV Operations | `core` | KTV product/tech | `platform-resource-service` KTV modules + `platform-order-service` KTV modules | Order Kernel, Payment, Customer, Audit | C-end, Admin BFF, B-end | room slots, open/close, billing |
| Restaurant / Massage / Gym | `core` (planned) | each product/tech | TBD | Order Kernel, Payment, Customer, Stock, Audit | C-end, Admin BFF | own capacity/scheduling/fulfillment; no shared capacity tables |
| Order Kernel | `supporting` | transaction platform | `platform-order-service` `domain/order` | Payment, Customer, Audit | all businesses, C-end order center | header/line/amount/payment/base lifecycle |
| Customer / Member | `supporting` | customer platform | `platform-customer-service` | Identity, Tenant, Audit | all businesses | profile, points, wallet, IM binding |
| Marketing | `supporting` | marketing platform | `platform-marketing-service` | Customer, Order Kernel, Payment | businesses, operations | coupons/campaigns; not fully implemented |
| Identity / IAM | `supporting` | identity platform | `platform-identity-service` | Tenant, Audit | all services | login, session, OAuth/OIDC, permission snapshot |
| Tenant / Store | `supporting` | tenant platform | `platform-tenant-service` | Identity, Audit | all businesses | tenant, store, roles, business time |
| Resource Catalog | `supporting` | resource platform | `platform-resource-service` resource/roomtype | Tenant, Audit | KTV, Hotel (physical catalog only) | resource/room-type catalog, no business capacity |
| Stock | `supporting` (planned) | supply-chain platform | TBD | Tenant, Audit | Hotel, KTV, Restaurant, Gym, Retail | SKU, warehouse, balance, receipts/issues |
| Payment | `supporting` | payment platform | `common-payment-service` + `common-payment-channel-service` | Customer, Order Kernel, Audit | all businesses | payment, refund, split collection, reconciliation |
| Media | `supporting` | media platform | `common-media-service` | Tenant, Audit | IM, Hotel, Marketing | images/video/files, upload sessions |
| Audit | `supporting` | platform governance | `common-audit-service` | Tenant | all services | audit log, tracing |
| Notification | `supporting` | notification platform | `common-sms-service`, `common-mail-service` | Tenant, Audit | all businesses | SMS, email, verification/notification |
| Admin BFF | `edge` | web/ingress | `platform-admin-service` | all businesses | operations console | aggregation/translation, no domain model |
| Gateway / WS Access | `generic` | infrastructure | `gateways/gateway`, `gateways/im-access-ws` | Identity, Tenant | all clients | routing, auth, long connection |

## 3. Dependency rules

```text
Core -> Supporting -> Generic
Core -> Generic
Edge -> Core / Supporting
Supporting -X-> Core
Generic -X-> Core / Supporting
Core -X-> another Core's internal model
```

## 4. Service-to-context mapping

| Service | Contexts |
| --- | --- |
| `platform-order-service` | Order Kernel (supporting) + Hotel Booking (core) + KTV order modules (core) |
| `platform-resource-service` | Resource Catalog (supporting) + KTV Operations (core) |
| `platform-admin-service` | Admin BFF (edge) |
| `platform-customer-service` | Customer/Member (supporting) |
| `platform-identity-service` | Identity/IAM (supporting) |
| `platform-tenant-service` | Tenant/Store (supporting) |
| `platform-marketing-service` | Marketing (supporting) |
| `common-payment-service` / `-channel-service` | Payment (supporting) |
| `common-media-service` | Media (supporting) |
| `common-audit-service` | Audit (supporting) |
| `common-sms-service` / `common-mail-service` | Notification (supporting) |
| `im-conversation-service` | IM Conversation (core) |
| `im-message-service` | IM Message (core) |
| `im-user-service` | IM User/Social (core) |
| `im-admin-service` | IM Admin (supporting) |
| `gateways/gateway` | Gateway (generic) |
| `gateways/im-access-ws` | WS Access (generic) |

## 5. Evolution

- a new core domain requires an ADR;
- a new supporting domain states its reuse scope and owner;
- generic domains must not contain business rules;
- promoting supporting to core requires re-review and a map update;
- splitting/merging a context updates the service mapping and diagrams.

## 6. Related

- [Domain classification standard](../80-development/standards/05_DOMAIN_CLASSIFICATION.en.md)
- [Inventory platform](inventory/README.en.md)
- [Order platform](order/README.en.md)
- [Hotel business](hotel/README.en.md)
- [Service directory](../50-services/README.en.md)
