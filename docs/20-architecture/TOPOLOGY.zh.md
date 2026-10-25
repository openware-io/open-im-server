# im-saas 工程拓扑

[中文](TOPOLOGY.zh.md) | [English](TOPOLOGY.en.md)

> 状态：当前版本（2.2.1 阶段）  
> 范围：`D:\projects\cnb-oss` 下的 im-saas 产品仓库与 `open-im-server` 运行时  
> 目标：给仓库、部署单元、运行时服务和数据基础设施建立统一的结构层次。

## 1. 读法

本拓扑按五级组织，避免把“仓库、服务、进程、数据”混在同一层：

```text
Workspace
  └─ Product
       └─ Repository
            └─ Deployment unit
                 └─ Runtime service
                      └─ Data store
```

一个仓库可以产出多个部署单元（例如 `open-chat-app` 产出 Android/iOS/Web），一个部署单元也可以由多个服务组成（例如 `open-im-server` 的 `open-im-local` 环境）。

## 2. 分层总览

```text
L0 体验层
├─ open-chat-app          Android / iOS / Web 客户端
├─ open-chat-desktop      Electron 桌面客户端
├─ open-saas-admin        SaaS 运营后台（Web）
├─ open-chat-admin        IM 管理后台 / pc-admin
├─ open-saas-mobile       SaaS C 端 / B 端 H5
└─ open-website           官网与下载页

L1 接入与边缘层
├─ ingress-nginx          Kind/ACK 统一入口
├─ gateway                HTTPS REST 网关、鉴权透传、路由
├─ im-access-ws           WebSocket 长连接接入
└─ unified-portal         统一登录门户

L2 SaaS 平台域
├─ platform-identity-service
├─ platform-tenant-service
├─ platform-admin-service
├─ platform-customer-service
├─ platform-order-service
├─ platform-resource-service
└─ platform-marketing-service

L3 IM 业务域
├─ im-user-service
├─ im-message-service
├─ im-conversation-service
└─ im-admin-service

L4 公共能力域
├─ common-media-service
├─ common-audit-service
├─ common-mail-service
├─ common-sms-service
├─ common-payment-service
├─ common-payment-channel-service
└─ group-idaas-service

L5 数据与消息基础设施
├─ MySQL                  open_im / open_saas / open_group / open_audit
├─ MongoDB                im_chat（消息热投影）
├─ Redis                  会话、在线状态、缓存与跨实例协同
├─ RocketMQ               IM 消息命令与事件
└─ MinIO                  受管对象存储
```

## 3. 仓库与部署单元映射

| 仓库 | 部署单元 | 层 | 职责 |
| --- | --- | --- | --- |
| `open-chat-app` | Android / iOS / Web | L0 | 移动端与 Flutter Web |
| `open-chat-desktop` | Electron 安装包 | L0 | 桌面客户端 |
| `open-saas-admin` | `saas-admin` | L0 | SaaS 运营后台 |
| `open-chat-admin` | `pc-admin` | L0 | IM 管理后台 |
| `open-saas-mobile` | `saas-mobile` | L0 | `/c/` C 端、`/b/` B 端 H5 |
| `open-website` | `open-website` | L0 | 官网、下载与协议页 |
| `open-im-server` | `gateway` | L1 | REST 网关 |
| `open-im-server` | `im-access-ws` | L1 | WebSocket 接入 |
| `open-im-server` | `unified-portal` | L1 | 统一登录门户 |
| `open-im-server` | `platform-*` | L2 | 身份、租户、客户、订单、资源、营销、平台管理 |
| `open-im-server` | `im-*` | L3 | 用户、消息、会话、IM 管理 |
| `open-im-server` | `common-*` / `group-idaas-service` | L4 | 媒体、审计、邮件、短信、支付、集团 IAM |
| `open-chat-turn` | TURN 服务 | L1/L4 | WebRTC NAT 穿透 |
| `open-green-pass` | GreenPass Web | 独立产品 | 软件工程测试治理平台，不参与 im-saas 运行时 |

## 4. 关键运行链路

### 4.1 客户端 REST

```text
open-chat-app / open-chat-desktop / H5
  → Ingress /api/v1/**
  → gateway（校验、路由、透传）
  → platform-* / im-* / common-*
  → MySQL / Redis / MongoDB / MinIO
```

### 4.2 WebSocket

```text
客户端
  → POST /api/v1/auth/ws-ticket（一次性 ticket）
  → WS /ws/im/v1?ticket=...
  → im-access-ws
  → RocketMQ 命令
  → im-message-service
  → MySQL 权威落库 + outbox
  → MongoDB / Redis 投影
  → im-access-ws 下行
```

### 4.3 SaaS H5 与 OAuth

```text
OpenChat App 服务板块
  → im-admin-service 的 /miniapp/services（服务目录）
  → /c/ 或 /b/
  → saas-mobile（Nginx）
  → OAuth / identity 会话
  → SaaS 业务页面
```

### 4.4 服务板块数据

```text
im-admin-service
  └─ adm_miniapp_service_type
       └─ adm_miniapp_service_item
            ├─ consumer：固定服务列表
            └─ operator：运营后台入口（隐藏、可搜索、可固定）
```

## 5. 数据归属

| 数据 | 权威存储 | 说明 |
| --- | --- | --- |
| IM 用户、好友、设备、贴纸 | MySQL `open_im` | `im-user-service` |
| IM 消息、会话、群组 | MySQL `open_im` + RocketMQ | `im-message-service` / `im-conversation-service` 权威写入 |
| 消息热投影、离线收件箱 | MongoDB `im_chat` | 只作投影，不作权威事实 |
| 在线状态、会话、缓存 | Redis | 可重建，不作权威持久化 |
| SaaS 租户、IAM、钱包配置 | MySQL `open_saas` | `platform-tenant-service` 等 |
| 平台账号与会话 | MySQL `open_saas` + Redis | `platform-identity-service` / `platform-admin-service` |
| 媒体对象 | MinIO | `common-media-service` 管元数据 |
| 审计事件 | MySQL `open_audit` | `common-audit-service` |
| 集团 IAM | MySQL `open_group` | `group-idaas-service` |

## 6. 边界规则

1. L0 只通过 Ingress 的公开入口访问系统，不直连 L2/L3 服务。
2. L1 网关只做横切，不承载业务编排；长连接只由 `im-access-ws` 维护。
3. L2 与 L3 通过领域接口协作，不跨域直接写对方表。
4. L4 只提供公共能力，不反向依赖具体业务域。
5. MySQL 是业务事实的权威来源；MongoDB、Redis 只作投影或缓存。
6. 服务板块的展示数据只由 `im-admin-service` 配置，客户端不硬编码服务列表。

## 7. 相关文档

- [架构总览](OVERVIEW.md)
- [文档中心](../README.md)
- [文档审计](../99-archive/audits/DOCUMENTATION_AUDIT.md)
- [本地部署](../60-deployment/LOCAL.md)
- [服务板块接入规范](../99-archive/renovation/IM_OPEN_PLATFORM_04_SERVICE.md)
- [网关服务说明](../50-services/edge/gateway/README.md)
