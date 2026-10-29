# ADR 0018：预置角色授权基线的数据校准

## Status / 状态

**proposed（提案中，2026-10-29）**。本 ADR 处理权限矩阵发现的 67 项“预置角色已授予、但角色作用域不在权限 `grantable_levels` 内”的授权，决定通过**数据校准**使 `grantable_levels` 与既有预置授权一致，而不是接受“预置角色例外”的长期状态。

## Context / 背景

权限目录矩阵上线后统计到 67 项“已授予但不可授予”的单元格：

| 角色 | 数量 | 角色作用域 | 典型权限 |
| --- | ---: | --- | --- |
| `platform.operator` | 54 | PLATFORM | `tenant.store.manage`、`order.*`、`inventory.*`、`payment.*` 等经营权限 |
| `tenant.owner` | 12 | TENANT | `hotel.booking.*`、`member.view/manage`、`wallet.*`、`points.*` 等 |
| `store.manager` | 1 | STORE | `tenant.store.manage` |

两条规则发生冲突：

1. **`grantable_levels` 是授予规则的唯一事实**：`Permission.grantableTo(roleScopeLevel)` 只允许角色被授予白名单内的权限；
2. **预置角色种子是既有授权**：V1 3.10/3.11 把全部经营权限授予 `platform.operator` / `tenant.owner`，并把 `tenant.store.manage` 授予 `store.manager`，但没有同步更新这些权限的 `grantable_levels`。

这不是“历史数据必须保留”的问题，而是**授权元数据与授权事实不一致**。正确的规范做法是让两者一致，而不是为不一致增加例外规则。

## Decision / 决策

### 1. 用迁移校准 `grantable_levels`，不删除既有授权

新增 `V36__iam_permission_grantable_normalize.sql`：

- 对 `platform.operator` 已授予且缺少 `PLATFORM` 的权限，追加 `PLATFORM`；
- 对 `tenant.owner` 已授予且缺少 `TENANT` 的权限，追加 `TENANT`；
- 对 `store.manager` 已授予且缺少 `STORE` 的权限，追加 `STORE`（当前为 `tenant.store.manage`）；
- 只更新 `grantable_levels`、`updated_by`、`updated_at`，不新增/删除 `iam_role_permission` 行；
- V1 保持不可变，迁移只加不改（G28）。

校准后，所有预置角色授权都满足 `Permission.grantableTo`，矩阵恢复正常四态，不再需要“预置基线例外”。

### 2. 语义依据

- PLATFORM 角色可以跨租户/门店上下文操作，平台运营需要经营权限；
- TENANT 角色覆盖租户内全部门店，租户老板需要租户内经营权限；
- STORE 角色管理门店目录/运营，`tenant.store.manage` 需要可授予 STORE；
- `scope_level` 仍描述权限的数据作用域，`grantable_levels` 描述“可授予给哪些角色作用域”，两者可以不同；本次只校准后者。

### 3. 矩阵恢复四态

| 状态 | 条件 | 展示 |
| --- | --- | --- |
| granted | 已授予且可授予 | 绿色 ✓ |
| grantable | 可授予未授予 | 蓝色 ○ |
| forbidden | 不可授予 | 灰色 — |
| anomaly | 已授予但不可授予 | 红色 ⚠，必须修复 |

矩阵不再区分“预置基线/自定义越界”；任何红色单元格都表示授权数据不一致。

### 4. 写入与后续约束

- 自定义角色写入继续强制 `grantableTo`；正常 UI 不应产生 anomaly；
- 预置角色仍只读；
- 后续新增预置角色授权时，必须在同一迁移中同步 `grantable_levels`，否则矩阵会立即报红；
- 空库（V1→V36）和已有库（V35→V36）都必须通过 Flyway 迁移验证。

## Consequences / 后果

正向：

- 授权事实与授权元数据一致，矩阵告警归零；
- 不再需要为预置角色维护例外规则；
- 自定义角色的可授予范围仍然受 `grantable_levels` 约束；
- 后续任何越界授权都会被矩阵准确识别为数据缺陷。

代价与风险：

- 部分权限的 `grantable_levels` 被放宽（增加 PLATFORM/TENANT/STORE），意味着同作用域的自定义角色也可以被授予这些权限；这是与预置角色能力一致的预期结果，但需要产品确认可接受；
- 数据迁移不可回滚到“元数据不一致”状态；如需回滚，必须另写迁移并评估影响；
- 迁移会更新 `iam_permission.updated_at`，导致矩阵缓存版本变化，属预期。

## Alternatives considered / 备选方案

1. **保留 67 项并标记为“预置基线例外”**：拒绝。会把元数据缺陷制度化，矩阵无法用统一规则判断异常。
2. **删除 67 项授权**：拒绝。会回退平台运营/租户老板/店长的既有业务能力。
3. **只删 `store.manager → tenant.store.manage`**：拒绝。该权限被门店目录等接口使用，删除会影响店长日常操作；应通过 `grantable_levels` 校准支持 STORE。
4. **新增“预置角色超级权限”表/标志，绕过 grantableTo**：拒绝。引入第二套授权规则，复杂度和长期维护成本更高；当前通过元数据校准即可解决。

## References / 参考

- ADR-0017：权限目录矩阵单元格语义、范围边界与授权并发控制
- `docs/40-domain/identity/PERMISSION_MATRIX_DESIGN.md`
- `docs/40-domain/identity/PERMISSION_DATA_CALIBRATION.md`
- `V36__iam_permission_grantable_normalize.sql`
