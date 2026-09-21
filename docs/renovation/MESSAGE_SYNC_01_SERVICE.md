# 消息序号同步服务端改造方案

## 方案信息

- 方案集：`MESSAGE_SYNC`
- 顺序号：`01`
- 实施边界：消息服务、接入网关、数据与契约
- 前置方案：[MESSAGE_DOMAIN_01_DDD.md](MESSAGE_DOMAIN_01_DDD.md)
- 后置方案：[MESSAGE_SYNC_02_APP.md](MESSAGE_SYNC_02_APP.md)、[MESSAGE_SYNC_03_TEST.md](MESSAGE_SYNC_03_TEST.md)
- 适用标准：[10 业务微服务 DDD 工程规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)、[12 RocketMQ 规范](../standards/12_ROCKETMQ_CONVENTIONS.md)、[21 持久化技术栈规范](../standards/21_PERSISTENCE_STACK.md)

## 目标、结论与非目标

以“用户同步序号”而不是“是否离线”或“是否已读”判断客户端缺失消息。每个用户收到或自己发送一条消息时，在同一数据库事务中获得单调递增的 `syncSeq`；所有终端以本地已持久化的最大 `syncSeq` 拉取后续消息。

此模型不需要客户端预先知道所有会话，首次安装也能完整恢复；会话内 `seq` 继续用于会话排序和实时缺口诊断。

不将 MongoDB 变为权威消息库；不以厂商 Push 保证可靠性；不为 PC 管理后台增加用户同步能力。当前未上线，不保留 `/messages/offline` 兼容路径。

已确认的业务决策：发送方和接收方均进入同步索引；群消息以发送时成员快照为准，成员退群后保留退群前消息、不再接收退群后消息；消息和同步索引保留期与权威消息保留期一致；Push 仅发送“有新消息”提醒；同步中的媒体地址按当前媒体服务授权规则实时签发，禁止落库签名 URL。

## 权威数据模型与 Flyway

在 `im-services/message/im-message-service/src/main/resources/db/migration/` 新建后续 Flyway 脚本，创建以下表；脚本必须同时提供索引、注释和幂等约束。

```text
msg_user_sync_sequence
  user_id       bigint       primary key
  last_sync_seq bigint       not null
  updated_at    datetime(3)  not null

msg_user_sync_index
  user_id         bigint       not null
  sync_seq        bigint       not null
  msg_id          varchar(64)  not null
  conversation_id varchar(128) not null
  created_at      datetime(3)  not null
  primary key (user_id, sync_seq)
  unique key (user_id, msg_id)
  key (msg_id)
```

约束：`last_sync_seq` 表示已分配的最大序号，首条消息分配为 `1`；序号不得回收、复用或由 Redis 分配。`msg_user_sync_index` 只保存引用，不复制消息正文或媒体 URL。现有 `msg_message(conversation_id, seq)` 保持会话内唯一顺序。

同步索引不设置独立 TTL；删除权威消息时才删除对应索引。当前权威消息没有自动清理策略，故新设备可从 `syncSeq=0` 分页恢复全部仍可见历史。后续如新增消息归档或保留期，必须在同一需求中同时定义索引归档/删除行为和客户端“历史不可用”的提示。

在同一迁移批次删除 Mongo `msg_offline_inbox` 的写入、索引初始化和文档类。保留 `msg_hot_message`，它只能用于历史消息查询加速，故障或延迟时必须可回退 MySQL。

## 写入事务与领域接口

涉及的代码边界如下：

| 层级 | 新增或修改项 |
| --- | --- |
| `domain.message.model` | `UserSyncIndex`、`UserSyncSequence` 值对象/聚合模型 |
| `domain.message.repository` | `UserSyncIndexRepository`：分配序号、批量保存索引、按用户序号查询、按 `msgId` 删除 |
| `application` | `MessageApplicationService.storeNew`、`sync`、`markRead`、删除/清空的编排 |
| `infra.persistence.message` | PO、Mapper、Repository Adapter；所有原生 SQL 参数化 |
| `api.dto` | `MessageSyncRequest`、`MessageSyncResponse`、`SyncedMessageResponse` |
| `infra.messaging.outbox` | `MessageStoredEvent` 序列化新增接收用户的 `syncSeq` 映射 |

`MessageApplicationService.storeNew` 必须在**同一个本地事务**按以下顺序执行：

1. 校验发送权限，分配会话内 `seq`，写入 `msg_message`。
2. 计算同步对象：私聊为发送方和接收方；群聊为发送方及消息接受时刻的全部成员。现有群组 `maxMembers` 默认且上限为 `500`，消息服务必须复核成员数不超过该上限，超限即拒绝本次发送，禁止改为异步索引或部分扇出。先去重，再按 `userId` 排序以避免并发锁顺序死锁。
3. 对每个同步对象以行锁方式原子递增 `msg_user_sync_sequence.last_sync_seq`，写入对应的 `msg_user_sync_index`。
4. 将 `userId -> syncSeq` 写入 `MessageStoredEvent.recipientSyncSeqs`；WebSocket 投递对象仍只包含接收方，发送方的其他设备通过同步接口补齐。
5. 写入 Outbox。任一步失败均回滚消息、用户索引、序号和 Outbox。

不能在 Outbox 消费者、Mongo 投影或网关投递后再补写同步索引，否则会产生不可恢复漏消息。

删除/撤回/清空操作必须在删除权威消息前或同一事务内删除相应 `msg_user_sync_index` 行、读状态并失效相关 Redis 未读缓存。同步游标允许跳号：客户端以返回的 `nextSyncSeq` 前进，不把“连续无空洞”作为数据约束。

撤回与“对所有人删除”均为全局不可见：消息、索引与读状态同事务处理。现有 `clear-private`、`clear-group` 不得再删除权威消息：清空会话改为客户端当前设备的本地展示操作，不调用服务端。群消息的全局删除仅允许群主或管理员；为此扩展 `GroupMembershipPort` 与会话服务内部授权契约以返回 `GroupRole`，不能再以“是成员”作为删除授权。

## HTTP 契约与实现文件

在 `MessageController` 新增 `GET /messages/sync`，在 `docs/contracts/openapi/message.json` 定义并生成 App 客户端；不再暴露 `/messages/offline`。

请求参数：

```text
afterSyncSeq  required int64, >= 0
limit         optional int32, default 200, range 1..500
```

响应：

```json
{
  "items": [
    {
      "syncSeq": 43,
      "message": {
        "msgId": "...",
        "conversationId": "private:7:9",
        "seq": 12,
        "chatType": "private",
        "fromUserId": 7,
        "toId": "9",
        "msgType": "text",
        "content": "...",
        "createdAt": "2026-08-04T10:00:00Z"
      }
    }
  ],
  "nextSyncSeq": 43,
  "hasMore": true
}
```

查询语义：`WHERE user_id=:currentUserId AND sync_seq>:afterSyncSeq ORDER BY sync_seq ASC LIMIT :limitPlusOne`，再批量按 `msg_id` 获取仍可见的权威消息并签发媒体 URL。`limitPlusOne` 仅用于计算 `hasMore`；最后一个实际返回项的 `syncSeq` 是 `nextSyncSeq`。即使索引引用的消息已被删除，也必须推进游标至该索引的 `syncSeq`，避免无限重复扫描。

在 `MessageApplicationService.markRead` 增加批量归属校验：查询 `msg_user_sync_index(user_id, msg_id)` 与权威消息；只有索引存在且消息仍可见才写 `msg_read_status`。这统一覆盖私聊接收方和群成员，禁止客户端提供任意 `msgId` 污染已读状态。

## 网关与事件契约

修改 `sdk/protocol-mq/.../MessageStoredEvent.java`，新增按用户 ID 表达的 `recipientSyncSeqs`。修改 `JacksonMessagePayloadCodec` 写入该字段。

修改 `gateways/im-access-ws/.../StoredMessageEventListener.java` 和 `StoredMessageWsPayloadFactory.java`：循环每位实际接收方时，从事件取得该用户的 `syncSeq`，在 `chat:receive` 载荷增加 `syncSeq`。缺少映射视为不可投递事件并重试，不允许默默发送无同步序号的帧。

实时帧仅加速展示；客户端仍可在重连或发现序号缺口后调用 HTTP 同步。发送方当前设备继续接收命令 ACK，其他发送端设备依赖 `msg_user_sync_index` 同步。

## 交付顺序与回滚

1. 先提交 Flyway、领域模型、Repository 与 MySQL 集成测试。
2. 完成写入事务和同步 HTTP 接口，更新 OpenAPI。
3. 扩展 MQ 事件与 Gateway 载荷；先部署服务端与网关，再发布 App。
4. App 切换并通过验收后，删除 `/messages/offline`、`OfflineInboxDocument` 和 Mongo 离线收件箱索引。

本次不保留旧端兼容；若发布验证失败，只能在停止流量后回滚到包含原接口与原事件契约的完整版本，禁止让新 App 连接旧服务端。

发布时消息服务、`im-access-ws` 和 App 必须同版本切换。Gateway 在新版本下拒绝未携带或不能处理 `syncSeq` 的旧客户端连接；发布窗口内不允许新旧 App、Gateway、消息服务混用。离线 Push 载荷不含消息正文、媒体 URL 或签名 URL，只携带必要的提醒和聚合标识。

## 变更清单与完成定义

| 范围 | 交付物 |
| --- | --- |
| Flyway | 两张用户同步表、索引及 Mongo 离线收件箱清理 |
| 消息服务 | 事务性索引写入、同步查询、已读授权、删除一致性 |
| OpenAPI | `/messages/sync` 请求/响应及删除 `/messages/offline` |
| MQ/网关 | `recipientSyncSeqs` 与 `chat:receive.syncSeq` |
| App | 按 [MESSAGE_SYNC_02_APP.md](MESSAGE_SYNC_02_APP.md) 切换 |
| PC 后台 | 仅回归审计与历史查询，无代码改造 |
| 测试 | 按 [MESSAGE_SYNC_03_TEST.md](MESSAGE_SYNC_03_TEST.md) 执行 |

完成标准：任意数量消息可从 `afterSyncSeq` 无重复、无遗漏补齐；任何网关、Redis、Mongo 故障均不损害 MySQL 同步事实；未授权用户不能读取或标记他人消息。
