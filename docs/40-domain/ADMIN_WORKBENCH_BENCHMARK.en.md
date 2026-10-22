# Admin Workbench Industry Benchmark

[中文](ADMIN_WORKBENCH_BENCHMARK.md) | [English](ADMIN_WORKBENCH_BENCHMARK.en.md)

> Status: `draft` (2026-10-22, design review input).  
> Purpose: validate the four-workspace model against mature products and identify gaps.  
> The confirmed Platform / Headquarters / Store / IM model is not being replaced.

## 1. Products reviewed

| Product | Type | Borrow | Do not copy |
| --- | --- | --- | --- |
| Shopify Admin | multi-store commerce | store switcher, staff permissions, app ecosystem | single vertical |
| Square Dashboard | multi-location operations | location switcher, team permissions, task home | payment/retail focus |
| Toast | restaurant operations | HQ/store split, front-of-house tasks, roles | single vertical |
| Mews / Cloudbeds | hotel PMS | property switcher, front desk/reservations/rooms/reports | single vertical |
| Oracle OPERA / Agilysys | enterprise hospitality | group-property-role-task chain | too heavy for MVP |
| Odoo / SAP Fiori | multi-app / role work centers | module registry, role work center | too many apps/tiles |

References:

- [Shopify staff permissions](https://help.shopify.com/en/manual/your-account/staff-accounts)
- [Square team permissions](https://squareup.com/help/us/en/article/5096-set-up-team-permissions)
- [Toast back office](https://pos.toasttab.com/products/back-office)
- [Mews PMS](https://www.mews.com/en/products/pms)
- [Oracle OPERA](https://www.oracle.com/hospitality/hotel-property-management/)
- [Odoo apps](https://www.odoo.com/apps)
- [SAP Fiori](https://www.sap.com/products/technology-platform/fiori.html)

## 2. Matrix

`Strong / Partial / Missing` for the current design and prototype.

| Dimension | Industry pattern | Our state | Verdict |
| --- | --- | --- | --- |
| Org hierarchy | group/brand/region/store/role | Platform/Tenant/Store/IM | Strong |
| Role landing | login to a role home | prototype yes; implementation pending | Partial |
| Multi-business | portfolio + business switch | HQ cards + store switch | Strong |
| Module extension | registered modules/entitlements | `businessModule` designed | Partial |
| Menu visibility | server-filtered by permission | RBAC exists; role profile/menu contract pending | Partial |
| Task home | front desk sees tasks | prototype differentiates manager/front desk | Strong |
| Multi-location rollup | all-locations view + ranking/drill-down | ranking exists; all-locations range missing | Partial |
| Onboarding | new tenant/store checklist | missing | Missing |
| Permission granularity | role templates + custom + store scope | fixed roles + permissions; templates missing | Partial |
| Mobile | same role/task model | B-end prototype exists; not wired | Partial |
| Search/favorites | global search/recent | missing | Missing (non-MVP) |
| Support mode | view-as-tenant with audit | designed, not implemented | Partial |

## 3. Conclusions

The four-workspace model is consistent with Toast (HQ/store), Mews (property/front desk) and Shopify (store switcher). The HQ portfolio + store business switch is a better fit for multi-vertical groups than one shared sidebar. Manager-vs-front-desk task homes match hospitality products. Module registration is the right extension model.

Gaps, in priority order:

```text
P0 role profile + landing + server menus
P1 all-locations rollup
P2 role templates + store-scope grants
P3 tenant/store onboarding
P4 entitlement-driven modules
P5 mobile role/task parity
P6 global search/recent (non-MVP)
```

## 4. Recommendations

### 4.1 All locations at HQ

Add a scope switch:

```text
All locations / Hotel / KTV / single store
```

Keep the portfolio cards and add consolidated metrics plus drill-down.

### 4.2 Role templates and custom roles

Industry products provide role templates, scope (platform/tenant/store) and page/action permissions. We have `iam_role` and `iam_permission`; missing are templates, role profiles, store-scope grants and custom-role governance.

### 4.3 Onboarding

New tenant:

```text
create tenant -> choose business -> create store -> assign roles -> payment -> first order
```

New store:

```text
profile -> enable business -> rooms/resources -> staff roles -> hours -> first order
```

Without onboarding, new accounts land on empty dashboards.

### 4.4 Entitlement-driven modules

```text
hotel subscribed -> hotel module visible
not subscribed -> portfolio shows "available", no hotel menus
```

### 4.5 Action-level permissions

Menu controls entry; actions also need permissions such as `hotel.checkin`, `hotel.checkout`, `hotel.rate.update`. Otherwise users can enter a page but cannot act.

### 4.6 Mobile parity

Mobile should reuse role/workspace/home/task, only trimming modules.

## 5. Do not copy

- Odoo-style app catalog: too many modules becomes a maze.
- SAP Fiori-style tile work centers: too heavy for MVP.
- Oracle OPERA full hotel depth: high learning cost.
- Shopify store switching alone: we also need the business dimension.
- One superadmin for everything: destroys role workbenches.

## 6. Impact

Unchanged: four workspaces, role homes, hotel/KTV store workbenches, portfolio + business switch.

Added: all-locations rollup, role templates/store scope, onboarding, entitlement-driven modules, action-level permissions, mobile parity.

## 7. Related

- [Admin role workbench](ADMIN_WORKBENCH.en.md)
- [ADR-0013](../20-architecture/adr/0013-role-workbench.md)
- [Hotel plan](hotel/HOTEL_IMPLEMENTATION_PLAN.en.md)
