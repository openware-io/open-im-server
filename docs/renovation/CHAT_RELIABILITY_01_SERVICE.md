# 聊天可靠性与群治理服务端改造说明

## 方案信息

- 方案集：`CHAT_RELIABILITY`
- 顺序号：`01`
- 实施边界：消息服务、会话服务、WebSocket 接入、MySQL、RocketMQ、Redis 与 ACK 运行配置
- 关联契约：[消息 OpenAPI 快照](../contracts/openapi/message.json)、[会话 OpenAPI 快照](../contracts/openapi/conversation.json)
- 适用标准：[DDD 服务约定](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)、[RocketMQ 约定](../standards/12_ROCKETMQ_CONVENTIONS.md)、[数据库迁移规范](../DATABASE_MIGRATION_STANDARD.md)

## 目标与边界

本方案保证消息先持久化、再投递和推送。离线 Push 只是一种提醒，不参与消息可靠性、历史记录或未读状态的判定。MySQL 中的消息、用户同步索引和用户级已读状态是唯一权威数据；MongoDB 热投影、Redis 在线态和 WebSocket 下行均可重建。

本方案不把群成员、角色或好友关系复制到消息域。群成员资格和群角色由 `im-conversation-service` 管理，私聊好友关系由用户服务管理；消息服务只通过内部授权端口判断可见性和发送权限。

## 消息接续与已读

1. 每位消息接收者（私聊也包含发送者）拥有单调递增的 `syncSeq`。群消息的接收者以消息写入时的成员快照为准。
2. 客户端在首次安装、前后台切换、断线重连和发现实时帧缺口时调用 `GET /messages/sync`。`afterSyncSeq` 只能向前推进，客户端以服务端返回的 `nextSyncSeq` 为准，不能被旧响应回退。
3. `/messages/sync` 的每个条目同时返回 `syncSeq`、消息正文和当前认证用户的 `readAt`。`readAt` 缺失表示当前用户未读；不得使用消息的全局状态推导用户已读状态。
4. `POST /messages/read` 仅接受当前用户同步索引中仍然可见的消息。这样新设备恢复历史后，未读数与已读水位仍按用户隔离。
5. 用户退群后，历史同步和搜索会再次校验当前成员资格；退群前已写入的同步索引不会让退群用户继续拉取群消息。

## 可见性、撤回与离线提醒

- 私聊历史、搜索、清空和已读操作要求双方仍是好友；群聊历史、搜索和已读操作要求当前仍为群成员。
- 撤回采用 tombstone：权威消息保留 `RECALL/RECALLED` 状态和同步索引，不物理删除同步游标所需记录；消息服务通过 MQ 和 WebSocket 通知在线端即时替换本地展示。
- 消息必须在本地事务中完成权威落库、用户同步索引和 Outbox 写入后，才由事件驱动实时下行或离线提醒。极光失败、设备令牌失效或用户离线都不能影响聊天记录。
- 极光默认不发送正文和发件人，仅发送通用提醒与会话定位所需字段。ACK 正式环境使用 `JPUSH_ENABLED=true` 与 `JPUSH_APNS_PRODUCTION=true`；AppKey、Master Secret 等仅保存于 Kubernetes Secret。

## 群聊治理规则

| 场景 | 当前规则 |
| --- | --- |
| 创建群 | 群主为 `OWNER`，默认最多 500 人，默认允许普通成员邀请。所有初始成员必须是 ACTIVE 用户。 |
| 邀请成员 | `OWNER`、`ADMIN` 始终可邀请；`MEMBER` 仅在 `allowMemberInvite=true` 时可邀请。入群人数在群行锁内校验，避免并发超员。 |
| 修改群资料与邀请开关 | `OWNER`、`ADMIN` 可更新；邀请开关由 `UpdateGroupRequest.allowMemberInvite` 传入。 |
| 踢人、禁言与角色 | 踢人必须角色高于目标；禁言仅 `OWNER`、`ADMIN`；角色调整仅 `OWNER`，群主转让只能经群主离群流程。 |
| 成员离群 | 普通成员直接离开；群主离开时按“管理员优先、同角色按最早入群时间”转让。无继任者时解散群。 |
| 成员资料 | `GET /groups/{id}/members` 返回 `userId`、昵称、用户名、角色和头像，用户资料通过批量内部接口补齐，避免 N+1 调用。 |

群创建、入群、退群、踢人、禁言、角色调整、群主转让和解散均发布成员/授权变更事件。WebSocket 接入层收到事件后通知在线客户端刷新群信息与成员列表。

## 数据与迁移

- `msg_read_status` 通过 `V2__add_msg_read_status_sync_index.sql` 增加 `(user_id, msg_id)` 索引，支撑 `/messages/sync` 按当前用户批量读取 `readAt`。
- 本次无需新增表，也不得修改已经执行的 Flyway 脚本。后续结构调整必须新增更高版本迁移并遵守 [数据库迁移规范](../DATABASE_MIGRATION_STANDARD.md)。
- ACK 已验证该迁移将 `im_server` 从 v1 升级至 v2；发布前应确认 Flyway 日志中存在成功应用记录。

## ACK 发布与验收

1. 使用 `scripts/deploy/build-saas-release.ps1 -FormalRelease -FormalTargets ...` 构建不可覆盖的正式 ACR 制品，并使用 `scripts/deploy/ack.ps1 -ReleaseManifestPath ...` 发布。ACK 入口校验正式清单、纯 SemVer tag 与 ACR digest，并按版本 tag 更新工作负载、按 Pod `imageID` 核验 digest。
2. 先确认 `gv-im-config`、`gv-im-secret`、`acr-public-key` 与 TLS Secret 存在，再执行发布。推送密钥不得写入仓库或日志。
3. 依次确认 MySQL、Redis、MongoDB、RocketMQ、MinIO 就绪，随后等待全部业务 Deployment `READY=UPDATED=AVAILABLE=1`。
4. 验收至少覆盖：消息服务 Flyway v2 成功、会话服务启动成功、WebSocket 无重启、`JPUSH_ENABLED=true`、`JPUSH_APNS_PRODUCTION=true`，以及 `/messages/sync` 的 `readAt` 返回。

若新镜像无法就绪，按 Deployment 执行 `kubectl rollout undo` 恢复上一个健康版本；已成功执行的 Flyway 迁移不回滚、不修改。
