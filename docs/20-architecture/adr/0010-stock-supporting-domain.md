# ADR 0010：采销库存作为公共支撑域

## Status / 状态

**proposed（提案中，2026-10-22）**。
**修订说明**：本 ADR 早期版本曾把容量库存也放进公共平台；2026-10-22 修正为“Stock 公共支撑，Capacity 归业务核心”。

## Context / 背景

酒店、KTV、饭店、健身、零售都需要库存，但存在两种完全不同的东西：

- **采销库存（Stock）**：商品/原料数量、仓库、出入库、成本、批次；可以跨业务复用。
- **容量库存（Capacity）**：房间、包厢、桌台、课程、技师时段；是各业务的核心经营能力。

酒店房间不足不能通过采购补房间；KTV 包厢、饭店桌台、健身课程也一样。容量不是采销库存。

## Decision / 决策

1. **Stock 是公共支撑域**，可以平台化，服务多个业务。
2. **Capacity 不是公共平台能力**，归各业务核心域：
   - 酒店：房型库存、房态、多晚 hold、入住分配；
   - KTV：包厢时段、会话、开台/结台；
   - 按摩：技师/房间时段；
   - 饭店：桌台/餐段/翻台；
   - 健身：课程/场地/容量。
3. 禁止建立跨业务 `inv_capacity_*` 公共表或跨业务容量服务。
4. 相似性只能沉淀为**技术模式/组件**：
   - 日历计算、hold 状态机、乐观锁、幂等、测试夹具；
   - 不共享领域模型、表、服务。
5. Stock 的边界：
   - `inv_stock_item`、`inv_stock_balance`、`inv_stock_movement`、`inv_stock_reservation`、`inv_stock_batch`；
   - 业务域通过版本化契约 + ACL 调用；
   - Stock 不依赖任何具体业务域。
6. 业务核心对 Stock 的依赖是**下游→上游**；业务核心之间不互相依赖。

## Consequences / 后果

正面：

- 容量逻辑留在业务核心，不牺牲领域边界；
- Stock 真正复用，不重复实现采销台账；
- 多业务不会因为“都用库存”被耦合到一张表；
- 各业务可以独立演进容量策略和房价/时段规则。

代价与风险：

- 各业务容量实现会有相似代码，需要通过共享技术库降低重复；
- 跨业务统一报表需要数据平台/读模型，不靠共享表；
- 需要明确的 ACL 和契约版本；
- 团队要接受“相似但不共享模型”的边界。

后续动作：

- 抽出可选的技术库 `inventory-patterns`（不包含业务表）；
- Stock 平台单独建模块/服务；
- 各业务容量在自己的核心模块实现；
- 用 G18/G24/G25/G32/G43 约束边界。

## Alternatives / 备选方案

- **容量也做公共平台**：拒绝。把业务核心耦合到平台，平台会积累酒店/KTV/饭店规则。
- **每个业务重写采销库存**：拒绝。Stock 是真正可复用的支撑域。
- **共享一张库存万能表**：拒绝。Stock 与 Capacity 语义不同。
- **Shared Kernel**：拒绝。多业务共享模型会强耦合。

## References / 参考

- [库存平台域（Stock）](../../40-domain/inventory/README.md)
- [ADR-0011：库存上下文映射](0011-inventory-context-map.md)
- [ADR-0008：酒店域边界](0008-hotel-domain-boundary.md)
- [酒店实现方案](../../40-domain/hotel/HOTEL_IMPLEMENTATION_PLAN.md)
- [DDD 服务工程规范](../../80-development/standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)
