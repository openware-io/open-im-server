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
        └── java/com/gvchat/im/<domain>
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
- 聚合根负责维护不变量、状态迁移、成员增删、权限约束和领域事件收集，不得提供无约束的 `setXxx` 修改核心状态。
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

- 本文只定义通用 DDD 分层原则、目录模板、依赖方向、对象边界和验收要求，不记录具体项目的历史包路径、迁移顺序、存量违规或实施待办。
- 采用本规范的工程必须另行形成改造方案，说明现有目录与依赖图、目标分层、迁移批次、兼容策略、数据迁移、风险、回滚和验收证据。
- 本文的目录、对象和领域案例仅用于解释规则；命名可按组织语言和业务语义调整，但不得突破依赖方向与对象隔离约束。

## 新建业务服务检查清单

- 创建满足构建规范的契约与部署模块，并遵循项目选用构建工具的版本和依赖边界。
- 明确限界上下文、聚合根、权威数据表、跨服务契约、领域事件和数据所有权。
- 建立 `api`、`application`、`domain`、`infra` 目录，先定义领域模型和 Repository 契约，再实现 Application Service 与基础设施适配器。
- 为聚合不变量、状态迁移和领域事件建立领域单元测试；为应用用例建立编排测试。
- 增加数据库迁移、服务文档、MQ 资源台账和依赖图校验；执行项目定义的完整构建、测试和 IDE 诊断检查。
