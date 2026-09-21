# im-conversation 与 im-message 规范化联合重构方案

## 1. 文档定位

本文是 `im-conversation` 与 `im-message` 服务的项目专用联合治理方案，用于完成服务边界、数据所有权、DDD 分层、跨服务协作和工程门禁的规范化。本文不修改下列通用规范的定义：

1. [业务微服务 DDD 工程规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)。
2. [Maven 工程规范](../standards/20_MAVEN_ENGINEERING_CONVENTIONS.md)。
3. [持久层技术栈标准](../standards/21_PERSISTENCE_STACK.md)。
4. [RocketMQ 治理规范](../standards/12_ROCKETMQ_CONVENTIONS.md)。

本方案只定义改造顺序、目标边界、兼容要求、迁移策略、验收标准和回滚原则。未经评审确认，不执行本方案中的业务代码、数据库、MQ 契约或运行配置修改。

## 2. 背景、目标与非目标

### 2.1 背景

`im-message` 的消息写模型、已读状态、Outbox、HTTP 入站层和核心分层已基本完成规范化，但仍保存好友、群组、群成员等跨域历史 ORM 副本，并由历史适配器依赖全局根包服务。

`im-conversation` 仍处于历史结构：业务 DTO 位于 `com.gvchat.common.dto`，领域服务、MyBatis 实体和 Mapper 风格 Repository 位于 `com.gvchat.im.domain`，Controller 直接调用领域服务并返回领域对象。启动扫描已收敛到本域根包，但历史业务 Bean 尚未迁入该根包，存在无法完成 Spring 装配的风险。

两个服务需要作为一项联合治理处理：群组、群成员、角色、禁言和群状态是 conversation 域事实；message 发送前需要这些事实完成即时授权校验，但不应再复制或直连它们。

### 2.2 总体目标

1. 保持 `im-conversation-api`、`im-conversation-service`、`im-message-api`、`im-message-service` 的局部 Reactor 结构，不引入新的业务服务。
2. 两个 Service 均收敛为 `api -> application -> domain <- infra`；领域层不依赖 Spring、MyBatis-Plus、Redis、MongoDB、RocketMQ、HTTP/RPC 或 Jackson。
3. conversation 成为群组、成员、角色、禁言、群状态与 RTC 会话规则的唯一权威服务。
4. message 只拥有消息、已读状态、消息 Outbox 与消息投影事实；不保留好友、群组、群成员的 PO、Mapper、Repository、SQL 或事务写入路径。
5. MySQL 始终是唯一权威库；MongoDB 仅保存热消息和离线收件箱投影；Redis 仅保存热点、序号、去重和可重建的授权投影。
6. 保持 HTTP 路径、认证语义、请求字段、响应字段、错误码、RocketMQ Topic、Payload 版本、`conversationId` 顺序键、`commandId` 幂等键和 Outbox 语义兼容。
7. 建立自动化架构、依赖、Flyway、契约和投影门禁，使历史根包和跨域表映射不能重新出现。

### 2.3 非目标

1. 不在本轮拆分新的微服务，不将群组能力拆出 conversation。
2. 不重定义客户端 WebSocket 协议、既有消息命令 Topic、消费组或消息顺序策略。
3. 不将 MongoDB、Redis 或接入网关升级为权威业务库。
4. 不以扩大 `@ComponentScan` 至 `com.gvchat.im` 作为兼容方案。
5. 不允许以 Service Maven 依赖、共享 Mapper、共享业务实体或跨库事务取代稳定契约。

## 3. 已确认架构决策

### 3.1 限界上下文与数据所有权

| 事实或能力 | 唯一所有者 | 权威存储 | message 的目标使用方式 | conversation 的目标使用方式 |
| --- | --- | --- | --- | --- |
| 消息、撤回、删除、已读、消息序列 | message | MySQL `msg_` 表 | 本域写入、查询、Outbox | 通过 API 或事件消费，不直连消息表 |
| 热消息、离线收件箱 | message | MongoDB 投影 | 本域投影和查询加速 | 不作为权威输入 |
| 群组、群成员、角色、禁言、解散、群规则 | conversation | MySQL conversation 表 | 同步受保护查询或授权投影 | 本域写入、查询、Outbox |
| RTC 房间与 ICE 配置规则 | conversation | conversation 权威配置或受控配置投影 | 不直接访问 | 本域处理 |
| 用户、账户状态、好友关系 | user | MySQL user 表 | 通过 user 契约或授权投影 | 通过 user 契约或授权投影 |
| 运营配置 | admin | MySQL `adm_` 表 | 配置事件投影或受保护查询 | 配置事件投影或受保护查询 |
| 在线连接 | access-ws 或专用在线投影 | Redis 热点状态 | 通过事件或内部查询读取 | 通过事件或内部查询读取 |

1. `conversation_group`、`conversation_group_member` 及其后续演进表只能由 conversation 的 Flyway 维护，任何其他服务不得新增 Mapper、PO、Repository 或 SQL。
2. `msg_message`、`msg_read_status`、`msg_outbox` 及其后续演进表只能由 message 的 Flyway 维护，conversation 不得直连。
3. `system_configs` 不属于 conversation。conversation 现有系统配置实体、Mapper 和写路径必须删除；需要配置时使用 admin 的配置事实、事件投影或受服务身份保护的查询。
4. message 内的好友关系和好友申请历史副本不属于 message，必须由 user 的受控查询契约或可重建投影取代。

### 3.2 分层与对象边界

固定依赖方向如下：

```text
api -> application -> domain <- infra
```

| 对象 | 所属位置 | 允许用途 | 禁止事项 |
| --- | --- | --- | --- |
| HTTP Request / Response | `api.dto` | 接入协议与响应协议 | 传入应用、领域或持久层 |
| Command / Query / Result | `application` | 用例输入、查询条件、用例输出 | 映射数据库或替代领域模型 |
| 聚合、实体、值对象、领域事件、Repository 契约 | `domain` | 业务规则与不变量 | Spring、MyBatis、缓存、MQ、HTTP 注解或实现 |
| PO、Mapper、Repository Adapter | `infra.persistence` | 关系型持久化实现 | 向 API、Application、Domain 泄露 |
| 远程调用、MQ 消费发布、Redis、Mongo | `infra.integration`、`infra.messaging`、`infra.cache`、`infra.projection` | 技术适配 | 反向承载领域规则 |

`common` 仅允许保留当前服务内部的异常、无业务语义工具和局部技术配置。不得把业务 DTO、实体、Repository、Service、跨域模型放入 `common`，不得再使用服务源码根包 `com.gvchat.common` 或 `com.gvchat.im.domain` 承载业务实现。

### 3.3 跨服务协作模型

消息写入前的群成员资格、角色、禁言、群状态属于即时授权决策，默认采用受服务身份保护的同步内部 Query API；事件投影仅用于降低调用频率和提供降级读取，不能在授权需要即时拒绝时覆盖权威查询。

```text
客户端 / 接入网关
        |
        v
im-message-service
        |
        | 受服务身份保护的成员资格查询
        v
im-conversation-service
        |
        | 返回最小授权快照
        v
im-message-service
        |
        | 本地事务：消息 + 已读状态 + Outbox
        v
MySQL msg_ 表
        |
        v
Outbox Relay -> RocketMQ -> Mongo / Redis / 下游投影
```

同步内部调用必须具备：服务身份认证、最小权限、超时、错误码、关联 ID、调用审计、明确失败语义和受控降级。不得使用无认证 HTTP 调用，不得将远程 DTO、HTTP 客户端或异常对象带入领域层。

异步事件用于群成员、角色、禁言和群状态的授权投影刷新，必须具备：`eventId` 幂等、`conversationId` 顺序键、事件版本、最小字段集、可重放、死信观测和对账能力。

### 3.4 一致性与事务边界

1. conversation 只在本地事务中保存自身聚合与 conversation Outbox。
2. message 只在本地事务中保存消息聚合、已读状态与 message Outbox。
3. 禁止跨服务数据库事务、跨库直接更新和业务事务内直接发送 MQ。
4. 消息命令继续以 `conversationId` 作为顺序键，以 `commandId` 作为命令幂等键。
5. 领域事件与投影事件继续以 `eventId` 作为消费幂等键；事件处理必须能够安全重放。
6. 同步授权查询失败时，默认拒绝产生群消息；仅在已冻结的授权投影仍有效、调用方具备明确降级授权且审计可追踪时，才允许受控降级。私聊与群聊的失败语义分别定义，不能以全局放行处理。

## 4. 当前基线与偏差

### 4.1 conversation 服务

| 编号 | 优先级 | 当前偏差 | 风险 | 目标处理 |
| --- | --- | --- | --- | --- |
| C-01 | P0 | 启动扫描已限定 `com.gvchat.im.conversation`，但 `GroupService`、RTC、配置等核心 Bean 仍位于 `com.gvchat.im.domain` | Controller 依赖无法装配，服务无法可靠启动 | 同一批次迁移到本域包；不扩大扫描范围 |
| C-02 | P0 | `Group`、`GroupMember`、`SystemConfig` 为 MyBatis-Plus 实体，Repository 直接继承 `BaseMapper`，服务使用 MyBatis Wrapper | 领域规则、持久化和框架耦合，无法测试与演进 | 拆出纯领域模型、Repository 契约、PO、Mapper、Adapter、Converter |
| C-03 | P0 | Flyway 被关闭，POM 缺 Flyway Starter 与 MySQL 适配器，未见权威迁移目录 | 无法空库重建、版本升级和追溯表归属 | 建立 conversation 专属 Flyway 基线与增量迁移 |
| C-04 | P0 | 映射历史 `system_configs` | 与 admin 的配置事实所有权冲突 | 删除实体、Mapper、服务与写路径，改为 admin 协作 |
| C-05 | P1 | Controller 直接使用全局 DTO、领域服务与领域实体 | HTTP 协议泄露领域与持久化模型 | 建立 API DTO、Converter、Command/Result 和 Application Service |
| C-06 | P1 | 内部管理查询直接注入 Mapper 风格 Repository | admin 与 conversation 内部实现耦合 | 将稳定响应 DTO 放入 `im-conversation-api`，由 Application Query 提供 |
| C-07 | P2 | 缺少最小架构、启动、Flyway、持久化测试基线 | 后续迁移容易回归 | 建立自动化门禁与本域集成测试 |

### 4.2 message 服务

| 编号 | 优先级 | 当前偏差 | 风险 | 目标处理 |
| --- | --- | --- | --- | --- |
| M-01 | P0 | `LegacyGroupMembershipAdapter`、`LegacyFriendRelationAdapter` 依赖历史全局根包服务 | 本域扫描后 Bean 依赖不可满足，跨域实现不稳定 | 改为对方版本化内部 Query API 或授权投影适配器 |
| M-02 | P1 | `Friend`、`FriendRequest`、`Group`、`GroupMember` 及 Mapper 风格 Repository 位于消息服务源码 | message 跨域读取、持久化模型复制、表所有权模糊 | 先替换调用，再删除全部历史副本 |
| M-03 | P1 | `com.gvchat.common.dto` 保留历史好友、群组 DTO | 业务模型归属不清，可能继续被错误复用 | 删除随遗留副本消失的 DTO；消息私有 DTO 迁至 `message.api.dto` |
| M-04 | P1 | Mongo 投影目录仍为 `message.projection` | 基础设施职责未完全归位 | 迁至 `message.infra.projection`，保持事件与索引语义不变 |
| M-05 | P1 | 架构测试未禁止历史根包和跨域表映射 | 已知遗留可以回流 | 在删除遗留后升级为强制失败门禁 |

### 4.3 可保留基线

1. message 已完成消息、已读状态、Outbox 的核心纯领域模型、持久化 PO/Mapper/Adapter 与应用编排，应作为 conversation 的落地参考，不重复重构已稳定逻辑。
2. `im-message-api`、`im-conversation-api` 已存在 admin 所需的稳定响应模型或潜在跨服务契约，应按消费者需求演进，不将 Service 内部 DTO 或领域对象直接下沉。
3. `conversationId` 顺序键、`commandId` 幂等和 Outbox 投递语义是现有消息链路兼容基线，重构不得改变。

## 5. 目标模块结构

### 5.1 conversation

```text
com.gvchat.im.conversation
├── ImConversationServiceApplication.java
├── api
│   ├── controller
│   ├── dto
│   │   ├── request
│   │   └── response
│   └── converter
├── application
│   ├── group
│   ├── rtc
│   ├── configuration
│   ├── command
│   ├── query
│   └── result
├── domain
│   ├── group
│   │   ├── model
│   │   ├── repository
│   │   ├── policy
│   │   └── event
│   ├── rtc
│   ├── configuration
│   └── port
├── infra
│   ├── persistence
│   │   ├── group
│   │   │   ├── po
│   │   │   ├── mapper
│   │   │   ├── converter
│   │   │   └── repository
│   │   └── outbox
│   ├── integration
│   │   ├── user
│   │   └── admin
│   ├── messaging
│   │   ├── outbox
│   │   └── projection
│   ├── cache
│   └── config
└── common
    └── exception
```

### 5.2 message

```text
com.gvchat.im.message
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
│   └── service
├── domain
│   └── message
│       ├── model
│       ├── event
│       ├── repository
│       └── port
├── infra
│   ├── persistence
│   │   └── message
│   ├── integration
│   │   ├── conversation
│   │   └── user
│   ├── messaging
│   │   └── outbox
│   ├── projection
│   ├── cache
│   └── config
└── common
    └── exception
```

所有 `@MapperScan` 仅扫描各自 `infra.persistence` 根路径。Spring 启动扫描固定为服务自身根包；共享平台安全、Redis、MQ 等能力通过平台自动配置或显式配置导入，不通过扫描其他服务源码获取。

## 6. 跨服务契约设计

### 6.1 message 到 conversation 的成员资格查询

在 `im-conversation-api` 定义版本化、稳定、最小化的内部查询契约。具体 HTTP 路径与鉴权头格式在批次 0 冻结，但语义必须满足以下模型：

```text
GroupMessageAuthorizationQuery
- conversationId
- userId
- commandId
- requestedAt

GroupMessageAuthorizationSnapshot
- conversationId
- userId
- groupStatus
- memberStatus
- memberRole
- mutedUntil
- authorizationVersion
- allowed
- denialCode
```

规则如下：

1. `allowed` 由 conversation 权威规则计算，message 不复制群状态、成员状态、角色或禁言规则。
2. `denialCode` 是稳定业务错误码，不泄露数据库、异常堆栈或内部实现信息。
3. `authorizationVersion` 用于投影对账、日志关联和后续缓存失效；它不是绕过同步校验的依据。
4. 请求必须携带 `commandId` 与关联 ID，服务端记录调用审计；查询本身不修改群状态。
5. 未找到群、用户非成员、群已解散、成员被禁言、成员被移除等情况均返回明确拒绝结果，不使用模糊的空响应。

该契约只允许 message、admin 等确有消费场景的服务使用。Controller、领域对象、PO、Mapper、Spring Bean 或 Service 制品不得成为契约内容。

### 6.2 message 到 user 的好友关系查询

在 `im-user-api` 定义私聊授权所需的最小查询契约。message 的适配器只消费是否允许发送、关系状态、关系版本及稳定拒绝码；不得引入 `Friend`、`FriendRequest`、用户表 PO 或 Mapper。

### 6.3 conversation 事件到 message 授权投影

conversation 在群成员、角色、禁言、群状态变化时，通过本地事务保存 Outbox，再发布版本化事件。事件最小模型如下：

```text
ConversationAuthorizationChangedEvent
- eventId
- eventVersion
- occurredAt
- conversationId
- userId
- changeType
- groupStatus
- memberStatus
- memberRole
- mutedUntil
- authorizationVersion
```

约束如下：

1. 按 `conversationId` 作为 RocketMQ 顺序键。
2. message 消费时按 `eventId` 幂等，按 `authorizationVersion` 防止旧事件覆盖新投影。
3. Redis 投影键、TTL、重放和对账方式在批次 0 写入契约登记表；投影丢失时通过同步查询回填，不允许访问 conversation 数据库。
4. 事件不得携带消息正文、用户敏感信息、Token、数据库字段快照或 PO。

### 6.4 admin 协作

1. admin 的群治理通过 conversation 同步管理命令完成，不更新群组或成员表。
2. admin 的消息审核、撤回和处置通过 message 同步管理命令完成，不更新消息表。
3. 仪表盘、趋势和列表查询可以消费两个领域的版本化事件建立管理读投影。
4. 每个内部 API 均要求服务身份、最小权限、超时、幂等、审计关联 ID 和稳定错误码。

## 7. 分批执行计划

每个批次必须独立可编译、可测试、可回退。未经上一批次验收通过，不进入下一批次。

### 批次 0：边界冻结与可启动基线

**目标**：建立现状证据、接口兼容基线与自动化保护，先消除启动扫描与历史依赖的不确定性。

**工作项**：

1. 生成 conversation 的 HTTP 兼容矩阵，覆盖 `/groups/**`、RTC、内部管理查询的路径、方法、鉴权、输入字段、响应字段、错误码和状态码。
2. 生成 message 的 HTTP 与 MQ 兼容矩阵，覆盖消息命令、查询、撤回、删除、已读、历史与 Outbox Topic。
3. 为 conversation、message 增加 Spring 上下文启动测试，固定服务根包扫描边界；不得通过扩大扫描路径让历史全局 Bean 重新生效。
4. 为现有跨域表、Mapper、实体、Repository、SQL 和调用点建立只读遗留清单，冻结新增路径。
5. 冻结 `GroupMessageAuthorizationQuery`、响应 DTO、服务身份、超时、错误码、审计字段和降级策略。
6. 冻结 conversation 授权变化事件的 Topic、消费者组、Payload、`conversationId` 顺序键和版本策略。
7. 增加报告模式架构测试：发现 `com.gvchat.common.dto`、`com.gvchat.im.domain`、跨域 Mapper 和跨服务 Service 依赖时输出明确清单，暂不以历史存量阻断构建。

**不做事项**：不删除历史代码，不改表结构，不更改既有 HTTP/MQ 对外语义。

**验收**：

- [ ] 两服务在各自根包扫描下均可完成上下文加载。
- [ ] HTTP、MQ、跨域遗留和 API 消费方兼容清单已评审。
- [ ] 历史跨域依赖的调用点、表名、所属服务和替换目标均可追溯。
- [ ] 架构扫描可稳定发现新增违规，但不误报平台共享技术模块。

### 批次 1：conversation 权威模型、Flyway 与纯领域迁移

**目标**：让 conversation 独立拥有群组和群成员事实，具备可重建数据库与纯领域模型。

**工作项**：

1. 在 `im-conversation-service` POM 中按持久层规范声明 MyBatis-Plus Boot 4 Starter、MySQL 运行时依赖、Flyway Starter 与 `flyway-mysql`；不得声明 MyBatis 底层组件。
2. 建立 `src/main/resources/db/migration`，新增 conversation 基线迁移与后续增量迁移。已执行脚本只增不改，所有表、字段、索引使用中文注释和稳定命名。
3. 将群组、成员、角色、禁言、群状态规则建模为纯领域聚合、实体、值对象、策略和领域事件。聚合根承担成员增删、角色变更、禁言、解散等不变量。
4. 将现有 MyBatis 实体拆为 `infra.persistence.group.po`，Mapper 迁入 `infra.persistence.group.mapper`，PO/领域对象转换收敛到 `infra.persistence.group.converter`。
5. 在 `domain.group.repository` 定义聚合仓储契约，在 `infra.persistence.group.repository` 实现 Adapter；禁止暴露 `BaseMapper`、Wrapper、PO 或分页插件对象。
6. 建立 `application.group` 用例服务，承接事务、幂等、授权结果使用、聚合保存与 Outbox 记录。
7. 将 `GroupController`、RTC Controller、内部管理查询迁入 `api`，使用 Request/Response、Converter、Command/Query/Result，不返回领域对象或 PO。
8. 将历史 `SystemConfig`、`AppConfigService`、相关 Mapper/Repository 从 conversation 删除，替换为 admin 配置查询 Port 或配置投影 Adapter。
9. 将 `ConversationPersistenceConfig` 收敛为本域 Mapper 扫描和分页插件配置；Flyway 在正式配置启用。

**兼容要求**：

1. 群组接口路径、字段、错误码和鉴权语义保持兼容。
2. 现有群组、群成员数据须在迁移前完成备份、校验和回填演练。
3. 如果历史表需要改名或引入 conversation 前缀，采取“新增目标表 -> 可验证回填 -> 单写切换 -> 保留只读回退窗口 -> 下一个发布周期删除旧表”的方式；禁止双向无审计写入。
4. conversation 本地事务中仅保存群组事实和 Outbox，不直接调用 MQ。

**验收**：

- [ ] 空库可通过 Flyway 初始化 conversation 权威表。
- [ ] 历史库升级可完成，记录数、关键成员关系、角色与状态校验通过。
- [ ] 领域层无 Spring、MyBatis、Redis、Mongo、MQ、HTTP/RPC 依赖。
- [ ] PO、Mapper、Repository Adapter 仅存在于 `infra.persistence`。
- [ ] 领域、应用、持久化、HTTP、Flyway 与 Outbox 测试通过。
- [ ] `system_configs` 不再由 conversation 映射或写入。

### 批次 2：conversation 内部授权契约与授权事件

**目标**：提供 message 删除历史群组副本所需的可靠协作能力。

**工作项**：

1. 在 `im-conversation-api` 定义成员资格查询 Request/Response 与稳定错误码；API 模块只包含契约，不引入 Spring Boot、MyBatis、Flyway 或 Service 实现。
2. 由 conversation API Controller 调用 Application Query，基于领域规则返回最小授权快照。
3. 完成服务身份认证、调用方最小权限、超时、关联 ID、审计和失败映射。
4. 为群创建、成员加入/移除、角色变更、禁言/解禁、群解散等状态变化建立领域事件和 Outbox 记录。
5. 定义并发布版本化授权变化事件；提供 message 消费方的契约测试样例与事件重放说明。
6. 建立授权投影对账能力：按群和成员抽样比对 MySQL 权威状态、Outbox、MQ 消费位点和 Redis 投影版本。

**验收**：

- [ ] message 可仅通过内部 Query API 获取群消息授权结论。
- [ ] 内部 API 无用户凭据或服务身份时拒绝访问。
- [ ] 授权变化事件按 `conversationId` 有序并可按 `eventId` 去重。
- [ ] 事件重复、乱序、重放和消费者失败重试均有测试证据。
- [ ] 不存在将 conversation PO、领域对象、Mapper 或异常直接暴露给 message 的路径。

### 批次 3：message 替换跨域历史副本

**目标**：使用稳定契约替代好友、群组和群成员的本地副本，消除跨域表级依赖。

**工作项**：

1. 将 `LegacyGroupMembershipAdapter` 重写为 `infra.integration.conversation` 适配器，调用 conversation 内部授权 Query；应用层依赖领域 Port，不依赖远程客户端。
2. 消费 conversation 授权变化事件，建立 Redis 授权投影；投影只用于加速和受控降级，缺失或版本不确定时回退至同步查询。
3. 将 `LegacyFriendRelationAdapter` 重写为 `infra.integration.user` 适配器，调用 user 的稳定好友关系 Query 或权威授权投影。
4. 删除 message 历史 `Friend`、`FriendRequest`、`Group`、`GroupMember`、相关 Mapper 风格 Repository、Service、PO、SQL 与无效配置。
5. 删除随历史副本消失的 `com.gvchat.common.dto`；消息本域 HTTP DTO 必须迁入 `message.api.dto`。
6. 将 Mongo 热消息、离线收件箱和投影服务从 `message.projection` 迁至 `message.infra.projection`，保持集合名、索引、事件消费顺序与查询行为兼容。
7. 保持消息写库与 Outbox 同事务，不改变 `msg_` 表所有权、命令幂等和 `conversationId` 顺序键。

**兼容要求**：

1. 群消息在成员被移除、禁言、群解散后必须被权威规则拒绝。
2. 同步 conversation 查询不可用时不得无条件放行群消息；受控降级只允许使用未过期、版本可验证的授权投影并记录审计日志与指标。
3. 私聊好友校验沿用 user 权威规则，message 不缓存或复制可写好友关系。
4. 消息 HTTP/MQ 的路径、字段、错误码、顺序、幂等与投影语义保持兼容。

**验收**：

- [ ] message 源码、POM 和依赖树中不存在好友/群组/成员表的 Mapper、PO、Repository、SQL 或 Service 依赖。
- [ ] 群消息授权的允许、拒绝、超时、投影命中、投影失效和事件重放均有自动化测试。
- [ ] 消息核心领域和应用层不导入 conversation/user 的 HTTP DTO、客户端或基础设施实现。
- [ ] Mongo 与 Redis 均可删除后由 MySQL、Outbox 与事件重新构建。

### 批次 4：接口收敛、遗留删除与强制门禁

**目标**：完成目录、依赖和构建治理，防止规范倒退。

**工作项**：

1. 删除两个服务中的全局历史根包 `com.gvchat.common.dto`、`com.gvchat.im.domain` 及其空目录。
2. 完成 Controller、DTO、Application、Domain、Infra 包归位；保留服务私有 `common.exception` 时必须位于服务根包内。
3. 将 message 与 conversation 投影、缓存、MQ、远程调用配置统一收敛到 `infra`。
4. 将报告模式架构测试升级为强制失败：禁止全局历史根包、跨域表映射、跨服务 Service 依赖、领域框架依赖、PO/Mapper 越层、Controller 直连基础设施。
5. 根 POM 从 `dependencyManagement` 移除业务 `*-service` 制品管理项，只保留平台模块与版本化 `*-api` 契约，降低错误服务依赖的可能性。
6. 将持久层依赖、文本 UTF-8、日志规范、弃用 API、Flyway 和架构门禁接入统一验证入口。

**验收**：

- [ ] 两个服务的启动扫描均仅覆盖自身服务根包。
- [ ] `im-message-service` 与 `im-conversation-service` 不依赖任何其他业务 `*-service` 制品。
- [ ] `domain`、`application`、`api`、`infra` 的导入方向与对象隔离测试均为强制失败门禁。
- [ ] Flyway、依赖边界、编码、日志、架构测试和局部 Reactor 构建全部通过。

### 批次 5：发布、观测与回滚演练

**目标**：以可观测、可回退方式上线，验证跨服务协作的真实运行行为。

**工作项**：

1. 执行 conversation 与 message 的空库初始化、历史库升级、数据回填校验和恢复演练。
2. 执行消息发送、群成员变更、禁言、解散、撤回、已读、离线投影、授权事件重放的端到端回归。
3. 监控内部授权 Query 成功率、超时率、错误码分布、降级次数、授权投影滞后、Outbox 积压、MQ 重试、死信和投影对账差异。
4. 执行接口消费者回归：接入网关、admin、消息消费者和会话消费者均通过契约验证。
5. 完成发布后数据对账与一段稳定观察窗口；仅在投影、权限和消息一致性指标稳定后删除兼容 Adapter。

**验收**：

- [ ] 局部 `clean verify`、受影响消费者构建与根 Reactor 验证均通过。
- [ ] 事件重复、乱序、重放、死信恢复、Redis/Mongo 重建与投影对账通过。
- [ ] 群消息权限变更在定义的同步或投影一致性窗口内生效，未出现无条件放行。
- [ ] 回滚演练可回退调用 Adapter、读路由或部署版本，不恢复跨域直连，也不回滚已验证的 Flyway 迁移。

## 8. 文件级迁移清单

### 8.1 conversation 历史文件处理

| 当前区域 | 处理 | 目标区域 |
| --- | --- | --- |
| `com.gvchat.common.dto` 下群组 DTO | 迁移并拆分为 HTTP Request/Response | `com.gvchat.im.conversation.api.dto` |
| `com.gvchat.im.domain.entity.Group`、`GroupMember` | 拆为纯领域模型与持久化 PO | `domain.group.model`、`infra.persistence.group.po` |
| `com.gvchat.im.domain.repository.GroupRepository` 等 | 拆为领域契约与基础设施 Adapter | `domain.group.repository`、`infra.persistence.group.repository` |
| `com.gvchat.im.domain.service.GroupService` | 拆为聚合行为、领域策略和应用用例 | `domain.group`、`application.group` |
| `com.gvchat.im.domain.service.RtcService`、`RtcIceService` | 迁移并按职责拆分 | `domain.rtc`、`application.rtc`、`infra.integration` |
| `SystemConfig`、`AppConfigService` 及关联 Mapper | 删除；改为 admin 协作 | `infra.integration.admin` 或 `infra.cache` |
| `GroupController`、`RtcController` | 改为 DTO + Application Service 入口 | `api.controller` |
| `InternalAdminConversationQueryController` | 改为版本化内部契约入口 | `api.controller.internal` 与 `im-conversation-api` |
| `ConversationPersistenceConfig` | 调整 MapperScan 与分页配置 | `infra.config` |

### 8.2 message 历史文件处理

| 当前区域 | 处理 | 目标区域 |
| --- | --- | --- |
| `com.gvchat.im.domain.entity.Friend`、`FriendRequest` | 删除 | user 契约或 user 授权投影 |
| `com.gvchat.im.domain.entity.Group`、`GroupMember` | 删除 | conversation 授权 Query 或投影 |
| 对应 `BaseMapper` 风格 Repository | 删除 | `infra.integration.user`、`infra.integration.conversation` |
| `LegacyFriendRelationAdapter` | 替换 | user API Client Adapter |
| `LegacyGroupMembershipAdapter` | 替换 | conversation API Client Adapter |
| `com.gvchat.common.dto` 历史好友/群组 DTO | 删除或迁移 | 删除；仅消息 HTTP DTO 留在 `api.dto` |
| `com.gvchat.im.message.projection` | 移动 | `com.gvchat.im.message.infra.projection` |
| `MessageLayerArchitectureTest` | 扩展并升级强制门禁 | `src/test/java/com/gvchat/im/message` |

实际文件移动必须在实施批次开始前以当前 Git 工作区为准复核；文档清单用于定义职责与迁移方向，不应作为盲目批量重命名脚本的输入。

## 9. 数据迁移、切换与回滚原则

### 9.1 Flyway 与数据迁移

1. conversation 先建立可执行基线，再用增量脚本演进；不得修改已经在环境执行的 Flyway 脚本。
2. 迁移前必须登记源表、目标表、记录数、校验维度、回填脚本、执行人、切换窗口与回滚负责人。
3. 表名变更或数据归并必须遵循：新增目标结构、幂等回填、读校验、单写切换、保留只读回退窗口、最终删除旧路径。
4. 禁止双向写入；若短期必须双读，只允许在 Adapter 层执行并明确优先级、观测指标和删除期限。
5. MySQL 数据是唯一权威事实。Mongo、Redis 的重建只能从权威数据与可重放事件恢复。

### 9.2 契约切换

1. 先发布 conversation 内部 Query API 与授权事件消费者兼容版本，再切换 message Adapter。
2. 切换期间保留旧 Adapter 的可观测只读路径或快速回退开关，但禁止继续通过旧 Adapter 写跨域表。
3. 通过成功率、延迟、拒绝码、投影版本、消息拒绝数和 Outbox 事件滞后判断是否完成切换。
4. 旧跨域实体和 Mapper 只能在新契约、投影重建、端到端回归和观察窗口完成后删除。

### 9.3 回滚边界

| 风险 | 控制措施 | 回滚方式 |
| --- | --- | --- |
| conversation 新模型与历史数据不一致 | 回填前后校验、灰度读、抽样对账 | 回退读 Adapter 或应用版本，不回滚已执行迁移 |
| 内部授权 Query 超时或不可用 | 超时、熔断、限流、可验证短 TTL 投影 | 回退至已验证投影读取；不恢复 message 直连群表 |
| 授权事件重复或乱序 | `eventId` 幂等、版本比较、顺序键、重放 | 删除并重建投影，必要时同步回填 |
| message 删除历史副本后权限回归 | 契约测试、端到端测试、分阶段删除 | 回退 Adapter 版本，不恢复跨域 Mapper |
| Flyway 升级失败 | 预演、备份、幂等回填、发布窗口 | 停止发布并回退应用；以新迁移修复，不修改已执行脚本 |

## 10. 自动化门禁与验证命令

### 10.1 必须新增或扩展的门禁

1. conversation 分层测试：检查 `api`、`application`、`domain`、`infra` 导入方向，领域框架依赖禁令，PO/Mapper 位置和 Controller 返回对象约束。
2. message 架构测试：禁止 `com.gvchat.im.domain`、`com.gvchat.common.dto` 历史根包、好友/群组跨域 Mapper、跨服务 Service 依赖和投影目录回退。
3. POM 依赖边界测试：业务数据库服务只使用 MyBatis-Plus Boot Starter，不直接声明底层 MyBatis 组件；持有 Flyway 脚本的服务具备 Flyway Starter 和 MySQL 适配器。
4. Flyway 测试：空库初始化、历史库升级、迁移表集合、关键索引与中文注释校验。
5. 契约测试：conversation 授权 Query、user 好友 Query、授权事件 Payload、错误码、服务身份与超时映射。
6. 投影测试：重复、乱序、重放、死信恢复、Redis/Mongo 删除后重建和权威数据对账。
7. 编码、日志、弃用 API 和编译诊断检查继续作为强制失败基础门禁。

### 10.2 每批次最低验证

```powershell
.\mvnw.cmd clean verify -pl im-services/conversation/im-conversation-service -am
.\mvnw.cmd clean verify -pl im-services/message/im-message-service -am
```

涉及 API 契约时，追加构建契约消费者；涉及根 POM、平台依赖或跨域事件时，执行受影响模块的 Reactor 构建与根 Reactor 编译。所有验证在 Windows 环境使用 UTF-8 无 BOM 文件，脚本改写必须显式指定编码。

## 11. 完成定义

本联合重构完成不以“目录已移动”判定，而以以下可验证事实判定：

1. conversation 是群组、成员、角色、禁言、群状态与 RTC 规则的唯一权威服务，具备 Flyway、纯领域模型和本地 Outbox。
2. message 是消息、已读状态、消息 Outbox 与消息投影的唯一所有者，不再保存好友、群组或群成员的跨域 ORM 副本。
3. message 对群消息和私聊授权仅通过受保护的 user/conversation 契约或可重建投影协作，不访问对方数据库、Mapper、Repository、领域对象或 Service 制品。
4. 两服务均满足 `api -> application -> domain <- infra`，领域层不依赖技术框架，Controller 不泄露领域对象或 PO。
5. HTTP、MQ、顺序键、幂等、Outbox、数据副作用与错误码兼容已由自动化测试和消费者验证覆盖。
6. Flyway 可完成空库初始化和历史库升级；Mongo、Redis 投影可从 MySQL 与事件重建。
7. 架构、依赖、编码、日志、Flyway、契约、投影与编译门禁均为强制失败并持续通过。
8. 发布、监控、对账与回滚演练完成，且回滚不恢复跨域数据库直连。

## 12. 实施前需评审确认的决策

以下事项须在执行批次 1 前明确确认并记录：

1. conversation 权威表的现状、目标命名、是否需要表迁移、历史数据规模、回填窗口与切换负责人。
2. 群消息授权 Query 的具体路径、服务身份认证方式、超时阈值、错误码、审计字段和受控降级边界。
3. conversation 授权变化事件的 Topic、消费者组、版本规则、Payload、保留期、顺序键、重放策略与死信处理人。
4. user 好友关系 Query 的现有契约是否足够；不足时由 user 先提供最小版本化内部 API，再删除 message 副本。
5. `system_configs` 到 admin 配置事实或投影的迁移方式、缓存刷新策略和配置失效行为。
6. admin 对群治理和消息治理所需的内部 Command/Query 契约范围，避免重构后再次创建跨域数据库访问。
7. 发布顺序：conversation 权威化和契约先行，message Adapter 切换随后，历史副本删除最后；不得颠倒。
