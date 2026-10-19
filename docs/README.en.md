# im-saas Documentation Center

[中文](README.md) | [English](README.en.md)

> Scope: im-saas product repositories under `D:\projects\cnb-oss` and the `open-im-server` engineering docs  
> Current stage: developing on `develop/2.3.0` (latest stage tag `2.2.1`)  
> Language policy: top-level engineering and delivery docs are bilingual; historical audit/renovation records stay Chinese-only and are marked as historical.

## 1. Documentation hierarchy

```text
im-saas docs
├─ 00 Getting started
│  ├─ One-click deployment
│  ├─ Entry points and account rules
│  └─ Documentation audit (findings and plan)
├─ 10 Architecture
│  ├─ Engineering topology (repo / deployment / service / data)
│  ├─ Architecture overview (runtime boundaries and ownership)
│  └─ Engineering rules (development and gates)
├─ 20 Deployment and operations
│  ├─ Local deployment
│  ├─ Docker Compose
│  ├─ Kubernetes / Kind
│  └─ Release runbook
├─ 30 Services
│  ├─ Edge (gateway / im-access-ws)
│  ├─ IM domain
│  ├─ SaaS platform
│  └─ Shared capabilities
├─ 40 Business
│  └─ Conversation, privacy, KTV and order semantics
├─ 50 Standards
│  └─ DDD, RocketMQ, media, release, currency, logging and testing
└─ 90 Historical
   ├─ renovation
   ├─ superpowers
   └─ audit snapshots
```

## 2. Reading paths

| Goal | Order |
| --- | --- |
| First run | One-click deployment → entry points/accounts → engineering topology |
| Understand the system | Engineering topology → architecture overview → service docs |
| Local development | Local deployment → service docs → engineering rules |
| Release | Release runbook → deployment docs → E2E conventions |
| Historical context | Documentation audit → renovation/audit (background only, not current contracts) |

## 3. Index by layer

### 00 Getting started

| Document | Relationship |
| --- | --- |
| [One-click deployment](../README.en.md#one-click-deployment-recommended) | Start here: requirements, Kind deployment, entries and verification |
| [Engineering topology](architecture/TOPOLOGY.en.md) | After deployment: repositories, deployment units, services and data |
| [Documentation audit](DOCUMENTATION_AUDIT.en.md) | Current documentation findings, priorities and plan |

### 10 Architecture and governance

| Document | Relationship |
| --- | --- |
| [Engineering topology](architecture/TOPOLOGY.en.md) | Top-level structure |
| [Architecture overview](ARCHITECTURE.en.md) | Runtime boundaries, ownership and data |
| [Engineering rules](ENGINEERING_RULES.md) | Development, review and gates |
| [Gate registry](GATE_REGISTRY.md) | Executable checks derived from the rules |

### 20 Deployment and operations

| Document | Relationship |
| --- | --- |
| [Local deployment](deployment/LOCAL.md) | Shared entry for local modes |
| [Docker Compose](deployment/DOCKER.md) | Daily integration |
| [Kubernetes manifests](../k8s/README.md) | Kind/ACK resources and images |
| [Release runbook](RELEASE_RUNBOOK.md) | Release, rollback and traceability |
| [Deployment index](deployment/README.md) | Parent index for deployment docs |

### 30 Services and business

| Document | Relationship |
| --- | --- |
| [Services](services/) | Runtime service responsibilities and APIs |
| [Gateway](services/gateway/README.md) | Edge entry |
| [IM user service](services/im-user-service/README.md) | IM domain |
| [IM message service](services/im-message-service/README.md) | IM domain |
| [IM conversation service](services/im-conversation-service/README.md) | IM domain |
| [IM admin service](services/im-admin-service/README.md) | IM domain |
| [Media support](services/common-media-service/README.md) | Shared capabilities |
| [Business docs](business/) | Business semantics above service docs |

### 50 Standards

| Document | Relationship |
| --- | --- |
| [00 DDD outline](standards/00_DDD_MICROSERVICE_SOFTWARE_ENGINEERING_OUTLINE.md) | Standards umbrella |
| [10 DDD service conventions](standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md) | Service implementation |
| [12 RocketMQ conventions](standards/12_ROCKETMQ_CONVENTIONS.md) | Messaging and events |
| [13 Platform media conventions](standards/13_PLATFORM_MEDIA_CONVENTIONS.md) | Media and object storage |
| [14 Client release governance](standards/14_CLIENT_RELEASE_GOVERNANCE.md) | Client versions |
| [15 Client release skill](standards/15_CLIENT_RELEASE_SKILL.md) | Release operations |
| [16 Currency conventions](standards/16_CURRENCY_CONVENTIONS.md) | Money, tokens and display |
| [20 Maven conventions](standards/20_MAVEN_ENGINEERING_CONVENTIONS.md) | Build and dependencies |
| [21 Persistence stack](standards/21_PERSISTENCE_STACK.md) | Data access |
| [22 Technology stack matrix](standards/22_TECHNOLOGY_STACK_VERSION_MATRIX.md) | Version baseline |
| [40 Observability](standards/40_OBSERVABILITY_CONVENTIONS.md) | Logs, metrics and traces |
| [43 E2E testing](standards/43_E2E_TESTING_CONVENTIONS.md) | Acceptance and smoke tests |

### 90 Historical archive

| Document | Relationship |
| --- | --- |
| [renovation](renovation/) | Transformation history; background only |
| [superpowers](superpowers/) | Design and plan snapshots |
| [Historical audit](audit-2026-09-17-exceptions-and-i18n.md) | 2026-09-17 audit snapshot |

## 4. Language and status

- **Current docs**: `*.md` is Chinese, the paired `*.en.md` is English; each file starts with a language switch.
- **Historical docs**: keep the original Chinese, mark them historical/archived, and do not require an English pair.
- **Status labels**: `current`, `draft`, `historical`, `superseded`; new docs must declare one.
- **Single source of truth**: runtime routes come from `gateways/gateway/src/main/resources/application.yml`; tables come from Flyway migrations; this index only navigates and explains.

## 5. Current bilingual coverage

| Level | Chinese | English | Status |
| --- | --- | --- | --- |
| Documentation center | `docs/README.md` | `docs/README.en.md` | ✅ |
| Engineering topology | `docs/architecture/TOPOLOGY.zh.md` | `docs/architecture/TOPOLOGY.en.md` | ✅ |
| Architecture overview | `docs/ARCHITECTURE.md` | `docs/ARCHITECTURE.en.md` | ✅ |
| Documentation audit | `docs/DOCUMENTATION_AUDIT.md` | `docs/DOCUMENTATION_AUDIT.en.md` | ✅ |
| Repository READMEs | each `README.md` | `README.en.md` pending | ⏳ Phase 2 |
| Services/business/standards | mostly Chinese | pending | ⏳ Phase 3 |

## 6. Documentation governance

1. New top-level docs must include both Chinese and English versions with language links.
2. Routes, service names and table names must be traceable to code or migrations.
3. Historical docs are not rewritten as current facts; add a `superseded-by` note when conclusions change.
4. Legacy names (A380, WV Chat, etc.) are allowed only in historical/archived docs; current docs must use OpenChat.
5. Documentation changes must pass link validation; topology, routing or data-ownership changes must update this index and the topology doc.
