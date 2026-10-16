# 规范遵守情况审查（2026-10-16）

本文是**一次性的审查记录**，不是规范。规范正文在 `docs/standards/`；门禁目录在
`docs/standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md` §领域规范门禁。

审查方法：以 **15 份规范文档 + 27 个校验器脚本源码 + 根/域/叶子 POM + CI 接线** 为对象，
逐条把规范条文映射到实际生效的门禁。**每一条结论都回到源码或实测**，不依据门禁名称推断。
标注为"已确证"的条目，我自己复算过命令与计数。

## 1. 结论摘要

| 维度 | 状态 |
|---|---|
| 27 个门禁运行状态 | **全部 exit=0，违规 0**（阻断模式，无豁免） |
| 全量 `clean verify` | **109 模块通过，主代码 javac 告警 0** |
| 门禁**覆盖**（条文 → 门禁） | **大量条文无门禁兜底**；3 份规范自带的"可自动化门禁表"共 **13 个门禁声明，0 个实现** |
| 门禁**有效性** | 发现 **2 个门禁抓不住它自己存在的理由**（已修，见 §3） |
| 评审**在产违规** | 规范禁止、门禁未拦、代码确实如此——见 §4 |

**一句话**：门禁"全绿"是真实的，但它衡量的是**已实现的那部分规则**；
规范里还有相当一部分规则**从未被任何门禁衡量过**，其中若干条**当前正在被违反**。

## 2. 已修复：两个"不会为它存在的理由而失败"的门禁

这类缺陷比缺失门禁更危险——它给出**虚假的安心**。两者都用**让它们真的失败**来确认，而不是读代码。

### 2.1 `text-encoding` 抓不住它为之而生的乱码事故

原实现：把行按 GBK 重编码再用**会抛异常**的 UTF-8 解码器解回；对正常行，重编码得到的字节通常**不是合法 UTF-8**，
于是 `catch { continue }` 把整行**跳过**——跳过的正是**携带证据的行**。另有 Private Use Area 前置条件，
而真实乱码只有一部分命中该区间。

重放规范描述的事故（4 行普通中文）：

| 原文 | 旧实现 | 修复后 |
|---|---|---|
| 数据库迁移脚本 | 漏掉 | 命中 |
| 发送消息成功, msgId={} | 漏掉 | 命中 |
| 租户隔离必须显式声明 | 命中 | 命中 |
| 并发写入必须带版本条件 | 漏掉 | 命中 |

修复后**全仓 2465 个文件仍 0 误报**，不是用噪音换检出。

**已知限制（诚实记录）**：规范举例的孤立单字 `鎺`/`娑`/`濞` **两种实现都抓不住**——
单字重编码后无法产生"≥2 个连续 CJK"，这是**举例本身的限制**，不是检测的缺陷。

### 2.2 `flyway-immutability` 看不到 3 个迁移

pathspec 原为 `*db/migration/*.sql`，只匹配 **19/22** 个迁移文件；
`common-audit-service/src/main/resources/db/migration-audit/` 的 3 个文件在路径之外，
**在 `v2.2.0` 冻结之后可以被改写而没有任何东西失败**。

改为 `*db/migration*/*.sql`，与 `flyway-conventions` 按目录名前缀发现迁移根的做法一致。
验证：向此前不可见的文件追加一行 → 门禁失败并**指名该文件**；还原 → 通过。

## 3. 门禁的表层盲区（可廉价补齐）

| 门禁 | 盲区 | 实测 |
|---|---|---|
| `port-contract` | 正则 `/domain/(repository\|port)/` **匹配 0/39** 个实际存在的 `domain/<ctx>/port/` 文件 | 39 个端口文件**完全没被扫描** |
| `po-containment` | 只按 `import` 匹配；**全限定名引用**可绕过 | 见 §5 注 |
| `java-fully-qualified-names` | 模式要求根包后 ≥2 个小写段，故 `java.util.Map` **不匹配** | 与上条组合成绕过路径 |
| `domain-model-purity` | 覆盖 `@Data` 但**不覆盖 `@Setter`**，而其自身注释正以"无约束的整对象 setter"为由 | 3 处 `@Setter` 在领域模型上 |
| `persistence-dependency-boundaries` | 禁用清单含 `mybatis-plus-core/-extension/-annotation/mybatis-spring`，**缺 `mybatis-plus-jsqlparser-4.9`** | 13 个模块正在声明 |
| `service-dependency-graph` | 只读 `<dependencies>`，**从不读 `<dependencyManagement>`** | 经 dependencyManagement 的跨服务实现依赖不可见 |

## 4. 已确证的在产违规（规范禁止 + 门禁未拦 + 代码事实）

每条我都用 `git grep` 复算过。

| # | 规范 | 事实 | 规模 |
|---|---|---|---|
| 1 | 10 §应用服务规则：事务**不得**定义在 Controller | `@Transactional` 出现在控制器方法上 | **12 处**：`IamController`×5、`OrderItemController`×2，以及 `TenantConfigController`/`KtvBusinessHoursController`/`InternalPricingPlanController`/`InventoryRecoveryAdminController`/`ChannelConfigController` |
| 2 | 10 §聚合装配：审计字段**不属于**领域模型 | 领域 `model` 里声明 `createdBy/createdAt/updatedBy/updatedAt` | **21 个文件**（im-conversation / im-message / im-user 等域） |
| 3 | 10 §聚合与实体：不得用无约束整对象 setter | 领域模型带 `@Setter` | **3 处**：`AuditLog`、`SmsSendLog`、`MailSendLog` |
| 4 | 16 §2 + §门禁表"字面量扫描" | 业务代码硬编码币种字面量 | `PaymentChannelTransactionRecorder`、`AlipayChannel`×3、`WechatChannel`×6、`CurrencyResponseHeaderFilter` |
| 5 | 21 §版本与依赖治理 + 20 §内部依赖边界 | 业务模块直接声明 `mybatis-plus-jsqlparser-4.9` | **13 个模块** |
| 6 | 10 §乐观锁判据：无判定的 `version` 是装饰 | `MemberRepository` 源码注释**自认**"写路径不按版本判定" | 1 处（另有若干需逐 PO 核实） |

### 4.1 关于乐观锁的**精确**结论

- `@Version` 真实（非注释）使用数 = **0**；`OptimisticLockerInnerInterceptor` 真实注册数 = **0**。
  （`git grep` 表面计数 18/13 全部落在注释里——**包括我为写判据而加的注释**。）
- 25 个 PO 带 `version` 字段。
- **但这不等于 25 个都是装饰性**：本仓的实际并发保护是**显式 CAS**（`WHERE ... AND version = ?` + SQL 侧自增），
  本会话已为 order / customer / payment 等域补齐并加测试锁定。
- **判据应表述为**：*带 `version` 字段、却没有任何 CAS 谓词守卫的 PO* 才是装饰性缺陷。
  逐 PO 核实工作未完成，`MemberRepository` 是已确证的一处。

## 5. 规范声明但未实现的门禁（**最高价值的发现**）

多份规范在"门禁与人工检查项"里**列出了可自动化门禁**。其中：

| 规范 | 声明 | 实现 |
|---|---|---|
| `16_CURRENCY_CONVENTIONS.md:186` | **"必须进统一校验入口，不得选择性跳过"** 的 5 个门禁（字面量扫描 / 符号表唯一 / 缺省口径 / 快照不回改 / 写穿一致） | **0 个** |
| `13_PLATFORM_MEDIA_CONVENTIONS.md` §10 | 5 个可自动化项 | **0 个** |
| `40_OBSERVABILITY_CONVENTIONS.md` §门禁 | 3 个可自动化项（日志脱敏 / 关联标识 / 指标口径） | **0 个** |
| `43_E2E_TESTING_CONVENTIONS.md` | 层级顺序、真机验证、一次性数据清理 | **0 个**（`scripts/verify/*` **没有任何流水线调用**） |

**这是规范层自己的诚实之处**：它写明了应该自动化什么。缺的是校验器层没有实现这些表。
`16` 尤其明确——它写的是"不得选择性跳过"，而当前是**全部跳过**。

### 5.1 附带发现

- **MQ 台账**：30 行仍标 `待核`；且 `im_message_command_read_v1` 用 `userId`、`recall` 用 `msgId`
  作顺序键，而 12 章要求消息链路以 `conversationId` 为顺序键。`mq-registry` 只比对**名称**，不校验语义列。
- **`00` 自身的索引**未列出 `13`/`16`/`43`/`14`/`15`/`22`；`documentation-links` 只检查根 README，
  不检查 `00` 的索引完整性。
- **`artifactid` 与目录名不一致**：`common-services/media/pom.xml` 的 artifactId 是 `common-media`，
  目录是 `media`。
- **版本分散**：7 处硬编码 `2.2.0` 与 `sdk.version` 重复。
- **`maven-deploy-plugin` 无版本**（根 POM），从 Boot parent 继承——与"每个插件版本显式锁定"冲突。
- **POSIX `mvnw` 无锁且不读 `.mvn/jvm.config`**，而 CI 跑的正是 `./mvnw`；`mvnw.ps1` 两者都有。

## 6. 建议的处置顺序

按"**风险 × 廉价**"排序，每条都可通过**先写失败测试/变异**来验证门禁真的生效。

| 优先级 | 事项 | 类型 |
|---|---|---|
| P1 | 补 `mybatis-plus-jsqlparser*` 到禁用清单，并处理 13 个模块 | 门禁 + 改造决策 |
| P1 | 修 `port-contract` 的路径模式（39 个文件未扫） | 门禁修复 |
| P1 | 补 16 章的 4 个缺失门禁（至少"字面量扫描"，它本可拦住 §4 第 4 条） | 新增门禁 |
| P1 | 事务边界门禁（12 处在产违规） | 新增门禁 + 改造 |
| P2 | 审计字段/`@Setter` 领域模型门禁（21 + 3 处在产违规） | 新增门禁 + 改造 |
| P2 | 补 `service-dependency-graph` 读 `dependencyManagement` | 门禁修复 |
| P2 | 逐 PO 核实"装饰性 version"，并把判据写成门禁 | 审查 + 门禁 |
| P3 | 为规范声明的门禁表与实现建立**映射校验**，防止再次出现"声明 13 个、实现 0 个" | 元门禁 |
| P3 | MQ 台账语义列（顺序键/幂等键）校验，清掉 30 处 `待核` | 门禁 + 文档 |

## 7. 本报告**未**做的事

- **未修**上述在产违规。它们要么改变运行时行为（事务边界、审计字段），要么需要产品决策
  （13 个模块的 jsqlparser 去向），不属于"顺手改"。
- **未新增** §5 列出的门禁。新增门禁会把当前违规变成构建失败，顺序必须先于改造单独决定。
- **未修改**任何规范正文。
