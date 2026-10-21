# Engineering Principles

[中文](03_ENGINEERING_PRINCIPLES.md) | [English](03_ENGINEERING_PRINCIPLES.en.md)

> Status: `current` (`develop/2.3.0`).  
> Layer: L0 principles.  
> Purpose: give L1 domain standards and L2 practice standards a stable basis for trade-offs.

## Scope

These principles guide architecture, development, testing, delivery and operations when a concrete standard does not cover a case, or when standards conflict.

## Terms

- **Principle**: a stable judgement basis, independent of tools.
- **Standard**: an executable constraint in a domain.
- **Practice**: how a standard is applied to a specific stack.
- **Gate**: automated evidence for a standard.
- **Exception**: a bounded, owned, expiring deviation.

## Principles

1. **Correctness before speed**: no lost/duplicated data, no privilege escalation, no misleading state.
2. **Explicit boundaries, single ownership**: one owner per service/table/topic; cross-boundary only via API, event or contract.
3. **Single source of truth**: every fact has one authority; projections and caches are rebuildable.
4. **Automation over memory**: rules that can be gates become gates; gates block.
5. **Design for failure**: timeouts, retries, idempotency, degradation, DLQ and rollback.
6. **Secure and private by default**: least privilege, deny by default, data minimisation.
7. **Observable by default**: logs, metrics, traces and correlation on every critical path.
8. **Compatibility and evolution**: version breaking changes and provide migration/deprecation windows.
9. **Simplicity and least mechanism**: justify every new middleware, abstraction or process.
10. **Documentation is part of the product**: discoverable, copyable, verifiable docs with link checks.

## Gate

- G48 standards catalog: numbering, English summary and index.
- G49 standards map: every standard maps to layer, domain, gate and gap.
- Domain gates: see the [gate registry](../governance/GATE_REGISTRY.md).

## Acceptance

- A new standard names the principles it supports.
- Principle conflicts have an ADR or written trade-off.
- The standards map has no orphan standards.
- Exceptions have owner, expiry and exit criteria.

## Related

- [Standards map](STANDARDS_MAP.en.md)
- [DDD conventions](10_DDD_SERVICE_ENGINEERING_CONVENTIONS.en.md)
- [ADR standard](02_ARCHITECTURE_DECISION_RECORDS.en.md)
- [Engineering rules](../governance/ENGINEERING_RULES.md)
