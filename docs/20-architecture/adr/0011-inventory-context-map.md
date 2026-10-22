# ADR 0011：库存上下文映射与依赖规则

## Status / 状态

**proposed（提案中，2026-10-22）**。本 ADR 固化库存平台与业务域的上下文关系，不替代 ADR-0010 的 stock/capacity 划分。

## Context / 背景

库存正在从“KTV 资源占用”扩展为酒店、按摩、饭店、健身、采销共同依赖的公共能力。若不先定义上下文关系，容易出现：

- 库存平台反向依赖酒店/KTV；
- 业务域直接读写 `inv_*` 表；
- 每个业务复制一套库存逻辑；
- 把公共平台误称为 Shared Kernel，导致模型强耦合；
- 权限和数据隔离口径分裂。

## Decision / 决策

1. **Inventory 是上游公共支撑域**，Hotel/KTV/按摩/饭店/健身/采购是下游消费者。
2. **依赖方向固定**：

   ```text
   Business Contexts -> Inventory Platform
   Procurement -> Inventory Platform
   Inventory Platform -> Audit / MQ
   Inventory Platform -X-> Business Contexts
   ```

3. **集成模式**：
   - 下游通过 **Published Language**（版本化命令/查询/事件）调用库存；
   - 下游用 **Anti-Corruption Layer** 把业务语言翻译成库存语言；
   - 不允许共享数据库表；
   - 不允许库存平台导入业务域类。
4. **不是 Shared Kernel**：共享的是契约和平台能力，不是业务模型；Shared Kernel 会造成强耦合，不适用于多业态。
5. **业务策略归业务域**：
   - `CapacityPolicy` 参数由业务域提供；
   - 库存平台只执行通用不变量（不超卖、hold 幂等、过期释放、版本冲突、审计、租户隔离）；
   - 库存平台不知道 `hotelId`、`roomTypeId`、`ktvRoomId`。
6. **数据所有权**：
   - Inventory 拥有 `inv_stock_*` / `inv_capacity_*`；
   - Hotel 拥有酒店、房型、房价、预订、押金；
   - KTV 拥有包厢、会话、计价；
   - 采购拥有采购单/供应商，库存拥有收货后的库存流水。
7. **部署演进**：
   - 阶段 1 模块在 `platform-resource-service`；
   - 阶段 2 抽 `platform-inventory-service`；
   - KTV 现有 `res_occupation` 作为兼容适配层，不做强制迁移。

## Consequences / 后果

正面：

- 多业态复用同一库存平台，不复制并发/审计/权限；
- 依赖方向清晰，库存不被业务规则污染；
- 未来按摩、饭店、健身按策略接入，不新增库存代码；
- 业务域可独立演进。

代价与风险：

- 需要维护版本化契约和 ACL；
- 库存平台与业务域之间可能出现映射成本；
- 跨域一致性需要 Outbox/补偿；
- 多业态测试矩阵更大。

后续动作：

- 建立 `InventoryPlatform` 上下文文档；
- 契约版本化并加契约测试；
- 监控跨域调用失败、hold 过期、超卖；
- 用 G18/G24/G25/G32/G43/G52 约束边界。

## Alternatives / 备选方案

- **Shared Kernel**：拒绝。多业态共享模型会强耦合。
- **每个业务独立库存**：拒绝。重复实现不变量。
- **库存平台感知业务类型**：拒绝。平台会变成业务规则堆积地；只允许通用 `businessType` 配置。
- **直接共享数据库**：拒绝。破坏边界和权限。

## References / 参考

- [库存平台域](../../40-domain/inventory/README.md)
- [ADR-0010：库存作为公共支撑域](0010-shared-inventory-kernel.md)
- [DDD 服务工程规范](../../80-development/standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)
- [酒店实现方案](../../40-domain/hotel/HOTEL_IMPLEMENTATION_PLAN.md)
