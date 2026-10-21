# im-saas Documentation Center

[中文](README.md) | [English](README.en.md)

> Scope: im-saas product repositories under `D:\projects\cnb-oss` and the `open-im-server` engineering docs.
> Stage: `develop/2.3.0` in development (latest stage tag `2.2.1`).
> Language: top-level engineering and delivery docs are bilingual; historical audits/renovation stay Chinese-only with archive metadata.

## 1. Structure

```text
docs/
├─ README.md / README.en.md
├─ 00-getting-started/      # five-minute start
├─ 10-product/              # positioning, capabilities, screenshots
├─ 20-architecture/         # topology, overview, ADRs
├─ 30-reference/            # API, protocol, MQ registry, OpenAPI contracts
├─ 40-domain/               # domain semantics, business rules, feature topics
├─ 50-services/             # service docs: edge / im / platform / common
├─ 60-deployment/           # local, Docker, K8s, push configuration
├─ 70-operations/           # release, upgrade, rollback, operations
├─ 80-development/          # contributor entry
│  ├─ standards/            # enforced engineering standards
│  ├─ governance/           # rules, gates, documentation governance
│  ├─ tooling/              # IDE and developer tooling
│  └─ contributing/         # contribution workflow
├─ 90-security/             # security design, sensitive data, deferred findings
├─ 91-compliance/           # compliance and remediation
├─ assets/                  # screenshots, diagrams, brand assets (unnumbered)
└─ 99-archive/              # historical context only
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
| [Getting started](00-getting-started/README.en.md) | Shortest path to a running system |
| [One-click deployment](../README.en.md#one-click-deployment-recommended) | Requirements, Kind deployment, entry points |
| [Product introduction](10-product/README.en.md) | Positioning, screens, capabilities, audience |

### Architecture and reference

| Document | Relationship |
| --- | --- |
| [Architecture overview](20-architecture/OVERVIEW.en.md) | Runtime boundaries, ownership, data |
| [Engineering topology](20-architecture/TOPOLOGY.en.md) | Repo / deployment / service / data |
| [Architecture diagrams](20-architecture/diagrams/README.en.md) | Eight core diagrams: context, container, deployment, data, contexts, sequence, MQ, state machines |
| [API documentation](30-reference/API_DOCUMENTATION.md) | Gateway Swagger/OpenAPI entry |
| [Protocol](30-reference/PROTOCOL.md) | WebSocket events and REST conventions |
| [OpenAPI contracts](30-reference/contracts/README.md) | External and cross-service contracts |
| [MQ registry](30-reference/mq/REGISTRY.md) | Topic/event registry |
| [ADR index](20-architecture/adr/README.en.md) | Architecture decisions, status and template |
| [ADR 0007](20-architecture/adr/0007-version-named-exceptions.md) | Withdrawn version-field decision |

### Domain and services

| Document | Relationship |
| --- | --- |
| [Domain docs](40-domain/README.en.md) | Conversation, privacy, KTV and order semantics |
| [Service catalog](50-services/README.en.md) | Runtime services and layers |
| [Edge services](50-services/edge/README.en.md) | gateway / im-access-ws |
| [IM services](50-services/im/README.en.md) | user, message, conversation, admin |
| [SaaS platform services](50-services/platform/README.en.md) | admin BFF, identity, tenant, customer, resource, marketing |
| [Shared capabilities](50-services/common/README.en.md) | media, audit, payment, payment channel, SMS |

### Delivery and operations

| Document | Relationship |
| --- | --- |
| [Local deployment](60-deployment/LOCAL.md) | Unified entry for local modes |
| [Docker Compose](60-deployment/DOCKER.md) | Daily integration |
| [Kubernetes manifests](../k8s/README.md) | Kind / ACK resources and images |
| [Push setup](60-deployment/PUSH_SETUP.md) | Push channels and environment setup |
| [Release runbook](70-operations/RELEASE_RUNBOOK.md) | Release, rollback, traceability |

### Contributor docs

| Document | Relationship |
| --- | --- |
| [Contributing](80-development/contributing/README.en.md) | Pre-commit checks and workflow |
| [Engineering rules](80-development/governance/ENGINEERING_RULES.md) | Development, review and gates |
| [Gate registry](80-development/governance/GATE_REGISTRY.md) | Executable checks |
| [IDE setup](80-development/tooling/IDE_SETUP.md) | VS Code / Trae diagnostics and JDK config |
| [AI collaboration](80-development/governance/AI_COLLABORATION_PRINCIPLES.md) | Human-AI collaboration boundaries |
| [Documentation organization](80-development/governance/DOCUMENTATION_ORGANIZATION.en.md) | Classification and archive rules |
| [Standards index](80-development/standards/README.en.md) | DDD, RocketMQ, media, release, currency, logging, testing |
| [Database migration](80-development/standards/23_DATABASE_MIGRATION_STANDARD.en.md) | Flyway and data migration |
| [ADR standard](80-development/standards/02_ARCHITECTURE_DECISION_RECORDS.en.md) | ADR numbering, status, template and gate |

### Security and compliance

| Document | Relationship |
| --- | --- |
| [Security](90-security/) | Sensitive data protection and deferred findings |
| [Compliance](91-compliance/) | China compliance and P0 remediation |
| [Documentation audit](99-archive/audits/DOCUMENTATION_AUDIT.en.md) | Historical audit snapshot |

### Archive

| Document | Relationship |
| --- | --- |
| [Archive index](99-archive/README.en.md) | Registration, metadata and move checklist |
| [renovation](99-archive/renovation/) | Change history; background only |
| [superpowers](99-archive/superpowers/) | Design and plan snapshots |

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