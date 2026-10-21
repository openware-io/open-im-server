# 架构图集

[中文](README.md) | [English](README.en.md)

> 状态：`current`（`develop/2.3.0`）  
> 原则：一张图回答一个问题；图必须能和代码、K8s 清单、Flyway、MQ 台账对上。

## 图目录

| 编号 | 图 | 视角 | 主要读者 |
| --- | --- | --- | --- |
| 01 | [系统上下文](01-system-context.md) | C4 Context | 产品、架构、首次接触项目的人 |
| 02 | [容器/部署单元](02-container.md) | C4 Container | 后端、前端、测试 |
| 03 | [部署拓扑](03-deployment.md) | Deployment | 运维、SRE、发布负责人 |
| 04 | [数据归属](04-data-ownership.md) | Data Ownership | 后端、DBA、领域负责人 |
| 05 | [限界上下文](05-context-map.md) | DDD Context Map | 架构、领域负责人 |
| 06 | [消息发送时序](06-message-send-sequence.md) | Sequence | IM 后端、客户端、测试 |
| 07 | [MQ/事件拓扑](07-mq-topology.md) | Event Flow | IM/SaaS 后端、运维 |
| 08 | [订单/预约/开台状态机](08-order-reservation-state.md) | State Machine | 业务、后端、测试 |

## 读图约定

- 实线：同步调用或明确的数据写入；
- 虚线：异步事件、投影、只读依赖；
- 箭头方向：调用方 → 被调用方；事件图是生产者 → Topic → 消费者；
- 端口以 `k8s/local/applications.yaml` 和运行中 Service 为准；
- 表名以前缀表示，精确表结构以各服务 Flyway 和领域模型为准；
- 图源是 Mermaid，必须可 diff、可 review，不使用不可检索的截图代替。

## 单一事实源

- 服务与端口：[k8s/local/applications.yaml](../../../k8s/local/applications.yaml)
- 路由：[gateway application.yml](../../../gateways/gateway/src/main/resources/application.yml)
- MQ：[MQ 台账](../../30-reference/mq/REGISTRY.md)
- 数据：[工程拓扑](../TOPOLOGY.zh.md) 与各服务 Flyway
- 订单/预约状态：`platform-order-service` 的 `Order` / `ReservationStatus` / `KtvSession`

## 相关文档

- [架构总览](../OVERVIEW.md)
- [工程拓扑](../TOPOLOGY.zh.md)
- [服务目录](../../50-services/README.md)
