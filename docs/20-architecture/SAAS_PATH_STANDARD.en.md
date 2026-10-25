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

## 3. Removed legacy path

`/a380/` has been removed. It is not kept as a compatibility alias.

Old bookmarks, QR codes and APKs must move to `/c/`.
The gateway no longer registers `/a380/`, and the C artifact no longer copies
its files under `/a380/`.

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
Avoid:     saas-c, saas-s929-c
```

Tenant availability is decided by:

```text
tnt_app_installation + tenantId + application registry
```

The legacy `saas-c` / `saas-b` IDs remain for compatibility and should be migrated later.

## 6. Migration order

```text
1. Serve the C app only at /c/
2. Register only /c/ OIDC redirect URIs
3. Point Android/IM service entries to /c/
4. Update DB service links and callback URLs
5. Remove /a380/ from gateway, static copy and docs
6. Verify /c/ and fail old links
```

## 7. Acceptance

```text
GET /c/                  200
GET /a380/               not served as the C app
C /c/#/service/hotel     console []
C /c/#/real-hotel/1      console []
OIDC redirect /c/        registered
OIDC redirect /a380/     removed
Renaming a tenant        does not change /c/
Adding a tenant          adds no Ingress or OAuth client
```

