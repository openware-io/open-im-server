# Permission and Data-Scope Standard

[中文](50_PERMISSION_AND_DATA_SCOPE.md) | [English](50_PERMISSION_AND_DATA_SCOPE.en.md)

> Status: `current` (`develop/2.4.0`)  
> Layer: L1 security standard  
> Gate: G55 `permission-access-control`

## 1. Scope

Every tenant/admin and business HTTP endpoint, especially `/admin/**`, `/business/**`, and multi-store business types (hotel, KTV, massage, billiards, retail).

## 2. Four mandatory rules

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

## 3. Controller audit rule

Every business admin controller must contain `PermissionGuard.require(...)`, an equivalent permission helper (`requireManageContext`, `requirePlatformOperator`), or `requireContext` plus `iam.role.manage`.

Only authentication infrastructure is exempt: `AdminLoginController`, `AdminSessionController`, `SsoAuthController`, `ContextController`, `AdminMenuController`, `AdminBackendController`.

G55 and `AdminControllerPermissionCoverageTest` fail when a new unguarded controller appears.

## 4. New business checklist

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

## 5. Evidence

- G55 `permission-access-control`: controller guards, filter registration, KTV business guard, permission-code declarations;
- `AdminControllerPermissionCoverageTest`;
- `StoreScopeRequestFilterTest`;
- `ReportControllerPermissionTest`;
- `AdminReportStoreScopeTest`.

## 6. Known boundary

G55 is static coverage, not a proof that every SQL statement is scoped. Request-body `storeId`, complex joins, and legacy endpoints still need review and integration tests. An endpoint that is hidden in the menu but callable without permission is a defect, not a compatibility concern.
