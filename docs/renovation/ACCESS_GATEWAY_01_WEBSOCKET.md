# im-access-ws 长连接网关改造方案

## 1. 文档定位

本文是 `im-access-ws` 的项目专用重构方案。该模块是长连接接入网关，不是拥有业务事实的 DDD 业务服务；因此不套用业务服务内部 `api -> application -> domain <- infra` 目录模板。

规范依据：

- [10 业务微服务 DDD 工程规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)
- [12 RocketMQ 治理规范](../standards/12_ROCKETMQ_CONVENTIONS.md)
- [20 Maven 工程规范](../standards/20_MAVEN_ENGINEERING_CONVENTIONS.md)
- [21 持久层技术栈标准](../standards/21_PERSISTENCE_STACK.md)

## 2. 已确认架构决策

### 2.1 不创建 im-access-ws-api

不创建 `im-access-ws-api`，理由如下：

1. 全仓不存在其他模块对 `im-access-ws` Maven 制品、Java 包或 HTTP 路由的消费。
2. 网关对外只暴露 WebSocket 入口 `/ws/im`，不承载供其他服务同步调用的业务 API。
3. 客户端 WebSocket 帧、事件名和 DTO 由 `protocol-ws` 承载；跨服务命令、事件、Topic 和消费组由 `protocol-mq` 承载。
4. 新增空 API 制品会扩大版本与发布面，并违反“新增公开 API 必须存在稳定消费者”的规范。

未来仅在出现明确的跨 JVM 同步调用者，且确实需要复用网关专属、稳定的 Java 契约时，才通过架构评审新增 API 制品；不得以复用网关内部会话、认证、连接或实现对象为理由创建 API。

### 2.2 接入网关单向依赖

```text
客户端 WebSocket
       |
       v
im-access-ws
       |
       +--> protocol-mq 命令 --> 业务服务权威写入
       |
       +<-- protocol-mq 领域事件 <-- 业务服务 Outbox Relay
       |
       +--> 本机会话路由与 Redis 节点广播
```

业务服务不得依赖网关 Service、网关数据库、网关内部 Java 包或网关 API。网关不得访问业务 Service、业务数据库、Mapper、Repository、PO 或领域实体；它只依赖平台协议制品并通过 MQ 与业务域协作。

## 3. 目标职责与非目标

### 3.1 保留职责

1. `/ws/im` WebSocket 握手、认证上下文提取、帧解码、参数校验和协议错误响应。
2. 本机连接生命周期、`SessionRegistry`、心跳、背压与房间路由。
3. 将客户端命令转换为版本化 MQ 命令并异步投递。
4. 消费版本化 MQ 事件，去重后转换为 WebSocket 下行帧和本机/跨节点会话路由。
5. Redis 仅用于节点广播、在线会话租约和去重热点状态；本机 WebSocket 对象只保留在进程内。

### 3.2 严禁职责

1. 不拥有 MySQL、MongoDB、Flyway、MyBatis-Plus、Mapper、PO、Repository、领域实体或业务表。
2. 不执行好友、群成员、禁言、敏感词、消息撤回、已读、离线消息、设备令牌、推送、RTC、配置或管理业务规则。
3. 不直接调用任何业务 Service、数据库、Mapper、Repository 或读取业务表。
4. 不在业务事实落库前向客户端确认“已持久化”。

## 4. 改造结果与剩余事项

| 编号 | 优先级 | 偏差 | 目标处理 |
| --- | --- | --- | --- |
| G-01 | 已完成 | 网关持久层与业务副本 | 已删除实体、Mapper、Repository、业务服务、Push 代码和 MyBatis 运行依赖 |
| G-02 | 已完成 | 入站业务编排 | 消息发送、已读、撤回已改为版本化 MQ 命令；无权威契约的好友、群组、RTC 入站事件明确返回不可用 |
| G-03 | 已完成 | MQ 重复下行 | 使用 `eventId` Redis 原子去重；空收件人或节点广播失败会释放占用并触发 MQ 重试 |
| G-04 | 已完成 | 弱默认凭据 | 已移除数据库配置、Redis/JWT 默认密钥，启动时校验必填安全配置 |
| G-05 | 已完成 | 群成员与 Push 直连 | `MessageStoredEvent` 携带稳定接收用户集合；网关只按接收人路由，不查询群成员或设备令牌 |
| G-06 | 已完成 | 启动扫描与历史路径 | 启动扫描收敛至网关包，显式装配平台组件；Docker 与开发脚本已迁至 `gateways/` 路径 |
| G-07 | 待完成 | 客户端 ACK 协议兼容发布 | 需要客户端按“已受理”和“已持久化”状态完成灰度兼容后，才可完全废弃历史 ACK 解释 |
| G-08 | 待完成 | 在线会话 TTL 与节点故障补偿 | 需结合客户端心跳、离线收件箱和多节点演练完成统一租约与补偿策略 |
| G-09 | 待完成 | RocketMQ ACL 运行验证 | 平台配置已保留 AK/SK 模型，需在启用 ACL 的集成环境执行连接与重试验证 |

## 5. 目标工程结构

```text
io.openware.im.accessws
├── ImAccessWsApplication.java
├── websocket
│   ├── config
│   ├── handshake
│   ├── handler
│   ├── protocol
│   └── session
├── command
│   ├── inbound
│   ├── mapper
│   └── publisher
├── event
│   ├── consumer
│   ├── deduplication
│   └── mapper
├── routing
│   ├── local
│   └── cluster
├── security
├── observability
└── config
```

该目录是适配器按流向组织的网关结构，不引入 `domain`、`repository`、`entity`、`po` 或 `mapper` 持久化目录。

## 6. 协议与可靠性决策

### 6.1 上行命令与确认语义

1. `chat:send` 完成帧校验、认证和 MQ 成功受理后，只能返回“已受理”状态；其含义是命令已被网关接受并成功投递至 MQ。
2. 业务服务写入 MySQL 并提交 Outbox 后，发布 `MessageStoredEvent`；网关收到该事件后才下行“已持久化”确认和消息事件。
3. `commandId` 必须基于客户端稳定重试键生成或显式携带；网关重试不得重新生成不同命令标识。
4. `chat:read`、`chat:recall` 等状态变更同样以版本化 MQ 命令进入权威服务，网关不直接写业务表。

### 6.2 下行事件幂等

1. 每个 MQ 事件以 `eventId` 作为网关消费幂等键。
2. 事件处理前原子占用去重键；仅占用成功的消费者允许执行 WebSocket 下行、Push 触发或 Redis 广播。
3. 去重 TTL 必须覆盖 RocketMQ 最大重投窗口并保留安全冗余；TTL、命中、失败和重试必须记录指标与中文日志。
4. 事件处理失败不得确认消费，必须让 MQ 按既有重试/DLQ 策略处理。

### 6.3 节点路由与在线状态

1. `SessionRegistry` 是唯一的本机 WebSocket Session 所有者，不得序列化或写入 Redis。
2. Redis 在线状态使用节点标识、TTL 租约与续租；连接断开、心跳超时、节点宕机均必须可自动过期。
3. Redis Pub/Sub 仅承担在线节点即时广播，不作为权威投递保证；广播失败的补偿由客户端重连、离线收件箱或受控重试策略承担，并在协议中明确。

## 7. 分批实施

### 批次 0：基线、契约与安全

1. 建立网关架构测试，禁止 MyBatis、MySQL、Flyway、`domain.entity`、`domain.repository` 与业务 Service 被重新引入。
2. 盘点并冻结 WebSocket 帧、MQ Topic、消费组、事件字段和错误码兼容矩阵。
3. 移除默认凭据，增加配置启动校验、Origin 白名单、日志脱敏和帧大小限制。
4. 修复 Dockerfile、开发脚本中的旧 `im-services/access-ws` 路径。

### 批次 1：上行命令去业务化

1. 保留 WebSocket 帧解析、认证、结构校验和命令工厂。
2. 将发送、已读、撤回、输入、RTC 等入站事件改为 MQ 命令发布或明确的纯连接级处理。
3. 移除网关本地权限、敏感词、业务配置、消息读写、离线补偿和业务表查询。
4. 重定义 ACK 状态并完成客户端兼容迁移：先兼容新增字段/事件，再废弃旧“已持久化”ACK 语义。

### 批次 2：下行事件、幂等与节点路由

1. 将消息、好友等 MQ 监听器收敛为事件消费者与 WebSocket payload Mapper。
2. 增加 `eventId` 去重、失败重试、DLQ 观测与重复下行测试。
3. 将跨节点广播、在线状态、SessionRegistry 分离为 routing 组件，增加 TTL 续租与异常断开清理。
4. 将群成员受众、设备推送目标、离线收件箱等由业务服务事件或专用通知投影提供；网关不得回查业务表。

### 批次 3：清理、依赖与发布验证

1. 删除全部业务副本、MyBatis 依赖、MySQL 驱动、编译排除项及失效测试。
2. 将启动扫描收敛至 `io.openware.im.accessws`，显式装配需要的平台组件。
3. 补齐握手认证、Origin、帧校验、命令发布、ACK 状态机、事件幂等、跨节点路由和配置启动测试。
4. 执行网关局部与根 Reactor `clean verify`，完成 MQ 回放、重复投递、节点故障和客户端重连演练。

## 8. 前置依赖与风险

1. user、message、conversation、admin 等权威服务必须先提供网关所需的版本化 MQ 命令与事件；网关不以回读数据库作为过渡方案。
2. `MessageStoredEvent` 需要表达持久化确认与下行消息所需的稳定字段；群成员受众、推送目标等不能再由网关查询业务表补齐。
3. ACK 语义改变涉及客户端协议兼容，必须采用双事件或新增状态字段的渐进迁移，禁止直接改变既有 `chat:ack` 含义。
4. Redis Pub/Sub 不能提供离线可靠投递，可靠消息由业务服务 MySQL 权威库、Outbox 和离线投影保证。

## 9. 完成定义

完成标准不是删除目录，而是：网关不拥有业务持久化与业务规则；只经平台协议/MQ 与业务服务协作；客户端确认语义与持久化事实一致；下行消费可幂等；认证、配置、Origin、日志和节点状态满足安全与故障恢复要求；构建、架构门禁、MQ 回放和多节点演练全部通过。
