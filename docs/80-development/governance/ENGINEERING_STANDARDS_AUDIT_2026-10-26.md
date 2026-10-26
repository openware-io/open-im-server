# Engineering Standards Audit - 2026-10-26

Scope: post-A380 cleanup, SaaS/IM naming separation, tenant/IAM/hotel changes,
frontend and documentation assets.

## Baseline evidence

- `invoke-engineering-validation.ps1 -Scope Full`: PASS, 54 gates, Maven clean verify 05:33.
- C: 26 files / 172 tests PASS.
- B: 51 files / 696 tests PASS.
- Android `flutter analyze`: no issues for changed files (not a full test pass).

This audit is a review of conformance, not a claim that every UI path has full coverage.

## Findings

### P0 - `/a380/` returned 200 (FIXED)

Original evidence:

```text
curl -I http://127.0.0.1:30080/a380/
HTTP/1.1 200 OK
Content-Type: text/html
```

Cause: `open-im-portal` ingress has `path: /` to the unified portal SPA; the
portal's fallback served `index.html` for `/a380/`. The mobile ingress only
registered `/c/` and `/b/`; removing the mobile route did not remove the portal
fallback.

Fix: added an explicit `saas-not-found` nginx backend and registered
`/a380(/|$)(.*)` on `open-im-mobile-apps` before `/c/`.

Verified:

```text
/a380/ -> 404
/c/    -> 200
/b/    -> 200
/saas/ -> 200
```

Status: COMPLIANT with `SAAS_PATH_STANDARD.md`.

### P1 - SaaS/IM naming boundary still leaks

Correctly separated:

```text
tenant code: saas-demo
tenant name: SaaS 生活服务
hotel: SaaS Hotel
admin: tenant-admin / 租户管理员
wallet: SaaS 币
```

Still mixed in current runtime code/assets:

```text
c-end/app.js: OpenChat hotel/bar mock names, OpenChat member label
c-end/data.js: OpenChat hotel mock data
c-end/login brand: OpenChat + 生活服务
c-end auth-errors: OpenChat service wording
B docs/remote-dev-integration.md: OpenChat 租户运营 wording
```

The IM login button and IM app itself are correctly OpenChat. The remaining
SaaS business pages/data should say SaaS or a neutral business name.

Status: PARTIAL.

### P1 - A380 internal names and assets remain

Counts in current working trees:

```text
open-saas-mobile: 184 A380 lines
open-chat-app:    75 A380 lines
open-saas-admin:  12 A380 lines
open-im-server:  223 non-historical A380 references (including frozen migrations and tests)
```

Main current-code remnants:

```text
A380ApiErrors, A380DateTime, A380PageLoad, A380Picker,
A380BusinessHours, A380HotelBookings, A380ReservationRoom
localStorage: a380-orders, a380-cart
Android l10n key: servicesWalletA380Coin
web/miniservice/a380 legacy prototype
```

Frozen migrations and historical evidence are intentionally immutable; they
should be labeled as historical, not treated as current targets.

Status: NOT CLEAN, but mixed severity: internal namespace > docs history.

### P1 - Admin hotel booking customer association is not a finished business flow

Now implemented:

```text
POST /api/v1/admin/hotel/bookings
body.bookedByAccountId
```

and C 端 “我的预约” reads the associated booking.

Still incomplete:

- B 后台 customer selector search was not reliably exercised end to end.
- No automatic phone -> account resolution; operator must select/enter a customer.
- No test for checkout/check-in status propagation to C.

Status: PARTIAL; business logic not closed.

### P1 - Hotel images are not implemented

Evidence:

```text
B /admin/tenant/hotel: 0 <img>
C /c/#/service/hotel: 0 <img>
C /c/#/real-hotel/1: 0 <img>
```

C uses `🏨` emoji and gradients; B explicitly says hotel master data is not connected.
Image loading, broken-image fallback and room-type galleries cannot be validated.

Status: FUNCTIONAL GAP, not a passing test.

### P2 - Authorization coverage is partial

Fixed:

```text
tenant -> /api/v1/admin/platform/tenants: 403 PLATFORM_ADMIN_REQUIRED
platform -> same endpoint: 200
tenant -> /api/v1/admin/staff: 200
store -> /api/v1/admin/staff: 403
C customer -> admin platform: 401
```

Not fully audited:

```text
all /admin/platform/** endpoints
all /admin/iam/** endpoints
all tenant-only page URLs entered by store accounts
customer audience widening to saas-b/saas-admin
```

Some endpoint probes returned 404/400 because the route does not exist, not
because authorization passed; those must not be counted as security coverage.

### P2 - Full page/button/style coverage is not complete

Actually exercised:

```text
platform tenants page
tenant staff page
tenant members page
tenant hotel page
store landing/orders/members
C hotel list/detail/date picker/quote/empty submit
```

Not exercised:

```text
all admin menu pages and every button
all dialogs and form validations
all pagination/filter/export flows
all destructive actions with test data
all resolutions/devices
full Android interaction matrix
```

Status: NOT FULL COVERAGE.

### P2 - Historical docs and frozen migrations still contain A380

Examples:

```text
docs/40-domain/identity/S3/S4/S5/S7 evidence
docs/99-archive
immutable V1 migration seeds and old V2/V3/V10/V20 rebrand migrations
test fixtures using A380 names
```

These are historical records; rewriting frozen migrations would violate
Flyway immutability. They should be marked historical rather than silently used
as current naming.

## Compliance summary

| Area | Result |
|---|---|
| Full gate / Maven | PASS |
| C/B test suites | PASS |
| `/c/` `/b/` `/saas/` runtime | PASS |
| `/a380/` removal | PASS (404 via explicit not-found backend) |
| Tenant data branding | PASS (saas-demo/SaaS) |
| SaaS/IM separation in all UI | PARTIAL |
| A380 internal namespace | FAIL / not migrated |
| Platform tenant authz | PASS |
| All platform authz | NOT FULLY VERIFIED |
| Hotel booking customer link | PARTIAL |
| Hotel images | NOT IMPLEMENTED |
| Full browser/button coverage | NOT COMPLETE |
| Docs current naming | PARTIAL (history remains) |
| Frozen migrations immutability | PASS |

## Recommended remediation order

1. Block `/a380` at ingress/portal (P0).
2. Rename C `A380*` namespace and `a380-*` storage keys (P1).
3. Remove/rename Android `A380` l10n keys and legacy `web/miniservice/a380` (P1).
4. Finish admin customer selection/phone auto-link and test B -> C status transitions (P1).
5. Add hotel image data and B master-data page; then test image fallback (P1).
6. Crawl every admin route and exercise buttons/forms with seeded test data (P1).
7. Update current docs only; keep frozen migrations/historical evidence clearly marked (P2).
