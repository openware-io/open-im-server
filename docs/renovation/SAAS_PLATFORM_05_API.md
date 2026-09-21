# SaaS 多业态接口与事件契约

> **变更记录（v2）**：①新增 IM 授权登录（OAuth 2.0）与用户信息同步契约（§3.2）；②订单履约 API 收敛为 KTV 首发，酒店/足浴标为后续。

## 1. 契约范围

本文是 `SAAS_PLATFORM_02_SERVICE` 与 `SAAS_PLATFORM_03_APP` 的 HTTP/MQ 实施基线。外部请求只进入 Gateway；Admin 和 B 端 App 不访问内部端口、数据库或 `/internal/**`。

统一版本：REST `/api/v1`，B 端 Header `X-Client-Contract: business-v1`，Admin Header `X-Client-Contract: admin-v1`。已有 C 端协议按现有兼容文档维护，新增 SaaS 业务不得修改旧接口语义。

## 2. 通用协议

### 2.1 请求头

| Header | 必填 | 说明 |
| --- | --- | --- |
| `Authorization` | 除公开接口外 | Bearer SaaS 账号 Token |
| `X-Tenant-Context` | 经营接口 | 短期签名租户上下文 Token |
| `X-Client-Contract` | 是 | `business-v1` 或 `admin-v1` |
| `Idempotency-Key` | 所有写接口 | UUID；有效期 24 小时 |
| `X-Request-Id` | 否 | 客户端关联 ID；服务端无效时重新生成 |
| `Accept-Language` | 否 | 门店语言，默认租户语言 |

服务端禁止信任请求体中的 `tenantId`、`operatorId`、`storeId` 作为权限依据；它们只能用于业务参数，最终以上下文和授权关系校验。

### 2.2 成功与失败

非列表成功：

```json
{"data": {}, "requestId": "req_01", "serverTime": "2026-08-17T10:00:00Z"}
```

列表成功：

```json
{"items": [], "page": 1, "pageSize": 20, "total": 0, "requestId": "req_01", "updatedAt": "2026-08-17T10:00:00Z"}
```

失败：

```json
{"code":"ORDER_VERSION_CONFLICT","message":"订单已被其他操作更新","requestId":"req_01","retryable":false,"fieldErrors":{}}
```

HTTP 语义：`400` 参数错误、`401` 身份失效、`403` 权限/数据范围不足、`404` 不存在或不可见、`409` 状态/幂等/资源冲突、`422` 业务规则拒绝、`429` 限流、`5xx` 服务故障。

### 2.3 通用字段规则

- ID 和订单号 JSON 使用字符串；时间为 RFC3339 UTC；金额为字符串并同时返回币种。
- 单号（订单号/预约号）为**新建单据**生成，格式 `<前缀><yyyyMMdd><当日序号>`：订单 `O202609190001`、预约 `R202609190001`。日期是门店营业日（`Asia/Shanghai` + 04:00 切点），序号按租户每日从 `0001` 起、最小定宽 4 位（超 9999 自然加宽）。历史单号（`O<毫秒时间戳>`、`R<UUID>`）保持不变；客户端只能当**不透明字符串**展示/搜索，不得解析、截断或做格式校验。序号服务不可用时创建直接失败（503 `DOC_NO_SEQUENCE_UNAVAILABLE`），不会返回不符合规则的单号。
- 分页参数 `page>=1`、`pageSize 1..100`；默认 `pageSize=20`。
- 排序字段必须来自服务端白名单；客户端不能传 SQL 字段。
- 写请求的幂等键重复时返回第一次结果和相同 `requestId`，不重复执行。
- 未知枚举、过期上下文 Token、非法门店范围和错误币种均拒绝。

## 3. 认证与上下文接口

### 3.1 选择经营上下文

`GET /api/v1/auth/contexts`

响应 `items`：`{contextId,tenantId,tenantName,organizationId?,organizationName?,storeId?,storeName?,roles[],scopeType}`。

`POST /api/v1/auth/context/select`

请求：`{contextId}`。响应：`{tenantContextToken,expiresAt,tenantId,organizationId?,storeId?,authorizationVersion,permissions[],allowedActions[]}`。

规则：上下文只能从认证服务返回的集合中选择；Token 默认 30 分钟；角色版本变化立即失效；切换上下文不得继续使用旧缓存。

### 3.2 IM 授权登录与用户信息同步（入口打通）

IM 提供第三方授权方案：SaaS 在 IM 开放平台注册为「第三方应用/模块」并登记 `client_id`、回调和 scope；confidential 后端凭据只由服务端安全托管，Flutter/Electron/H5 public client 不持有 secret。C 端用户再通过 OAuth 2.0（Authorization Code + PKCE）完成**用户级授权**，授权 SaaS 在指定 `scope` 内访问其 IM 身份。

| 方法与路径 | 说明 |
| --- | --- |
| `GET /api/v1/oauth/im/authorize` | SaaS 跳转 IM 授权页，携带 `client_id/redirect_uri/scope/state`；IM 展示授权确认 |
| `POST /api/v1/oauth/im/token` | SaaS 用 `code` 换令牌与授权范围内 userinfo |
| `GET /api/v1/identity/userinfo` | 授权范围内返回昵称/头像/手机号（脱敏），供 SaaS 同步 |
| `POST /api/v1/identity/oauth/im/callback` | BFF 服务端校验 code、PKCE、state/nonce 并建立按应用隔离的 HttpOnly SaaS Session |

用户信息同步（授权范围内 IM → SaaS）：昵称/头像/手机号通过 OAuth userinfo 拉取 + `UserProfileChanged` 事件推送；SaaS 落 `idt_` 资料与绑定关系，不拉取 IM 聊天/好友/群组数据。解绑/撤销授权后 SaaS 账号保留业务数据，仅移除 IM 登录方式。

- **应用登记**：SaaS 在 IM 开放平台注册应用、回调 origin 和 scope；审核及安装授权由平台治理流程决定，不能以“接入即授权”替代用户同意。
- **用户级授权（scope）**：用户首次进入 SaaS 时授权指定 `scope`（昵称/头像/手机号等），可随时在 IM 侧撤销；撤销后 SaaS 仅移除 IM 登录方式，业务数据保留。

## 4. 租户与 IAM Admin API

| 方法与路径 | 请求要点 | 权限/结果 |
| --- | --- | --- |
| `POST /api/v1/admin/sdk/tenants` | `{tenantCode,name,defaultLocale,defaultTimezone}` | `platform.tenant.manage`；创建租户和默认配置 |
| `PUT /api/v1/admin/sdk/tenants/{id}/status` | `{status,reason}` | 平台运营；写审计 |
| `POST /api/v1/admin/tenant/organizations` | `{code,name}` | `tenant.tenant.manage` |
| `POST /api/v1/admin/tenant/stores` | `{organizationId,code,name,countryCode,regionCode,timezone,currency,locale,taxProfile,businessDayCutoff}` | 租户范围；初始化门店配置 |
| `PUT /api/v1/admin/tenant/stores/{id}` | 仅允许门店配置字段 | `tenant.tenant.manage`；不可跨租户 |
| `GET /api/v1/admin/iam/users` | `page,pageSize,keyword?,storeId?,status?` | `iam.user.manage` |
| `POST /api/v1/admin/iam/users` | `{accountId,roles:[{roleId,scopeType,organizationId?,storeId?}]}` | 不能赋予超出操作者范围的角色 |
| `PUT /api/v1/admin/iam/users/{id}/roles` | `{roles,reason}` | 递增授权版本并审计 |
| `GET /api/v1/admin/iam/roles` | `scopeType?` | `iam.role.manage` |
| `POST /api/v1/admin/iam/roles` | `{code,name,permissions[]}` | 只能复制自身拥有的权限 |
| `GET /api/v1/admin/audits` | `page,pageSize,action?,resourceType?,operatorId?,fromAt?,toAt?` | `audit.view` |

## 5. 目录、资源与预约 API

### 5.1 目录

| 方法与路径 | 请求要点 |
| --- | --- |
| `GET /api/v1/admin/catalog/items` | `itemType?,status?,keyword?` |
| `POST /api/v1/admin/catalog/items` | `{itemType,name,description?,unit,enabled}` |
| `PUT /api/v1/admin/catalog/items/{id}` | 只能修改未结算未来交易使用的配置；历史订单不变 |
| `POST /api/v1/admin/catalog/prices` | `{itemId,storeId?,currency,startAt,endAt,unitPrice,taxRule}` |
| `GET /api/v1/business/catalog` | B App 当前门店可用目录和价格快照 |

### 5.2 资源与排班

| 方法与路径 | 请求要点 |
| --- | --- |
| `GET /api/v1/business/resources` | `resourceType?,status?,at?`；返回状态、占用订单摘要和 `updatedAt` |
| `POST /api/v1/admin/resources` | `{storeId,resourceType,resourceCode,name,capacity,attributes}` |
| `PUT /api/v1/admin/resources/{id}` | 禁止修改正在使用资源的类型；停用需无有效占用 |
| `POST /api/v1/admin/resources/schedules` | `{resourceId,startAt,endAt,scheduleType}` |
| `PUT /api/v1/admin/resources/{id}/status` | `{status,reason}`；后端检查占用 |

### 5.3 预约

| 方法与路径 | 请求要点 |
| --- | --- |
| `GET /api/v1/business/reservations` | `page,pageSize,status?,businessType?,fromAt?,toAt?` |
| `POST /api/v1/business/reservations` | `{businessType,customerId?,storeId,resourceId?,startAt,endAt,partySize,contact}` |
| `PUT /api/v1/business/reservations/{id}` | 只允许状态机和预约窗口允许的字段 |
| `POST /api/v1/business/reservations/{id}/arrival` | `{operatorNote?}`；生成/绑定订单 |
| `POST /api/v1/business/reservations/{id}/confirm` | `{expectedVersion}`；确认预约（PENDING→CONFIRMED） |
| `POST /api/v1/business/reservations/{id}/cancel` | `{reason}`；释放占用 |

预约创建的 `startAt/endAt` 必须包含时区偏移；服务端转换为 UTC 后判断冲突。重复预约和跨门店资源返回 `RESOURCE_OCCUPIED` 或 `RESERVATION_SCOPE_INVALID`。

## 6. 订单与 KTV 履约 API（首发）

### 6.1 公共订单

| 方法与路径 | 请求要点 |
| --- | --- |
| `POST /api/v1/business/orders` | `{businessType,customerId?,storeId,items[],reservationId?}`；价格由服务端重算 |
| `GET /api/v1/business/orders` | `page,pageSize,status?,businessType?,keyword?,fromAt?,toAt?` |
| `GET /api/v1/business/orders/{id}` | 返回订单、明细、履约摘要、支付摘要和 `allowedActions` |
| `POST /api/v1/business/orders/{id}/items` | `{items[],expectedVersion}`；加项/加钟 |
| `POST /api/v1/business/orders/{id}/hold` | `{reason}`；挂单 |
| `POST /api/v1/business/orders/{id}/transfer` | `{targetStoreId?,targetResourceId?,reason}`；需权限和状态允许 |
| `POST /api/v1/business/orders/{id}/settle` | `{expectedVersion,discountCode?}`；返回应收金额，不能传最终金额 |
| `GET /api/v1/business/orders/{id}/bill` | 客户消费账单（分级明细：计时费/加项/服务人员费/优惠逐项/应收/已收分项/找零） |
| `POST /api/v1/admin/orders/{id}/void` | `{reason,expectedVersion}`；高风险审批后执行 |

### 6.2 酒店（后续）

| 方法与路径 | 请求要点 |
| --- | --- |
| `POST /api/v1/business/hotel/stays/{id}/check-in` | `{guest,expectedVersion,depositAmount?,paymentMethod?}` |
| `POST /api/v1/business/hotel/stays/{id}/extend` | `{plannedCheckOutAt,expectedVersion}` |
| `POST /api/v1/business/hotel/stays/{id}/change-room` | `{targetResourceId,reason,expectedVersion}` |
| `POST /api/v1/business/hotel/stays/{id}/check-out` | `{expectedVersion,settlementMode}`；返回账单、押金结果、房态 |
| `POST /api/v1/business/hotel/rooms/{id}/clean-complete` | `{checklist?,remark?}`；房务权限 |

### 6.3 KTV（首发）

| 方法与路径 | 请求要点 |
| --- | --- |
| `POST /api/v1/business/ktv/sessions/{id}/open` | `{expectedVersion}`；锁定包厢和计时规则 |
| `POST /api/v1/business/ktv/sessions/{id}/pause` | `{reason,expectedVersion}`；仅配置启用时 |
| `POST /api/v1/business/ktv/sessions/{id}/resume` | `{expectedVersion}` |
| `POST /api/v1/business/ktv/sessions/{id}/close` | `{expectedVersion}`；结算并释放包厢 |
| `POST /api/v1/business/ktv/sessions/{id}/correct-pause` | `{correctedPausedSeconds,reason,expectedVersion}`；暂停时长修正（店长/财务，写审计） |
| `POST /api/v1/business/orders/{id}/servers` | `{serverResourceId,expectedVersion}`；点服务人员（按单位时间计费） |
| `POST /api/v1/business/ktv/servers/{id}/end` | `{expectedVersion}`；服务人员结束计费 |
| `POST /api/v1/business/ktv/servers/{id}/cancel` | `{reason,expectedVersion}`；取消服务人员点单 |

### 6.4 足浴（后续）

| 方法与路径 | 请求要点 |
| --- | --- |
| `POST /api/v1/business/spa/sessions/{id}/assign` | `{roomResourceId,therapistResourceId,expectedVersion}` |
| `POST /api/v1/business/spa/sessions/{id}/start` | `{expectedVersion}` |
| `POST /api/v1/business/spa/sessions/{id}/add-time` | `{minutes,expectedVersion}` |
| `POST /api/v1/business/spa/sessions/{id}/complete` | `{expectedVersion}` |

## 7. 支付、押金、退款和班次 API

| 方法与路径 | 请求要点 |
| --- | --- |
| `GET /api/v1/business/payments/available-methods` | `{orderId,amount,currency}`；服务端返回可用渠道，金额仅用于能力校验 |
| `POST /api/v1/business/payments/intents` | `{orderId,method,amount,currency,terminalId,expectedVersion}`；金额服务端比对 |
| `GET /api/v1/business/payments/{id}` | 查询支付最终状态；处理中不可重复创建 |
| `POST /api/v1/business/payments/{id}/close` | 关闭过期支付意图 |
| `POST /api/v1/business/deposits` | `{orderId,amount,currency,paymentIntentId}` |
| `POST /api/v1/business/refund-requests` | `{orderId,amount,reason,transactionId}`；收银员只能申请 |
| `POST /api/v1/admin/refund-requests/{id}/approve` | `{approvedAmount,reason}`；店长/财务审批 |
| `POST /api/v1/admin/refund-requests/{id}/reject` | `{reason}` |
| `POST /api/v1/business/shifts/open` | `{terminalId,openingCash}` |
| `POST /api/v1/business/shifts/{id}/close` | `{actualCash,remark}` |
| `POST /api/v1/admin/daily-closings/{id}/submit` | `{businessDate,summaryVersion}` |
| `POST /api/v1/admin/daily-closings/{id}/review` | `{action:APPROVE|REOPEN,reason?}` |
| `POST /api/v1/business/orders/{id}/collect` | `{payments:[{method:CASH|WALLET|POINT,amount}],expectedVersion}`；组合收款逐笔拆分，每笔 ≤ 剩余应收，服务端返回剩余；金额最小货币单位整数，舍入让利消费者 |

支付接口禁止客户端传入商户号、密钥、手续费、汇率或最终订单金额。`amount` 只用于展示/校验，最终金额来自订单服务。

## 8. 会员与营销 API

| 方法与路径 | 请求要点 |
| --- | --- |
| `GET /api/v1/business/members` | `page,pageSize,keyword?,level?,status?`；仅当前租户 |
| `POST /api/v1/business/members` | `{accountId?,name,phone,consent}`；未验证手机号创建待认领客户 |
| `GET /api/v1/business/members/{id}/points` | 返回积分账户和账本分页 |
| `POST /api/v1/business/members/{id}/points/adjust` | `{points,reason,commandId}`；高风险审计 |
| `GET /api/v1/business/members/{id}/wallet` | 仅同主体/同币种余额 |
| `POST /api/v1/admin/wallets/recharge` | `{customerId,amount,currency,paymentIntentId}`；储值币/代币充值（账本 RECHARGE，幂等） |
| `POST /api/v1/admin/wallets/refund` | `{customerId,amount,reason}`；储值退还（账本 REFUND，审批） |
| `PUT /api/v1/admin/tenant/config` | `{configKey,configValue}`；租户配置（含 `wallet_brand_name` 储值展示名），权限 `tenant.tenant.manage` |
| `POST /api/v1/admin/marketing/campaigns` | `{name,type,scope,rules,startAt,endAt,fundingParty}` |
| `POST /api/v1/admin/marketing/campaigns/{id}/issue` | `{customerIds?,triggerType}`；幂等发放 |
| `POST /api/v1/business/orders/{id}/promotions/quote` | `{couponCode?,memberId?}`；返回可用优惠和规则快照 |
| `POST /api/v1/business/orders/{id}/promotions/apply` | `{quoteId,expectedVersion}`；结算前锁定 |
| `POST /api/v1/business/coupons/{id}/redeem` | `{orderId,expectedVersion}`；核销不可重复 |

首发积分默认同国家同币种计划共享；储值默认同主体同币种共享。跨币种兑换接口不开放，必须待合规走廊启用后增加新契约。

## 9. B 端 App 专用聚合 API

为避免 App 组合多个内部服务，BFF 提供只读聚合：

| 路径 | 内容 |
| --- | --- |
| `GET /api/v1/business/menu` | 权限过滤菜单、按钮和允许动作 |
| `GET /api/v1/business/workbench` | 今日营业、待办、资源摘要、待审批数 |
| `GET /api/v1/business/resources/board` | 按业态聚合当前门店资源状态 |
| `GET /api/v1/business/orders/{id}/workspace` | 订单、履约、可用操作、支付摘要 |
| `GET /api/v1/business/reports/summary` | 店长摘要，带 `updatedAt` 和数据时点 |

聚合接口只读，不执行跨领域写事务；写操作必须调用拥有权威事实的领域接口。

## 10. MQ 事件契约

事件信封：

```json
{
  "eventId":"evt_01",
  "eventType":"order.completed",
  "schemaVersion":1,
  "tenantId":"1001",
  "organizationId":"2001",
  "storeId":"3001",
  "aggregateType":"order",
  "aggregateId":"4001",
  "occurredAt":"2026-08-17T10:00:00Z",
  "producer":"platform-order-service",
  "data":{}
}
```

首发事件：`tenant.activated`、`store.activated`、`reservation.created`、`reservation.arrived`、`resource.occupied`、`resource.released`、`order.created`、`order.completed`、`ktv.session.opened`、`ktv.session.paused`、`ktv.session.resumed`、`ktv.session.closed`、`ktv.server.ordered`、`ktv.server.ended`、`payment.succeeded`、`payment.refunded`、`deposit.changed`、`wallet.recharged`、`wallet.consumed`、`shift.closed`、`point.changed`、`coupon.redeemed`、`ktv.server.cancelled`、`reservation.confirmed`。

事件规则：生产者业务事务和 Outbox 同库提交；消费者按 `eventId` 幂等；失败指数退避并进入死信；事件不可携带密钥、完整证件、卡号或不必要的客户隐私；跨租户消费者必须明确平台权限。

## 11. 错误码基线

| 错误码 | HTTP | 处理 |
| --- | --- | --- |
| `AUTH_CONTEXT_EXPIRED` | 401 | 清除上下文，重新选择 |
| `TENANT_SCOPE_DENIED` | 403 | 不重试，不泄露资源是否存在 |
| `RESOURCE_OCCUPIED` | 409 | 刷新资源和订单 |
| `ORDER_VERSION_CONFLICT` | 409 | 查询最新订单 |
| `ORDER_STATUS_INVALID` | 422 | 显示当前状态，不重放 |
| `PAYMENT_IDEMPOTENCY_REPLAY` | 409/200 | 返回第一次支付结果 |
| `PAYMENT_CALLBACK_INVALID` | 422 | 记录安全告警，不改变状态 |
| `PAYMENT_PROCESSING` | 409 | 轮询原支付意图 |
| `REFUND_APPROVAL_REQUIRED` | 422 | 创建审批申请 |
| `LEDGER_INSUFFICIENT` | 422 | 不写流水 |
| `CURRENCY_CORRIDOR_DISABLED` | 422 | 禁止跨币种交易 |
| `PROMOTION_EXPIRED` | 422 | 重新报价 |
| `IDEMPOTENCY_CONFLICT` | 409 | 同键不同请求体，拒绝 |
| `PAYMENT_CHANNEL_DISABLED` | 422 | 线上渠道默认关闭，改用现金/储值/积分 |
| `KTV_SESSION_PAUSE_DISABLED` | 422 | 门店未启用暂停 |
| `KTV_SESSION_CLOCK_CONFLICT` | 409 | 计时冲突（暂停/修正期间） |
| `ORDER_ALREADY_SETTLED` | 409 | 订单已结算，不可重复 |
| `RESERVATION_ALREADY_CONVERTED` | 409 | 预约已转订单 |
| `SHIFT_ALREADY_OPEN` | 409 | 已有开放班次 |
| `PAYMENT_AMOUNT_MISMATCH` | 422 | 组合收款金额与剩余应收不符 |

## 12. 契约发布流程

1. 服务端先更新 OpenAPI、错误码和事件 JSON Schema。
2. 契约测试验证字段、权限、状态、幂等和未知枚举。
3. Admin/B App 生成或维护 DTO，不直接读取服务端内部 PO。
4. 变更必须带契约版本、兼容性说明、E2E 编号和回滚策略。
5. 破坏性变更新增 `/v2` 或新事件版本；禁止静默修改 v1 字段含义。
