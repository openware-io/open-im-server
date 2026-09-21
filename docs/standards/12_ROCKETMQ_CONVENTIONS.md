# RocketMQ 治理规范

## 适用范围

- 本规范适用于所有通过 `protocol-mq` 定义或使用的 RocketMQ Topic、Producer Group、Consumer Group、消息契约及运行配置。
- 当前 MQ 集群为独立集群。资源名称不得附加 `dev`、`test`、`prod` 等环境后缀；环境隔离由独立 NameServer、Broker、ACL 与部署配置完成。
- 已实现资源的唯一登记来源为 [MQ 资源台账](../mq/REGISTRY.md)。未登记资源不得接入生产链路。

## 命名规则

| 资源 | 格式 | 示例 |
| --- | --- | --- |
| Topic | `<领域>_<实体>_<类别>_<动作>_v<主版本>` | `im_message_command_send_v1` |
| Producer Group | `<服务名>-producer` | `im-access-ws-producer` |
| Consumer Group | `<服务名>-<消费目的>-consumer` | `im-message-service-write-command-consumer` |

- Topic 仅使用小写字母、数字和点号；各段表达稳定业务语义，禁止使用环境、主机、部署批次或临时需求名称。
- Topic 类别限定为 `command`、`event`；新增类别必须先更新本规范并完成评审。
- Producer Group 与 Consumer Group 使用小写连字符分段；同一服务的不同消费目的必须使用不同 Consumer Group。
- 受控缩写仅允许使用领域通用缩写：`im`（即时通讯）、`mq`（消息队列）、`ws`（WebSocket）、`dlq`（死信队列）、`acl`（访问控制列表）。除此之外的缩写必须在本规范中定义后才可使用。

## 协议与配置边界

- Topic、Producer Group、Consumer Group 必须集中定义在 `protocol-mq` 的协议常量中；业务服务只引用常量，不得自行声明重复字面量。
- 禁止硬编码 Topic 或 Group 字符串，禁止通过字符串拼接、格式化或环境后缀构造资源名称。
- 服务配置中的 Producer Group 必须使用服务专属变量，变量名遵循 `<SERVICE>_MQ_PRODUCER_GROUP`。例如：`IM_MESSAGE_SERVICE_MQ_PRODUCER_GROUP`、`IM_ACCESS_WS_MQ_PRODUCER_GROUP`。
- Consumer Group、Topic、分片键、幂等键的语义属于协议契约；变更必须先兼容旧消费者，再按版本化 Topic 完成迁移。

## 可靠性与顺序

- RocketMQ 按至少一次投递设计。消费者必须按台账登记的幂等键去重，并以数据库唯一约束或可持久化幂等记录作为最终保障。
- 消息服务写命令与消息已存储事件均以 `conversationId` 为顺序键；同一会话必须使用完全相同的顺序键，禁止改用 `messageId`、用户标识或随机值。
- 权威写入与事件发布必须采用 Outbox：在同一数据库事务内提交业务数据和 Outbox 记录，由 Relay 可靠发布。禁止“写库后尽力发送 MQ”。
- Relay 与消费者必须记录可定位字段，至少包括 `conversationId`、`commandId` 或 `eventId`，并明确重复、失败与补偿语义。
- 任何拥有权威数据的业务服务都可以通过本域 Outbox Relay 作为 MQ 生产者；该服务不得在业务事务中直接发送 MQ。网关仅负责协议下行与接入，不拥有业务事件的权威生产职责。

## 重试、DLQ 与安全

- 可恢复失败使用 RocketMQ 重试机制；消费者必须保证重复执行安全，不得依赖“只投递一次”。
- 达到最大重试次数后进入对应 Consumer Group 的 DLQ。DLQ 消息必须经人工或受控补偿流程处置，补偿前确认幂等键、顺序影响和原始失败原因。
- 不可恢复的契约、校验或业务拒绝错误不得无限重试；必须记录失败原因并按运维流程转入 DLQ 或受控终止。
- 启用 ACL 时，所有 Remoting 客户端必须配置 `AclClientRPCHook`；Access Key 与 Secret Key 仅从受管配置读取，禁止写入代码、日志、文档示例或资源名称。

## 变更门禁

- 新增、重命名、废弃或变更 MQ 资源时，必须在同一变更中同步更新协议常量、[MQ 资源台账](../mq/REGISTRY.md)、所属服务 README 与相关校验门禁。
- 变更 Topic 的 Payload、顺序键、幂等键、重试策略或消费目的时，必须完成生产者、消费者、Outbox/Relay 与回放方案的兼容性评审。
- 合并前必须执行工程规则要求的编码、构建与相关测试校验；未完成台账和服务文档同步的变更不得合并。
