# Business Documentation Index

[中文](README.md) | [English](README.en.md)

> Status: `current` (`develop/2.3.0`)  
> The Chinese documents are normative; this index provides English titles and scope. Historical transformation context is in `docs/archive/renovation/`.
> Domain classification and dependencies: [domain map](DOMAIN_MAP.en.md).

| Document | Scope |
| --- | --- |
| [Account and permission model](ACCOUNT_PERMISSION_MODEL.md) | Platform/IM accounts, tenant/store roles and operator permissions |
| [Identity and access](identity/README.en.md) | IM/SaaS identity federation, tenant memberships, role bindings, entitlements, app installation |
| [Organization management design](identity/ORGANIZATION_MANAGEMENT_DESIGN.md) | Tenant-side organization lifecycle, platform read-only boundary, phase-1 authorization-scope staging |
| [Permission catalog matrix design](identity/PERMISSION_MATRIX_DESIGN.md) | Four-state matrix, global-role-template scope, per-role permission CAS, page freshness |
| [Preset role baseline calibration](identity/PERMISSION_DATA_CALIBRATION.md) | 67 metadata grants, V36 migration, audit query and zero-anomaly acceptance |
| [Domain map](DOMAIN_MAP.en.md) | Core/supporting/generic/edge domains, owners and dependency rules |
| [Admin role workbench](ADMIN_WORKBENCH.en.md) | Platform/headquarters/store/IM workspaces and role homes |
| [Admin workbench benchmark](ADMIN_WORKBENCH_BENCHMARK.en.md) | Benchmark against Shopify/Square/Toast/Mews and gaps |
| [Public support domain blueprint](PLATFORM_DOMAINS.en.md) | Transaction, inventory, user and order concepts with a three-horizon plan |
| [Billing domain](billing/README.en.md) | Billing/amount due, distinct from checkout/settlement |
| [Architecture diagrams and business lines](ARCHITECTURE_DIAGRAMS.md) | IM and SaaS lines, domain boundaries and key flows |
| [Conversation types and privacy](CONVERSATION_TYPES_AND_PRIVACY.md) | Private/group/channel/secret chats and data boundaries |
| [Project facts inventory](PROJECT_FACTS_INVENTORY.md) | Current product and engineering facts |
| [User deletion and data lifecycle](USER_DELETION_AND_DATA_LIFECYCLE.md) | Cancellation, deletion, retention and audit requirements |
| [Feature topics](features/README.en.md) | Channel, secret chat and secret-group implementation topics |
| [Hotel business](hotel/README.en.md) | Room types, rates, inventory calendar, booking, check-in/out |
| [Inventory platform](inventory/README.en.md) | Supporting domain: Stock |
| [Order platform](order/README.en.md) | Shared order transaction kernel + business fulfillment extensions |

## Related

- [Service directory](../50-services/README.en.md)
- [Engineering topology](../20-architecture/TOPOLOGY.en.md)
- [Documentation center](../README.en.md)
