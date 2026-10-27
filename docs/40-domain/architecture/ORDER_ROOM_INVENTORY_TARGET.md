# 订单 / 房态 / 房量 / 商品库存目标契约

状态：执行中。此文件是重构期间的 DTO、状态、表名和边界的唯一约定。

## 1. 组织与业态

```text
平台 -> 租户 -> 组织 -> 门店 -> business_type（门店属性）
organization_id = store.organization_id
business_type = store.business_type
```

任何订单、预订、会话、库存写入都必须校验上述两条。

## 2. 订单

`ord_order` 只放交易字段：

```text
trade_status: DRAFT / PENDING_PAYMENT / PAID / COMPLETED / CANCELLED / VOIDED
refund_status: NONE / PARTIAL / FULL
```

`VOIDED` 独立保留，作废不能混同普通取消；报表按 `VOIDED` 统计作废单。

不再使用 `ord_order.status` 表达 `SERVING`、`WAITING_SETTLEMENT`、`CHECKED_IN`、`DUE_OUT`。

## 3. 酒店

```text
htl_booking.order_id UNIQUE
htl_booking.status: PENDING_CONFIRM / CONFIRMED / CHECKED_IN / DUE_OUT / CHECKED_OUT / CANCELLED / NO_SHOW
```

房态：

```text
htl_room.occupancy_status: VACANT / OCCUPIED
htl_room.housekeeping_status: CLEAN / DIRTY / CLEANING / INSPECTED
htl_room.out_of_order_status: NONE / OUT_OF_ORDER / OUT_OF_SERVICE
htl_room_hold.reason: RESERVED / GROUP_BLOCK / HOUSE_USE / MAINTENANCE / OUT_OF_SERVICE / LOCKED
```

房量：

```text
htl_room_availability
tenant_id + store_id + room_type_id + stay_date
```

`organization_id` 是归属/报表快照，不进入房量唯一键。

房量不是库存。

## 4. KTV

```text
ord_ktv_session.order_id UNIQUE
ord_ktv_session.status: RESERVED / OPEN / PAUSED / CLOSED / CANCELLED
```

KTV 订单 DTO 必须同时返回：

```text
tradeStatus
sessionStatus
settlementStatus
```

不再返回单一 `status` 让前端推断。

## 5. 商品库存

通用域，酒店和 KTV 共用一套服务：

```text
tenant_id + business_type + store_id + warehouse_id + material_id
```

Material/SKU 是门店级共享主数据，酒店和 KTV 可以共用同一个 SKU；Stock 和 StockTransaction 必须按 `tenant_id + business_type + store_id + warehouse_id + material_id` 隔离。`organization_id` 是归属/报表快照，不进入库存唯一键。

## 6. 预约

不通用：

```text
KTV: ord_reservation（明确仅 KTV 使用，不改名）
酒店: htl_booking
```

不新增通用预约聚合、通用预约状态机。`ord_reservation.business_type` 必须为 `KTV`。

## 7. DTO

```text
TradeOrderView: tradeStatus, refundStatus, amounts, org/store/business
HotelOrderView: 订单 + 预订号 + 住客 + 房型 + 房号 + 入离日期 + 预订状态 + 金额
KtvOrderView: 订单 + 包厢 + 人数 + 开台/结台 + 会话状态 + 金额
RoomBoardView: 房号/房型/占用/清扫/不可售原因/当前住客
RoomAvailabilityView: 房型/日期/总房量/已售/占用/可售
```

## 8. 发布

全部迁移、后端、KTV、酒店、库存、Web、App、报表、权限、测试完成；全量门禁通过后，只发布一次 Kind。
