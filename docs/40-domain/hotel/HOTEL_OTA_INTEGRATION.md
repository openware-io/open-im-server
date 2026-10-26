# 酒店 OTA / 渠道接入规范

[中文](HOTEL_OTA_INTEGRATION.md) | [English](HOTEL_OTA_INTEGRATION.en.md)

> 状态：`draft`（`develop/2.4.0-hotel`）  
> 定位：**对外接入契约**，不是某一家 OTA 的私有文档。  
> 原则：先定契约、再做适配器；OTA 是销售渠道，不是第二套酒店域。

契约文件：[hotel-channel-v1.yaml](../../30-reference/contracts/openapi/hotel-channel-v1.yaml)。

## 1. 范围

本规范覆盖：

- 酒店、房型、房价计划、库存与限制（ARI）的对外映射；
- OTA 订单进入、确认、取消、修改、退款状态回传；
- 渠道鉴权、幂等、重试、错误码、版本；
- 渠道经理（Channel Manager）作为聚合层的边界。

**明确不包含**：真实 OTA 适配器、沙箱环境、渠道经理实现。MVP 只做规范与内部渠道模型。

不覆盖：

- 某家 OTA 的专有字段和签名细节（放在 `channel-adapters/<code>`）；
- 渠道结算/对账的财务实现；
- 渠道经理完整产品。

## 2. 角色与边界

```text
OTA / Channel Manager  <-->  SaaS Hotel Channel API  <-->  Hotel Domain
```

- OTA：外部销售渠道；
- Channel Manager：聚合多个 OTA 的房态/房价/订单同步中枢；
- OpenChat：权威库存、价格、订单和入住状态；
- 任何渠道都不能直接写 `res_inventory_calendar` 或 `ord_hotel_booking`，只能走渠道契约。

## 3. 渠道模型

新增/预留：

| 模型 | 关键字段 | 说明 |
| --- | --- | --- |
| `Channel` | `code`, `name`, `type`（OTA/CHANNEL_MANAGER/DIRECT）, `status` | 渠道注册 |
| `ChannelCredential` | `channelId`, `keyId`, `secretRef`, `rotatedAt` | 密钥只存引用，不落明文 |
| `ChannelMapping` | `channelId`, `hotelId`, `roomTypeId`, `ratePlanId`, `externalHotelId`, `externalRoomTypeId` | 外部 ID 映射 |
| `ExternalBookingRef` | `channelId`, `externalRef`, `bookingId`, `status` | 外部订单关联，唯一键 `(channelId, externalRef)` |
| `ChannelSyncLog` | `channelId`, `direction`, `payloadHash`, `status`, `attempts`, `lastError` | 同步审计与重放 |

## 4. 对外 API（v1 草案）

统一前缀：

```text
/api/v1/open/hotel
```

### 4.1 ARI 推送（OpenChat → OTA / Channel Manager）

```text
POST /api/v1/open/hotel/v1/ari
```

请求包含：

```json
{
  "channelCode": "sandbox",
  "hotelRef": "H-1001",
  "roomTypeRef": "DLX-K",
  "ratePlanRef": "BAR",
  "stayDate": "2026-11-01",
  "total": 20,
  "sold": 14,
  "stopSell": false,
  "priceMinor": 36800,
  "currency": "USD",
  "minStay": 1,
  "version": 12
}
```

要求：

- `channelCode + hotelRef + roomTypeRef + stayDate` 唯一；
- `version` 单调递增，旧版本不得覆盖新版本；
- 批量推送使用同一条消息的数组，单条失败不影响其他条，但必须返回逐条结果。

### 4.2 OTA 订单进入（OTA → OpenChat）

```text
POST /api/v1/open/hotel/v1/bookings
```

请求包含：

```json
{
  "channelCode": "sandbox",
  "externalRef": "OTA-20261101-0001",
  "hotelRef": "H-1001",
  "roomTypeRef": "DLX-K",
  "ratePlanRef": "BAR",
  "checkIn": "2026-11-01",
  "checkOut": "2026-11-03",
  "rooms": 1,
  "guests": 2,
  "guest": { "name": "ZHANG SAN", "phone": "+8613800000000" },
  "amountMinor": 73600,
  "currency": "USD",
  "paymentResponsibility": "OTA_COLLECT"
}
```

要求：

- `externalRef` 在渠道内唯一，重复提交必须返回同一订单，不得重复占库；
- `paymentResponsibility`：`OTA_COLLECT` / `HOTEL_COLLECT` / `PREPAID`；
- 库存不足返回 `409 INVENTORY_UNAVAILABLE`，不得部分确认；
- 成功后返回 `bookingNo`、`status`、`confirmedAt`。

### 4.3 取消 / 修改

```text
POST /api/v1/open/hotel/v1/bookings/{externalRef}/cancel
POST /api/v1/open/hotel/v1/bookings/{externalRef}/modify
```

- 取消按房价计划政策计算，返回可退金额与退款状态；
- 修改只允许日期/房型/数量等明确字段，必须重新校验库存；
- 所有操作幂等，重复取消返回当前状态而不是报错。

### 4.4 状态查询

```text
GET /api/v1/open/hotel/v1/bookings/{externalRef}
```

返回订单状态、入住/退房、支付/退款状态，不返回不必要的住客 PII。

### 4.5 事件（可选推送）

```text
hotel.booking.confirmed
hotel.booking.cancelled
hotel.booking.no_show
hotel.ari.updated
```

事件只做通知，**状态以查询接口为准**；事件消费方必须幂等。

## 5. 鉴权与安全

- 生产推荐 mTLS；至少 HMAC-SHA256 签名；
- 签名包含 `timestamp`、`nonce`、`bodyHash`；时间窗 5 分钟，nonce 防重放；
- 每个渠道独立凭据，按 `channelCode` 隔离；
- 密钥只存 Secret 引用，禁止入日志/仓库；
- 外部订单只传必要住客信息，证件号不在渠道接口明文传输；
- 所有请求记录 `channelCode + externalRef + requestId` 便于对账。

## 6. 幂等、重试与错误码

### 幂等

- 写接口要求 `Idempotency-Key`；
- OTA 订单以 `(channelCode, externalRef)` 唯一；
- 重复请求返回首次结果，HTTP 200/201 语义保持一致。

### 重试

- `429`、`5xx` 可重试，指数退避；
- `4xx` 业务错误不自动重试，返回明确错误码；
- 渠道必须能处理重复推送和乱序 ARI。

### 错误码

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 字段缺失/格式错误 |
| 401 | `UNAUTHORIZED` | 签名/凭据无效 |
| 403 | `CHANNEL_FORBIDDEN` | 渠道无权访问该酒店 |
| 404 | `MAPPING_NOT_FOUND` | 外部 ID 未映射 |
| 409 | `INVENTORY_UNAVAILABLE` | 库存不足/关房 |
| 409 | `BOOKING_STATE_CONFLICT` | 当前状态不允许操作 |
| 422 | `POLICY_REJECTED` | 取消/修改不符合政策 |
| 429 | `RATE_LIMITED` | 限流 |
| 500 | `INTERNAL_ERROR` | 服务端错误，可重试 |

## 7. 版本与兼容

- URL 带主版本：`/v1/`；
- 新增可选字段向后兼容；删除/改语义必须升主版本；
- 旧版本至少保留一个发布周期，弃用需公告和迁移窗口；
- 渠道适配器与核心契约解耦，禁止在核心域写 OTA 专有字段。

## 8. 规范实现与后续沙箱

本期只交付规范与内部渠道模型：

- OpenAPI/接口文档评审通过；
- `Channel` / `ChannelMapping` / `ExternalBookingRef` 模型落地；
- 不实现真实 OTA 适配器、不提供沙箱环境；
- 后续接真实渠道时，再补沙箱、契约测试和映射核对。

沙箱后续必须覆盖：正常下单、重复下单、库存不足、取消、重复取消、签名失败、乱序 ARI。

## 9. 与内部模型的关系

```text
ChannelMapping -> Hotel / RoomType / RatePlan
ExternalBookingRef -> HotelBooking
ARI -> InventoryCalendar / DailyRate
```

- 渠道只通过应用服务操作领域，不直接写表；
- 外部订单创建仍走酒店预订应用服务，享受同样的库存、支付、审计规则；
- `paymentResponsibility` 决定支付由 OTA 还是酒店承担，但**不改变**酒店订单状态机。

## 10. 待确认

1. 真实 OTA 首发时间（本期不接，只做规范）；
2. 优先接哪家：携程、美团、飞猪，还是通过渠道经理？
3. OTA 结算模式：OTA 代收、酒店现付、预付？
4. 是否需要虚拟卡/信用卡担保？
5. 渠道经理选型：自建、SiteMinder、德比，或其他？
6. 对账频率与差异处理 SLA？

## 11. 相关文档

- [酒店实现方案](HOTEL_IMPLEMENTATION_PLAN.md)
- [酒店决策](HOTEL_DECISIONS.md)
- [API 契约目录](../../30-reference/contracts/README.md)
- [数据库迁移标准](../../80-development/standards/23_DATABASE_MIGRATION_STANDARD.md)
