# 工程规范

> 状态：生效
>
> 适用范围：`gv_im_server` 工作区、构建脚本与本地开发环境
>
> 最后核查：2026-07-31
>
> Maven 版本模型与依赖门禁以 [20 Maven 工程规范](./standards/20_MAVEN_ENGINEERING_CONVENTIONS.md) 和根 `pom.xml` 为准。

## 模块职责

## 工程结构与模块版本

- 根工程只承担统一版本、依赖管理、构建插件和顶层聚合职责，不承载业务实现。
- `sdk/` 只放不可运行的共享依赖包：`common`、协议契约和技术基础设施。共享技术可以复用，共享业务实现一律禁止。
- `im-services/<domain>/`、`platform-services/<domain>/`、`common-services/<domain>/` 分别是 IM 业务、平台（SaaS）业务、通用支撑三类域的父级目录。可运行服务必须放在该目录的 `*-service` 子模块中；对外 Java 契约必须放在同级 `*-api` 子模块中。
- `*-api` 仅可公开稳定的请求/响应 DTO、内部客户端接口、领域事件、错误码和契约版本信息；禁止包含 JPA/MyBatis 实体、Mapper、Repository、业务 Service、Spring Boot 启动类或数据库迁移。
- `*-service` 是唯一可运行与部署的模块，拥有本服务的领域实现、持久化、迁移脚本、配置和启动类。其他服务不得将其作为 Java 依赖。
- `gateway` 是独立可运行的接入服务，放在 `gateways/gateway`；它不需要空的 API 模块，也不得依赖业务服务实现。
- 新服务必须以 `im-services/<domain>/<service>-api` 与 `im-services/<domain>/<service>-service`（或 `platform-services/`、`common-services/` 下同名结构）创建；仅在确实没有 Java 消费方时，API 模块可以暂不承载源码，但父 POM 和目录必须预留。
- 每个业务域使用服务级 Maven 父 POM 聚合 API 与 Service。API 和 Service 都必须继承该父 POM，服务级父 POM再继承根 POM。
- 根 `im-server` 的版本仅表达全仓构建基线（仅 `MAJOR` 变化）；平台库、各服务域、网关为独立版本单元，各自维护版本；跨端（后端/管理端/客户端）不再强制一致。具体所有权模型见 [20 Maven 工程规范](./standards/20_MAVEN_ENGINEERING_CONVENTIONS.md) 与 [Maven 版本治理实施记录](./renovation/BUILD_GOVERNANCE_01_MAVEN.md)。
- 服务域 API 与 Service 必须继承所属服务域父 POM 的版本，叶子模块不得自行声明版本；网关独立部署制品显式维护自身版本。
- 版本所有权由 `scripts/validate/validate-maven-version-ownership.ps1` 校验，并通过统一工程校验入口执行。

当前目录模板：

```text
sdk/
  common/
  protocol-ws/
  protocol-mq/
  infrastructure/
im-services/
  <domain>/
    pom.xml
    <service>-api/
    <service>-service/
platform-services/
  <domain>/
    pom.xml
    <service>-api/
    <service>-service/
common-services/
  <domain>/
    pom.xml
    <service>-api/
    <service>-service/
```

| 模块 | 职责 |
| --- | --- |
| `gateway` | 统一入口、路由、鉴权透传、限流等横切能力 |
| `im-user-service` | 用户域与社交关系 |
| `im-message-service` | 消息域权威写模型、消息查询与消息投影协调 |
| `im-conversation-service` | 会话、群组与 RTC 会话域能力 |
| `common-media-service` | 通用支撑域：跨领域媒体对象、上传会话、存储适配、短期访问授权与媒体引用登记 |
| `im-admin-service` | 管理端与配置域能力 |
| `im-access-ws` | 即时通讯长连接接入网关，只负责鉴权、连接管理、命令投递与事件下行 |
| `common` | 与业务无关的通用工具、异常、分页与基础组件 |
| `protocol-ws` | WebSocket 外部协议契约（DTO、事件名、常量） |
| `protocol-mq` | MQ 命令与事件契约（DTO、Topic、路由键辅助工具） |
| `infrastructure` | 纯技术设施：JWT、Redis、MyBatis、Flyway、MQ 客户端抽象等 |

## 依赖规则

- 业务服务只能依赖 `common`、`protocol-ws`、`protocol-mq`、`infrastructure`
- `gateway` 不得依赖任何业务服务实现
- `im-access-ws` 是长连接接入层，不得承担消息权威落库职责
- `im-access-ws` 及其他接入层模块不得承载用户、积分、营销等业务域实现；确需调用时只能通过协议或内部接口委托权威业务服务
- `im-message-service` 是消息域唯一权威写模型中心，消息权威存储只能落 MySQL
- MongoDB 只承担热消息、离线收件箱、最近会话快照等投影职责，不得被视为权威库
- Redis 只承担热点状态、游标、路由、限流与幂等辅助职责，不得持久化消息正文
- 外部接口统一挂载 `/api/v1/**`，服务内 Controller 路径不带 `/api`

## 代码风格与注解规范

- DTO、Command、Event、Properties 默认允许使用 Lombok 精简机械式 `get/set`
- 领域实体、聚合根、持久化核心模型禁止使用 `@Data`
- 推荐组合：
  - `@Getter`
  - `@Setter`
  - `@Builder`
  - `@NoArgsConstructor`
  - `@AllArgsConstructor`
- 业务 DTO 必须放在所属业务域模块内的明确包路径下，例如 `user.points.dto`、`message.command`；禁止伪装为 `common.dto` 混入业务对象
- 禁止使用全路径类型、全路径常量、全路径静态成员引用；必须通过 `import` 维护可读性
- `org.jetbrains:annotations` 可以酌情引入，用于 `@NotNull`、`@Nullable`、`@Contract` 等静态契约表达，但不做强制门禁
- 运行时输入校验仍以 `jakarta.validation` 为准，JetBrains 注解不能替代运行时校验

## 注释规范

- 每个可部署服务必须在 `docs/im-services/<service>/README.md` 维护职责、入口、依赖和运行方式
- 关键业务类必须保留高质量中文注释，说明业务意图、边界条件、幂等性和失败语义
- Controller、MQ 消费者、消息投影、网关接入、鉴权、状态流转等关键类必须说明“为什么这样设计”，不能只写“做了什么”
- 影响权限、一致性、顺序性、幂等性、补偿、重试和降级的代码必须补充注释
- 注释必须与实现同步维护，迁移代码时要同步更新注释归属
- 禁止乱码、占位符残留、机翻不通顺或与实现不一致的注释

## 日志规范

- 日志目标是排查问题，不是堆砌输出；必须优先保证关键链路“进得来、出得去、错得明白”
- 以下位置必须有日志：
  - 外部输入入口：HTTP、WebSocket、MQ Consumer、定时任务入口
  - 关键输出边界：MQ Producer、Redis 广播、WebSocket 下行、数据库权威写入、Mongo/Redis 投影写入
  - 异常报错位置：`catch`、重试、补偿、降级、吞异常、启动失败、关闭失败
  - 关键状态切换：连接建立/关闭、消息入队、消息落库、事件投递、会话顺序号分配、Outbox 发布
- 高并发链路日志必须记录“可定位字段”，至少包括业务主键或关联键，例如：`msgId`、`commandId`、`eventId`、`conversationId`、`userId`、`groupId`、`sessionId`
- 禁止在日志中直接打印密钥、Token、完整消息正文、身份证号、手机号等敏感数据
- 文本类内容如确需辅助排障，只能记录长度、摘要或业务标识，不能默认输出全文
- 严禁静默吞异常；确实需要继续流程时，必须至少记录 `warn` 日志并说明降级语义
- 建议日志级别：
  - `INFO`：关键业务状态变化、关键边界输入输出
  - `WARN`：可恢复异常、降级、非法输入、权限拒绝、重试挂起
  - `ERROR`：真正失败、链路中断、数据不一致风险、启动失败、关键资源不可用
  - `DEBUG`：高频明细，仅用于本地排障，不得依赖 `DEBUG` 才能理解主链路

## 构建与校验规则

- JDK：`25`
- 提交前必须通过：
  - `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate\invoke-engineering-validation.ps1 -Scope Full`
  - IDE `Problems` 无新增红色错误
- 修改源码、POM、脚本、配置或文档后，使用 `-Scope Changed` 执行快速工程门禁；提交和 CI 使用 `-Scope Full`。
- 结构调整、模块依赖调整、Starter 变更、YAML 配置变更后，必须执行 Maven Reload 并复查 IDE `Problems`
- 涉及新增模块、模块间依赖或协议契约变更时，`package` 不足以覆盖 IDE/JDT 依赖解析，必须执行 `install` 以同步本地 `.m2`
- 禁止新增 deprecated API 调用；发现上游 API 已弃用时，必须同步切换到当前推荐写法，不能通过 `@SuppressWarnings("deprecation")` 规避
- 提交前不仅要保证 Maven 构建成功，还要处理新增的 Java/JDT、Spring Boot YAML 配置、弃用 API 等 IDE 诊断
- 若 IDE 仍提示 `BOOT_VERSION_VALIDATION_CODE` 或 `build file has been changed and may need reload`，需要先完成 Reload，再判断是否属于真实问题
- 文本编码、PowerShell UTF-8 安全、Java 注释、弃用 API、日志、异常、服务边界、持久层、Flyway、RocketMQ、密钥暴露与 Maven 版本所有权校验均由统一入口执行；不得选择性跳过。
- `.env` 是版本控制中的共享本地开发基线；环境差异通过各开发机的受管环境变量覆盖，不得将生产或共享环境凭据写入其中。
- `.env` 的受版本控制状态和密钥校验豁免是有意设计：不得擅自将其加入 `.gitignore`、改为仅模板文件或纳入密钥扫描；如需调整，必须同步更新工程规范、校验脚本和开发环境迁移说明。
- 禁止在 Windows PowerShell 批量改写源码、文档、配置时裸用 `Get-Content`、`Set-Content`、`Out-File`；必须显式指定 UTF-8 或按字节读取后按 UTF-8 解码
- 仅当验证命令真实执行并通过后，才能声明“已修复”“已完成”“已通过”

## RocketMQ 生产规范

- RocketMQ 资源命名、协议边界、可靠性、安全与变更门禁以 [12 RocketMQ 治理规范](./standards/12_ROCKETMQ_CONVENTIONS.md) 为准；已实现资源以 [MQ 资源台账](./mq/REGISTRY.md) 为唯一台账。
- 当前阶段统一采用 Remoting 直连模式：
  - `org.apache.rocketmq:rocketmq-client:5.3.1`
- 未来如引入 Proxy + gRPC，只能在 `infrastructure` 新增实现，不得修改业务层 Topic、Payload、Sharding Key 语义
- RocketMQ Topic 命名统一使用小写、下划线分段、显式版本后缀，例如：`im_message_command_send_v1`
- 独立 MQ 集群的资源名称不得携带环境后缀；环境隔离由集群、部署配置和 ACL 完成
- Topic、Producer Group、Consumer Group 必须集中定义在 `protocol-mq`，禁止硬编码或通过字符串拼接构造资源名称
- Producer Group 配置必须使用服务专属环境变量；启用 ACL 时客户端必须配置 `AclClientRPCHook`
- 同一 `conversationId` 下必须使用同一顺序分片键，保证会话内强顺序
- MQ 仅保证至少一次投递，业务必须通过幂等键与唯一约束完成去重
- 权威写入禁止“写库后 best effort 发 MQ”；必须使用可靠事件发布（例如 Outbox + Relay）
- 新增或变更 MQ 资源时，必须同步更新协议常量、资源台账、所属服务文档与相关校验门禁

## 数据库生产规范

- 当前阶段所有表允许放在单库中，默认数据库名为 `gv_im`
- 运行时代码分三类域：IM 业务（`im-services/`、`im-*`）、平台/SaaS 业务（`platform-services/`、`platform-*`）、通用支撑（`common-services/`、`common-*`，如媒体/审核/邮件/短信/支付渠道）；公共 SDK 库放 `sdk/`（`common`/`infrastructure`/`im-protocol-*`）。IM 与 SaaS 数据库独立（IM 库与 SaaS 库分设，SaaS 库名为 `gv_saas`）、代码独立、独立部署、可独立销售；公共工程规范、技术框架与公共依赖抽为公共结构共享，业务形态不耦合。SaaS 内部多租户采用共享库 + 行级租户隔离。
- 单库不等于混用。表名统一使用“已登记领域缩写前缀 + 单数业务名词”；缩写的目的在于控制表名长度，但必须语义稳定、全局唯一，禁止按开发人员偏好临时创造。

### 领域与数据库表前缀登记表

| 领域 | 领域职责 | 数据库表前缀 | 状态 | 示例/说明 |
| --- | --- | --- | --- | --- |
| User | IM 账户、社交关系、用户积分（IM 域，非 SaaS 账号） | `user` / `user_` | 历史冻结 | `user` 为主表，附属表如 `user_friend` |
| Message | 消息、已读、同步索引 | `msg_` | 历史冻结 | `msg_message` |
| Conversation | 群组、成员、会话投影 | `conversation_` | 历史冻结 | `conversation_group` |
| Admin | 平台管理读模型与配置 | `adm_` | 历史冻结 | `adm_system_config` |
| Order | 预约、订单、KTV 履约（首发）、酒店/足浴（后续） | `ord_` | 历史冻结并扩展 | `ord_reservation`、`ord_order` |
| Media | 媒体对象、上传会话、存储适配、访问授权（通用支撑域） | `media_` | 二阶段 | `media_object`、`media_upload_session` |
| WebSocket | 长连接接入 | `ws_` | 保留 | 新建持久化表前需补充领域设计 |
| Identity | SaaS 平台账号、登录、OAuth 绑定、资料同步 | `idt_` | 二阶段 | `idt_account`、`idt_login_identity`、`idt_oauth_link` |
| Tenant | 租户、组织、门店、商户主体 | `tnt_` | 二阶段 | `tnt_tenant`、`tnt_store` |
| IAM | 身份与访问控制、角色、授权范围 | `iam_` | 二阶段 | `iam_role`、`iam_user_role` |
| Resource | 包厢（首发）、房间、技师等经营资源及占用 | `res_` | 二阶段 | `res_resource`、`res_occupation` |
| Payment | 收款、退款、押金、班次、日结（通用支撑域·支付域） | `pay_` | 二阶段 | `pay_intent`、`pay_refund` |
| Customer | 租户客户、会员、积分、储值、权益 | `cst_` | 二阶段 | `cst_customer`、`cst_point_ledger` |
| Marketing | 活动、优惠券、触达、营销同意 | `mkt_` | 二阶段 | `mkt_campaign`、`mkt_coupon` |

- 通用支撑域（Common Services）承载跨业务通用运行能力：媒体（`media_`）、审核（`audit_`，预留）、邮件、短信、支付渠道（`paych_`，预留）等；各能力独立建表、独立前缀、独立登记，配置管理与审计统一进通用能力后台（角色权限控制）。支付渠道适配（`common-payment-channel-service`）与支付业务（`common-payment-service`，`pay_` 前缀）同属支付域（支撑域子域），职责分离：渠道适配 vs 资金事实。
- 新领域、新前缀或前缀语义变更必须先完成架构确认，并在上表及对应领域设计中登记后才能创建 Flyway 表；既有表不得仅为统一缩写而重命名。
- 表名、列名统一使用 `snake_case`
- 表名使用单数名词；关联表也使用单数并以业务主体在前，例如 `user_friend`、`user_device_token`
- 索引命名统一：
  - 唯一索引：`uk_<table>_<semantic>`
  - 普通索引：`idx_<table>_<semantic>`
- 所有生产表必须显式声明：
  - `ENGINE=InnoDB`
  - `DEFAULT CHARSET=utf8mb4`
  - `COLLATE=utf8mb4_0900_ai_ci`
- 所有核心表必须使用 `bigint unsigned` 自增 `id` 作为主键；附属表、关联表也默认保留 `id`，只有经设计评审确认的纯关系表可例外
- 所有表默认包含以下审计字段：
  - `created_by bigint unsigned not null default 0`
  - `created_at datetime(3) not null`
  - `updated_by bigint unsigned not null default 0`
  - `updated_at datetime(3) not null`
  - `0` 仅表示系统任务或初始化数据；应用写入必须填入当前操作者的用户 ID
  - 如需软删除：`deleted_at datetime(3) null`
- 禁止把 JSON 列作为核心查询字段
- 消息权威表必须至少具备：
  - 业务唯一标识
  - 幂等键
  - `(conversation_id, seq)` 唯一约束
  - 面向查询路径的必要二级索引

## 持久层技术栈与依赖规范

- MySQL 是唯一权威库；MongoDB 仅承担消息热投影，Redis 仅承担热点状态，接入层不拥有权威持久层。
- 关系型持久层主栈为 MyBatis-Plus `3.5.17`；持有关系型实体与 Mapper 的业务关系库服务使用 `mybatis-plus-spring-boot4-starter`，并以运行时依赖声明 `mysql-connector-j`。
- 持有迁移脚本的服务使用 `spring-boot-starter-flyway` 与 `flyway-mysql`；未持有迁移脚本的模块不得预置迁移依赖。
- Starter 模板：关系库服务声明 MyBatis-Plus Starter 和运行时 MySQL 驱动；迁移服务在此基础上追加 Flyway Starter 与 MySQL 适配器。
- Spring Boot Parent 管理 Spring Boot、MySQL 与 Flyway 版本；父 POM 管理 MyBatis-Plus 等 Boot BOM 外版本。
- 禁止业务模块直接声明 MyBatis-Plus 底层组件或 `flyway-core`；原生 MyBatis 注解 SQL 仅用于 MyBatis-Plus 无法安全表达的场景，并须保留字段清单、事务边界与中文说明。
- 门禁入口为 `scripts/validate/validate-persistence-dependency-boundaries.ps1`，并通过统一验证入口执行；跨域 Mapper、实体复制和数据所有权属于后续专项，禁止借此向接入层迁移持久层职责。

## 数据库迁移规范

- Flyway 是唯一权威迁移体系
- Flyway 版本由 Spring Boot Parent 管理；子模块通过 Starter 与 MySQL 适配器声明迁移能力
- 只有实际持有 `src/main/resources/db/migration` 脚本的服务，才允许声明 `spring-boot-starter-flyway` 与 `flyway-mysql`
- 未持有迁移脚本的模块，禁止仅因“以后可能会用”而预置 Flyway 依赖；需要接入时，必须同时补齐脚本、配置和职责说明
- 持有迁移脚本的服务必须在 `application.yml` 中显式配置 `spring.flyway.enabled=true`，避免依赖存在但职责不清
- 禁止在生产或开发主链路使用 `ddl-auto=create/update`
- 历史迁移脚本只增不改；如需修正结构，新增后续版本脚本
- 旧式“大一统初始化脚本”可以保留为历史参考，但不得继续作为权威来源
- 如果需要重置数据库，必须通过仓库内统一的重建脚本执行，并在 `.outputs/` 留存日志

## 日志与输出规范

- 构建、验证、诊断输出统一落到 `.outputs/`
- 构建日志：`.outputs/logs/build/<yyyyMMdd>/`
- 临时调试日志：`.outputs/logs/debug/<yyyyMMdd>/`
- 服务运行日志：`.outputs/logs/im-services/<service-name>/`
- 本地服务优先使用 `run-dev.ps1`
- 构建与验证使用 `scripts/validate/invoke-engineering-validation.ps1`；该入口通过 Maven Wrapper 串行执行 Maven 并持久化日志。
- 禁止通过 `> xxx.log`、`2>&1` 等方式把临时日志直接写到仓库根目录
- 临时调试日志如需保留，必须按“日期 + 任务名”落到 `.outputs/logs/debug/`
- 详细目录约定见 [42 日志输出与诊断规范](./standards/42_LOGGING_AND_OUTPUTS.md)
- 编码与批量改写规范见 [30 文本编码与批量改写规范](./standards/30_TEXT_ENCODING_AND_BATCH_EDITING.md)
