# Maven 版本治理实施记录

> 状态：生效
>
> 适用范围：根 POM、平台、网关、服务域与统一工程校验入口
>
> 最后核查：2026-07-31

## 目标

本次改造将 Maven 版本所有权从根 POM 的全局业务制品管理，调整为平台、网关和服务域分别负责的独立模型。根工程只承担全仓构建基线、第三方依赖、公共插件和质量门禁。

## 最终模型

```text
im-server
├── sdk
│   ├── common
│   ├── protocol-ws
│   ├── protocol-mq
│   └── infrastructure
├── im-gateways
│   ├── gateway
│   └── im-access-ws
└── im-services
    ├── im-user
    ├── im-message
    ├── im-conversation
    ├── im-admin
    └── im-order
```

- `im-server`：维护构建基线版本、Spring Boot 与组织批准 BOM、第三方依赖版本、插件版本和 Enforcer 门禁；禁止管理业务 API 或业务 Service 制品。
- `im-server` 仅维护全仓构建基线版本（仅 `MAJOR` 变化，作为聚合父版本）；平台、网关和各服务域制品不再继承同一业务版本。
- 平台、服务域和网关制品各自维护独立版本；跨端（后端 pom / 管理端 package.json / 客户端 pubspec）不再强制一致。
- `sdk` 是平台版本唯一来源；`common`、`protocol-ws`、`protocol-mq` 与 `infrastructure` 必须继承其版本。
- `im-gateways` 维护网关聚合版本；`gateway` 与 `im-access-ws` 是独立部署制品，必须显式声明各自版本。
- `im-user`、`im-message`、`im-conversation`、`im-admin` 各自维护唯一域版本；同域 API 与 Service 仅继承域父版本。
- 消费方域父 POM 显式管理跨域 API 兼容版本；禁止依赖其他业务域的 `*-service` 制品。

## 父版本解析约束

Maven 在解析子模块父 POM 前无法可靠继承普通自定义属性。因此，父 POM 的 `<version>` 必须保留显式且可解析的发布坐标，不能机械替换为业务域属性。叶子制品不声明自身版本，完全继承域父 POM 的版本。

例如，user 域使用一次命令同步域父 POM 与两个叶子模块的父版本：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\set-domain-version.ps1 -Domain user -Version 1.1.0
```

该脚本会更新 `im-services/user/pom.xml` 的版本以及 `im-user-api`、`im-user-service` 的 `<parent><version>`，随后执行版本所有权校验。两个叶子模块继承域父版本，因此会同步输出同一制品版本。平台、message、conversation 与 admin 域遵循同一模式。跨域 API 升级仅修改消费方域父 POM 的兼容版本属性，并执行契约与集成验证。

## 插件治理

根 POM 的 `pluginManagement` 统一锁定以下插件及通用配置：

- `maven-compiler-plugin`：Java 25、Lombok 注解处理。
- `maven-enforcer-plugin`：构建门禁版本。
- `maven-checkstyle-plugin`：静态检查版本。
- `spring-boot-maven-plugin`：可执行服务重打包版本。

可执行模块仅声明 `spring-boot-maven-plugin` 坐标启用重打包，不得在叶子模块漂移插件版本。

## 依赖收敛

启用 `requireUpperBoundDeps` 后，RocketMQ 5.3.1 的传递依赖与 Spring Boot 4 管理版本发生冲突。根 POM 已统一收敛为较高版本：

| 制品 | RocketMQ 传递版本 | 统一版本 |
| --- | --- | --- |
| `com.alibaba.fastjson2:fastjson2` | `2.0.43` | `2.0.58` |
| `com.squareup.okio:okio-jvm` | `3.4.0` | `3.16.1` |
| `com.google.errorprone:error_prone_annotations` | `2.14.0` | `2.41.0` |
| `org.javassist:javassist` | `3.21.0-GA`（`rocketmq-client -> rocketmq-remoting -> reflections`） | `3.32.0-GA` |

该处理通过根 `dependencyManagement` 明确最终选择版本，不使用全局依赖排除掩盖传递关系。

`javassist` 的旧 POM 仍引用 Java 8 的 `tools.jar`，会在 Java 25 的依赖收集阶段产生模型告警。根 POM 统一覆盖到 `3.32.0-GA`，并在 `validate` 使用 `bannedDependencies` 禁止 `3.31.0-GA` 之前的版本重新进入依赖树。该覆盖的退出条件是上游依赖链不再解析到失效 POM，并完成根 Reactor 构建与 RocketMQ 运行态回归。

## 强制门禁

根 Maven `validate` 阶段执行：

- JDK 25 与 Maven 3.9.16 最低版本检查。
- 重复 POM 依赖版本检查。
- 传递依赖上界检查。
- 传递依赖收敛检查。
- 禁止低于基线的受限传递依赖。
- Checkstyle 检查。

`scripts/validate/invoke-engineering-validation.ps1` 在 Maven 前串行执行 Maven 版本所有权、编码、密钥、服务边界、持久层、Flyway、RocketMQ、日志和弃用 API 等静态校验；`-Scope Full` 再执行根 Reactor `clean verify`。

## 验收证据

以下命令已在本地通过：

```powershell
.\mvnw.cmd -B -ntp validate
.\mvnw.cmd -B -ntp clean verify
```

2026-07-31 的根 Reactor `clean verify` 覆盖 25 个模块并全部成功；所有可执行模块均完成 Spring Boot repackage。依赖树确认 `javassist` 已由 `3.21.0-GA` 统一到 `3.32.0-GA`，且 Java 25 下不再出现该依赖的 `tools.jar` 模型告警。

## 发布顺序

正式发布时按依赖拓扑执行：

1. 发布构建基线父 POM。
2. 发布平台父 POM及其平台制品。
3. 发布被跨域消费的 API 制品。
4. 发布消费方服务域的 API 与 Service。
5. 发布网关制品。

每次发布后必须在不依赖 Reactor `relativePath` 的干净工作目录中验证 POM 与依赖坐标可从制品仓库解析。
