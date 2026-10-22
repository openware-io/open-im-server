# Domain Classification Standard

[中文](05_DOMAIN_CLASSIFICATION.md) | [English](05_DOMAIN_CLASSIFICATION.en.md)

> Status: `current` (`develop/2.4.0-hotel`).  
> Layer: L0 governance.  
> Scope: every bounded context, platform capability and edge application in im-saas.

## Purpose

Define core, supporting, generic and edge; how to classify; dependency direction; registration and review.

## Terms

- **Bounded Context**: a model boundary with its own language and rules.
- **Deployable**: a running process/container that may host one or more contexts.
- **Core**: differentiation, revenue or critical experience.
- **Supporting**: required and reusable, but not differentiating.
- **Generic**: commodity, purchasable/reusable, no business rules.
- **Edge**: BFF, gateway, ingress; owns no domain model.

## Classification test

| Type | Question | Traits |
| --- | --- | --- |
| Core | Does it differentiate the product or create revenue/critical experience? | complex, fast-changing, needs domain experts |
| Supporting | Do multiple businesses need it without differentiating rules? | configurable, platform-owned |
| Generic | Can we buy/reuse it? Does it contain no business rules? | identity protocols, messaging, files, infrastructure |
| Edge | Does it only translate/aggregate/connect? | BFF, gateway, WebSocket ingress |

Rules of thumb:

- if every business changes it, it is probably not supporting;
- if the business cannot run without it but it does not differentiate, it is supporting;
- if it can be bought, it is generic;
- if it only adapts for clients/external systems, it is edge.

## Dependency rules

```text
Core -> Supporting -> Generic
Core -> Generic
Edge -> Core / Supporting
Supporting -X-> Core
Generic -X-> Core / Supporting
Core -X-> another Core's internal model
```

Core may depend on supporting/generic. Supporting must not depend on core. Core-to-core uses published language/events/ACL, never shared tables. Edge owns no domain state.

## Boundaries

- Supporting provides invariants, configuration, extension points and contracts; it must not contain hotel/KTV/restaurant business rules.
- Core must not be swallowed by platform abstractions: hotel capacity, KTV slots, restaurant tables and gym classes are core.
- Similarity is reused as technical components, not shared domain models.

## Adding or changing a domain

1. Update [DOMAIN_MAP](../../40-domain/DOMAIN_MAP.en.md).
2. Record type, owner, implementation module, dependencies and consumers.
3. A core domain requires an ADR.
4. Pass G53 `domain-map`.
5. Update service README and architecture diagrams.

## Ownership

| Type | Owner |
| --- | --- |
| Core | business product owner + domain tech lead |
| Supporting | platform team owner |
| Generic | infrastructure/platform owner |
| Edge | frontend/ingress team |

## Gate

**G53 domain-map**:

- every service directory appears in the map;
- every context has a valid type, owner and module;
- core contexts reference an ADR;
- type is one of `core/supporting/generic/edge`.

G18/G24/G25/G32/G43 continue to enforce code, port, PO, tenant SQL and layout boundaries.

## Acceptance

- every new service/module has a context and type;
- core count stays controlled;
- supporting has no business rules;
- dependencies follow Core -> Supporting -> Generic;
- core-to-core uses ACL/events, no shared tables;
- G53 passes.

## Related

- [Engineering principles](03_ENGINEERING_PRINCIPLES.en.md)
- [Gate governance](04_GATE_GOVERNANCE.en.md)
- [DDD service conventions](10_DDD_SERVICE_ENGINEERING_CONVENTIONS.en.md)
- [Domain map](../../40-domain/DOMAIN_MAP.en.md)
- [Inventory platform](../../40-domain/inventory/README.en.md)
- [Order platform](../../40-domain/order/README.en.md)
