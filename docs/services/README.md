# 服务目录

[中文](README.md) | [English](README.en.md)

> 状态：`current`（`develop/2.3.0`）  
> 上层结构见[工程拓扑](../architecture/TOPOLOGY.zh.md)；运行时端口和路由以 Gateway 配置为准。

## 1. 分层

### 接入层

| 服务 | 说明 |
| --- | --- |
| [gateway](gateway/README.md) | HTTPS REST 统一入口、路由、鉴权透传 |
| [im-access-ws](im-access-ws/README.md) | WebSocket 长连接接入 |

### IM 业务域

| 服务 | 说明 |
| --- | --- |
| [im-user-service](im-user-service/README.md) | 用户、认证、设备、好友、贴纸 |
| [im-message-service](im-message-service/README.md) | 消息、已读、撤回、频道、私密消息 |
| [im-conversation-service](im-conversation-service/README.md) | 群组、频道、私密会话、RTC 配置 |
| [im-admin-service](im-admin-service/README.md) | IM 管理接口、客户端配置、服务板块 |

### SaaS 平台域

| 服务 | 说明 |
| --- | --- |
| [platform-admin-service](platform-admin-service/README.md) | SaaS 后台 BFF、菜单、上下文、KTV 配置与预约 BFF |
| [platform-identity-service](platform-identity-service/README.md) | SaaS 账号、登录、会话、OAuth/OIDC |
| [platform-tenant-service](platform-tenant-service/README.md) | 租户、组织、门店、IAM 与租户配置 |
| [platform-customer-service](platform-customer-service/README.md) | 客户、会员、积分、钱包账本 |
| [platform-resource-service](platform-resource-service/README.md) | 资源、房型、库存与占用 |
| [platform-marketing-service](platform-marketing-service/README.md) | 营销、优惠与活动 |

`platform-order-service` 负责订单、预约与履约编排；其独立服务说明待补，当前契约见 Gateway 路由与 `docs/archive/renovation/ORDER_RESERVATION_01_SERVICE.md`（历史背景）。

### 公共能力域

| 服务 | 说明 |
| --- | --- |
| [common-media-service](common-media-service/README.md) | 媒体上传会话、对象元数据、访问授权 |
| [common-audit-service](common-audit-service/README.md) | 审计事件采集与查询 |
| [common-payment-service](common-payment-service/README.md) | 支付编排 |
| [common-payment-channel-service](common-payment-channel-service/README.md) | 支付渠道配置 |
| [common-sms-service](common-sms-service/README.md) | 短信发送 |

`common-mail-service`、`group-idaas-service` 已部署，独立服务说明待补。

## 2. 服务文档结构

每份服务说明至少包含：

1. 职责与边界；
2. 端口、网关入口、Controller 路径；
3. 依赖（MySQL / Redis / MQ / 外部服务）；
4. 数据归属与迁移位置；
5. 关键指标、告警和故障处置；
6. 关联契约和测试入口。

## 3. 相关文档

- [工程拓扑](../architecture/TOPOLOGY.zh.md)
- [架构总览](../ARCHITECTURE.md)
- [部署说明](../deployment/LOCAL.md)
- [文档中心](../README.md)
