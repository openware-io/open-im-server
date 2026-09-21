# 持久层技术栈标准

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
- 业务模块禁止直接声明 MyBatis-Plus 底层组件，包括 `mybatis-plus-core`、`mybatis-plus-extension`、`mybatis-plus-annotation`、`mybatis-plus-jsqlparser-4.9` 与 `mybatis-spring`。
- 业务模块禁止直接声明 `flyway-core`；迁移能力必须通过 Flyway Starter 与 MySQL 适配器声明。
- 持久层依赖边界由 `scripts/validate/validate-persistence-dependency-boundaries.ps1` 校验，并接入统一验证入口。

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
