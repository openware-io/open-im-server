# 预置角色授权基线数据校准

> 状态：`current`（2026-10-29）
> 关联决策：[ADR-0018](../../20-architecture/adr/0018-preset-role-grantable-level-calibration.md)
> 关联设计：[权限目录矩阵设计](PERMISSION_MATRIX_DESIGN.md)

## 1. 结论

权限矩阵发现的 67 项“已授予但角色作用域不在 `grantable_levels` 内”的授权，根因是**预置角色种子与权限元数据不一致**，不是业务上需要保留的历史包袱。

本期通过 `V36__iam_permission_grantable_normalize.sql` 校准 `grantable_levels`：

- 不删除任何既有授权；
- 不新增任何授权；
- 只让 `grantable_levels` 覆盖预置角色已经持有的权限；
- 校准后矩阵红色异常应为 **0**。

## 2. 校准前的数据

2026-10-29 本地 Kind 数据：

| 角色 | 角色类型 | 角色作用域 | 跨作用域授权 |
| --- | --- | --- | ---: |
| `platform.operator` | PRESET | PLATFORM | 54 |
| `tenant.owner` | PRESET | TENANT | 12 |
| `store.manager` | PRESET | STORE | 1 |
| 其他预置/自定义角色 | — | — | 0 |
| 合计 | | | **67** |

## 3. 校准规则（V36）

```text
platform.operator 已授予的权限：grantable_levels 追加 PLATFORM（若缺失）
tenant.owner      已授予的权限：grantable_levels 追加 TENANT（若缺失）
store.manager     已授予的权限：grantable_levels 追加 STORE（若缺失）
```

约束：

- 只更新 `iam_permission` 的元数据，不触碰 `iam_role_permission`；
- `V1` 基线不可变，校准以新增迁移实现（G28）；
- 迁移更新 `updated_at`，矩阵缓存版本自动变化；
- 空库（V1→V36）和已有库（V35→V36）都必须通过验证。

## 4. 只读审计查询

校准后预期返回 **0 行**：

```sql
SELECT
  r.tenant_id,
  r.code AS role_code,
  r.role_type,
  r.scope_level AS role_scope,
  p.code AS permission_code,
  p.scope_level AS permission_scope,
  p.grantable_levels
FROM iam_role_permission rp
JOIN iam_role r ON r.id = rp.role_id AND r.status = 'ACTIVE'
JOIN iam_permission p ON p.id = rp.permission_id AND p.status = 'ACTIVE'
WHERE NOT FIND_IN_SET(r.scope_level, p.grantable_levels)
ORDER BY r.code, p.code;
```

若返回非 0：

1. 确认是否新迁移新增了预置授权但未同步 `grantable_levels`；
2. 禁止直接改库“消警”，必须新增迁移并补充审计；
3. 若是自定义角色越界，按数据缺陷修复并补写路径测试。

## 5. 验证与回滚

- 空库：执行全部迁移后运行上面的查询，预期 0 行；
- 已有库：执行 V36 后查询，预期 0 行；
- 矩阵页面：红色异常计数为 0；
- 回滚：V36 属数据校准，回滚需新增迁移恢复原 `grantable_levels`；仅在确认业务影响后执行，并同步回滚矩阵预期。

## 6. 后续约束

- 新增预置角色授权时，必须同步更新对应权限的 `grantable_levels`；
- 自定义角色写入继续强制 `Permission.grantableTo`；
- 任何新的跨作用域授权都视为数据缺陷，矩阵必须保持零异常。
