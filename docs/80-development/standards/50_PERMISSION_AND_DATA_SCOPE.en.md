# Permission and Data-Scope Standard

[中文](50_PERMISSION_AND_DATA_SCOPE.md) | [English](50_PERMISSION_AND_DATA_SCOPE.en.md)

> Status: `current` (`develop/2.4.0`)  
> Layer: L1 security standard  
> Gate: G55 `permission-access-control`

## 1. Scope

Every tenant/admin and business HTTP endpoint, especially `/admin/**`, `/business/**`, and multi-store business types (hotel, KTV, massage, billiards, retail).

## 2. Terms and assumptions

| Term | Definition |
| --- | --- |
| Functional permission | `iam_permission.code`, granted to roles and enforced at the endpoint. |
| Menu permission | `iam_menu.required_permission`; controls visibility only, never API authorization. |
| Store scope | Visibility limited by the signed-context `storeId`; cross-store requests return 403. |
| Business type | Store business type such as `HOTEL` or `KTV`; one role may serve several types, so endpoints must check it. |
| Tenant boundary | Data ownership by `tenantId`, enforced by `TenantContextFilter` and SQL tenant conditions. |
| Server-side authorization | Permission and scope checks in controllers/services, not in the client. |

**Assumptions**: `X-Tenant-Context` is signed; every business request has a tenant context; store-scoped tables have `store_id`; permission codes are declared in Flyway.

## 3. Four mandatory rules

### 2.1 Menu hiding is not authorization

The server must enforce a permission code on every business endpoint:

```java
PermissionGuard.require("report.view");
```

The code must be declared in a Flyway migration, granted to roles, referenced by `iam_menu.required_permission`, and covered by a denial test.

### 2.2 Store scope comes from the signed context

`StoreScopeRequestFilter` applies to `/admin/**` and `/business/**`: a missing `storeId` is filled from context; another store's `storeId` or `storeIds` returns `403 STORE_SCOPE_FORBIDDEN`. Services must not fall back to all tenant stores when `storeId` is null. A request-body `storeId` is not a permission source.

### 2.3 Business type must be checked

A shared `store.manager` role can serve several business types. KTV endpoints require:

```java
BusinessScopeGuard.require("KTV");
```

Hotel endpoints require `HOTEL`. Future massage/billiards endpoints must define their own business type and must not reuse KTV APIs.

### 2.4 SQL must include store scope

The filter only covers query parameters. Store-scoped tables must query by `store_id`, and every new store-scoped table needs a cross-store invisibility test.

## 4. Controller audit rule

Every business admin controller must contain `PermissionGuard.require(...)`, an equivalent permission helper (`requireManageContext`, `requirePlatformOperator`), or `requireContext` plus `iam.role.manage`.

Only authentication infrastructure is exempt: `AdminLoginController`, `AdminSessionController`, `SsoAuthController`, `ContextController`, `AdminMenuController`, `AdminBackendController`.

G55 and `AdminControllerPermissionCoverageTest` fail when a new unguarded controller appears.

## 5. New business checklist

```text
1. Permission codes, e.g. massage.order.view / billiards.resource.manage
2. Flyway: iam_permission + iam_role_permission
3. Menu required_permission
4. Controller: PermissionGuard.require
5. Business type: BusinessScopeGuard.require
6. Store scope: StoreScopeRequestFilter + service-level current store
7. Tests: no-permission 403, cross-store 403, cross-business 403, normal 200
8. Documentation and gate registration
```

## 6. Template and example

```java
@RestController
@RequestMapping("/admin/massage/orders")
public class MassageOrderController {
    @ModelAttribute
    public void requireAccess() {
        PermissionGuard.require("massage.order.view");
        BusinessScopeGuard.require("MASSAGE");
    }

    @GetMapping
    public List<OrderView> list(@RequestParam(required = false) Long storeId) {
        return service.listCurrentStore(storeId);
    }
}
```

```sql
INSERT INTO iam_permission (code, module, resource, action, scope_level)
VALUES ('massage.order.view', 'massage', 'order', 'view', 'STORE');
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM iam_role r JOIN iam_permission p ON p.code = 'massage.order.view'
WHERE r.code IN ('tenant.owner', 'store.manager');
```

## 7. Evidence

- G55 `permission-access-control`: controller guards, filter registration, KTV business guard, permission-code declarations;
- `AdminControllerPermissionCoverageTest`;
- `StoreScopeRequestFilterTest`;
- `ReportControllerPermissionTest`;
- `AdminReportStoreScopeTest`.

## 8. Verifiable acceptance criteria

- A permission-less account receives `403 PERMISSION_DENIED`.
- A store account requesting another `storeId` receives `403 STORE_SCOPE_FORBIDDEN`.
- A hotel context calling KTV configuration/reservations receives `403 BUSINESS_SCOPE_FORBIDDEN`.
- A valid store request returns only the current store.
- G55 and `AdminControllerPermissionCoverageTest` pass.
- A new controller without a guard fails the gate.

## 9. Relationship to other standards

- [Engineering principles](03_ENGINEERING_PRINCIPLES.en.md): correctness and boundaries;
- [DDD service engineering](10_DDD_SERVICE_ENGINEERING_CONVENTIONS.en.md): controllers are inbound adapters;
- [Database migration](23_DATABASE_MIGRATION_STANDARD.en.md): permission and grant changes use Flyway;
- [Gate governance](04_GATE_GOVERNANCE.en.md): G55 registration and lifecycle;
- [Account permission model](../../40-domain/ACCOUNT_PERMISSION_MODEL.md): roles, scopes and delegation.

## 10. Known boundary

G55 is static coverage, not a proof that every SQL statement is scoped. Request-body `storeId`, complex joins, and legacy endpoints still need review and integration tests. An endpoint that is hidden in the menu but callable without permission is a defect, not a compatibility concern.
