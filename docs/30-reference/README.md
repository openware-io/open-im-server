# 参考文档

[中文](README.md) | [English](README.en.md)

本目录放“查得到、可复制”的接口与协议参考，不承载产品叙事或业务规则解释。

| 文档 | 用途 |
| --- | --- |
| [API 文档](API_DOCUMENTATION.md) | 网关 Swagger/OpenAPI 入口与分组 |
| [协议说明](PROTOCOL.md) | WebSocket 事件、REST 约定、错误码 |
| [OpenAPI 契约](contracts/README.md) | 跨服务/对外接口契约 |
| [MQ 台账](mq/REGISTRY.md) | Topic、Producer/Consumer Group、顺序键与幂等键 |

## 边界

- 架构概念与决策放 `architecture/`；
- 业务规则与状态机放 `domain/`；
- 具体服务的实现说明放 `services/`。
