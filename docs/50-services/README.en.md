# Service Directory

[中文](README.md) | [English](README.en.md)

> Status: `current` (`develop/2.3.0`)  
> Upstream structure: [Engineering topology](../20-architecture/TOPOLOGY.en.md). Runtime ports and routes follow the Gateway config.

## 1. Layers

### Edge

| Service | Responsibility |
| --- | --- |
| [gateway](edge/gateway/README.md) | HTTPS REST entry, routing and auth pass-through |
| [im-access-ws](edge/im-access-ws/README.md) | WebSocket access |

### IM domain

| Service | Responsibility |
| --- | --- |
| [im-user-service](im/im-user-service/README.md) | Users, auth, devices, friends, stickers |
| [im-message-service](im/im-message-service/README.md) | Messages, read receipts, recall, channels, secret messages |
| [im-conversation-service](im/im-conversation-service/README.md) | Groups, channels, secret chats, RTC config |
| [im-admin-service](im/im-admin-service/README.md) | IM admin APIs, client config, service board |

### SaaS platform

| Service | Responsibility |
| --- | --- |
| [platform-admin-service](platform/platform-admin-service/README.md) | SaaS admin BFF, menus, context, KTV config and reservation BFF |
| [platform-identity-service](platform/platform-identity-service/README.md) | SaaS accounts, login, sessions, OAuth/OIDC |
| [platform-tenant-service](platform/platform-tenant-service/README.md) | Tenants, organizations, stores, IAM and tenant config |
| [platform-customer-service](platform/platform-customer-service/README.md) | Customers, members, points, wallet ledger |
| [platform-resource-service](platform/platform-resource-service/README.md) | Resources, room types, inventory and occupation |
| [platform-marketing-service](platform/platform-marketing-service/README.md) | Marketing, promotions and campaigns |

`platform-order-service` owns orders, reservations and fulfillment. Its standalone service doc is pending; current contracts are in the Gateway routes and `docs/archive/renovation/ORDER_RESERVATION_01_SERVICE.md` (historical background).

### Shared capabilities

| Service | Responsibility |
| --- | --- |
| [common-media-service](common/common-media-service/README.md) | Upload sessions, object metadata, access authorization |
| [common-audit-service](common/common-audit-service/README.md) | Audit ingestion and query |
| [common-payment-service](common/common-payment-service/README.md) | Payment orchestration |
| [common-payment-channel-service](common/common-payment-channel-service/README.md) | Payment channel configuration |
| [common-sms-service](common/common-sms-service/README.md) | SMS delivery |

`common-mail-service` and `group-idaas-service` are deployed; their standalone service docs are pending.

## 2. Service-doc structure

Each service doc should cover:

1. responsibility and boundaries;
2. port, gateway entry and controller paths;
3. dependencies (MySQL / Redis / MQ / external services);
4. data ownership and migration location;
5. metrics, alerts and troubleshooting;
6. related contracts and test entry.

## 3. Related docs

- [Engineering topology](../20-architecture/TOPOLOGY.en.md)
- [Architecture overview](../20-architecture/OVERVIEW.en.md)
- [Deployment](../60-deployment/LOCAL.md)
- [Documentation center](../README.en.md)
