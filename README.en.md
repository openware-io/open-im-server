# OpenIM Server

[中文](README.md) | [English](README.en.md)

> Status: `current` (2.2.1 stage)  
> Engineering topology and documentation entry: [Documentation center](./docs/README.en.md) and [Engineering topology](./docs/architecture/TOPOLOGY.en.md).

## Local deployment

- [Deployment index](./docs/deployment/README.md)
- [Scripts](./scripts/README.md)
- [Kubernetes manifests](./k8s/README.md)

OpenIM Server is split into microservices. The public entry is `gateway` (HTTPS/WSS); the backend is split into the user, message, conversation, admin, SaaS platform, shared capability and long-lived connection layers.

## Runtime topology

The system is organized as “experience → edge → SaaS platform → IM domain → shared capabilities → data infrastructure”. See [Engineering topology](./docs/architecture/TOPOLOGY.en.md) for the repository/deployment mapping.

- **L0 Experience**: `open-chat-app`, `open-chat-desktop`, `open-saas-admin`, `open-chat-admin`, `open-saas-mobile`, `open-website`
- **L1 Edge**: `gateway` (HTTPS REST), `im-access-ws` (WebSocket), `unified-portal` (unified sign-in)
- **L2 SaaS platform**: `platform-identity-service`, `platform-tenant-service`, `platform-admin-service`, `platform-customer-service`, `platform-order-service`, `platform-resource-service`, `platform-marketing-service`
- **L3 IM domain**: `im-user-service`, `im-message-service`, `im-conversation-service`, `im-admin-service`
- **L4 Shared capabilities**: `common-media-service`, `common-audit-service`, `common-mail-service`, `common-sms-service`, `common-payment-service`, `common-payment-channel-service`, `group-idaas-service`
- **L5 Data**: MySQL (`open_im` / `open_saas` / `open_group` / `open_audit`), MongoDB (`im_chat`), Redis, RocketMQ, MinIO

## Entry points

- Gateway HTTP: `http://localhost:3002/api/v1/**`
- Gateway WSS: `ws://localhost:3002/ws/im/v1?ticket={one-time-ticket}`
- User service: `3100`
- Message service: `3200`
- Conversation service: `3300`
- Admin service: `3400`
- Order service: `3500`
- Media support service: `3600`
- Access WS: `3001`

Only Gateway HTTP/WSS is a public client entry. Other ports are for local debugging and service-to-service traffic. Media control plane goes through `/api/v1/media/**`; binaries go directly to managed object storage.

## Routing

External REST APIs use `/api/v1/**`. Gateway applies `StripPrefix=2` to business routes, so controllers receive paths without `/api/v1`. The authoritative route table is `gateways/gateway/src/main/resources/application.yml`; update this table and the gateway service doc in the same change.

| Client path | Service path | Target | Route id |
| --- | --- | --- | --- |
| `/api/v1/auth/**` | `/auth/**` | `im-user-service` | `api-auth` |
| `/api/v1/users/**` | `/users/**` | `im-user-service` | `api-users` |
| `/api/v1/device-tokens/**` | `/device-tokens/**` | `im-user-service` | `api-device-tokens` |
| `/api/v1/friends/**` | `/friends/**` | `im-user-service` | `api-friends` |
| `/api/v1/points/**` | `/points/**` | `im-user-service` | `api-points` |
| `/api/v1/user-stickers/**` | `/user-stickers/**` | `im-user-service` | `api-user-stickers` |
| `/api/v1/messages/**` | `/messages/**` | `im-message-service` | `api-messages` |
| `/api/v1/favorites/**` | `/favorites/**` | `im-message-service` | `api-favorites` |
| `/api/v1/conversations/**` | `/conversations/**` | `im-conversation-service` | `api-conversations` |
| `/api/v1/groups/**` | `/groups/**` | `im-conversation-service` | `api-groups` |
| `/api/v1/rtc/**` | `/rtc/**` | `im-conversation-service` | `api-rtc` |
| `/api/v1/business/**` | `/business/**` | `platform-order-service` | `api-business-order` | Orders, KTV, reservations and catalog orchestration |
| `/api/v1/media/**` | `/media/**` | `common-media-service` | `api-media` |
| `/api/v1/admin/**` | `/admin/**` | `im-admin-service` | `api-admin` |
| `/api/v1/config/client/**` | `/config/client/**` | `im-admin-service` | `api-client-config` |
| `/api/v1/config/app-update` | `/config/app-update` | `im-admin-service` | `api-app-update` |
| `/api/v1/miniapp/services` | `/miniapp/services` | `im-admin-service` | `api-miniapp-services` |
| `/api/v1/reports/**` | `/reports/**` | `im-admin-service` | `api-reports` |
| `/ws/im/v1?ticket=...` | — | `im-access-ws` | WebSocket, no `StripPrefix` |

## Run locally

Three independent local modes are available. See [Local deployment](./docs/deployment/LOCAL.md) for prerequisites, startup order, stop scope and verification.

| Mode | Use case | Command | Gateway |
| --- | --- | --- | --- |
| Local processes | Debug one service / Java breakpoints | `powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy-local.ps1` | `http://127.0.0.1:3002` |
| Docker Compose | Daily local integration | `powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy-docker.ps1` | `http://127.0.0.1:3002` |
| Kind Kubernetes | Full IM + SaaS, service discovery, rolling updates | `powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy-k8s.ps1` | `http://127.0.0.1:30080/saas/` |

## Documentation

- [Documentation center](./docs/README.en.md)
- [Engineering topology](./docs/architecture/TOPOLOGY.en.md)
- [Architecture overview](./docs/ARCHITECTURE.en.md)
- [Documentation audit](./docs/DOCUMENTATION_AUDIT.en.md)
- [Engineering rules](./docs/ENGINEERING_RULES.md)
- [Release runbook](./docs/RELEASE_RUNBOOK.md)
- [Services](./docs/services/)
- [Standards](./docs/standards/)
