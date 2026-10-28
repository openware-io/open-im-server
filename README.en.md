# OpenChat

[中文](README.md) | [English](README.en.md) · [Product introduction](docs/10-product/README.en.md) · [Engineering topology](docs/20-architecture/TOPOLOGY.en.md) · [One-click deployment](#one-click-deployment-recommended)

[![Documentation governance](https://github.com/openware-io/open-im-server/actions/workflows/docs-governance.yml/badge.svg?branch=main)](https://github.com/openware-io/open-im-server/actions/workflows/docs-governance.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

**An open-source instant-messaging + multi-tenant SaaS platform.**

![OpenChat website](docs/assets/screenshots/website.jpg)

OpenChat combines an IM open platform, multi-tenant SaaS, operations consoles, mobile/desktop clients and one-click local deployment in one engineering system. It works as a runnable product foundation and as a reference implementation for microservices, reliable long connections and multi-tenant permissions.

<table>
  <tr>
    <td align="center"><img src="docs/assets/screenshots/c-end.jpg" width="360" alt="OpenChat SaaS C-end"><br><sub>SaaS C-end</sub></td>
    <td align="center"><img src="docs/assets/screenshots/phone-im-home.jpg" width="180" alt="OpenChat conversations"><br><sub>App chats</sub></td>
    <td align="center"><img src="docs/assets/screenshots/phone-im-service.jpg" width="180" alt="OpenChat service board"><br><sub>App services</sub></td>
  </tr>
</table>

> Status: `current` (developing on `develop/2.4.0-hotel`; version `2.4.0-SNAPSHOT`)  
> Engineering topology and documentation entry: [Documentation center](docs/README.en.md) and [Engineering topology](docs/20-architecture/TOPOLOGY.en.md).

## One-click deployment (recommended)

`deploy-k8s.ps1` is the one-click entry for the complete local environment: it builds the service images, imports them into Kind, applies the Kubernetes manifests, waits for health and prints the entry points. This path has been validated on Windows + Docker Desktop + Kind.

### Requirements

| Item | Requirement |
| --- | --- |
| OS | Windows 10/11, PowerShell 5.1+ (PowerShell 7 recommended) |
| Container runtime | Docker Desktop, **Linux containers**, Docker Engine running |
| Kubernetes tools | `kind` and `kubectl` on `PATH`; Docker Desktop's built-in Kubernetes is not used |
| Java | JDK 25 with a valid `JAVA_HOME` |
| Build | Repository `mvnw.cmd`; first run needs Maven/npm/Docker registry access or a warm cache |
| Version control | Git |
| Config | Repository-root `.env`; generate it first and review the local values |
| Ports | Default entry `30080`; Docker/Kind needs free ports and disk space |
| Recommended resources | 16 GB RAM, 4 vCPU, 30 GB free disk (lower configurations may time out while starting all JVMs/Pods) |

### Run

```powershell
cd open-im-server

# 1. Generate .env (existing file is preserved; use -Force to recreate)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\initialize-local-env.ps1

# 2. Review .env (database, Redis, MQ, MinIO addresses and secrets)
notepad .env

# 3. Build and deploy
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy-k8s.ps1
```

The first run builds all service images and downloads dependencies; duration depends on network and machine speed.

### Entry points

| Entry | URL |
| --- | --- |
| Unified portal | `http://<host-ip>:30080/` |
| SaaS admin | `http://<host-ip>:30080/saas/` |
| SaaS C-end (H5) | `http://<host-ip>:30080/c/` |
| SaaS B-end / operator console | `http://<host-ip>:30080/b/` |
| IM admin | `http://<host-ip>:30080/im/` |
| Gateway health | `http://<host-ip>:30080/actuator/health` |
| Website | `http://<host-ip>:30080/website/` |

Use `http://127.0.0.1:30080/` for local-only access. LAN devices need inbound `30080`.

### Accounts and passwords

| Role | Username | Credential source |
| --- | --- | --- |
| SaaS administrator | `admin` | Seeded admin account; password set/rotated by the deployment environment, never stored in the repo |
| B-end operator | An IM account granted operator permission | No separate B-end password; sign in through IM OAuth |
| IM admin | Managed by the SaaS admin account system | Use `admin` or another authorized admin |

Rules and locations:

- The SaaS admin seed lives in `platform-services/admin/platform-admin-service/src/main/resources/db/migration/V1__cons_admin_account.sql`; it stores only a BCrypt `password_hash`, never plaintext.
- `scripts/initialize-local-env.ps1` generates infrastructure secrets (DB, Redis, MongoDB, RocketMQ, MinIO) only; it does **not** generate or reset the SaaS admin password.
- Change the admin password after signing in through `POST /api/v1/admin/auth/password` (or the admin UI); the current session is revoked immediately.
- Verification scripts never embed a password; they read `IM_SAAS_ADMIN_PASSWORD` and fail explicitly when it is missing.
- B-end needs no separate account: grant an IM account operator permission and store scope in the SaaS admin, then sign in at `/b/` through IM OAuth and select a store.
- Local smoke has been verified with the `admin` account; the actual password is held by the deployer and is intentionally not committed to the repo or this document.

### Verify and stop

```powershell
# All Pods should be Running and Ready
kubectl --context kind-open-im-local get pods -n open-im-local

# Inspect entries
kubectl --context kind-open-im-local get ingress -n open-im-local

# Stop and delete the local Kind environment
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy-k8s.ps1 -Stop
```

Force a rebuild or prune old images with:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy-k8s.ps1 -ForceRebuild -ForceRedeploy
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy-k8s.ps1 -ForceRedeploy -PruneImages
```

See [Local deployment](docs/60-deployment/LOCAL.md) for all parameters, data lifecycle and troubleshooting.

## Runtime topology

The system is organized as “experience → edge → SaaS platform → IM domain → shared capabilities → data infrastructure”. See [Engineering topology](docs/20-architecture/TOPOLOGY.en.md) for the repository/deployment mapping.

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

## Other run modes

Besides the one-click Kind deployment, two independent local modes are available. See [Local deployment](docs/60-deployment/LOCAL.md) for prerequisites, startup order, stop scope and verification.

| Mode | Use case | Command | Gateway |
| --- | --- | --- | --- |
| Local processes | Debug one service / Java breakpoints | `powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy-local.ps1` | `http://127.0.0.1:3002` |
| Docker Compose | Daily local integration | `powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy-docker.ps1` | `http://127.0.0.1:3002` |
| Kind Kubernetes | Full IM + SaaS, service discovery, rolling updates | `powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy-k8s.ps1` | `http://127.0.0.1:30080/saas/` |

## Documentation

```text
im-saas docs
├─ 00 Getting started and product
│  ├─ Getting started (./docs/00-getting-started/README.en.md)
│  ├─ Product introduction (./docs/10-product/README.en.md)
│  ├─ Documentation center (./docs/README.en.md)
│  ├─ One-click deployment (top of this README)
│  └─ Engineering topology (./docs/20-architecture/TOPOLOGY.en.md)
├─ 20 Architecture and 30 Reference
│  ├─ Architecture overview (./docs/20-architecture/OVERVIEW.en.md)
│  ├─ Architecture diagrams (./docs/20-architecture/diagrams/)
│  ├─ API / protocol (./docs/30-reference/)
│  ├─ ADR / MQ registry (./docs/20-architecture/adr/, ./docs/30-reference/mq/)
│  └─ Contracts (./docs/30-reference/contracts/)
├─ 40 Domain and 50 Services
│  ├─ Domain docs (./docs/40-domain/)
│  ├─ Service catalog (./docs/50-services/README.en.md)
│  └─ edge / im / platform / common
├─ 60 Deployment and 70 Operations
│  ├─ Local deployment (./docs/60-deployment/LOCAL.md)
│  ├─ Deployment index (./docs/60-deployment/README.md)
│  └─ Release runbook (./docs/70-operations/RELEASE_RUNBOOK.md)
├─ 80 Development and governance
│  ├─ Engineering rules (./docs/80-development/governance/ENGINEERING_RULES.md)
│  ├─ Gate registry (./docs/80-development/governance/GATE_REGISTRY.md)
│  ├─ Standards (./docs/80-development/standards/)
│  ├─ IDE / tooling (./docs/80-development/tooling/)
│  └─ Contributing (./docs/80-development/contributing/)
├─ 90 Security and 91 Compliance
│  ├─ Security (./docs/90-security/)
│  └─ Compliance (./docs/91-compliance/)
└─ 99 Historical archive
   ├─ Archive index (./docs/99-archive/README.en.md)
   ├─ renovation (./docs/99-archive/renovation/)
   ├─ superpowers (./docs/99-archive/superpowers/)
   └─ Audit snapshots (./docs/archive/audits/)
```
   ├─ superpowers (./docs/superpowers/)
   └─ Audit snapshot (./docs/audit-2026-09-17-exceptions-and-i18n.md)
```

- Full hierarchy, reading paths and bilingual coverage: [Documentation center](docs/README.en.md).
- Service entry: [services](docs/50-services/).
- Standards entry: [standards](docs/80-development/standards/).
- Standards system and gaps: [standards map](docs/80-development/standards/STANDARDS_MAP.en.md).
- Gate checklist: [GATE_REGISTRY](docs/80-development/governance/GATE_REGISTRY.md) · [Gate catalog](docs/80-development/governance/GATE_CATALOG.en.md).

### Standards

- [00 DDD microservice outline](docs/80-development/standards/00_DDD_MICROSERVICE_SOFTWARE_ENGINEERING_OUTLINE.md)
- [01 Text encoding and batch editing](docs/80-development/standards/01_TEXT_ENCODING_AND_BATCH_EDITING.en.md)
- [02 Architecture Decision Records](docs/80-development/standards/02_ARCHITECTURE_DECISION_RECORDS.en.md)
- [03 Engineering principles](docs/80-development/standards/03_ENGINEERING_PRINCIPLES.en.md)
- [04 Gate governance](docs/80-development/standards/04_GATE_GOVERNANCE.en.md)
- [05 Domain classification](docs/80-development/standards/05_DOMAIN_CLASSIFICATION.en.md)
- [10 DDD service conventions](docs/80-development/standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)
- [12 RocketMQ conventions](docs/80-development/standards/12_ROCKETMQ_CONVENTIONS.md)
- [13 Platform media conventions](docs/80-development/standards/13_PLATFORM_MEDIA_CONVENTIONS.md)
- [20 Maven conventions](docs/80-development/standards/20_MAVEN_ENGINEERING_CONVENTIONS.md)
- [21 Persistence stack](docs/80-development/standards/21_PERSISTENCE_STACK.md)
- [22 Technology stack matrix](docs/80-development/standards/22_TECHNOLOGY_STACK_VERSION_MATRIX.md)
- [23 Database migration](docs/80-development/standards/23_DATABASE_MIGRATION_STANDARD.en.md)
- [24 Currency conventions](docs/80-development/standards/24_CURRENCY_CONVENTIONS.md)
- [30 Client release governance](docs/80-development/standards/30_CLIENT_RELEASE_GOVERNANCE.md)
- [31 Client release operations](docs/80-development/standards/31_CLIENT_RELEASE_SKILL.md)
- [40 Observability conventions](docs/80-development/standards/40_OBSERVABILITY_CONVENTIONS.md)
- [41 Java logging conventions](docs/80-development/standards/41_JAVA_LOGGING_CONVENTIONS.md)
- [42 Logging and diagnostics](docs/80-development/standards/42_LOGGING_AND_OUTPUTS.md)
- [43 E2E testing conventions](docs/80-development/standards/43_E2E_TESTING_CONVENTIONS.md)
- [50 Permission and data scope](docs/80-development/standards/50_PERMISSION_AND_DATA_SCOPE.md)
