# Frontend UI Style Standard (SaaS B/C)

> Status: `current`
> Scope: `open-saas-admin` (B), `open-saas-mobile` (C)
> Baseline: KTV business pages; hotel and all group businesses must reuse it

## 1. Goal

All businesses under the same group (KTV, hotel, bar, etc.) share one visual language:

```text
one set of design tokens
one page skeleton and card system
one status/tag/button semantics
no per-page primary colors, radii, or shadows
```

## 2. B-side standard

### 2.1 Page skeleton

```text
root class="admin-page"
header class="page-header": h2 20px/600 + 13px description + actions
block class="admin-card": white, 1px border, var(--admin-radius), 20px padding, light shadow
metrics class="metric-grid" + "metric-card"
filters class="filter-bar"
tables Element Plus el-table border stripe
```

### 2.2 Component rules

```text
primary action el-button type="primary"
secondary default/link
status el-tag + business dictionary
pagination admin-pagination
no hand-written page-level cards
no custom primary color/radius/shadow
```

### 2.3 Metric cards

```text
4 columns desktop; 2 at <=640px; 1 at <=420px
number 26px/600, label and hint 13px muted
same radius and shadow as admin-card
```

## 3. C-side standard

### 3.1 Design tokens

```text
--primary #13b981   --primary-dark #079668
--navy #273f73      --text #1b2430
--muted #7b8492     --line #e9ebef
--bg #f5f6f8        --card #ffffff
--danger #ff654e    --radius 18px
```

### 3.2 Components

```text
page-head       sticky header: back / title / action
venue-card      list card: cover + copy
hotel-cover     hotel cover placeholder, same size as KTV image card
detail-card     detail card: title, address, tags
form-card       form card: label + input/select + primary-button
tag-row         tag pills
status-*        status pills
```

### 3.3 Business rules

```text
lists use venue-card
details use detail-card + tag-row
order status uses tag-row, not bare text
config reads /api/v1/business/tenant/config
must not call /api/v1/admin/tenant/config
no local colors conflicting with tokens
```

## 4. Acceptance

```text
B pages have no custom primary color/radius/shadow
B metric cards match between KTV and hotel
C list/detail/order use shared components
C console has no 401/403/JS error
same action uses consistent wording and status color on B/C
```

## 5. References

```text
open-saas-admin/src/styles/global.css
open-saas-admin/src/views/tenant/ktv-config.vue
open-saas-admin/src/views/tenant/hotel.vue
open-saas-mobile/c-end/styles.css
open-saas-mobile/c-end/app.js
open-saas-mobile/c-end/hotel-real.js
```

## 6. Enforcement

```text
scripts/validate/validate-frontend-ui-standard.ps1
```

Checks B-side `admin-page/admin-card/metric-grid`, C-side tokens/`hotel-cover`/`tag-row`,
and forbids C from calling `/api/v1/admin/tenant/config`. Registered in `invoke-engineering-validation.ps1`.

## 10. Compatibility baseline and component constraints

```text
Measured baseline: Xiaomi MI 9, Android 11, WebView 83.0.4103.106
Supported baseline: Chrome / WebView >= 83
```

Shared semantic tokens are mandatory for B/C: brand, surface, text, border, danger,
radius, shadow, space, font, z-index.

B maps them to Element Plus (`--el-color-primary`, `--el-border-radius-base`);
C maps them to its own CSS variables. Pages must not hard-code primary colors,
radii or shadows.

WebView 83 prohibitions:

```text
flex gap without margin fallback
aspect-ratio without fallback
inset shorthand
:is() / :where() / color-mix() / dvh
backdrop-filter without solid fallback
```

`html.compat-webview83` is added by feature detection and provides margin,
fixed-height and solid-background fallbacks.

Mobile overlays use a bottom sheet for select/date/time and a product dialog for
confirmation. `window.confirm` is forbidden in C. B keeps Element Plus dialogs
and popovers with the same tokens.

Gates: UI standard validation, compatibility scanning, and visual regression at
360x800, 393x851 and 412x915.
