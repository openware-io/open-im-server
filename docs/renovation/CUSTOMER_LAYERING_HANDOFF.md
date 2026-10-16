# Customer 域分层交接（进行中）

本文记录 `platform-services/customer` 分层改造的**当前状态**、已经踩过的坑，以及**尚未完成**的部分。
与 `DOMAIN_LAYERING_HANDOFF.md`（resource）、`PAYMENT_LAYERING_HANDOFF.md`（payment）同一系列。

## 0. 一句话现状

**本域的四个切片（积分规则 / 钱包 / 积分账户 / 会员）都已做完，生产侧与测试侧都在端口上，
227 测试全绿，两个分层门禁在客户域都是 0 违规**（见 §5）。
唯一还需要注意的不是"未完成"，而是 §4 记的那类**已发布契约的收紧**（审计字段、分页外层类型）
——那要单独一次带版本说明的提交，不该混在重构里做。

起点：该域**完全没有 `domain` 包**，14 个类直接从 `infra.persistence.po` / `mapper` 取数。
起点计数：`po-containment` 33、`service-api-boundaries` 14。

## 1. 已完成

### 1.1 积分规则（`PointRuleConfig`）—— 已提交 `a981613`

- `domain/point/model/PointRuleConfig.java`（record）+ `domain/point/service/PointRuleResolver.java` + `domain/point/repository/PointRuleConfigRepository.java`
- `infra/persistence/repository/PointRuleConfigRepositoryAdapter.java`
- `application/PointRuleConfigApplicationService.java` 重写；`api/dto/PointRuleConfigView.java`；`InternalPointRuleController` 不再返回 PO

**该切片的两个要点**：

1. **`save` 原本是读-判-写 + 无条件写回**（`version = existing.version + 1`）。两个并发写者都不带版本号时会同时通过比对——版本比对**只在调用方显式传了版本号时才执行**。已改为单条 CAS。积分倍率被静默改错会让此后每一次积分计算都错，所以这里必须由 SQL 判定。
2. **作用域优先级**原本是三层嵌套 `orElseGet`，只能靠 mock mapper 触达。已抽成纯函数 `PointRuleResolver`，可单独验证。两条**刻意的不对称**：限定业态的租户级默认**不**匹配"未指定业态"的查询；不限业态的默认**可以**匹配任意指定业态（它存在的意义就是兜底）。停用行不参与解析，但**写路径必须能看到它并复用**——否则会在同一作用域插入第二行。

### 1.2 储值账户与账本（`WalletAccount` / `WalletLedgerEntry`）—— 已提交 `ec297e5`

- `domain/wallet/model/{WalletAccount,WalletLedgerEntry}.java`
- `domain/wallet/repository/WalletAccountRepository.java`（含 `LedgerPage` record）
- `infra/persistence/repository/WalletAccountRepositoryAdapter.java`
- `application/WalletApplicationService.java` 重写；`application/WalletTokenDisplayService.java` 改为面向视图
- `api/dto/{WalletAccountView,WalletLedgerPageView}.java`
- 4 个控制器改造：`WalletAdminController`、`InternalCustomerController`、`MemberController`、`MyAssetsController`

### 1.3 积分账户与账本（`PointAccount` / `PointLedgerEntry`）—— 生产侧完成，待提交

- `domain/point/model/{PointAccount,PointLedgerEntry}.java`
- `domain/point/repository/PointAccountRepository.java`（含 `LedgerPage` record）
- `infra/persistence/repository/PointAccountRepositoryAdapter.java`
- `application/PointApplicationService.java` 重写（返回领域模型 + `MemberPointsData`）
- `api/dto/{PointAccountView,PointLedgerRow,MemberPointsView}.java`
  （`MemberPointsView` / `PointLedgerRow` 由 `application` 包**移到** `api/dto`：它们是响应视图，
  迁移时顺手放回它们该在的层）
- 3 个控制器改造：`MemberController`、`MyAssetsController`、`InternalCustomerController`

**该切片的三个要点**：

1. **本表的 CAS 是真的，不是"看起来有"**（与钱包 §2 相反，务必不要混为一谈）。
   迁移前 `updateAccountOptimistic` 就是
   `... WHERE id = ? AND version = ? SET available_points = ?, version = ?, updated_at = ?`，
   0 行受影响抛 409 `POINT_ACCOUNT_VERSION_CONFLICT`，且 `accountMapper.updateById` 从未被调用。
   适配器把这条语句的形状**原样保留**为 `updateBalanceWithVersion`：版本自增写在 SQL 里、只写真实列、
   返回 `false` 由服务侧转 409，**不降级为无条件写入**。迁移没有"修"任何东西——这一点是逐条核对后确认的，
   不是假定的。
2. **写入顺序改成"先 CAS，再写流水"**。迁移前四个写路径都是 `appendLedger(...)` 在前、
   `updateAccountOptimistic(...)` 在后，只靠外层 `@Transactional` 在 CAS 抛错时把流水滚回去；
   一旦有人把这段挪出事务，账本就会记着"积分动过"而余额没动，而**对账只能相信账本**。
   顺序锁进了两条测试（`adjustWritesBalanceBeforeLedger` 用 `InOrder` 断言；
   `casConflictIs409AndLeavesNoLedgerRow` 断言 CAS 失败不写流水）。业务行为无变化，全部既有断言照旧通过。
3. **API 外形照搬**（与钱包 §4 同一处境）：`MemberPointsView` 保留 `records/total/size/current/pages`
   五个键（原 `Page` 的 `orders/optimizeCountSql/searchCount/maxLimit/countId` 不承载分页信息，未保留）；
   `PointAccountView` 保留 `id/tenantId/customerId/programId/availablePoints/frozenPoints/version` 与
   四个审计列名——**审计时间戳字段仍在但是 `null`**（领域模型不承载审计字段，适配器只回填主键与版本）。
   `PointLedgerRow` 保留 8 个键（含真实列 `createdAt`）。收紧字段集与恢复审计值都要单独一次带版本说明的变更。

**迁移时确认过、值得记住的一条**：`CstPointLedgerPo` 上**没有** `@TableField(exist = false)` 字段
（逐字段核对 + 与 DDL 对齐），所以积分适配器不需要像钱包那样跳过展示字段；
`PointLedgerEntry` 保留 `createdAt`（NOT NULL 真实列，且是响应字段），但不带 `createdBy/updatedBy/updatedAt`。

### 1.4 会员切片（`Member` / `MemberNameToken` / 清理）—— 生产侧与测试侧完成，待提交

- `domain/member/model/{Member,MemberNameToken,MemberFilter}.java`
- `domain/member/repository/{MemberRepository,MemberNameTokenRepository,MemberImBindingRepository,MemberPurgeReferenceRepository}.java`
- `domain/account/model/AccountPurgeCount.java`（储值/积分两条清理路径共用的计数形状）
- `infra/persistence/repository/MemberRepositoryAdapter.java`
- `infra/persistence/repository/MemberNameTokenRepositoryAdapter.java`
- `infra/persistence/repository/MemberImBindingRepositoryAdapter.java`
- `infra/persistence/repository/MemberPurgeReferenceRepositoryAdapter.java`
- `application/MemberDisplay.java`（应用层的「领域档案 + 展示值」载体）
- `api/dto/MemberView.java`
- 重写 `MemberApplicationService`、`MemberPurgeApplicationService`、`CustomerOverviewApplicationService`
- 改造 `MemberController`（`Page<MemberView>` / `MemberView`）、`MyAssetsController`（`currentMember()` → `MemberDisplay`）
- 扩端口：`WalletAccountRepository` / `PointAccountRepository` 各加
  `listAll` / `deltaForPeriodByStores` / 窄口清理方法

**该切片的六个要点**：

1. **`CstMemberPo` 有五个 `@TableField(exist = false)` 的展示字段**
   （`name` / `phone` / `accountType` / `imAccountName` / `imAccountDeleted`）：它们**是既有响应字段**
   （旧契约就是整行 PO），但**不是列**。领域模型 `Member` 一个都不承载；
   展示值走 `MemberDisplay`（应用层）→ `MemberView`（api/dto）。这条边界有两个测试各钉一半：
   `MemberRepositoryAdapterTest` 断言 INSERT/UPDATE 的列集合与实体赋值里没有它们，
   `MemberResponseContractTest` 断言序列化出来的键**一个不少**。
   **该适配器测试做过变异验证**：把 `.set(CstMemberPo::getName, "x")` 加回 `updateImBinding` 后，
   `MemberRepositoryAdapterTest` 立刻 3 个错误，报的正是
   `can not find lambda cache for this property [name]`，另加 1 个集成测试错误；去掉后全绿。
2. **`MemberFilter` 是唯一的筛选条件载体**（关键词/摘要/盲索引命中 id/等级/状态/是否未关联/门店/
   建档时间闭区间/偏移/上限）。三个列表（客户、积分、储值）都只能经它表达，
   "同一份 `joined_at` 过滤实现"从注释变成了结构约束。
   两个容易踩的点：① 计数语句必须用**同一个 WHERE 但不带 ORDER BY / LIMIT**
   ——count 里带 ORDER BY 在 H2 上直接报 `Column "id" must be in the GROUP BY list`（实测），
   MySQL 上也只是白写；② 偏移不是页大小的整数倍时，靠 MyBatis-Plus 分页插件按"页码"换算会悄悄错页，
   所以列表走显式 `LIMIT/OFFSET`（与钱包/积分适配器同一写法）。
3. **`pointsList` 的批量取数换了口径但语义不变**：原先按当页 memberIds `IN` 查积分账户，
   现在端口加了 `findByCustomers(List<Long>)`（仍是当页 `IN` 查，不是全表）。
   注意**不要**为了"省一次查询"改成 `listAll()`：那会把整租户的账户读进来。
4. **清理用例的闸门顺序与范围逐字段核对后原样保留**（没有顺手"修"）：
   ① IM 关联（含"IM 账号已不存在"的放行路径，查询失败 fail-closed 仍拒绝）；
   ② 跨域引用体检（读不到也 fail-closed，回同一句 `MEMBER_HAS_REFERENCES`）；
   ③ 删姓名索引 → 清零额储值账户（流水→账户）→ 清积分账户 → 删档案。
   积分那条有个**刻意保留的不对称**：账户行删该会员**全部**积分账户，流水**只对零额账户**删
   （原实现如此）。也就是说非零账户的流水不会被这条路径删掉——账面事实不会因清理垃圾档案被悄悄抹掉。
   改变它是行为变更，要单独一次带版本说明的提交。适配器测试把这条不对称钉住了。
5. **响应字段名逐个保留**（与钱包/积分同一处境）：`MemberView` 保留 `CstMemberPo` 的**全部**键
   （含 `tenantId` / `nameCipher` / `phoneCipher` / `phoneDigest` / `version` /
   `createdBy` / `createdAt` / `updatedBy` / `updatedAt`）；审计时间戳在重构后是 `null`
   （领域模型不承载审计字段），**字段仍在、形状不变**。
   列表外层仍是 MyBatis-Plus `Page`（`records/total/size/current/pages` 就是既有外形，换名字同样是破坏），
   只有 `records` 的元素从 PO 换成了视图。`MemberMeResponse` 与 `MemberWalletRow` 的形状没动。
   `MemberResponseContractTest` 把上面这些序列化键逐个钉住（含 `pages` 的算式）。
6. **`AccountPurgeCount` 是一个刻意共享的小类型**：储值/积分两条清理路径的"删了几行"形状完全相同，
   共用它可以让审计详情的六个计数由调用点直接写出来，而不用两边各定义一个 record。

**该切片顺带补上的覆盖缺口**：会员切片的 SQL 形状/列保真此前**没有适配器测试**
（`MemberKeywordSearchTest` mock 的是 Mapper、断的是应用层拼出来的 SQL），
现在拆成三层：`MemberSearchFilterTest`（应用层断言端口收到的条件对象，
包括"纯数字关键词走摘要"与"无租户上下文不查盲索引"）+
`MemberRepositoryAdapterTest`（19 个测试，真实 Wrapper 断言 OR 组、过滤列、显式分页、
计数不带 ORDER BY/LIMIT、单行读取、写入列集合）+
`MemberNameTokenRepositoryAdapterTest`（7 个测试，钉住"先删后插 + 失败补偿删除"：
半套索引会被回填的「没有 token 行」判据误判为已完成，此后按姓名静默搜不到）。

## 2. 本域最严重的发现：**看起来有乐观锁，实际没有**

`cst_wallet_account.version` **存在**，但：

- PO 上**没有 `@Version` 注解**
- 全仓**没有 `OptimisticLockerInnerInterceptor`**

所以 `accountMapper.updateById(account)`（在 `recharge`/`refund`/`consume`/`release` 四处）**不会附加任何版本条件**，就是无条件整行覆盖：

```java
long balanceAfter = account.getAvailableAmount() - amount;
if (account.getAvailableAmount() < amount) throw ...;   // 判定
account.setAvailableAmount(balanceAfter);               // 覆盖
bump(account);                                          // version +1，但没人检查它
accountMapper.updateById(account);                      // 无条件写回
```

两个并发消费都读到余额 100、都判定够扣 80、各自算出 `balanceAfter=20` 并写回：
**一笔钱被扣两次，而账本只记一次成功**。

这比"没有防护"更难发现，因为**代码读起来像是有乐观锁**——`version` 被维护、被递增，只是从不参与判定。
排查方法值得复用：**看到 `version` 字段就要确认三件事**——PO 有 `@Version` 吗？有乐观锁拦截器吗？
`updateById` 真的是 `WHERE version = ?` 吗？**三者缺一，version 就只是装饰。**

修复：`updateBalanceWithVersion(account, expectedVersion)` → 单条 `... AND version = ? SET ..., version = version + 1`。
服务侧 CAS 冲突后重读重算（最多 3 次），用尽则 **409 `WALLET_CONCURRENT_MODIFICATION`**，
**绝不降级为无条件写入**。

**写入顺序是刻意的**：先确认余额 CAS 成功，**再**写流水。反过来在 CAS 失败时会留下"钱动过"的流水而余额没动，
而**对账只能相信流水**。

## 3. 不要"顺手修"的既有语义

1. **懒初始化**：账户**只在首次充值**时创建。读路径对没有账户的会员返回**零额只读视图**（`id == null`），
   **不落库、不抛 404**。消费/退还对没有账户的会员回 **422 `LEDGER_INSUFFICIENT`**（不是 404），
   释放（RELEASE）是**无副作用空操作**。为一次失败的扣减建账户会把"余额不足"变成持久化的空账户。
2. **零额视图 `id == null` 是刻意的**：它必须不可能被当成真实账户写回或审计。
3. **金额一律 `long` 最小货币单位整数**，不用 `BigDecimal`；`tokenAmount`（代币数量）是唯一的小数字段，
   且**只是展示**——绝不参与任何金额合计。
4. **跨币种储值消费默认关闭**（`CURRENCY_CORRIDOR_DISABLED`）：不做汇率换算，也不静默按账户币种消费。
5. **账本只追加**：绝不 update/delete 历史流水；币种由账本自证（快照），不依赖回查账户。
6. **`decorate` 是服务端唯一代币换算点**（`余额 ÷ 100 × ratio`，HALF_UP）。C 端/后台/B 端不得各自换算。
7. **失败留痕只走 `recordAsync`，业务异常原样抛出**：留痕绝不改变业务结果；detail 只放会员 ID，
   **金额与支付凭证不入详情**。
8. **积分不是钱包**（别把两者的懒初始化混为一谈）：积分账户由 C 端入口
   `ensureAccount` **显式建出**，`balance` / `view` 对没有账户的会员就是 **404 `POINT_ACCOUNT_NOT_FOUND`**，
   没有"零额只读视图"。积分没有币种字段，流水里的 `currency_code` 只是当时租户币种快照。
9. **积分的审计入口只有 `adjust`**（`points.adjust`，成功与失败都留痕）；
   `redeem`/`release`/`earn` 是内部调用，本来就不留痕——不要顺手加，那会改变审计口径。
10. **积分账本的写入顺序是"先 CAS 再流水"**（见 §1.3 要点 2）：对账只能相信账本，
    所以绝不能出现"账本说动过、余额没动"。

## 4. 一处我**没有**顺手做的收紧

钱包响应此前直接返回 `CstWalletAccountPo`，即 **API 契约就是持久化行**——`tenantId`、`createdBy`、
`createdAt`、`updatedBy`、`updatedAt` 一直是响应的一部分。

按 standards/10 D2 这些**不该出现在契约里**，但它们**已经是契约**（B 端与 C 端可能已在读）。
新视图 `WalletAccountView` **保留全部字段**并在 Javadoc 写明：收紧需要**单独一次带版本说明的变更**。
**紧缩 API 契约不该混在重构里偷偷做。**

同理，`WalletLedgerPageView` 刻意镜像 MyBatis-Plus `Page` 的序列化字段名
（`records`/`total`/`size`/`current`/`pages`）——调用方读的就是这些名字，换名字同样是破坏。

## 5. 尚未完成

| 项 | 状态 |
|---|---|
| 钱包相关 5 个测试文件 | **已迁到端口**（`WalletApplicationServiceTest`、`WalletTokenDisplayServiceTest`、`WalletTokenResponseContractTest`、`WalletTokenResponseWiringTest`、`CustomerIntegrationTest`） |
| 积分切片（`PointApplicationService` 等） | **已完成**（生产侧 + 测试侧，见 §1.3） |
| 积分切片测试 | `PointApplicationServiceTest`（27，端口语义）、`PointAccountRepositoryAdapterTest`（16，SQL 形状/列保真）、`PointAccountIntegrationTest`（8，H2 真库）、`WalletTokenResponseContractTest`/`WiringTest` 的积分部分已迁 |
| 会员切片（`MemberApplicationService` 754 行、`MemberPurgeApplicationService` 等） | **已完成**（生产侧 + 测试侧，见 §1.4） |
| 会员切片测试 | `MemberApplicationServiceTest`（14）、`MemberSearchFilterTest`（8，取代 `MemberKeywordSearchTest`）、`MemberNameIndexRebuildTest`（8）、`MemberPurgeApplicationServiceTest`（7）、`MemberRepositoryAdapterTest`（19，SQL 形状/列保真）、`MemberNameTokenRepositoryAdapterTest`（7，先删后插+失败补偿）、`MemberResponseContractTest`（4，序列化字段名）、三个 H2 集成测试已迁到端口 |
| `domain` 包整体 | 已建 `point` / `wallet` / `member` 三个子域（+ `account` 一个小类型） |

`platform-customer-service` 当前 **227 测试全绿**（迁移前 126；积分切片后 172），全部阻断门禁通过；
客户域 `po-containment` 21 → **0**，`service-api-boundaries` 9 → **0**。

**客户域的 `po` 引用已经清零**：`MemberApplicationService`（含 `pointsList` 直接读 `CstPointAccountPo`）、
`MemberPurgeApplicationService`（直接删 `cst_point_account` / `cst_point_ledger`）、
`CustomerOverviewApplicationService`（4 行）、`MemberController.list`（返回 `CstMemberPo`）、
`MyAssetsController`（`CstMemberPo`）全部改走领域端口（见 §1.4）。
四道跨域只读查询（账号类型、IM 关联、引用体检、IM 标识/昵称）也从 `MemberMapper` 收进了
`MemberImBindingRepository` 与 `MemberPurgeReferenceRepository` 两个端口，
所以适配器之外再也看不到 `MemberMapper`。

**本域之外仍然存在的门禁违规只有 `platform-services/order`**（`po-containment` 29、
`service-api-boundaries` 21），那是另一个切片正在进行中的工作，不在本域范围内。

### 5.1 缺口已补：积分切片有适配器测试了

§5.1 记的缺口（CAS 的 SQL 形状与列集合没有测试锁定）在积分切片**已补上**：
`PointAccountRepositoryAdapterTest` = 真适配器 + mock mapper，覆盖
CAS 带 `version = ?` 条件、`version = version + 1` 在 SQL 里而非 Java 加一、只 set 真实列、
`LIMIT/OFFSET` 与 id 倒序、幂等键计数、INSERT 默认值与时间戳。

<details>
<summary>写这个测试时踩到的两个坑（下一个切片会再遇到）</summary>

1. **lambda 列名解析需要 `TableInfo` 注册**。单测里没有 Spring，`TableInfoHelper` 从未被调用，
   于是 `eq()/set()` 只登记方法引用、不解析列名，`getParamNameValuePairs()` 是空的、
   `getSqlSet()` 里没有列——表现为"断言莫名其妙地空"，不是报错。
   用 `TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Po.class)`
   在 `@BeforeAll` 里注册即可。
2. **条件参数值是 `getTargetSql()` 触发、懒加进参数表的**（MyBatis-Plus 3.5.17）：
   `set` 的值在 `set()` 时就进了表，`eq` 的值要等 SQL 求值才进。
   所以断言顺序必须是"先求值 SQL，再读参数表"。另外条件值按 Java 类型装箱（`Integer` vs `Long`），
   `containsValue(11L)` 会假失败——按数值比较。
</details>

`WalletAccountRepositoryAdapterTest` 已补（11 个测试，提交 `78ba35f`），缺口的最后一环闭合。

**它钉住的是"曾经真的炸过"的那一条**：CAS 的 SET 列集合里
`token_amount` / `token_brand_name` / `account_opened` **必须不出现**——三者是
`@TableField(exist = false)` 的展示字段，不是列；写进去会在运行时抛
`can not find lambda cache for this property`，而 `test-compile` 完全干净（见 §5.3）。

**这个测试被验证过，不是"看起来在测"**：把 `tokenAmount` 的 `.set(...)` 加回适配器后，
该类 11 个测试中 **4 个失败**；去掉后全通过。

> **踩坑记录**：我第一次做这个验证时用了 `-Dtest=... -am`，结果在上游模块 `common`
> 报 `No tests matching pattern` **根本没跑到目标测试类**——那个非零退出码什么也没证明。
> 改成整模块 `test` 才拿到预期的 4 个错误。**这个陷阱在本会话里已经骗过我两次。**

### 5.2 又一个"从未真跑过"的运行时洞

`src/test/resources/db/test-migration/V1__cst_h2_schema.sql` **原先只有钱包两张表**。
后果：**积分写路径从未碰过真实数据库**——积分切片的集成测试是第一批真正跑到积分表的测试。
已补 `cst_point_account`、`cst_point_ledger`、`cst_point_rule_config`（镜像生产列集；
`cst_point_rule_config` 是必需的，因为 `PointRuleConfigApplicationService.resolve` 会读它）。

这与 §5.3 钱包 `@TableField(exist = false)` 缺陷能溜过去是**同一个洞**：H2 测试 schema
只镜像"当前测试用到的表"，于是**任何新写路径默认都没有真实数据库覆盖**。
**新增写路径时，先确认它的表在测试 schema 里存在**——否则整套集成测试都不会碰它。

### 5.3 本轮真实炸掉的一次：`@TableField(exist = false)` 被写进 UPDATE

CAS 适配器最初对 `tokenAmount` / `tokenBrandName` / `accountOpened` 调了 `.set(...)`。
这三个字段在 PO 上标了 **`@TableField(exist = false)`**（DDL 里也没有列），于是 MyBatis-Plus 抛：

```
can not find lambda cache for this property [tokenAmount] of entity [CstWalletAccountPo]
```

后果：**`recharge`/`consume`/`refund`/`release` 全部在运行时失败**，而 **`test-compile` 完全看不出来**。

两个教训，都值得记住：

1. **`exist = false` 的字段绝不能进入任何 UPDATE/INSERT 的列引用。** 领域模型里可以有它们
   （为了让视图从一处装配），但**列集合必须只来自真实 DDL**。
2. **这一条只有集成测试能抓到。** 该 CAS 路径在集成测试真正跑起来之前**没有任何运行时覆盖**——
   单元测试用 mock 适配器，编译期又查不出列名。**"编译通过 + 单测绿"不等于这条 SQL 能跑。**

**积分切片顺带补上了这条运行时的路**：`src/test/resources/db/test-migration/V1__cst_h2_schema.sql`
此前**只有钱包两张表**（`cst_wallet_account` / `cst_wallet_ledger`）与 outbox，没有积分表，
所以积分写路径在真库上从来没有跑过。现在补了 `cst_point_account` / `cst_point_ledger` /
`cst_point_rule_config`（后者的缺失一度让积分集成测试直接报 `Table not found`）。
**新切片加集成测试时，先确认 H2 测试 schema 里有这张表**，否则第一个报错会是 Flyway/表缺失而不是你要验的东西。


## 6. 验证命令

```powershell
# 编译（主代码应 0 错误）
.\mvnw.cmd --offline -B -ntp -pl platform-services/customer/platform-customer-service -am test-compile

# 测试（先删 target/surefire-reports 以取新鲜计数）
.\mvnw.cmd --offline -B -ntp -pl platform-services/customer/platform-customer-service -am test

# 两个分层门禁（报告模式）
powershell -File scripts/validate/validate-po-containment.ps1 -ReportOnly
powershell -File scripts/validate/validate-service-api-boundaries.ps1 -ReportOnly
```

## 7. 本域已踩过的坑（给后来者）

- **领域模型不得 import `io.openware.infrastructure.*`**（`validate-domain-model-purity.ps1` 第 2 条）。
  我曾把 `Currency` 直接引进 `WalletAccount` 做归一——**违反**。正确做法：领域只提供
  `currencyCodeOrNull()` / `differsFromNormalizedCurrency(normalized)`，归一留在应用层。
- Checkstyle `UnusedImports` 会在编译通过后失败，**看日志里的 `[ERROR]` 行而不是只看退出码**。
- PowerShell 多行 `.Replace` 在本会话**反复静默失败**（空白差异）。多行改动一律用 `edit` 工具。
