# im-saas Documentation Center

[中文](README.md) | [English](README.en.md)

> Scope: im-saas product repositories under `D:\projects\cnb-oss` and the `open-im-server` engineering docs  
> Current stage: 2.2.1 (2026-10-19)  
> Language policy: top-level engineering and delivery docs are bilingual; historical audit/renovation records stay Chinese-only and are marked as historical.

## 1. Start here

| Document | Purpose |
| --- | --- |
| [One-click deployment](../README.en.md#one-click-deployment-recommended) | Requirements, Kind deployment, entry points and verification |
| [Engineering topology](architecture/TOPOLOGY.en.md) | Repositories, deployment units, services and data layers |
| [Architecture overview](ARCHITECTURE.en.md) | Runtime boundaries, responsibilities and constraints |
| [Documentation audit](DOCUMENTATION_AUDIT.en.md) | Findings, priorities and phased plan |
| [Local deployment](deployment/LOCAL.md) | Kind, Docker Compose and local processes |
| [Engineering rules](ENGINEERING_RULES.md) | Development, review and gates |
| [Release runbook](RELEASE_RUNBOOK.md) | Release, rollback and traceability |
| [Services](services/) | Runtime service responsibilities and APIs |
| [Business](business/) | Conversation, privacy and KTV semantics |
| [Standards](standards/) | DDD, RocketMQ, media, release and currency rules |
| [Renovation](renovation/) | Historical transformation records; not current contracts |

## 2. Language and status

- **Current docs**: `*.md` is Chinese, the paired `*.en.md` is English; each file starts with a language switch.
- **Historical docs**: keep the original Chinese, mark them as historical/archived, and do not require an English pair.
- **Status labels**: `current`, `draft`, `historical`, `superseded`; new docs must declare one.
- **Single source of truth**: runtime routes come from `gateways/gateway/src/main/resources/application.yml`; tables come from Flyway migrations; this index only navigates and explains.

## 3. Current bilingual coverage

| Level | Chinese | English | Status |
| --- | --- | --- | --- |
| Documentation center | `docs/README.md` | `docs/README.en.md` | ✅ |
| Engineering topology | `docs/architecture/TOPOLOGY.zh.md` | `docs/architecture/TOPOLOGY.en.md` | ✅ |
| Architecture overview | `docs/ARCHITECTURE.md` | `docs/ARCHITECTURE.en.md` | ✅ |
| Documentation audit | `docs/DOCUMENTATION_AUDIT.md` | `docs/DOCUMENTATION_AUDIT.en.md` | ✅ |
| Repository READMEs | each `README.md` | `README.en.md` pending | ⏳ Phase 2 |
| Services/business/standards | mostly Chinese | pending | ⏳ Phase 3 |

## 4. Documentation governance

1. New top-level docs must include both Chinese and English versions with language links.
2. Routes, service names and table names must be traceable to code or migrations.
3. Historical docs are not rewritten as current facts; add a `superseded-by` note when conclusions change.
4. Legacy names (A380, WV Chat, etc.) are allowed only in historical/archived docs; current docs must use OpenChat.
5. Documentation changes must pass link validation; topology, routing or data-ownership changes must update this index and the topology doc.
