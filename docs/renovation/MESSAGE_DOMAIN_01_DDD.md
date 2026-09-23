# im-message 域 DDD 改造方案

## 1. 文档定位

本文是 `im-message` 域迁移至既定工程规范的项目专用改造方案，不修改 [10 业务微服务 DDD 工程规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md) 与 [20 Maven 工程规范](../standards/20_MAVEN_ENGINEERING_CONVENTIONS.md) 的定义。

## 2. 目标与边界

1. 保持 `im-message-api` 与 `im-message-service` 的局部 Reactor 结构。
2. 将 Service 内部收敛为 `api -> application -> domain <- infra`，领域代码不得依赖 Spring、MyBatis-Plus、Jackson、Redis、MongoDB、RocketMQ 或 HTTP 协议 DTO。
3. 保持 MySQL 为唯一消息权威库；MongoDB 只保存可再生的热消息查询投影，Redis 只保存会话序号、去重和未读热点状态。离线补偿以 MySQL 用户同步序号收件索引实现，不建立或读取 MongoDB 离线收件箱。
4. 保持消息命令 Topic、消费组、`conversationId` 顺序键、`commandId` 幂等键、Outbox Topic 和事件字段兼容。
5. 保持 `/messages/**` 路径、认证语义、请求字段、响应字段和错误码兼容。

不在本次改造中将消息域拆分为新的服务；不修改既有 `msg_` 表数据所有权；不借改造重定义 WebSocket 或 RocketMQ 协议。

## 3. 实施状态与当前偏差

### 已完成

1. 已建立 `api -> application -> domain <- infra` 的核心消息写入、查询与 HTTP 接口结构；HTTP Controller 通过 Request/Response DTO、Converter 和应用命令/查询交互。
2. 已将消息、已读状态和 Outbox 拆为 `domain.message` 纯领域模型、仓储契约与端口；MyBatis PO、Mapper、仓储适配器、Redis 序列适配器和 Outbox 投递实现均位于 `infra`。
3. 已增加核心分层架构测试，检查 `api`、`application`、`domain.message` 的 import 方向及领域层框架依赖禁令；原有领域、应用、转换、Outbox、Flyway 与 MQ 配置测试继续保留。

### 未完成与遗留

1. 启动扫描仍为 `io.openware.im`，未完成批次 0 要求的 `io.openware.im.message` 收敛和共享基础设施显式装配。
2. 好友、群组及其 DTO、服务、实体、Mapper 风格仓储仍在该服务源码中；敏感词和系统配置的无调用历史 ORM 代码已移除。好友、群成员端口的实现仍通过 `LegacyFriendRelationAdapter`、`LegacyGroupMembershipAdapter` 依赖历史服务。
3. 分层架构测试仅对已迁移的 `io.openware.im.message` 核心路径设为强制门禁，不将上述跨域遗留纳入领域纯净性断言，避免将已知迁移工作伪装为已完成。

| 编号 | 优先级 | 状态 | 内容与后续方向 |
| --- | --- | --- | --- |
| M-01 | P0 | 已完成 | 消息、已读、Outbox 已拆为纯领域模型与 `infra.persistence` PO |
| M-02 | P0 | 已完成 | 领域层已定义 Repository 契约，基础设施提供 Mapper 与 Adapter |
| M-03 | P0 | 已完成 | Controller 已通过 HTTP DTO、Converter、Application Command/Result 交互 |
| M-04 | P0 | 已完成 | 应用层编排消息写入，领域端口隔离序号与序列化，基础设施提供实现 |
| M-05 | P1 | 已完成 | Outbox Relay 已迁入 `infra.messaging.outbox`，保持先投影后发布的运行语义 |
| M-06 | P1 | 进行中 | 已清理敏感词、系统配置无调用历史 ORM 代码；继续清理好友、群组历史 ORM 代码，或迁移到其权威服务 |
| M-07 | P1 | 保持 | `im-message-api` 保持为空制品，直至存在稳定跨服务 HTTP/Java 契约 |
| M-08 | P2 | 进行中 | 核心分层边界测试已建立；完成启动扫描和跨域遗留收口后扩大门禁范围 |
| M-09 | P0 | 待实施 | 按 `MESSAGE_SYNC_01_SERVICE.md`、`MESSAGE_SYNC_02_APP.md` 与 `MESSAGE_SYNC_03_TEST.md` 将固定条数离线拉取迁移为用户同步序号增量同步 |

## 4. 目标结构

```text
io.openware.im.message
├── api
│   ├── controller
│   ├── dto
│   │   ├── request
│   │   └── response
│   └── converter
├── application
│   ├── command
│   ├── query
│   ├── result
│   └── MessageApplicationService.java
├── domain
│   └── message
│       ├── model
│       ├── event
│       ├── port
│       └── repository
├── infra
│   ├── persistence
│   │   └── message
│   ├── messaging
│   │   └── outbox
│   ├── projection
│   ├── cache
│   └── config
└── common
    └── exception
```

## 5. 迁移批次

### 批次 0：冻结边界

1. 已建立方案、核心分层 import 扫描和 Flyway 表集合检查。
2. 未完成：将启动扫描收敛至 `io.openware.im.message`，仅通过显式配置加载共享基础设施。

### 批次 1：权威消息写入与 Outbox

1. 迁移 `Message`、`MessageReadStatus`、`MessageOutbox` 为纯领域模型。
2. 建立消息、已读、Outbox Repository 与领域端口。
3. 将 MQ 命令消费适配器保留在 `infra.messaging`，由应用服务完成幂等、会话序号、权威消息和 Outbox 的同事务写入。
4. 保持 `msg_message`、`msg_read_status`、`msg_outbox` 及 MQ 协议不变。

### 批次 2：查询与会话管理 HTTP API

1. 将历史、搜索、离线、已读、删除、清理、撤回迁入应用服务。
2. 建立 API Request/Response DTO 和 Converter，禁止返回领域对象或动态 Map。
3. 好友与群成员校验改为领域端口；端口尚无稳定实现前，不得保留对消息域历史表的直接依赖。

### 批次 3：投影与遗留收口

1. 已完成 Outbox Relay 的基础设施迁移；Mongo/Redis 投影仍位于 `io.openware.im.message.projection`，待迁入 `infra.projection` 时保持现有投影与事件发布顺序。`msg_offline_inbox` 为未被读取的冗余投影，应随消息同步改造移除；`msg_hot_message` 保留为可再生的热消息查询投影。
2. 已完成：移除 `SensitiveWord`、`ContentFilterService`、`SensitiveWordRepository`、`SystemConfig`、`AppConfigService`、`SystemConfigRepository` 的无调用消息域历史代码，并由静态引用测试防止重新引入；未完成：清理 `Friend`、`FriendRequest`、`Group`、`GroupMember` 的消息域历史 ORM 代码，或迁移到其权威服务。
3. 已完成 API、领域、应用的核心自动化边界测试；待启动扫描和跨域遗留收口后扩展至全模块基础设施边界。

## 6. 完成定义

消息域完成不以包名迁移定义，而以领域层无框架依赖、Controller 不暴露领域对象、MyBatis PO/Mapper 仅存在于基础设施层、消息、用户同步索引和 Outbox 同事务、MQ 契约兼容、Mongo 仅作为可再生投影、用户同步序号增量同步替代固定条数离线拉取、构建与边界门禁通过定义。
