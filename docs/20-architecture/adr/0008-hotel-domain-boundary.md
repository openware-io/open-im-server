# ADR 0008：酒店域边界与 MVP 归属

## Status / 状态

**proposed（提案中，2026-10-22）**。本 ADR 只确定 MVP 的领域边界，不替代酒店实现方案。

## Context / 背景

酒店业务需要：

- 酒店、房型、物理房间、房价计划、每日库存；
- 日期区间预订、支付、取消、入住/退房；
- 集团内多门店与单店接入；
- OTA/渠道对接的对外契约。

现有 `platform-resource-service` 已拥有资源、房型、占用和 `res_*` 数据；`platform-order-service` 已拥有订单、预约、KTV 会话和 `ord_*` 数据。若为酒店新建独立服务，MVP 会引入跨服务事务、重复 BFF 和运维成本。

## Decision / 决策

1. MVP **不新建** `platform-hotel-service`。
2. 酒店核心（目录、房型、房价、酒店容量、预订、押金）打包在 `platform-order-service` 的 `domain/hotel` 模块：
   - `Hotel`、`HotelRoomType`、`HotelRoom`、`RatePlan`、`DailyRate`、`HotelInventoryCalendar`、`HotelInventoryHold`、`HotelBooking`、`StayDeposit`；
   - 酒店容量是核心域，不进入 `platform-resource-service`，也不进入 Stock 平台；
   - 数据使用 `htl_*` / `ord_*` 前缀（最终以数据模型评审为准），不跨域写表。
3. `platform-resource-service` 继续承载 KTV 资源/占用；不再承载酒店容量。
4. 后台聚合由 `platform-admin-service` 承担；支付、客户、审计、Stock 复用现有/平台服务。
5. 满足以下任一条件时，另立 ADR 拆出 `platform-hotel-service`：
   - 酒店团队独立开发/发布；
   - 酒店库存 QPS 或数据量显著高于订单其他业务；
   - 需要独立数据库/SLA；
   - OTA 渠道同步成为独立子系统。

## Consequences / 后果

正面：

- 复用现有资源、订单、支付、客户、审计能力；
- MVP 不引入跨服务事务；
- 酒店模块有清晰包边界，后续可拆。

代价与风险：

- `platform-order-service` 职责变宽，必须用包边界和门禁约束；
- 酒店容量与 KTV 占用不再共享表，需要各自维护；
- 若拆分延迟，酒店团队与订单团队可能耦合。

后续动作：

- 实现前补 `hotel-booking-v1.yaml` 与 `hotel-channel-v1.yaml`；
- 数据模型评审后再写 Flyway；
- 用 G18/G24/G25/G32/G43/G52 约束边界和文档同步。

## Alternatives / 备选方案

- **全部放入 resource**：拒绝。酒店容量是酒店核心，不是资源平台能力。
- **全部放入 order**：本期采用模块化方式，酒店核心打包在 order；不是把库存做成订单子表。
- **新建 hotel-service**：暂不采纳。MVP 分布式成本高，等触发条件满足再拆。

## References / 参考

- [酒店实现方案](../../40-domain/hotel/HOTEL_IMPLEMENTATION_PLAN.md)
- [酒店方案规范审查](../../40-domain/hotel/HOTEL_DESIGN_REVIEW.md)
- [DDD 服务工程规范](../../80-development/standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)
- [资源服务](../../50-services/platform/platform-resource-service/README.md)
- [订单服务](../../50-services/platform/platform-order-service/README.md)
