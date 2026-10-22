# 业务域版图

[中文](DOMAIN_MAP.md) | [English](DOMAIN_MAP.en.md)

> 状态：`current`（2026-10-22）  
> 作用：登记所有限界上下文的类型、Owner、实现模块、依赖和消费者。  
> 规则：[业务域分类规范](../80-development/standards/05_DOMAIN_CLASSIFICATION.md)。

## 1. 分类图例

| 类型 | 含义 |
| --- | --- |
| `core` | 核心业务域：差异化、收入或关键体验 |
| `supporting` | 支撑业务域：必需、可复用，不形成差异化 |
| `generic` | 通用域：商品化/基础设施，不含业务规则 |
| `edge` | 边缘应用：BFF、网关、接入层，不拥有领域模型 |

## 2. 业务域

| 限界上下文 | 类型 | Owner | 实现模块 | 依赖 | 消费者 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| IM Conversation | `core` | IM 产品/技术 | `im-conversation-service` | Identity, Tenant, Audit | Message, IM Admin, C 端 | 会话、成员、权限边界 |
| IM Message | `core` | IM 产品/技术 | `im-message-service` | Conversation, User, Media, MQ | C 端, IM Admin | 消息、已读、撤回、同步 |
| IM User / Social | `core` | IM 产品/技术 | `im-user-service` | Identity, Tenant, Media | Conversation, Message, C 端 | 用户资料、关系、状态 |
| IM Admin | `supporting` | IM 平台 | `im-admin-service` | Message, User, Audit | 运营后台 | 审核、查询、只读投影 |
| Hotel Booking | `core` | 酒店产品/技术 | `platform-order-service` `domain/hotel`（规划） | Order Kernel, Payment, Customer, Stock, Audit | C 端, Admin BFF, OTA | 房型、房价、房态、预订、押金 |
| KTV Operations | `core` | KTV 产品/技术 | `platform-resource-service` KTV 模块 + `platform-order-service` KTV 模块 | Order Kernel, Payment, Customer, Audit | C 端, Admin BFF, B 端 | 包厢时段、开台、结台、计费 |
| Restaurant / Massage / Gym | `core`（规划） | 各业务产品/技术 | 待建 | Order Kernel, Payment, Customer, Stock, Audit | C 端, Admin BFF | 各自容量/排班/履约，不共享容量表 |
| Order Kernel | `supporting` | 交易平台 | `platform-order-service` `domain/order` | Payment, Customer, Audit | 所有业务域, C 端订单中心 | 订单头/明细/金额/支付状态/基础生命周期 |
| Customer / Member | `supporting` | 客户平台 | `platform-customer-service` | Identity, Tenant, Audit | 所有业务域 | 客户档案、积分、钱包、IM 绑定 |
| Marketing | `supporting` | 营销平台 | `platform-marketing-service` | Customer, Order Kernel, Payment | 业务域, 运营后台 | 优惠券、活动；目前未完整实现 |
| Identity / IAM | `supporting` | 身份平台 | `platform-identity-service` | Tenant, Audit | 所有服务 | 登录、会话、OAuth/OIDC、权限快照 |
| Tenant / Store | `supporting` | 租户平台 | `platform-tenant-service` | Identity, Audit | 所有业务域 | 租户、门店、角色、业务时间 |
| Resource Catalog | `supporting` | 资源平台 | `platform-resource-service` resource/roomtype | Tenant, Audit | KTV, Hotel（仅物理资源目录） | 资源/房型目录，不含业务容量 |
| Stock | `supporting`（规划） | 供应链平台 | 待建 | Tenant, Audit | Hotel, KTV, Restaurant, Gym, Retail | 采销库存：SKU、仓库、余额、出入库 |
| Payment | `supporting` | 支付平台 | `common-payment-service` + `common-payment-channel-service` | Customer, Order Kernel, Audit | 所有业务域 | 支付、退款、组合收款、对账 |
| Media | `supporting` | 媒体平台 | `common-media-service` | Tenant, Audit | IM, Hotel, Marketing | 图片/视频/文件、上传会话 |
| Audit | `supporting` | 平台治理 | `common-audit-service` | Tenant | 所有服务 | 审计日志、操作追踪 |
| Notification | `supporting` | 通知平台 | `common-sms-service`, `common-mail-service` | Tenant, Audit | 所有业务域 | 短信、邮件、验证码/通知 |
| Admin BFF | `edge` | 平台前端/接入 | `platform-admin-service` | 各业务域 | 运营后台 | 聚合、协议转换，不拥有领域模型 |
| Gateway / WS Access | `generic` | 基础设施 | `gateways/gateway`, `gateways/im-access-ws` | Identity, Tenant | 所有客户端 | 路由、鉴权、长连接接入 |

## 3. 依赖规则

```text
Core -> Supporting -> Generic
Core -> Generic
Edge -> Core / Supporting
Supporting -X-> Core
Generic -X-> Core / Supporting
Core -X-> 另一个 Core 的内部模型
```

- 核心域可以依赖支撑域；
- 支撑域不能反向依赖核心域；
- 核心域之间通过契约/事件/ACL 协作，不共享表；
- 边缘应用不拥有领域状态；
- Stock 不承载酒店/KTV 容量；
- 订单内核不承载酒店房态/KTV 时段。

## 4. 服务与上下文映射

一个部署服务可以承载多个上下文，但模块必须分开：

| 服务 | 承载上下文 |
| --- | --- |
| `platform-order-service` | Order Kernel（supporting）+ Hotel Booking（core）+ KTV order modules（core） |
| `platform-resource-service` | Resource Catalog（supporting）+ KTV Operations（core） |
| `platform-admin-service` | Admin BFF（edge） |
| `platform-customer-service` | Customer/Member（supporting） |
| `platform-identity-service` | Identity/IAM（supporting） |
| `platform-tenant-service` | Tenant/Store（supporting） |
| `platform-marketing-service` | Marketing（supporting） |
| `common-payment-service` / `-channel-service` | Payment（supporting） |
| `common-media-service` | Media（supporting） |
| `common-audit-service` | Audit（supporting） |
| `common-sms-service` / `common-mail-service` | Notification（supporting） |
| `im-conversation-service` | IM Conversation（core） |
| `im-message-service` | IM Message（core） |
| `im-user-service` | IM User/Social（core） |
| `im-admin-service` | IM Admin（supporting） |
| `gateways/gateway` | Gateway（generic） |
| `gateways/im-access-ws` | WS Access（generic） |

## 5. 演进规则

- 新增核心域必须有 ADR；
- 新增支撑域必须说明复用范围和 Owner；
- 新增通用域不得包含业务规则；
- 从支撑域升级为核心域，必须重新评审并更新本图；
- 一个上下文拆分/合并必须更新服务映射和架构图。

## 6. 相关文档

- [业务域分类规范](../80-development/standards/05_DOMAIN_CLASSIFICATION.md)
- [库存平台域](inventory/README.md)
- [订单平台域](order/README.md)
- [酒店业务](hotel/README.md)
- [服务目录](../50-services/README.md)
