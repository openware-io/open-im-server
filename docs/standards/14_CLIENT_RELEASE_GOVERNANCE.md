# 客户端发布与版本治理规范

[中文](14_CLIENT_RELEASE_GOVERNANCE.md) | [English](14_CLIENT_RELEASE_GOVERNANCE.en.md)

## 1. 目的与适用范围

本规范约束消费者客户端在 Android、iOS、Windows、macOS、Linux 的版本命名、构建、制品、渠道、灰度、最低支持版本、更新校验与审计。它适用于客户端、PC 管理后台、CI/CD 与分发存储；不改变领域业务 REST/WS 协议的版本策略。

“PC 客户端”仅是产品分组，不是发布平台。Windows、macOS、Linux 必须分别管理版本、制品、最低支持版本和发布节奏。

## 1.1 术语与前置假设

| 术语 | 定义 |
| --- | --- |
| 受管平台 | `android`、`ios`、`windows`、`macos`、`linux`；新增平台须完成完整评审，禁止用现有平台冒充。 |
| 渠道（channel） | `internal`（研发与自动化验证）、`beta`（受控测试）、`stable`（正式用户）。已安装客户端不可自动切换渠道。 |
| 展示版本 | `MAJOR.MINOR.PATCH`；`MAJOR` 为产品级不兼容变更，`MINOR` 为兼容功能发布，`PATCH` 为兼容修复。 |
| `buildNumber` | 正整数，在同一 `platform + channel` 内严格递增；服务端只比较它，不比较字符串版本。 |
| Artifact | 一份不可覆盖的制品记录：SHA-256、字节大小、下载 URL、包类型、目标架构与签名元数据。 |
| Release | 面向某 `platform + channel` 的一次发布记录，先草稿后发布，审计只追加。 |
| 灰度 | 按 `installationId` 稳定哈希命中的分比例放量；比例只上调。 |
| 最低支持版本 | 低于该阈值的客户端被判定为 `unsupported_client`。单独治理，需预留支持与回滚窗口。 |
| 未签名发布 | 仅限开源本地、操作者显式选择：`status=unsigned`、`source=open-source-build`、`installable=false`，不得进入公网生产渠道。 |

**前置假设**：服务端具备发布记录与审计模型；`installationId` 由客户端随机生成并安全保存；HTTPS 在正式环境始终可用。

**非目标**：不定义领域业务 REST/WS 协议版本策略；不定义发布审批的组织流程（只约束其系统行为）。


## 2. 平台、渠道与版本

### 2.1 平台

受管平台固定为 `android`、`ios`、`windows`、`macos`、`linux`。新增平台必须同时完成枚举、数据校验、安装/更新安全模型、制品规则、客户端实现、E2E 与管理端展示评审，禁止用现有平台冒充新平台。

### 2.2 渠道

`internal` 用于研发与自动化验证，`beta` 用于受控测试，`stable` 面向正式用户。渠道不可在已安装客户端上自动切换；同一构建可分别进入多个渠道，但必须创建独立 Release 记录并保留各自审计。

### 2.3 版本号

1. 展示版本为 `MAJOR.MINOR.PATCH`。`MAJOR` 表示产品级不兼容变更，`MINOR` 表示兼容功能发布，`PATCH` 表示兼容修复。
2. `buildNumber` 为正整数并在同一 `platform + channel` 内严格递增。服务端只比较 buildNumber，不比较字符串版本。
3. 已发布的 platform、channel、version、buildNumber、Artifact 摘要、架构和包类型均不可修改或复用。
4. 后端服务 JAR/Maven 版本、客户端展示版本、构建号、REST/WS 协议版本是四个独立概念，禁止相互替代。

## 3. 制品安全与分发

1. 正式环境的所有直装制品必须经 CI 构建、正式签名，并记录 SHA-256、字节大小、下载 URL、包类型、目标架构和签名元数据。密钥库与口令只保存在本地或密钥服务，`android/key.properties` 与 `android/*.jks` 不得入库。开源本地环境缺少运营方密钥时，仅允许操作者显式选择未签名发布；制品必须记录 `status=unsigned`、`source=open-source-build`、`installable=false`，不得冒充可安装正式包，也不得进入公网生产渠道。禁止静默回退为 debug 签名。
2. 发布后不得覆盖同一路径下的二进制文件；首发采用内容不可覆盖的稳定分发地址，CDN 内部迁移不得改变该地址返回的内容摘要。需要变更二进制、摘要、架构、包类型或公开地址时必须创建新的 Release，不能修改已发布 Artifact。
3. Android 公网直装制品必须是经正式签名密钥签名的 APK；开源本地未签名包只用于验证构建、上传、版本治理和官网展示链路，不得引导用户安装。AAB 仅可作为向商店上传的 CI 产物，客户端更新决策使用 `google-play + storeUrl` 跳转。iOS 仅按 App Store/TestFlight 能力跳转；Windows 校验 Authenticode；macOS 必须代码签名及公证；Linux 明确一种首发包格式并提供摘要校验。
4. 正式环境的下载、安装和更新必须是 HTTPS。开源本地部署可通过默认关闭的显式配置，仅对白名单内的 loopback、RFC1918、链路本地或 IPv6 ULA 地址允许 HTTP；商店地址和公网地址始终必须 HTTPS。客户端不得执行服务端下发的 shell、PowerShell、AppleScript 或任意安装脚本。
5. 客户端在摘要、签名、公证或包类型校验失败时必须停止安装、保留当前可运行版本、记录脱敏诊断并允许用户重试。

## 3. 原则与允许禁止例外

**原则**：
1. **四个版本概念互不替代**：后端 JAR/Maven 版本、客户端展示版本、构建号、REST/WS 协议版本各自独立。
2. **制品不可变**：已发布 Artifact 的二进制、摘要、架构、包类型与公开地址均不可修改或复用；需要变更就新建 Release。
3. **决策只由服务端做**：客户端不得猜测 URL、版本规则或灰度比例，只信任与自身平台和架构匹配的服务端响应。
4. **审计只追加**：所有发布相关写操作留痕，不删除记录、不替换二进制、不倒推 `buildNumber`。
5. **灰度可上调不可改写**：降级走暂停或撤回后新建 Release，不改写已命中的用户集合。

**允许**：
- 同一构建分别进入多个渠道，但须各自建立独立 Release 记录并保留各自审计；
- 客户端在非强更状态下延后 `optional_update`，不影响本地可用业务；
- 开源本地环境在**显式配置**下对白名单地址（loopback、RFC1918、链路本地、IPv6 ULA）允许 HTTP。

**禁止**：
- 用现有平台冒充新平台；用用户 ID、手机号、硬件序列号、IP、地理位置或随机数作为灰度命中依据；
- 覆盖同一路径下的二进制，或修改已发布 Artifact；
- 静默回退为 debug 签名；未签名制品进入公网生产渠道或引导用户安装；
- 客户端执行服务端下发的 shell、PowerShell、AppleScript 或任意安装脚本；
- 以客户端上报版本作为业务鉴权依据；
- 在日志、审计、指标标签或错误上报中记录 access token、刷新 token、下载签名 URL、个人信息或原始硬件标识；
- 密钥库与 `android/key.properties` 入库。

**例外**：**无长期例外**。开源本地未签名发布是**显式选择的受限模式**（须记录 `status=unsigned`、`source=open-source-build`、`installable=false`），不是对"正式制品必须签名"的豁免；取得运营方密钥后必须回到 `RequireRelease` 正式发布。

## 4. 发布控制

1. 任何 Release 先创建草稿，完成制品校验、发布说明和协议兼容快照校验后才可排期或发布。双人审批只有在服务端具备相应角色与审计模型后才可启用，不能以客户端界面模拟。
2. 首发灰度从 `internal` 到 `beta` 再到 `stable`；stable 首次只能从低比例逐步上调。
3. 灰度采用 installationId 稳定哈希。不得以用户 ID、手机号、硬件序列号、IP、地理位置或随机数作为命中依据。
4. 灰度比例只能上调；需要降级时暂停或撤回后新建 Release，禁止直接改写已命中的用户集合。
5. 已发布 Release 发现故障时先暂停；确认严重问题时撤回。禁止删除记录、替换二进制或倒退 buildNumber。
6. 最低支持版本单独治理。设置前必须确认所有高风险业务和数据迁移都对该阈值之上的客户端可用，并预留支持与回滚窗口。

## 5. 客户端更新行为

1. 客户端启动、恢复前台、网络恢复和手动检查时调用统一 release-check；失败不能影响本地可用业务，除非本地已经处于强更/不支持状态。
2. `optional_update` 可延后；`mandatory_update` 和 `unsupported_client` 必须阻断进入业务主界面，但保留查看更新说明、下载、重试和退出路径。
3. 客户端必须安全保存随机 installationId；不允许使用账户 ID 或设备硬件 ID。卸载后重装生成新值可接受。
4. 客户端不得根据本地猜测 URL、版本规则或灰度比例；仅信任服务端响应中与自身平台和架构匹配的 Artifact。
5. 业务鉴权不可依赖客户端上报版本。服务端如需硬拦截，必须通过 Gateway 的统一策略实施并有独立发布计划。

## 6. 权限、审计与可观测性

1. 版本发布相关的创建、提交、灰度、暂停、恢复、撤回和最低支持版本变更仅限授权管理员；每次写操作均需 Idempotency-Key、请求 ID 和操作原因。
2. 发布审计记录只追加，至少包括操作者、时间、原因、变更前后状态、Release/Artifact 摘要和关联请求 ID。
3. 禁止在日志、审计、指标标签或错误上报中记录 access token、刷新 token、下载签名 URL、用户个人信息或原始硬件标识。
4. 必须按平台、渠道、构建号和 Release 观察检查成功率、下载率、校验失败率、升级后首次启动率、崩溃率及活跃版本分布。

## 7. 变更门禁、验收标准与发布模板

任一版本治理变更必须同步更新：Flyway 迁移、领域/应用/持久化代码、OpenAPI 快照、Gateway 路由、管理后台、客户端更新实现、CI 制品元数据、单元/集成/E2E 测试和运行手册。若需要退役旧表，Flyway 迁移仍遵循工程迁移规范：已执行版本只增不改，迁移脚本不直接 `DROP TABLE`；运行时切换、备份确认与退役物理清理分别留存证据。

发布到 stable 的最低验收为：对应平台可安装、可升级、可校验、可失败回退；release-check 五种决策正确；草稿/灰度/暂停/撤回均有审计；分发地址与制品摘要经过独立验证；监控和告警已可用。未通过任一项不得提升 stable 灰度比例。

### 7.1 发布记录模板（示例，字段不构成强制命名）

```text
platform=android  channel=stable  version=1.0.25  buildNumber=26
artifactId=<uuid>  sha256=<64-hex>  sizeBytes=<n>  packageType=apk  arch=arm64-v8a
signing={ status=signed|unsigned, source=ci|open-source-build, installable=true|false }
downloadUrl=https://<host>/download/<artifactId>
releaseNotes=<面向用户的更新说明>
decisions={ optional_update | mandatory_update | unsupported_client | up_to_date | store_redirect }
```

### 7.2 发布前检查清单（人工确认项）

1. 制品经 CI 构建、正式签名，摘要与大小已记录；密钥库与 `android/key.properties` 未入库。
2. 下载地址 HTTPS 且内容不可覆盖；需要变更二进制时新建 Release 而不是覆盖。
3. 灰度比例按 `installationId` 哈希命中，且本次只上调不回改。
4. 最低支持版本变更已确认高风险业务与迁移对该阈值之上客户端可用。
5. 审计含操作者、时间、原因、前后状态与关联请求 ID；日志与指标标签无 token、签名 URL 与个人信息。

## 8. 与其他规范的关系

| 关系 | 文档 | 说明 |
| --- | --- | --- |
| 被依赖 | [15_CLIENT_RELEASE_SKILL.md](15_CLIENT_RELEASE_SKILL.md) | 客户端发版 Skill 的规则来源（版本命名、渠道、签名、审计） |
| 关联 | [20_MAVEN_ENGINEERING_CONVENTIONS.md](20_MAVEN_ENGINEERING_CONVENTIONS.md) | 后端 JAR/Maven 版本治理；与客户端展示版本、构建号、协议版本是四个独立概念 |
| 关联 | [43_E2E_TESTING_CONVENTIONS.md](43_E2E_TESTING_CONVENTIONS.md) | 发布前的端到端验收与真实设备要求 |
| 引用 | [40_OBSERVABILITY_CONVENTIONS.md](40_OBSERVABILITY_CONVENTIONS.md) | 发布相关的日志、指标与脱敏共同原则 |
| 引用 | [ENGINEERING_RULES.md](../ENGINEERING_RULES.md) | 迁移规范与统一工程校验入口 |
