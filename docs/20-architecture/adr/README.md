# 架构决策记录（ADR）

[中文](README.md) | [English](README.en.md)

> 规则入口：[ADR 规范](../../80-development/standards/02_ARCHITECTURE_DECISION_RECORDS.md)
> 模板：[0000-template.md](0000-template.md)  
> 原则：一个 ADR 一个决策；已接受记录不重写；撤回/替代记录仍保留。

## 索引

| 编号 | 决策 | 状态 | 日期 |
| --- | --- | --- | --- |
| 0007 | [`version` 具名例外](0007-version-named-exceptions.md) | withdrawn | 2026-10-18 |
| 0008 | [酒店域边界与 MVP 归属](0008-hotel-domain-boundary.md) | accepted | 2026-10-23 |
| 0009 | [C 端统一订单视图](0009-user-order-index.md) | proposed | 2026-10-22 |
| 0010 | [采销库存作为公共支撑域](0010-stock-supporting-domain.md) | proposed | 2026-10-22 |
| 0011 | [库存上下文映射与依赖规则](0011-inventory-context-map.md) | proposed | 2026-10-22 |
| 0012 | [订单作为共享交易内核](0012-order-kernel.md) | proposed | 2026-10-22 |
| 0013 | [角色工作台作为平台能力](0013-role-workbench.md) | proposed | 2026-10-22 |
| 0014 | [平台、租户、门店工作区与角色治理](0014-workspace-role-model.md) | accepted | 2026-10-23 |
| 0015 | [IM 与 SaaS 身份联邦、租户成员与授权模型](0015-identity-federation-tenant-iam.md) | accepted | 2026-10-23 |
| 0016 | [组织管理的归属与一期形态](0016-organization-management-boundary.md) | proposed | 2026-10-28 |
| 0017 | [权限目录矩阵的单元格语义、范围边界与授权并发控制](0017-permission-matrix-semantics-and-concurrency.md) | proposed | 2026-10-29 |
| 0018 | [预置角色授权基线的数据校准](0018-preset-role-grantable-level-calibration.md) | proposed | 2026-10-29 |
| 0019 | [角色管理菜单与读权限边界](0019-role-menu-and-read-permission-boundary.md) | proposed | 2026-10-29 |

## 状态说明

```text
proposed     提案中
accepted     已接受
rejected     已拒绝
deprecated   已弃用
superseded   已被新 ADR 替代
withdrawn    已撤回
```

## 新增 ADR

1. 复制 [0000-template.md](0000-template.md)；
2. 使用下一个未使用编号，命名为 `NNNN-kebab-case.md`；
3. 填写 `Status / 状态`、`Context / 背景`、`Decision / 决策`、`Consequences / 后果`；
4. 将新 ADR 登记到上表；
5. 运行 `scripts/validate/validate-architecture-decisions.ps1`。

## 边界

- 本目录记录**历史决策**，不是现行规则；
- 现行技术约束放 `docs/80-development/standards/`；
- 被替代的 ADR 标注 `superseded-by`，不删除、不改写成新结论。