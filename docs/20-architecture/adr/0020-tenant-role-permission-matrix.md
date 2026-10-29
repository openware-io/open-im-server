# ADR 0020：租户角色权限矩阵

## Status / 状态

**proposed（提案中，2026-10-29）**。本 ADR 决定为租户后台提供“角色权限矩阵”：在同一视图中展示本租户可见的预置角色、自定义角色与权限授予关系；不改变 ADR-0017/0018/0019 已确立的矩阵语义、数据校准和读权限边界。

## Context / 背景

平台后台已有“权限目录矩阵”，但矩阵列只含全局角色模板（`tenant_id IS NULL`），租户管理员无法：

- 同时比较“预置角色 + 本租户自定义角色”的授权覆盖；
- 发现本租户自定义角色的授权差异；
- 在不逐个打开角色弹窗的情况下总览本租户权限。

租户角色页已有单角色编辑，但缺少聚合视图。直接复用平台矩阵会泄露平台角色或返回错误数据；复用全局版本也无法感知租户自定义角色变化。

## Decision / 决策

### 1. 独立租户矩阵端点，租户上下文必填

```text
GET /api/v1/admin/iam/permissions/matrix/tenant
GET /api/v1/admin/iam/permissions/matrix/tenant/version
```

- 必须带租户上下文；平台/无上下文调用返回 401/403；
- 角色集合 = `listAssignableByTenant(tenantId)`：全局预置角色 + 本租户自定义角色，不含平台角色；
- 权限集合为全局权限字典；
- 响应沿用 `PermissionMatrixView`，前端复用同一四态矩阵组件。

### 2. 租户级版本与缓存键

`IamCatalogVersionMapper.selectTenantVersion(tenantId)` 统计：

```text
全局预置 + 本租户自定义角色的数量 / updated_at
+ 这些角色的授权数量 / CRC 校验和 / updated_at
+ 权限字典数量 / updated_at
```

- 缓存键为 `tenant:{tenantId}:{version}`，避免不同租户版本字符串碰撞；
- 复用 Redis + 本地缓存、TTL、single-flight、Micrometer 指标；
- 租户版本与全局版本独立，自定义角色变更不会影响平台矩阵缓存，反之亦然；
- 支持 `ETag` / `If-None-Match`，`refresh=true` 强制重建。

### 3. 权限与菜单

- 读接口要求 `iam.role.view`；写操作仍在角色页，要求 `iam.role.manage`；
- 租户菜单新增“角色权限矩阵”（`/admin/roles/matrix`，`required_permission=iam.role.view`）；
- 角色页增加“角色权限矩阵”入口；
- 平台后台继续使用全局角色模板矩阵，两者互不复用数据。

### 4. 前端复用与边界

- `permissions.vue` 根据路由 meta `tenantMatrix` 选择租户/平台 API 与角色页深链；
- 标题/说明/角色数量按租户模式切换；
- 角色表头深链到 `TenantRoles`（租户）或 `IamRoles`（平台）；
- 导出、筛选、角色列选择、四态、刷新、版本提示保持同一套逻辑。

## Consequences / 后果

正向：

- 租户管理员可一次看到本租户所有可管理角色的权限覆盖；
- 平台/租户矩阵数据隔离，缓存键与版本独立；
- 复用既有矩阵组件和四态语义，不引入第二套展示逻辑。

代价：

- 租户矩阵响应包含租户自定义角色，需严格依赖租户上下文；
- 每个活跃租户可能有一份矩阵缓存（TTL 内），需关注 Redis 容量；
- 租户自定义角色变更会使租户缓存失效，但不会影响平台缓存。

## Alternatives considered / 备选方案

1. **复用平台矩阵端点，按上下文切换数据**：拒绝。平台端点当前无租户上下文语义，切换会破坏既有平台页面和缓存。
2. **前端用现有每角色接口拼矩阵**：拒绝。N+1、跨快照不一致，且无法安全获取租户全部授权。
3. **不做租户聚合，只保留单角色编辑**：拒绝。租户管理员缺少跨角色对照能力。
4. **使用全局版本刷新租户矩阵**：拒绝。全局版本不包含租户自定义角色。

## References / 参考

- ADR-0017：权限目录矩阵
- ADR-0018：预置角色授权基线校准
- ADR-0019：角色菜单与读权限边界
- `docs/40-domain/identity/PERMISSION_MATRIX_DESIGN.md`
- `docs/10-product/ROLE_MANAGEMENT.md`
- `V11__iam_role_matrix_menu.sql`
