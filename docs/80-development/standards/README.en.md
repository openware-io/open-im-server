# Engineering Standards Index

[中文](README.md) | [English](README.en.md)

> Status: `current` (`develop/2.3.0`).
> Chinese is normative; this index provides English summaries. New standards must be registered here and in the root README.
> Layers, domains, gates and gaps: [standards map](STANDARDS_MAP.en.md).

## Numbering

Standards use **category block + two-digit sequence**, not append-by-time:

```text
00–09  Foundations and governance
10–19  Architecture and service design
20–29  Build, data and versions
30–39  Delivery and clients
40–49  Quality, observability and testing
50–59  Security and compliance (reserved)
60–69  Operations (reserved)
```

- Numbers increase within a block and are never reused;
- choose the block first, then the next sequence;
- unnumbered standards are not allowed;
- published numbers are not renumbered casually; a change must update links and a migration note.

## 00–09 Foundations and governance

| ID | Standard | Scope |
| --- | --- | --- |
| 00 | [DDD microservice engineering outline](00_DDD_MICROSERVICE_SOFTWARE_ENGINEERING_OUTLINE.en.md) | Service boundaries, contracts and governance |
| 01 | [Text encoding and batch editing](01_TEXT_ENCODING_AND_BATCH_EDITING.en.md) | UTF-8, line endings, batch edits and validation |
| 02 | [Architecture Decision Records](02_ARCHITECTURE_DECISION_RECORDS.en.md) | ADR numbering, status, template, index and gate |
| 03 | [Engineering principles](03_ENGINEERING_PRINCIPLES.en.md) | Correctness, boundaries, single source of truth, automation and evolution |

## 10–19 Architecture and service design

| ID | Standard | Scope |
| --- | --- | --- |
| 10 | [DDD service engineering](10_DDD_SERVICE_ENGINEERING_CONVENTIONS.en.md) | Layers, packages, naming and domain model |
| 12 | [RocketMQ governance](12_ROCKETMQ_CONVENTIONS.en.md) | Topics, keys, idempotency, retries and DLQ |
| 13 | [Platform media](13_PLATFORM_MEDIA_CONVENTIONS.en.md) | Upload sessions, object storage, authorization and lifecycle |

## 20–29 Build, data and versions

| ID | Standard | Scope |
| --- | --- | --- |
| 20 | [Maven engineering](20_MAVEN_ENGINEERING_CONVENTIONS.en.md) | Modules, dependencies, builds and versions |
| 21 | [Persistence stack](21_PERSISTENCE_STACK.en.md) | MyBatis-Plus, Flyway and data-access boundaries |
| 22 | [Technology stack matrix](22_TECHNOLOGY_STACK_VERSION_MATRIX.en.md) | Java, Spring Boot, database and middleware versions |
| 23 | [Database migration](23_DATABASE_MIGRATION_STANDARD.en.md) | Flyway naming, immutable migrations and validation |
| 24 | [Currency conventions](24_CURRENCY_CONVENTIONS.en.md) | Minor units, currency dictionary, display and settlement snapshots |

## 30–39 Delivery and clients

| ID | Standard | Scope |
| --- | --- | --- |
| 30 | [Client release governance](30_CLIENT_RELEASE_GOVERNANCE.en.md) | Version/build numbers, channels and rollout |
| 31 | [Client release operations](31_CLIENT_RELEASE_SKILL.en.md) | Release steps, artifacts, verification and rollback |

## 40–49 Quality, observability and testing

| ID | Standard | Scope |
| --- | --- | --- |
| 40 | [Observability](40_OBSERVABILITY_CONVENTIONS.en.md) | Logs, metrics, traces and alerts |
| 41 | [Java logging](41_JAVA_LOGGING_CONVENTIONS.en.md) | Levels, fields, redaction and sampling |
| 42 | [Logging outputs](42_LOGGING_AND_OUTPUTS.en.md) | Output locations, diagnostics and troubleshooting |
| 43 | [E2E testing](43_E2E_TESTING_CONVENTIONS.en.md) | Smoke, real devices, acceptance and credentials |

## Related

- [Documentation center](../../README.en.md)
- [Engineering rules](../governance/ENGINEERING_RULES.md)
- [Gate registry](../governance/GATE_REGISTRY.md)
- [MQ registry](../../30-reference/mq/REGISTRY.md)