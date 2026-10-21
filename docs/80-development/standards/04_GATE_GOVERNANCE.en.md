# Gate Governance

[中文](04_GATE_GOVERNANCE.md) | [English](04_GATE_GOVERNANCE.en.md)

> Status: `current` (`develop/2.3.0`).  
> Layer: L0 governance.  
> Scope: every gate, gate script, registry, catalog, runner and evidence.

## Scope

Defines how gates are added, classified, placed, ordered, reviewed, downgraded and retired.

## Terms

- **Gate**: an automated check with a deterministic failure condition.
- **ID**: `G01`, `G02`, … assigned by registration order; it does not encode category.
- **Category**: governance concern such as `architecture`, `security`, `data`.
- **Owner**: role accountable for the rule and false positives.
- **Stage**: `static`, `unit`, `integration`, `docs`, `release`.
- **Retired**: rule no longer applies; ID is retained forever.

## Rules

1. One gate answers one question; name it `domain-subject`.
2. IDs are registration order and are never reused; category lives in the catalog, not the ID.
3. Categories: `meta`, `code-quality`, `architecture`, `security`, `data`, `domain`, `quality`, `media`, `delivery`.
4. Script path: `scripts/validate/validate-<domain>-<subject>.ps1`, ASCII-only.
5. Register in four places: registry, catalog, runner and related docs. Missing one fails G33/G51.
6. Execution order: static → unit → integration → docs → release.
7. `report-only` requires a finite worklist, owner, expiry and exit condition; no permanent report-only.
8. A new gate names its supporting principle (P1–P10), owner, stage, failure message, false-positive strategy and baseline.
9. Retirement keeps the ID, marks `retired` in the catalog, removes the runner entry and archives the script.

## Checklist

```text
Gate: G53
Name: contract-compatibility
Category: architecture
Owner: architecture
Stage: static
Why: prevent breaking changes to published APIs
Principle: P8 compatibility
Script: scripts/validate/validate-contract-compatibility.ps1
Blocking: yes
Baseline: 0
Docs: docs/80-development/standards/44_API_COMPATIBILITY.md
Registry / Catalog / Runner updated
```

## Gates

- **G33** registry consistency.
- **G51** catalog coverage and metadata.
- **G52** docs impact for high-risk code changes.
- **G50** current-doc reachability.

## Acceptance

- Unique ID, category, owner, stage and doc entry.
- Actionable failure message.
- No permanent report-only.
- G33/G51 pass.
- High-risk code changes carry doc evidence through G52.

## Related

- [Engineering principles](03_ENGINEERING_PRINCIPLES.en.md)
- [Standards map](STANDARDS_MAP.en.md)
- [Gate registry](../governance/GATE_REGISTRY.md)
- [Gate catalog](../governance/GATE_CATALOG.md)
- [Engineering rules](../governance/ENGINEERING_RULES.md)
