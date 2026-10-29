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

## 2. Menus

| Console | Menu path | Visibility |
| --- | --- | --- |
| Platform | IAM → Role & Permission | Platform AdminRole (SUPER_ADMIN / PLATFORM_ADMIN) |
| Platform | IAM → Permission Catalog | Platform AdminRole (SUPER_ADMIN / PLATFORM_ADMIN) |
| Tenant | People & Permissions → Role & Permission | `iam.role.manage` |

Hiding a menu is UX only; direct API access is rejected with 403.

## 3. Role list

- name, code, type, scope and permission count;
- permission counts are loaded in one batch call;
- “Permission Matrix” opens the per-role editor;
- “Copy” creates a custom role from an existing one;
- platform additionally offers “Workbench defaults” and “Permission Catalog”;
- tenant additionally offers “Assign members”.

## 4. Permission editing

- only permissions grantable to the role scope are shown;
- preset roles are read-only;
- saves carry a version; conflicting edits report a conflict and reload the latest grants;
- closing with unsaved changes asks for confirmation;
- platform roles can carry business permissions marked grantable to PLATFORM; platform operators use the same permission snapshot when entering a tenant context.

## 5. Data refresh

- a Refresh button is available;
- returning to the page/window refreshes silently unless a dialog is open;
- opening a role matrix reloads permission metadata first so `grantable_levels` is current;
- role counts, role catalog and editor use the same context and never cross tenants.

## 6. FAQ

**Why can't I see Role & Permission in the tenant console?**  
The account needs `iam.role.manage`; menus are permission-filtered by the backend.

**Why are preset roles read-only?**  
They are system baselines; copy one to create a customizable role.

**What does “permissions were changed by another admin” mean?**  
The editor detected a version conflict and reloaded the latest grants; review and save again.

**Why can role-page counts differ from the matrix?**  
They are different views. Refresh the role page; if it still differs, check for unsaved/conflicting changes.

## 7. Acceptance

- authorized platform/tenant users can open the page from the menu;
- users without `iam.role.manage` do not see the menu and get 403 from APIs;
- tenant pages do not show platform-only “Workbench defaults”;
- platform role page links to the permission catalog;
- counts load in batch and refresh on focus/refresh;
- opening the editor uses current permission metadata;
- preset roles are read-only and save conflicts are reported.
