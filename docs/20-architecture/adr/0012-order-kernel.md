# ADR 0012：订单作为共享交易内核

## Status / 状态

**proposed（提案中，2026-10-22）**。本 ADR 确定订单公共内核与业务履约的边界。

## Context / 背景

当前订单相关模型分散：

- `Order`：通用订单头/明细/金额/支付；
- `Reservation`：预约；
- `KtvSession`：KTV 会话；
- 酒店预订如果不复用，会再出现一套 `HotelBooking` 订单系统。

如果每个业务各做一套订单，会重复：订单号、金额、支付、退款、审计、幂等、C 端订单列表。  
如果做一张万能订单表，则会重复容量库存的错误：大量业务字段、错误状态枚举、业务耦合。

订单的本质是**交易单据**，与容量不同：订单头/明细/金额/支付可以共享。

## Decision / 决策

1. **订单内核是公共支撑域**，不按业务复制订单系统。
2. 公共模型：

   ```text
   ord_order
     orderNo / tenantId / storeId / customerId / platformUserId
     orderType / businessType
     status / paymentStatus / fulfillmentStatus
     amountMinor / currency
     source / idempotencyKey / timestamps

   ord_order_line
     orderId / lineNo / itemType / itemRef
     title / quantity / unitPriceMinor / amountMinor
   ```

3. **业务扩展 1:1 分表**：
   - `HotelBookingDetail`：酒店/房型/房价/入住离店/间数/押金；
   - `KtvOrderDetail`：包厢/会话/套餐/服务人员；
   - `RestaurantOrderDetail`：桌台/餐段/翻台；
   - `GymOrderDetail`：课程/场地/会员。
4. `orderType` 表达交易形态（SALE/RESERVATION/BOOKING），`businessType` 表达业务归属（HOTEL/KTV/...）；两者不能合并。
5. 公共状态只表达交易生命周期；履约状态由业务扩展（酒店 CHECKED_IN、KTV OPEN 等），不塞入一个全局枚举。
6. 预约单是 `orderType=RESERVATION` 的订单，不另建一套预约系统。
7. 现有 KTV `Reservation`/`KtvSession` 通过适配层兼容，不做一次性重写。
8. Order Kernel 不依赖酒店/KTV；业务域通过 ACL 创建订单和扩展。
9. 支付、退款、C 端统一订单索引复用公共订单头；C 端按 `platformUserId` 查询。

## Consequences / 后果

正面：

- 订单号、金额、支付、退款、审计、幂等只实现一次；
- 酒店/KTV/饭店/健身共享交易内核，但履约各自独立；
- C 端“我的订单”天然统一；
- 未来新业务只加 `businessType` + 扩展，不新建订单系统。

代价与风险：

- 公共订单模型需要稳定的抽象，避免被单个业务拉偏；
- 业务扩展表增多，查询需要组合；
- 现有 KTV 订单/预约需要适配层；
- 状态模型要区分交易状态与履约状态。

后续动作：

- 酒店先接公共订单头 + `HotelBookingDetail`；
- KTV 适配 `Reservation`/`Order`；
- C 端订单索引统一读取 `ord_order`；
- 补订单内核契约测试、状态机测试和 ACL 测试。

## Alternatives / 备选方案

- **每业务一套订单**：拒绝。重复支付/退款/审计/列表。
- **一张万能订单表**：拒绝。业务字段和状态会污染内核。
- **订单内核感知业务类型**：拒绝。只保留 `businessType` 配置，不写酒店/KTV 规则。
- **预约单独一套系统**：拒绝。预约是订单类型，不是新领域。

## References / 参考

- [订单平台域](../../40-domain/order/README.md)
- [酒店实现方案](../../40-domain/hotel/HOTEL_IMPLEMENTATION_PLAN.md)
- [订单服务](../../50-services/platform/platform-order-service/README.md)
- [DDD 服务工程规范](../../80-development/standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)
