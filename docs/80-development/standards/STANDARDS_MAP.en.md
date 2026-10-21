# Standards Map

[中文](STANDARDS_MAP.md) | [English](STANDARDS_MAP.en.md)

> Status: `current` (`develop/2.3.0`).  
> Role: the **overall map** of the standards system, not another normative standard.  
> Principle: standards are not files appended over time; they form an assurance stack: principles → domain standards → practices → gates/evidence.

## 1. Two axes

The map is a matrix, not a tree:

```text
Vertical: assurance layer
  L0 principles & governance    why, and how to trade off conflicts
  L1 domain standards           what a domain must satisfy
  L2 practice standards         how to apply it on a specific stack
  L3 gates & evidence           how we prove it

Horizontal: engineering concern
  foundation / architecture / build / data / delivery / quality / security / collaboration / domain
```

Numbers are a **classification index**, not a dependency graph. Dependencies are expressed by supporting principles and gate evidence.

## 2. Standards map

| ID | Standard | Layer | Domain | Gates / evidence | Maturity |
| --- | --- | --- | --- | --- | --- |
| 00 | [DDD microservice outline](00_DDD_MICROSERVICE_SOFTWARE_ENGINEERING_OUTLINE.en.md) | L0 | foundation | G48, G49 | active |
| 01 | [Text encoding and batch editing](01_TEXT_ENCODING_AND_BATCH_EDITING.en.md) | L2 | collaboration | G01, G29 | active |
| 02 | [Architecture Decision Records](02_ARCHITECTURE_DECISION_RECORDS.en.md) | L0 | governance | G47 | active |
| 03 | [Engineering principles](03_ENGINEERING_PRINCIPLES.en.md) | L0 | foundation | G48, G49 | active |
| 04 | [Gate governance](04_GATE_GOVERNANCE.en.md) | L0 | governance | G33, G48, G49, G51, G52 | active |
| 10 | [DDD service engineering](10_DDD_SERVICE_ENGINEERING_CONVENTIONS.en.md) | L1 | architecture | G18, G19, G20, G21, G24, G25, G43 | active |
| 12 | [RocketMQ governance](12_ROCKETMQ_CONVENTIONS.en.md) | L2 | messaging | G14, G15, G42 | active |
| 13 | [Platform media](13_PLATFORM_MEDIA_CONVENTIONS.en.md) | L2 | media | G36, G40 | active |
| 20 | [Maven engineering](20_MAVEN_ENGINEERING_CONVENTIONS.en.md) | L1 | build | G11, G26 | active |
| 21 | [Persistence stack](21_PERSISTENCE_STACK.en.md) | L1 | data | G12, G24, G25, G27 | active |
| 22 | [Technology stack matrix](22_TECHNOLOGY_STACK_VERSION_MATRIX.en.md) | L1 | build | G11 (partial) | active |
| 23 | [Database migration](23_DATABASE_MIGRATION_STANDARD.en.md) | L2 | data | G27, G28 | active |
| 24 | [Currency conventions](24_CURRENCY_CONVENTIONS.en.md) | L1 | domain | G13, G34, G35 | active |
| 30 | [Client release governance](30_CLIENT_RELEASE_GOVERNANCE.en.md) | L1 | delivery | manual release check | active + manual |
| 31 | [Client release operations](31_CLIENT_RELEASE_SKILL.en.md) | L2 | delivery | manual release check | active + manual |
| 40 | [Observability](40_OBSERVABILITY_CONVENTIONS.en.md) | L1 | quality | G37, G38, G41 | active |
| 41 | [Java logging](41_JAVA_LOGGING_CONVENTIONS.en.md) | L2 | quality | G06, G07, G08, G37 | active |
| 42 | [Logging outputs](42_LOGGING_AND_OUTPUTS.en.md) | L2 | quality | G07, G37 | active |
| 43 | [E2E testing](43_E2E_TESTING_CONVENTIONS.en.md) | L2 | quality | G39 | active |

## 3. Coverage matrix

| Concern | L1 domain standards | L2 practices | Status |
| --- | --- | --- | --- |
| foundation | 00 | 03, 04 | ✅ |
| architecture | 10 | 12, 13 | ✅ |
| build | 20, 22 | — | ✅ |
| data | 21, 24 | 23 | ✅ |
| delivery | 30 | 31 | ⚠️ manual checks only |
| quality | 40 | 41, 42, 43 | ✅ |
| security | **missing** | only G16/G17 gates | ❌ gap |
| collaboration | — | 01, 02 | ⚠️ docs gates, no contribution standard |
| domain | 24 | — | ⚠️ currency only; other rules live in `40-domain/` |

## 4. Gaps

These are **uncovered cells**, not merely unwritten documents:

1. **Security standard**: G16/G17 cover secrets/config, but there is no threat model, authn/authz, key rotation or supply-chain standard.
2. **Privacy/data lifecycle**: classification, retention, deletion/export, cross-border transfer.
3. **Performance/capacity**: SLOs, latency budgets, load testing, capacity planning.
4. **API compatibility/deprecation**: versioning, compatibility windows, deprecation process.
5. **Reliability/DR**: RTO/RPO, backup/restore, failure drills.
6. **Supply chain/licensing**: third-party dependencies, licenses, SBOM.
7. **Client release gates**: 30/31 rely on manual checks, without automated evidence.
8. **Contribution/community**: ownership, review SLA, contribution workflow.
9. **i18n/accessibility**: multilingual and multi-client requirements.

## 5. Adding a standard

1. Locate the cell: choose layer (L0/L1/L2) and concern.
2. Name the supporting principles (P1–P10 in `03`).
3. Define a gate or evidence: automate where possible; otherwise specify manual evidence.
4. Register in this map, `standards/README.md` and the root README.
5. Record remaining gaps instead of pretending completeness.
6. Run G48/G49.

## 6. Gates

- **G48 standards-catalog**: numbering, English summaries and index integrity.
- **G49 standards-map**: every standard appears in the map; the map cannot reference missing standards; each row has layer, domain and gate/evidence.
- Domain gates: [gate registry](../governance/GATE_REGISTRY.md).

## 7. Related

- [Engineering principles](03_ENGINEERING_PRINCIPLES.en.md): L0 basis.
- [Standards index](README.en.md): browse by classification block.
- [Engineering rules](../governance/ENGINEERING_RULES.md): process and ownership.
- [Gate registry](../governance/GATE_REGISTRY.md): L3 implementation.
- [ADR index](../../20-architecture/adr/README.en.md): decision history for principle conflicts.
