# Maven 工程规范

## 目标与适用范围

本规范适用于本仓库全部 Maven 模块、构建脚本、发布脚本和 CI 校验入口。

- 构建必须可复现：固定 Maven、JDK、插件和依赖版本，不依赖开发者本机的隐式环境。
- 模块边界必须表达部署边界：共享技术可复用，业务实现不得跨服务 Maven 依赖。
- 版本、依赖、插件和质量门禁必须集中治理，任何例外必须可说明、可校验、可清理。
- Maven Reactor 成功不等同于功能完整；不得以排除生产源码、跳过关键校验等方式制造“假绿”构建。

## 工程拓扑

```text
im-server
├── platform
│   ├── common
│   ├── protocol-ws
│   ├── protocol-mq
│   └── infrastructure
├── gateways
│   ├── gateway
│   └── im-access-ws
└── services
    └── <domain>
        ├── <service>-api
        └── <service>-service
```

- 根工程 `im-server` 仅承担全仓聚合、公共构建基线、第三方依赖版本和公共插件治理，不承载业务实现，也不得成为业务服务制品的依赖入口。
- `platform` 是不可运行的平台 BOM 与技术契约集合，只允许放置跨业务复用的技术能力、协议、通用工具和基础设施抽象。
- `gateways` 放置可独立部署的 HTTP 网关与长连接接入网关；网关不拥有业务域权威数据，不得依赖业务 `*-service` 制品。
- 每个 `im-services/<domain>` 是一个服务域的聚合父模块，包含稳定 API 契约和唯一可运行的服务实现。
- `*-api` 仅包含跨服务稳定 DTO、命令、事件、客户端接口、错误码和契约版本信息；禁止放入实体、Mapper、Repository、业务实现、启动类及数据库迁移。
- `*-service` 是该业务域的唯一实现与部署单元，持有业务代码、配置、迁移、持久化模型和启动类；其他服务不得 Maven 依赖该模块。

## 版本治理

### 版本模型

本工程采用服务域独立版本模型。

- 根 `im-server` 的版本仅表达全仓构建基线版本，不作为全部业务服务的发布版本；根聚合和结构聚合 POM 保持纯 SemVer，不追加 `-SNAPSHOT`。
- `sdk/pom.xml` 维护平台 BOM 的唯一版本；平台内部模块继承该版本。
- 每个 `im-services/<domain>/pom.xml` 维护该服务域的稳定基础版本，保持纯 SemVer；开发分支的可部署叶子服务 Maven 版本使用对应基础版本的 `-SNAPSHOT` 形式，正式构建恢复为纯 SemVer。叶子版本迁移必须同步检查其父版本、依赖管理和构件坐标。
- `gateway` 与 `im-access-ws` 是独立部署单元，可在自身 POM 声明独立版本；如未来拆出专属网关父 POM，应由该父 POM 管理网关域版本。
- 同域 API 与 Service 的版本关系必须按实际发布边界保持一致；若叶子 API 与 Service共同作为一个服务域发布，开发阶段一起使用同一 `-SNAPSHOT` 基础版本，领域父 POM仍保持纯 SemVer。
- 禁止使用 `LATEST`、`RELEASE`、版本区间或动态版本作为项目依赖版本。

### 内部制品引用

- 子模块引用同一父模块管理的内部制品时，版本由父模块的 `dependencyManagement` 管理，叶子模块不得重复声明版本。
- 服务域导入 `sdk` BOM 时，必须使用明确的平台版本属性或由服务域父 POM统一管理；禁止在多个 POM 散落硬编码同一平台版本。
- 根 POM 禁止在 `dependencyManagement` 中登记业务 API 或业务 Service 制品，避免跨服务依赖绕过服务边界。
- 版本升级使用 Maven Versions Plugin，并在升级后检查 API 和 Service 的有效版本；开发分支叶子服务使用 `-SNAPSHOT`，正式构建使用同一基础版本的纯 SemVer：

```powershell
.\mvnw.ps1 -f im-services/user/pom.xml versions:set -DnewVersion=1.1.0-SNAPSHOT
.\mvnw.ps1 -f im-services/user/pom.xml versions:update-child-modules
.\mvnw.ps1 -f im-services/user/im-user-api/pom.xml help:evaluate -Dexpression=project.version -q -DforceStdout
.\mvnw.ps1 -f im-services/user/im-user-service/pom.xml help:evaluate -Dexpression=project.version -q -DforceStdout
```

## 依赖治理

### 第三方依赖

- Spring Boot Parent 是 Spring Boot、MySQL、Flyway 等 Boot 生态依赖的唯一版本来源；业务模块不得覆盖其已管理版本，除非完成兼容性评审并在根 POM 集中声明原因与退出计划。
- 根 POM 的 `dependencyManagement` 仅管理 Boot BOM 外、全仓或多模块复用的第三方依赖版本，以及组织批准的 BOM。
- 只被少数服务使用的技术依赖应在对应服务域父 POM 或服务模块声明，不得为了方便加入根 POM 默认依赖。
- 业务模块声明依赖时不得重复指定已经由父 POM 或导入 BOM 管理的版本。
- 必须使用最小依赖集合；不得为“以后可能使用”预置 starter、驱动、数据库迁移或测试框架依赖。
- `runtime`、`test`、`provided` 等 scope 必须表达真实运行时需求。数据库驱动通常使用 `runtime`，测试依赖必须使用 `test`。

### 内部依赖边界

- 业务服务可依赖平台模块、同域 API 和经批准的其他服务 API；禁止依赖其他业务服务的 `*-service`。
- `gateway` 与 `im-access-ws` 不得依赖任何业务 `*-service`，不得承载用户、积分、营销、消息权威写入等业务实现。
- 接入层不得声明 MyBatis-Plus、MySQL 驱动、Flyway 等关系型持久层依赖，除非其被重新定义为权威数据服务并完成架构评审。
- 业务模块不得直接声明 MyBatis-Plus 底层组件或 `flyway-core`；持久化与迁移依赖遵循 [21 持久层技术栈标准](./21_PERSISTENCE_STACK.md)。

## 插件与构建治理

- 所有构建插件必须显式锁定版本。插件版本和通用配置应定义在根 POM 的 `build.pluginManagement`，模块仅在需要启用插件时引用插件坐标。
- 可执行服务的 `spring-boot-maven-plugin` 通用配置应由服务或网关父 POM统一提供；叶子模块仅保留不可继承的例外配置。
- `maven-compiler-plugin` 必须统一配置 Java 发行版本、UTF-8 编码和 Lombok 注解处理器；禁止在业务模块自行漂移编译参数。
- 禁止通过 `maven-compiler-plugin` 的 `excludes` 排除 `src/main/java` 中的生产业务源码以维持构建通过。不能编译的代码必须迁移、修复或删除。
- 所有源码、资源和构建报告使用 UTF-8。根 POM 必须定义：

```xml
<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
<project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
```

- Maven Wrapper 是本仓库唯一推荐的 Maven 执行入口。开发、校验、启动和发布脚本均必须调用 `mvnw.ps1` 或 `mvnw.cmd`，不得直接依赖 PATH 中的 `mvn`。
- Wrapper、JDK 与 Maven Enforcer 的版本下限必须相互一致。当前 JDK 基线为 25，Maven 最低版本应由 Enforcer 明确校验。
- Wrapper、`.mvn/jvm.config`、终端和 Maven 日志必须统一使用 UTF-8；Windows PowerShell 5 调用 Wrapper 时必须显式设置控制台输入、输出编码，禁止接受乱码构建日志。
- 同一工作区不得并行执行包含 `clean` 的 Maven 构建。`clean` 会删除 Reactor 前置模块的 `target/classes`；并发构建可能造成后续模块读取到被另一构建删除的类文件，即使前置模块在当前日志中显示 `SUCCESS`。Wrapper 必须对同一工作区的 Maven 调用串行化。

## 质量门禁

### 必须由统一工程校验入口执行

- `requireJavaVersion`：强制 JDK 25 或更高的项目批准版本。
- `requireMavenVersion`：强制与 Maven Wrapper 一致的最低 Maven 版本。
- `validate-maven-version-ownership.ps1`：确保根构建父版本、平台、服务域、业务叶子模块与网关制品遵循项目的独立版本所有权模型。
- `banDuplicatePomDependencyVersions`：禁止同一 POM 重复声明依赖版本。
- `requireUpperBoundDeps`：阻止低版本传递依赖因 Maven 最近路径规则覆盖高版本依赖。
- 对组织明确禁止的组件、过时 JDBC 驱动、错误日志实现和未经批准的持久层实现，使用 `bannedDependencies` 明确拒绝；当前禁止引入 `3.31.0-GA` 之前的 `org.javassist:javassist`。
- Checkstyle、文本 UTF-8、乱码、弃用 API、日志、异常日志、API 边界、持久层边界、Flyway 边界和 RocketMQ 契约校验必须作为统一验证入口的一部分。

`dependencyConvergence` 与 `requireUpperBoundDeps` 会暴露既有依赖树冲突。新增规则时应先在 CI 报告模式清理存量问题，再切换为强制失败；不得长期以全局排除规则掩盖冲突。

### POM 依赖图校验

源码扫描不能代替 POM 依赖图校验。统一验证入口必须额外检查：

- 不存在业务模块对其他业务 `*-service` 的直接依赖。
- 接入层不存在 MyBatis-Plus、数据库驱动和 Flyway 依赖。
- API 模块不存在 Spring Boot 可执行插件、数据库迁移、持久层 starter 与业务实现依赖。
- 有 `db/migration` 的服务必须声明 Flyway Starter 与 MySQL 适配器；没有迁移目录的模块不得预置 Flyway。
- 所有业务域导入的平台 BOM 版本符合批准的平台版本策略。

## 构建、验证与发布

### 标准命令

```powershell
.\mvnw.ps1 -B -ntp validate
.\mvnw.ps1 -B -ntp clean test
.\mvnw.ps1 -B -ntp -DskipTests install
.\mvnw.ps1 -B -ntp -pl im-services/user -am clean verify
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate\invoke-engineering-validation.ps1 -Scope Changed
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate\invoke-engineering-validation.ps1 -Scope Full
```

- `validate` 用于快速验证 Maven 模型、编码、风格与 Enforcer 规则。
- `test` 用于执行相关模块测试；不得以 `-DskipTests` 替代应执行的测试验证。
- `install` 用于模块关系、API 契约或依赖变更后同步本地仓库；它不是发布动作。
- `verify` 是提交和 CI 的完整构建生命周期目标；统一校验脚本必须在 Maven 构建前执行并传播失败退出码。
- `invoke-engineering-validation.ps1` 是工程门禁唯一入口：`Changed` 执行全部静态校验与 Maven `validate`，`Full` 在相同静态校验后串行执行根 Reactor `clean verify`。
- 构建日志统一写入 `.outputs/logs/build/<yyyyMMdd>/`，临时日志不得散落在仓库根目录。
- 当编译报错指向 Reactor 前置模块的 `target/classes` 且为 `NoSuchFileException` 时，必须先停止其他 Maven/IDE 构建任务，再通过 Wrapper 执行一次单一的 `clean verify -pl <module> -am`；禁止以复制 class、手工修改 `target` 或删除正常源码依赖的方式掩盖构建竞争。

### 发布要求

- 发布前必须执行根 Reactor 的完整校验，并对待发布服务域执行 `-pl <domain> -am clean verify`。
- 开发分支默认使用 Maven `-SNAPSHOT` 版本和可覆盖的 ACR `-SNAPSHOT` 镜像；只有用户明确要求正式包时才切换为纯 SemVer，并由发布脚本阻止正式 tag 覆盖。正式发布版本不得使用 Maven 的保留动态语义名称。
- `distributionManagement` 的 `server.id`、凭据注入方式、仓库权限、回滚方案和制品验收步骤必须在发布文档中说明，凭据不得提交到仓库。
- 发布后必须验证仓库坐标、POM、可执行 JAR、依赖元数据和服务启动结果；失败制品必须按仓库策略撤回或标记，不得静默覆盖。

## 规范适配与改造分离

- 本文只定义可复用的 Maven 原则、约束、模板、门禁与验收标准，不记录任何具体工程的存量缺陷、模块名称、整改优先级或迁移步骤。
- 具体工程在采用本规范前，必须单独编写“现状评估与改造方案”，将实际 POM、脚本、模块依赖图与本规范逐项比对，并记录范围、优先级、风险、回滚与验收证据。
- 工程案例只能用于说明规则的应用方式，案例中的组织名、模块名、版本号和路径不得被解释为规范强制要求。
- 改造方案完成后，应将可复用结论回写到本规范或相应专项规范；项目特有决策保留在改造方案和架构决策记录中。

## 变更检查清单

- 新增模块前，确认其属于平台、网关或业务服务域，并完成父子聚合关系。
- 新增依赖前，检查是否已有 BOM 或 `dependencyManagement` 管理；确认 scope、所有权与替代方案。
- 修改版本前，检查父版本、内部 BOM、发布管理器和消费方兼容性。
- 修改插件前，优先在根 `pluginManagement` 配置，并确认每个可运行模块的继承结果。
- 修改模块依赖、构建脚本、启动脚本或 POM 后，执行相关域 `verify`、根 Reactor `install`、Maven Reload 和 IDE Problems 检查。
- 提交前执行统一验证入口；任一门禁失败不得以跳过、排除或降低规则等级方式合并。
