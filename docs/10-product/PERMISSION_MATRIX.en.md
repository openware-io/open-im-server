# Permission Catalog Matrix (Product Guide)

[中文](PERMISSION_MATRIX.md)

> Status: `current` (2026-10-29)
> Page: SaaS operations console → Permissions → Permission Catalog
> Related: Role & Permission → role list → Permission Matrix

## 1. What it is

The Permission Catalog page combines the permission list and global-role grants in one table. It helps platform operators answer:

1. Which permissions exist?
2. What does each global role template have?
3. Which permissions are grantable but not yet granted?
4. Which grants exceed the role's grantable scope and need governance?

It is a read-only governance view. Editing still happens in the Role & Permission page.

## 2. Audience and scope

- Platform operations accounts with access to the permission catalog.
- The matrix contains **global role templates** (preset roles and platform custom roles); it does not include tenant custom roles or actual role bindings.
- Tenant admins maintain their own roles in the tenant console.

## 3. Four cell states

| State | Marker | Meaning | Action |
| --- | --- | --- | --- |
| Granted | `✓` green | Role has it and it is grantable to the role scope | Normal |
| Grantable, not granted | `○` blue | Permission can be granted to this role, but is missing | Consider granting |
| Not grantable | `—` grey | Permission cannot be granted to this role scope | No action |
| Cross-scope grant | `⚠` red | Role has it, but the role scope is outside the permission's grantable levels | Fix immediately |

## 4. Interactions

- Search by permission code, name, module or domain;
- Filter by module, domain, scope and cell state;
- Select which role columns to compare; role columns stay pinned on the right while the permission list scrolls horizontally; reset restores all;
- Click a role header to open that role's Permission Matrix editor (preset roles are read-only);
- Export the current view to CSV, with readable cell states for Excel review;
- Sort list columns; matrix rows follow the same row order;
- Refresh; an in-page notice appears when the server version changes;
- Use “only these” in the governance banner to filter cross-scope rows.

## 5. Data freshness

- The matrix carries a version; the page checks the lightweight version endpoint when it regains focus/visibility;
- If the version changed, the page asks the user to refresh instead of replacing content while reading;
- “Refresh” bypasses the cache and rebuilds the snapshot;
- The footer shows the data update time; version/cache details are available under “technical details”.

## 6. Cross-scope grants

The matrix highlights grants outside the role's grantable scope in red. Preset-role grantable metadata was calibrated by migration V36, so the normal count is **0**:

- A red cell means the grant data and permission metadata are inconsistent; review and fix it.
- Do not edit the database just to clear the warning; fix through a migration and keep the same audit trail.
- Custom-role writes still enforce `grantableTo`, so new anomalies should not be produced through the UI.

## 7. Role permission editing

- Preset roles are read-only; copy a preset role to customize.
- Concurrent edits are protected by an expected version: if another admin changed the role, the editor reports a conflict and reloads the latest grants.
- Closing an editor with unsaved changes asks for confirmation.
- Saved role grants and the catalog matrix stay consistent through the version mechanism.

## 8. Limitations

- Only global role templates are covered in this phase.
- Tenant custom roles and actual bindings are not included; a tenant-scoped matrix needs a separate design for isolation and read permissions.
- Editing is intentionally separated from the read-only catalog.

## 9. Acceptance criteria

- List and matrix keep the same row order under sorting and filtering;
- Four states and the cross-scope count are correct (zero after calibration);
- Role columns can be shown/hidden; reset restores all;
- Version changes are detected on focus/visibility;
- Concurrent role-permission saves return a conflict and reload;
- Preset roles are read-only; cross-tenant role permission reads are forbidden.
