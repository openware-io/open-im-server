# ADR 0019：角色管理菜单与读权限边界

## Status / 状态

**proposed（提案中，2026-10-29）**。本 ADR 处理角色页面可发现性、平台/租户菜单口径、角色页数据刷新和角色读接口权限守卫；不改变 ADR-0014 / ADR-0015 / ADR-0017 / ADR-0018 已确立的角色授权模型。

## Context / 背景

权限矩阵与 V36 元数据校准完成后，角色页面暴露出以下问题：

1. **租户角色页是孤儿入口**：前端已有 `/admin/roles` 路由和 `roleAdmin.vue`，但 `iam_menu` 没有 `TENANT` 作用域的对应菜单，租户管理员只能通过 URL/深链进入；
2. **平台菜单与页面口径不一致**：菜单叫“角色”“权限”，页面标题是“角色与权限”“权限目录”；
3. **平台专属按钮泄漏到租户页**：`roleAdmin.vue` 的“工作台默认值”按钮无条件渲染，但 `/admin/iam/role-profiles` 是 `PLATFORM` 路由；
4. **菜单无权限门禁**：平台角色/权限菜单 `required_permission` 为空，前端 `MENU_ITEM_PERMISSIONS` 也未登记；平台/租户管理入口没有明确的权限声明；
5. **角色页数据只在挂载时加载**：V36 或其他人修改角色权限后，权限数量、可授予权限列表可能长期陈旧；
6. **角色列表权限计数 N+1**：每个角色调用一次 `/roles/{id}/permissions`。

这些问题的共同点是：角色页面的**入口、权限边界和数据生命周期**没有与权限目录矩阵的治理模型对齐。

## Decision / 决策

### 1. 菜单数据由迁移修正

新增 `platform-admin-service` 迁移 `V8__iam_role_menu_entries.sql`（租户菜单/平台命名），
以及 `V9__iam_platform_menu_permission_scope.sql`（平台菜单改用 `platform.iam.role.manage`）：

- 在租户 `人员与权限`（parent 102）下插入 `角色与权限`：
  - path `/admin/roles`
  - required_permission `iam.role.manage`
  - sort 5（在运营人员/脱敏权限之前）
- 平台菜单改名：
  - `iam.role` → `角色与权限`
  - `iam.permission` → `权限目录`
- 平台菜单 `iam.role` / `iam.permission` 设置 `required_permission='platform.iam.role.manage'`；
- 租户角色菜单使用 `iam.role.manage`；
- 平台菜单剪枝按 `AdminRole`（SUPER_ADMIN / PLATFORM_ADMIN）执行，租户菜单按权限快照执行；菜单隐藏只是 UX，API 仍然守住。

不新增“角色-菜单”表；菜单可见性继续由 `iam_menu.required_permission` + IAM 权限快照驱动。

### 2. 前端角色页按上下文收敛

`roleAdmin.vue` 由平台/租户共用：

- “工作台默认值”只在 `isPlatform` 时渲染；
- 平台角色页增加“查看权限目录”入口；
- 平台角色文案改为“按 PLATFORM 可授予范围配置权限”，不再声称“平台角色不能进入租户业务”；
- 新建角色提示改为“只能选择已标记为可授予 PLATFORM 的权限”。

### 3. 角色读接口必须经过服务端权限守卫

角色页面涉及的读接口统一通过 `requireRoleRead()` 校验：

- 无租户上下文（平台后台工作区）：按平台 AdminRole/入口放行平台级只读；
- 平台上下文：要求 `platform.iam.role.manage`；
- 租户上下文：要求 `iam.role.manage`。

- `/roles/catalog`
- `/roles/assignable`
- `/roles/permission-counts`
- `/roles/{id}/permissions`
- `/roles/{id}/permissions/snapshot`
- `/permissions`
- `/permissions/matrix`
- `/permissions/matrix/version`

菜单隐藏只是 UX；服务端 403 才是边界。

### 4. 角色页数据刷新与批量计数

- 角色列表权限计数改为 `GET /roles/permission-counts`，一次批量读取；
- 角色页增加刷新按钮；页面重新可见/聚焦且无弹窗时静默刷新；
- 打开“权限矩阵”前重新拉取 `listPermissions()`，确保 `grantable_levels` 是最新的；
- 不复用全局矩阵版本判断租户角色页：矩阵版本只覆盖全局角色，租户自定义角色变更需要角色页自己的刷新策略。

## Consequences / 后果

正向：

- 租户角色页可发现、可进入；
- 菜单名称、权限门禁、页面能力一致；
- 角色页数据不会长期陈旧；
- 角色列表去掉 N+1；
- 菜单和 API 都有明确权限边界。

代价：

- 租户角色/权限菜单对无 `iam.role.manage` 的用户隐藏；平台菜单由平台 AdminRole 控制；
- 角色页在聚焦时会额外请求一次目录/计数，低频页面可接受；
- 读接口守卫按上下文区分：租户要求 `iam.role.manage`，平台上下文要求 `platform.iam.role.manage`，无上下文平台只读由平台入口/会话控制。

## Alternatives considered / 备选方案

1. **只补租户菜单，不改平台菜单**：拒绝。平台菜单与页面标题继续不一致。
2. **在前端固定拼租户菜单项**：拒绝。菜单主体由后端下发，前端拼固定项会绕过 `required_permission`。
3. **新增 `iam.role.view` 读权限**：暂不采用。当前角色管理页面本质是管理页；如产品需要只读查看者，再单独立项拆分读/写权限。
4. **复用全局矩阵版本刷新角色页**：拒绝。全局版本不包含租户自定义角色，租户角色页会漏刷新。
5. **保留 N+1 计数**：拒绝。角色数量增长后页面线性变慢，且多次查询不是同一快照。

## References / 参考

- ADR-0017：权限目录矩阵
- ADR-0018：预置角色授权基线的数据校准
- `docs/40-domain/identity/PERMISSION_MATRIX_DESIGN.md`
- `docs/10-product/ROLE_MANAGEMENT.md`
- `V8__iam_role_menu_entries.sql`
- `V9__iam_platform_menu_permission_scope.sql`
