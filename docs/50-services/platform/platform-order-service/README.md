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
GET/PUT      /admin/hotel/availability
```

写操作分别校验 `hotel.room.availability.manage`（房价/房量），读取校验
`hotel.room.availability.view` / `hotel.booking.view`；房态写操作校验 `hotel.room.manage`，
清扫写操作校验 `hotel.housekeeping.manage`；
所有查询强制当前 `storeId`；房态图读接口也校验当前门店 `business_type=HOTEL`，KTV 门店直连返回
`409 STORE_BUSINESS_TYPE_MISMATCH`。表模型见 [酒店数据模型](../../../40-domain/hotel/HOTEL_DATA_MODEL.md)，
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

C 端入口为 `POST /business/hotel/quotes`、`POST /business/hotel/bookings` 与
`GET /business/hotel/bookings`。C 端上下文可能来自 KTV 门店安装的消费应用；
报价/预订按房型的权威 `storeId` 切换到酒店门店作用域，再执行酒店权限与库存校验，
不要求 C 应用安装门店本身是酒店。

库存并发与状态机见 [酒店数据模型](../../../40-domain/hotel/HOTEL_DATA_MODEL.md)。

## 酒店每日房价与 hold 过期（H2 补充）

```text
GET /admin/hotel/daily-rates
PUT /admin/hotel/daily-rates
```

报价优先读取 `htl_daily_rate`，没有配置时回退房价计划基础价。

未确认（`PENDING_CONFIRM`）预订的 hold 到期后，由订单服务定时任务释放房量并把预订置为 `CANCELLED`；
`htl_booking_night` 按夜记录价格，确认置 `SOLD`，取消置 `CANCELLED`。

## 酒店入住与账务（H3）

```text
POST /admin/hotel/frontdesk/bookings/{id}/checkin
POST /admin/hotel/frontdesk/bookings/{id}/deposit
GET  /admin/hotel/frontdesk/bookings/{id}/folio
POST /admin/hotel/frontdesk/bookings/{id}/checkout
```

- 入住：从房型下选择 `VACANT + CLEAN/INSPECTED + NONE` 且未被预留的物理房间，原子占用，创建
  stay/folio 和房费账单项；入住不再二次扣减房量（房量在预订确认时已从 HELD 转 SOLD）；
- 押金：写 `DEPOSIT` 账单项，累加已收；
- 退房：账单未结清（且无订单预付可抵扣）返回 409，不允许退房；
- 结清后：stay/folio 关闭，房间置 `VACANT + DIRTY`，创建 `PENDING` 清扫任务。

当前押金/账务是本地账本，尚未接支付网关和真实退款。

## 房态与清扫状态（2.4.0 三字段）

`htl_room` 的房态拆成三组互不覆盖的字段（不再使用单一 `status`）：

- 占用：`occupancy_status` = `VACANT` / `OCCUPIED`；
- 清扫：`housekeeping_status` = `CLEAN` / `DIRTY` / `CLEANING` / `INSPECTED`；
- 不可售：`out_of_order_status` = `NONE` / `OUT_OF_ORDER` / `OUT_OF_SERVICE`。

预订/在住（`htl_booking.status`）为
`PENDING_CONFIRM / CONFIRMED / CHECKED_IN / DUE_OUT / CHECKED_OUT / CANCELLED / NO_SHOW`：
客人在店为 `CHECKED_IN` / `DUE_OUT`，房间必须 `OCCUPIED`；退房后房间 `VACANT + DIRTY` 并生成
`DEPARTURE` 清扫任务，清扫完成才回到 `CLEAN`。

`htl_room_hold` 承载预留/锁房/自用/维修/停售（`RESERVED / GROUP_BLOCK / HOUSE_USE /
MAINTENANCE / OUT_OF_SERVICE / LOCKED`）。维修/停售由房态服务同时调整房量 `total`，恢复时加回；
房态写路径不改 `sold`。

C 端在住客人申请清扫：

```text
POST /business/hotel/bookings/{id}/housekeeping-requests
```

只创建 `STAYOVER / CUSTOMER / PENDING` 任务，同一预订未完成任务幂等返回；不会把在住房间标成 DIRTY。
前台任务接口只把有真实 stay 的 `CHECKED_IN / DUE_OUT` 预订当作在住客人；历史导入的“只有预订状态、没有 stay/folio”数据不会进入前台任务，避免点账单时出现 `FOLIO_NOT_FOUND`。
完整状态机与前端展示规则见 [房态与清扫状态模型](../../../40-domain/hotel/HOTEL_ROOM_STATE_MODEL.md)。

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

- no-show：仅 `CONFIRMED` 可标记，释放 held/sold，按夜账单置 `NO_SHOW`；
- 清扫：`PENDING → IN_PROGRESS → DONE`，完成时房间 `DIRTY → CLEAN`；
- 清扫列表校验 `hotel.housekeeping.view`，清扫写操作校验 `hotel.housekeeping.manage`。

## 酒店夜审与换房（H4）

```text
GET  /admin/hotel/night-audit
POST /admin/hotel/night-audit/run
POST /admin/hotel/frontdesk/bookings/{id}/move-room
```

- 夜审按营业日把到离店日期的在住预订转 `DUE_OUT`，固化在住数/待退房数/房费汇总，同一营业日不可重复；
- 换房只允许在住预订，新房间原子占用，旧房间置 `DIRTY` 并生成清扫任务；
- 夜审和换房分别校验 `hotel.room.availability.manage` / `hotel.room.manage`。

## 酒店订单应收桥接（H4）

酒店预订创建时生成 `ord_order` 应收并写入 `htl_booking.order_id`：

```text
business_type     = HOTEL                  -- 必须等于门店 business_type
organization_id   = store.organization_id  -- 组织快照，不进入唯一键
trade_status      = DRAFT                  -- 见下方「订单交易状态契约」
refund_status     = NONE                   -- NONE/PARTIAL/FULL
total_amount      = 预订总价（最小货币单位）
idempotency       = hotel-booking:{bookingNo}
htl_booking.order_id 唯一：一个预订只关联一个订单
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
`hotel.room.manage` 权限校验；新日期必须晚于当前离店日期；库存不足返回 409。

## C 端酒店目录/报价（H5）

```text
GET  /business/hotel/catalog/hotels
GET  /business/hotel/catalog/hotels/{id}
POST /business/hotel/catalog/quotes
```

- 只读、按租户隔离，不要求门店上下文；
- 目录返回酒店、房型、房价计划；
- 报价按入住夜读取库存/每日房价，`available` 与总价同时返回；
- C 端酒店报价/预订已接入；支付仍按订单支付端点处理。

## C 端 KTV 预约（H5）

```text
GET  /me/reservations      # 当前会员的预约列表（按 customer_id 收窄）
POST /me/reservations      # 当前会员创建 KTV 预约，Idempotency-Key 幂等
```

- `POST /me/reservations` 只接受 `roomTypeId/startAt/endAt/partySize/contact`；
  租户、门店、业态从签名消费者上下文派生，`customerId` 从当前会员派生，客户端不能指定。
  历史消费者上下文未携带 `businessType` 时，该 C 端 KTV 预约入口按 `KTV` 处理。
- 权限：`reservation.create`；列表不额外要求权限，按当前会员过滤。
- 返回体与 B 端预约视图一致；到店后由门店分配具体包厢，C 端创建时不锁定包厢。

## C/B audience

- order /admin/** 与 B 前台 /business/reservations|orders|ktv/** 要求 saas-b。
- /business/hotel/catalog/** 要求 saas-c。
- /me/reservations 的 POST 要求 saas-c；GET 为 C 端会员自助读路径。
- 有 TenantContext 但缺 clientApp 的用户 token 拒绝。

Catalog 写端点（POST/PUT/DELETE /business/catalog/items）要求 saas-b audience；列表保持 C/B 共享。

C 端酒店：/business/hotel quotes|bookings 仅 saas-c；HOTEL 门店 store_id=102。

## 2.4.0 领域重构契约（订单 / 房态 / 房量 / 商品库存）

目标契约唯一说明见
[订单/房态/房量/商品库存目标契约](../../../40-domain/architecture/ORDER_ROOM_INVENTORY_TARGET.md)。
本节只记录**本服务对外契约与实现口径**。

### 订单交易状态（`ord_order`）

订单主表只表达交易事实，不再承载业态业务状态：

```text
trade_status  : DRAFT / PENDING_PAYMENT / PAID / COMPLETED / CANCELLED / VOIDED
refund_status : NONE / PARTIAL / FULL
```

- `SERVING`、`WAITING_SETTLEMENT`、`CHECKED_IN`、`DUE_OUT` 等**不得**写进订单主表；
  KTV 的会话阶段在 `ord_ktv_session.status`，酒店的在住阶段在 `htl_booking.status`；
- `CANCELLED`（用户取消）与 `VOIDED`（审批作废）**分开统计**，报表按 `VOIDED` 单列作废金额；
- 兼容列 `ord_order.status` 已在收口迁移 `V27__drop_domain_compat_columns.sql` 删除
  （`DROP INDEX idx_ord_order_store_status` + `DROP COLUMN status`），代码/报表/支付只读
  `trade_status` / `refund_status`；
- `organization_id = store.organization_id`、`business_type = store.business_type` 在写入时校验。

订单入口（`OrderController`，网关前缀 `/api/v1`）：

```text
POST /business/orders                         快速开台：创建 KTV 订单 + 包厢会话（ORDER 创建）
POST /business/orders/{id}/confirm            DRAFT -> PENDING_PAYMENT
POST /business/orders/{id}/cancel             CANCELLED（释放会话/库存）
POST /business/orders/{id}/void               VOIDED（按作废规则审批）
GET  /business/orders、/business/orders/{id}
```

OrderView 同时返回 `tradeStatus`、`refundStatus`、`settlementStatus`（后端派生，不落列）与
`sessionStatus`；前端不得再用单一 `status` 推断业务阶段。

### KTV 会话（`ord_ktv_session`）

```text
order_id UNIQUE                一个订单只关联一个当前会话
status   : RESERVED / OPEN / PAUSED / CLOSED / CANCELLED
organization_id                 创建时从订单快照
```

```text
POST /business/ktv/sessions/{id}/open|pause|resume|close|cancel
POST /business/orders/{id}/servers            服务人员点单
POST /business/orders/{id}/transfer           转台（计时继承）
```

开台/结台只改会话状态；结台生成 `ROOM_FEE` 明细并把交易状态推进到 `PENDING_PAYMENT`
（若已全额收款则 `COMPLETED`）。

### 酒店房态（Room 聚合）与房量（RoomAvailability）

房态三字段与 hold 见上文「房态与清扫状态（2.4.0 三字段）」。房态聚合入口
（`HotelRoomStateController`）：

```text
GET  /admin/hotel/rooms/board                 房态图（只看三字段，不返回单一 status）
PUT  /admin/hotel/rooms/{id}/housekeeping     清扫状态流转
PUT  /admin/hotel/rooms/{id}/out-of-order     维修/停售（同时调整房量 total）
GET  /admin/hotel/rooms/{id}/holds
POST /admin/hotel/rooms/{id}/holds            预留/锁房/自用
POST /admin/hotel/rooms/{id}/holds/{holdId}/release
```

房量是酒店核心域，**不是商品库存**，按 `tenant + store + room_type + stay_date` 唯一
（`organization_id` 是归属/报表快照，不进入唯一键）：

```text
GET /admin/hotel/availability?roomTypeId&from&to
PUT /admin/hotel/availability                 维护房型当日 total
```

- 预订创建：原子 `held + rooms`（`total - sold - held >= rooms`）；确认：`held -> sold`；
  取消：释放 held/sold；
- 入住不二次扣房量，退房不改房量；维修/停售逐日 `total -1`，恢复 `+1`（不允许低于 sold+held）；
- `htl_room_availability_hold` 是占用账本，不变量：`held = SUM(ACTIVE hold.rooms)`。

酒店订单读模型：

```text
GET /admin/hotel/orders?status=
```

返回 `bookingId/bookingNo/orderId/orderNo/organizationId/storeId/guestName/roomTypeId/roomType/
roomNo/checkInDate/checkOutDate/bookingStatus/tradeStatus/amountMinor/currency/createdAt`。

### 酒店 OTA 渠道 v1（channel booking）

外部 OTA 最小适配器（HMAC 鉴权 + 幂等预订 + 映射表），入口经网关 `/api/v1/open/hotel/v1/**`，
创建/查询/取消复用本服务既有 `HotelBookingApplicationService`，不绕过房量与订单逻辑：

```text
POST /v1/bookings                       创建（externalRef 幂等；新建 201 / 回放 200）
GET  /v1/bookings/{externalRef}         查询（实时 htl_booking 状态）
POST /v1/bookings/{externalRef}/cancel  取消（幂等；重复取消 200）
POST /v1/ari                            v1 契约占位：202 no-op，逐项 accepted=false
```

- 鉴权：`X-Channel-Code` + `X-Channel-Timestamp` + `X-Channel-Signature`（HMAC-SHA256 canonical
  request）；验签失败 401，无绕过开关；secret 由 env/K8s Secret 注入，未配置即拒绝；
- 映射表 `htl_channel_booking` 唯一键 `(channel_code, external_ref)`，唯一键兜底并发幂等；
- 外部请求不携带 tenant/organization/store/business，由渠道凭证 + `htl_hotel.store_id` +
  `tnt_store` 权威数据解析；
- 详见 [HOTEL_OTA_CHANNEL_V1.md](HOTEL_OTA_CHANNEL_V1.md)，契约见
  [hotel-channel-v1.yaml](../../../30-reference/contracts/openapi/hotel-channel-v1.yaml)。

### 通用商品库存（酒店与 KTV 共用）

库存是公共支撑域，不建立 Hotel Inventory 领域，也不与房间绑定。作用域：
`tenant_id + business_type + store_id + warehouse_id + material_id`
（`organization_id` 为归属/报表快照，不进入唯一键；Material/SKU 是门店级共享主数据）。

```text
GET  /admin/inventory/materials、/admin/inventory/transactions、/admin/inventory/costs
POST /admin/inventory/materials、/receipts、/adjustments、/stocktakes
POST /admin/inventory/transfers               跨门店/跨业态调拨
POST /admin/inventory/consumptions            客房领用/迷你吧/KTV 商品/订单加项（不传房间号）
```

- 写路径必须落在具体仓库：`warehouseId` 缺省取门店默认仓（懒创建 `DEFAULT`）；
- 目标门店的组织/业态以门店权威快照为准（请求声明不一致即 422）；
- 消费场景 `scene` = `ROOM_CONSUMPTION / MINIBAR_SALE / KTV_ITEM / ORDER_ITEM`，只传业务单号
  `sourceId`；流水来源类型与订单/账单联动，不跨表写订单/账单。

### 权限拆分（2.4.0）

| 权限码 | 用途 |
| --- | --- |
| `hotel.room.view` / `hotel.room.manage` | 房态图、房态/维修/保留 |
| `hotel.housekeeping.view` / `hotel.housekeeping.manage` | 清扫任务列表 / 流转 |
| `hotel.room.availability.view` / `hotel.room.availability.manage` | 房量查询 / 日历维护 |
| `hotel.booking.view` / `hotel.booking.create` | 预订查询 / 创建 |
| `hotel.folio.view` / `hotel.folio.manage` | 账务查看 / 收款退房 |
| `hotel.room.manage` / `hotel.folio.manage` | 入住 / 退房动作 |
| `inventory.view` / `inventory.manage` | 通用库存读写（含仓库/成本/调拨/消费） |
| `ktv.session.open` / `ktv.session.operate` / `ktv.session.correct_pause` | KTV 会话动作 |

`hotel.inventory.manage` **不再作为访问条件**（代码已无引用）；旧授权由租户域 V31 迁移映射到新码，
权限行本身由 `V34__retire_hotel_inventory_permission.sql` 删除授权后置 `DISABLED`。

### 数据库迁移（本次发布）

订单服务 `db/migration`：

| 版本 | 内容 |
| --- | --- |
| `V24__domain_boundary_foundation.sql` | `ord_order.trade_status/refund_status`；`htl_booking.organization_id/business_type/order_id 唯一`；`htl_room` 三状态；`htl_room_hold`；`htl_room_availability(+hold)`；`ord_ktv_session.organization_id/order_id 唯一`；库存 org/business/warehouse 列与仓库表 |
| `V25__inventory_warehouse.sql` | 库存作用域收紧：`organization_id/business_type/warehouse_id NOT NULL`、`uk_ord_inventory_stock_scope`、流水仓库索引 |
| `V26__demo_hotel_domain_seed.sql` | 清库 Kind 演示数据：HOTEL 门店 6 房型/60 房/31 天房量，移除旧 KTV 门店演示预订 |
| `V27__drop_domain_compat_columns.sql` | **破坏性收口（最终清理）**：删除 `ord_order.status` + `idx_ord_order_store_status`、`htl_room.status`、`htl_inventory_calendar`、`htl_inventory_hold`（清库重建下旧表无可迁移数据） |
| `V28__drop_order_hold_columns.sql` | 删除订单级挂单列 `ord_order.hold_reason/hold_at`（挂单/暂停由 `ord_ktv_session.status=PAUSED` 表达，端点 `/hold`、`/unhold` 已删） |
| `V29__rename_ktv_reservation_tables.sql` | `ord_reservation` → `ktv_reservation`、`ord_reservation_rule_config` → `ktv_reservation_rule_config`，索引同步改 `uk_ktv_*` / `idx_ktv_*` |
| `V30__htl_channel_booking.sql` | 酒店 OTA channel v1 映射表 `htl_channel_booking`（唯一键 `channel_code + external_ref`） |

租户服务 `db/migration`：`V29__hotel_room_permissions.sql`（房态/清扫/房量权限）、
`V31__permission_hardening.sql`（folio/housekeeping.view/inventory 权限 + 旧授权迁移）、
`V32__demo_hotel_store.sql`（HOTEL 门店 102 + 业态权益 + 门店经理绑定）、
`V34__retire_hotel_inventory_permission.sql`（删除 `hotel.inventory.manage` 授权并置 `DISABLED`）、
`V35__retire_hotel_action_permissions.sql`（`hotel.checkin/checkout/rate.update` 授权迁移到
`hotel.room.manage`/`hotel.folio.manage`/`hotel.room.availability.manage`，旧授权删除并置 `DISABLED`；
同时停用随 `/hold`、`/unhold` 端点删除而不再使用的 `order.hold`）。

> 收口状态：`ord_order.status`、`htl_room.status`、`htl_inventory_calendar`、`htl_inventory_hold` 由 V27
> 在二期清库发布中删除；`hotel.inventory.manage` 授权由租户 V34 删除并置 `DISABLED`。
> P2-3 收口（V28 删订单级挂单列、V29 预约表改名 `ktv_*`、租户 V35 旧动作权限/`order.hold` 迁移停用）
> 已合入代码，**随合并后的下一次清库发布生效**（`ktv_` 前缀在 `scripts/validate/table-prefixes.txt`
> 与 `ENGINEERING_RULES.md` 登记）。
> 验证门禁：`invoke-engineering-validation.ps1 -Scope Full`；E2E 覆盖 KTV 开台→结台→收款→作废、
> 酒店预订→入住→退房→清扫、房态/房量不变量与共享库存。
