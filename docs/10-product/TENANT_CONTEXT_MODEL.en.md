# Tenant Workspace & Store Context (Product Guide)

[中文](TENANT_CONTEXT_MODEL.md)

> Status: `current` (2026-10-29)
> Pages: tenant console context switcher, store business pages, role/permission pages

## 1. Three concepts

| Concept | Meaning |
| --- | --- |
| Workspace | headquarters / store / platform shell, decides navigation and entries |
| Data scope | current `tenantId / organizationId / storeId`, decides queried data |
| Role source | headquarters roles and store roles effective in the current context |

Role definitions belong to the tenant; a store only determines bindings and data scope.

## 2. Tenant administrator

- enters the tenant console;
- can switch the context to headquarters or a subordinate store;
- switching to a store keeps the **headquarters view**; only the data scope changes;
- the header shows `Headquarters view · Data scope: store X`;
- menus are pruned by the current store business type (KTV store shows KTV config, hotel store shows hotel front desk);
- role/permission pages still maintain tenant-level role templates and show a store-scope notice.

## 3. Store manager

- enters the store console;
- sees only the authorized store, no headquarters entries;
- header shows `Store view · Data scope: current store`;
- menus contain only the current business type (hotel/KTV/common store);
- cannot enter tenant role/permission pages;
- direct access to cross-business pages is redirected.

## 4. Business menus

Menus are filtered by `business_type`:

- hotel store/context: hotel front desk, room types/rates, etc.;
- KTV store/context: KTV configuration;
- common store menus remain (reservations, cashier, orders, resources, inventory, members, reports, store settings).

Backend read paths also validate the store business type, so direct API access cannot bypass menu pruning.

## 5. Permission source

The context bar shows effective roles:

```text
Headquarters view · Data scope: A380 KTV Flagship    Roles: tenant.owner
Store view · Data scope: SaaS Hotel                 Roles: store.manager
```

Role/permission pages show that role definitions are tenant-level templates when a store scope is active.

## 6. Acceptance

- tenant administrator `/select` shows only the tenant backend;
- direct `/store` redirects to the tenant overview;
- switching to a store keeps the tenant shell and shows headquarters view/data scope;
- store managers see only their store and cannot enter tenant role/permission pages;
- KTV manager sees KTV business menus only; hotel manager sees hotel menus only;
- KTV store direct hotel room-board access is rejected; hotel store direct KTV access is rejected;
- header role source matches context candidates.
