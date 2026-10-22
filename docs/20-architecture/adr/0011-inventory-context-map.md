# ADR 0011：库存上下文映射与依赖规则

## Status / 状态

**proposed（提案中，2026-10-22）**。
**修订说明**：容量库存不进入公共平台；本 ADR 只描述 Stock 平台与业务核心的上下文关系。

## Context / 背景

库存容易被误做成一个跨业务“大库存”：

- 把酒店房间、KTV 包厢、饭店桌台和商品库存放一张表；
- 库存平台反向依赖酒店/KTV；
- 业务域直接读写库存表；
- 把“都有可用量”误认为“同一个领域”。

这会把业务核心耦合到平台，违反领域边界。

## Decision / 决策

1. **Stock Platform 是上游公共支撑域**；Hotel/KTV/饭店/健身/零售/采购是下游消费者。
2. **Capacity 不进入 Stock Platform**：
   - 各业务核心自己拥有容量/可用量/房态/时段/桌台/课表；
   - 业务核心之间不共享容量表；
   - 相似性通过技术库/组件复用，不通过共享领域模型。
3. 依赖方向：

   ```text
   Business Core -> Stock Platform
   Procurement -> Stock Platform
   Stock Platform -> Audit / MQ
   Stock Platform -X-> Business Core
   Business Core -X-> another Business Core
   ```

4. 集成方式：
   - Published Language（版本化命令/查询/事件）；
   - 下游 ACL 把业务语言翻译为 Stock 语言；
   - 禁止共享数据库表；
   - Stock 不导入业务域类。
5. 数据所有权：
   - Stock 拥有 `inv_stock_*`；
   - 酒店拥有房型、房价、房态、酒店库存、预订、押金；
   - KTV 拥有包厢、时段、会话、计价；
   - 饭店/按摩/健身各自拥有容量和排班。
6. 部署演进：
   - Stock 先在模块中，后抽 `platform-inventory-service`；
   - 业务容量留在各自核心模块，不迁移到 Stock。

## Consequences / 后果

正面：

- 领域边界清晰，容量逻辑不被平台抽象污染；
- Stock 真正复用采销台账；
- 各业务可以独立演进容量策略；
- 多业务不会因为共享表而被迫一起发布。

代价与风险：

- 各业务容量会存在相似代码，需要共享技术库；
- 跨业务报表需要独立数据平台；
- 需要维护 Stock 契约和 ACL。

后续动作：

- 建 `inventory-patterns` 技术库（状态机、日历、幂等、锁）；
- Stock 平台契约版本化；
- 各业务容量补自己的超卖/冲突/权限测试；
- 用 G18/G24/G25/G32/G43 约束边界。

## Alternatives / 备选方案

- **公共 Capacity 平台**：拒绝。容量是业务核心，平台会积累酒店/KTV/饭店规则。
- **共享一张库存表**：拒绝。Stock 与 Capacity 语义不同。
- **业务域直接写 Stock 表**：拒绝。破坏契约与权限。
- **Shared Kernel**：拒绝。共享模型会强耦合。

## References / 参考

- [库存平台域（Stock）](../../40-domain/inventory/README.md)
- [ADR-0010：采销库存作为公共支撑域](0010-stock-supporting-domain.md)
- [ADR-0008：酒店域边界](0008-hotel-domain-boundary.md)
- [酒店实现方案](../../40-domain/hotel/HOTEL_IMPLEMENTATION_PLAN.md)
- [DDD 服务工程规范](../../80-development/standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)
