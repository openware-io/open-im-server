# 架构决策记录（ADR）规范

[中文](02_ARCHITECTURE_DECISION_RECORDS.md) | [English](02_ARCHITECTURE_DECISION_RECORDS.en.md)

> 状态：`current`（`develop/2.3.0`）  
> 适用范围：`docs/20-architecture/adr/` 下的架构决策记录。

## 目标与适用范围

本规范约束 **架构决策记录（ADR）** 的编号、状态、结构、索引和归档方式，确保重要技术决策可追溯、不可篡改、可被后来者理解。

适用对象：

- 影响多个服务或长期演进的架构选择；
- 认证、数据一致性、服务拆分、持久化、消息、部署等关键技术决策；
- 已撤回或已被替代的历史决策。

不适用：

- 单个需求的设计稿；
- 接口字段说明；
- 会议纪要；
- 代码规范（应写入对应的 `standards/` 文档）。

## 术语与前置假设

- **ADR**：Architecture Decision Record，一次架构决策的短记录。
- **决策记录**：记录当时的选择、背景、备选方案和后果，不随代码演进而改写。
- **状态**：`proposed`、`accepted`、`rejected`、`deprecated`、`superseded`、`withdrawn`。
- **索引**：`docs/20-architecture/adr/README.md`，列出全部 ADR 及状态。
- **不可变**：已接受的 ADR 不直接改写结论；被替代时新增 ADR 并标注 `superseded`。

## 原则与约束

1. **一个 ADR 只记录一个决策**，不把多个无关决策塞进同一篇。
2. 文件命名固定为 `NNNN-kebab-case.md`，四位编号，编号不复用；模板为 `0000-template.md`。
3. 每篇 ADR 必须包含四个一级章节：
   - `Status / 状态`
   - `Context / 背景`
   - `Decision / 决策`
   - `Consequences / 后果`
4. 状态必须使用上述英文枚举之一；历史 ADR 保留原文，不移动到 `99-archive/`。
5. 已撤回/被替代的 ADR 仍保留在 ADR 目录，因为“曾经这样决定过”本身就是架构历史。
6. 新增 ADR 必须同时更新 `adr/README.md` 索引；不得出现未登记文件。
7. ADR 记录**决策历史**，不是现行规则；现行约束写入 `standards/`，由门禁执行。
8. 被替代的 ADR 必须写明 `superseded-by`；不得直接改写成新决策。

## 模板或示例

模板：

[0000-template.md](../../20-architecture/adr/0000-template.md)

最小结构：

```markdown
# ADR 0008: 选择 Outbox 而不是双写

## Status / 状态

accepted

## Context / 背景

消息落库与 MQ 投递需要保证一致性。

## Decision / 决策

业务写入与 Outbox 记录在同一事务内完成。

## Consequences / 后果

- 需要 Relay 与幂等消费；
- 避免消息丢失；
- 增加 Outbox 表。
```

## 门禁与验证

G47 `validate-architecture-decisions.ps1` 检查：

- ADR 目录、索引和模板存在；
- 文件名符合 `NNNN-kebab-case.md`；
- 每篇 ADR 具备四个必需章节；
- 状态值在允许枚举内；
- 每篇 ADR 都登记在索引中；
- 编号唯一。

## 可验证的验收标准

- `validate-architecture-decisions.ps1` 通过；
- 新增 ADR 已登记索引；
- 文档链接与标准治理门禁通过。

## 与其他规范的关系

- [DDD 服务工程规范](10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)
- [文档整理与归档方案](../governance/DOCUMENTATION_ORGANIZATION.md)
- [ADR 索引](../../20-architecture/adr/README.md)
