# im-saas Documentation Organization and Archival Plan

[中文](DOCUMENTATION_ORGANIZATION.md) | [English](DOCUMENTATION_ORGANIZATION.en.md)

> Status: `current` (global plan, baseline before execution)  
> Version: `develop/2.3.0`  
> Principle: classify globally and define archival rules first; edit files one by one afterwards. Archiving is not deletion: every move must be traceable and linkable.

## 1. Goals

Turn the current 249 Markdown files into a maintainable documentation system:

1. **One authoritative document per topic**, instead of README, service docs and renovation each owning a copy.
2. **Separate current from historical** so history does not pollute current contracts.
3. **Bilingual top-level docs, then service/standards docs**.
4. **Align the directory hierarchy with the engineering topology**: getting started → architecture → deployment → services → business/standards → history.
5. **Every move has a manifest, links and validation**.

## 2. Classification model

### 2.1 Lifecycle

| Status | Meaning | Handling |
| --- | --- | --- |
| `current` | Valid now; code/deployment/process still depends on it | Keep and update |
| `draft` | Being written, not yet a contract | Keep, assign owner and deadline |
| `historical` | Completed or replaced; background only | Archive, preserve original |
| `superseded` | A named replacement exists | Archive, add `superseded-by` |

### 2.2 Disposition actions

| Action | Definition | Example |
| --- | --- | --- |
| **KEEP** | Correct content, correct location | Engineering topology, architecture overview |
| **UPDATE** | Correct location, stale/incomplete content | README entries, deployment docs, service docs |
| **MERGE** | Multiple docs cover the same topic; consolidate into the authoritative one | `im-services/**` duplicates the service tree |
| **ARCHIVE** | Keep the original, move to `docs/archive/`, add historical metadata | renovation, superpowers, old audits |
| **DELETE** | No independent content, no inbound links, no historical value; verify first | empty shells, generated files, duplicate indexes |
| **ADD** | Missing from the current system | English repo READMEs, archive index, service contract template |

## 3. Current inventory

Snapshot: 2026-10-19, `develop/2.3.0` baseline.

| Area | Count | Main issue |
| --- | ---: | --- |
| `docs/` top level | 18 | Current standards mixed with one-off audits |
| `docs/architecture/` | 2 | New topology; documentation governance missing |
| `docs/adr/` | 1 | Withdrawn ADR still in the current ADR directory |
| `docs/business/` | 5 | Overlaps business designs in renovation |
| `docs/compliance/` | 2 | Needs explicit relation to security/privacy docs |
| `docs/contracts/` | 2 | Snapshot and OpenAPI notes are thin |
| `docs/deployment/` | 7 | Stale ports, environment and release values |
| `docs/features/` | 3 | Confirm which features are still current |
| `docs/im-services/` | 10 | Overlaps the service tree; named `im-` but contains SaaS services |
| `docs/mq/` | 1 | Merge into messaging/standards or keep as a focused topic |
| `docs/archive/renovation/` | 78 | Large historical record; some conclusions remain valid |
| `docs/security/` | 2 | Needs relation to compliance and auth docs |
| `docs/services/` | 8 | Incomplete tree; platform/shared services missing |
| `docs/standards/` | 15 | Current standards; bilingual work pending |
| `docs/superpowers/` | 11 | Historical design and plan snapshots |
| Other product repositories | 84 | No English README; some release notes stale |

## 4. Global disposition matrix

### 4.1 Keep and update (KEEP / UPDATE)

| Document/directory | Action | Target |
| --- | --- | --- |
| Root `README.md` / `README.en.md` | UPDATE | One-click deployment, entries, accounts and topology stay current |
| `docs/README.md` / `.en.md` | UPDATE | Documentation center and hierarchical index |
| `docs/ARCHITECTURE.md` / `.en.md` | UPDATE | Runtime boundaries and layered responsibilities |
| `docs/architecture/TOPOLOGY.*` | KEEP/UPDATE | Single source for engineering topology |
| `docs/ENGINEERING_RULES.md` | KEEP | Engineering rules |
| `docs/GATE_REGISTRY.md` | KEEP/UPDATE | Gates and checks |
| `docs/RELEASE_RUNBOOK.md` | UPDATE | Release, rollback and traceability |
| `docs/PROTOCOL.md` | KEEP | WebSocket/protocol contract |
| `docs/API_DOCUMENTATION.md` | UPDATE | Gateway Swagger/OpenAPI entry |
| `docs/PUSH_SETUP.md`, `docs/IDE_SETUP.md` | UPDATE | Development environment and push setup |
| `docs/DATABASE_MIGRATION_STANDARD.md` | KEEP | Migration standard |
| `docs/standards/**` | UPDATE | Current standards; bilingual by priority |
| `docs/business/**` | UPDATE | Business semantics; absorb still-valid renovation conclusions |
| `docs/deployment/**` | UPDATE | Unified 30080 entry, environment matrix, release steps |
| `docs/services/**` | UPDATE/ADD | Unified service tree; add platform/shared services |
| `docs/contracts/**`, `docs/security/**`, `docs/compliance/**` | UPDATE | Current contracts, security and compliance |
| `docs/features/**` | UPDATE | Keep current features; archive obsolete ones |
| Product repo `README.md` | UPDATE/ADD | Add `README.en.md`; align quick start |

### 4.2 Merge (MERGE)

| Source | Target | Rule |
| --- | --- | --- |
| `docs/im-services/**` (10) | `docs/services/<service>/` | Compare first; keep the authoritative description, archive the old copy |
| `docs/deployment/LOCAL.md` and Docker/K8s docs | Single deployment entry | Quick start stays in README; details stay in deployment docs |
| `docs/architecture/ARCHITECTURE.md` and topology | Architecture overview + engineering topology | Topology covers layers; architecture covers runtime boundaries |
| `docs/business/**` and KTV/wallet/privacy plans in renovation | `docs/business/` + `docs/services/` | Extract current conclusions, then archive originals |
| Old route/service lists in root README | Gateway config + service directory | `application.yml` is authoritative for routes |

### 4.3 Archive (ARCHIVE)

| Directory/document | Reason | Target |
| --- | --- | --- |
| `docs/archive/renovation/**` (78) | Transformation history and historical decisions; some conclusions still needed | `docs/archive/renovation/` |
| `docs/superpowers/**` (11) | Historical plans and design snapshots | `docs/archive/superpowers/` |
| `docs/audit-2026-09-17-exceptions-and-i18n.md` | One-off audit snapshot | `docs/archive/audits/2026-09-17/` |
| `docs/currency-scope-inventory-2026-09-18.md` | One-off inventory | `docs/archive/inventories/2026-09-18/` |
| `docs/DATABASE_AUDIT_REPORT.md` | Historical database audit | `docs/archive/audits/` (current conclusions into `standards/21` or deployment docs) |
| `docs/adr/0007-version-named-exceptions.md` | Withdrawn ADR | Keep in `docs/adr/`, mark `superseded`, do not move |
| Old release notes / one-off acceptance records | Valid only for that release | `docs/archive/release-notes/` |

Before archiving, perform **conclusion extraction**: write still-valid fields, APIs and business rules into current `business/`, `services/` or `standards/`. Never treat a historical design as a current contract.

### 4.4 Cleanup/delete (DELETE)

A file may be deleted only when all conditions hold:

1. No independent content (empty shell, pure link, duplicate index).
2. No inbound links (confirmed by `git grep` and the link validator).
3. No historical value (not a decision record, audit evidence or migration basis).
4. An authoritative replacement exists and the archive index has recorded it.

Recheck candidates:

- `docs/contracts/README.md`: not an empty shell; keep and expand the contract index.
- `docs/API_DOCUMENTATION.md`: keep and update as the gateway Swagger note.
- `docs/services/im-user-service/README.md`: keep and complete responsibility/API/data sections.
- `docs/adr/0007-version-named-exceptions.md`: keep as a `superseded` ADR; do not delete.
- Old release/acceptance records in product repos: archive first, do not delete directly.

### 4.5 Add (ADD)

| New document | Purpose | Priority |
| --- | --- | --- |
| `docs/DOCUMENTATION_ORGANIZATION.md` / `.en.md` | Global organization and archival plan (this doc) | P0 |
| `docs/archive/README.md` / `.en.md` | Archive index and metadata rules | P0 |
| `README.en.md` in 7 product repositories | Bilingual repository entry | P1 |
| Service template and platform/shared service docs | Unified service tree | P1 |
| `docs/deployment/ENVIRONMENTS.md` | Environment matrix (Kind/Compose/ACK/production) | P1 |
| `docs/architecture/DECISIONS.md` | ADR index and supersession map | P2 |
| Metadata standard and CI gates | Status, bilingual pairing, legacy-name/link checks | P2 |

## 5. Target directory structure

```text
docs/
├─ README.md / README.en.md
├─ 00-getting-started/
├─ 10-product/
├─ 20-architecture/
│  ├─ OVERVIEW.md / .en.md
│  ├─ TOPOLOGY.zh.md / TOPOLOGY.en.md
│  └─ adr/
├─ 30-reference/
│  ├─ API_DOCUMENTATION.md
│  ├─ PROTOCOL.md
│  ├─ mq/REGISTRY.md
│  └─ contracts/
├─ 40-domain/
│  ├─ README.md / README.en.md
│  └─ features/
├─ 50-services/
│  ├─ README.md / README.en.md
│  ├─ edge/
│  ├─ im/
│  ├─ platform/
│  └─ common/
├─ 60-deployment/
├─ 70-operations/
├─ 80-development/
│  ├─ standards/
│  ├─ governance/
│  ├─ tooling/
│  └─ contributing/
├─ 90-security/ + 91-compliance/
├─ assets/ (support assets, unnumbered)
└─ 99-archive/
   ├─ README.md / README.en.md
   ├─ audits/
   ├─ inventories/
   ├─ renovation/
   ├─ superpowers/
   └─ release-notes/
```

The top level keeps only the documentation entry point; architecture, reference, domain, services, delivery, development/governance, security/compliance and history each have their own directory. Historical directories no longer sit beside current contracts.

The top level keeps only entry points, architecture and documentation-governance notes; details live in their topic directories. Historical directories no longer sit beside current contracts.

Only entry, architecture and governance docs remain at the top level. Details move into their domain directories. Historical directories no longer sit beside current ones.

## 6. Archival rules

### 6.1 Metadata header

Every archived document starts with:

```markdown
> Status: historical
> Archived: 2026-10-19
> Superseded-by: <relative path to current doc, may be empty>
> Original-location: <old path>
```

ADRs use `Status: superseded`; keep the original decision and do not rewrite it.

### 6.2 Naming and links

- Keep original file names to avoid breaking historical references.
- Always use `git mv` to preserve history.
- After archiving, leave a **redirect note** (at most three lines) at high-traffic old paths.
- Update all inbound links and run `validate-documentation-links.ps1`.
- Register every item in the archive index: old path, new path, status, replacement.

### 6.3 Deletion gate

```text
no independent content + no inbound links + no historical value + authoritative replacement + archive-indexed
```

Delete only when all five hold; otherwise archive.

## 7. Execution order

### Phase 0: Global plan (this document)

- Define classification, archival rules and target structure.
- Do not edit individual documents yet.

### Phase 1: Archive scaffold and manifests

- Create `docs/archive/` index.
- Generate a per-file disposition manifest (CSV/Markdown): path, action, target, reason, owner.
- Create a **conclusion-extraction list** for `renovation`, `superpowers` and old audits.
- Do not move files yet.

### Phase 2: Historical archive

- `git mv` into archive in batches.
- Add metadata headers.
- Update indexes and inbound links.
- Run link validation after each batch.

### Phase 3: Merge/update current docs

- Merge `im-services` into `services`.
- Unify deployment entry and environment matrix.
- Update routes, versions, brand and account docs.
- Complete the service tree.

### Phase 4: Bilingual and additions

- Add `README.en.md` to 7 repos.
- Translate service/standards/business docs by priority.
- Add environment matrix, ADR index and contract index.

### Phase 5: Cleanup and gates

- Delete only files that pass all five conditions.
- Add CI checks for metadata, bilingual pairs, legacy names and links.
- Make archive and current indexes mutually reachable.

## 8. Acceptance criteria

- Top-level directories no longer mix historical audits/renovation.
- One authoritative description per service.
- Every historical doc has `Status: historical` and source/replacement relations.
- Current docs contain no A380/WV Chat; old ports/URLs are not presented as entries.
- Top-level docs switch between Chinese and English.
- Link validation and standards index validation pass.
- Any current topic is reachable from README in three hops.

## 9. Non-goals

- Do not rewrite all 249 docs at once.
- Do not delete historical decisions or audit evidence.
- Do not rewrite historical docs to look current.
- Do not move files in bulk without a per-file manifest.
