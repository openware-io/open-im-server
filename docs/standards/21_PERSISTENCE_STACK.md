# 持久层技术栈标准

## 目标与适用范围

**目标**：明确**权威数据的归属**与持久层技术选型边界，使"哪份数据以什么为准"没有歧义，并让持久层依赖可被门禁约束。

**适用范围**：所有持有持久化职责的服务（关系库服务、消息热投影服务、缓存使用者）及其依赖声明、SQL 写法与迁移脚本。

**非目标**：不规定业务表结构设计（属各领域规范）；不规定迁移的版本编排流程（见 Maven 与迁移相关规范）；不解决跨域 Mapper 与实体复制问题（列为后续专项治理）。

## 术语与前置假设

| 术语 | 定义 |
| --- | --- |
| 权威库 | 业务事实与权威写入的唯一来源。本工程为 MySQL。 |
| 热投影 | 为查询性能而生的派生数据，可重建，不承载权威事实。 |
| 热点状态 | 短生命周期、可失效的状态；本工程由 Redis 承担。 |
| 接入层 | 只做接入、协议转换与命令投递的组件，不拥有持久层职责。 |
| 迁移基线 | 合并后的单文件起始迁移；后续增量从次版本起。 |
| 物理外键 | 数据库层面的外键约束；本工程在业务表中禁止声明。 |

**前置假设**：Spring Boot Parent 是 Boot 生态依赖的版本来源；服务按部署形态声明对应 Starter；迁移脚本受版本控制且不被回改。

## 权威数据与职责边界

- MySQL 是系统唯一权威库，业务事实、关系数据与权威写入均以 MySQL 为准。
- MongoDB 仅承担消息热投影，不得承载权威业务事实或替代 MySQL。
- Redis 仅承担热点状态，不得承载需要长期保存或可追溯的权威数据。
- 接入层只负责接入、协议转换和命令投递，不拥有权威持久层；不得将持久层职责迁移到接入层。

## 关系型主栈

- MyBatis-Plus 是关系型持久层主栈，统一版本为 `3.5.17`。
- 持有关系型实体与 Mapper 的业务关系库服务必须使用 `mybatis-plus-spring-boot4-starter`。
- 业务关系库服务必须以运行时依赖声明 `mysql-connector-j`。
- 持有 `src/main/resources/db/migration` 迁移脚本的服务必须追加 `spring-boot-starter-flyway` 与 `flyway-mysql`，并在配置中明确启用迁移能力。

## 版本与依赖治理

- Spring Boot Parent 管理 Spring Boot、MySQL 与 Flyway 的版本。
- 父 POM 仅管理 MyBatis-Plus 等 Spring Boot BOM 外依赖的版本；MyBatis-Plus 版本为 `3.5.17`。
- 业务模块禁止直接声明 MyBatis-Plus 底层组件，包括 `mybatis-plus-core`、`mybatis-plus-extension`、`mybatis-plus-annotation` 与 `mybatis-spring`。
- `mybatis-plus-jsqlparser-*` **不在此禁令内，且分页模块必须显式声明它**：MyBatis-Plus 3.5.17 的
  `mybatis-plus` 聚合 POM 把它标为 `<optional>true</optional>`，因此**不会传递**；而分页插件
  （`PaginationInnerInterceptor`/`selectPage`）在运行时会委托它解析 SQL，只做类型收敛不做分页的服务可省略。
  它与上一条列举的组件性质不同——那些是持久层实现本身，这是分页扩展所需的 SQL 解析器，
  声明它不构成"绕过 Starter 直连底层实现"。**版本仍由根 POM 的 `dependencyManagement` 统一管理**，
  业务模块只声明坐标、不写版本，这正是"禁止散落版本"这一条要守的东西。
  （2026-10-16 修订：此前该组件被列入禁令，与框架要求冲突，详见 `docs/renovation/STANDARDS_COMPLIANCE_AUDIT_2026-10-16.md` §8.3。）
- 业务模块禁止直接声明 `flyway-core`；迁移能力必须通过 Flyway Starter 与 MySQL 适配器声明。
- 持久层依赖边界由 `scripts/validate/validate-persistence-dependency-boundaries.ps1` 校验，并接入统一验证入口。

## jsqlparser 使用边界

`mybatis-plus-jsqlparser-*` 是分页与租户行拦截所需的 SQL 解析器，不是持久层实现本身，但也不能被业务代码当作通用 SQL 工具使用。边界如下：

- **坐标唯一**：只允许 `com.baomidou:mybatis-plus-jsqlparser-4.9`；版本只由根 POM 的 `dependencyManagement` 管理，模块 POM 不写 `<version>`。
- **必须声明的场景**：模块配置 `PaginationInnerInterceptor`、调用 `selectPage`，或实现 `TenantLineHandler` 时，必须显式声明该坐标。
- **scope 必须是 compile（默认）**：`TenantLineHandler#getTenantId()` 的返回类型就是 `net.sf.jsqlparser.expression.Expression`，写成 `runtime` 会导致实现类编译失败。
- **import 边界**：`net.sf.jsqlparser.*` 只允许出现在 `**/config/**` 或 `**/infra/**/config/**` 的 MyBatis-Plus 扩展点；禁止出现在 `domain`、`application`、`api` 与普通业务 service 中，禁止用 JSqlParser 拼 SQL、改 SQL。
- **禁止其他变体**：禁止 `com.github.jsqlparser:jsqlparser`、`mybatis-plus-jsqlparser-common` 及其他 `mybatis-plus-jsqlparser-*` 主版本；升级主版本必须走全仓 ADR/评审。
- **禁止排除**：分页或租户拦截模块不得对 Starter 或该解析器做 exclusion。
- 依赖边界由 `scripts/validate/validate-persistence-dependency-boundaries.ps1` 校验：使用未声明、声明未使用、版本/scope 漂移、越界 import、变体坐标都会失败。

## SQL 使用约束

- 优先使用 MyBatis-Plus 提供的安全表达能力实现关系型读写。
- 原生 MyBatis 注解 SQL 仅可用于 MyBatis-Plus 无法安全表达的场景，例如包含行锁的特定 SQL。
- 原生注解 SQL 必须保持明确字段清单、清晰事务边界，并使用中文说明采用原生 SQL 的业务原因和并发语义。

## Flyway 脚本可读性

- 新建表必须声明中文表注释；字段必须声明中文业务语义注释。
- 主键、唯一键和普通索引必须使用稳定命名，并对唯一性、幂等性或查询路径等业务目的添加中文注释。
- 本项目禁止在业务表中声明物理外键；关联完整性、级联行为与删除策略必须由 Java 领域/应用逻辑显式维护。关联列仍须按查询路径建立普通索引或唯一约束，且在字段中文注释中说明关联对象。
- 初始化基线和后续增量迁移均适用本规则；已在任何环境执行的 Flyway 脚本不得仅为补充注释而修改，必须以新的增量迁移演进。
- 脚本必须使用 UTF-8 无 BOM 编码，并在改动后通过项目文本编码与迁移边界校验。

## 后续专项治理

- 本轮不解决跨域 Mapper、实体复制或数据所有权问题；这些问题列为后续专项治理范围。
- 不得以本规范为由，将业务持久层职责迁移到接入层。

## 依赖声明模板

**按服务形态声明依赖（示例，版本以父 POM 管理为准）**：

```xml
<!-- 持有关系型实体与 Mapper 的服务 -->
<dependency>
  <groupId>com.baomidou</groupId>
  <artifactId>mybatis-plus-spring-boot4-starter</artifactId>
</dependency>
<dependency>
  <groupId>com.mysql</groupId>
  <artifactId>mysql-connector-j</artifactId>
  <scope>runtime</scope>
</dependency>

<!-- 仅持有迁移脚本的服务，额外追加 -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-flyway</artifactId>
</dependency>
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-mysql</artifactId>
</dependency>
```

**不要声明**：`mybatis-plus-core`、`mybatis-plus-extension`、`mybatis-plus-annotation`、`mybatis-spring`、`flyway-core`（底层组件由 Starter 传递引入）。

**需要声明**：`mybatis-plus-jsqlparser-4.9`（分页或租户行拦截模块）——它被 Starter 标为 optional，不声明则分页插件运行时缺解析器；版本仍由根 POM 管理，模块只写坐标，scope 保持 compile。

## 门禁与人工检查项

**可自动化**：

| 门禁 | 判据 |
| --- | --- |
| 依赖边界 | 业务模块未直接声明被禁止的底层持久层组件；jsqlparser 仅在需要时声明，版本/scope/import 合规 |
| 迁移存在性 | 持有 `db/migration` 的服务同时声明了 Flyway Starter 与 MySQL 适配器 |
| 迁移不可回改 | 已在任何环境执行的迁移未被修改（冻结后只增不改） |
| 脚本编码 | 迁移脚本为 UTF-8 无 BOM |
| 表注释与索引命名 | 新建表有中文表注释、字段有中文语义注释、索引命名稳定且带目的注释 |

**人工检查项**：

1. 原生注解 SQL 是否确为 MyBatis-Plus 无法安全表达的场景，并说明了业务原因与并发语义。
2. 是否在业务表中声明了物理外键（应改为 Java 层显式维护 + 关联列索引）。
3. 读取 Redis/MongoDB 的代码路径是否误把派生数据当权威事实使用。

## 可验证的验收标准

| # | 判据 | 证据形式 |
| --- | --- | --- |
| 1 | 持久层依赖边界门禁通过（无被禁底层组件直接声明） | `validate-persistence-dependency-boundaries.ps1` 通过 |
| 2 | 权威、派生、缓存三类数据的归属与本规范一致 | 代码审查记录 |
| 3 | 新建表与字段有中文注释；索引命名稳定且注明业务目的 | 迁移脚本片段 |
| 4 | 业务表无物理外键；关联列有索引或唯一约束 | 迁移脚本与 `information_schema` 查询 |
| 5 | 已执行迁移未被回改；变更以新增增量迁移实现 | 冻结门禁通过 / 迁移 diff |
| 6 | 脚本为 UTF-8 无 BOM 并通过编码与迁移边界校验 | 编码门禁通过 |

**完成定义**：上表逐项有可复现证据，且统一工程校验入口在该范围退出码为 0。

