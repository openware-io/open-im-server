# 业务微服务 DDD 工程规范

## 目标与适用范围

本规范适用于任何承载用户、群组、会话、消息、订单、账户等业务能力的 Java 微服务。本规范不绑定组织名、仓库名、构建工具、包名或模块命名方式。

- 业务服务采用领域驱动设计，领域模型承载核心业务规则，禁止以 Controller、Application Service、Mapper 作为业务规则中心。
- 依赖必须指向领域内核：`api -> application -> domain <- infra`。
- `domain` 必须保持框架与中间件无关，不得依赖 Spring、MyBatis-Plus、Redis、RocketMQ、HTTP 客户端、数据库驱动或具体持久化实现。
- 网关、WebSocket 长连接接入、协议编解码、连接路由等边缘基础设施不套用本规范；它们采用适合高并发接入的简化分层，并通过协议或内部 API 委托业务服务。

## 工程全景与 Maven 模块边界

本规范定义业务微服务从聚合工程到服务内部包的完整结构。Maven 版本、BOM、插件、依赖 scope、Wrapper、制品仓库和 Enforcer 的具体配置以 [20 Maven 工程规范](./20_MAVEN_ENGINEERING_CONVENTIONS.md) 为准；本节只定义这些构建单元如何表达 DDD 限界上下文、服务边界和部署边界。

```text
<workspace-root>
├── pom.xml                                  # 根构建基线与全仓 Reactor
├── sdk/                                # 跨业务复用的技术能力与协议
│   ├── pom.xml                              # 平台聚合与平台 BOM
│   ├── <common-technology-module>/
│   ├── <protocol-module>/
│   └── <infrastructure-abstraction-module>/
├── gateways/                                # HTTP、长连接等边缘接入部署单元
│   ├── pom.xml
│   └── <gateway-deployment-module>/
└── im-services/
    ├── pom.xml                              # 业务服务分组聚合，不承载业务实现
    └── <bounded-context>/                   # 一个限界上下文、一个服务发布边界
        ├── pom.xml                          # 服务域父 POM 与局部 Reactor
        ├── <service>-api/                   # 可被其他服务消费的稳定契约 JAR
        │   ├── pom.xml
        │   └── src/main/java/<base-package>/<context>/api
        └── <service>-service/               # 唯一可运行、可部署的业务实现 JAR
            ├── pom.xml
            └── src/
```

### 构建单元职责

| 单元 | Maven 角色 | DDD 与部署职责 | 允许内容 | 禁止内容 |
| --- | --- | --- | --- | --- |
| 根工程 | 全仓聚合、构建基线 | 协调全仓验证，不代表业务边界或发布单元 | 第三方版本、公共插件、全仓门禁、分组模块 | 业务代码、业务 API/Service 依赖管理、启动类 |
| 平台 | 聚合、BOM、技术制品 | 提供跨业务复用的技术能力和协议 | 无业务语义的工具、技术抽象、协议、基础设施能力 | 服务私有领域模型、业务 Repository、业务实现、可部署业务应用 |
| 服务分组 | 聚合 POM | 仅组织业务服务域，不代表一个可部署服务 | 服务域聚合声明 | 业务实现、跨域版本统一、跨域依赖中转 |
| 服务域父模块 | 继承父 POM、局部聚合、版本与依赖管理 | 一个限界上下文的构建与发布协作边界 | 本域版本、平台 BOM、本域 API/Service 管理、公共服务插件 | 业务源码、跨域 Service 聚合、其他服务 API 的版本中转 |
| API 模块 | 普通 JAR、契约制品 | 对外暴露最小稳定业务契约 | 请求/响应、命令、事件、客户端接口、错误码、契约版本 | 聚合、实体、PO、Mapper、Repository、启动类、迁移、业务实现 |
| Service 模块 | 可执行 JAR、部署制品 | 承载本域权威业务实现、数据与运行配置 | DDD 分层代码、迁移、配置、启动类、运行依赖 | 其他服务实现、其他服务数据访问、共享的服务私有模型 |

### 聚合、继承与制品术语

1. **聚合 POM** 通过 `<modules>` 决定一次 Maven Reactor 构建包含哪些模块；它不自动表示代码可见性或运行时依赖。
2. **继承父 POM** 通过 `<parent>` 继承属性、`dependencyManagement`、插件与门禁；父子关系必须服从模块所属边界。
3. **BOM** 只管理依赖版本，不引入业务实现；服务域按批准策略导入平台 BOM，叶子模块不得重复散落平台版本。
4. **API 制品** 是可独立被消费的稳定契约 JAR，但仍属于其服务域的局部 Reactor 和版本边界。
5. **Service 制品** 是唯一部署单元，拥有权威数据、业务实现、配置和数据库迁移；不能因被聚合而被其他服务 Maven 依赖。
6. **局部 Reactor** 指从服务域父 POM发起、只覆盖该限界上下文 API 和 Service 的构建；它是日常开发、测试、打包和发布验证的默认入口。

## 服务域模块结构

```text
<bounded-context>/
├── pom.xml
├── <service>-api/
│   ├── pom.xml
│   └── src
│       ├── main/java/<base-package>/<context>/api
│       │   ├── client
│       │   ├── dto
│       │   │   ├── request
│       │   │   └── response
│       │   ├── command
│       │   ├── event
│       │   ├── error
│       │   └── version
│       └── test/java/<base-package>/<context>/api
└── <service>-service/
    ├── pom.xml
    └── src
        ├── main
        │   ├── java/<base-package>/<context>
        │   └── resources
        └── test/java/<base-package>/<context>
```

1. 服务域父 POM只聚合本域 API 模块和本域唯一 Service 模块。契约测试、迁移验证等需要独立 Maven 模块时，必须属于本域、没有独立部署职责，并在服务文档中说明其必要性。
2. 一个限界上下文出现两个需要独立扩缩容、独立拥有数据或独立发布节奏的运行单元时，必须拆分为两个服务域；禁止在同一服务域父 POM中堆叠多个无关 Service 部署模块。
3. API 模块和 Service 模块必须继承服务域父 POM，并继承同一服务域版本；除完成独立兼容性、发布和回滚能力建设外，禁止拆分 API 与 Service 版本。
4. Service 模块可以依赖本域 API、平台技术模块和经批准的其他服务 API；不得依赖任何其他服务的 Service 制品。
5. API 模块不得为了“复用”镜像 Service 的内部包、领域对象或持久化模型。跨服务只传递稳定协议对象，服务内部对象不跨 Maven 边界。
6. API 模块新增公开类型前，必须存在稳定的跨服务消费者，或具备经架构评审批准的近期消费计划；不得以预置、猜测或镜像内部模型的方式扩张公共契约。

## 服务域依赖图与对象边界

```text
其他服务 Service ────────────────> 本域 API
                                   ^
                                   │
平台技术模块 ────────────────────> 本域 Service ───> 本域 API
                                   │
                                   └──> 本域权威数据与外部基础设施

本域 Service 内部：api -> application -> domain <- infra
```

1. 跨服务的同步调用仅依赖对方 API 制品；异步集成仅依赖版本化事件契约。任何调用都不得绕过契约直接访问对方数据库、Mapper、Repository 或 Service 制品。
2. 本域 API 与 Service 的内部依赖方向为 `service -> api`，不得反向依赖。API 不得依赖 Service、平台具体基础设施或应用运行框架。
3. Service 内部的 `api` 包是入站适配器，不等同于 Maven API 模块：前者只服务本进程 HTTP/gRPC 入站协议，后者只表达跨服务稳定契约。两者不得互相替代。
4. 外部 API DTO、内部 Controller DTO、Application Command/Result、领域对象、PO 和 MQ/RPC Payload 必须继续遵守本规范的对象隔离规则；Maven 模块拆分不能成为跨层直接传递对象的理由。
5. 领域层依赖方向不因 Maven 模块而改变。Service 模块中的 `domain` 仍不得依赖 Spring、MyBatis、Redis、MQ、RPC 或任何 `infra` 实现。

## 局部构建、变更与发布

### 构建入口

1. 日常 Service 内部实现改动应从服务域父 POM发起局部 Reactor 构建；根 Reactor只用于全仓基线、平台变更或跨域变更的扩展验证。
2. 修改本域 API、父 POM、平台 BOM引用或其他服务 API消费关系时，必须确保依赖制品可解析：优先从根 Reactor使用 `-pl <service-domain> -am` 构建，或先按受控流程安装所需父 POM、平台和 API 制品。
3. 使用 Maven Wrapper 执行构建。命令、生命周期目标、版本更新、依赖解析和发布配置遵循 [20 Maven 工程规范](./20_MAVEN_ENGINEERING_CONVENTIONS.md)。

### 验证矩阵

| 变更范围 | 最小验证 | 必须追加验证 |
| --- | --- | --- |
| 仅 Service 内部领域、应用、基础设施或入站适配器 | 服务域局部 `clean verify`、相关领域/应用/基础设施测试 | 数据迁移、MQ、RPC或配置变更的专项验证 |
| 本域 API 契约 | 服务域局部 `clean verify`、API 兼容性与契约测试 | 所有已知消费方构建或契约验证、根 Reactor校验 |
| 服务域父 POM、版本、依赖管理或插件 | 服务域局部 `clean verify`、有效 POM与有效版本检查 | 根 Reactor校验、发布前制品解析验证 |
| 平台 BOM、公共插件或全仓门禁 | 根 Reactor `clean verify` | 受影响服务域构建、依赖树与运行验证 |
| 跨服务 API消费、事件契约或数据所有权 | 生产方与消费方服务域构建、契约测试 | 根 Reactor校验、兼容/回滚演练与服务文档更新 |

### 发布与回滚

1. 服务域是业务 API 与 Service 的默认发布协作单元：发布前必须确认父 POM、API JAR、Service JAR三者坐标和版本一致，且制品仓库中的 POM依赖可解析。
2. 发布顺序为：先发布服务域父 POM和 API JAR，再发布 Service JAR与部署配置；部署前必须验证所部署 Service 解析到目标 API 坐标。
3. API 变更必须保持向后兼容，或通过版本化契约、并行消费者和明确弃用窗口迁移；禁止以直接覆盖旧字段语义、删除仍被消费的类型或静默改写错误码实现破坏性变更。
4. 回滚必须同时考虑 API 消费方与运行中 Service 的兼容范围。不能兼容回滚的 API 变更不得进入常规发布流程。
5. 服务私有领域模型、迁移脚本、业务实现和运行配置不发布给其他服务；它们的回滚由本服务的数据迁移和部署方案负责。

### 自动化门禁

1. 校验服务域父 POM只聚合本域允许模块，叶子 API/Service 的 `<parent>` 指向该服务域父 POM。
2. 校验 API 模块不包含可执行 Spring Boot插件、数据库迁移、持久层依赖、启动类或对任何 Service 制品的依赖。
3. 校验 Service 模块只依赖本域 API、平台模块和批准的外部 API；禁止依赖其他服务 Service 制品。
4. 校验服务域 API 与 Service 的有效版本一致，并校验平台 BOM版本符合批准的平台兼容策略。
5. 校验存在 `db/migration` 的 Service 是相应权威数据的唯一迁移所有者；跨服务不得复用或修改其迁移脚本。
6. 门禁发现例外时必须强制失败。临时例外须有架构决策记录、负责人、到期时间、退出条件和补充验证范围。

## Service 内部目录

```text
<business-service>
├── pom.xml
└── src
    ├── main
    │   ├── java/<base-package>/<bounded-context>
    │   │   ├── <Service>Application.java
    │   │   ├── api
    │   │   │   ├── controller
    │   │   │   ├── dto
    │   │   │   │   ├── req
    │   │   │   │   └── resp
    │   │   │   └── converter
    │   │   ├── application
    │   │   │   ├── command
    │   │   │   ├── query
    │   │   │   ├── result
    │   │   │   ├── service
    │   │   │   └── assembler
    │   │   ├── domain
    │   │   │   ├── model
    │   │   │   │   ├── aggregate
    │   │   │   │   ├── entity
    │   │   │   │   └── valueobject
    │   │   │   ├── event
    │   │   │   ├── repository
    │   │   │   ├── service
    │   │   │   ├── policy
    │   │   │   └── exception
    │   │   ├── infra
    │   │   │   ├── persistence
    │   │   │   │   ├── po
    │   │   │   │   ├── mapper
    │   │   │   │   ├── converter
    │   │   │   │   └── repository
    │   │   │   ├── cache
    │   │   │   ├── messaging
    │   │   │   ├── rpc
    │   │   │   └── config
    │   │   └── common
    │   │       ├── exception
    │   │       └── util
    │   └── resources
    │       ├── application.yml
    │       ├── application-dev.yml
    │       ├── application-prod.yml
    │       ├── mybatis/mapper
    │       └── db/migration
    └── test
        └── java/io/openware/im/<domain>
            ├── domain
            ├── application
            └── infra
```

- 包按业务子域继续分组，避免一个服务出现过大的横向目录。例如群组域可使用 `domain.model.aggregate.group`、`domain.repository.group`、`application.service.group`。
- `common` 仅可放当前服务内部的通用异常处理、局部工具和非业务配置；不得成为逃避领域归属的业务对象容器。
- 跨服务通用的技术能力放入平台或共享技术模块；跨服务稳定的业务契约放入独立契约模块；不得将服务私有领域模型下沉到公共模块。
- 示例：采用“服务域父模块 + API + Service”结构的工程，可将稳定 Java 契约放入 `<service>-api`，将可部署实现放入 `<service>-service`。这是一种推荐落地方式，不是本规范唯一允许的目录形式。

## 分层职责与依赖规则

| 层 | 职责 | 允许依赖 | 严禁依赖 |
| --- | --- | --- | --- |
| `api` | 接收 HTTP 输入、参数校验、鉴权上下文提取、DTO 转 Command、调用应用服务、Result 转响应 DTO | `application`、接口 DTO、无业务语义的通用校验组件 | `domain.repository`、`infra`、Mapper、PO、事务与业务规则 |
| `application` | 用例编排、事务边界、权限与幂等协调、加载聚合、调用领域行为、持久化、记录 Outbox | `domain`、应用 Command/Query/Result、抽象端口 | API DTO、Mapper、PO、MyBatis、Redis、RocketMQ 客户端 |
| `domain` | 聚合、实体、值对象、领域服务、领域策略、领域事件、仓储契约、业务异常 | JDK、同层类型、经批准的纯基础类型 | Spring、Lombok `@Data`、MyBatis、数据库、缓存、MQ、RPC、JSON 序列化实现 |
| `infra` | 仓储实现、PO/Mapper、缓存、MQ 投递、远程调用、Spring 配置、技术适配 | `domain`、技术框架、配置 | API Controller、应用 DTO；不得反向承载业务规则 |

- `api` 只能调用 `application.service` 暴露的用例入口，Controller 禁止直接调用 Repository、Mapper、缓存或 MQ。
- `application` 依赖 `domain.repository` 等抽象，不得导入 `infra.persistence.mapper`、`infra.persistence.po` 或具体仓储实现。
- `infra` 通过实现领域仓储接口、事件投递接口或外部系统端口接入领域和应用；依赖方向必须从实现指向契约。
- 领域模型不以注解扫描、ORM 映射或序列化注解作为持久化便利手段。持久化映射由 `infra.persistence.po` 与转换器承担。
- `domain` 可以使用不可变 JDK 集合、时间类型和服务私有基础异常；共享基础能力必须无业务语义且不引入技术框架。

## 对象隔离与转换

服务内对象严格隔离，禁止跨层直接复用。

| 对象 | 所属层 | 用途 | 禁止事项 |
| --- | --- | --- | --- |
| Request DTO / Response DTO | `api.dto` | 外部协议输入输出 | 禁止进入应用、领域和持久层 |
| Command / Query / Result | `application` | 用例输入、查询条件和用例输出 | 禁止成为 Controller 入参或数据库映射对象 |
| Aggregate / Entity / Value Object | `domain` | 业务状态、行为与不变量 | 禁止直接序列化、直接映射数据库、直接返回接口 |
| PO | `infra.persistence.po` | 数据库表映射 | 禁止泄露至 Application、Domain、API |
| MQ Payload / RPC Request | `infra.messaging` / `infra.rpc` | 外部基础设施协议 | 禁止替代领域事件或领域对象 |

- `api.converter` 负责 DTO 与 Command/Result 的转换，禁止把 DTO 直接转换成持久化 PO。
- `application.assembler` 负责 Command 与领域对象所需数据的组装；领域对象创建必须通过工厂方法、构造器或聚合行为完成。
- `infra.persistence.converter` 负责 PO 与领域对象的双向转换，PO 不得穿透 Repository 实现边界。
- MapStruct 可以用于无业务语义的字段映射；涉及默认值、权限、状态流转、不变量和聚合装配时必须由显式代码完成。

## 领域模型规则

### 聚合与实体

- 每个聚合必须明确一个聚合根。外部对象只能通过聚合根修改聚合内部实体和值对象。
- 聚合根负责维护不变量、状态迁移、成员增删、权限约束和领域事件收集，不得提供无约束的整体设值方法（例如把核心状态直接暴露为可写属性或通用 setter）修改核心状态。
- 聚合之间只能通过标识关联，不得在一个事务中加载和修改多个大型聚合以维持一致性；跨聚合一致性通过领域事件、流程编排或补偿实现。
- 领域实体与聚合根禁止使用 Lombok `@Data`；可按需使用 `@Getter`、受限访问器或显式方法。
- 值对象必须不可变，字段在构造时完成校验，不提供 setter；等值比较以业务属性而非对象标识为准。

### 仓储与领域服务

- Repository 接口必须位于 `domain.repository`，以聚合为单位定义 `findById`、`save`、必要的业务查询等契约。
- Repository 不得暴露 MyBatis `Wrapper`、PO、Mapper、分页插件对象、数据库异常或技术查询语法。
- 跨实体且无法自然归属某个聚合根的无状态业务规则可定义为 `domain.service`；能放入聚合行为的规则不得拆成贫血领域服务。
- `domain.policy` 用于可替换的纯业务决策，例如群成员上限、群类型创建资格、状态迁移许可；策略接口及其纯业务实现在领域层，依赖外部数据的适配在应用层完成。

### 领域事件与可靠发布

- 领域事件定义在 `domain.event`，事件名称使用过去时，例如 `GroupCreatedEvent`、`GroupMemberRemovedEvent`。
- 聚合状态变化时创建并暂存领域事件；应用层在同一事务中保存聚合和 Outbox 记录。
- RocketMQ 投递只能由 `infra.messaging` 的 Relay 在事务提交后异步执行。禁止在数据库事务中直接发送 MQ，也禁止“保存后 best effort 发送”。
- 领域事件对象不得包含 PO、Mapper、HTTP DTO、完整敏感正文或技术框架类型；事件负载只保留消费者所需的稳定业务事实。
- 外部事件消费者必须以 `eventId` 幂等；同一会话的顺序事件必须使用约定的顺序分片键。

### 领域模型与只读投影的边界

写路径与读路径的建模要求不同，必须显式区分，避免两种错误：把只读报表硬套成领域模型（过度设计），或让 PO 继续充当读模型（违反隔离）。

判定标准是**该查询结果是否参与状态变更决策**：

| 场景 | 建模要求 | 放置位置 |
| --- | --- | --- |
| 写路径：结果会参与状态变更、需要维护不变量 | 领域模型（聚合根 / 实体 / 值对象） | `domain.<ctx>.model` |
| 只读聚合查询：报表、列表、导出、统计，结果不参与决策 | 只读投影类型 | `domain.<ctx>.readmodel` 或 `application.query` |

- 只读投影**不得复用 PO 类型**，也不得返回裸 `Map`／`Object[]` 而无类型；必须是有名字的投影类型。
- 只读投影**不得**定义业务行为、不得被写路径当作实体使用；一旦某查询结果开始参与状态变更，必须升级为领域模型。
- 端口返回只读投影时，方法名应体现“查询”语义（如 `searchOrders`），与写路径的聚合获取（`findById`）区分。

### 端口命名与契约

- Repository 接口一律位于 `domain.repository`；**不再使用 `domain.port`**。既有的 `domain.port` 在改造中迁入 `domain.repository`。
- 端口以**聚合为单位**定义，方法名表达业务意图，禁止暴露技术查询语法：

| 必须 | 禁止 |
| --- | --- |
| `findById`、`save`、`findForUpdate`、按业务语义命名的查询 | MyBatis `Wrapper`、`PO`、`Mapper`、`IPage`、`Example` |
| 返回业务结果（如 `boolean deductAvailable`） | 返回受影响行数让调用方自行解释 |
| 加锁读用显式命名并说明锁的持有范围 | 隐含加锁，或在应用层拼接查询条件 |

- **原子性/一致性契约必须写进端口 Javadoc**：说明该操作依赖哪种数据库原子性（如单条带上下界的条件更新），以及若改为“先读后判再写”会产生什么后果。这是防止后续实现静默退化的唯一手段。
- 需要数据库原子性的操作必须由**单条语句**完成，不得在应用层先查后判。

### 聚合装配与持久化契约

- 领域模型**不得**包含持久化注解（`@TableName`、`@TableId`、`@TableField`、`@Entity`）或序列化注解。
- 领域模型**保留** `id` 与 `version`：前者是聚合标识，后者是并发语义，二者都是业务可见的。
- **审计字段（`createdBy`、`createdAt`、`updatedBy`、`updatedAt`）不属于领域模型**：它们是记账信息而非业务事实，由适配器或统一拦截器填充。领域模型携带它们会迫使领域层理解持久化语义。
- `save` 语义统一为：`id == null` 时新增，否则更新；乐观锁冲突由适配器抛出明确异常，**不得静默覆盖**。
- 领域对象与 PO 的转换只允许发生在 `infra.persistence`；`PO` 不得穿过 Repository 实现边界。

### 乐观锁的可检验判据

上一条要求"乐观锁冲突由适配器抛出明确异常，不得静默覆盖"。**这条规则的可检验形式是三问**——
只要 `version` 字段存在，三者必须同时成立，缺一即视为**未加锁**：

1. **PO 上有 `@Version` 注解吗？**
2. **框架注册了乐观锁拦截器吗？**（MyBatis-Plus：`OptimisticLockerInnerInterceptor`）
3. **实际发出的 SQL 带 `WHERE ... AND version = ?` 吗？**

若三者任一不成立，却在代码里维护 `version`（例如写前 `version = version + 1`），则该字段是**装饰**：
它读起来像有并发保护，实际是无条件覆盖。**这比完全没有 version 字段更危险**，因为评审者会以为已经防住。

允许的两种正确形态（二者选一，不得混用）：

- **框架托管**：PO 加 `@Version` + 注册拦截器 + 使用 `updateById`。此时不得手工改 `version`。
- **显式 CAS**：仓储端口提供**具名**方法（如 `updateBalanceWithVersion(aggregate, expectedVersion)`），
  适配器发单条 `... WHERE id = ? AND version = ? SET ..., version = version + 1`，
  **版本自增写在 SQL 里**而非由 Java 读出后加一（后者在并发窗口内会写回过期版本号），
  受影响 0 行时由调用方决定重试或抛出明确冲突异常。

补充约束：

- **CAS 是唯一判定点，应用层的"读出来比对"不是并发保护**；它只用于给出更快的错误提示。
  只做应用层比对而无 CAS，等价于未加锁。
- **CAS 成功后**才写附属记录（账本、事件、审计明细）。反过来在 CAS 失败时，
  附属记录会声称"钱/积分动过"而余额未动，而**对账只能相信账本**。
  该顺序依赖事务回滚兜底的写法不得作为常态。
- 并发写法**必须有运行时覆盖**：只做 `test-compile` 或只 mock 适配器的单元测试**证明不了**这条 SQL 能跑。
  至少一个真实适配器（或集成）测试要覆盖列集合与 `WHERE version = ?` 的存在性——
  对 `@TableField(exist = false)` 的字段生成列引用会在运行时抛
  `can not find lambda cache for this property [...]`，编译期完全看不出来。

### 保留字段名与 version 的 A+B 路线

`version` 在本项目里是**语义保留字段名**，不是普通列名。采用 A+B 综合路线：

- **A（锁语义）**：有写入路径的聚合，`version` 必须是真的乐观锁——框架 `@Version` 或显式 CAS；否则按“装饰性 version”处理。
- **B（改名）**：不是锁的字段不得叫 `version`；改用 `revision`、`rowVersion` 等名字，且不得继续在模型/API 中暴露 `version` 语义。
- **只读/追加模型**：没有更新路径时，必须登记为 `READ_ONLY` / `APPEND_ONLY`，不得借 `version` 暗示并发保护。
- **已评审的遗留例外**（只减不增，必须列入改名计划）：
  - `BUSINESS_VERSION`：字段是业务版本字符串（如客户端发布版本），并发由独立 `row_version` 列保护；
  - `COUNTER`：并发由其他原子条件（如库存数量）保护，`version` 只是递增的修订计数，不承担锁判定。

管理方式：

- 所有带 `version` 的 PO 必须登记在 `scripts/validate/reserved-identifiers-reviewed.txt`；
- 新增 `version` 字段未登记、或登记项已不存在，门禁失败；
- 标记为 `PENDING` 的是已知迁移债务，只减不增；
- 门禁 `validate-reserved-identifiers.ps1` 已接入统一入口。

其他保留字段名（`id`、审计四列、`tenantId`/`storeId`）继续按本规范与既有门禁管理，不得复用为业务编码或展示字段。

### 转换层职责切分

| 层 | 职责 | 禁止 |
| --- | --- | --- |
| `api.converter` | DTO 与 Command / Result 互转 | 触碰领域对象或 PO |
| `application.assembler` | Command 到领域对象所需数据的组装 | 直接 new 领域对象后逐字段 setter |
| `infra.persistence.converter` | 领域对象与 PO 双向转换 | 承载业务规则 |

- 领域对象创建必须经工厂方法、构造器或聚合行为完成，装配层只准备入参。
- MapStruct 仅用于**无业务语义**的字段映射；涉及默认值、权限、状态流转、不变量与聚合装配时必须显式编码。

### 跨上下文读取

- **同服务内跨聚合**：通过对方**领域端口**读取，不得直接取用对方的 PO 或 Mapper。
- **跨服务**：只经对方 `*-api` 契约或版本化领域事件；远程 DTO 不得进入领域层。
- 只读的跨上下文查询可以定义**专用只读端口**，但端口归属**调用方**（`domain.<ctx>.repository`），不得反向依赖被调用方的持久化实现。

### 领域规范门禁

规范必须可执行，否则会在迭代中被绕过。全部门禁固化于 `scripts/validate/validate-<name>.ps1`，
统一入口为 `scripts/validate/invoke-engineering-validation.ps1`，`-Scope {Changed|Full}` 只决定 Maven 目标
（`Changed` → `mvn validate`，`Full` → `mvn clean verify`），**不影响跑哪些校验器：34 个一律全跑**。
治理完成后**全部为阻断模式**：`scripts/validate/report-only-gates.txt` 当前**条目为空**（无任何豁免）。

门禁编号索引见 [GATE_REGISTRY.md](../GATE_REGISTRY.md)；新增、删除或改名门禁必须同步更新注册表、
统一入口与本文计数，`validate-gate-registry.ps1` 会校验四方一致。

> **判据的权威来源是校验器脚本本身，不是本表。** 本表只说明每个门禁管什么、违规会怎样表述；
> 修改规则必须改脚本，改完再回来同步本表的描述。这样表格是导航，不会变成第二份会漂移的真相。

#### 分层与依赖边界

| 门禁 | 阻断的写法 |
| --- | --- |
| `service-api-boundaries` | 控制器/应用层依赖持久化实现；`*-api` 模块含启动类、迁移、持久层依赖或可执行业务实现 |
| `controller-repository-boundary` | 控制器持有 `*Repository`/`*Mapper` 字段（**领域接口也算**——边界是依赖本身，不是类型所在的包） |
| `domain-model-purity` | `domain` 包出现 `@TableName`/`@TableId`/`@TableField`/`@Entity`/`@Data` 等持久化或序列化注解；import `infra.*` 或 `*.persistence.*` |
| `port-contract` | `domain.repository` 端口暴露 `Wrapper`/`PO`/`Mapper`/`IPage` 等技术类型，或返回类型是 PO；端口放错包（应在 `domain.repository`） |
| `po-containment` | `api`/`application`/`domain` 层 import `infra.persistence.po.*` |
| `persistence-dependency-boundaries` | 业务/接入模块使用遗留 `javax.persistence`；模块直接声明底层持久化组件；接入层声明持久化依赖 |
| `service-dependency-graph` | 服务依赖另一个服务的 **Service 实现制品**（只能依赖 `*-api` 契约） |

#### Maven 版本与制品

| 门禁 | 阻断的写法 |
| --- | --- |
| `maven-version-ownership` | 根 POM 缺显式版本；服务域父 POM 的 parent/聚合模块越界；服务域 API 与 Service 版本不一致 |
| `security-and-flyway-configuration` | 含迁移的模块缺 `application.yml`、未启用 Flyway、未声明 Flyway 历史表，或 pom 缺 `spring-boot-starter-flyway` |

#### 持久化与数据库

| 门禁 | 阻断的写法 |
| --- | --- |
| `flyway-conventions` | 迁移使用 `CREATE TABLE IF NOT EXISTS`、`DROP TABLE`、物理外键，或未按注册前缀命名 |
| `flyway-immutability` | 基线 tag 冻结后修改既有迁移文件（含未提交改动） |
| `tenant-sql` | 表既无 `tenant_id` 又未登记为平台级；手写 SQL 缺租户谓词 |

#### Java 代码规则

| 门禁 | 阻断的写法 |
| --- | --- |
| `javac -Xlint:deprecation,unchecked` + `failOnWarning` | `src/main/java` 的「已过时 API」或「未检查转换」告警（见下方说明） |
| `java-fully-qualified-names` | 源码里出现全限定名引用（应 import；允许清单外的另有交代） |
| `java-comment-quality` | Java 注释不是合法 UTF-8，读不出来 |
| `java-silent-catch` | `catch` 块既不记日志也不抛出（含「已记日志却仍留降级标记」） |
| `java-exception-logging` | 直接打印堆栈（如 `printStackTrace`）而非走统一日志口径 |
| `java-logging-conventions` | 手工声明 SLF4J logger（应统一用 Lombok `@Slf4j`） |
| `java-deprecated-api-usage` | 源码中出现 `@Deprecated` 声明（**只查本仓源码注解，第三方弃用由上面的编译器门禁负责**） |
| `exception-handling` | 服务缺 `@RestControllerAdvice` 统一出口；兜底分支漏 `Exception`/`Throwable`；兜底不 `log.error`（静默 500）；兜底文案不是简体中文 |

#### 消息、配置与密钥

| 门禁 | 阻断的写法 |
| --- | --- |
| `mq-registry` | RocketMQ 资源未登记到 `docs/mq/REGISTRY.md`；登记项无 `protocol-mq` 常量；group 字面量声明在 `protocol-mq` 之外 |
| `rocketmq-contracts` | 声明了 RocketMQ 契约却没有实现，或实现缺 RocketMQ 依赖 |
| `secret-exposure` | 内联密钥值、疑似凭据赋值、密钥配置默认值、或历史已轮换的凭据值 |

#### 文档、编码与仓库卫生

| 门禁 | 阻断的写法 |
| --- | --- |
| `documentation-links` | 相对链接指向不存在的路径（**含仓库外路径**，CI 只 checkout 本仓）；根 README 未索引 `docs/standards/*` |
| `standards-governance` | 标准文档缺 7 个必备章节之一（目标/术语/规则/模板/门禁/验收/关系）；正文出现占位标记（未完成事项的标记词，清单见 `scripts/validate/standards-governance-rules.txt`） |
| `text-encoding` | UTF-8 BOM、非法 UTF-8、替换字符 U+FFFD、或字面 Unicode 句点转义 |
| `powershell-utf8-safety` | `.ps1` 带 BOM 或不是合法 UTF-8；**Windows PowerShell 5.1 把无 BOM 的 `.ps1` 当 ANSI 读**，故 `.ps1` 必须无 BOM |
| `powershell-ascii-only` | 无 BOM 的 `.ps1` 含非 ASCII 字节（中文须放 `.txt` 数据文件，用显式 UTF-8 解码读取） |
| `repository-hygiene` | 仓内出现第二套门禁/工具入口或第二套校验批次；工作区根出现非仓库目录；跟踪了构建产物等游离文件 |

**为什么弃用检测必须由编译器把门**：`java-deprecated-api-usage` 脚本只 grep 源码里的 `@Deprecated` 注解，
**扫不到第三方（Spring / Jackson / MyBatis-Plus / Spring Data Redis）的弃用**——而依赖升级造成的弃用
恰恰都在这里。本仓的真实案例：`WebJsonConfig` 使用 Spring Framework 7 已标记「过时且待删除」的
`extendMessageConverters(List)`，编译器能报，脚本门禁却永远是绿的。
因此编译期开启 `-Xlint:deprecation -Xlint:unchecked`，并让**生产源码**的告警直接失败
（`failOnWarning`；测试编译单独放宽——该条只约束 `src/main/java`，测试里 mock 泛型的噪声不属同一类）。
新增第三方弃用时构建会失败并指出确切位置，修法只有两条：**迁移到未弃用的 API**，
或**显式抑制并写明理由**（`@SuppressWarnings` 加注释说明为何安全）。

**门禁本身的负向验证**：新增或修改门禁后，必须证明它**真的能失败**——把违规写进代码，
确认构建/校验确实报错并指出位置，再撤掉。**没被证明能失败的断言不算门禁**
（本会话据此验证过 `markExecuted` 的 version 条件、编译器 `failOnWarning`、
以及跨仓相对链接在隔离 checkout 下的失败）。

## 应用服务规则

- 每个公开方法对应一个明确用例，例如 `createGroup`、`inviteMember`、`removeMember`，禁止把大量无关流程堆入单个 Application Service。
- 应用层负责事务边界、鉴权结果的使用、幂等校验、聚合获取与保存、跨聚合协调、Outbox 持久化和调用领域行为。
- 事务必须定义在应用服务用例入口，不得定义在 Controller、Repository 或领域实体中。
- 核心业务判断、状态迁移和不变量必须下沉到聚合根、值对象、领域服务或领域策略；应用层不得出现可复用的业务规则分支。
- 跨服务调用应优先通过异步领域事件或稳定 `*-api` 契约。同步调用必须具备超时、失败语义、幂等和降级设计，且不得把远程 DTO 传入领域层。
- 命令类使用不可变字段；Command、Query、Result 可使用 Lombok 精简样板代码，但不得使用领域实体替代。

## 基础设施规则

- `infra.persistence.repository` 实现 `domain.repository` 接口；`mapper` 和 `po` 仅由基础设施层访问。
- MyBatis-Plus Mapper 仅面向 PO，禁止直接映射领域实体。复杂查询应返回 PO 或专用查询 PO，再在基础设施或应用查询组装层转换。
- Redis 缓存是仓储或查询适配器的实现细节，不得在聚合根、领域服务或 Controller 中直接操作。
- RPC Client 是基础设施适配器。应用层依赖面向业务语义的端口，而非 OpenFeign、RestClient 或 HTTP DTO。
- Spring `@Configuration`、`@Component`、`@Repository`、`@Service` 等框架注解只能出现在 `api`、`application`、`infra`；领域对象不使用 Spring 托管。
- 数据库迁移脚本只放在拥有该领域权威数据的 `*-service/src/main/resources/db/migration`，遵循 Flyway 只增不改规则。

## 测试规则

- 优先编写 `domain` 纯单元测试，验证聚合不变量、状态迁移、值对象校验和领域事件生成；测试不得启动 Spring 容器、数据库或 MQ。
- `application` 测试验证用例编排、事务语义、仓储端口调用、Outbox 记录和失败处理；使用 Repository 与外部端口的测试替身。
- `infra` 测试验证 PO 映射、Repository 实现、迁移、缓存策略和 MQ/RPC 适配；涉及 MySQL 的测试应使用可重复的隔离数据库环境。
- API 测试验证参数校验、鉴权、DTO 转换、HTTP 状态码和错误响应，不以 API 测试代替领域规则测试。
- 新增或修改聚合行为时，必须先补充或更新领域测试；跨服务事件变更必须同步更新契约测试和资源台账。

## 强制禁止项

- 禁止 `domain` import `infra`、`api`、MyBatis、Redis、RocketMQ、Spring、Feign、RestClient、数据库驱动。
- 禁止 Controller 调用 Repository、Mapper、缓存、MQ 或执行事务。
- 禁止 Application Service 导入 PO、Mapper、MyBatis Query Wrapper 或数据库表字段常量。
- 禁止 Repository 接口放置在 `infra`，禁止领域事件定义在 `infra.messaging`。
- 禁止 DTO、Command、Result、Entity、Value Object、PO 和 MQ/RPC Payload 互相替代或跨层直接返回。
- 禁止为持久化便利在领域实体添加 ORM 映射注解，禁止使用 Lombok `@Data` 弱化聚合封装。
- 禁止在业务事务中直接发送 MQ，禁止依赖“写库成功后立即发送消息”的 best effort 方式。
- 禁止把服务特有枚举、实体和业务工具放入 `sdk/common` 或全局 `common` 包。

## 规范适配与改造分离

- 本文只定义通用 DDD 分层原则、目录模板、依赖方向、对象边界和验收要求，不记录具体项目的历史包路径、迁移顺序、存量违规或未完成事项。
- 采用本规范的工程必须另行形成改造方案，说明现有目录与依赖图、目标分层、迁移批次、兼容策略、数据迁移、风险、回滚和验收证据。
- 本文的目录、对象和领域案例仅用于解释规则；命名可按组织语言和业务语义调整，但不得突破依赖方向与对象隔离约束。

## 新建业务服务检查清单

- 创建满足构建规范的契约与部署模块，并遵循项目选用构建工具的版本和依赖边界。
- 明确限界上下文、聚合根、权威数据表、跨服务契约、领域事件和数据所有权。
- 建立 `api`、`application`、`domain`、`infra` 目录，先定义领域模型和 Repository 契约，再实现 Application Service 与基础设施适配器。
- 为聚合不变量、状态迁移和领域事件建立领域单元测试；为应用用例建立编排测试。
- 增加数据库迁移、服务文档、MQ 资源台账和依赖图校验；执行项目定义的完整构建、测试和 IDE 诊断检查。

## 服务域目录模板

以下是**示例**，用于说明依赖方向与对象边界；命名可按组织语言调整，但不得突破分层与隔离约束。

```text
<context>/
├── <domain>-api/                    入站契约：DTO、接口定义、错误码；不得依赖 Spring/持久层
└── <domain>-service/
    ├── api/                         入站适配：Controller、过滤器、请求/响应转换
    ├── application/                 用例编排与事务边界；只依赖 domain 与 api(DTO)
    ├── domain/
    │   ├── model/                   聚合根、实体、值对象；不变量在此实现
    │   ├── repository/              仓储契约（接口）
    │   └── event/                   领域事件定义
    └── infra/
        ├── persistence/             PO、Mapper、仓储实现（实现 domain 契约）
        ├── mq/                      事件发布/消费适配
        └── client/                  外部服务调用适配（只依赖对方 *-api）
```

## 可验证的验收标准

一个服务域**达到完成定义**（Definition of Done）须同时满足：

| # | 判据 | 证据形式 |
| --- | --- | --- |
| 1 | 依赖方向无违反：`api`/`application`/`domain` 不依赖持久层实现与 Spring 运行时 | 服务依赖图门禁通过 |
| 2 | `domain` 不依赖 Spring / MyBatis / Redis / MQ 等基础设施 | 同上（门禁断言） |
| 3 | 聚合不变量与状态迁移有领域单元测试 | 测试报告，用例逐条对应不变量 |
| 4 | 应用用例有编排测试（含事务与事件发布断言） | 测试报告 |
| 5 | 跨服务调用只经对方 `*-api` 或版本化事件 | 依赖图 + 代码审查记录 |
| 6 | 新增表已登记前缀、迁移脚本符合 Flyway 约定 | Flyway 门禁通过 |
| 7 | 服务文档、MQ 资源台账、OpenAPI 制品已同步 | 相应门禁与制品差异为空 |

**通过标准**：上表逐项有可复现证据，且统一工程校验入口在该范围退出码为 0；不得以"仅编译通过"或"仅单测通过"替代。
