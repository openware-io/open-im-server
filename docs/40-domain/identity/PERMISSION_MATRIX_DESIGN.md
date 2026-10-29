# 权限目录矩阵设计与实现（一期）

> 状态：与 ADR-0017 同步；一期范围 = 平台后台“权限目录”页的全局角色模板矩阵。
> 目标：让矩阵能发现“越界授权”，让角色权限编辑不丢并发修改，让打开中的页面能发现数据过期。

## 1. 问题与目标

### 1.1 现状问题

- 矩阵只有“授予/未授予”两态：`—` 既可能是“可授予但未授予”，也可能是“不可授予”，甚至“已授予但越界”被画成正常 `✓`；
- 目录页只展示全局角色模板，但页面没有明确边界，容易被误读为全平台实际授权；
- 角色权限编辑整体替换且无版本校验，两个管理员并发保存会静默覆盖；
- 目录页只在挂载时加载一次，其他管理员修改后页面不会发现。

### 1.2 一期目标

1. 四态矩阵 + 越界授权计数/筛选；
2. 明确“全局角色模板”范围，不含租户自定义角色与实际绑定；
3. 角色权限编辑的乐观并发控制（409 冲突、刷新后重试）；
4. 页面聚焦/可见性恢复时做轻量版本校验，发现过期时提示刷新；
5. 不新增数据库迁移，不改变既有权限/角色/绑定表结构。

### 1.3 非目标

- 不做租户自定义角色/实际绑定的跨角色矩阵（需独立 ADR 与租户隔离设计）；
- 不做自动轮询、SSE/WebSocket 实时推送；
- 不在本页直接写权限（写入口仍在“角色与权限”编辑弹窗）。

## 2. 四态模型

服务端返回：

```json
{
  "permissions": [ ... ],
  "roles": [{
    "id": 1,
    "code": "platform.operator",
    "scopeLevel": "PLATFORM",
    "permissionCodes": ["tenant.tenant.manage", "..."],
    "grantablePermissionCodes": ["..."]
  }],
  "version": "...",
  "generatedAt": "...",
  "cached": true
}
```

- `permissionCodes`：该角色已有授权；
- `grantablePermissionCodes`：`permissions.filter(p -> p.grantableTo(role.scopeLevel()))` 的编码集合，由服务端领域规则计算。

前端映射：

| 状态 | granted | grantable | 展示 | 含义 |
| --- | --- | --- | --- | --- |
| granted | true | true | `✓` 绿色 | 正常授权 |
| anomaly | true | false | `⚠` 红色 | 已授予但不可授予，需立即治理 |
| grantable | false | true | `○` 蓝色 | 可补充授予 |
| forbidden | false | false | `—` 灰色 | 角色作用域不可授予 |

> 前端不得用 `permission.grantableLevels` 自行计算可授予，避免复制授权规则。
> 预置角色授权元数据已由 V36 校准，矩阵异常应为 0；见 `PERMISSION_DATA_CALIBRATION.md`。

## 3. API 与数据流

### 3.1 读取

```text
GET /api/v1/admin/iam/permissions/matrix?refresh=false
GET /api/v1/admin/iam/permissions/matrix/version
GET /api/v1/admin/iam/roles/{roleId}/permissions/snapshot
```

- matrix：同一事务内读取权限、全局角色、角色授权，返回缓存/实时快照；响应带 `ETag`（目录版本），支持
  `If-None-Match` → 304（`refresh=true` 时忽略条件，强制重建）；
- version：只查目录版本，页面聚焦时使用；
- role snapshot：返回角色已授予编码 + 该角色权限版本，编辑弹窗使用。

### 3.2 租户角色权限矩阵

```text
GET /api/v1/admin/iam/permissions/matrix/tenant
GET /api/v1/admin/iam/permissions/matrix/tenant/version
```

- 必须持租户上下文，要求 `iam.role.view`；
- 角色集合 = 全局预置角色 + 本租户自定义角色，不含平台角色；
- 版本 = 这些角色的数量/updated_at + 授权数量/CRC/updated_at + 权限字典数量/updated_at；
- 缓存键 `tenant:{tenantId}:{version}`，与平台矩阵缓存隔离；
- 前端复用矩阵页面，路由 meta `tenantMatrix` 切换 API、文案和角色深链；
- 角色页提供“角色权限矩阵”入口；租户菜单同步新增矩阵入口（ADR-0020）。

### 3.3 角色权限写入

```text
POST /api/v1/admin/iam/roles/{roleId}/permissions        { permissionIds, expectedVersion? }
POST /api/v1/admin/iam/roles/{roleId}/permissions/toggle { permissionCode, granted, expectedVersion? }
```

写事务顺序：

```text
BEGIN
  SELECT ... FROM iam_role WHERE id = ? FOR UPDATE   -- 串行化同一角色的写
  assert 角色存在/active
  assert 上下文/租户/作用域可写
  if expectedVersion != null:
      current = rolePermissionVersion(roleId)
      if current != expectedVersion: 409 PERMISSION_VERSION_CONFLICT
  validate permission ids 存在 + grantableTo(role.scopeLevel)
  replaceRolePermissions / grant / revoke
  evict permission snapshots
  audit
COMMIT
```

读取边界：

- 平台上下文可读全局角色；
- 租户上下文可读全局预置角色 + 本租户自定义角色；
- 禁止跨租户按 `roleId` 读取权限集合，`ROLE_TENANT_MISMATCH` 403。

版本组成：

```text
角色 updated_at + 授权数量 + 授权集合 CRC32 校验和 + 授权最后更新时间
```

- 行锁保证“版本校验 → 替换”之间没有同一角色的并发写穿透；
- 不传 `expectedVersion` 保留旧调用方兼容语义；新 UI 必须传；
- 冲突错误码 `PERMISSION_VERSION_CONFLICT`（HTTP 409）。

## 4. 缓存与新鲜度

### 4.1 矩阵缓存

沿用现有 `PermissionCatalogCache`：

- Redis 一级 + 本地兜底，TTL 10 分钟；
- 键 = `iam:catalog:matrix:{version}`；
- 版本由 IAM 事实派生（全局角色/授权/权限字典的数量、CRC 校验和、最后更新时间）；
- 任一侧写入 → 版本变化 → 新键；多实例无需广播；
- `refresh=true` 跳过缓存读取；
- 同 JVM 内同版本并发未命中会合并为一次重建（in-flight single-flight），避免缓存击穿；跨实例仍由 Redis 共享结果，极端并发下各实例最多重建一次，可接受；
- 角色行锁与版本查询只用于写路径，不进入缓存；
- Micrometer 计数器 `permission.catalog.matrix.cache`（tag `result=hit|miss|join|rebuild|refresh`）用于观察命中/重建情况，由部署侧指标系统采集（当前服务仅暴露 health/info，未开启 metrics 端点）。

### 4.2 页面版本检查

```text
onMounted / window.focus / document.visibilitychange(visible)
  → GET /permissions/matrix/version
  → 与当前 meta.version 比较
  → 不同：显示“数据已更新”提示，用户点击刷新
```

不自动替换当前矩阵，避免打断阅读；刷新时 `refresh=true` 强制重建。

## 5. 前端交互

- 图例四态 + 角色列头 tooltip（名称/编码/角色类型）；
- 状态筛选：全部 / 只看越界授权 / 只看未授予 / 只看可补充；模块/业务域/作用域筛选；
- 角色列选择、固定在右侧，未选择角色列时显示提示；
- 点击角色列头深链到角色权限矩阵（`/admin/iam/roles?role=code`，打开后清理 query）；
- 导出当前筛选结果 + 当前角色列为 CSV（中文状态、BOM、防公式注入）；
- 页脚显示权限数、全局角色数、数据更新时间、越界授权计数；
- 技术细节（version/cached）收进 tooltip，不展示给业务用户；
- 单元格 `title/aria-label` 解释状态；
- 保留“列表/矩阵共用同一行数组”的排序一致性。

## 6. 错误与兼容

| 场景 | 行为 |
| --- | --- |
| 版本冲突 409 | 前端提示“权限已被其他管理员修改”，重新加载快照，保留弹窗 |
| 旧调用方无 expectedVersion | 继续无条件写入（兼容期），不阻断现有集成 |
| 矩阵无角色 | 表格正常显示权限列，矩阵列为空；页脚角色数 0 |
| 缓存/Redis 不可用 | 本地兜底；版本查询仍走 DB |
| 页面后台/重新聚焦 | 轻量版本检查失败静默，不阻塞主数据 |

## 7. 测试与门禁

后端：

- 四态可授予集合：`PermissionCatalogConverterTest`（角色作用域过滤、排序）；
- CAS：`IamAuthorizationApplicationServiceTest`（冲突/匹配/旧调用/快照版本）；
- 控制器：`IamControllerScopeTest`（矩阵/版本端点委托、越界拒绝不写入）；
- SQL 版本查询：需在集成/手工验证中覆盖（`BIT_XOR/CRC32` 为 MySQL 特性）；
- 全量 `platform-tenant-service` 单测。

前端：

- `permissionMatrix.test.js`：四态、计数、状态筛选、角色 rank、排序；
- 全量 Vitest + `vite build`。

文档/门禁：

- 本设计 + ADR-0017 + 服务 README + OpenAPI 快照；
- `validate-docs-impact` / `documentation-*` / `java-*` / `controller-*` / `tenant-sql` / `text-encoding`；
- G55：本矩阵为平台只读字典，沿用 `/permissions` 口径；写接口继续走上下文/作用域校验。

## 8. 发布与回滚

- 无数据库迁移；先发布 `platform-tenant-service`，再发布 `saas-admin`；
- 旧前端不带 `expectedVersion` 仍可保存，但无并发保护；新前端使用快照版本；
- 回滚后端：恢复旧镜像即可；旧前端不受新字段影响（新增字段向后兼容）；
- 回滚前端：矩阵退回三态前样式，缓存/版本字段仍可被忽略。

## 9. 已知限制 / 后续

- 一期矩阵不含租户自定义角色与实际绑定；
- 权限默认排序已改为 `module + code`（跨环境稳定），角色列使用预置 rank + 编码；
- **预置角色授权元数据由 V36 校准**：platform.operator 追加 PLATFORM、tenant.owner 追加 TENANT、store.manager 追加 STORE；不删除授权，矩阵异常应为 0；数据校准与处置边界见 `PERMISSION_DATA_CALIBRATION.md`；
- 后续可补：ETag/304、single-flight 重建、缓存指标、矩阵导出/审计、租户级矩阵；
- 预置角色授权由 V36 校准为合法；本页不再维护“预置基线例外”，任何红色异常都需修复。
