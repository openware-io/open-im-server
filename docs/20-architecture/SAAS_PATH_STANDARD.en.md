# SaaS Path and Tenant Routing Standard

[中文](SAAS_PATH_STANDARD.md) | [English](SAAS_PATH_STANDARD.en.md)

> Status: `current`
> Applies to: SaaS admin, B/C mobile, OAuth callbacks, gateway and Ingress paths.

## 1. Principles

```text
A business application is not bound to a tenant.
A path identifies an entry/channel, not tenant identity.
Tenant identity comes only from the signed session
(tenantId + membership/role_binding).
A path never participates in authorization.
```

Therefore:

```text
Never put a tenant code in a business path.
Never derive the current tenant from a path parameter.
Never add one Ingress or one OAuth client per new tenant.
```

## 2. Canonical paths

```text
/saas/      SaaS admin (platform operations + tenant admin)
/b/         B-side mobile entry
/c/         C-side consumer entry
/api/**     business APIs, tenant-agnostic
/oauth/**   OAuth endpoints, tenant-agnostic
```

The tenant is resolved from the session/context:

```text
C: SaaS session -> tenantId
B: Admin session -> selected context -> tenantId
```

## 3. Legacy path

```text
/a380/      legacy C-side brand entry, kept as a compatibility alias
```

Requirements:

```text
/a380/ and /c/ serve the same saas-mobile artifact
OIDC redirect_uri registers both /c/ and /a380/
Old APKs, QR codes and bookmarks keep working
New links and docs use /c/
```

`/a380/` must not become the path template for new tenants.

## 4. Tenant branding

By default, do not distinguish tenants by path. Prefer subdomains:

```text
https://{tenantSlug}.example.com/c/
```

The edge maps the subdomain to a tenantId; the application still trusts the session.

If path isolation is required, only allow:

```text
/t/{tenantCode}/c/
/t/{tenantCode}/b/
```

Constraints:

```text
1. One wildcard Ingress serves all tenants; no per-tenant route
2. The edge resolves tenantCode to an ACTIVE tenantId
3. The application still uses the session tenantId
4. OIDC redirect_uri is registered per tenant, with a migration table
5. Renaming tenantCode keeps old-code 302/308 redirects
```

Not allowed:

```text
/{tenantCode}/          tenant code at the root
/a380/                  a single tenant brand as the generic entry
```

## 5. appId rules

```text
appId identifies the client application, not the tenant.
```

Examples:

```text
Preferred: saas-c, saas-b, saas-hotel-c
Avoid:     saas-a380-c, saas-s929-c
```

Tenant availability is decided by:

```text
tnt_app_installation + tenantId + application registry
```

The legacy `saas-a380-c` / `saas-a380-h5` IDs remain for compatibility and should be migrated later.

## 6. Migration order

```text
1. Add /c/ Ingress, keep /a380/
2. Register both /c/ and /a380/ OIDC redirect URIs
3. Make the C appId configurable, defaulting to the legacy value
4. Point Android/IM service entries to /c/
5. Observe traffic on both entries
6. After old traffic reaches zero, redirect /a380/ GET to /c/
7. Remove tenant/brand literals from appIds
```

## 7. Acceptance

```text
GET /c/                  200
GET /a380/               200
C /c/#/service/hotel     console []
C /a380/#/service/hotel  console []
OIDC redirect /c/        registered
OIDC redirect /a380/     retained
Renaming a tenant         does not change /c/
Adding a tenant           adds no Ingress or OAuth client
```
