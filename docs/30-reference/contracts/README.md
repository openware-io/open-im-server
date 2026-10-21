# 契约快照

[中文](README.md) | [English](README.en.md)

`openapi/` 存放由网关文档路由提供的、已版本化的 REST 规范。入口见 [OpenAPI 快照索引](openapi/README.md)。

在本地运行环境中执行 `scripts/export-openapi-snapshots.ps1` 可重新生成快照；文档访问凭据必须从未纳入版本控制的本地环境配置读取，不得写入源码库。

WebSocket v1 契约维护在 [协议说明](../PROTOCOL.md)，事件名称由 `sdk/protocol-ws` 定义。
