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

酒店封面 `cover_image_url` 只接受同源相对地址或 HTTPS 地址，最长 512 字符；图片本体由后台媒体
上传接口保存。前台编辑约束为 JPG/PNG/WebP、单张 ≤ 5MB、16:9、建议 1600×900、最小 800×450，
比例偏差 ≤ 5%。


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

## 酒店前台房态卡字段

`GET /admin/hotel/frontdesk/tasks` 返回前台房态卡所需的摘要字段。除房号/房型/状态外，还包含：

| 字段 | 用途 |
| --- | --- |
| `nights` / `rooms` | 入住晚数、房间数 |
| `checkInDate` / `checkOutDate` | 住宿区间，卡片显示“10/30 → 11/02 · 3晚” |
| `guestPhone` | 仅由前端脱敏显示尾号，不把完整手机号放进卡片 |
| `source` | 订单来源（前台/官网/电话/OTA），前端统一映射为中文 |
| `checkInAt` / `checkOutAt` / `stayStatus` | 实际入住/离店时间和在住状态 |
| `folioChargeMinor` / `folioPaidMinor` / `folioStatus` | 当前账单应收/已收，用于“待结/账已结清” |
| `depositAmountMinor` / `depositOrderId` | 定金金额与收款单状态 |
| `hasStay` | 历史预订没有 stay 时，前台不显示可退房 |

这些字段只属于当前门店任务查询；`BookingView` 不改变既有字段的 JSON 名称。房态卡展示遵循
Opera Cloud / Mews / Cloudbeds 等前台系统的共同信息层级：**房号 → 房态 → 住客 → 住宿区间 →
账务/来源**，不展示没有数据来源的 VIP、偏好或伪备注。

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

## 酒店订单应收桥接（H4）

酒店预订创建时生成 `ord_order` 应收并写入 `htl_booking.order_id`：

```text
business_type = HOTEL
status        = DRAFT
total_amount  = 预订总价（最小货币单位）
idempotency   = hotel-booking:{bookingNo}
```

支付服务 collect 可以按该 `orderId` 校验应收。酒店侧尚未提供支付页面和回调，支付确认链路仍需下一步接入。

### 押金退款（本地账本）

```text
POST /admin/hotel/frontdesk/bookings/{id}/refund
```

写 `REFUND` 负向账单项并减少已收金额；当前不调用 payment-service 退款，真实资金退款仍待接。

退款方式：

```text
POST /admin/hotel/frontdesk/bookings/{id}/refund
{ "amountMinor": 500, "method": "CASH", "note": "现金押金退款" }
```

- `CASH`：写 `REFUND` 账单项（`method=CASH`）并减少已收，走本地现金账本；
- `ONLINE`：当前返回 409 `ONLINE_REFUND_NOT_READY`，必须接 payment-service 退款后才能标记成功；
- 不允许把线上退款记成现金退款。

### 线上退款申请

```text
POST /admin/hotel/frontdesk/bookings/{id}/refund-requests
```

调用 payment-service `/business/refund-requests`，写入 `htl_refund`（method=ONLINE, status=PENDING, external_ref=退款申请号）。
线上退款在支付侧审批/打款前不减少 folio.paid，也不写 `REFUND` 账单项；现金退款仍走 `/refund` 并立即记账。

### 线上退款状态同步

```text
GET  /admin/hotel/frontdesk/refunds
POST /admin/hotel/frontdesk/refunds/sync
```

同步 payment-service 的 `APPROVED/REJECTED/REFUNDED`：
- `APPROVED` 只更新 `htl_refund`；
- 只有 `REFUNDED` 才写 `REFUND` 账单项、减少 `folio.paid`；
- `PENDING` 不会被当成已退款。

## 酒店续住

```text
POST /admin/hotel/frontdesk/bookings/{id}/extend
{ "newCheckOutDate": "2026-11-01" }
```

只对新增夜追加 `sold` 库存、`htl_booking_night`、房费和账单项，不改已住夜。
`hotel.checkin` 权限校验；新日期必须晚于当前离店日期；库存不足返回 409。

## C 端酒店目录/报价（H5）

```text
GET  /business/hotel/catalog/hotels
GET  /business/hotel/catalog/hotels/{id}
POST /business/hotel/catalog/quotes
```

- 只读、按租户隔离，不要求门店上下文；
- 目录返回酒店、房型、房价计划；
- 报价按入住夜读取库存/每日房价，`available` 与总价同时返回；
- C 端下单/支付仍待接入。

## C/B audience

- order /admin/** 与 B 前台 /business/reservations|orders|ktv/** 要求 saas-b。
- /business/hotel/catalog/** 要求 saas-c。
- 有 TenantContext 但缺 clientApp 的用户 token 拒绝。

Catalog 写端点（POST/PUT/DELETE /business/catalog/items）要求 saas-b audience；列表保持 C/B 共享。

C 端酒店：/business/hotel quotes|bookings 仅 saas-c；HOTEL 门店 store_id=102。
