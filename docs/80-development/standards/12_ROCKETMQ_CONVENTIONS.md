# RocketMQ 治理规范

[中文](12_ROCKETMQ_CONVENTIONS.md) | [English](12_ROCKETMQ_CONVENTIONS.en.md)

## 适用范围

- 本规范适用于所有通过 `protocol-mq` 定义或使用的 RocketMQ Topic、Producer Group、Consumer Group、消息契约及运行配置。
- 当前 MQ 集群为独立集群。资源名称不得附加 `dev`、`test`、`prod` 等环境后缀；环境隔离由独立 NameServer、Broker、ACL 与部署配置完成。
- 已实现资源的唯一登记来源为 [MQ 资源台账](../../30-reference/mq/REGISTRY.md)。未登记资源不得接入生产链路。

## 术语与前置假设

| 术语 | 定义 |
| --- | --- |
| Topic | 消息主题，格式 `<领域>_<实体>_<类别>_<动作>_v<主版本>`；类别限定 `command`、`event`。 |
| Producer Group | 生产者分组，格式 `<服务名>-producer`。 |
| Consumer Group | 消费者分组，格式 `<服务名>-<消费目的>-consumer`。 |
| 顺序键 | 决定同一分区内顺序的字段；本工程消息链路以 `conversationId` 为顺序键。 |
| 幂等键 | 消费者去重依据，须在台账登记。 |
| Outbox | 在同一数据库事务内写业务数据与待发记录，再由 Relay 可靠发布。 |
| DLQ | 达到最大重试次数后的死信队列；须人工或受控补偿处置。 |
| 受控缩写 | 仅允许 `im`、`mq`、`ws`、`dlq`、`acl`；其余缩写须先在本规范定义。 |

**前置假设**：MQ 为独立集群（环境隔离由独立 NameServer/Broker/ACL 完成）；协议常量集中在 `protocol-mq`；资源台账可写并可校验。

## 命名规则

| 资源 | 格式 | 示例 |
| --- | --- | --- |
| Topic | `<领域>_<实体>_<类别>_<动作>_v<主版本>` | `im_message_command_send_v1` |
| Producer Group | `<服务名>-producer` | `<service>-producer` |
| Consumer Group | `<服务名>-<消费目的>-consumer` | `<service>-<purpose>-consumer` |

- Topic 仅使用小写字母、数字和下划线（与上表格式 `<领域>_<实体>_<类别>_<动作>_v<主版本>` 一致）；各段表达稳定业务语义，禁止使用环境、主机、部署批次或临时需求名称。
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

- 新增、重命名、废弃或变更 MQ 资源时，必须在同一变更中同步更新协议常量、[MQ 资源台账](../../30-reference/mq/REGISTRY.md)、所属服务 README 与相关校验门禁。
- 变更 Topic 的 Payload、顺序键、幂等键、重试策略或消费目的时，必须完成生产者、消费者、Outbox/Relay 与回放方案的兼容性评审。
- 合并前必须执行工程规则要求的编码、构建与相关测试校验；未完成台账和服务文档同步的变更不得合并。
- `mq-registry` 只校验名称一致性；`mq-semantics` 校验 Topic/Consumer Group 的顺序键与幂等键是否为真实值。
  后者现在是 **blocking**：47 行待补已清零，消息链路统一 `conversationId`；`read`/`recall` 命令新增
  `conversationId` 字段，客户端必须随请求下发。

## 资源登记模板

**新增或变更一个 MQ 资源时，按此清单落地（示例，措辞不构成强制）**：

1. **协议常量**：在 `protocol-mq` 增加常量（Topic / Producer Group / Consumer Group），业务侧只引用常量。
2. **命名**：Topic 用 `<领域>_<实体>_<类别>_<动作>_v<主版本>`；组名用 `<服务名>-...`；不含环境后缀。
3. **台账**：[MQ 资源台账](../../30-reference/mq/REGISTRY.md) 登记资源名、生产方、消费方、顺序键、幂等键与重试/DLQ 策略。
4. **服务配置**：Producer Group 走服务专属变量 `<SERVICE>_MQ_PRODUCER_GROUP`。
5. **可靠性**：权威写入走 Outbox；消费者按登记的幂等键去重（数据库唯一约束或持久化幂等记录）。
6. **文档**：同步所属服务 README 与相关门禁清单。

```text
protocol-mq 常量:  <DOMAIN>_<ENTITY>_<KIND>_<ACTION>_V1
Producer Group:     <service>-producer
Consumer Group:     <service>-<purpose>-consumer
台账行:             resource | producer | consumer | orderKey | idempotencyKey | retry | dlq
```

## 可验证的验收标准

| # | 判据 | 证据形式 |
| --- | --- | --- |
| 1 | 所有 Topic/Producer/Consumer 在 `protocol-mq` 有常量定义，业务侧无重复字面量 | 常量清单与代码检索 |
| 2 | 资源名不含环境后缀（`dev`/`test`/`prod`），不含主机或批次名 | 资源名清单 |
| 3 | 每个已实现资源都在台账登记，且台账与协议常量一致 | 台账与常量对照 |
| 4 | 消息链路以 `conversationId` 为顺序键且同一会话一致 | 生产者代码与台账 |
| 5 | 权威写入走 Outbox，业务事务中无直接 MQ 发送 | 代码审查与 Relay 日志 |
| 6 | 消费者按台账登记的幂等键去重，重复执行安全 | 幂等记录或唯一约束与重放测试 |
| 7 | ACL 启用时客户端配置 `AclClientRPCHook`，密钥仅来自受管配置 | 配置审查；代码与日志中无密钥 |

**完成定义**：上表逐项有可复现证据，且统一工程校验入口在该范围退出码为 0。

## 与其他规范的关系

| 关系 | 文档 | 说明 |
| --- | --- | --- |
| 引用 | [MQ 资源台账](../../30-reference/mq/REGISTRY.md) | 已实现资源的唯一登记来源 |
| 关联 | [40_OBSERVABILITY_CONVENTIONS.md](40_OBSERVABILITY_CONVENTIONS.md) | 消息链路的关联标识、指标与脱敏要求 |
| 关联 | [10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md](10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md) | 领域事件与可靠发布的架构约束；本规范给出 MQ 侧规则 |
| 引用 | [ENGINEERING_RULES.md](../governance/ENGINEERING_RULES.md) | 统一工程校验入口与项目级启用方式 |

