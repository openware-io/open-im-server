# im-access-ws

## 当前实时与推送语义

- WebSocket 事件用于实时展示，HTTP `/messages/sync` 负责断线接续与最终一致性。
- 在线态通过 Redis 集群协调，避免多 Pod 场景将在线用户误判为离线。
- ACK 正式环境启用极光与生产 APNs；通知默认不包含消息正文和发件人，推送失败不影响权威消息落库。
- 详情见 [聊天可靠性与群治理服务端改造说明](../../renovation/CHAT_RELIABILITY_01_SERVICE.md)。

## 职责

- WebSocket 长连接接入
- 握手鉴权
- 在线会话维护
- `chat:send` 事件改为投递 RocketMQ 命令，不再直接写消息库
- 订阅 `im_message_event_stored_v1` 事件并向在线会话下行
- Redis 协同的跨实例广播与投递
## 对外入口

- `wss://{gateway}/ws/im/v1?ticket={one-time-ticket}`

## 约束

- 只做接入层职责，不承载复杂业务规则
- 在线状态与跨实例投递不能依赖单机内存，必须通过 Redis 协同
- 使用 Producer Group `im-access-ws-producer` 发布 `im_message_command_send_v1`；运行配置变量为 `IM_ACCESS_WS_MQ_PRODUCER_GROUP`
- 使用 Consumer Group `im-access-ws-delivery-consumer` 消费 `im_message_event_stored_v1`，以 `conversationId` 作为 MQ 分片键保障会话顺序，并以 `eventId` 实现幂等
- 发送命令使用 `commandId` 作为幂等键；消费者的重复投递、重试和 DLQ 处理不得重复下行业务副作用
- 启用 MQ ACL 时，Remoting 客户端必须配置 `AclClientRPCHook`；资源定义和变更规则见 [12 RocketMQ 治理规范](../../standards/12_ROCKETMQ_CONVENTIONS.md) 与 [MQ 资源台账](../../mq/REGISTRY.md)
- 启动仅扫描网关自身组件，并显式装配 Redis、RocketMQ 和 JWT 解析所需的平台配置；不会加载业务服务组件或 MyBatis 配置
- 网关通过排除 `infrastructure` 的 MyBatis 传递依赖维持无持久化运行时；消息事件下行发布失败会释放 `eventId` 去重占用并抛回 MQ 触发重试
