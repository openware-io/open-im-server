# 频道域服务端设计（CHANNEL_01_SERVICE）

> 文档定位：`im-conversation-service` 频道（Channel）域的工程沉淀文档，供后续开发与维护对齐。频道属"单向广播"会话空间，产品语义见 [会话空间与隐私边界](../business/CONVERSATION_TYPES_AND_PRIVACY.md)；实现遵守 [10 DDD 服务工程规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md) 与 [ENGINEERING_RULES](../ENGINEERING_RULES.md)。

## 1. 领域语义

频道是"一对多单向广播"会话（Telegram 类比）：

- **单向发布**：仅频道主（owner）可发布消息，订阅者只读，不能发言。
- **订阅关系**：用户可订阅频道成为 subscriber；owner 同时是订阅者。
- **会话形态**：频道在会话表中以 `channel` 类型存在，消息归属 `channel` 会话（`ConversationIds.channelConversation(channelId)`），消息流与云端单聊/群组共用消息表，靠会话类型与序号隔离。

## 2. 领域模型（`domain.channel`）

- `Channel`：频道聚合根。字段含 `name/ownerUserId/status`（`active/dissolved`）等；`myRole`、`subscribed`、`memberCount` 为视图派生值。
- `ChannelSubscription`：订阅关系聚合。字段含 `channelId/userId/role`（`OWNER`/`SUBSCRIBER`）/`status`。
- `ChannelRepository` / `ChannelSubscriptionRepository`：领域仓储契约，由 `infra.persistence.channel` 的 PO/Mapper/Adapter 实现。

## 3. 应用服务（`application.channel`）

- `ChannelApplicationService`：创建频道、订阅/退订、我的频道、频道详情（返回 `ChannelResult`，含 `myRole/subscribed/memberCount`）、频道授权查询（`isOwner/isSubscribed`）。
- 校验：订阅者必须是 ACTIVE 用户；同一用户对同一频道唯一订阅；owner 退订会解散频道（或按产品决策保留 owner 不退订）。

## 4. 消息面（跨服务）

- `im-message-service` 的 `MessageApplicationService.listChannelMessages(channelId, afterSeq, limit)`：先通过 `ChannelMembershipPort`（内部接口 `/internal/channels/{id}/owner|subscribed`）校验 `isOwner || isSubscribed`，再按 `channel` 会话内序号递增拉取，返回 `List<MessageResult>`。
- 删除消息：仅 owner 可执行（`assertCanDelete` 中 `CHANNEL + channelMembershipPort.isOwner` 分支）。

## 5. 数据模型（Flyway）

- `V3__init_channel_schema.sql` 初始化 `channel` 与 `channel_subscription`。
- `V6__fix_channel_secret_chat_schema_conventions.sql`：表名规范化 + 审计字段（`created_by/updated_by`）+ `bigint unsigned` 主键，对齐工程数据库规范（[21 持久化栈](../standards/21_PERSISTENCE_STACK.md)）。

## 6. 接口契约

- 客户端：`/channels/**`（网关 `/api/v1/channels/**`）。
- 内部：`/internal/channels/{id}/owner?userId=`、`/internal/channels/{id}/subscribed?userId=`（服务间签名鉴权，供消息服务授权查询）。

## 7. 测试

- `ChannelApplicationServiceTest`：创建/订阅/角色/成员数/授权 5 用例；消息面用例见 `MessageApplicationServiceTest`（频道拉取/owner 删除）。
