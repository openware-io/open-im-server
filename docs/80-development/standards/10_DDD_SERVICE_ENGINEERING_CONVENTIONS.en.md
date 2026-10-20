# DDD Service Engineering Conventions

[中文](10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md) | [English](10_DDD_SERVICE_ENGINEERING_CONVENTIONS.en.md)

> English summary; the Chinese document is normative.

## Scope

Layering, package structure, naming, domain modeling and service implementation rules.

## Fixed service template (G43)

```text
<ctx>/
├── api/controller/      # inbound HTTP adapters
├── application/         # use cases, commands, transactions
├── domain/              # aggregates, entities, value objects, repositories
├── infra/               # persistence, cache, MQ, RPC adapters
├── config/              # Spring runtime configuration
└── handler/             # web exception/advice support
```

- New controllers must live under `api/controller` (feature subpackages under `api/` are allowed).
- A top-level `controller` package is legacy-only and must be listed in `scripts/validate/service-layout-legacy-controllers.txt`.
- Media adapter-only services may omit `domain`; other business services require `api / application / domain / infra`.
- `validate-service-layout.ps1` (G43) is blocking; legacy exceptions must migrate out.
- `validate-controller-mapping-integrity.ps1` (G45) blocks duplicate verb/path mappings and method paths that repeat the class path; `consumes` overloads are allowed.

## Related

- [Documentation center](../../README.en.md)
- [Engineering topology](../../20-architecture/TOPOLOGY.en.md)
