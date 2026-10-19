# im-saas Engineering Topology

[中文](TOPOLOGY.zh.md) | [English](TOPOLOGY.en.md)

> Status: current stage (2.2.1)  
> Scope: im-saas product repositories under `D:\projects\cnb-oss` and the `open-im-server` runtime  
> Goal: give repositories, deployment units, runtime services and data infrastructure one consistent hierarchy.

## 1. How to read this document

The topology has five levels. It deliberately separates repositories, services, processes and data stores:

```text
Workspace
  └─ Product
       └─ Repository
            └─ Deployment unit
                 └─ Runtime service
                      └─ Data store
```

A repository may produce several deployment units (for example `open-chat-app` produces Android/iOS/Web), and a deployment unit may contain several services (for example the `open-im-local` environment of `open-im-server`).

## 2. Layer overview

```text
L0 Experience
├─ open-chat-app          Android / iOS / Web client
├─ open-chat-desktop      Electron desktop client
├─ open-saas-admin        SaaS operations console
├─ open-chat-admin        IM admin console / pc-admin
├─ open-saas-mobile       SaaS C-end / B-end H5
└─ open-website           Marketing site and downloads

L1 Edge and access
├─ ingress-nginx          Kind/ACK shared entry
├─ gateway                HTTPS REST gateway, auth pass-through, routing
├─ im-access-ws           WebSocket access
└─ unified-portal         Unified sign-in portal

L2 SaaS platform
├─ platform-identity-service
├─ platform-tenant-service
├─ platform-admin-service
├─ platform-customer-service
├─ platform-order-service
├─ platform-resource-service
└─ platform-marketing-service

L3 IM domain
├─ im-user-service
├─ im-message-service
├─ im-conversation-service
└─ im-admin-service

L4 Shared capabilities
├─ common-media-service
├─ common-audit-service
├─ common-mail-service
├─ common-sms-service
├─ common-payment-service
├─ common-payment-channel-service
└─ group-idaas-service

L5 Data and messaging
├─ MySQL                  open_im / open_saas / open_group / open_audit
├─ MongoDB                im_chat (message projection)
├─ Redis                  sessions, presence, cache, cross-instance coordination
├─ RocketMQ               IM commands and events
└─ MinIO                  managed object storage
```

## 3. Repository to deployment-unit mapping

| Repository | Deployment unit | Layer | Responsibility |
| --- | --- | --- | --- |
| `open-chat-app` | Android / iOS / Web | L0 | Mobile and Flutter Web |
| `open-chat-desktop` | Electron package | L0 | Desktop client |
| `open-saas-admin` | `saas-admin` | L0 | SaaS operations console |
| `open-chat-admin` | `pc-admin` | L0 | IM admin console |
| `open-saas-mobile` | `saas-mobile` | L0 | `/a380/` C-end and `/b/` B-end H5 |
| `open-website` | `open-website` | L0 | Marketing site, downloads and legal pages |
| `open-im-server` | `gateway` | L1 | REST gateway |
| `open-im-server` | `im-access-ws` | L1 | WebSocket access |
| `open-im-server` | `unified-portal` | L1 | Unified sign-in portal |
| `open-im-server` | `platform-*` | L2 | Identity, tenant, customer, order, resource, marketing, platform admin |
| `open-im-server` | `im-*` | L3 | User, message, conversation, IM admin |
| `open-im-server` | `common-*` / `group-idaas-service` | L4 | Media, audit, mail, SMS, payments, group IAM |
| `open-chat-turn` | TURN service | L1/L4 | WebRTC NAT traversal |
| `open-green-pass` | GreenPass Web | Separate product | Engineering test governance; not part of the im-saas runtime |

## 4. Key runtime flows

### 4.1 Client REST

```text
open-chat-app / open-chat-desktop / H5
  → Ingress /api/v1/**
  → gateway (validation, routing, pass-through)
  → platform-* / im-* / common-*
  → MySQL / Redis / MongoDB / MinIO
```

### 4.2 WebSocket

```text
Client
  → POST /api/v1/auth/ws-ticket (one-time ticket)
  → WS /ws/im/v1?ticket=...
  → im-access-ws
  → RocketMQ command
  → im-message-service
  → MySQL authoritative write + outbox
  → MongoDB / Redis projection
  → im-access-ws downstream
```

### 4.3 SaaS H5 and OAuth

```text
OpenChat App service board
  → im-admin-service /miniapp/services (catalog)
  → /a380/ or /b/
  → saas-mobile (Nginx)
  → OAuth / identity session
  → SaaS business page
```

### 4.4 Service catalog data

```text
im-admin-service
  └─ adm_miniapp_service_type
       └─ adm_miniapp_service_item
            ├─ consumer: fixed service list
            └─ operator: operator console entry (hidden, searchable, pinnable)
```

## 5. Data ownership

| Data | Authoritative store | Owner |
| --- | --- | --- |
| IM users, friends, devices, stickers | MySQL `open_im` | `im-user-service` |
| IM messages and groups | MySQL `open_im` + RocketMQ | `im-message-service` / `im-conversation-service` |
| Message hot projection, offline inbox | MongoDB `im_chat` | Projection only, never authoritative |
| Presence, sessions, cache | Redis | Rebuildable, never authoritative |
| SaaS tenants, IAM, wallet config | MySQL `open_saas` | `platform-tenant-service` and peers |
| Platform accounts and sessions | MySQL `open_saas` + Redis | `platform-identity-service` / `platform-admin-service` |
| Media objects | MinIO | `common-media-service` owns metadata |
| Audit events | MySQL `open_audit` | `common-audit-service` |
| Group IAM | MySQL `open_group` | `group-idaas-service` |

## 6. Boundary rules

1. L0 reaches the system only through the Ingress public entry; it never connects directly to L2/L3 services.
2. L1 only provides cross-cutting behavior and access; long-lived connections belong to `im-access-ws`.
3. L2 and L3 collaborate through domain interfaces; they never write each other's tables directly.
4. L4 provides shared capabilities and must not depend back on a specific business domain.
5. MySQL is the authoritative business fact store; MongoDB and Redis are projections or caches only.
6. Service-board display data is configured only in `im-admin-service`; clients must not hard-code the catalog.

## 7. Related documents

- [Architecture overview](../ARCHITECTURE.en.md)
- [Documentation center](../README.en.md)
- [Documentation audit](../DOCUMENTATION_AUDIT.en.md)
- [Local deployment](../deployment/LOCAL.md)
- [Service-board integration](../renovation/IM_OPEN_PLATFORM_04_SERVICE.md)
- [Gateway service](../services/gateway/README.md)
