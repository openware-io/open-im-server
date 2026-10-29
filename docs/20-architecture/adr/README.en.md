# Architecture Decision Records (ADR)

[中文](README.md) | [English](README.en.md)

> Rules: [ADR standard](../../80-development/standards/02_ARCHITECTURE_DECISION_RECORDS.en.md)
> Template: [0000-template.md](0000-template.md)  
> One decision per ADR; accepted records are immutable; withdrawn/superseded records stay in the log.

## Index

| ID | Decision | Status | Date |
| --- | --- | --- | --- |
| 0007 | [`version` named exception](0007-version-named-exceptions.md) | withdrawn | 2026-10-18 |
| 0014 | [Platform, tenant and store workspaces and role governance](0014-workspace-role-model.md) | accepted | 2026-10-23 |
| 0015 | [IM and SaaS identity federation, tenant membership and authorization](0015-identity-federation-tenant-iam.md) | accepted | 2026-10-23 |
| 0017 | [Permission catalog matrix cell semantics, scope boundary and write concurrency](0017-permission-matrix-semantics-and-concurrency.md) | proposed | 2026-10-29 |
| 0018 | [Preset role grantable-level calibration](0018-preset-role-grantable-level-calibration.md) | proposed | 2026-10-29 |
| 0019 | [Role management menu and read-permission boundary](0019-role-menu-and-read-permission-boundary.md) | proposed | 2026-10-29 |
| 0020 | [Tenant role permission matrix](0020-tenant-role-permission-matrix.md) | proposed | 2026-10-29 |
| 0021 | [Platform admin role layers and super-admin protection](0021-platform-admin-role-layers.md) | proposed | 2026-10-29 |
| 0022 | [Tenant workspace, context and store data scope](0022-tenant-workspace-context-scope.md) | proposed | 2026-10-29 |

## Status values

```text
proposed
accepted
rejected
deprecated
superseded
withdrawn
```

## Add an ADR

1. Copy [0000-template.md](0000-template.md).
2. Use the next unused number and name it `NNNN-kebab-case.md`.
3. Fill `Status / 状态`, `Context / 背景`, `Decision / 决策`, `Consequences / 后果`.
4. Add it to the index above.
5. Run `scripts/validate/validate-architecture-decisions.ps1`.

## Boundary

- This directory records **historical decisions**, not current rules.
- Current technical constraints live in `docs/80-development/standards/`.
- Superseded ADRs name `superseded-by`; do not delete or rewrite them.