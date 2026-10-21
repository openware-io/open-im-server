# Architecture Diagrams

[中文](README.md) | [English](README.en.md)

> Status: `current` (`develop/2.3.0`).  
> Principle: one diagram answers one question; each diagram must map to code, K8s manifests, Flyway or the MQ registry.

## Index

| ID | Diagram | View | Audience |
| --- | --- | --- | --- |
| 01 | [System context](01-system-context.en.md) | C4 Context | Product, architecture, first-time readers |
| 02 | [Container](02-container.en.md) | C4 Container | Backend, frontend, QA |
| 03 | [Deployment](03-deployment.en.md) | Deployment | Ops, SRE, release owners |
| 04 | [Data ownership](04-data-ownership.en.md) | Data Ownership | Backend, DBA, domain owners |
| 05 | [Bounded contexts](05-context-map.en.md) | DDD Context Map | Architecture, domain owners |
| 06 | [Message send sequence](06-message-send-sequence.en.md) | Sequence | IM backend, clients, QA |
| 07 | [MQ/event topology](07-mq-topology.en.md) | Event Flow | IM/SaaS backend, ops |
| 08 | [Order/reservation state machines](08-order-reservation-state.en.md) | State Machine | Business, backend, QA |

## Conventions

- Solid: synchronous call or explicit write.
- Dotted: async event, projection or read-only dependency.
- Arrow direction: caller → callee; event diagrams go producer → topic → consumer.
- Ports follow `k8s/local/applications.yaml` and the running Services.
- Table names are prefixes; exact schema follows each service's Flyway and domain model.
- Diagrams are Mermaid sources, reviewable in Git; no opaque screenshots.

## Visual rules

Diagrams use **C4 semantics plus controlled Mermaid layout**:

| Type | Technique | Reason |
| --- | --- | --- |
| 01 Context | actors / boundary / external systems | boundary only; no internal services |
| 02 Container | grouped clients, edge, IM, platform, shared, data | Mermaid C4 overlaps labels with many nodes; grouped flowchart is stable |
| 03 Deployment | namespace / ingress / pod groups / infrastructure | must map to K8s manifests |
| 04 Data ownership | service groups → stores, dotted cross-domain links | owner first, cross-domain references second |
| 05 Contexts | domain boundaries + dependency direction | no tables or classes |
| 06 Sequence | `sequenceDiagram` | call order, idempotency and retries |
| 07 Events | producer → topic family → consumer | grouped by family; exact registry linked |
| 08 States | `stateDiagram-v2` | explicit terminal and failure states |

Palette:

```text
grey    clients
blue    edge
green   IM domain
amber   SaaS platform
red     shared capabilities
purple  data and messaging
```

Use at most six primary colors; color expresses layer, not importance.

## Sources of truth

- Services and ports: [k8s/local/applications.yaml](../../../k8s/local/applications.yaml)
- Routes: [gateway application.yml](../../../gateways/gateway/src/main/resources/application.yml)
- MQ: [MQ registry](../../30-reference/mq/REGISTRY.md)
- Data: [engineering topology](../TOPOLOGY.en.md) and service Flyway
- Order/reservation states: `platform-order-service` domain models

## Related

- [Architecture overview](../OVERVIEW.en.md)
- [Engineering topology](../TOPOLOGY.en.md)
- [Service catalog](../../50-services/README.en.md)
