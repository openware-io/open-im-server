# ADR 0010：库存作为公共支撑域（stock + capacity）

## Status / 状态

**proposed（提案中，2026-10-22）**。本 ADR 确定库存的公共支撑边界；不改变现有 KTV 行为。

## Context / 背景

当前 KTV 已有资源与占用模型：

- `res_resource`：包厢/服务人员等资源；
- `res_occupation`：按 `startAt/endAt` 的时段占用；
- 占用冲突、超时释放、版本号、审计 Outbox。

酒店、按摩、饭店、健身等业务也需要库存能力，但语义不同：

- 酒店：房型 + 日期区间 + 每晚可售数量；
- KTV：包厢 + 时段；
- 按摩：技师/房间 + 服务时长；
- 饭店：桌台 + 用餐时段/翻台；
- 健身：课程/器械 + 时段容量；
- 采销：SKU + 仓库 + 入库/出库/盘点/成本。

如果每个业务各自实现一套库存，会出现重复的并发控制、超卖、权限、审计和对账；如果强行用一张“万能库存表”，会出现大量可空字段和错误约束。

## Decision / 决策

1. **库存是平台级公共支撑能力**，不是酒店私有模块，也不按业务复制代码。
2. 库存平台分两个内核：
   - **Stock（采销库存）**：SKU、仓库/门店、库存余额、入库、出库、盘点、调拨、成本、批次/效期、安全库存。
   - **Capacity（容量库存）**：资源/资源组、时间粒度、容量、可用量、保留、占用、释放、超时。
3. Capacity 内核用统一抽象承载多业态：

   ```text
   inv_capacity_unit     资源/资源组、业态、容量、时间粒度
   inv_capacity_slot     时间槽、总容量、已售、保留、关房
   inv_capacity_hold     预订/占用期间、数量、过期时间、状态
   ```

   - 酒店：`granularity=DAY`，unit=房型，slot=入住日，hold 覆盖多晚；
   - KTV：`granularity=SLOT`，unit=包厢，slot=时段；
   - 按摩：`granularity=SLOT`，unit=技师/房间，slot=服务时段；
   - 饭店：`granularity=SLOT`，unit=桌台/区域，slot=用餐时段；
   - 健身：`granularity=SLOT`，unit=课程/器械/场地，slot=开课时段。
4. **策略与投影分离**：
   - `CapacityPolicy` 由各业态配置：时间粒度、时长、最大人数、是否允许跨段、是否可超售、释放规则；
   - 前台展示是各业态 Projection：KTV 看板、酒店日历、按摩排班、饭店桌台、健身课表；
   - 不做一个混合所有业态的库存页面。
5. **现有 KTV `res_occupation` 保留为兼容适配层**：
   - MVP 不强制迁移 KTV；
   - 新酒店直接使用 capacity 内核；
   - 后续可在不影响 KTV 的前提下，把 KTV 适配到同一 capacity 内核；
   - 不做一次性大迁移。
6. **不共用一个万能表**：Stock 和 Capacity 分表；Capacity 内部用统一 `inv_capacity_*`，不是每个业态复制一套表。
7. **租户/门店隔离**：
   - 所有查询/写入必须带 `tenantId + storeId`；
   - 禁止跨门店读写；
   - 集团级只读聚合走独立权限和读模型。
8. **前后端双重权限**：
   - 后端从会话解析 tenant/store，校验门店归属；
   - 前端按权限隐藏路由/菜单/按钮，但不作为安全边界；
   - API 客户端统一注入上下文，禁止页面硬编码 storeId。

## Consequences / 后果

正面：

- 酒店、按摩、饭店、健身复用 capacity 内核，不再各写库存；
- 采销库存有独立 stock 内核，不被时段/日期语义污染；
- 并发、超卖、审计、权限、报表口径统一；
- 新业态主要通过策略 + 配置接入，而不是复制代码。

代价与风险：

- 平台初期需要设计好 `CapacityPolicy` 抽象，避免过度通用；
- KTV 兼容适配层会存在一段时间；
- 多业态共享平台需要清晰的模块边界和更完整的测试矩阵；
- Stock 与 Capacity 的团队/发布节奏可能不同，必要时可拆成两个服务。

后续动作：

- 抽出 `inventory/stock` 与 `inventory/capacity` 模块；
- 酒店 MVP 先接 capacity 内核；
- KTV 保留适配层，后续再评估迁移；
- 按摩、饭店、健身按 capacity policy 接入，不新建库存代码；
- 补跨门店越权、超卖、hold 过期和对账测试。

## Alternatives / 备选方案

- **每个业务单独做库存**：拒绝。重复实现并发、权限、审计，超卖口径会分裂。
- **一张万能库存表**：拒绝。Stock 与 Capacity 语义不同，会产生大量可空字段和错误约束。
- **只做 Capacity，不做 Stock**：拒绝。采销、餐饮原料、零售商品需要数量/成本/批次库存。
- **只做 Stock，不做 Capacity**：拒绝。酒店、KTV、按摩、健身卖的是时间/容量，不是 SKU 数量。

## References / 参考

- [酒店实现方案](../../40-domain/hotel/HOTEL_IMPLEMENTATION_PLAN.md)
- [酒店域边界 ADR](0008-hotel-domain-boundary.md)
- [资源服务](../../50-services/platform/platform-resource-service/README.md)
- [DDD 服务工程规范](../../80-development/standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)
- [租户 SQL 门禁](../../80-development/governance/GATE_REGISTRY.md)
