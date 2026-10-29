# ADR 0021：平台管理员角色分层与超级管理员保护

## Status / 状态

**proposed（提案中，2026-10-29）**。本 ADR 明确平台超级管理员、平台运营角色、租户管理员三层角色模型，并约束平台角色权限管理的边界；不改变 ADR-0019/0020 的租户角色与矩阵设计。

## Context / 背景

租户角色权限管理已闭环（角色页、权限矩阵、读写权限、租户矩阵）。平台侧仍存在三个容易混淆的概念：

1. `cons_admin_account.role = SUPER_ADMIN`：后台 BFF 的固定管理员角色；
2. `cons_admin_account.role = PLATFORM_ADMIN`：后台 BFF 的平台管理员角色；
3. `iam_role.code = platform.operator`：IAM 平台运营角色，拥有 `platform.*` 权限。

当前实现中：

- 平台超级管理员不是 `iam_role` 行，不在角色目录/权限矩阵中，也不能通过角色页编辑；
- 平台运营角色 `platform.operator` 出现在 IAM 平台角色目录，可通过平台“角色与权限”页编辑权限；
- 平台运营人员账号（`idt_account.account_type=PLATFORM_OPERATOR`）与平台 IAM 角色的绑定接口存在，但没有完整的管理页面。

如果不把三层讲清楚，会误以为“平台超级管理员也应该能在 IAM 里编辑权限”，从而把 break-glass 账号变成可被误配置、可被锁死的普通角色。

## Decision / 决策

### 1. 三层角色模型

| 层 | 载体 | 是否 IAM 可编辑 | 职责 |
| --- | --- | --- | --- |
| 平台超级管理员 | `cons_admin_account.role=SUPER_ADMIN`（BFF AdminRole） | **否**，固定/break-glass | 平台最高权限、初始化、应急恢复 |
| 平台管理员 | `cons_admin_account.role=PLATFORM_ADMIN`（BFF AdminRole） | 后台账号角色，不进入 IAM 角色目录 | 平台日常管理（受 BFF 路由/角色约束） |
| 平台运营角色 | `iam_role`（如 `platform.operator` 或自定义 PLATFORM 角色） | **是** | 可授予平台运营人员的平台权限模板 |
| 租户管理员/角色 | `iam_role`（tenant.owner/store.*/租户自定义） | 按 ADR-0019 规则 | 租户内权限 |

### 2. 超级管理员保护

- `SUPER_ADMIN` 不写入 `iam_role`，不进入权限矩阵，不提供 IAM 授权编辑；
- 其权限是平台 BFF 的固定能力集合，变更必须走代码/发布与审计，而不是运营界面；
- 任何试图将 `SUPER_ADMIN` 作为租户/平台 IAM 角色授予的接口都应拒绝（按账号类型/AdminRole 校验）；
- 超级管理员操作必须留审计（登录、平台角色变更、平台账号变更）。

### 3. 平台角色权限管理

平台角色权限管理**存在且应保留**，入口为平台后台：

```text
平台后台 → 权限管理 → 角色与权限
平台后台 → 权限管理 → 权限目录
```

- 管理对象是 IAM 平台角色（`platform.operator` 与自定义 PLATFORM 角色）；
- 读写权限仍遵循 ADR-0019：查看 `platform.iam.role.view`，写入 `platform.iam.role.manage`；
- 平台超级管理员在无租户上下文的平台工作区执行这些操作，由 BFF AdminRole 边界保护；
- 平台运营人员要获得这些权限，需通过平台角色绑定（`/admin/iam/platform/role-bindings`）授予平台角色。

### 4. 待补齐的产品能力

以下不属于“超级管理员是否可编辑”，而是平台运营角色管理的完整业务：

1. 平台运营人员账号列表与平台角色授予/撤销 UI；
2. 平台角色权限变更审计查询；
3. 平台角色删除/停用前的绑定检查（当前无删除接口，保持只增不删）。

## Consequences / 后果

正向：

- 明确超级管理员不可通过界面编辑，避免 break-glass 被误配；
- 平台运营角色权限仍有正式管理入口；
- 平台/租户角色模型不再混用。

代价/约束：

- 平台超级管理员权限调整需要发布，不是运营配置；
- 平台运营账号授权 UI 尚未完成，当前只能通过接口/后续页面；
- 平台菜单仍由 AdminRole 控制，`required_permission` 为后续权限化元数据。

## Alternatives considered / 备选方案

1. **把 SUPER_ADMIN 建成 IAM 角色并允许编辑**：拒绝。会引入自我锁死、权限漂移，且与 BFF AdminRole 双写不一致。
2. **删除平台角色页，只保留 BFF 管理员角色**：拒绝。平台运营权限需要可配置模板。
3. **把平台运营角色和租户角色混在同一权限矩阵**：拒绝。作用域、菜单、上下文和缓存都不同。

## References / 参考

- ADR-0019：角色菜单与读权限边界
- ADR-0020：租户角色权限矩阵
- `docs/10-product/ROLE_MANAGEMENT.md`
- `PlatformRoleBindingApplicationService`
- `AdminRole`
