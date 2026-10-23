# Admin Role Workbench and Information Architecture

[中文](ADMIN_WORKBENCH.md) | [English](ADMIN_WORKBENCH.en.md)

> Status: `draft` (2026-10-22, pending prototype review).  
> Scope: SaaS admin (platform / headquarters / store / IM).  
> Principle: roles choose the workspace, permissions decide visibility, context decides data scope.

## 0. Implementation status

| Phase | Status | Delivered |
| --- | --- | --- |
| P0 role profile / landing / server menus | done | `iam_role_profile`; login/session roles/workspaces/landing; role landing |
| P1 all-locations rollup | done | Overview scope: all / hotel / KTV / single store |
| P2 role templates / store scope | done (basic) | Real role-workbench profiles with workspace/landing/scope editing |
| P3 onboarding | done (basic) | Data-driven tenant onboarding checklist |
| P4 entitlement-driven modules | done (basic) | `tnt_business_entitlement` + `/admin/tenant/businesses`; unentitled scopes disabled |
| P5 action-level permissions | done | Hotel front-desk tasks/check-in/check-out guarded by `hotel.*` permissions |
| P6 mobile role/task parity | wired | identity employee login → B-end session → store context → hotel tasks |

### P4-P6 entry points

```text
P4
  GET  /api/v1/admin/tenant/businesses
  POST /api/v1/admin/tenant/businesses/{businessType}/enable
  table: tnt_business_entitlement

P5
  iam_permission: tenant.business.manage / hotel.*
  frontend: v-permission
  backend: tenant.business.manage check in BusinessEntitlementApplicationService

P6
  Flutter: /business/role-home
  POST /api/v1/identity/login
  POST /api/v1/auth/session?appId=saas-a380-h5
  GET  /api/v1/auth/contexts
  POST /api/v1/auth/context/select
  GET  /api/v1/business/hotel/frontdesk/tasks
  manager / front desk / cashier task home
```

## 1. Problem

The current admin mixes four layers in one sidebar:

```text
platform operations
tenant headquarters
store operations
business configuration
```

As a result, every role sees too much, store menus disappear without a store role, and the hotel page mixes configuration with daily operations.

## 2. Four workspaces

```text
Group IDaaS
  ├─ Platform Console
  ├─ Headquarters
  ├─ Store Workspace
  └─ IM Console
```

| Workspace | Roles | Data scope | Landing |
| --- | --- | --- | --- |
| Platform | platform superadmin/operator | platform | `/platform/overview` |
| Headquarters | tenant owner/admin | tenant | `/tenant/overview` |
| Store | manager/front desk/cashier/finance | store | `/store/:storeId/today` |
| IM | IM admin | IM | `/im/overview` |

Multiple workspaces are possible; only one is the default, and a workspace switcher appears only when needed.

## 3. Role Profile

A role needs more than permissions:

```text
iam_role_profile
  role_id
  workspace_code
  landing_route
  default_scope
  default_business_type
  nav_profile
  data_scope
  status
```

Examples:

```text
store.manager -> STORE / today / store scope
store.cashier -> STORE / cashier / store scope
tenant.owner -> HEADQUARTERS / overview / tenant scope
```

## 4. Login landing

The login response returns roles, workspaces, default workspace and landing route. Stop using the first menu leaf as the landing page.

## 5. Role home

### Platform

Tenant count, active stores, new tenants, exceptions, tenant table, platform alerts. No hotel room status or KTV floor.

### Headquarters

Group revenue, occupancy, KTV open rate, business portfolio, store ranking, policy alerts. No front-desk check-in.

### Hotel manager

Today's arrivals/in-house/due-out/occupancy, task queue, room grid, quick actions.

### Hotel front desk

Task queue: check-in, check-out, payment, confirmation. No group reports.

### KTV manager

Open rooms, reservations, revenue, pending bills, floor status.

## 6. Navigation

- Platform: overview, tenants/plans, IAM, payment methods, integrations, audit/health.
- Headquarters: overview, business portfolio, stores, members, finance, marketing, reports, governance, policies.
- Store: today, front desk, reservations, rooms, cashier, shifts, products, members, reports, settings.
- Hotel store: today, front desk, reservations, rooms, rates/inventory, guests, reports, settings.
- KTV store: today, floor, reservations, cashier, shifts, products, reports, settings.

## 7. Hotel split

Do not keep nine tabs in `hotel.vue`. Use routes:

```text
/store/:storeId/hotel/overview
/store/:storeId/hotel/frontdesk
/store/:storeId/hotel/reservations
/store/:storeId/hotel/rooms
/store/:storeId/hotel/rates
/store/:storeId/hotel/guests
/store/:storeId/hotel/reports
/store/:storeId/hotel/settings
```

Headquarters keeps a portfolio view, not store operations.

## 8. Future businesses

Register modules; do not change the shell:

```text
businessModule
  code
  name
  workspace
  routeBase
  menuProvider
  dashboardProvider
  capabilities
```

Headquarters shows business cards; store shows a business switcher.

## 9. Permissions

```text
Capability + Permission + Context
```

Menus are server-filtered; unauthorized actions are not rendered; deep links redirect to the role home with a reason. No "visible then denied".

## 10. Migration

```text
P0 role profile + landing + menu by workspace
P1 three-workspace shell
P2 store role provisioning
P3 split hotel
P4 align KTV
P5 business module registry
```

## 11. Production entries

The prototype route has been removed from the production router.

```text
Platform roles   /saas/#/admin/iam/roles
Tenant roles     /saas/#/admin/roles
Store workspace  /saas/#/store
```

Menus, roles and data scope are determined by the signed-in workspace and IAM permissions.

## 12. Related

- [Domain map](DOMAIN_MAP.en.md)
- [Industry benchmark](ADMIN_WORKBENCH_BENCHMARK.en.md)
- [ADR-0013: role workbench](../20-architecture/adr/0013-role-workbench.md)
- [Hotel plan](hotel/HOTEL_IMPLEMENTATION_PLAN.en.md)
