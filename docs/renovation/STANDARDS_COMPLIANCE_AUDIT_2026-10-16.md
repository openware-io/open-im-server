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

## 8. 处置进展与**顺序判断**（2026-10-16 更新）

### 8.1 已完成

| 违规 | 处理 |
|---|---|
| V4 `domain/<ctx>/port/` 39 个文件 | ✅ 迁入 `domain/<ctx>/repository/`，173 处引用更新，4 模块编译通过（`6b7e888`） |
| `port-contract` 作用域缺陷 | ✅ 改为 `-match '/domain/([^/]+/)*port/'` 并覆盖全部 Java 文件；**变异验证**：注入 `port/ProbePort.java` → 门禁指名失败 |
| `text-encoding` 乱码检测失效 | ✅ 改用非抛出解码器（`3723cbd`） |
| `flyway-immutability` 漏扫 3 个迁移 | ✅ pathspec 改为 `*db/migration*/*.sql`（`3723cbd`） |

### 8.2 **顺序判断：V1 + V2 + V3 必须一起做**

逐条复核后确认三者**相互耦合**，分开做会让同一段代码被反复重构：

| 违规 | 真实修法 | 与其它项的关系 |
|---|---|---|
| **V1** 12 处控制器 `@Transactional` | 把方法体搬进应用服务 | **不能只删注解**：12 处里 **2 处控制器直调仓储**（`IamController.createRole`、`OrderItemController.confirm`）、其余 10 处调**私有 helper**（`upsert`/`doDecide`/`save`）——业务逻辑确实在控制器里 |
| **V2** 21 个领域模型的审计字段 | 审计字段由**适配器或拦截器填充** | 填充点**就在 V1 搬进去的那个应用服务写路径上** |
| **V3** 3 处领域模型 `@Setter` | 补**领域变更方法**（`markSent()`/`markFailed()`） | `@Setter` 揭示的正是"**领域变更逻辑写在应用服务里**"（`SmsApplicationService` 的 `log.setStatus("SENT")`）——与 V1 同源 |

**具体反例（说明不能机械处理）**：`TenantConfigController.update` 标着 `@Transactional`，
看似"只委托服务"，实际调用私有 `upsert(...)` 做**两次写**——事务是**必需的**，
但按规范它属于应用服务的编排。删注解会**丢掉原子性**。

**证据**：`IamController` 的类注释自称"**控制器只做协议转换与审计**"，
而它注入了 **3 个 Repository** 并有 **5 个写方法**——**注释描述的是目标态，代码是违规态**。

### 8.4 执行进度（2026-10-16 第二次更新）

| 项 | 状态 | 备注 |
|---|---|---|
| `SmsSendLog` | ✅ 完成（`36ea0cd`） | 改不可变 record：去 `@Setter`、去审计字段、补 `markSent`/`markFailed` |
| `MailSendLog` | ✅ 完成（`36ea0cd`） | 同上 |
| `AuditLog` | ⏳ **需要传输类型** | 与前两者**不同**：{@code createdAt} 真的进响应（`AuditLogView:54`），所以适配器重建时仍须带回，必须引入 `Persisted<T>` 之类的载体，不能只挪适配器 |
| V2 主体（21 个 im-services 模型） | ⏳ | message 5 / user 12 / conversation 4；同样有"审计值是否进响应"的差异要逐模型判断 |
| V1（14 控制器 / 72 调用点） | ⏳ | 门禁 `controller-repository-boundary` 已在**报告模式**追踪，清单见 `.outputs/quality/controller-repository-boundary-worklist.txt` |

**已确证的可复用判据**（sms/mail 试点得出）：先问"**审计字段有没有读者**"。
`SmsSendLog`/`MailSendLog` 的 `save` 返回领域对象、`update` 返回 void，应用层只用 `id`/`status`，
所以审计值**无读者**，可纯下沉到适配器、**无需新类型**；`AuditLog` 有读者，则必须带载体。
这个判断决定了改动大小，应在动手前先做。

**写路径口径不可顺带改变**：两条路径列行为逐字保留——`save` 写两列、`update` 只刷 `updated_at`（创建时刻不可变）。

### 8.5 V2 主体的**真实规模**（2026-10-16 逐模型核实后）

对 21 个 im-services 模型逐个查"有无读者"，结论是**全部有读者**，而且比 `AuditLog` 更复杂：

| 服务 | 模型数 | 审计值的去向 | 现状 |
|---|---|---|---|
| conversation | 4 | **进响应**（`ChannelResponse`/`SecretChatResponse`/`SecretGroupChatResponse`），且仓储**已从 PO 就地取审计值** | 领域模型是 record，但把审计值当构造参数；需去掉参数、保留响应传参 |
| message | 5 | **进响应**（`MessageResponse`/`FavoriteResponse`） | 同上 |
| user | 12 | **进响应**（`UserProfileResponse`/`FriendResponse`/`DeviceTokenResponse`/`DeviceSessionResponse`/`FriendRequestResponse`/`UserStickerResponse`） | 同上 |

**为什么这不能像 sms/mail 那样一次做完**：每个模型要同时改
**领域模型（去掉 4 个构造参数）→ 每个仓储的 `toPo`/`toDomain` → 每个响应 DTO 的装配点 → 每个测试**，
共 21 × 4 类改动点。**这是多轮工作量，不是一次编辑**；我按服务逐个推进并逐个验证。

**conversation 的既有答案（可作模板）**：`MybatisSecretGroupChatRepository:83` 已经在写
`po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt()`——即**适配器直接从 PO 取审计值**，
不必穿过领域模型。修法就是把这条既有做法推广到其余 20 个模型，并把这些值改为经端口显式传给响应装配点。

**另一个被这次核实纠正的判断**：我先前按 `git grep -c` 统计"全局审计 getter 调用 = 222"，
由此差点得出"无读者"的结论——**222 是全局计数，对单个模型毫无区分度**。
必须逐模型查具体调用点。**按计数判断会出错**，本会话这是第三次。

### 8.6 V2 的**具体修法**（2026-10-16 核实 conversation 后确定）

以 conversation 的 4 个模型为样本逐行核对后，修法已经明确，**两端分别处理**：

**写路径（比现在更简单）**：这些模型的工厂与迁移方法都带 `operatorId`/`occurredAt` 参数，
用来填 `createdBy/createdAt/updatedBy/updatedAt`。例如
`SecretChat.create(long userA, long userB, long operatorId, LocalDateTime occurredAt)`、
`submitHandshake(userId, publicKey, operatorId, occurredAt)`、`withDestroyPolicy(policy, operatorId, occurredAt)`。
审计值本就该在**持久化那一刻**确定，所以这些参数**整体删除**：适配器写库时自行给 PO 打时间戳，
领域方法不再认识"操作人"这个概念。这是**净简化**，不是复杂化。

**读路径（需要载体）**：端口目前返回裸领域对象
（`Optional<SecretChat> findById`、`List<SecretChat> findByParticipant` 等 6 个方法），
而应用层用 `chat.getCreatedBy()` 等组装响应。去掉领域字段后，这些值必须由适配器**另行带出**——
按本仓既有做法引入 `Persisted<T>(T aggregate, AuditSnapshot audit)`（order/payment/customer 三个域已在用），
响应装配点改从 audit 取。

**已确认可直接复用的既有实现**：`MybatisSecretGroupChatRepository:83` 写路径**已经在用**
`po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt()`，即适配器从 PO 取审计值；
4 个模型又都已是**不可变类（private 构造器）**，所以是"删参数 + 加载体"，**不需要重写可变性**。

**结论**：V2 = 21 个模型 × {删 4 个构造/方法参数、适配器补审计列、端口加载体、响应装配点改取值、测试适配}。
**建议按服务拆分为 3 个独立任务**（conversation 4 → message 5 → user 12），每个服务完成即跑完整测试并提交；
不要试图一次改完，否则中间态无法验证。

### 8.7 V2 执行进度与 im-message 的逐模型判定（2026-10-16 第三次更新）

**已完成**：

| 服务 | 模型 | 结果 |
|---|---|---|
| conversation | `SecretChat` | 需载体（`SecretChatResult` 发布审计值） |
| conversation | `SecretGroupChat` | 需载体 |
| conversation | `Channel` | 需载体（`ChannelResult` 发布 `createdAt`/`updatedAt`） |
| conversation | `ChannelSubscription` | **无需载体**（审计值只被适配器读，用于映射 `joined_at`） |
| common | `SmsSendLog` / `MailSendLog` | 无需载体（写路径返回领域对象，应用层只用 id/status） |
| common | `AuditLog` | **有读者但有意偏离**：`createdAt` 留在模型（`AuditLogView` 发布），已在类注释说明 |

**判据已精化为模型级**：同一聚合边界内 `Channel` 需要载体、`ChannelSubscription` 不需要——
差别只在"**适配器之外有没有人读这四个字段**"。这决定工作量差一倍。

**im-message 剩余 5 个模型的判定**（已逐个查读者，尚未动工）：

| 模型 | 外读者 | 修法 | 备注 |
|---|---|---|---|
| `Message` | **有**（`MessageResponse` 发布全部四个） | 需载体 | **本仓最大的聚合**，应用服务 `MessageApplicationService` 有 11 处审计读取；风险最高 |
| `MessageFavorite` | **有**（`FavoriteResponse` 发布 `createdAt`） | 需载体 | 走 `MessagePersistenceConverter` 转换，与 `Message` 共用 |
| `SecretGroupMessage` | **有**（`SecretGroupMessageApplicationService:388` 读 `getCreatedAt()`） | 需载体 | 独立仓储 |
| `SecretMessage` | 待最终确认 | 待定 | 独立仓储 |
| `MessageOutbox` | **看起来无外读者**，但存在**语义归属问题**（见下） | 待定 | 可变类 |

**`MessageOutbox` 是一个需要**先做判断**的案例，不能机械套用**：它的 `createdAt` 由调用方经
`pending(..., createdAt)` 传入，而 `markPublished(occurredAt)`/`assignId(id)` 会**修改实例**（可变类）。
outbox 的 `created_at` 更接近"**事件入队时刻**"这一领域事实，而非"谁在什么时候改的"记账信息；
把它移进适配器会改变"何时决定入队时刻"的语义。**这一条应先明确归属，再决定是否纳入 V2**。

**据此的工作切分建议**：`SecretMessage` / `SecretGroupMessage` / `MessageFavorite` 三个先做（独立仓储、读者明确），
`Message` 单独一轮（聚合最大、共用转换器），`MessageOutbox` 先定语义再动。

### 8.8 进一步核实：**两个模型的 `createdAt` 参与领域逻辑**（2026-10-16 第四次更新）

我把"审计字段"当成同类，逐个核实后发现**不是**。有两个模型的 `createdAt` 是**业务输入**：

| 模型 | 证据 | 含义 |
|---|---|---|
| `SecretMessage` | `SecretMessageApplicationService:286`：`LocalDateTime candidate = message.getCreatedAt().plus(ttl)` | **销毁时刻 = 创建时刻 + TTL**。`createdAt` 是推导 `destroyAt` 的输入，移除它会把这条领域规则从领域里剥离 |
| `MessageOutbox` | `pending(..., createdAt)` 由调用方传入；`markPublished(occurredAt)` 修改实例 | `created_at` 是"事件入队时刻"这一领域事实 |

**结论：不能把 21 个模型的审计字段当成同一类改动。** 动手前必须逐模型回答**两个**问题：

1. **适配器之外有没有人读这四个字段？**（决定是否需要载体）
2. **`createdAt` 是否参与领域判定？**（决定它是否根本不是"审计字段"）

只有两个都答"否/无"时，才是 standards/10 意义上的"审计字段"，可以安全下沉到适配器。
`sms`/`mail`/`ChannelSubscription` 属这一类；`AuditLog`、`SecretMessage`、`MessageOutbox` **不属于**——
它们要么有读者需要载体，要么 `createdAt` 承担业务语义，需要**先明确归属再动手**。

**这也是我在这一轮停下来记录、而不是继续改的原因**：`SecretMessage` 有 **11 个端口方法**返回裸领域对象，
而 `createdAt` 又参与 TTL 推导；直接套用前面的模板会改掉销毁时刻的语义。这类判断必须先做，改起来才安全。

### 8.9 剩余 17 个模型的**实际字段分布**（2026-10-16 第五次更新）

逐个统计字段数后，剩余工作**不是同质的**——有一部分模型已被前人**部分处理过**：

| 字段数 | 模型数 | 模型 |
|---|---|---|
| **四个齐全** | **12** | `Message`、`SecretMessage`、`UserAccount`、`UserSecurityQuestion`、`AccountCancellation`、`DeviceSession`、`DeviceToken`、`DeviceKey`、`OpenApplication`、`FriendRelation`、`FriendRequest`、`UserSticker` |
| **仅 `createdAt`** | **3** | `MessageFavorite`、`MessageOutbox`、`SecretGroupMessage` |
| **`createdAt` + `updatedAt`** | **2** | `UserNotificationSetting`、`UserPrivacySetting` |

**含义**：那 5 个"部分处理过"的模型，`createdBy`/`updatedBy`（以及部分 `updatedAt`）**早已不在领域模型里**——
它们不是"从零改"，而是"收尾"。`SecretGroupMessage` 尤其明显：它只剩 `createdAt`，且该字段**不参与 TTL 推导**
（`scheduleDestroy(destroyAt)` 显式传参），唯一外读者是响应装配。

**已完成的 7 个模型**：conversation 4 个（`SecretChat`/`SecretGroupChat`/`Channel`/`ChannelSubscription`）
+ common 3 个（`SmsSendLog`/`MailSendLog`/`AuditLog`）。conversation 与 common 两个服务**已完全收口**。

**下一步的合理顺序**（按"剩余工作量"而非"模型数"）：
1. `SecretGroupMessage`（只剩 `createdAt`，无领域规则牵涉）——最小
2. `MessageFavorite`、`MessageOutbox`（同样只剩 `createdAt`，但 `MessageOutbox` 的语义待定，见 §8.8）
3. `UserNotificationSetting`、`UserPrivacySetting`（只剩两个字段）
4. 12 个"四个齐全"的模型逐个处理；`Message` 与 `SecretMessage` 各单独一轮

### 8.10 `MessageFavorite` 与 `MessageOutbox`：`createdAt` 的第三种角色——**排序键**（2026-10-16 第六次更新）

`SecretGroupMessage` 已完成（`createdAt` 只被响应读，干净下沉）。接着核 `MessageFavorite` 时发现它的
`createdAt` **不只是显示用**：

| 证据 | 含义 |
|---|---|
| `MessageFavoriteRepositoryAdapter:52`：`orderByDesc(MessageFavoritePo::getCreatedAt)` | **收藏列表按它倒序分页** |
| `FavoriteApplicationServiceTest:519-524`：测试替身自己按 `createdAt` 排序后 `subList` 分页 | **测试替身依赖它排序** |

**这意味着**：把 `createdAt` 从 `MessageFavorite` 移除，不只是"给响应加个载体"——
`findByUserId` 返回的 `FavoritePage(items, total)` 里，`items` 必须变成 `Persisted<MessageFavorite>`，
**而且**：领域对象一旦不带时刻，**内存测试替身就无法复现"按收藏时刻倒序"这一契约**，
只能改成让替身维持 `Persisted` 并自己记时间。这不是机械改动。

**所以 `createdAt` 有四种角色，必须分开处理**：

| 角色 | 例子 | 处理 |
|---|---|---|
| 纯记账（无人读） | `ChannelSubscription` | 下沉适配器，无载体 |
| 响应回显 | `Channel`、`SecretGroupMessage` | 下沉适配器 + `Persisted` 载体 |
| **领域规则的输入** | `SecretMessage`（销毁 = 创建 + TTL） | **留在模型**，并把规则收进领域 |
| **排序键** | `MessageFavorite`、疑同 `MessageOutbox` | **待定**：需同时处理载体与测试替身的分页语义 |

**这就是我在这里停下的原因**：`MessageFavorite` 与 `MessageOutbox` 需要一个**共同决定**——
"排序键留在领域还是改为载体承载"。两种做法都可行，但会影响 `FavoritePage` 的形状与测试替身，
属于**设计选择**而非机械清理。先记录，避免在半途做出会反复修改的选择。

**（该决定已作出并落地：见 §8.11——用只读投影，不引入 `SortKey`。`MessageFavorite` 已完成。）**

### 8.11 只读投影方案落地 + 剩余 12 个模型的施工图（2026-10-16 第七次更新）

**B 方案（只读投影）已在 `MessageFavorite` 落地并验证**（message 124 测试全绿）：

| 要素 | 做法 |
|---|---|
| 投影类型 | `domain.message.readmodel.MessageFavoriteView`（record，含 `favoritedAt`） |
| 端口方法 | `searchFavorites(...)`（L279 查询语义），返回 `FavoritePage(List<MessageFavoriteView>, total)` |
| 排序 | 适配器内 `orderByDesc(created_at)`，**不进端口** |
| 业务判定 | 原在实体上的 `hasSnapshot()` 移到应用服务（L278 投影不得定义行为） |
| 作废 | 我此前提议的"`SortKey` 枚举放进端口包"——**规范里没有这一层，不引入** |

**剩余 12 个模型的逐模型判定（已全仓 grep 读者，可直接照此施工）**：

| 模型 | 适配器外读者 | 判据结论 | 修法 |
|---|---|---|---|
| `UserAccount` | ✅ 3 处（admin 列表 `:152`、profile `:151-152,161`） | 有响应读者 | 载体或投影 |
| `FriendRelation` | ✅ 4 处（admin `:160`、friend `:209-210`、授权查询 `:47`） | 有响应读者 + **`:47` 参与判定** | 投影 + 注意 time 语义 |
| `DeviceToken` | ✅ 2 处（admin `:165-166`、`DeviceTokenApplicationService:62-63`） | 有响应读者 | 载体/投影 |
| `UserSticker` | ✅ 2 处（admin `:172`、sticker `:62`） | 有响应读者 | 载体/投影 |
| `DeviceSession` | ✅ 1 处（`:76`） | 有响应读者 | 载体/投影 |
| `DeviceKey` | ✅ 1 处（`:51`） | 有响应读者 | 载体/投影 |
| `FriendRequest` | ✅ 1 处（`FriendApplicationService:216-217`） | 有响应读者 | 载体/投影 |
| `UserSecurityQuestion` | ❌ **无** | **纯记账** | **纯下沉（最省）** |
| `AccountCancellation` | ❌ **无** | **纯记账** | **纯下沉** |
| `OpenApplication` | ❌ **无** | **纯记账**（`reviewedBy`/`reviewedAt` 是业务字段，保留） | **纯下沉** |
| `MessageOutbox` | ❌ 仅转换器（`MessagePersistenceConverter:39`） | **纯记账**，但 `pending(..., createdAt)` 由调用方决定入队时刻 | 先确认调用方是否恒传 `now()` |
| `Message` | ✅ 11 处 | 有响应读者 | 单独一轮（最大聚合、共用转换器） |

**据此顺序**：3 个"纯下沉"先做（`UserSecurityQuestion`/`AccountCancellation`/`OpenApplication`），
再 7 个"有读者"逐个按投影或载体处理，最后 `Message`。这与"模型数"无关，按**剩余工作量**排序。

**（3 个"纯下沉"已完成：`50f968f` / `1baf1cb` / `31cf744`。）**

### 8.12 `DeviceSession` 施工单——同一模型内 `occurredAt` 的两种命运（2026-10-16 第八次更新）

7 个"有读者"模型里最小的是 `DeviceSession`（端口仅 4 个方法、唯一读者 1 处），适合做模板。
核实其四个迁移方法后，发现**同一个模型内 `occurredAt` 有两种处置**：

| 方法 | `occurredAt` 的用途 | 结论 |
|---|---|---|
| `start(...)` | 写 `lastActiveAt`（**业务**）+ `updatedAt` | **保留** |
| `refreshActivity(...)` | 写 `lastActiveAt`（**业务**）+ `updatedAt` | **保留** |
| `kick(occurredAt)` | 只写 `updatedAt` | **删参** |
| `logout(occurredAt)` | 只写 `updatedAt` | **删参** |

**这条再次证明判据必须逐方法问，不能按方法名或按模型推断。** `lastActiveAt` 是设备在线状态的事实
（与 `AccountCancellation` 的 `requestedAt` 同类），而 `updatedAt` 是记账。

**施工步骤**（已完全确定，无需再推导）：

1. **模型**：删 4 个审计字段与 getter；`start`/`refreshActivity` **保留** `occurredAt`；
   `kick()`/`logout()` 去参；`restore` 去末 4 参
2. **载体**：`domain/device/repository/` 下加 `Persisted<DeviceSession>` + `AuditSnapshot`
   （从 `SecretChat` 的同名类型复制并改包名，两者同形）
3. **端口**（4 个方法）：`findByUserIdAndDeviceId`/`findById`/`findByUserId` → `Persisted<DeviceSession>`；
   `save` → `Persisted<DeviceSession>`
4. **适配器**：insert 写 `created_at`+`updated_at`，update 只写 `updated_at`；
   `created_by`/`updated_by` 取 `getUserId()`（会话操作人恒为用户本人）
5. **应用服务**：`DeviceSessionApplicationService:76` 的响应装配改从 `row.audit()` 取值
6. **测试**：内存假仓储改存 `Persisted`（形态同 `SecretMessage` 的假仓储：`(聚合, 时刻)` 对）

**其余 6 个"有读者"模型**按同一套路，但**必须先跑两问**再动手：
①适配器外有谁读？②该字段是否喂业务字段/参与判定？
已知需特别小心的：`FriendAuthorizationQueryService:47` 用 `updatedAt`/`createdAt` **二选一参与判定**，
投影必须同时保留两者且语义原样。

### 8.13 `FriendRequest`：`createdAt` 被**写路径重置**，需要端口拆分（2026-10-16 第九次更新）

`FriendRequest` 看起来和 `DeviceKey` 同型（1 处响应读者、4 个端口方法），但有一个**结构性问题**：

```java
/** 重新申请时覆盖已有 pending：刷新附言与时间，保持单行幂等并重新触发通知。 */
public void overwrite(String message, LocalDateTime occurredAt) {
  this.message = message == null ? "" : message;
  this.createdAt = occurredAt;   // ← 审计列被写路径重置
  this.updatedAt = occurredAt;
}
```

**「重新申请」会把 `created_at` 重置为当前时刻**，因为好友申请列表按 `created_at` 倒序——
重新申请必须排到最前。这是**业务需求**，不是记账。

**审计列不进领域模型后，这条规则无处落地**：领域模型只说"附言变了"，适配器无从知道
"这一行需要把 `created_at` 前移"。

**我试过一个临时解法（在领域模型上挂一个 `reapplyAt` 字段）并立刻回退**——那等于把审计列
换个名字放回领域模型，既违规又难懂。

**正确解法是端口拆分**（也是领域设计的常规手法：把"重新申请"表达为一个独立的业务动作）：

```java
// 写路径语义不同 → 端口方法不同，模型不需要携带时刻
FriendRequest save(FriendRequest request);          // 首次申请：created_at 由适配器打
FriendRequest reapply(FriendRequest request);       // 重新申请：适配器把 created_at 与 updated_at 都刷新为 now
```
适配器：`reapply` 走 `UPDATE ... SET message = ?, created_at = now, updated_at = now WHERE id = ?`。

**含义**：这类"审计列被业务写路径重置"的模型**不能按载体套路处理**，要先确认端口语义是否够表达。
`FriendRequest` 因此**比我预判的多一步**，与 `FriendRelation` 同属"需先设计再动手"。

### 8.14 剩余 5 个模型的**真实规模**——修正一处预判错误（2026-10-16 第十次更新）

`DeviceToken` 与 `UserSticker` 完成后，剩余 5 个模型的实测规模如下。

**我把 `UserAccount` 预判为"同 `UserSticker` 的双形态"是错的**，它明显更大：

| 模型 | 端口方法 | 模型内 `updatedAt` 写入点 | 实际难度 |
|---|---|---|---|
| **`UserAccount`** | **18**（含 `searchForAdmin`、`findSelfDestructDue`、`saveStatusIfVersionMatches`、`default findByIds`、`dailyCounts`） | **7 处 + 1 处 `updatedBy = operatorId`** | **最大**，每个迁移方法的 `occurredAt` 都要单独判定是否还喂业务字段 |
| `FriendRelation` | 待查 | 待查 | 需投影（`:47` 两时刻二选一参与判定） |
| `FriendRequest` | 5 | 2 | 需端口拆 `save`/`reapply` |
| `MessageOutbox` | 待查 | — | `pending(..., createdAt)` 语义待确认 |
| `Message` | 待查 | 多数迁移方法 | 11 处审计读取，最大聚合 |

**教训**：仅凭"有读者 / 无读者"两问**不足以估工作量**——还要看**端口方法数**与**迁移方法中 `occurredAt` 的用途分歧数**。
`DeviceKey`/`DeviceSession` 只有 3–4 个端口方法、2 个迁移方法，所以一轮就过；
`UserAccount` 有 18 个端口方法和 7 个写点，需要拆成多轮（先端口与载体、再逐个迁移方法）。

**建议的下一步顺序**（按实测规模而非模型数）：`FriendRelation` → `FriendRequest` → `MessageOutbox` → `UserAccount` → `Message`。

### 8.15 `FriendRelation`：`createdAt`/`updatedAt` 是**授权版本号**（2026-10-16 第十一次更新）

核实 `FriendAuthorizationQueryService:46-49` 后，这已经**不是"响应回显"级别的问题**：

```java
private long version(FriendRelation relation) {
  LocalDateTime timestamp = relation.getUpdatedAt() != null ? relation.getUpdatedAt() : relation.getCreatedAt();
  return timestamp == null ? 0L : timestamp.toInstant(ZoneOffset.UTC).toEpochMilli();
}
```

`version(...)` 的结果进入 `PrivateMessageAuthorizationSnapshot`（`api/authorization`），
经 `PrivateMessageAuthorizationController` 暴露给 **im-message-service** 用于私信授权判定
（`UserServiceFriendRelationAdapter` 跨服务调用）。

**即：`updatedAt`/`createdAt` 共同编码了"这段好友关系何时被改过"，是授权缓存的版本语义。**

**施工约束（比"用投影"更严）**：

1. 投影/载体必须**同时携带** `createdAt` 与 `updatedAt`，且
2. **回退顺序 `updatedAt ?? createdAt` 必须原样保留**——它决定了版本号取哪一个时刻，改了就可能在
   关系未变更时错误地判定"版本变了"（缓存击穿）或"版本没变"（授权过期不失效）。
3. `FriendApplicationService:209-210` 还要四个值做响应，所以这一处必须走**载体或投影**，不能只给两个时间戳。

**`FriendRelation` 的规模**：端口 **10 个方法**（含 `searchForAdmin` 分页 → 投影）、模型 2 处写审计列
（`create`、`reactivate`）、**2 处应用层读者**（响应 + 授权版本）。

**结论**：这是剩余 5 个里**语义风险最高**的一个——改动会跨服务影响私信授权。必须：
先补一条**回归测试锁住 `version()` 的取值**（关系未变更时版本稳定、变更后版本递增），再动模型。

### 8.16 `UserAccount` 的逐方法判定（2026-10-16 第十二次更新）

`UserAccount` 有 **8 个写审计列的方法**，逐一判定 `occurredAt` / `operatorId` 的用途后结论如下。
**这张表是施工依据，不需要重新推导**：

| 方法 | 参数用途 | 结论 |
|---|---|---|
| `register(... occurredAt)` | 还写 `lastLoginAt`（**业务**：首次登录时刻） | **保留 `occurredAt`** |
| `updateProfile(... occurredAt)` | **只**写 `updatedAt` | **删 `occurredAt`** |
| `changePassword(hash, occurredAt)` | **只**写 `updatedAt` | **删 `occurredAt`** |
| `invalidateAuthentication(occurredAt)` | **只**写 `updatedAt` | **删 `occurredAt`** |
| `recordLogin(occurredAt)` | 写 `lastLoginAt` + `selfDestructAt`（**业务**）+ `updatedAt` | **保留** |
| `setSelfDestructPolicy(policy, occurredAt)` | 用 `lastLoginAt != null ? lastLoginAt : occurredAt` 推策略（**业务**）+ `updatedAt` | **保留** |
| `markCancelled(hash, occurredAt)` | **只**写 `updatedAt` | **删 `occurredAt`** |
| `changeStatus(target, operatorId, occurredAt)` | `operatorId` **只**写 `updatedBy`；`occurredAt` **只**写 `updatedAt` | **两个参数都删** |

**统计**：5 个方法删参（其中 1 个删两个参数），3 个方法保留（`register`/`recordLogin`/`setSelfDestructPolicy`）。

**其余施工面**：

- 端口 **18 个方法**；`findById`/`findByUsername`/`findByEmail`/`findByPhone`/`search`/`findByIds` → 需载体；
  `searchForAdmin` → `PageResult<UserAccountView>` 投影；`countAll`/`countCreatedSince`/`dailyCounts`/
  `findIdsMatchingKeyword`/`saveStatusIfVersionMatches`/`findSelfDestructDue`/`hardDelete` 视返回值而定
  （`findSelfDestructDue` 返回实体列表 → 需载体；`saveStatusIfVersionMatches` 返回 boolean → 不变）
- 应用层读者 **2 处**：`AdminUserQueryApplicationService:152`（admin 列表，走投影）与
  `ProfileApplicationService:151-152,161`（个人资料响应，走载体）
- 建议**分两轮**：先端口 + 载体 + 投影 + 适配器（编译通过、测试绿），再逐个改那 5 个迁移方法的签名

**为什么分两轮**：第一轮只动「读取形态」，编译错误集中在适配器与应用层；
第二轮只动「方法签名」，编译错误集中在调用点。混在一轮会同时暴露两类错误，难以定位。

### 8.17 `UserAccount.updatedBy` 同样需要在端口上表达操作人（2026-10-16 第十三次更新）

试做第一轮时发现 §8.16 的表还不完整。**`updatedBy` 不是恒为常量**：

| 路径 | `updatedBy` 取值 |
|---|---|
| `register(...)` | **常量 `0L`**（系统） |
| 其它迁移方法 | 不改 `updatedBy`（沿用原值） |
| `changeStatus(targetStatus, operatorId, occurredAt)` | **`operatorId`**（管理端操作人） |

所以 `updatedBy` 一旦移出领域模型，适配器**无法知道**这次写库要不要记录"谁改的"。

**这与 `FriendRequest` 的 `createdAt` 是同一类问题**：审计列被某条业务路径赋予了真实含义，
必须用**端口方法**表达，而不是让领域模型携带字段。

**解法**：状态变更走独立端口方法，并带上操作人：

```java
// 现状：只有 account 与版本号
boolean saveStatusIfVersionMatches(UserAccount account, long expectedStatusVersion);

// 改为：把操作人作为端口参数（它本来就是这次「管理端变更」的业务输入）
boolean saveStatusIfVersionMatches(UserAccount account, long expectedStatusVersion, Long operatorId);
```

适配器据此写 `updated_by = operatorId`；其余路径写 `0L`（沿用 `register` 的既有口径）。

**推论**：§8.16 的"分两轮"要改成**三轮**——
①读取形态、②5 个迁移方法删参、③`changeStatus` 的端口加 `operatorId`。
第三轮必须独立，因为它改的是**端口语义**而不是签名。

**我在此回退了第一轮的尝试**：中途引入了不存在的 PO 访问器、且尚未处理 `updatedBy` 的这条冲突，
继续下去会同时改坏读取形态与写入语义。已 `git checkout` 恢复，构建确认 `BUILD SUCCESS`。

### 8.18 `Message.createdAt` 是**编辑窗口规则的输入**（2026-10-16 第十四次更新）

动 `Message` 前的核实结果：它**不是**"四字段、纯回显"的载体案例。

```java
/** 编辑窗口是否仍有效：发送后 2 分钟内（含已被读消息）可编辑。 */
public boolean editableAt(LocalDateTime now) {
  return createdAt != null && now != null && createdAt.plus(EDIT_WINDOW).isAfter(now);
}
```

**`createdAt` 参与领域判定**——与 `SecretMessage`（销毁 = 创建 + TTL）**完全同型**。

**所以 `Message` 直接复用 §8.8 已验证的解法**，不重新发明：

```java
// 规则仍是领域的，但把发送时刻作为参数传入；createdAt 移出模型
public static boolean editableAt(LocalDateTime sentAt, LocalDateTime now) {
  return sentAt != null && now != null && sentAt.plus(EDIT_WINDOW).isAfter(now);
}
```
应用层调用 `Message.editableAt(row.audit().createdAt(), now)`。
**规则在领域层、字段在适配器，零违规**——`SecretMessage.destroyAtFrom(...)` 走的就是这条路。

**其余写点**（供后续判定，尚未逐一执行）：

| 方法 | 写入 | 备注 |
|---|---|---|
| `create(...)` ×2 | `createdBy`/`createdAt`/`updatedBy`/`updatedAt` | 参数用途需确认是否还喂业务字段 |
| `recalled(recalledAt)` | `updatedBy`（沿用）、`updatedAt = recalledAt` | `recalledAt` 是否只喂审计列待确认 |
| `edited(newContent, operatorId, editedAt)` | `editedAt` 同时写 **`editedAt`（业务字段）** 与 `updatedAt`；`operatorId` 只写 `updatedBy` | **`editedAt` 参数保留**（业务），`operatorId` 待判定 |

**施工顺序建议**：先把 `editableAt` 改成静态纯函数并补回归测试（编辑窗口边界：恰好 2 分钟内可编辑、
超过则不可），再动字段——与 `FriendRelation` 的"测试先行"同一套路。

### 8.19 `Message` 第一轮：10 个端口方法与两种读取形态（2026-10-16 第十五次更新）

第一轮只动**读取形态**，不动 `Message` 上的字段——与 `UserAccount` 的分轮理由相同：
把编译错误限制在适配器与应用层，第二轮再改方法签名。

**端口现在是 10 个迁移方法**，按返回形态分三类：

| 形态 | 方法 | 返回 |
|---|---|---|
| 单条 / 批量 / 写 | `findByMsgId`、`findBySenderIdAndClientMsgId`、`findByMsgIds`、`save` | `Persisted<Message>` |
| 列表 ×4 | `findHistory`、`findHistoryByDate`、`findCentered`、`findChannelMessages` | `List<MessageView>` |
| 分页 ×2 | `search`、`findAdminPage` | `MessageView`（`MessagePage` / 列表） |

`save` 合并了原来的 `save`/`update`：standards/10 L300 要求 `id == null` 新增、否则更新。
方法数因此正好是 10，也避免调用方按"技术动作"而不是业务意图选方法。`findHistoryDates` 返回
`List<String>`（只读日期，不承载消息行），不在此轮。

**两个新类型的职责**：`MessageRepository.Persisted<Message>` + `AuditSnapshot` 承载响应回显与编辑窗口判定所需的
四个审计列；`MessageView` 承载列表/分页的整行展示字段（含审计列）。第一轮时审计列还暂时留在 `Message` 上，
只保证读路径**不再依赖**模型上的审计字段；字段本身已在 §8.20 删除。

**`editableAt` 的调用点已切换**：`MessageApplicationService.editMessage` 用
`Message.editableAt(row.audit().createdAt(), now)`。新增的回归测试钉住「窗口只取快照发送时刻」，
覆盖快照在窗口内与已过期两态；第二轮删字段后它仍然成立。

**第二轮待办**（尚未执行）：

1. 从 `Message` 删除四个审计字段与 getter，`restore` 去末 4 参。
2. **`createdAt` 不是纯记账**：它还进入 `StoredMessageEvent`（`StoredMessageEventFactory`、`JacksonMessagePayloadCodec`）
   与 `UserSyncIndex.createdAt`（`synchronizeUsers`）。删字段时不能简单让适配器打 `now()`，
   否则事件时间与同步索引时间会从 `command.acceptedAt()` 漂移；这两处需要在应用层显式持有发送时刻。
   编辑窗口只是它"参与判定"的那一面，不是全部用途。
3. `edited(newContent, operatorId, editedAt)`：`editedAt` 保留（写业务字段 `edited_at`），`operatorId` 只写
   `updatedBy`，需要决定端口是否表达操作人——与 `UserAccount.changeStatus` 同型。
4. `recalled(recalledAt)` 在主代码中没有任何调用方（撤回是硬删除 + Outbox 事件），可随字段一并删除。
5. 适配器在 `save` 里自打审计列；`MessagePersistenceConverter.toPo` 不再从模型取审计值。
6. `FavoriteApplicationService:121` 与 `:202` 仍读 `message.getCreatedAt()`，第二轮改走 `Persisted.audit()` 或快照。

**（以上 6 项已在 §8.20 执行完毕。）**

### 8.20 `Message` 第二轮：发送时刻离开模型，写入时刻由端口参数表达（2026-10-16 第十六次更新）

`Message` 的四个审计字段已删除，`create(...)`/`restore(...)` 去掉 `createdAt` 与末 4 参，
`edited(newContent, editedAt)` 只保留业务字段 `edited_at`，无调用方的 `recalled(...)` 一并删除。

**关键设计：`save` 显式接收写入时刻与操作人**（`MessageRepository`）：

```java
Persisted<Message> save(Message message, LocalDateTime occurredAt, long operatorId);
```

- 新增（`id == null`）：`created_at = updated_at = occurredAt`，`created_by = updated_by = operatorId`；
  新建消息由 `storeNew` 传 `command.acceptedAt()` 与系统操作人 `0L`。
- 更新：只写 `updated_at = occurredAt`、`updated_by = operatorId`，`created_*` 保持原值；
  编辑由 `editMessage` 传 `now` 与 `command.userId()`。

这是 §8.17 `UserAccount.changeStatus` 的同型解法：**操作人不是常量时，必须由端口参数表达**，
而不是让适配器猜。`MessagePersistenceConverter.toPo` 不再从模型取任何审计值。

**发送时刻的三条下游没有被漏掉**（§8.19 第 2 项）：

| 下游 | 处理 |
|---|---|
| `persisted.audit().createdAt()` | `storeNew` 以落库返回值为准，供下面两处使用 |
| `UserSyncIndex.createdAt` | `synchronizeUsers(..., sentAt)` 显式传入 |
| `MessageStoredEvent.createdAt` | `MessagePayloadCodecPort.writeStoredMessageEvent(..., sentAt, ...)` 显式传入；`StoredMessageEventFactory.from(message, sentAt)` 同步调整 |

因此 `msg_message.created_at`、per-user 同步索引与 MQ 事件使用**同一个** `acceptedAt`，
没有退回"适配器打 now()"的漂移写法。

**收藏的读取也改走快照**：`FavoriteApplicationService.authorize` 返回 `Persisted<Message>`，
收藏快照的 `sentAt` 与列表实时消息的 `sentAt` 都取 `row.audit().createdAt()`，
不再从领域模型读 `createdAt`。

**验证**：`im-message-service` 128 个测试全绿；`invoke-engineering-validation.ps1 -Scope Changed`
全部门禁通过。`Message` 至此不再携带审计列，端口/模型/适配器/投影四处职责一致。

### 8.21 `UserAccount` 第一轮：17 个端口方法的读取形态（2026-10-16 第十七次更新）

按 §8.17 的"三轮"计划，第一轮只动**读取形态**，`UserAccount` 上的审计字段仍在；
`register/recordLogin/setSelfDestructPolicy` 的 `occurredAt` 与 `changeStatus` 的 `operatorId` 也保持原样。

**新增类型**：`domain.account.repository.Persisted<UserAccount>` + `AuditSnapshot`，
以及 `domain.account.readmodel.UserAccountView`。投影**刻意不含密码哈希**：管理端列表不需要它。

**端口映射**（当前接口实为 **17 个方法**；§8.16 记为 18，逐一核对后以此表为准）：**9 个**方法改变返回形态：

| 形态 | 方法 | 返回 |
|---|---|---|
| 单条 ×4 | `findById`、`findByUsername`、`findByEmail`、`findByPhone` | `Optional<Persisted<UserAccount>>` |
| 批量 ×2 | `findByIds`（default）、`search` | `List<Persisted<UserAccount>>` |
| 写 ×2 | `save`、`findSelfDestructDue` | `Persisted<UserAccount>` / `List<Persisted<UserAccount>>` |
| 分页 ×1 | `searchForAdmin` | `PageResult<UserAccountView>` |
| 不变 ×8 | `existsByUsername`、`existsByEmail`、`findIdsMatchingKeyword`、`countAll`、`countCreatedSince`、`dailyCounts`、`saveStatusIfVersionMatches`、`hardDelete` | 原样 |

**应用层读者**：

- `AdminUserQueryApplicationService.listUsers` 走 `UserAccountView`；`detailUser` 从载体组装同形投影
  （该类已导入 device 的 `Persisted`，为避免同名类型冲突，detail 处用 `var` + 内联投影）。
- `ProfileApplicationService.getProfile/getProfileForViewer` 走载体；搜索、摘要、禁言状态等只读业务字段的路径
  统一 `.map(Persisted::aggregate)`。
- 其余应用服务（登录/找回/注销/删除/OpenPlatform/好友等）只需业务事实，全部解包聚合；写路径忽略返回的载体。

**测试**：各测试类加了一个本地 `row(account)` 辅助把聚合包成载体（第二轮删字段时再改为显式快照）；
`OidcTokenFlowTest` 的内存仓库同步了方法签名；新增 `UserAccountPersistenceConverterTest` 钉住
"投影携带展示/审计列、密码哈希不进入投影"。`im-user-service` **113 个测试全绿**，
`invoke-engineering-validation.ps1 -Scope Changed` 全部门禁通过。

**第二轮/第三轮仍按 §8.16、§8.17 执行**：

1. 删除四个审计字段与 getter，`restore` 去末 4 参；5 个迁移方法按表删 `occurredAt`（`changeStatus` 两个参数都删）。
2. `save` 增加写入时刻/操作人参数（与 `Message.save(..., occurredAt, operatorId)` 同型），
   适配器据此写审计列。
3. `saveStatusIfVersionMatches(account, expectedVersion, operatorId)` 作为独立的端口语义变更，
   让 `updated_by = operatorId`；其余路径写 `0L`（沿用 `register` 口径）。

### 8.22 `UserAccount` 第二轮+第三轮合并：审计列移出，写入时刻/操作人进入端口（2026-10-16 第十八次更新）

按 §8.16 的逐方法表执行：`UserAccount` 的四个审计字段与 getter 已删除，`restore` 去末 4 参；
`updateProfile`、`changePassword`、`invalidateAuthentication`、`markCancelled` 删 `occurredAt`，
`changeStatus` 删 `operatorId` 与 `occurredAt`；`register`/`recordLogin`/`setSelfDestructPolicy` 保留
`occurredAt`（它们还写 `lastLoginAt`/`selfDestructAt` 这些**业务**字段）。

**端口**：

```java
Persisted<UserAccount> save(UserAccount account, LocalDateTime occurredAt);
boolean saveStatusIfVersionMatches(UserAccount account, long expectedStatusVersion, Long operatorId,
    LocalDateTime occurredAt);
```

`save`：新增时 `created_at = updated_at = occurredAt`、`created_by = updated_by = 0L`；
更新时**只写 `updated_at`**，`created_*` 与 `updated_by` 保持库中原值——这正对应 §8.17 表中
"其它迁移方法不改 `updatedBy`"。

**计划修正：第三轮不能独立存在**。§8.17 原本只给 `saveStatusIfVersionMatches` 加 `operatorId`，
但 `updatedAt` 一旦离开模型，状态变更的 `updated_at` 也需要一个显式时刻；若让适配器打 `now()`，
库里的时间会与 `ChangeUserStatusResult`/事件使用的 `occurredAt` 不一致。因此本轮合并第三轮，
CAS 方法同时接收 `operatorId` 与 `occurredAt`。这不是扩大范围，而是删字段后的必要条件。

**应用层**：`register/login/loginByQr/ssoLogin` 捕获同一个 `now` 传给 `register(...)`/`recordLogin(...)` 与
`save(account, now)`；`changeStatusOnce` 把 `operatorId`/`occurredAt` 交给 CAS 端口；
`updateProfile`、密码找回、注销、自毁清扫等路径同样把捕获的时刻显式传给 `save`。

**测试**：各测试的 `restore(...)` 去掉末 4 参，`save` 验证改为两参、状态 CAS 改为四参，
`row(account)` 改为显式快照；新增 `UserAccountRepositoryAdapterTest` 钉住三条写入契约
（新增写全四列、普通更新只写 `updated_at`、状态 CAS 写 `updated_by/updated_at`）。
`im-user-service` **116 个测试全绿**，`invoke-engineering-validation.ps1 -Scope Changed` 全部门禁通过。

**12 个模型的收口**：按 §8.11 的清单，`UserAccount` 是最后一个有读者的模型。
但"清单内收口"不等于"全仓收口"——后续复核发现清单外仍有领域模型携带审计字段，
见 §8.23；本节的完成范围仅是 §8.11 的 12 个模型。

### 8.23 清单外审计模型复核 + V1 首个切片（2026-10-16 第十九次更新）

**（1）清单外的审计字段**。按 `/domain/**/model/` 做全仓复核后，发现 §8.11 的 21 个模型清单
并不覆盖全部领域模型：

| 模型 | 字段 | 判定 | 处理 |
|---|---|---|---|
| `ConversationMute` | `createdAt` | **纯记账**，无任何读者 | ✅ 已删除字段与 `create(..., occurredAt)` 参数，适配器写 `created_at` |
| `ConversationGroup` | `createdAt`、`updatedAt` | 纯记账（版本语义由独立 `authorizationVersion` 承担） | ⏳ 但控制器直接返回领域对象，需先引入响应投影/载体 |
| admin `ClientRelease` | 四列 | 待逐读者核实（可能进响应） | ⏳ 下一批 |
| admin `ClientReleasePolicy` | 四列 | 同上 | ⏳ |
| admin `ClientReleaseOperation` | `createdBy`、`createdAt` | 操作日志，需确认是否为业务事实 | ⏳ |
| admin `ReleaseAuditLog` | `createdBy`、`createdAt` | 审计日志，`createdAt` 很可能是业务事实（事件时刻） | ⏳ 需判定 |
| `UserSyncIndex` | `createdAt` | **业务字段**：per-user 同步索引的消息时刻，供同步消费 | 保留（不是审计列） |

**教训**：§8.11 的"12 个模型"是按**有响应读者的聚合**筛出来的，不是 `domain/**/model/` 的全集；
`ConversationMute`/`ConversationGroup`/admin clientrelease 都在筛选条件之外。后续要么把范围写清楚，
要么用门禁扫描整个 `model` 目录，不能再用"清单完成"代替"规则完成"。

**（2）V1 前四个切片：批量/后台编排下沉**。

- `ChannelConfigController.setEnabledBatch` 的校验 + 循环 + `@Transactional` 移入
  `ChannelApplicationService.setEnabledBatch(...)`；控制器只做协议转换。同时把"先整批校验门店与渠道、
  再逐店写入"落到代码（原实现是边校验边写）。新增 `ChannelApplicationServiceTest` 覆盖非法门店、
  重复门店、非线上渠道与成功批量四条路径。`common-payment-service` **148 个测试全绿**。
- `InternalPricingPlanController.createBatch` 的整批校验 + 循环移入
  `PricingPlanApplicationService.createBatch(...)`，控制器只做 DTO → 应用层命令的转换。
  既有 `InternalPricingPlanBatchTest`（跨租户、混合业态、先校验后写）原样通过。
- `InventoryRecoveryAdminController` 的权限、订单/明细读取、库存回补、落库与审计整体移入新的
  `InventoryRecoveryApplicationService`；控制器不再注入 `OrderRepository`/`OrderItemRepository`，
  也不再持有 `@Transactional`。`controller-repository-boundary` 清单由 14 降为 **13**；
  订单服务受影响测试 10 个通过（成功/失败留痕 3 个 + 库存回补集成 7 个）。
- `KtvBusinessHoursController.update` 的时间解析、目标门店合并、权限与审计移入
  `KtvBusinessHoursApplicationService.updateWithAudit(...)`；控制器只做协议转换。
  新增 4 条应用服务测试（租户默认写入、混合目标拒绝、缺失/非法时间）。
- `TenantConfigController.update` 的权限、租户边界、两个配置键的 upsert 与审计移入
  `WalletTokenConfigApplicationService.update(...)`；读路径也改走 `resolveScoped(...)`，
  控制器不再注入 `TenantConfigRepository`/`AuditClient`，也不再持有 `@Transactional`。
  `controller-repository-boundary` 清单由 13 降为 **12**；租户服务 **125 个测试全绿**。
- `IamController` 的 5 个写方法（建角色、整体授权、单权限开关、分配/撤销用户角色）连同审计移入
  `IamAuthorizationApplicationService`，4 个仓储读方法也改为服务查询；控制器只注入应用服务。
  清单由 12 降为 **11**，控制器 `@Transactional` 只剩 `OrderItemController`×2。
- `MyOrderController` 的会员归属解析与订单查询移入新的 `OrderQueryApplicationService`，
  控制器只保留订单/预约的响应映射；清单由 11 降为 **10**。
  订单服务上下文与账单授权测试 15 个通过。
- `OrderBillController` 的账单访问控制（商户权限码 / 消费者本人订单）移入新的
  `OrderBillAccessService`，控制器只保留端点映射；清单由 10 降为 **9**。
  账单授权测试 5 个通过。
- `TenantController` 的租户列表筛选、创建幂等与平台审计移入新的 `TenantApplicationService`，
  控制器只做协议转换；清单由 9 降为 **8**；租户服务 **125 个测试全绿**。
- `StoreController` 的门店列表与写路径编排（权限/租户边界/审计/权限快照失效）移入已有的
  `StoreApplicationService`，控制器只做协议转换；清单由 8 降为 **7**。
- `ReservationCompatController` 的租户上下文、预约查询与旧契约字段映射移入新的
  `ReservationCompatQueryService`，控制器只负责 Map 形状的协议转换；清单由 7 降为 **6**；
  订单服务上下文与兼容读测试 6 个通过。
- `ReportController`（order 服务）的三个只读报表聚合、币种分组与时间口径移入新的
  `OrderReportApplicationService`，控制器只做端点映射；清单由 6 降为 **5**；
  订单 Spring 上下文测试通过。
- `ResourceController` 的创建/编辑编排（权限、上下文、编号唯一、房型/图片/描述归一、落库与审计）
  整体移入新的 `ResourceCommandApplicationService`，控制器只保留列表与协议转换；
  清单由 5 降为 **4**；资源服务 **123 个测试全绿**。
- `CatalogController` 的列表可点性/图片兜底/库存可用量与目录项创建、改价、软删、审计
  整体移入新的 `CatalogApplicationService`；`CatalogItemView` 从 `api.dto` 移到
  `application.dto`（应用层读模型，避免应用服务反向依赖 API 层）；控制器只做协议转换。
  清单由 4 降为 **3**；目录相关 **31 个测试通过**（含上架可见性/商品类型/分类集成测试）。
- `OrderItemController` 的加项、列表、确认、拒绝（含授权、目录项快照、库存占用、金额同步、
  待确认缓存失效与审计）整体移入新的 `OrderItemApplicationService`；`settle` 保留端点权限守卫。
  清单由 3 降为 **2**，控制器上的 `@Transactional` 由 2 降为 **0**（V1 的 12 个事务方法全部迁移完成）；
  订单加项相关 **33 个测试通过**。
- `OrderController` 的列表/详情/创建/确认/挂单/解挂（含授权、会话投影、实时合计、创建幂等与审计）
  整体移入新的 `OrderApplicationService`；`OrderView` 从 `api.dto` 移到 `application.dto`；
  KTV 会话、取消/作废端点继续委托既有应用服务。清单由 2 降为 **1**（仅剩 admin `ReportController`）；
  订单控制器相关 **55 个测试通过**。
- admin `ReportController` 的 7 个报表端点、时间/营业日/分桶/币种聚合与嵌套报表 DTO
  整体移入新的 `AdminReportApplicationService`；控制器只保留端点映射。`controller-repository-boundary`
  清单由 1 降为 **0**，该门禁从 `report-only-gates.txt` 删除并转为**阻塞门禁**；
  admin 报表相关 **62 个测试通过**。
- `ConversationGroup` 去掉 `createdAt/updatedAt`；端口改为 `Persisted<ConversationGroup>` +
  `AuditSnapshot`，适配器插入写 `created_at/updated_at`、更新只写 `updated_at`；
  控制器改用 `ConversationGroupView` 保留原 JSON 字段。群组相关 **21 个测试通过**。
- `ClientRelease` / `ClientReleasePolicy` 去掉 `createdBy/createdAt/updatedBy/updatedAt`：
  - `save`/`update` 端口改为显式接收 `actorId/occurredAt`，适配器插入写四列、更新只写 `updated_*`；
  - `ClientReleasePolicy` 的读端返回 `Persisted`，`PolicyResponse.updatedAt` 从 `AuditSnapshot` 取；
  - `ClientRelease` 的 DTO 不暴露审计列，读端保持聚合返回。
  admin clientrelease 相关测试通过。
- `ClientReleaseOperation` / `ReleaseAuditLog` **保留** `createdBy/createdAt`：它们是 append-only
  操作/审计记录，这两个字段是记录本身的「谁在何时操作」，属于业务载荷而非表记账列；
  与 `UserSyncIndex.createdAt`（同步时刻）同类，不再按审计列迁移。

**V1 完成**：控制器 `@Transactional` **12/12 清零**；`controller-repository-boundary` **14 → 0**，
门禁已转为 blocking（不再有 report-only 例外）。
**审计字段**：除上述两个 append-only 载荷与 `UserSyncIndex.createdAt` 外，清单内模型已全部收口。

### 8.24 审计字段门禁 + ChannelConfig（2026-10-16 第二十次更新）

新增阻塞候选门禁 `audit-fields`（`scripts/validate/validate-audit-fields.ps1`）：
扫描 `domain/**/model/**` 的 `createdBy/createdAt/updatedBy/updatedAt`，只 allowlist
`ClientReleaseOperation`、`ReleaseAuditLog`、`UserSyncIndex` 三个「字段是业务载荷」的模型。
门禁首次运行就发现 §8.23 的清单仍不完整：

- `ChannelConfig`（payment）也是纯记账列，且 `ChannelConfigDto` 暴露 `createdAt/updatedAt`。
  已迁移：模型去四列，端口读返回 `Persisted`、写接收 `occurredAt`，适配器插入写
  `created_at/updated_at`、更新只写 `updated_at`；DTO 从 `AuditSnapshot` 取时间。
  `common-payment-service` **148 个测试全绿**。
- `TenantPaymentMethod`（payment）同样迁移：模型去两列，端口读返回 `Persisted`、
  `insert/updateSwitches` 接收 `occurredAt`，DTO 从 `AuditSnapshot` 取时间。
- `PayCollect`、`PaymentIntent`、`Shift`、`PaymentEventOutboxEntry` 的时间字段是**业务时间**
  （收款单建立/状态迁移、支付有效期、交班时序、事件发生时刻），加入 allowlist 并写明理由，
  不按记账列删除。
- 仍有 **8 个**模型带纯记账列，工作清单 `.outputs/quality/audit-fields-worklist.txt`，
  门禁暂为 report-only（batch 2）：
  - payment 2：`DailyClosing`、`Refund`；
  - customer 2：`MemberNameToken`、`PointLedgerEntry`；
  - order inventory 2：`InventoryMaterial`、`InventoryTransaction`；
  - tenant 2：`PricingPlan`、`Tenant`。

**结论**：§8.23 的「审计字段已收口」只对当时的清单成立；`audit-fields` 门禁才是范围的
唯一事实来源。13 个模型逐个迁移后，删除 report-only 条目，门禁转为 blocking。






### 8.3 待决事项（需要决定，不能由我单方面选择）

**`mybatis-plus-jsqlparser-4.9`：标准与框架要求直接冲突。**
已查证：`mybatis-plus-3.5.17.pom` 把它标为 `<optional>true</optional>`（**不会传递**），
而 **10 个模块使用 `PaginationInnerInterceptor`、14 处调用 `selectPage`** → 分页扩展**运行时需要**它。
规范 L42/L92 禁止 `mybatis-plus-jsqlparser-*`。三个选项：
**(A)** 修订规范（把 jsqlparser 从禁令移出，说明它是分页扩展的必需解析器）；
**(B)** 严格执行（移除 13 处声明，**破坏分页**）；
**(C)** 改用手写 SQL / 移除分页（改造量最大）。
**注意**：其余被禁组件（`core`/`extension`/`annotation`/`mybatis-spring`）实测**声明模块数均为 0**，
说明禁令对它们有效——问题只出在 jsqlparser 这一项。
