> Status: historical
> Archived: 2026-10-19
> Superseded-by:
> Original-location: docs/renovation/PAYMENT_LAYERING_HANDOFF.md

# Payment 域分层迁移交接文档

**模块**：`common-services/payment/common-payment-service`
**状态**：**工作树未提交，当前不绿**——`main` 源码 0 错误，但 **7 个测试文件编译失败**。
**恢复后的第一个动作**：迁完测试（见 §3），再提交。**不要在此状态提交。**

---

## 1. 已完成且编译通过

### 1.1 领域层（`src/main/java/io/openware/common/payment/domain/`）

本轮之前 payment **完全没有 `domain` 包**——13 张表、14 个 mapper，应用层 DTO 自带 `from(SomePo)`
工厂直接 import 持久化对象。因此这不是"迁移已有模型"，而是**从零建模型**。

| 聚合 | 模型 | 端口 | 解析/规则 |
| --- | --- | --- | --- |
| 渠道 | `channel/model/ChannelConfig` | `channel/repository/ChannelConfigRepository` | `channel/service/ChannelConfigResolver` |
| 规则 | `rule/model/RefundRuleConfig`、`rule/model/ClosingRuleConfig` | `rule/repository/PaymentRuleRepository` | `rule/service/RefundRuleResolver` |
| 对账 | `transaction/model/PaymentTransaction` | `transaction/repository/PaymentTransactionRepository` | `transaction/service/ReconciliationSummaryBuilder` |
| 支付意图 | `intent/model/PaymentIntent` | `intent/repository/PaymentIntentRepository` | — |
| 交班 | `shift/model/Shift` | `shift/repository/ShiftRepository` | — |
| 日结 | `closing/model/DailyClosing` | `closing/repository/DailyClosingRepository` | — |
| 退款 | `refund/model/Refund` | `refund/repository/RefundRepository` | — |
| 收款单 | `collect/model/PayCollect` | `collect/repository/PayCollectRepository` | — |
| 支付方式 | `method/model/TenantPaymentMethod` | `method/repository/TenantPaymentMethodRepository` | — |

`RefundRuleResolver` 与 `ReconciliationSummaryBuilder` 有专门领域测试
（`domain/rule/RefundRuleResolverTest` 128 行、`domain/transaction/ReconciliationSummaryBuilderTest` 113 行）。
**`ChannelConfigResolver` 目前没有专门测试**（其行为仍只由 `PaymentMethodChannelAuditTest` 等间接覆盖）。

### 1.2 适配器（`src/main/java/io/openware/common/payment/infra/persistence/repository/`）

新增：`ChannelConfigRepositoryAdapter`、`PaymentRuleRepositoryAdapter`、
`PaymentTransactionRepositoryAdapter`、`PaymentIntentRepositoryAdapter`、`ShiftRepositoryAdapter`、
`DailyClosingRepositoryAdapter`、`RefundRepositoryAdapter`、`PayCollectRepositoryAdapter`、
`TenantPaymentMethodRepositoryAdapter`。

适配器是**行对象与领域投影的唯一翻译点**；领域层不认识 PO/行对象。

### 1.3 已改写的 DTO（`application/`）

`ChannelConfigDto`、`ShiftDto`、`DailyClosingDto`、`PayIntentDto`、`RefundDto`、`TenantPaymentMethodDto`
——全部由 `from(SomePo)` 改为 `from(领域模型)`，并**去掉 `createdBy`/`updatedBy`**（见 §4.5）。

### 1.4 已迁移完成的应用服务

| 服务 | 现状 |
| --- | --- |
| `ChannelApplicationService` | 0 个 mapper/PO import，已用 `ChannelConfigRepository` + `ChannelConfigResolver` |
| `PaymentRuleApplicationService` | 0 个 mapper/PO import，已用 `PaymentRuleRepository` + `RefundRuleResolver` |
| `ReconciliationApplicationService` | 0 个 mapper/PO import，已用 `PaymentTransactionRepository` + `ReconciliationSummaryBuilder` |
| `RefundApplicationService` | 0 个 PO import；已用 `RefundRepository`（含 `updateStatusGuarded`，见 §4.1）；**仍留 `OrderBillingMapper`**（L5/L39/L103，用于读订单币种） |
| `PaymentQueryApplicationService` | 已用 6 个端口（`ShiftRepository`/`PaymentIntentRepository`/`DailyClosingRepository`/`RefundRepository`/`PayCollectRepository`/`PaymentTransactionRepository`），0 个 PO/mapper import |
| `PaymentMethodApplicationService` | 0 个 PO/mapper import |
| `CashierApplicationService` | **部分迁移**：已用 `ShiftRepository` + `DailyClosingRepository`；**仍注入 `PayIntentMapper`/`RefundMapper`/`PayCollectMapper`**（L198 `sumCashSucceeded`、L349 `selectList`、L405 `selectConfirmedByStoreAndWindow`、L430 `sumRefundedByCurrency`） |

---

## 2. 未完成

| 项 | 说明 |
| --- | --- |
| **`CollectApplicationService`** | 742 行，**5 个 PO import、5 个 mapper import**，**完全未迁移**。这是支付域最后一块，也是最大一块。 |
| **`CollectApplicationServiceTest`** | 645 行，**未改动**（因为被测类未动）。 |
| **`CashierApplicationService` 的 3 个 mapper** | 见 §1.4 末行；需要为"按门店+窗口汇总现金/退款"补端口方法。 |
| **`RefundApplicationService` 的 `OrderBillingMapper`** | 读订单币种；需要 `OrderBilling` 侧端口或纳入退款端口。 |
| **`PaymentOverviewApplicationService`** | 仍依赖 `PaymentOverviewMapper`（见 §3 注）。 |

**当前门禁计数（payment）**：`po-containment` = **6**
（`CollectApplicationService` ×5 + `CashierApplicationService` ×1）；
`service-api-boundaries` = **4**（`CashierApplicationService`、`CollectApplicationService`、
`PaymentOverviewApplicationService`、`RefundApplicationService`）。
全域：`po-containment` = 106，`service-api-boundaries` = 50。

---

## 3. 测试迁移（恢复后的首要工作）

**12 个测试文件中，5 个干净、7 个编译失败。**

### 3.1 干净（无需改动）

`InternalPaymentQueryControllerTest`(87)、`OrderCollectionsControllerTest`(105)、
`PaymentChannelCurrencyCapabilityTest`(66)、`PaymentMethodChannelAuditTest`(164)、
`RefundRuleResolverTest`(128)、`ReconciliationSummaryBuilderTest`(113)。

> `PaymentMethodChannelAuditTest` 已在早前提交中改为端口替身，本次 DTO 改写不再影响它。

### 3.2 需迁移的 7 个文件

| 测试文件 | 行数 | PO 引用 | mapper 引用 | 替换关系 |
| --- | --- | --- | --- | --- |
| `api/controller/PaymentTimeRangeFilterTest.java` | 239 | 4 | 6 | `ShiftMapper`→`ShiftRepository`、`PayIntentMapper`→`PaymentIntentRepository`、`RefundMapper`→`RefundRepository`、`DailyClosingMapper`→`DailyClosingRepository` |
| `application/CashierApplicationServiceTest.java` | 733 | 3 | 5 | `ShiftMapper`→`ShiftRepository`、`DailyClosingMapper`→`DailyClosingRepository`；`PayIntentMapper`/`PayCollectMapper`/`RefundMapper` 依赖 `CashierApplicationService` 自身先迁完（§2） |
| `application/OrderCollectionsQueryTest.java` | 301 | 6 | 6 | `ShiftMapper`→`ShiftRepository`；其余同 `PaymentQueryApplicationService` 的 6 个端口 |
| `application/RefundApplicationServiceTest.java` | 277 | 1 | 2 | `RefundMapper`→`RefundRepository`；`OrderBillingMapper` 保留（被测类仍有它） |
| `application/PaymentIntegrationTest.java` | 502 | 3 | 4 | `PayCollectMapper`→`PayCollectRepository`、`PayIntentMapper`→`PaymentIntentRepository`、`PayTransactionMapper`→`PaymentTransactionRepository`；`OrderBillingMapper` 保留 |
| `application/CollectApplicationServiceTest.java` | 645 | 4 | 4 | **暂不动**——被测类 `CollectApplicationService` 未迁移；迁完该类后再改 |
| `application/PaymentMethodChannelAuditTest.java` | 164 | — | — | 已干净（列出仅为对齐清单） |

### 3.3 编译期证据（实测，`mvn ... clean test-compile`）

`main` 源错误 **0**；测试错误 **7 条唯一**（14 条含重复上报）：

```
PaymentTimeRangeFilterTest.java:[64,48]        ShiftMapper  无法转换为 ShiftRepository
CashierApplicationServiceTest.java:[46,83]     ShiftMapper  无法转换为 ShiftRepository
CashierApplicationServiceTest.java:[415,48]    DailyClosingPo 无法转换为 DailyClosing
OrderCollectionsQueryTest.java:[59,48]         ShiftMapper  无法转换为 ShiftRepository
RefundApplicationServiceTest.java:[24,81]      RefundMapper 无法转换为 RefundRepository
RefundApplicationServiceTest.java:[45,72]      RefundMapper 无法转换为 RefundRepository
RefundApplicationServiceTest.java:[95,38]      RefundMapper 无法转换为 RefundRepository
```

> 任务书提到"8 个具体错误"，实测为 **7 条唯一**（其中 `RefundApplicationServiceTest` 的 `RefundMapper`
> 在三处上报）。第 8 条预期中的 `OrderCollectionsQueryTest` 端口替换错误与 L59 那条同源。
> 以实测为准。

---

## 4. 必须保留的设计规则（附理由）

### 4.1 `RefundRepository.updateStatusGuarded`——本表**没有版本列**

`pay_refund` **没有 `version` 列**（已核 DDL）。退款审批是 `PENDING → APPROVED/REJECTED` 状态机，
原实现是**读-判-写**：`requireRefund(id)` → `requireStatus(po, "PENDING")` → 无条件 `updateById(po)`。

**两个并发审批者会双双通过 `PENDING` 检查**，各自把状态写为 `APPROVED`——
对退款而言**这意味着同一笔申请被批准两次**。

修复：**状态守卫的条件更新**，判定与写入在同一条 SQL：

```sql
UPDATE pay_refund SET ... WHERE id = ? AND status = 'PENDING'
```

受影响 0 行 = 状态已被他人改变 → 调用方报冲突，**不得当作成功**。
**不要**照搬 pricing/channel 的 `updateWithVersion`——那会是在发明一个不存在的机制
（我一度给 `Refund` 加了 `version` 字段，查 DDL 后移除）。

### 4.2 `ChannelConfigRepository.updateWithVersion`——真 CAS

`ChannelApplicationService.setEnabled` 原是**读出来比对版本 → 无条件 UPDATE + 用读到的值加一**。
两个并发写者双双通过比对，后写者覆盖前者，**双方都收到成功**。
现为单条 `... SET version = version + 1 WHERE id = ? AND version = ?`。
版本自增写在 **SQL 里**，避免用陈旧值覆盖并发写入已涨上去的版本号。

上层的版本比对**只作快速失败**（给出可读的 409），**真正的并发判定在那条条件 UPDATE 里**。

### 4.3 `ChannelConfigResolver` **不得**按 `enabled` 过滤

配置列表**必须显示已关闭的渠道**——那是运营**重新打开**它们的唯一入口；关心开关值的是
**可用性判定**（`ChannelConfig.enabledForUse()`）。

若在 resolver 里按 `enabled` 过滤，**指定门店的列表会静默少几行**，运营再也开不回来。
（我在 `RefundRuleResolver` 初稿里犯过**同一个**错并改回。）

### 4.4 `RefundRuleResolver` 的两条路径**刻意不同**

| 取值 | 层级链 |
| --- | --- |
| 审批阈值 | 门店覆盖 > 业态默认 > 租户默认 |
| **线下退款开关** | **只看业态默认 > 租户默认，门店行不参与** |

第二条是**资金边界**：线下退款意味着钱在系统外离开。若门店行能决定它，
**门店页面就能绕过总部策略打开它**。门店行即使带该字段（统一安全默认值）也不被读取。

测试明确写出：门店行说"关"、业态行说"开" → **答案必须是开**。

### 4.5 记账字段 `createdBy`/`updatedBy` 已从 payment DTO 去掉

已核实**无任何调用方读取**它们（`git grep` 为空）。它们属审计事实，由审计留痕承载
（standards/10 的 D2 规则）。**`createdAt`/`updatedAt` 保留**——对运营可读的时序。

**注意**：`Refund.requestedBy`/`approvedBy`、`DailyClosing.submittedBy`/`reviewedBy`
是**业务角色**（谁申请/谁审批/谁提交），**不是**记账字段，**保留**。

### 4.6 两处刻意的 `IS NULL` 作用域查询

`ChannelConfigRepository.findByScope` 与 `RefundRuleConfig` 路径都用
`.isNull(storeId == null, ...).eq(storeId != null, ...)`。

**理由**：SQL 里 `= NULL` 永远不成立。用等值比较会把"租户级配置已存在"误判成"不存在"，
于是每次写入都插入新行——而作用域唯一键会让它**直接失败**。

另外 `ChannelConfigRepository.findByScope` **不过滤 `status`**：停用行**必须可复用**，
过滤掉会**同作用域插入第二行并撞唯一键**。

### 4.7 区间**左闭右开**（`listSucceededBetween`）

`PaymentTransactionRepository.listSucceededBetween` 用 `ge(from)` + `lt(to)`。
那两个边界通常是**相邻两天的分界点**；用闭区间（`le(to)`）会把**午夜那一笔同时算进两天**，
**对账金额静默翻倍**。

（对比：`PaymentQueryApplicationService` 的四个列表是**闭区间**、由 `TimeRangeParams.parse`
把日期形态收口到当天 00:00:00.000 / 23:59:59.999——这是各列表既有的、不同的口径，
**不要统一**，除非明确要改语义。）

### 4.8 币种必须参与分组（`ReconciliationSummaryBuilder`）

把 CNY 与 USD 的毛额/手续费相加会得出**没有业务含义的"净额"**，
而**调用方从数字上无法察觉**。因此每个渠道**按币种各出一行**；混币种时信封与 `total` 行
**都带 `null` 币种**，显式标注"这是跨币种合计"。

`PaymentQueryApplicationService.buildOrderCollections` 有同款规则
（`distinct.size() == 1` 才给单币种，否则只给 `mixedCurrency` 标记）。

---

## 5. 验证命令

```powershell
cd D:\projects\cnb-oss\open-im-server

# 编译 + 测试（先删报告目录以免读到上次结果）
Remove-Item 'common-services\payment\common-payment-service\target\surefire-reports' -Recurse -Force -ErrorAction SilentlyContinue
.\mvnw.cmd --offline -B -ntp -pl common-services/payment/common-payment-service -am test

# 三个门禁
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate\validate-domain-model-purity.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate\validate-port-contract.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate\validate-po-containment.ps1 -ReportOnly
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate\validate-service-api-boundaries.ps1 -ReportOnly
```

**基线**（本轮开始前，绿）：payment 模块 **144 测试 / 0 失败 / 0 错误**；
payment `po-containment` 21、`service-api-boundaries` 11；全域 121 / 57。

### 当前状态与恢复动作

- 工作树**未提交**，**不绿**：`main` 编译通过（0 错误），**测试编译失败 7 条**。
- **恢复后第一件事**：按 §3.2 迁移 6 个测试文件（`CollectApplicationServiceTest` 除外，
  它随 `CollectApplicationService` 一起做），使模块回到绿，**然后**才提交。
- **在此状态不要提交**——此前多次说明过"提交非绿状态"的做法是错的。

### 提交时的注意点

- 提交前跑一遍 §5 的三个门禁；`domain-model-purity` 与 `port-contract` 必须 exit 0。
- `.ps1` 脚本必须保持**纯 ASCII**。
- 提交信息里应记录 §4.1 与 §4.2 两个并发缺陷（它们无法靠读代码发现，需要并发测试或 DDL 核对）。
