# Customer 域分层交接（进行中）

本文记录 `platform-services/customer` 分层改造的**当前状态**、已经踩过的坑，以及**尚未完成**的部分。
与 `DOMAIN_LAYERING_HANDOFF.md`（resource）、`PAYMENT_LAYERING_HANDOFF.md`（payment）同一系列。

## 0. 一句话现状

**不要在这个状态下打包发布**：生产代码已全部编译通过，测试源码仍在迁移中（见 §5）。

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

### 1.2 储值账户与账本（`WalletAccount` / `WalletLedgerEntry`）—— 生产侧完成，待提交

- `domain/wallet/model/{WalletAccount,WalletLedgerEntry}.java`
- `domain/wallet/repository/WalletAccountRepository.java`（含 `LedgerPage` record）
- `infra/persistence/repository/WalletAccountRepositoryAdapter.java`
- `application/WalletApplicationService.java` 重写；`application/WalletTokenDisplayService.java` 改为面向视图
- `api/dto/{WalletAccountView,WalletLedgerPageView}.java`
- 4 个控制器改造：`WalletAdminController`、`InternalCustomerController`、`MemberController`、`MyAssetsController`

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
| 积分切片（`PointApplicationService` 等 10 个文件） | **未开始** |
| 会员切片（`MemberApplicationService` 758 行、`MemberPurgeApplicationService` 等） | **未开始** |
| `domain` 包整体 | 只建了 `point` 与 `wallet` 两个子域 |

`platform-customer-service` 当前 **126 测试全绿**，全部阻断门禁通过。

### 5.1 一个必须补的缺口：CAS 路径**没有适配器测试**

原服务测试里的 SQL 形状/列保真断言**已随迁移消失**（按测试层分工，它们本就该在适配器测试里）。
目前 **不存在 `WalletAccountRepositoryAdapterTest`**，后果是：

> **CAS 的 SQL 形状与列集合没有任何测试锁定。**

这不是理论问题——本轮就因此炸了一次（见 §5.2）。补一个适配器测试（真适配器 + 假 mapper）应当覆盖：
CAS 语句带 `version = ?` 条件、只 set 真实列、`version = version + 1` 在 SQL 里而非由 Java 加一、
以及 `LedgerPage` 的 id 倒序与 OFFSET/LIMIT。

### 5.2 本轮真实炸掉的一次：`@TableField(exist = false)` 被写进 UPDATE

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
