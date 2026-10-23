# platform-order-service

[中文](README.md) | [English](README.en.md)

> 状态：`current`（`develop/2.3.0`）  
> 运行时端口：`4130`  
> 数据归属：MySQL `open_saas` 的 `ord_*` 表

## 职责

订单与预约域的写模型和编排服务：

- 订单：创建、确认、取消、作废、结算、收款状态衔接；
- 预约：确认、到店、未到店、取消、开台兑现；
- KTV 会话：开台、暂停/恢复、结台、计时与包厢费；
- 订单明细、账单、商品目录与库存占用；
- 通过 Outbox 发布订单/结算等事件。

## 边界

- 拥有 `ord_*` 订单/预约/会话/账单/商品/库存表；
- 不拥有客户、会员、房间资源、支付流水；通过客户/资源/支付/审计服务协作；
- 不直接写其他服务的表；跨域只传 ID 或事件。

## 入口与依赖

| 项 | 说明 |
| --- | --- |
| HTTP | Gateway 路由到 `platform-order-service:4130` |
| 数据库 | MySQL `open_saas` |
| 消息 | RocketMQ；Outbox Relay 发布订单事件 |
| 下游 | 客户、资源、支付、审计 |

## 关键状态

订单、预约、KTV 会话的状态与迁移见：

- [订单/预约/开台状态机](../../../20-architecture/diagrams/08-order-reservation-state.md)
- [领域文档](../../../40-domain/README.md)

## 相关文档

- [服务目录](../README.md)
- [API 契约](../../../30-reference/contracts/README.md)
- [数据库迁移标准](../../../80-development/standards/23_DATABASE_MIGRATION_STANDARD.md)

## 酒店主数据（H1）

`domain/hotel` 已提供酒店/房型/房间/房价计划/库存日历接口：

```text
GET/POST/PUT /admin/hotel/hotels
GET/POST/PUT /admin/hotel/room-types
GET/POST/PUT /admin/hotel/rooms
GET/POST/PUT /admin/hotel/rate-plans
GET/PUT      /admin/hotel/inventory
```

写操作分别校验 `hotel.inventory.manage` / `hotel.rate.update`，读取校验 `hotel.booking.view`；
所有查询强制当前 `storeId`。表模型见 [酒店数据模型](../../../40-domain/hotel/HOTEL_DATA_MODEL.md)，
契约见 [hotel-booking-v1.yaml](../../../30-reference/contracts/openapi/hotel-booking-v1.yaml)。


## 酒店报价与预订（H2）

```text
POST /admin/hotel/quotes
POST /admin/hotel/bookings
GET  /admin/hotel/bookings
POST /admin/hotel/bookings/{id}/confirm
POST /admin/hotel/bookings/{id}/cancel
```

- 报价按入住夜逐日读取库存和房价，任一晚不足即不可售；
- 创建预订使用原子 `UPDATE ... WHERE total-sold-held >= rooms` 扣 hold；
- 确认：hold → sold；
- 取消：未确认释放 hold，已确认释放 sold；
- 同一 `booking_id + stay_date` 只允许一条 hold 记录。

库存并发与状态机见 [酒店数据模型](../../../40-domain/hotel/HOTEL_DATA_MODEL.md)。

## 酒店每日房价与 hold 过期（H2 补充）

```text
GET /admin/hotel/daily-rates
PUT /admin/hotel/daily-rates
```

报价优先读取 `htl_daily_rate`，没有配置时回退房价计划基础价。

未支付 `PENDING_PAYMENT` 预订的 hold 到期后，由订单服务定时任务释放库存并把预订置为 `CANCELLED`；
`htl_booking_night` 按夜记录价格，确认置 `SOLD`，取消置 `CANCELLED`。

## 酒店入住与账务（H3）

```text
POST /admin/hotel/frontdesk/bookings/{id}/checkin
POST /admin/hotel/frontdesk/bookings/{id}/deposit
GET  /admin/hotel/frontdesk/bookings/{id}/folio
POST /admin/hotel/frontdesk/bookings/{id}/checkout
```

- 入住：从房型下选择 `ACTIVE` 物理房间，原子占用，创建 stay/folio 和房费账单项；
- 押金：写 `DEPOSIT` 账单项，累加已收；
- 退房：账单未结清返回 409，不允许退房；
- 结清后：stay/folio 关闭，房间置 `DIRTY`，创建 `PENDING` 清扫任务。

当前押金/账务是本地账本，尚未接支付网关和真实退款。

## 酒店 no-show 与客房清扫（H3 补充）

```text
POST /admin/hotel/bookings/{id}/no-show
GET  /admin/hotel/housekeeping/tasks
POST /admin/hotel/housekeeping/tasks/{id}/start
POST /admin/hotel/housekeeping/tasks/{id}/complete
```

- no-show：仅 `CONFIRMED`/`ARRIVAL` 可标记，释放 held/sold，按夜账单置 `NO_SHOW`；
- 清扫：`PENDING → IN_PROGRESS → DONE`，完成时房间 `DIRTY → ACTIVE`；
- 清扫操作校验 `hotel.inventory.manage`。

## 酒店夜审与换房（H4）

```text
GET  /admin/hotel/night-audit
POST /admin/hotel/night-audit/run
POST /admin/hotel/frontdesk/bookings/{id}/move-room
```

- 夜审按营业日把到离店日期的在住预订转 `DUE_OUT`，固化在住数/待退房数/房费汇总，同一营业日不可重复；
- 换房只允许在住预订，新房间原子占用，旧房间置 `DIRTY` 并生成清扫任务；
- 夜审和换房分别校验 `hotel.inventory.manage` / `hotel.checkin`。
