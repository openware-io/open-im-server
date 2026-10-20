# 架构决策记录（ADR）

[中文](README.md) | [English](README.en.md)

> 规则入口：[ADR 规范](../../80-development/standards/44_ARCHITECTURE_DECISION_RECORDS.md)  
> 模板：[0000-template.md](0000-template.md)  
> 原则：一个 ADR 一个决策；已接受记录不重写；撤回/替代记录仍保留。

## 索引

| 编号 | 决策 | 状态 | 日期 |
| --- | --- | --- | --- |
| 0007 | [`version` 具名例外](0007-version-named-exceptions.md) | withdrawn | 2026-10-18 |

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
