# 业务文档目录

[中文](README.md) | [English](README.en.md)

> 状态：`current`（`develop/2.3.0`）  
> 业务语义正文以中文为准；本目录提供英文索引。历史改造背景见 `docs/archive/renovation/`。
> 领域分类与依赖见[业务域版图](DOMAIN_MAP.md)。

| 文档 | 说明 |
| --- | --- |
| [账号与权限模型](ACCOUNT_PERMISSION_MODEL.md) | 平台账号、IM 账号、租户/门店角色和运营权限 |
| [业务域版图](DOMAIN_MAP.md) | 核心/支撑/通用/边缘域、Owner 与依赖规则 |
| [公共支撑域蓝图](PLATFORM_DOMAINS.md) | 交易、库存、用户、订单等概念与三阶段演进 |
| [架构图与业务线](ARCHITECTURE_DIAGRAMS.md) | IM 与 SaaS 两条业务线、域边界和关键链路 |
| [会话类型与隐私边界](CONVERSATION_TYPES_AND_PRIVACY.md) | 私聊、群聊、频道、私密会话与数据边界 |
| [项目事实清单](PROJECT_FACTS_INVENTORY.md) | 当前产品/工程事实清单 |
| [用户删除与数据生命周期](USER_DELETION_AND_DATA_LIFECYCLE.md) | 注销、删除、保留和审计要求 |
| [功能专题](features/README.md) | 频道、私密会话、私密群聊等实现专题 |
| [酒店业务](hotel/README.md) | 房型、房价、库存日历、预订支付与入住退房 |
| [库存平台域](inventory/README.md) | 公共支撑域：Stock 采销库存 |
| [订单平台域](order/README.md) | 共享订单交易内核 + 各业务履约扩展 |

## 相关

- [服务目录](../50-services/README.md)
- [工程拓扑](../20-architecture/TOPOLOGY.zh.md)
- [文档中心](../README.md)
