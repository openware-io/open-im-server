# Gate Catalog

[中文](GATE_CATALOG.md) | [English](GATE_CATALOG.en.md)

> Status: `current` (`develop/2.3.0`).  
> English summary; the Chinese catalog is the complete table. The numeric ledger remains [GATE_REGISTRY.md](GATE_REGISTRY.md).

## Categories

| Category | Concern | Typical owner |
| --- | --- | --- |
| `meta` | gates, standards and documentation governance | governance |
| `code-quality` | encoding, exceptions, logging, script hygiene | backend |
| `architecture` | layers, boundaries, dependencies, modules, routes | architecture |
| `security` | secrets, tenant isolation, sensitive data | security |
| `data` | persistence, migrations, data boundaries | data |
| `domain` | business rules, messaging semantics, currency | domain |
| `quality` | observability, testing, verification inventory | quality |
| `media` | media boundaries and URLs | platform |

## Adding a gate

Follow [Gate Governance](../standards/04_GATE_GOVERNANCE.en.md):

1. Classify category, owner and stage.
2. Implement `scripts/validate/validate-<domain>-<subject>.ps1`.
3. Add the next registry ID.
4. Add the catalog row with docs/evidence.
5. Add the runner entry in the right stage.
6. Define a finite worklist if report-only.
7. Run G33, G51 and G52.

## Full table

See the [Chinese gate catalog](GATE_CATALOG.md) for all G01–G53 rows with owner, stage and evidence.
