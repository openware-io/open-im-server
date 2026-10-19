# 架构总览

[中文](ARCHITECTURE.md) | [English](ARCHITECTURE.en.md)

> 状态：`current`（`develop/2.3.0` 开发中）  
> 工程分层、仓库/部署单元映射和数据归属见[工程拓扑](architecture/TOPOLOGY.zh.md)。

## 1. 总体形态

系统采用“统一接入 + 领域微服务 + 长连接接入层”的结构：

```text
客户端 / H5 / 管理后台
  → Ingress
  → gateway（HTTPS REST 横切入口）
  → platform-* / im-* / common-*
  → MySQL / MongoDB / Redis / RocketMQ / MinIO

客户端
  → im-access-ws（WebSocket 长连接）
  → RocketMQ
  → im-message-service
```

- 外部客户端只通过 Ingress 访问公开入口；
- REST 请求由 `gateway` 路由到各业务微服务；
- 长连接由 `im-access-ws` 接入，命令经 RocketMQ 进入消息域；
- 服务间 HTTP 调用使用 K8s Service/DNS 发现；
- 业务事实写入 MySQL，MongoDB/Redis 只承载投影、缓存与在线状态。

## 2. 分层职责

| 层 | 组成 | 职责 |
| --- | --- | --- |
| L0 体验层 | `open-chat-app`、`open-chat-desktop`、`open-saas-admin`、`open-chat-admin`、`open-saas-mobile`、`open-website` | 面向最终用户、运营和官网的交互入口 |
| L1 接入层 | `gateway`、`im-access-ws`、`unified-portal` | REST 网关、WebSocket 接入、统一登录门户 |
| L2 SaaS 平台域 | `platform-identity/tenant/admin/customer/order/resource/marketing-service` | 身份、租户、客户、订单、资源、营销、平台后台 |
| L3 IM 业务域 | `im-user/message/conversation/admin-service` | 用户、消息、会话、IM 管理 |
| L4 公共能力域 | `common-media/audit/mail/sms/payment/payment-channel-service`、`group-idaas-service` | 媒体、审计、邮件、短信、支付、集团 IAM |
| L5 数据基础设施 | MySQL、MongoDB、Redis、RocketMQ、MinIO | 权威数据、投影、缓存、消息与对象存储 |

## 3. 服务边界

### 接入层

- **gateway**：对外统一入口，负责路由、鉴权透传、限流、灰度、审计等横切能力；不承载业务编排。
- **im-access-ws**：WebSocket 建连与鉴权、在线会话维护、跨实例广播、向聊天域查询权限与投递结果。
- **unified-portal**：统一登录门户与入口编排。

### SaaS 平台域

- **platform-identity-service**：SaaS 账号、登录、会话、OAuth/OIDC 与租户上下文。
- **platform-tenant-service**：租户、门店、组织、IAM 权限、租户配置与钱包展示配置。
- **platform-admin-service**：平台运营后台接口、平台账号与平台级配置。
- **platform-customer-service**：客户、会员、积分、钱包账本与储值展示。
- **platform-order-service**：预约、订单、履约与 KTV 业务编排。
- **platform-resource-service**：资源、房型、库存与占用。
- **platform-marketing-service**：营销、优惠与活动能力。

### IM 业务域

- **im-user-service**：用户资料、注册登录、设备令牌、设备密钥（E2EE）、好友关系链、用户贴纸；积分归 SaaS 客户域。
- **im-message-service**：消息历史、离线消息、已读回执、撤回与清空、频道消息游标、私密消息密文与销毁引擎。
- **im-conversation-service**：群组与成员、群角色与禁言、频道、私密聊天（E2EE）、RTC 配置查询。
- **im-admin-service**：IM 管理后台接口、客户端配置下发、版本更新检查、举报处理、小程序服务配置。

### 公共能力域

- **common-media-service**：受控媒体直传会话、对象元数据、私有访问授权、对象存储适配与生命周期清理。
- **common-audit-service**：审计事件采集与查询。
- **common-mail-service / common-sms-service**：邮件与短信发送。
- **common-payment-service / common-payment-channel-service**：支付编排与渠道配置。
- **group-idaas-service**：集团统一身份与 IAM。

## 4. 数据与基础设施

- **MySQL**：唯一权威业务库，承载关系型业务事实、权威写入与可追溯数据；关系型持久层主栈为 MyBatis-Plus `3.5.17`。
- **MongoDB**：仅承担消息热投影与离线收件箱，不得承载权威业务事实。
- **Redis**：仅承担在线状态、热点状态、跨实例投递/广播协同与会话缓存，不得承载权威持久化数据。
- **RocketMQ**：IM 命令削峰、解耦与会话级保序。
- **MinIO**：受管对象存储；文件二进制由客户端直连，控制面经 `common-media-service`。

Flyway 由持有本域 MySQL 表结构的服务分别维护迁移脚本，包括但不限于 `im-user-service`、`im-message-service`、`im-admin-service`、`platform-tenant-service`、`platform-identity-service`、`platform-customer-service`、`platform-order-service`、`platform-resource-service`、`common-media-service`、`common-audit-service`。具体表结构以各服务 `db/migration` 为准。

## 5. 关键约束

- Gateway 只做横切，不做业务编排；
- 所有外部 REST 路径统一保留 `/api` 前缀；
- `im-access-ws` 不把单机内存在线状态作为最终事实，跨实例协同必须依赖 Redis；
- 接入层不拥有权威持久层，只负责接入、协议转换和命令投递；权威业务数据必须由对应业务服务写入 MySQL；
- 好友关系归属用户域，群归属聊天域，管理能力收敛到管理域；
- 服务板块展示数据由 `im-admin-service` 配置，客户端不硬编码服务列表；
- 具体仓库、部署单元与数据归属见[工程拓扑](architecture/TOPOLOGY.zh.md)。
