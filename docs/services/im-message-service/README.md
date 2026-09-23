# im-message-service

## 当前消息可靠性语义

- MySQL 消息、用户同步索引和用户级 `msg_read_status` 是权威数据；Push、Redis 和 MongoDB 不是消息记录来源。
- `/messages/sync` 返回当前认证用户的 `readAt`，用于新设备恢复和未读数计算；退群后不会继续同步该群消息。
- 撤回保留 tombstone 与同步游标，并通过 MQ/WebSocket 实时通知，不物理删除造成客户端游标断裂。
- 详情见 [聊天可靠性与群治理服务端改造说明](../../renovation/CHAT_RELIABILITY_01_SERVICE.md)。

## 职责

- 消费 `im_message_command_send_v1` 命令并完成消息权威落库
- 基于 `msg_outbox` 中转 `im_message_event_stored_v1` 事件
- 将近 30 天热消息和私聊离线收件箱投影到 MongoDB
- 维护 Redis 会话序列号与未读计数热点状态
- 提供消息历史、搜索、离线消息、已读回执、撤回与清空会话等查询管理接口
- 频道消息游标拉取（`/messages/channel/{channelId}?afterSeq=&limit=`，订阅校验后按会话内序号递增返回）
- 私密消息密文存储（`/secret-messages/**`：仅存密文与元数据，服务端不解密；游标拉取供参与设备同步）
- 私密消息定时销毁引擎（已读后计时 + 周期扫描，见 [私密聊天与 E2EE 服务端设计](../../features/SECRET_CHAT_01_E2EE.md)）

## 当前接口

- `/messages/**`
- `/secret-messages/**`

## 说明

- MySQL 是唯一权威消息库，消息表使用 `msg_` 领域前缀
- MongoDB 仅承载热投影和离线收件箱，可容忍短暂最终一致
- RocketMQ 当前采用 Remoting 直连 `NameServer/Broker`，后续可扩展 gRPC 客户端
- 使用 Producer Group `im-message-service-producer` 发布 `im_message_event_stored_v1`；运行配置变量为 `IM_MESSAGE_SERVICE_MQ_PRODUCER_GROUP`
- 使用 Consumer Group `im-message-service-write-command-consumer` 消费 `im_message_command_send_v1`，以 `conversationId` 保证会话顺序、以 `commandId` 实现幂等
- 权威消息写入与 `msg_outbox` 记录在同一事务提交，由 Outbox Relay 发布事件；重复投递、重试和 DLQ 处理必须保持 `eventId` 幂等
- 启用 MQ ACL 时，Remoting 客户端必须配置 `AclClientRPCHook`；资源定义和变更规则见 [12 RocketMQ 治理规范](../../standards/12_ROCKETMQ_CONVENTIONS.md) 与 [MQ 资源台账](../../mq/REGISTRY.md)
- 管理端消息治理接口已迁入 `im-admin-service`
- 群与会话元数据不在该服务承载，由 `im-conversation-service` 负责

## 分层架构门禁

- 核心消息链路遵循 `api -> application -> domain <- infra`：`api` 对消息核心只使用应用命令、查询和结果，`application` 只依赖领域模型、仓储与端口，`domain.message` 不依赖 Spring、MyBatis-Plus、Jackson、RocketMQ 或 Spring Data 实现
- `MessageLayerArchitectureTest` 在测试阶段扫描核心源文件 import，阻止 `api` 直接依赖 `domain` 或 `infra`，阻止 `application` 依赖 `api` 或 `infra`，并检查核心四层目录存在
- 本门禁覆盖已迁移的 `io.openware.im.message` 核心链路，不将历史跨域代码作为消息领域模型的一部分

## 已知遗留

- 启动类当前扫描 `io.openware.im`，尚未收敛到 `io.openware.im.message` 并通过显式配置引入共享基础设施；因此进程仍会发现同模块下的历史 Bean
- `io.openware.im.domain` 和 `io.openware.common.dto` 中仍保留好友、群组、敏感词、系统配置等历史代码；`LegacyFriendRelationAdapter`、`LegacyGroupMembershipAdapter` 仍直接调用其中的服务
- 上述遗留不改变消息权威写入主链路，但需要在跨域端口具备稳定实现后迁移至其权威服务，并移除 message 服务中的历史 ORM 与 Bean
# 好友通过自动消息

`im-message-service` 独立消费 `im_user_event_friend_accepted_v1`（消费组
`im-message-service-friend-accepted-consumer`）。好友申请被接受后，申请方收到同意方发出的正式私聊文本消息；同意方收到一条系统提示。两条消息都复用 `MessageApplicationService.store`，并分别创建“删除仅我”墓碑以对另一方隐藏，避免会话显示成双方已开始聊天；消息历史、同步索引、会话摘要、未读投影和 WebSocket/离线链路仍与普通消息一致。

消费至少一次投递，使用 `msg_friend_accept_message` 按 `request_id` 持久化幂等记录，并以数据库唯一约束兜底。资料服务或消息写入暂时失败时抛出异常触发 RocketMQ 重试/DLQ；若现有好友、私聊开关、禁言或审核策略明确拒绝写入，则记录 `SKIPPED`，后续重复事件不再生成消息。幂等记录不随消息硬删除而删除，避免旧事件重放重复发消息。
