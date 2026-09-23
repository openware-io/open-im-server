# im-admin 域 DDD 重构方案

## 1. 文档定位

本文是 `im-admin` 域迁移至既定工程规范的项目专用方案，不修改 [10 业务微服务 DDD 工程规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)、[20 Maven 工程规范](../standards/20_MAVEN_ENGINEERING_CONVENTIONS.md)、[21 持久层技术栈标准](../standards/21_PERSISTENCE_STACK.md) 和 [12 RocketMQ 治理规范](../standards/12_ROCKETMQ_CONVENTIONS.md) 的定义。

`im-admin-service` 是 PC 管理后台的业务服务和管理端 BFF，不是接入网关，也不是所有业务表的共享数据库管理工具。它负责管理端认证、授权、操作审计、管理域事实、跨域管理编排和管理读投影。

## 2. 已确认架构决策

### 2.1 API 模块保持为空

`im-admin-api` 当前没有源码，也没有跨服务消费者，应保持为空制品。

1. PC 管理后台通过 HTTP 路由访问 `im-admin-service`，不以 Maven API JAR 调用。
2. 其他服务不应反向依赖管理后台的 Service、Controller、DTO 或领域对象。
3. 管理服务对用户、消息、会话等权威服务的协作，应使用对方版本化 API 契约或 `protocol-mq` 事件/命令契约。
4. 只有出现稳定的跨服务同步消费者，且消费者确实需要管理域专属 Java 契约时，才经架构评审向 `im-admin-api` 增加版本化 Request、Response、错误码和接口定义。

### 2.2 数据所有权与协作模型

```text
PC 管理后台
     |
     v
im-admin-service
     |\
     | \-- 管理域 MySQL 权威表与 Outbox
     |
     +---- 受服务身份保护的同步命令/查询 ---> user / message / conversation
     |
     +<--- 版本化领域事件 ------------------- RocketMQ / Outbox
                  |
                  v
           管理读投影与统计视图
```

1. 管理域自身写入使用本地 MySQL 事务、Flyway 和 Outbox。
2. 管理端对其他领域的高风险变更不得直接更新对方表；通过受服务身份保护的同步命令委派给权威服务，以获得即时授权、校验和执行结果。
3. 仪表盘、趋势、跨域列表、历史查询等允许最终一致的读场景，消费领域事件建立 `adm_` 管理读投影。
4. 单条详情或明确要求最新数据的查询，可以调用权威服务版本化内部 Query API；不得访问对方数据库、Mapper、Repository 或 Service 制品。
5. MQ 不替代全部同步操作。它用于可靠事件、可异步治理任务和读投影；管理员需要即时判定成功或失败的命令，不应被无反馈 MQ 链路替代。

## 3. 当前基线与偏差

### 3.1 可保留基线

| 项目 | 当前结论 | 处理 |
| --- | --- | --- |
| 服务域 Maven 结构 | `im-admin` 聚合 `im-admin-api` 和唯一可部署的 `im-admin-service` | 保留 |
| 管理端入口 | `/admin/**` 已具备 `ADMIN` 路径级授权 | 迁移时保持接口与鉴权兼容 |
| 积分委派样例 | 已通过 `UserPointsAdminClient` 调用 user 内部接口 | 作为跨域防腐层样例重构，但补服务身份认证、超时和审计 |
| 管理域职责 | 版本发布、运营配置、举报处置、小程序服务配置 | 作为管理域核心能力保留 |

### 3.2 已确认偏差

| 编号 | 优先级 | 偏差 | 改造方向 |
| --- | --- | --- | --- |
| A-01 | P0 | `domain.entity` 是 MyBatis-Plus 实体，`domain.repository` 是 `BaseMapper`，`domain.service` 直接使用 Spring、MyBatis 和 Redis | 建立纯领域模型、仓储/端口契约，PO、Mapper、Adapter 收敛至 `infra` |
| A-02 | P0 | admin 直接读写用户、好友、消息、群组、设备、贴纸等跨域表 | 按数据归属退出直连，改为权威服务命令/查询或管理读投影 |
| A-03 | P0 | 用户禁用直接更新用户表，绕过用户账户状态转换、审计与事件 | 定义用户域管理命令，权威用户域同步执行并发布状态变更事件 |
| A-04 | P0 | 管理域表没有 Flyway 迁移，配置中明确禁用 Flyway | 为 `adm_` 表建立 admin 唯一迁移所有权，先完成历史基线和数据迁移 |
| A-05 | P1 | Controller 直接接收全局 DTO、调用历史 Service 并返回 ORM 实体 | 建立管理端 Request/Response、Command/Query/Result、Converter 和 Application Service |
| A-06 | P1 | 后台端点与客户端配置、版本检查、MiniApp 查询、举报创建混在同一 Controller 区域 | 按 admin-console、client-public、moderation 三类入站 API 组织，保持既有路径兼容 |
| A-07 | P1 | 启动扫描为 `io.openware.im`，会装配历史同名领域组件 | 最后收敛为 `io.openware.im.admin`，显式导入平台安全、Redis、MQ 组件 |
| A-08 | P1 | `system_configs` 在 admin 和 conversation 等模块同时映射 | 迁移为 `adm_system_config`，admin 成为唯一写入者，通过事件同步消费者缓存/投影 |
| A-09 | P1 | 管理操作仅有粗粒度 `ADMIN`，缺少权限点和不可篡改审计 | 引入管理权限、操作审计聚合和关键命令的操作者、理由、关联键记录 |
| A-10 | P2 | 测试仅覆盖积分 HTTP 客户端 | 按领域、应用、HTTP、持久化、MQ 投影和安全边界补齐测试 |

## 4. 目标结构

```text
io.openware.im.admin
├── ImAdminServiceApplication.java
├── api
│   ├── console
│   │   ├── controller
│   │   ├── request
│   │   └── response
│   ├── client
│   │   ├── controller
│   │   ├── request
│   │   └── response
│   ├── moderation
│   │   ├── controller
│   │   ├── request
│   │   └── response
│   └── converter
├── application
│   ├── configuration
│   ├── release
│   ├── moderation
│   ├── miniapp
│   ├── administration
│   ├── projection
│   ├── command
│   ├── query
│   └── result
├── domain
│   ├── configuration
│   ├── release
│   ├── moderation
│   ├── miniapp
│   ├── audit
│   ├── repository
│   ├── event
│   └── port
├── infra
│   ├── persistence
│   │   ├── po
│   │   ├── mapper
│   │   ├── repository
│   │   └── converter
│   ├── integration
│   │   ├── user
│   │   ├── message
│   │   └── conversation
│   ├── messaging
│   │   ├── outbox
│   │   └── projection
│   ├── cache
│   ├── security
│   └── config
└── common
    └── exception
```

依赖方向固定为：`api -> application -> domain <- infra`。管理读投影属于 `infra.messaging.projection`，不能成为领域模型或跨域写入入口。

## 5. 数据归属矩阵

| 能力/现有数据 | 目标所有者 | admin 目标交互 | 迁移要求 |
| --- | --- | --- | --- |
| 版本发布 `app_releases` | admin | 本域写入、查询、Outbox | 迁至 `adm_app_release`，由 admin Flyway 管理 |
| 运营配置 `system_configs` | admin | 本域写入、客户端读取、配置变更事件 | 迁至 `adm_system_config`，禁止其他服务直接写入 |
| 举报、违规、敏感词 | admin | 本域审核、审计、Outbox | 迁至 `adm_report`、`adm_violation`、`adm_sensitive_word` |
| 小程序服务配置 | admin | 本域写入、客户端读取 | 迁至 `adm_miniapp_service_type`、`adm_miniapp_service_item` |
| 用户账户、状态、设备、贴纸、积分、好友 | user | 管理命令、内部查询、用户事件投影 | 删除 admin 的对应实体、Mapper、直接 SQL |
| 消息、已读、撤回、消息统计事实 | message | 管理命令、内部查询、消息事件投影 | 删除 admin 的消息实体、Mapper、直接 SQL |
| 群组、群成员、群规则、RTC | conversation | 管理命令、内部查询、会话事件投影 | 删除 admin 的群实体、Mapper、直接 SQL |
| 在线状态 | access-ws 或专用在线投影 | 只读查询/事件投影 | 删除 admin 私有 Redis 在线集合写入职责 |

表名从历史共享表迁为 `adm_` 前缀，是为了在同一个物理 MySQL 实例中仍清晰表达逻辑数据库所有权。迁移期间不得双向无审计写入；每张表必须登记权威写入者、迁移负责人、回填策略和切换日期。

## 6. 管理操作与一致性策略

| 场景 | 交互方式 | 原因 |
| --- | --- | --- |
| 禁用/启用用户、积分调整、用户贴纸操作 | 同步管理命令到 user | 需要即时校验、状态转换、审计和明确操作结果 |
| 消息删除/审核处置 | 同步管理命令到 message | 消息域保留权限、撤回和数据一致性规则 |
| 群治理、成员处置 | 同步管理命令到 conversation | 会话域保留成员与角色规则 |
| 用户、消息、群的单条详情 | 受服务身份保护的内部 Query API | 管理端需要最新数据时避免投影延迟 |
| 仪表盘、趋势、跨域筛选、历史统计 | MQ 事件驱动管理读投影 | 降低跨域同步查询与共享数据库耦合 |
| 配置变更、审核结论、版本发布 | admin 本地事务 + Outbox 事件 | 本域事实先可靠落库，再通知缓存和消费者 |

同步内部 API 必须使用服务身份认证、最小权限、超时、幂等键、审计关联 ID 和错误码契约。不得沿用无认证 `RestClient` 调用。异步事件必须使用 `eventId` 幂等、可重放投影、死信观测和对账任务。

## 7. 分批改造计划

### 批次 0：边界冻结与安全基线

1. 建立现有 `/admin/**`、`/config/**`、`/reports/**`、`/miniapp/**` 的 HTTP 兼容矩阵。
2. 为当前管理端角色、操作人、理由、请求关联 ID 和响应错误码建立行为基线。
3. 新增架构门禁：`domain` 禁止 Spring、MyBatis、Jackson、Redis、RocketMQ 和 HTTP 客户端；Controller 禁止返回领域对象/PO；API 模块禁止 Spring Boot、MyBatis、Flyway 和 Service 依赖。
4. 对所有 admin 直连跨域表生成只读清单，禁止新增跨域 Mapper 或 SQL。
5. 修复 JWT、Redis、数据库等弱默认配置；为内部调用冻结服务身份认证方案。

### 批次 1：管理域权威表与 Flyway

1. 先建立 admin Flyway 基线和 `adm_` 表迁移，不直接修改已执行的其他服务迁移。
2. 迁移版本发布、运营配置、举报、违规、敏感词和小程序配置为 admin 唯一权威数据。
3. 每个切片建立纯领域模型、Repository 契约、PO/Mapper/Adapter、Application Service、HTTP DTO 与转换器。
4. 对 `system_configs` 先完成数据归并、只写切换和配置变更事件，再删除其他服务的写路径。
5. 为管理命令建立操作审计记录与 Outbox，审计至少包含操作者、权限点、理由、目标、前后摘要、关联 ID、时间和结果。

### 批次 2：跨域高风险命令退出直连

1. 优先迁移用户状态变更，建立 user 域管理命令与同步执行结果；禁用后同步刷新认证状态、令牌版本或会话失效。
2. 将积分、贴纸、设备操作统一收敛为 user 域受控管理接口，删除 admin 对应实体、Repository 和历史 Service。
3. 将消息治理迁移为 message 域管理命令；将群治理迁移为 conversation 域管理命令。
4. 每个命令使用稳定幂等键和服务身份认证；admin 只保存管理操作与结果引用，不复制对方领域规则。

### 批次 3：管理读投影与查询迁移

1. 定义用户、消息、会话服务向 admin 发布的最小版本化事件，不传递密码、Token、设备推送密钥或不必要正文。
2. 在 admin 消费事件建立 `adm_user_view`、`adm_message_view`、`adm_conversation_view` 和统计投影；投影以 `eventId` 去重，支持重放和对账。
3. 将后台列表、仪表盘、趋势统计迁移到投影；单条强一致详情通过内部 Query API 获取。
4. 取消 `AdminStatsService` 对用户、好友、群、消息表的聚合查询，删除跨域 Mapper 和实体。

### 批次 4：接口整理、旧代码删除与扫描收敛

1. 保持既有 HTTP 路径、字段和错误码兼容，将 Controller 迁入 `api.console`、`api.client`、`api.moderation`。
2. 删除 `io.openware.common.dto`、`io.openware.im.domain` 历史包、跨域 PO/Mapper/Service、admin 私有在线状态写入和无调用 Push 配置。
3. 将启动扫描收敛至 `io.openware.im.admin`，显式导入平台安全、Redis、MQ 和必要的 HTTP 客户端配置。
4. 在所有投影和跨域命令验证通过后，将边界门禁升级为强制失败。

### 批次 5：验证、发布与回滚演练

1. 执行 `clean verify -pl :im-admin-service -am`、根 Reactor 校验、Flyway 空库初始化和历史数据升级验证。
2. 验证管理命令的权限、服务身份、幂等、超时、重试、审计、用户禁用即时失效和错误映射。
3. 验证事件重复、乱序、重放、投影重建、死信告警和统计对账。
4. 完成 PC 管理后台 HTTP 契约回归与关键运营流程回归。

## 8. 风险、回滚与前置决策

| 风险 | 控制措施 | 回滚原则 |
| --- | --- | --- |
| 管理端切换后读取历史共享表失败 | 先完成 `adm_` 表回填、读写验证和只写切换 | 回退 admin Adapter 或读路由，不回滚已验证的权威数据迁移 |
| 用户禁用异步化造成旧 Token 短暂可用 | 禁用使用同步 user 命令，结合令牌版本/黑名单与会话失效事件 | 回退调用适配层，不恢复 admin 直写用户表 |
| 投影延迟或重复导致统计偏差 | `eventId` 幂等、重放、对账、延迟指标与死信处理 | 重建投影或临时降级为受控同步查询 |
| 内部 API 成为无认证后门 | 服务身份、最小权限、网络策略、审计和契约测试 | 立即停用调用方身份或路由，不暴露业务表 |
| 迁移扩大为无边界服务拆分 | 先完成同一 admin 服务内分层与数据所有权 | 新服务拆分必须另立架构决策记录 |

实施前必须冻结以下决策：

1. `adm_system_config` 的配置分类、消费者、缓存刷新、灰度和回滚模型。
2. 管理端服务身份认证机制，以及 PC 用户身份与服务身份的审计关联方式。
3. 用户禁用后的 Token、长连接和设备会话即时失效策略。
4. 用户、消息、会话向管理读投影发布的事件最小字段、版本、顺序键和隐私分级。
5. 管理权限点模型，例如运营、审核、客服、只读审计和超级管理员的职责边界。

## 9. 完成定义

完成标准不是把 Controller 改包名，而是：admin 只拥有管理域权威事实和迁移；跨域业务只通过稳定命令、查询或事件投影协作；领域层无框架依赖；管理操作可授权、审计、幂等与追溯；后台接口不暴露 PO/领域对象；Flyway、Outbox、投影、架构门禁、构建和关键管理流程验证全部通过。
