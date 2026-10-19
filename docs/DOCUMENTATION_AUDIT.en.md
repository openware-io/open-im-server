# im-saas Documentation Audit (2026-10-19)

[中文](DOCUMENTATION_AUDIT.md) | [English](DOCUMENTATION_AUDIT.en.md)

> Status: `current`  
> Version: `develop/2.3.0` documentation remediation (baseline tag `2.2.1`)  
> Method: checked 249 Markdown files in the workspace for naming, version, links, service inventory and language coverage; cross-checked the running Kind deployment for the live service inventory.

## 1. Conclusion

The problem is not a lack of files. It is the absence of a single engineering-level structure:

- repository READMEs, service docs, business designs and transformation records evolved independently;
- the architecture doc covered only the IM domain, not SaaS platform, shared capabilities, clients or infrastructure;
- current docs previously mixed legacy A380 / WV Chat names;
- docs were Chinese-only with no explicit bilingual boundary;
- versions, routes and service inventories drifted.

This change establishes a documentation center, an engineering topology, this audit and the top-level bilingual set. The rest is scheduled in Phase 2/3.

## 1.1 Progress (2026-10-19)

| Phase | Status | Result |
| --- | --- | --- |
| Phase 0 global plan | ✅ | Classification, archival rules, target layout and execution order |
| Phase 1 manifest | ✅ | 214-row per-file manifest across the 8 im-saas repositories |
| Phase 2 archive | ✅ | 94 historical docs archived: 78 renovation, 11 superpowers, audits/inventories/old release notes |
| Phase 3 merge/update | 🟡 | 10 `im-services` docs merged into the service tree; entries/ports/service names still being updated |
| Phase 4 bilingual/additions | 🟡 | English READMEs for 7 repos; English indexes for services/business/standards; per-doc translation pending |
| Phase 5 cleanup/gates | 🟡 | Documentation governance gate added; deletion only under the five-condition gate |

Remaining work: per-doc service/standard/business updates, older repo guides, and conclusion extraction from a few historical docs.

## 2. Findings

### P0: missing topology hierarchy

1. `docs/ARCHITECTURE.md` lists only `gateway`, four IM services and `im-access-ws`; it omits `platform-*`, `common-*`, `group-idaas`, SaaS H5/admin and clients.
2. The same doc still claims only `im-user-service` / `im-message-service` own Flyway migrations; tenant, identity, admin, customer, resource, order, payment, media and audit also own schemas now.
3. The root README topology omits the SaaS platform, shared capabilities, data infrastructure and deployment-unit hierarchy.
4. The root README still lists `im-order-service`, which does not exist in the repository or Kind; order capability lives in `platform-order-service`.

### P0: bilingual gap

5. Before this change there were no top-level `*.en.md` engineering docs; Chinese dominated all 241 Markdown files.
6. None of the 8 product repositories had an English README.
7. Service, business and standards docs had no language-switch convention.

### P1: naming and version drift

8. `A380` appears 257 times across 50 docs, mainly under `docs/archive/renovation/`, historical audits and some current deployment docs.
9. `WV Chat` remains in 9 docs (15 occurrences), including the desktop README, release notes and release runbook artifact names.
10. 46 references still say `2.2.0`, which no longer matches the `2.2.1` stage tag.
11. The current service-board doc uses OpenChat, but historical renovation docs keep the legacy name and must be marked historical rather than rewritten.

### P1: route, service and data drift

12. The root README route table omits `/miniapp/services`, `/business/**` and public `platform-*` routes.
13. Service-board data is documented only in a renovation doc, not in the architecture/service index.
14. The data-ownership table lacks complete boundaries for `open_saas`, `open_group` and `open_audit`.
15. Ports, deployment units and repositories are spread across docs; there is no single topology source.

### P2: organization and maintainability

16. `open-im-server/docs` contains 165 Markdown files; historical audits, renovation and superpowers plans sit beside current standards.
17. There is no documentation center; the root README index is a flat list that cannot express hierarchy.
18. Historical docs have no `historical` / `superseded-by` metadata.
19. There is no CI gate for bilingual pairs, legacy naming or broken links.

## 3. Phased plan

### Phase 1 (completed, 2026-10-19)

- Add `docs/README.md` / `.en.md` documentation center;
- Add `docs/architecture/TOPOLOGY.zh.md` / `.en.md` layered topology;
- Add this audit and its English version;
- Add an English counterpart and language switch to `docs/ARCHITECTURE.md`;
- Add an English counterpart and language switch to the root README, and correct obvious drift such as `im-order-service`.

### Phase 2

- Add `README.en.md` to all 8 product repositories;
- Prioritize English summaries for `docs/services/**`;
- Standardize service docs as responsibility / API / data / dependencies / runtime entry.

### Phase 3

- Add bilingual versions for `docs/standards/**`, `docs/deployment/**`, `docs/business/**` by priority;
- Recheck legacy names, ports and Ingress paths in deployment docs;
- Sync recent changes (service board, wallet brand, B-end entry) into current docs.

### Phase 4

- Mark historical audits, renovation and superpowers plans as `historical`;
- Add `superseded-by` to obsolete conclusions;
- Restrict `A380` / `WV Chat` to historical docs;
- Establish an archive index/directory.

### Phase 5

- Add documentation gates:
  - top-level docs must have a `.md` / `.en.md` pair;
  - language switches must be bidirectional;
  - current docs must not contain A380 / WV Chat;
  - route, service and table references must resolve to code or migrations.

## 4. Acceptance criteria

- A new reader can reach topology, deployment, services, standards and audit from the root README in three hops;
- the topology answers repository → deployment unit → service → data;
- top-level docs switch between Chinese and English;
- current docs contain no legacy brand names;
- historical docs are explicitly marked and no longer mistaken for current contracts;
- documentation gates can run in CI.
