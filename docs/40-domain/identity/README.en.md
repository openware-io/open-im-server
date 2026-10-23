# Identity and Access

[中文](README.md) | [English](README.en.md)

> Status: `accepted target model` (2026-10-23, `develop/2.4.0-hotel`)
> Scope: IM/SaaS account separation, OAuth federation, C/B memberships, tenant roles, entitlements, app installation, support grants.
> Decision: [ADR-0015](../../20-architecture/adr/0015-identity-federation-tenant-iam.md)

## Documents

| Document | Description |
| --- | --- |
| [Identity and access model](IDENTITY_ACCESS_MODEL.md) | Principals, memberships, roles, permissions, entitlements, OAuth, sessions, engineering structure |
| [Identity data model](IDENTITY_SCHEMA.md) | Target DDL, ownership, unique keys, migration order |
| [Identity API contract](IDENTITY_API.md) | OAuth callback, contexts, permission snapshot, app entries, error codes |
| [Rollout checklist](IDENTITY_ROLLOUT.md) | Migrations, code structure, app registration, backfill, gates |
| [Account permission model](../ACCOUNT_PERMISSION_MODEL.md) | Historical/current model; this directory wins on conflicts |

## Core decisions

```text
IM and SaaS keep separate account domains
OAuth 2.0/OIDC federation, no shared account table or session
CUSTOMER and STAFF are tenant memberships, not global account types
Role bindings attach to memberships, not global accounts
PLATFORM / TENANT / STORE scopes
Entitlements are separate from permissions
App installation + entitlement + role permission determine availability
```

## Related

- [ADR-0014 workspace role model](../../20-architecture/adr/0014-workspace-role-model.md)
- [Domain map](../DOMAIN_MAP.md)
- [platform-identity-service](../../50-services/platform/platform-identity-service/README.md)
- [platform-tenant-service](../../50-services/platform/platform-tenant-service/README.md)
