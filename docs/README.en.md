# im-saas Documentation Center

[中文](README.md) | [English](README.en.md)

> Scope: im-saas product repositories under `D:\projects\cnb-oss` and the `open-im-server` engineering docs.
> Stage: `develop/2.3.0` in development (latest stage tag `2.2.1`).
> Language: top-level engineering and delivery docs are bilingual; historical audits/renovation stay Chinese-only with archive metadata.

## 1. Structure

```text
docs/
├─ README.md / README.en.md
├─ getting-started/        # five-minute start
├─ product/                # positioning, capabilities, screenshots
├─ architecture/           # topology, overview, ADRs
├─ reference/              # API, protocol, MQ registry, OpenAPI contracts
├─ domain/                 # domain semantics, business rules, feature topics
├─ services/               # service docs: edge / im / platform / common
├─ deployment/             # local, Docker, K8s, push configuration
├─ operations/             # release, upgrade, rollback, operations
├─ development/            # contributor entry
│  ├─ standards/           # enforced engineering standards
│  ├─ governance/          # rules, gates, documentation governance
│  ├─ tooling/             # IDE and developer tooling
│  └─ contributing/        # contribution workflow
├─ security/               # security design, sensitive data, deferred findings
├─ compliance/             # compliance and remediation
├─ assets/                 # screenshots, diagrams, brand assets
└─ archive/                # historical context only
```

## 2. Reading paths

| Goal | Order |
| --- | --- |
| First run | Product → getting started → one-click deployment → accounts |
| Understand the system | Architecture overview → topology → service docs |
| Extend the system | Domain docs → service docs → engineering standards |
| Deploy and operate | Local deployment → Docker/K8s → release runbook |
| Contribute | Contributing → gate registry → DDD conventions |
| Historical context | Archive index → renovation / audits (background only) |

## 3. Index

### Start and product

| Document | Relationship |
| --- | --- |
| [Getting started](getting-started/README.en.md) | Shortest path to a running system |
| [One-click deployment](../README.en.md#one-click-deployment-recommended) | Requirements, Kind deployment, entry points |
| [Product introduction](product/README.en.md) | Positioning, screens, capabilities, audience |

### Architecture and reference

| Document | Relationship |
| --- | --- |
| [Architecture overview](architecture/OVERVIEW.en.md) | Runtime boundaries, ownership, data |
| [Engineering topology](architecture/TOPOLOGY.en.md) | Repo / deployment / service / data |
| [API documentation](reference/API_DOCUMENTATION.md) | Gateway Swagger/OpenAPI entry |
| [Protocol](reference/PROTOCOL.md) | WebSocket events and REST conventions |
| [OpenAPI contracts](reference/contracts/README.md) | External and cross-service contracts |
| [MQ registry](reference/mq/REGISTRY.md) | Topic/event registry |
| [ADR 0007](architecture/adr/0007-version-named-exceptions.md) | Withdrawn version-field decision |

### Domain and services

| Document | Relationship |
| --- | --- |
| [Domain docs](domain/README.en.md) | Conversation, privacy, KTV and order semantics |
| [Service catalog](services/README.en.md) | Runtime services and layers |
| [Edge services](services/edge/README.en.md) | gateway / im-access-ws |
| [IM services](services/im/README.en.md) | user, message, conversation, admin |
| [SaaS platform services](services/platform/README.en.md) | admin BFF, identity, tenant, customer, resource, marketing |
| [Shared capabilities](services/common/README.en.md) | media, audit, payment, payment channel, SMS |

### Delivery and operations

| Document | Relationship |
| --- | --- |
| [Local deployment](deployment/LOCAL.md) | Unified entry for local modes |
| [Docker Compose](deployment/DOCKER.md) | Daily integration |
| [Kubernetes manifests](../k8s/README.md) | Kind / ACK resources and images |
| [Push setup](deployment/PUSH_SETUP.md) | Push channels and environment setup |
| [Release runbook](operations/RELEASE_RUNBOOK.md) | Release, rollback, traceability |

### Contributor docs

| Document | Relationship |
| --- | --- |
| [Contributing](development/contributing/README.en.md) | Pre-commit checks and workflow |
| [Engineering rules](development/governance/ENGINEERING_RULES.md) | Development, review and gates |
| [Gate registry](development/governance/GATE_REGISTRY.md) | Executable checks |
| [IDE setup](development/tooling/IDE_SETUP.md) | VS Code / Trae diagnostics and JDK config |
| [AI collaboration](development/governance/AI_COLLABORATION_PRINCIPLES.md) | Human-AI collaboration boundaries |
| [Documentation organization](development/governance/DOCUMENTATION_ORGANIZATION.en.md) | Classification and archive rules |
| [Standards index](development/standards/README.en.md) | DDD, RocketMQ, media, release, currency, logging, testing |
| [Database migration](development/standards/DATABASE_MIGRATION_STANDARD.en.md) | Flyway and data migration |

### Security and compliance

| Document | Relationship |
| --- | --- |
| [Security](security/) | Sensitive data protection and deferred findings |
| [Compliance](compliance/) | China compliance and P0 remediation |
| [Documentation audit](archive/audits/DOCUMENTATION_AUDIT.en.md) | Historical audit snapshot |

### Archive

| Document | Relationship |
| --- | --- |
| [Archive index](archive/README.en.md) | Registration, metadata and move checklist |
| [renovation](archive/renovation/) | Change history; background only |
| [superpowers](archive/superpowers/) | Design and plan snapshots |

## 4. Language and status

- Current docs: `*.md` Chinese with `*.en.md` English, language switch at the top.
- Historical docs: Chinese-only with archive metadata.
- Status: `current`, `draft`, `historical`, `superseded`.
- Sources of truth: gateway routes in `gateways/gateway/src/main/resources/application.yml`; tables in Flyway migrations.

## 5. Governance

1. New top-level docs need Chinese and English versions with language switches.
2. Routes, service names and tables must map to code or migrations.
3. Historical docs are not rewritten as current facts; add `superseded-by` instead.
4. Legacy names (A380, WV Chat) are archive-only; current docs use OpenChat.
5. Documentation changes must pass link checks; topology/route/ownership changes also update this index.
6. Before committing:

```powershell
.\scripts\validate\validate-documentation-links.ps1
.\scripts\validate\validate-documentation-governance.ps1
.\scripts\validate\validate-standards-governance.ps1
```

CI runs the same checks in `.github/workflows/docs-governance.yml`.