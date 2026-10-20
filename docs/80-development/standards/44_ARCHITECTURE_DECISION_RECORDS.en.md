# Architecture Decision Records (ADR)

[中文](44_ARCHITECTURE_DECISION_RECORDS.md) | [English](44_ARCHITECTURE_DECISION_RECORDS.en.md)

> Status: `current` (`develop/2.3.0`).  
> Scope: architecture decision records under `docs/20-architecture/adr/`.

## Scope

This standard defines ADR numbering, status, structure, indexing and retention. It applies to decisions that affect multiple services or long-term evolution, such as authentication, data consistency, service decomposition, persistence, messaging and deployment.

It does not apply to feature design notes, API field references, meeting notes or coding standards.

## Terms

- **ADR**: Architecture Decision Record.
- **Status**: `proposed`, `accepted`, `rejected`, `deprecated`, `superseded`, `withdrawn`.
- **Index**: `docs/20-architecture/adr/README.md`.
- **Immutable**: accepted ADRs are not rewritten; supersede with a new ADR.

## Rules

1. One decision per ADR.
2. File name is `NNNN-kebab-case.md`; numbering is never reused; template is `0000-template.md`.
3. Required headings: `Status / 状态`, `Context / 背景`, `Decision / 决策`, `Consequences / 后果`.
4. Withdrawn and superseded ADRs stay in the ADR directory; do not move them to `99-archive/`.
5. Every ADR must be listed in `adr/README.md`.
6. ADRs record history; current rules belong in `standards/` and are enforced by gates.
7. Superseded ADRs must name `superseded-by`.

## Template

[0000-template.md](../../20-architecture/adr/0000-template.md)

## Gate

G47 `validate-architecture-decisions.ps1` validates directory/index/template, file names, required headings, status values, index coverage and unique numbers.

## Related

- [DDD service conventions](10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)
- [Documentation organization](../governance/DOCUMENTATION_ORGANIZATION.md)
- [ADR index](../../20-architecture/adr/README.md)
