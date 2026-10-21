# 02 容器图

[中文](02-container.md) | [English](02-container.en.md)

> 图版本：`2026-10-21` · C4 语义 / 分组布局

> 视角：C4 Level 2 — Container  
> 回答：系统由哪些可部署单元组成？它们的职责、协议和依赖是什么？  
> 不回答：Pod 副本、Node、数据库表结构。

![容器图](02-container.svg)

> 图源：[D2](src/02-container.d2) · 主题：D2 5

## 读图要点

- 客户端只访问 Ingress；Gateway 负责 REST 路由和鉴权透传，`im-access-ws` 负责 WebSocket 长连接。
- IM 消息的权威写入是 `im-message-service`：MySQL 事务 + Outbox，MongoDB/Redis 只是投影与缓存。
- `im-access-ws` 不直接写业务表，只生产消息命令、消费事件并下行。
- `platform-admin-service` 是 BFF，聚合身份/租户等能力，不拥有独立业务数据。
- `common-*` 只提供公共能力，不反向依赖 IM/SaaS 业务域。
- 完整端口与副本以 [K8s 清单](../../../k8s/local/applications.yaml) 为准。

## 服务与端口

图中使用短名，完整服务名和本地端口如下：

| 图中名称 | 服务 | 端口 |
| --- | --- | --- |
| `user` | `im-user-service` | `3100` |
| `message` | `im-message-service` | `3200` |
| `conversation` | `im-conversation-service` | `3300` |
| `im-admin` | `im-admin-service` | `3400` |
| `identity` | `platform-identity-service` | `4100` |
| `tenant` | `platform-tenant-service` | `4110` |
| `order` | `platform-order-service` | `4130` |
| `admin BFF` | `platform-admin-service` | `4150` |
| `customer` | `platform-customer-service` | `4160` |
| `marketing` | `platform-marketing-service` | `4170` |
| `media` | `common-media-service` | `3600` |
| `payment` | `common-payment-service` | `4140` |
| `audit` | `common-audit-service` | `4190` |
| `sms` | `common-sms-service` | `4200` |
| `IAM` | `group-idaas-service` | `4220` |
| `gateway` | `gateway` | `3002` |
| `im-access-ws` | `im-access-ws` | `3001` |

## 协议

| 路径 | 协议 |
| --- | --- |
| 客户端 → Ingress / Gateway | HTTPS |
| 客户端 → im-access-ws | WSS |
| Gateway → 业务服务 | HTTP |
| 服务 → RocketMQ | RocketMQ 客户端 |
| 服务 → MySQL / Redis / MongoDB | 各自原生协议 |
| 服务 → MinIO | S3 API |

## 相关文档

- [部署拓扑](03-deployment.md)
- [数据归属](04-data-ownership.md)
- [消息发送时序](06-message-send-sequence.md)
