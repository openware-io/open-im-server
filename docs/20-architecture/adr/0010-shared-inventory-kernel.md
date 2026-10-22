# ADR 0010：共享库存内核，分离 KTV 与酒店库存策略

## Status / 状态

**proposed（提案中，2026-10-22）**。只确定库存底层复用边界，不改变现有 KTV 库存行为。

## Context / 背景

KTV 已有资源与占用模型：

- `res_resource`：包厢/服务人员等资源；
- `res_occupation`：按 `startAt/endAt` 的时段占用，状态 `HELD/RESERVED/IN_USE/RELEASED/CANCELLED`；
- 占用冲突、超时释放、版本号、审计 Outbox。

酒店需要的是另一种库存语义：

- 按房型 + 入住日期区间的**每日可售数量**；
- 支持多晚、跨日期、超卖控制、保留房；
- 入住时才分配具体房间，不是预订时锁定某个物理资源。

两者有共同底层能力，但业务聚合不同。

## Decision / 决策

1. **复用库存内核**，继续放在 `platform-resource-service`，MVP 不新建酒店库存服务。
2. 复用范围：
   - `tenantId/storeId` 作用域；
   - 资源目录与房型能力；
   - 可用量、占用/保留、过期释放等通用概念；
   - 乐观锁/版本号、唯一键冲突、事务边界；
   - 审计、Outbox、权限校验、关联 ID。
3. **不共用同一张库存/占用表**：
   - KTV 继续使用 `res_occupation` 表达“资源 + 时段”占用；
   - 酒店新增 `res_hotel_*`、`res_rate_plan`、`res_daily_rate`、`res_inventory_calendar`、`res_inventory_hold`；
   - 禁止把 `res_occupation` 改成带大量可空字段的“万能表”。
4. **应用层按业务分离**：
   - `KtvInventoryApplicationService` 与 `HotelInventoryApplicationService` 各自实现策略；
   - API 分离：`/business/ktv/**` 与 `/business/hotel/**`；后台同理；
   - 共享技术组件，不共享业务状态机。
5. **店铺级隔离**：
   - 所有库存查询/写入必须带 `tenantId + storeId`；
   - 不允许跨门店读写；
   - 集团/租户级只读聚合必须走明确的集团权限和独立读模型，不能复用门店接口。
6. **前后端双重权限**：
   - 后端按会话上下文解析 tenant/store，并校验用户在该门店的角色/权限；
   - 前端按路由、菜单、按钮隐藏，但不能替代后端校验；
   - 越权请求返回 403/404，不泄露资源是否存在。

## Consequences / 后果

正面：

- 复用租户隔离、并发控制、审计、过期释放等成熟能力；
- KTV 与酒店各自保持清晰语义；
- 后续新增业态可以复用内核，而不是复制一套。

代价与风险：

- `platform-resource-service` 模块变多，需要包边界和门禁；
- 两套库存策略的测试、监控、迁移要分别维护；
- 共享内核 API 设计不当会导致业务耦合，需要 anti-corruption 层。

后续动作：

- 抽出 `domain/inventory` 通用接口与 `KtvInventoryPolicy` / `HotelInventoryPolicy`；
- 增加跨门店越权测试；
- 用 G18/G24/G25/G32/G43 约束边界；
- 酒店库存表先评审 ER 和唯一键，再写 Flyway。

## Alternatives / 备选方案

- **共用 `res_occupation` 一张表**：拒绝。时段占用与每日配额语义冲突，会产生大量可空字段和错误约束。
- **新建独立 hotel-service**：暂不采纳。MVP 分布式成本高，等 ADR-0008 的拆分触发条件满足再拆。
- **复制一套 KTV 库存代码**：拒绝。租户隔离、并发、审计会出现两套实现，长期漂移。

## References / 参考

- [酒店实现方案](../../40-domain/hotel/HOTEL_IMPLEMENTATION_PLAN.md)
- [酒店域边界 ADR](0008-hotel-domain-boundary.md)
- [资源服务](../../50-services/platform/platform-resource-service/README.md)
- [DDD 服务工程规范](../../80-development/standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)
- [租户 SQL 门禁](../../80-development/governance/GATE_REGISTRY.md)
