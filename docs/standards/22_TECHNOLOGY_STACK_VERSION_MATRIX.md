# 技术栈版本矩阵

## 目的与范围

本文是服务端仓库的技术栈版本清单。它用于评审依赖升级、排查兼容性问题和核对部署基线，不替代 Maven 的实际解析结果。

> 📐 **技术栈架构图**（客户端 → 接入 → 应用 → 数据 的全栈分层，含本项目实际采用的技术与版本）见[工程架构图集 §四](../business/ARCHITECTURE_DIAGRAMS.md)。此图**属项目材料**（含本项目技术选型），非通用规范正文；本文仍是**版本号的唯一权威来源**。

- 根 `pom.xml` 是第三方依赖和构建插件版本的唯一权威来源。
- Spring Boot Parent BOM 管理的依赖不在业务模块重复固定版本；其精确解析版本以当前 Parent 的 effective POM 为准。
- App、PC 和运维镜像的独立技术栈不在本表范围内，应在各自仓库维护对应矩阵。
- 版本变更必须同时更新本表、根 POM（或对应 BOM）并完成受影响模块验证。

## 术语与前置假设

| 术语 | 定义 |
| --- | --- |
| 版本权威来源 | 某个版本号的唯一声明位置。本工程为根 `pom.xml`（或其导入的 BOM）。 |
| BOM 管理 | 版本由父项或 BOM 决定，业务模块不重复固定。 |
| effective POM | Maven 合并继承与 BOM 后的最终生效模型；精确解析版本以它为准。 |
| 依赖收敛 | 整棵依赖树中同一依赖只解析到一个版本。 |
| 临时版本覆盖 | 与 BOM 或根声明不一致的显式固定版本，必须记账并有退出条件。 |
| 矩阵 | 本文的版本清单；它是**人读索引**，不替代 Maven 解析结果。 |

**前置假设**：构建使用仓库自带 Wrapper；根 `pom.xml` 可读；评审者能运行 `mvn help:effective-pom` 或 `dependency:tree` 核对精确版本。

## 构建基线

| 类别 | 技术/组件 | 当前版本 | 版本来源 | 说明 |
| --- | --- | --- | --- | --- |
| 语言运行时 | Java | 25 | 根 `pom.xml` 的 `java.version` | Maven Enforcer 要求最低 Java 25。 |
| 构建工具 | Maven | 3.9.16 | Maven Wrapper 与 `maven.minimum.version` | 使用 `./mvnw` 或 `mvnw.cmd` 执行构建。 |
| 构建父项 | Spring Boot Parent | 4.0.7 | 根 `pom.xml` Parent | 管理 Spring Boot、MySQL、Flyway 等生态依赖。 |
| 平台制品 | `sdk` 聚合与平台版本 | 2.2.0 | 根 `pom.xml` 的 `sdk.version` | `sdk/pom.xml` 是平台聚合 POM，平台制品版本由根 POM 的 `dependencyManagement` 统一管理（BOM 语义由根 POM 承担），服务域统一引用。 |
| 代码规范 | Checkstyle | 10.26.1 | `checkstyle.version` | 由 Maven Checkstyle Plugin 执行。 |
| 编译插件 | Maven Compiler Plugin | 3.14.1 | `maven-compiler-plugin.version` | 统一 Java 编译配置。 |
| 质量门禁 | Maven Enforcer Plugin | 3.6.3 | `maven-enforcer-plugin.version` | 校验 Java、Maven 和依赖收敛。 |
| 打包插件 | Spring Boot Maven Plugin | 4.0.7 | `spring-boot-maven-plugin.version` | 与 Spring Boot Parent 保持同版本。 |

## 服务端框架与通信

| 类别 | 技术/组件 | 当前版本 | 版本来源 | 说明 |
| --- | --- | --- | --- | --- |
| Web 框架 | Spring Framework / Spring Boot Starter | 由 Boot 4.0.7 BOM 管理 | Spring Boot Parent | 业务模块禁止覆盖已管理版本。 |
| Spring Cloud | Spring Cloud | 2025.1.2 | `spring-cloud.version` | 通过 `spring-cloud-dependencies` BOM 导入。 |
| Spring Cloud Alibaba | Spring Cloud Alibaba | 2025.1.0.0 | `spring-cloud-alibaba.version` | 通过 Alibaba BOM 导入。 |
| API 文档 | Springdoc OpenAPI | 3.0.1 | `springdoc.version` | WebMVC 和 WebFlux Starter 共用版本。 |
| WebSocket/网络 | Netty | 4.2.17.Final | `netty.version` | 跨模块网络基础版本。 |
| RPC | gRPC | 1.75.0 | `grpc.version` | 通过 `grpc-bom` 导入。 |
| 消息队列 | RocketMQ Client | 5.3.1 | `rocketmq.client.version` | 事件投递与消费客户端。 |
| 鉴权 | JJWT | 0.12.5 | `jjwt.version` | JWT 解析与签发。 |

## 数据、序列化与基础设施

| 类别 | 技术/组件 | 当前版本 | 版本来源 | 说明 |
| --- | --- | --- | --- | --- |
| 关系持久化 | MyBatis-Plus | 3.5.17 | `mybatis-plus.version` | 统一使用 Boot 4 Starter。 |
| MyBatis Spring 集成 | MyBatis-Spring | 4.0.0 | `mybatis-spring.version` | 仅由根依赖管理声明版本。 |
| 关系数据库 | MySQL Connector/J | 由 Boot 4.0.7 BOM 管理 | Spring Boot Parent | 服务模块以运行时依赖声明。 |
| 数据库迁移 | Flyway / flyway-mysql | 由 Boot 4.0.7 BOM 管理 | Spring Boot Parent | 仅持有迁移脚本的服务声明。 |
| JSON | Fastjson2 | 2.0.58 | `fastjson2.version` | 仅限已批准的兼容场景。 |
| HTTP 客户端 | Apache HttpClient | 4.5.13 | `apache-httpclient.version` | 受根 POM 统一管理。 |
| I/O | Okio JVM | 3.16.1 | `okio-jvm.version` | 受根 POM 统一管理。 |
| 字节码工具 | Javassist | 3.32.0-GA | `javassist.version` | 低版本由 Enforcer 禁止进入依赖树。 |
| 注解 | Lombok | 1.18.46 | `lombok.version` | 编译期可选依赖。 |

## 维护规则

1. 新增第三方技术栈前，先确认是否已被 Spring Boot、Spring Cloud、gRPC BOM 或根 `dependencyManagement` 管理。
2. 固定版本必须在根 `pom.xml` 集中声明；业务叶子模块不得复制版本号。
3. 升级 BOM、Java、MySQL/Flyway 或消息中间件时，必须评估兼容性，并运行受影响服务的 Reactor 测试和依赖收敛校验。
4. 由 BOM 管理的依赖如需精确版本，使用 `mvn help:effective-pom` 或 `mvn dependency:tree` 获取，不在本文手工猜测或写死。
5. 任何临时版本覆盖都必须记录原因、影响范围、退出条件和清理版本。

## 版本登记模板

**新增或升级一行时，按此格式登记（示例，措辞不构成强制）**：

```text
| 类别 | 技术/组件 | 当前版本 | 版本来源 | 说明 |
| --- | --- | --- | --- | --- |
| <类别> | <组件名> | <版本或"由 X BOM 管理"> | <根 pom.xml 的属性名 / Parent / BOM> | <为什么在此版本、可否被覆盖> |
```

**登记约束（示例应用）**：`版本来源`必须能定位到唯一声明位置；由 BOM 管理的行，`当前版本`写"由 <BOM> 管理"而不是写死一个具体号；`说明`给出该版本的可覆盖性（可否被业务模块覆盖）。

## 门禁与人工检查项

**可自动化**：

| 门禁 | 判据 |
| --- | --- |
| 单一权威来源 | 同一版本号在仓库中只有一个声明位置；业务叶子模块不复制版本号 |
| 依赖收敛 | 依赖树中同一依赖只有一个解析版本 |
| 禁止项 | 无 `latest`/`dev`/`dirty`/手工标签；无被 Enforcer 禁止的低版本进入依赖树 |
| 矩阵一致性 | 本文登记的受管版本与根 POM 属性一致（差异即为文档失真） |

**人工检查项**：

1. 新增技术栈是否已确认未被既有 BOM 管理（避免重复管理）。
2. 升级是否评估了兼容性，并跑过受影响服务的 Reactor 测试与收敛校验。
3. 临时版本覆盖是否记录了原因、影响范围、退出条件与清理版本。
4. `说明`列是否说明了该版本的可覆盖性，而非只写"最新版"。

## 可验证的验收标准

| # | 判据 | 证据形式 |
| --- | --- | --- |
| 1 | 本表登记的每个受管版本都能在根 POM 或 BOM 找到唯一声明 | 属性名与行号对照 |
| 2 | 精确解析版本以工具产出为准，未在本文写死由 BOM 管理的版本 | `help:effective-pom` 或 `dependency:tree` 输出 |
| 3 | 依赖收敛校验通过 | Enforcer 输出 |
| 4 | 版本变更同时更新了本表与根 POM（或 BOM） | 同一次变更的 diff |
| 5 | 受影响模块的 Reactor 测试通过 | 构建输出与退出码 |

**完成定义**：上表逐项有可复现证据，且统一工程校验入口在该范围退出码为 0。

## 关联规范

- [Maven 工程规范](./20_MAVEN_ENGINEERING_CONVENTIONS.md)
- [持久层技术栈标准](./21_PERSISTENCE_STACK.md)
- [DDD 服务工程规范](./10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)
