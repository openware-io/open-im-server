# 酒店业务实现方案

[中文](HOTEL_IMPLEMENTATION_PLAN.md) | [English](HOTEL_IMPLEMENTATION_PLAN.en.md)

> 状态：`draft`（`develop/2.4.0-hotel`）  
> 定位：酒店业务的技术基线；先对齐边界和模型，再拆任务。  
> 原则：不重复造订单/支付/客户能力；酒店特有库存与房价单独建模。

## 1. 目标与范围

### MVP 的定义

MVP 不是“先做一半功能”，而是：

> 能在一个真实集团酒店上线、跑通一笔真实订单闭环的最小产品切片。

判定标准：

```text
真实用户能搜索、报价、下单、支付
真实库存不超卖
真实前台能办理入住/退房（Web + 移动端）
真实运营能维护酒店、房型、房价、库存
真实权限、租户隔离、审计可用
出问题能取消/退款/回滚，有监控和对账
```

缺任何一项，都还是演示，不是 MVP。

### MVP 必须完成

```text
酒店/门店资料
房型 + 物理房间
房价计划 + 每日价格
按日期范围的库存日历
搜索 → 报价 → 下单 → 支付 → 确认
取消 / 超时未付 / no-show
入住 → 退房（Admin Web + 移动前台）
后台房价与库存维护
OTA 对外接入规范 + 沙箱验证
订单、支付、审计、权限
```

移动前台参考 KTV B 端工作台：

```text
今日到店 / 在住 / 待退房
办理入住 / 分配房号
办理退房 / 房间转清扫
订单查询 / 房态查看
```

### MVP 明确不做

| 项 | 结论 |
| --- | --- |
| 发票 | 暂不接入；字段预留，后续接财务/税控 |
| 公安入住登记 | 暂不接入；接口和字段预留 |
| 动态收益定价、自动调价 | 不做 |
| 跨集团 CRS / 子集团层级 | 不做；当前 `tenant=集团`、`store=门店` |
| 复杂套餐、会议宴会、长租 | 不做 |
| 多币种结算 | 沿用现有币种能力，默认人民币 |
| 会员等级价、企业协议价 | 二期 |
| OTA 真实渠道上线 | 规范、沙箱、适配器骨架进 MVP；**是否首发即接真实 OTA，需要业务确认** |
| 渠道经理全量同步 | 不在 MVP；由 OTA 规范预留 |

### MVP 之外的 OTA 边界

OTA 不是“以后再说”，而是 MVP 就要立住的对外契约：

- 本期交付 `Hotel Channel v1` 对外接入规范、鉴权、幂等、错误码、版本策略和沙箱用例；
- 至少完成一个**沙箱渠道适配器**，验证 ARI 推送、订单拉取、取消回传；
- 真实 OTA（携程/美团/飞猪等）上线时间由业务确认，不阻塞自营渠道闭环；
- 渠道经理（Channel Manager）作为 OTA 的聚合层，本期只定义接口，不实现完整产品。

### 接入模式：一套酒店域，两种入口

需求同时包含：

```text
单店：休闲娱乐 -> 酒店 -> 本店房型/库存
集团：OpenChat 服务中心 -> 酒店 -> 集团内酒店/门店
```

不按入口复制两套业务，统一为：

```text
HotelScope：SINGLE_STORE | GROUP
SalesEntry：SERVICE_CENTER | LEISURE | GROUP_INTERNAL
Visibility：STORE | GROUP
```

- **先做集团内酒店**：`HotelScope=GROUP`，从 OpenChat 服务中心进入，集团内门店共享酒店资料、房型和库存视图；
- 单店后续接入：同一套酒店域，`HotelScope=SINGLE_STORE`，入口改为休闲娱乐，库存只在本店可见；
- 入口是**渠道配置**，不是领域边界；订单、库存、支付仍走同一套服务；
- 每次查询必须带 `tenantId` + `storeId`/`groupId`，由 `Visibility` 决定可见范围。

现有 SaaS 模型已经能承载集团：`tenantId` 对应集团/租户，`storeId` 对应酒店门店。因此一期：

```text
GROUP         = tenant 级酒店目录 + 门店库存
SINGLE_STORE  = store 级酒店目录 + 本店库存
```

不新增 `groupId`；只有出现“集团下再分子集团”时才扩展层级。

这样不需要为“单店/集团”分叉代码；以后接 OTA 时，OTA 只是新增一个 `SalesEntry=OTA` 和渠道映射。

## 2. 原型现状与缺口

现有原型：`open-chat-app/web/miniservice/a380`。

| 原型页面 | 已表达 | 真实实现缺口 |
| --- | --- | --- |
| 酒店列表 | 搜索、排序、距离、评分、起价 | 城市/商圈/日期筛选、真实库存与价格 |
| 酒店详情 | 图片、标签、地址、房型套餐 | 酒店政策、设施、房型设施、取消规则 |
| 房型套餐 | 房型名、床型、面积、早餐、价格、库存 | 房价计划、每晚价格、税费、库存日历 |
| 预订表单 | 入住日期、到店时间、离店日期、客房数、住客数、姓名/手机 | 真实库存校验、报价明细、支付、取消政策 |
| 订单 | 本机 mock | 支付、确认、取消、入住/退房、发票 |
| B 端管理 | `open-saas-admin` 原型：资料、支付策略、房型房价、库存日历、订单、入住/退房 | 真实 BFF/API；发票、公安登记、渠道同步 |

**结论**：原型只覆盖“展示 + 提交表单”，不能直接作为领域模型依据。需要先确认第 11 节的业务决策。

## 3. 领域边界

### 方案对比

| 方案 | 做法 | 优点 | 风险 |
| --- | --- | --- | --- |
| A. 全部塞进 order | 酒店订单、库存、房价都进 `platform-order-service` | 改动最少 | 订单域承担库存/房价，边界失控 |
| B. 扩展 resource + order | `platform-resource-service` 管酒店/房型/房价/库存；`platform-order-service` 管预订订单 | 复用现有资源、订单、支付、客户；边界清晰 | 需要给 resource 增加酒店子域 |
| C. 新建 hotel-service | 独立部署、独立库 | 长期隔离最好 | MVP 分布式事务、后台 BFF、运维成本高 |

### 推荐：B，先模块化，后按触发器拆分

```text
platform-resource-service
  domain/hotel
    Hotel / RoomType / Room / RatePlan / InventoryCalendar / Hold
  application/hotel
  api/hotel
  infra/persistence/hotel

platform-order-service
  domain/hotelbooking
    HotelBooking / BookingNight / GuestSnapshot
  application/hotelbooking
  api/hotelbooking

platform-admin-service (BFF)
  酒店资料、房价、库存、订单、入住/退房

common-payment-service
  支付、退款、对账

platform-customer-service
  会员、住客档案、积分

common-audit-service
  酒店/房价/库存/订单审计
```

拆分触发条件（满足任一再新建 `platform-hotel-service`）：

- 酒店团队独立开发/发布；
- 酒店库存 QPS 或数据量显著高于 resource 其他业务；
- 需要独立数据库/独立 SLA；
- OTA 渠道同步成为独立子系统。

## 4. 核心模型

### 资源域（`gv_saas`，`res_*`）

| 模型 | 关键字段 | 说明 |
| --- | --- | --- |
| `Hotel` | `tenantId`, `storeId`, `name`, `city`, `address`, `star`, `checkInTime`, `checkOutTime`, `status` | 酒店/门店资料 |
| `HotelPolicy` | `hotelId`, `accessMode`, `entryPoints`, `paymentMode`, `depositRatioBps`, `cancelPolicy`, `invoiceMode` | 接入模式、支付策略、取消政策；在店铺管理中维护 |
| `HotelRoomType` | `hotelId`, `code`, `name`, `bedType`, `area`, `maxOccupancy`, `amenities`, `images` | 房型，不直接等于物理房 |
| `HotelRoom` | `roomTypeId`, `roomNo`, `floor`, `status` | 物理房间，入住时分配 |
| `RatePlan` | `roomTypeId`, `code`, `name`, `mealPlan`, `cancelPolicy`, `guarantee` | 房价计划 |
| `DailyRate` | `ratePlanId`, `stayDate`, `priceMinor`, `currency`, `minStay`, `closed` | 每日价格/限制 |
| `InventoryCalendar` | `roomTypeId`, `stayDate`, `total`, `sold`, `held`, `stopSell`, `version` | 每日库存，唯一键 `(tenantId, roomTypeId, stayDate)` |
| `InventoryHold` | `bookingId`, `roomTypeId`, `checkIn`, `checkOut`, `qty`, `expiresAt`, `status` | 下单占库存，超时释放 |

金额统一用最小货币单位 `*_minor` + `currency`，遵守现有币种规范。

### 订单域（`open_saas`，`ord_*`）

| 模型 | 关键字段 | 说明 |
| --- | --- | --- |
| `HotelBooking` | `bookingNo`, `tenantId`, `storeId`, `hotelId`, `roomTypeId`, `ratePlanId`, `checkIn`, `checkOut`, `roomCount`, `guestCount`, `status`, `amountMinor`, `currency` | 酒店预订聚合根 |
| `HotelBookingNight` | `bookingId`, `stayDate`, `priceMinor`, `taxMinor`, `currency` | 每晚价格快照 |
| `HotelGuest` | `bookingId`, `name`, `phone`, `idType`, `idNoMasked` | 入住人快照；证件号加密/脱敏 |

**不复用 KTV `Reservation` 的 slot 语义**：KTV 按时间点/包厢，酒店按“日期区间 + 房型库存”；强行复用会让两边都变形。

## 5. 状态机

### 预订

```text
PENDING_PAYMENT
  -> CONFIRMED       支付成功
  -> CANCELLED       用户取消/超时未付
CONFIRMED
  -> CHECKED_IN      前台入住
  -> CANCELLED       按取消政策取消
  -> NO_SHOW         到店日未入住
CHECKED_IN
  -> CHECKED_OUT     退房
CHECKED_OUT
  -> COMPLETED       账务结清
```

### 库存占用

```text
HELD -> CONSUMED    支付成功，转为已售
HELD -> RELEASED    取消/超时
CONSUMED -> RELEASED 取消且可退库存（按政策）
```

## 6. 关键流程

### 搜索与报价

1. 输入城市/酒店、入住/离店日期、房间数、住客数；
2. 查询 `InventoryCalendar` 可用量（`total - sold - held`）；
3. 查询 `DailyRate` 计算每晚价格，返回总价、税费、取消政策；
4. 报价只读，不占库存；下单时才创建 hold。

### 下单

1. 校验日期、房型、房价计划、库存；
2. `HotelBooking` 创建为 `PENDING_PAYMENT`；
3. 同一事务内创建 `InventoryHold`，原子扣减可售量；
4. 返回支付参数；库存不足返回 `409 INVENTORY_UNAVAILABLE`。

### 支付确认

1. 支付回调按 `paymentId` 幂等；
2. `HotelBooking` 从 `PENDING_PAYMENT` → `CONFIRMED`；
3. `InventoryHold` → `CONSUMED`，写 `sold`；
4. 发 `hotel.booking.confirmed` 事件，通知客户与酒店。

### 取消 / no-show

- 按 `RatePlan.cancelPolicy` 计算可退金额；
- 可退库存则 `CONSUMED` → `RELEASED`；
- 退款走支付服务，状态 `REFUNDING` → `REFUNDED`；
- no-show 由定时任务在到店日次日执行，收 no-show 费用（按政策）。

### 入住 / 退房

1. 前台选择具体 `HotelRoom`，写 `roomId`；
2. `CHECKED_IN`，房间状态 `OCCUPIED`；
3. 退房 `CHECKED_OUT`，房间状态 `CLEANING` → `AVAILABLE`；
4. 账务结清后 `COMPLETED`。

## 7. 库存与并发

- 唯一键：`(tenant_id, room_type_id, stay_date)`；
- 原子扣减：

```sql
UPDATE res_inventory_calendar
SET held = held + :qty, version = version + 1
WHERE tenant_id = :tenantId
  AND room_type_id = :roomTypeId
  AND stay_date BETWEEN :checkIn AND :checkOut - 1
  AND total - sold - held >= :qty
  AND stop_sell = 0;
```

- 任一天失败则整段回滚，禁止部分占库；
- `held` 有 `expires_at`，定时任务释放；
- 支付成功用 `bookingId + paymentId` 幂等，重复回调不重复扣减；
- 监控 `overbooking_count`，必须为 0；冲突率升高告警。

### 支付策略（店铺配置，不写死）

`HotelPolicy.paymentMode` 支持：

| 模式 | 行为 | 订单初始状态 |
| --- | --- | --- |
| `PREPAY` | 下单后全额支付，支付成功确认 | `PENDING_PAYMENT` |
| `PAY_AT_HOTEL` | 到店支付，先确认并保留库存 | `CONFIRMED`（未收款） |
| `DEPOSIT` | 支付定金确认，尾款到店/离店结清 | `PENDING_DEPOSIT` → `CONFIRMED` |

店铺管理里配置：

- 支付模式；
- 定金比例（`depositRatioBps`，如 3000 = 30%）；
- 取消/未入住政策；
- 是否支持发票（后续）。
同一酒店不同门店可以不同；集团默认值可被门店覆盖。

## 8. API 草案

C 端（经 gateway）：

```text
GET  /api/v1/hotels?city=&checkIn=&checkOut=&guests=&entry=service-center|leisure
GET  /api/v1/hotels/{hotelId}?checkIn=&checkOut=
GET  /api/v1/hotels/{hotelId}/room-types?checkIn=&checkOut=
POST /api/v1/hotel-bookings
GET  /api/v1/hotel-bookings/{bookingId}
POST /api/v1/hotel-bookings/{bookingId}/cancel
```

后台（经 admin BFF）：

```text
POST /api/v1/admin/hotels
PUT  /api/v1/admin/hotels/{hotelId}
PUT  /api/v1/admin/hotels/{hotelId}/policy
POST /api/v1/admin/hotels/{hotelId}/room-types
PUT  /api/v1/admin/hotels/{hotelId}/inventory
POST /api/v1/admin/hotel-bookings/{bookingId}/check-in
POST /api/v1/admin/hotel-bookings/{bookingId}/check-out
```

内部：

```text
POST /internal/hotels/availability
POST /internal/hotels/holds
POST /internal/hotels/holds/{holdId}/consume
POST /internal/hotels/holds/{holdId}/release
```

所有写接口要求 `X-Tenant-Context`、权限与 `Idempotency-Key`；金额字段用最小单位。

## 9. 权限、安全、审计

- 租户/门店隔离：所有查询带 `tenantId`/`storeId`，禁止跨租户；
- 权限：`hotel.view`、`hotel.manage`、`hotel.inventory.manage`、`hotel.booking.view`、`hotel.booking.checkin`、`hotel.booking.cancel`；
- 住客 PII：手机号、证件号加密/脱敏，日志禁止原文；
- 支付：不落卡号/CVV，只存支付单号；
- 审计：酒店/房型/房价/库存/订单/入住/退房/退款全链路；
- 中国合规：入住登记、证件类型、公安上传接口预留（是否接入需确认）。

## 10. 可观测性与测试

指标：

```text
hotel.search.latency
hotel.booking.created / confirmed / cancelled
hotel.inventory.conflict
hotel.inventory.hold_expired
hotel.payment.callback_lag
hotel.overbooking_count   # 必须 0
hotel.checkin.completed
```

测试：

- 领域单测：日期区间、价格、取消政策、状态迁移；
- 集成测试：并发下单不超卖、支付回调幂等、hold 过期释放；
- 契约测试：C 端/后台/内部 API；
- E2E：原型主流程 + 支付成功/失败/取消/退款。

## 11. 已确认与剩余问题

| # | 问题 | 结论 |
| --- | --- | --- |
| 1 | 单店、连锁还是多门店？ | 三者都要支持；**先做集团内酒店**，同一套酒店域，通过 `HotelScope` / `SalesEntry` 区分入口 |
| 2 | 库存按房型还是具体房间？ | 按房型卖，入住时分配具体房间 |
| 3 | 支付模式？ | **店铺配置**：预付 / 到店付 / 定金；不写死 |
| 4 | 取消政策？ | 按房价计划配置，默认入住前 24h 免费取消 |
| 5 | 是否接 OTA/渠道经理？ | OTA **要做**：MVP 交付对外接入规范 + 沙箱；真实 OTA 上线范围待确认。渠道经理是可选聚合层，模型预留 `channel` / `externalRef` |
| 6 | 前台操作端？ | 先 admin Web，移动端二期 |
| 7 | 发票/押金/公安登记？ | 仍需产品确认；字段和接口先预留 |
| 8 | 会员/积分/优惠券？ | 复用现有 customer/marketing，MVP 只做优惠券抵扣 |
| 9 | 多币种？ | 沿用现有币种能力，MVP 默认人民币 |
| 10 | 原型范围？ | C 端已有服务中心酒店流程；B 端原型在 `open-saas-admin` 代码内补建；不另建独立原型项目 |

## 12. 迭代计划

| 阶段 | 内容 | 产出 |
| --- | --- | --- |
| S0 决策 | 回答第 11 节，确认原型 | 决策记录、范围冻结 |
| S1 模型 | 酒店/房型/房价/库存/订单模型与迁移 | DDL、领域模型、单测 |
| S2 库存 | 搜索、报价、hold、并发、过期释放 | 可用性 API、库存测试 |
| S3 订单支付 | 下单、支付回调、确认、取消/退款 | 订单 API、支付集成 |
| S4 前台 | 入住、退房、房态、订单列表 | Admin BFF、前台页面 |
| S5 上线 | 灰度、监控、对账、SOP | 发布清单、运行手册 |

## 13. 验收标准

- 并发 100 次抢同一日期最后一间房，成功数不超过库存；
- 支付重复回调不重复确认/扣库存；
- 取消按政策计算退款，库存正确释放；
- 跨租户访问返回 403/404，不泄露数据；
- 所有写操作有审计；
- `hotel.overbooking_count = 0`；
- 原型主流程可完整走通：搜索 → 房型 → 下单 → 支付 → 确认 → 入住 → 退房。

## 14. 相关文档

- [酒店决策与待确认问题](HOTEL_DECISIONS.md)
- [资源服务](../../50-services/platform/platform-resource-service/README.md)
- [订单服务](../../50-services/platform/platform-order-service/README.md)
- [支付服务](../../50-services/common/common-payment-service/README.md)
- [数据库迁移标准](../../80-development/standards/23_DATABASE_MIGRATION_STANDARD.md)
- [订单/预约状态机](../../20-architecture/diagrams/08-order-reservation-state.md)
