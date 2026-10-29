# Role & Permission Page (Product Guide)

[中文](ROLE_MANAGEMENT.md)

> Status: `current` (2026-10-29)
> Page: Platform console → IAM → Role & Permission; Tenant console → People & Permissions → Role & Permission

## 1. What it is

The Role & Permission page manages platform, tenant and store roles:

- platform roles: configured with permissions grantable to PLATFORM;
- tenant roles: TENANT/STORE roles created by the tenant;
- preset roles are read-only; copy one to customize;
- the permission catalog matrix is a read-only comparison view; this page is the editing entry.

## 1.1 Platform role layers

Platform roles have three layers:

| Layer | Carrier | Editable on this page |
| --- | --- | --- |
| Platform super admin | fixed BFF role `SUPER_ADMIN` | no, break-glass |
| Platform admin | fixed BFF role `PLATFORM_ADMIN` | no, admin-account role |
| Platform operator role | IAM platform role (e.g. `platform.operator`) | yes, managed here |
| Tenant admin/roles | IAM tenant roles | tenant console |

The super admin is not an IAM role and does not appear in the catalog/matrix; platform operator roles are the configurable permission templates. Platform operator accounts get roles through platform role bindings (account-assignment UI is implemented; see 1.2).

## 1.2 Platform operators

Platform console → IAM → Platform Operators:

- lists identity `PLATFORM_OPERATOR` accounts (ID, login identifier, status);
- shows current platform-role bindings; assign/revoke supported;
- only platform operator accounts without tenant membership can receive platform roles;
- a custom platform role cannot be disabled while it has active bindings;
- preset platform roles cannot be disabled;
- super admin/platform admin are not listed and cannot be granted here.

## 2. Menus

| Console | Menu path | Visibility |
| --- | --- | --- |
| Platform | IAM → Role & Permission | Platform AdminRole (SUPER_ADMIN / PLATFORM_ADMIN) |
| Platform | IAM → Permission Catalog | Platform AdminRole (SUPER_ADMIN / PLATFORM_ADMIN) |
| Platform | IAM → Platform Operators | Platform AdminRole (SUPER_ADMIN / PLATFORM_ADMIN) |
| Tenant | People & Permissions → Role & Permission | `iam.role.view` (writes also need `iam.role.manage`) |
| Tenant | People & Permissions → Role Permission Matrix | `iam.role.view` |

Hiding a menu is UX only; direct API access is rejected with 403.

## 3. Role list

- name, code, type, scope and permission count;
- permission counts are loaded in one batch call;
- “Permission Matrix” opens the per-role editor;
- an account with only `iam.role.view` can read roles and the matrix but cannot create, copy, save or assign members;
- “Copy” creates a custom role from an existing one (requires `iam.role.manage`);
- platform additionally offers “Workbench defaults” and “Permission Catalog”;
- tenant additionally offers “Assign members”.

## 4. Role permission matrix (tenant)

The tenant Role Permission Matrix puts all visible roles (global presets + tenant custom roles) beside the permission list:

- four states: granted / grantable-not-granted / not-grantable / cross-scope grant;
- filters by module/domain/scope/state, role column selection, CSV export;
- click a role header to open its permission editor;
- the role page's “Matrix view” opens the matrix focused on that role column;
- tenant matrix excludes platform roles; platform matrix excludes tenant custom roles;
- refresh button and version check on return.

The platform Permission Catalog is a global-role-template matrix and is isolated from tenant data.

## 5. Permission editing

- only permissions grantable to the role scope are shown;
- preset roles are read-only;
- with view-only access the matrix is read-only and no Save button is shown;
- saves carry a version; conflicting edits report a conflict and reload the latest grants;
- closing with unsaved changes asks for confirmation;
- platform roles can carry business permissions marked grantable to PLATFORM; platform operators use the same permission snapshot when entering a tenant context.

## 6. Data refresh

- a Refresh button is available;
- returning to the page/window refreshes silently unless a dialog is open;
- opening a role matrix reloads permission metadata first so `grantable_levels` is current;
- role counts, role catalog and editor use the same context and never cross tenants.

## 7. FAQ

**Why can't I see Role & Permission in the tenant console?**  
The account needs `iam.role.view`; menus are permission-filtered by the backend.

**Why can I only view, not edit?**
The account needs `iam.role.manage` for writes; viewing uses `iam.role.view`.

**Why are preset roles read-only?**  
They are system baselines; copy one to create a customizable role.

**What does “permissions were changed by another admin” mean?**  
The editor detected a version conflict and reloaded the latest grants; review and save again.

**Why can role-page counts differ from the matrix?**  
They are different views. Refresh the role page; if it still differs, check for unsaved/conflicting changes.

## 8. Acceptance

- authorized platform/tenant users can open the page from the menu;
- users without `iam.role.view` do not see the menu and get 403 from APIs;
- view-only accounts open a read-only matrix without save/assign actions;
- tenant pages do not show platform-only “Workbench defaults”;
- platform role page links to the permission catalog;
- counts load in batch and refresh on focus/refresh;
- opening the editor uses current permission metadata;
- preset roles are read-only and save conflicts are reported.
