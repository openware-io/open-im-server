# 公共支撑域蓝图

[中文](PLATFORM_DOMAINS.md) | [English](PLATFORM_DOMAINS.en.md)

> 状态：`draft`（`develop/2.4.0-hotel`）  
> 目的：统一交易、库存、用户、订单等公共支撑域的概念、边界和三阶段演进。  
> 原则：先分概念，再分服务；先模块化，再按触发器拆分；不提前造平台。

## 1. 先拆概念

### 用户相关

| 概念 | 含义 | 不相等 |
| --- | --- | --- |
| `platformUser` | 平台全局用户身份，跨租户稳定 | 不是 IM 用户，也不是某个租户的客户 |
| `account` | 登录账号/凭据 | 不是业务客户 |
| `imUser` | OpenChat IM 用户资料/关系 | 不是平台账号 |
| `customer` | 租户内的客户/会员档案 | 不是全局用户 |
| `tenant` | 集团/租户 | 不是门店 |
| `store` | 门店 | 不是租户 |

ID 关系：

```text
platformUserId 1 ── N account
platformUserId 1 ── N imUser
platformUserId 1 ── N customer（按 tenant）
tenantId 1 ── N store
```

不能用一个 `userId` 字段同时表达这四者。

### 交易相关

| 概念 | 含义 | 不相等 |
| --- | --- | --- |
| `transaction` | 一次交易过程 | 不是订单表 |
| `order` | 交易单据/聚合根 | 不是支付 |
| `payment` | 资金支付/退款 | 不是订单状态 |
| `settlement` | 商户/渠道结算与对账 | 不是支付 |
| `fulfillment` | 履约（入住/开台/桌台/课程） | 不是订单基础状态 |

ID 关系：

```text
orderId 1 ── N paymentId
orderId 1 ── N settlementId（可空，按业务）
orderId 1 ── 1 fulfillment（按业务扩展）
```

### 库存相关

| 概念 | 含义 | 归属 |
| --- | --- | --- |
| `stock` | 采销库存：SKU/仓库/数量/成本 | 公共支撑域 |
| `capacity` | 容量库存：房型/时段/桌台/课程 | 各业务核心域 |

`stock` 与 `capacity` 不共享表、不共享模型。

### 价格与优惠

| 概念 | 含义 | 归属 |
| --- | --- | --- |
| `rate/price` | 商品/房型/时段的基础价格 | 业务核心 |
| `promotion` | 优惠券、活动、折扣 | 营销支撑域 |
| `order snapshot` | 下单时的价格/优惠快照 | 订单内核 |

订单必须保存价格快照；后续改价不能改历史订单。

## 2. 公共支撑域清单

| 支撑域 | 职责 | 现有实现 | 阶段 |
| --- | --- | --- | --- |
| Identity & Access | 登录、会话、OAuth/OIDC、权限快照 | `platform-identity-service` | H1 |
| Platform User | 全局用户身份、账号映射 | 规划，当前由 identity/customer 组合 | H1 |
| Tenant & Store | 租户、门店、组织、角色 | `platform-tenant-service` | H1 |
| Customer / Member | 客户档案、积分、钱包 | `platform-customer-service` | H1 |
| Order Kernel | 订单头/明细/金额/支付/退款/基础状态 | `platform-order-service` | H1 |
| Payment | 支付、退款、组合收款、渠道 | `common-payment-service` | H1 |
| Stock | 采销库存 | 规划 | H1/H2 |
| Marketing | 优惠券、活动、会员权益 | `platform-marketing-service`（未完整） | H2 |
| Settlement | 商户/OTA/渠道结算与对账 | 规划 | H2 |
| Channel Integration | OTA/渠道经理适配框架 | 规划 | H2/H3 |
| Notification | 短信、邮件、推送 | `common-sms-service` / `common-mail-service` | H1 |
| Media | 图片、视频、文件 | `common-media-service` | H1 |
| Audit | 审计、操作追踪 | `common-audit-service` | H1 |
| Search | 跨业务搜索/索引 | 规划 | H3 |
| Workflow / Approval | 退款、作废、人工审批 | 规划 | H3 |

## 3. 依赖与不变量

```text
Business Core -> Order Kernel -> Payment
Business Core -> Stock
Business Core -> Capacity（自有，不跨业务）
Business Core -> Marketing (优惠)
Business Core -> Notification / Media / Audit
```

不变量：

- 订单金额是下单快照，支付/退款不能改订单历史金额；
- 支付状态由 Payment 拥有，订单只引用；
- 结算由 Settlement 拥有，Payment 不负责分账；
- Stock 拥有数量，业务核心不直接写库存表；
- Capacity 由业务核心拥有，Stock/Order 不共享容量表；
- Customer 拥有积分/钱包账本，订单只引用；
- Platform User 是跨租户身份，Customer 是租户内业务身份。

## 4. 三阶段演进

### H1：MVP / 模块化（当前）

```text
Order Kernel（order 服务内模块）
Stock（模块/设计）
Identity + Platform User（身份）
Tenant + Customer
Payment
Media / Audit / Notification
```

不做：

- 不新建一堆平台服务；
- 不做通用 Settlement/Search/Workflow；
- 不把 Capacity 放进 Stock。

### H2：多业务复用

触发条件：酒店 + KTV + 至少一个其他业态同时依赖。

```text
Stock 独立为 platform-inventory-service
Settlement 独立（渠道/OTA/商户分账）
Marketing 补齐优惠券/权益
Channel Integration 框架
Order Kernel 从 order 服务中抽出为 platform-trade-service
```

仍然：

- Capacity 留在各业务核心；
- 订单扩展表随业务；
- 不共享数据库。

### H3：平台规模化

触发条件：多业务、多区域、多团队独立发布。

```text
Search / Workflow 独立
统一事件总线
数据平台/分析域
多区域部署
```

不在 H1/H2 提前实现。

## 5. 为未来预留什么

- `platformUserId`：现在就引入，避免 C 端统一订单再改；
- `orderId` / `paymentId` / `settlementId` 分离；
- 订单价格/优惠/币种快照；
- Outbox 事件：`order.created` / `order.paid` / `order.cancelled` / `payment.succeeded` / `stock.low`；
- 版本化契约与幂等键；
- 业务扩展表，不用万能表；
- 不跨库 join、不共享表；
- 软删除/审计/租户字段统一。

## 6. 反模式

- `user` 一个字段包打天下；
- 订单表里放支付状态、退款状态、结算状态；
- Stock 管房间容量；
- Capacity 放进公共平台；
- 每个业务一套订单；
- 提前拆十个平台服务；
- 共享数据库表当集成。

## 7. 相关文档

- [业务域版图](DOMAIN_MAP.md)
- [订单平台域](order/README.md)
- [库存平台域](inventory/README.md)
- [业务域分类规范](../80-development/standards/05_DOMAIN_CLASSIFICATION.md)
- [ADR-0012：订单共享内核](../20-architecture/adr/0012-order-kernel.md)
