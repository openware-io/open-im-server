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
- 图源：01–05、07 为 D2（`src/*.d2`），06、08 为 Mermaid；SVG 是 D2 的构建产物，不手改。
- 图必须可 diff、可 review；Markdown 嵌入 SVG，不依赖 GitHub 的 Mermaid 渲染缓存。

## 视觉规范

架构图采用 **C4 语义 + D2 布局**，时序/状态保留专用图型：

| 类型 | 画法 | 原因 |
| --- | --- | --- |
| 01 系统上下文 | D2，参与者 / 系统边界 / 外部系统 | 只回答边界，不画内部服务 |
| 02 容器 | D2，按客户端、接入、IM、平台、公共能力、数据分组 | 分组布局比 C4 Mermaid 稳定，标签不会被裁 |
| 03 部署 | D2，namespace / 入口 / Pod 组 / 基础设施 | 部署图必须能和 K8s 清单对上 |
| 04 数据归属 | D2，服务组 → 存储，跨域虚线 | 先看 Owner，再看跨域引用 |
| 05 上下文 | D2，域边界 + 依赖方向 | 不画表、不画类 |
| 06 时序 | Mermaid `sequenceDiagram` | 专门表达调用顺序、幂等和重试 |
| 07 事件 | D2，生产者 → Topic 族 → 消费者 | 按事件族聚合，精确清单链接 MQ 台账 |
| 08 状态 | Mermaid `stateDiagram-v2` | 状态机需要显式终态和异常分支 |

D2 构建命令（在仓库根目录执行）：`pwsh -File scripts/render-architecture-diagrams.ps1`。`n`n布局选择：`01/03` 用 dagre，`02/05/07` 用 ELK，`04` 用 TALA；同一张图不要频繁换布局。

配色约定：

```text
灰  客户端
蓝  接入层
绿  IM 业务域
黄  SaaS 平台域
红  公共能力域
紫  数据与消息基础设施
```

不要用超过 6 种主色；颜色只表达层级，不表达“重要程度”。

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
