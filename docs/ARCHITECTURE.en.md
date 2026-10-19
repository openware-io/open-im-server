# Architecture Overview

[中文](ARCHITECTURE.md) | [English](ARCHITECTURE.en.md)

> Status: `current` (developing on `develop/2.3.0`)  
> Repository/deployment-unit mapping and data ownership: [Engineering topology](architecture/TOPOLOGY.en.md).

## 1. Overall shape

The system uses a “unified edge + domain microservices + long-lived connection access” structure:

```text
Client / H5 / admin console
  → Ingress
  → gateway (HTTPS REST cross-cutting entry)
  → platform-* / im-* / common-*
  → MySQL / MongoDB / Redis / RocketMQ / MinIO

Client
  → im-access-ws (WebSocket)
  → RocketMQ
  → im-message-service
```

- External clients reach the system only through the Ingress public entry;
- REST requests are routed by `gateway` to business microservices;
- Long-lived connections enter through `im-access-ws`; commands flow through RocketMQ into the message domain;
- Service-to-service HTTP calls use Kubernetes Service/DNS discovery;
- Business facts are written to MySQL; MongoDB/Redis only hold projections, caches and presence.

## 2. Layers

| Layer | Components | Responsibility |
| --- | --- | --- |
| L0 Experience | `open-chat-app`, `open-chat-desktop`, `open-saas-admin`, `open-chat-admin`, `open-saas-mobile`, `open-website` | End-user, operations and marketing entry points |
| L1 Edge | `gateway`, `im-access-ws`, `unified-portal` | REST gateway, WebSocket access, unified sign-in |
| L2 SaaS platform | `platform-identity/tenant/admin/customer/order/resource/marketing-service` | Identity, tenant, customer, order, resource, marketing and platform admin |
| L3 IM domain | `im-user/message/conversation/admin-service` | User, message, conversation and IM admin |
| L4 Shared capabilities | `common-media/audit/mail/sms/payment/payment-channel-service`, `group-idaas-service` | Media, audit, mail, SMS, payments, group IAM |
| L5 Data infrastructure | MySQL, MongoDB, Redis, RocketMQ, MinIO | Authoritative data, projections, cache, messaging and object storage |

## 3. Service boundaries

### Edge

- **gateway**: single public entry; routing, auth pass-through, rate limiting, canary and audit; no business orchestration.
- **im-access-ws**: WebSocket connection/authentication, presence, cross-instance broadcast, permission and delivery checks.
- **unified-portal**: unified sign-in and entry orchestration.

### SaaS platform

- **platform-identity-service**: SaaS accounts, login, sessions, OAuth/OIDC and tenant context.
- **platform-tenant-service**: tenants, stores, organizations, IAM permissions, tenant configuration and wallet display config.
- **platform-admin-service**: platform operations APIs, platform accounts and platform-level config.
- **platform-customer-service**: customers, members, points, wallet ledger and stored-value display.
- **platform-order-service**: reservations, orders, fulfillment and KTV orchestration.
- **platform-resource-service**: resources, room types, inventory and occupation.
- **platform-marketing-service**: campaigns, promotions and coupons.

### IM domain

- **im-user-service**: profiles, registration/login, device tokens, E2EE device keys, friendships, user stickers; points belong to the SaaS customer domain.
- **im-message-service**: history, offline messages, read receipts, recall/clear, channel cursors, secret-message ciphertext and destruction.
- **im-conversation-service**: groups and members, roles/mute rules, channels, secret chats (E2EE), RTC config.
- **im-admin-service**: IM admin APIs, client config, release checks, reports and mini-program service configuration.

### Shared capabilities

- **common-media-service**: upload sessions, object metadata, private access authorization, storage adapters and lifecycle cleanup.
- **common-audit-service**: audit event ingestion and query.
- **common-mail-service / common-sms-service**: email and SMS delivery.
- **common-payment-service / common-payment-channel-service**: payment orchestration and channel configuration.
- **group-idaas-service**: group-wide identity and IAM.

## 4. Data and infrastructure

- **MySQL**: the only authoritative business store; MyBatis-Plus `3.5.17` is the relational stack.
- **MongoDB**: message hot projection and offline inbox only; never authoritative.
- **Redis**: presence, hot state, cross-instance delivery/broadcast and session cache; never authoritative.
- **RocketMQ**: IM command buffering, decoupling and per-conversation ordering.
- **MinIO**: managed object storage; binaries go directly to storage, control plane goes through `common-media-service`.

Flyway is owned per service that owns its MySQL schema, including `im-user-service`, `im-message-service`, `im-admin-service`, `platform-tenant-service`, `platform-identity-service`, `platform-customer-service`, `platform-order-service`, `platform-resource-service`, `common-media-service` and `common-audit-service`. The service's `db/migration` directory is the source of truth.

## 5. Constraints

- Gateway does cross-cutting work only, never business orchestration;
- All external REST paths keep the `/api` prefix;
- `im-access-ws` never treats local-memory presence as the final fact; cross-instance coordination must use Redis;
- The access layer owns no authoritative store; it only accepts connections, converts protocols and dispatches commands;
- Friendships belong to the user domain, groups to the conversation domain, and admin capabilities to the admin domain;
- Service-board display data is configured in `im-admin-service`; clients must not hard-code it;
- See [Engineering topology](architecture/TOPOLOGY.en.md) for repositories, deployment units and data ownership.
