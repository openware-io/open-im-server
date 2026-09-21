# im-user 域 DDD 改造方案

## 1. 文档定位

本文是 `im-user` 域从当前实现迁移至既定工程规范的项目专用改造方案，不是通用规范，不修改 [10 业务微服务 DDD 工程规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md) 与 [20 Maven 工程规范](../standards/20_MAVEN_ENGINEERING_CONVENTIONS.md) 的定义。

本文定义评估结论、目标状态、实施前置条件、迁移批次、兼容边界、验证和回滚策略，并记录实际实施状态。

## 2. 目标与非目标

### 2.1 改造目标

1. 保持 `im-user` 作为一个限界上下文、一个 API 契约模块和一个可部署 Service 模块。
2. 将 Service 内部收敛为 `api -> application -> domain <- infra`，使领域代码不再依赖 Spring、MyBatis-Plus、Jackson、Redis、MQ 或 HTTP 实现。
3. 隔离 HTTP DTO、应用 Command/Result、领域对象、持久化 PO 和跨服务契约对象，禁止直接互相返回或复用。
4. 保持 MySQL 权威数据、Flyway 迁移所有权、Outbox 可靠事件投递和对外接口的既有业务语义。
5. 在每个迁移批次前先建立可自动化的边界校验，避免“代码搬目录但依赖方向不变”。

### 2.2 非目标

1. 不在本方案中拆分 `im-user` 为多个独立微服务。
2. 不改变用户、好友、设备令牌、积分、贴纸等现有数据所有权。
3. 不借 DDD 改造调整 API 字段、HTTP 路径、认证语义、错误码、数据库表结构或 MQ Topic 语义。
4. 不在缺少独立规范时提前固化 MapStruct、OpenAPI、配置管理或测试门槛的全局规则。

## 3. 采用的规范

| 主题 | 采用标准 | 本方案中的作用 |
| --- | --- | --- |
| DDD 分层、对象隔离、服务域边界 | [10 业务微服务 DDD 工程规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md) | 目标包结构、依赖方向、Repository 契约、对象转换和测试分层 |
| Maven 聚合、依赖与发布 | [20 Maven 工程规范](../standards/20_MAVEN_ENGINEERING_CONVENTIONS.md) | API/Service 模块、局部 Reactor、依赖 scope、构建与发布验证 |
| MySQL、MyBatis-Plus、Flyway | [21 持久层技术栈标准](../standards/21_PERSISTENCE_STACK.md) | 权威库、持久化技术栈和迁移资源所有权 |
| 编码与批量编辑 | [30 文本编码与批量改写规范](../standards/30_TEXT_ENCODING_AND_BATCH_EDITING.md) | UTF-8 无 BOM、批量改动控制和乱码防护 |
| 日志与诊断 | [40 可观测性规范](../standards/40_OBSERVABILITY_CONVENTIONS.md)、[41 Java 日志规范](../standards/41_JAVA_LOGGING_CONVENTIONS.md) | 改造期间的日志、异常和诊断要求 |
| 项目硬性规则 | [工程规则](../ENGINEERING_RULES.md) | 编译、注释、弃用 API、编码和 IDE 诊断门禁 |

## 4. 当前基线

### 4.1 已符合且应保留的部分

| 项目 | 现状证据 | 结论 |
| --- | --- | --- |
| 服务域聚合 | [user 域父 POM](../../im-services/user/pom.xml) 只聚合 `im-user-api` 与 `im-user-service` | 符合服务域局部 Reactor，保留 |
| API 与部署制品 | [API POM](../../im-services/user/im-user-api/pom.xml) 与 [Service POM](../../im-services/user/im-user-service/pom.xml) 均继承 user 域父 POM | 符合同域版本与父继承边界，保留 |
| 持久化与迁移归属 | [Service POM](../../im-services/user/im-user-service/pom.xml) 声明 MySQL、MyBatis-Plus、Flyway；迁移由 Service 资源目录持有 | 符合权威数据服务职责，保留 |
| 服务职责 | [服务说明](../im-services/im-user-service/README.md) 已列出账户、用户资料、设备令牌、关系链、积分和贴纸 | 作为一个用户域内的子域集合保留，暂不拆服务 |

### 4.2 已确认的规范偏差

| 编号 | 优先级 | 偏差 | 证据 | 对应规范 | 改造方向 |
| --- | --- | --- | --- | --- | --- |
| U-01 | P0 | `User` 领域实体绑定 MyBatis-Plus 和 Jackson | 改造前历史实现，当前已迁移为纯领域聚合与持久化 PO | 10：领域层框架无关、对象隔离 | 拆分纯领域聚合与 `infra.persistence.po.UserPo` |
| U-02 | P0 | `domain.repository` 实际为 MyBatis Mapper，领域服务直接调用 `BaseMapper` 与 `Wrappers` | 改造前历史实现，当前已迁移为领域仓储契约与基础设施适配器 | 10：Repository 契约在 domain、实现和 Mapper 在 infra | 定义领域 Repository 接口；将 Mapper 与实现迁入 `infra.persistence` |
| U-03 | P0 | Controller 绕过 application，直接调用领域服务并返回领域实体 | 改造前历史实现，当前已由 API DTO 和应用服务承接 | 10：`api -> application -> domain <- infra`、对象隔离 | 引入内部 API DTO、Application Command/Result、应用服务与转换器 |
| U-04 | P1 | 服务私有 DTO、领域对象散落在全局 `com.gvchat.common` 与 `com.gvchat.im.domain` 包 | 改造前历史实现，当前已迁移至 `com.gvchat.im.user` 分层包 | 10：限界上下文目录与私有对象归属 | 迁移到 `com.gvchat.im.user` 下的分层包，收紧启动扫描范围 |
| U-05 | P1 | Maven API 契约 Command 被内部 HTTP Controller 直接用作请求 DTO | 改造前历史实现，当前内部请求 DTO 已与 Maven API 契约分离 | 10：Maven API 制品与 Service 内部入站 API 不可互相替代 | 建立内部请求 DTO 和应用 Command；保留 API Command 仅供跨服务契约 |
| U-06 | P1 | 现有领域/Controller Javadoc 出现损坏标签、缺字和不可读说明 | 改造前历史实现，已在迁移时一并修复 | 30、工程规则：UTF-8 与中文注释质量 | 随所属类迁移修复；不进行独立的大范围格式化 |
| U-07 | P2 | 测试以 Flyway 与架构检查为主，尚缺领域纯单测、应用用例测试和 HTTP 映射测试 | 改造前历史实现，当前实施状态见第 10 节 | 10：分层测试优先级 | 按迁移批次补齐，不以一次性覆盖率目标替代关键行为验证 |

## 5. 必须先补齐的规范缺口

以下事项尚未形成通用规范。它们不阻塞架构评估，但阻塞对应实施细节的冻结；在补齐前不得以 `im-user` 的局部实现替代全局决策。

| 缺口 | 对本次改造的影响 | 实施前需要冻结的最小决策 |
| --- | --- | --- |
| 对象转换与 MapStruct 规范 | PO、领域对象、DTO、Command/Result 需要稳定转换边界 | 是否采用 MapStruct；全局 Mapper 配置、注入模型、null 策略、枚举策略、禁止自动映射的敏感字段与手写转换条件 |
| Java 代码规范 | 领域对象的不可变性、异常表达、时间注入、日志和 Javadoc 质量尚无完整统一规则 | 聚合根是否允许 Lombok、时间/随机数/密码编码接口的抽象边界、异常到 API 错误的映射方式 |
| API 契约与兼容性规范 | 内部 HTTP DTO 与跨服务 API 契约拆分后，需要保证消费方兼容 | 公开契约版本、兼容检查、弃用窗口、错误码与 OpenAPI 生成规则 |
| 配置与密钥管理规范 | `infra` 配置、Security、Redis、MQ 和厂商推送配置迁移后需要统一管理 | Properties 归属、Profile、环境变量、敏感字段脱敏与运行时变更流程 |
| 测试策略规范 | DDD 分层测试需一致的门槛和测试替身原则 | 领域纯单测、应用测试、Repository 集成测试、HTTP 契约测试的最低集与 CI 门禁 |

## 6. 目标结构

```text
im-user-service/src/main/java/com/gvchat/im/user
├── ImUserServiceApplication.java
├── api
│   ├── controller
│   │   ├── account
│   │   ├── profile
│   │   ├── social
│   │   ├── device
│   │   ├── points
│   │   └── sticker
│   ├── dto
│   │   ├── request
│   │   └── response
│   └── converter
├── application
│   ├── account
│   ├── profile
│   ├── social
│   ├── device
│   ├── points
│   ├── sticker
│   ├── command
│   ├── query
│   └── result
├── domain
│   ├── account
│   ├── profile
│   ├── social
│   ├── device
│   ├── points
│   ├── sticker
│   ├── event
│   ├── repository
│   └── service
├── infra
│   ├── persistence
│   │   ├── po
│   │   ├── mapper
│   │   ├── repository
│   │   └── converter
│   ├── messaging
│   ├── cache
│   ├── security
│   └── config
└── common
    ├── exception
    └── util
```

### 6.1 子域边界

| 子域 | 核心职责 | 首批聚合候选 | 权威数据 |
| --- | --- | --- | --- |
| account | 注册、登录、凭证与账户注销 | `UserAccount` | 用户账户与凭证字段 |
| profile | 公开资料与个人资料修改 | `UserProfile` 或 `UserAccount` 的资料部分 | 用户资料字段 |
| social | 好友关系、好友请求、通知事件 | `FriendRelation`、`FriendRequest` | 好友关系和请求记录 |
| device | 设备令牌注册、启停与厂商路由信息 | `DeviceToken` | 设备令牌记录 |
| points | 积分余额、账本、充值和扣减 | `PointAccount`、`PointLedger` | 积分账户和流水 |
| sticker | 用户贴纸收藏与管理 | `UserSticker` | 用户贴纸记录 |

该划分仅用于同一 `im-user-service` 内的包、事务和测试组织。除出现独立数据所有权、独立扩缩容或独立发布节奏的证据外，不得据此直接拆分微服务。

### 6.2 依赖规则

```text
HTTP / 内部 HTTP DTO -> Application Command / Query -> Domain Aggregate / Domain Service
                                                              ^
                                                              |
MyBatis PO / Mapper / Repository Adapter / Redis / MQ / Security ---------- infra
```

1. `domain` 仅依赖 JDK、服务私有值对象、领域事件、领域 Repository 接口与领域服务接口。
2. `application` 负责事务、权限上下文协调、调用聚合、保存 Repository、收集 Outbox 事件；不直接调用 Mapper 或暴露 PO。
3. `api` 只做参数校验、认证主体提取、DTO 转换和调用 Application Service；不返回领域实体。
4. `infra.persistence` 持有 PO、Mapper、MyBatis-Plus 注解、查询包装器和 Repository Adapter；只在此层出现 MyBatis-Plus 类型。
5. Maven API 模块中的对象只用于跨服务稳定契约；内部 Controller 的 DTO、Application Command/Result 均属于 Service 模块。

## 7. 分批改造计划

### 批次 0：规范冻结与基线保护

#### 前置条件

1. 完成第 5 节中对象转换、Java 代码、API 契约与测试策略的最小决策，或为每项登记带到期时间的项目例外。
2. 为现有 API 建立行为基线：HTTP 状态、响应字段、错误码、鉴权要求、关键日志和数据库副作用。
3. 对 user 域执行局部 Reactor `clean verify`，保留构建与测试证据。

#### 新增门禁目标

1. `domain` 禁止导入 `org.springframework`、`com.baomidou`、`org.apache.ibatis`、`com.fasterxml.jackson`、Redis、RocketMQ 和 HTTP 客户端包。
2. `domain.repository` 禁止继承 `BaseMapper` 或标记 `@Mapper`。
3. Controller 禁止依赖 `domain.entity` 与 `domain.repository`。
4. API 模块禁止依赖 Service 模块、MyBatis、Flyway、可执行 Spring Boot 插件。

#### 验收

- 边界扫描可重复执行，且先以报告模式暴露存量问题；不得先改造再补门禁。
- 现有数据库迁移校验、API 边界校验、文本编码和注释质量校验保持通过。

### 批次 1：账户与资料垂直切片

#### 范围

- 迁移 `User`、资料查询/更新、修改密码、账号注销及相应 HTTP 接口。
- 引入纯领域模型、领域 Repository 契约、User PO/Mapper/Repository Adapter、Application Service、内部 DTO、Command/Result 与转换器。

#### 兼容要求

- `/users/me`、`/users/{id}`、`/users/search`、`/users/me/password` 与 `/users/me` 删除接口的路径、字段、错误码和授权语义保持不变。
- 密码哈希、软删除、脱敏、用户名唯一性、审计时间和事务语义保持不变。
- 不修改现有 Flyway 脚本；如果必须新增索引或约束，单独评审迁移并提供回滚说明。

#### 验收

- 领域纯单测覆盖资料更新、改密校验和注销状态转换。
- Application 测试覆盖事务内保存、异常映射和事件收集。
- Repository 集成测试覆盖 PO 与领域对象转换及关键查询语义。
- HTTP 映射测试确认外部契约未变化。

### 批次 2：好友关系与 Outbox 垂直切片

#### 范围

- 迁移好友、好友请求、关系变更事件、Outbox 写入与 Relay 调度。

#### 兼容要求

- 保持事件名称、版本、幂等键、顺序键和消费者可见 Payload 语义；遵循 [12 RocketMQ 治理规范](../standards/12_ROCKETMQ_CONVENTIONS.md)。
- 聚合收集领域事件，应用层在同一事务内保存聚合与 Outbox；`infra.messaging` 负责异步投递。

#### 验收

- 事务失败时不得出现孤立 Outbox 记录或已投递事件。
- 重试时保持幂等；现有消费者兼容性测试通过。

### 批次 3：设备令牌、积分与贴纸垂直切片

#### 范围

- 依次迁移 `DeviceToken`、积分账户/账本、用户贴纸；每次只迁移一个业务切片。
- 将积分内部 HTTP 请求 DTO 与 Maven API 契约 Command 分离。

#### 兼容要求

- 设备推送厂商路由、积分余额/账本计算、贴纸权限和数据库副作用保持不变。
- 内部管理接口保持对现有调用方兼容，契约变更须先遵循 API 兼容规范。

#### 验收

- 每个切片完成独立 `clean verify`，并执行相关 API/事件/持久化测试。
- Service 模块不残留业务私有 `com.gvchat.common.dto`、全局 `com.gvchat.im.domain` 或 MyBatis 注解的领域对象。

### 批次 4：收尾、收敛与发布验证

#### 范围

- 删除完成迁移后无引用的旧包、旧 DTO、旧 Mapper 适配层和临时兼容代码。
- 修复迁移类的 Javadoc 损坏内容与不准确说明。
- 收紧启动类 Component Scan 至 `com.gvchat.im.user`，前提是所有本域组件已完成迁移且所需共享组件采用显式配置导入。

#### 验收

- 所有边界门禁从报告模式升级为强制失败。
- user 域局部 Reactor 与根 Reactor 完整校验通过。
- 发布前执行 API 契约兼容验证、迁移验证、服务启动验证与关键业务回归。

## 8. 风险、回滚与决策点

| 风险 | 控制措施 | 回滚原则 |
| --- | --- | --- |
| 包迁移导致 Spring Bean 漏扫 | 每个垂直切片后执行启动测试和上下文校验；收紧扫描放在最后 | 保留原扫描范围，修正显式配置后再收紧 |
| DTO/Result 转换改变 JSON 输出 | 先建立 HTTP 契约测试，转换器显式处理敏感字段、null 与枚举 | 保留旧 HTTP DTO 外观，内部实现回滚至上一适配层 |
| Repository 重写改变 SQL 行为 | 为排序、分页、软删除、唯一性和锁语义编写集成测试 | 在同一表结构下回退 Repository Adapter，不回滚已验证的数据迁移 |
| Outbox 迁移造成重复或漏投递 | 保持事件标识、幂等键和 Relay 重试语义；先双向验证再删除旧路径 | 回退事件适配层，消费者依旧依赖原版本化契约 |
| DDD 改造扩张为服务拆分 | 子域只用于服务内组织；新增独立部署单元必须另立架构决策记录 | 停止拆分，继续在单一 user 域内完成边界收敛 |

以下决策必须在实施前单独记录：

1. `UserAccount` 与 `UserProfile` 是同一聚合还是分离聚合。
2. 密码编码、时钟和随机数是领域端口还是应用/基础设施协作者。
3. 关系链、积分和贴纸是否跨聚合引用用户账户，仅保存 ID 还是需要防腐查询端口。
4. 内部管理 HTTP 接口是否是正式跨服务契约；若是，是否纳入 API 模块并进行版本治理。

## 9. 实施顺序与完成定义

1. 先补齐第 5 节的规范缺口或批准限时例外。
2. 执行批次 0 的行为基线和门禁建设。
3. 按批次 1 至 3 的单一垂直切片推进；任一批次未通过验收不得并行扩大范围。
4. 完成批次 4 后，更新 [服务说明](../im-services/im-user-service/README.md)、运行手册、测试证据和本方案的实际完成状态。

本方案完成不以“包名已迁移”定义，而以以下事实定义：领域层不含框架和中间件依赖；Controller 不暴露领域对象；持久化模型只存在于 `infra`；同一行为的接口、数据和事件契约已验证兼容；所有约定门禁与构建验证均通过。

## 10. 实施状态

### 10.1 已完成

1. 批次 1 已完成：账户与资料已迁移为纯领域模型、领域仓储契约、应用服务、API DTO 与基础设施持久化适配器；现有 HTTP 路径与响应字段保持兼容。
2. 批次 2 已完成：好友关系、好友申请与 Outbox 已迁移；领域层仅定义事件和端口，应用层在同一事务内协调关系与 Outbox 写入，基础设施层负责持久化和投递。
3. 批次 3 已完成：设备令牌、积分与贴纸已迁移至分层结构；积分查询不再隐式开户，余额变动使用账户行锁和溢出校验，贴纸通过名额表的条件更新保证每用户最多 200 条。
4. 贴纸名额表已合并至 `V1__init.sql` 初始化基线；确认 V1 尚未在任何环境执行，因此无需独立迁移或历史数据回填。
5. API 内部请求 DTO 与 Maven API 积分契约已分离；Controller 不再返回领域实体。

### 10.2 待完成

1. 批次 4：将全域分层约束、API 映射测试和持久化集成测试收紧为统一构建门禁。
2. 在具备 Maven Wrapper 与 Java 可执行环境的终端执行根 Reactor `clean verify`，并保留完整构建证据。
3. 评审内部管理积分 HTTP 接口的正式跨服务契约归属与鉴权策略。
