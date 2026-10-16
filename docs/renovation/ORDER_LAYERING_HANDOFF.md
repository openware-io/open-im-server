# Order 域分层交接

本文记录 `platform-services/order` 分层改造的**最终状态**、**子域边界**、**并发缺陷清单**，
以及**不要"顺手修"的既有语义**。与 `DOMAIN_LAYERING_HANDOFF.md`（resource）、
`PAYMENT_LAYERING_HANDOFF.md`（payment）、`CUSTOMER_LAYERING_HANDOFF.md`（customer）同一系列。

## 0. 一句话现状

**已完成**：`platform-order-service` **739 测试全绿**，order 域 `po-containment` **0**、
`service-api-boundaries` **0**，两个门禁均为**阻断**模式（无豁免）。
全仓 `po-containment` 与 `service-api-boundaries` 亦均为 **0**。

## 1. 子域与端口

| 子域 | 领域模型 | 端口 | 备注 |
|---|---|---|---|
| 商品目录 | `domain/catalog/model/{Product,ProductCategory,CatalogItem}` | `ProductRepository` / `ProductCategoryRepository` / `CatalogItemRepository` | 写路径返回 `Persisted<T>(aggregate, audit)` |
| 库存 | `domain/inventory/model/{InventoryMaterial,InventoryStock,InventoryTransaction}` | `InventoryMaterialRepository` / `InventoryStockRepository` / `InventoryTransactionRepository` | `InventoryStock` 是**已有骨架但从未被采用**，本次接上 |
| KTV | `domain/ktv/model/{KtvSession,KtvServerSession}` | `KtvSessionRepository` / `KtvServerSessionRepository` | 端口保留全部既有 CAS 语句 |
| 预约 | `domain/reservation/model/{Reservation,ReservationRuleConfig,ReservationStatus}` | `ReservationRepository` / `ReservationRuleConfigRepository` | 状态集合在 `ReservationStatus` 上定义一次 |
| 订单核心 | `domain/order/model/{Order,OrderItem,OrderVoidApproval,VoidRuleConfig}` | `OrderRepository` / `OrderItemRepository` / `OrderVoidApprovalRepository` / `VoidRuleConfigRepository` / `DailySerialRepository` | `Order` 是本域枢纽 |
| 跨上下文只读 | — | `domain/order/port/MemberLookupProvider` | 会员查询；**只读端口归调用方所有** |

## 2. 订单聚合与 13 个非列字段（本域最容易踩的坑）

`OrderPo` 有 **38 个字段**，其中 **13 个是 `@TableField(exist = false)`**：
`sessionId` / `roomResourceId` / `roomName` / `roomCode` / `sessionStatus` / `partySize` /
`serverId` / `serverName` / `roomElapsedSeconds` / `roomEstimatedFee` / `liveTotalAmount` /
`sessionOpenedAt` / `sessionClosedAt`。

**它们是"从 `ord_ktv_session` 联表读出的投影"，不是订单自身的业务事实**，因此：

- **不进 `Order` 领域模型**、**不进任何端口签名**，只住在 `api/dto/OrderView`；
- 读取路径用 `OrderView.withSessionProjection/withSessionId/withLiveTotalAmount` 回填；
- `OrderRepositoryAdapterTest` 断言这 13 个字段的**精确集合**，且 `toPo` **一个都不设**。

**为什么这条要写下来**：对 `exist = false` 的字段生成列引用会在**运行时**抛
`can not find lambda cache for this property [...]`，而 `test-compile` 完全干净——
这个缺陷在 customer 钱包适配器上真的发生过一次，只有集成测试抓到。

## 3. 五条条件写语句 = 并发的唯一判定点

`OrderMapper` 上五条手写 `@Update`，**都必须保留条件谓词**：

| 方法 | 谓词 | 少了它会怎样 |
|---|---|---|
| `updateAmountsAndStatusWithVersion` | `id = ? AND version = ?` + SQL 侧 `version = version + 1` | 两个并发结算都成功，后者覆盖前者的金额快照 |
| `updateStatusGuarded` | `id = ? AND status = ?` | 两个并发确认都成功（订单被确认两次） |
| `holdIfNotHeld` | `id = ? AND hold_at IS NULL` | 两次挂单的理由与时间互相覆盖 |
| `unholdIfHeld` | `id = ? AND hold_at IS NOT NULL` | 第二次其实什么都没改，审计却写了一条 |
| `voidIfStatus` | `id = ? AND status = ?` | 两次取消都成功，各释放一次会话、各写一条审计 |

**为什么挂单用 `hold_at` 而不是 `status`**：挂单是 `SERVING` 内的标记，**故意不改商业状态**；
按 `status` 做条件对并发挂单**完全无效**。判定列必须逐处选择，不能统一套用。

`OrderRepositoryAdapterTest` 分两层钉住它们：反射读 `@Update` 文本断言谓词与 SQL 侧自增仍在，
以及行为层断言「受影响 0 行 → `false`、1 行 → `true`」。

## 4. 全仓已修的 6 处并发缺陷（本域占 3 处）

| # | 位置 | 形态 |
|---|---|---|
| 1 | customer 储值余额 | **维护 `version` 却从不判定**——最隐蔽，代码读起来像有乐观锁 |
| 2 | payment 退款审批 | 无版本列，读-判-写 |
| 3 | payment 交班/日结 | 无版本列，读-判-写 |
| 4 | order 结算 | Java 比对 + 无条件写（金额快照被覆盖） |
| 5 | order `confirm`/`hold`/`unhold` | 读状态-判-写（**判定列逐处不同**） |
| 6 | order 取消 / KTV 订单写 / `recalculate` | 读-判-写金额或状态 |

**判据已写进 `standards/10`「乐观锁的可检验判据」**：
只要 `version` 存在，三问必须同时成立——PO 有 `@Version` 吗？注册了拦截器吗？
`updateById` 真的发 `WHERE version = ?` 吗？**缺一即视为未加锁**。
全仓 139 个 PO 中 25 个带 `version` 字段、`@Version` 数量为 **0**，所以**每一个都只能靠显式 CAS**。

### 4.1 已核实**本来就是对的**、不要"顺手修"

| 位置 | 为什么是对的 |
|---|---|
| customer 积分账户 | 已有真 CAS（`updateAccountOptimistic`），`updateById` 调用 0 次 |
| KTV 会话 / 服务人员会话 | `updateWithVersion` / `transferRoom` 是真 CAS，有专项锁测试 |
| 库存扣减 | `deductAvailable` / `increaseOnHand` 是单条条件 UPDATE（含 `on_hand_qty - reserved_qty >=` 守卫） |
| 作废审批 `reviewWithVersion` | 同时带 `version = ?` 与 `status = 'PENDING'` |

## 5. 不要"顺手修"的既有语义

1. **两处真实 TOCTOU 窗口未修**（预约域）：`assign-room` 的重叠读取 vs 写入（CAS 只保护**一行**，
   两个**不同**预约仍可能被分配到同一房间的重叠时段）；`create` 的 count-then-insert 超订守卫。
   **修它需要唯一键或行锁（schema 变更）**，所以写进端口 Javadoc 而不是发明 DDL 掩盖。
2. **商品编辑"清空描述不生效"**：`updateById` 的 `NOT_NULL` 策略让 null 永不进 SET。
   这是**迁移前就有的**缺陷，**文档化但未修**——改变行为属于另一次有意识的变更。
3. **商品 `update` 的列集合与迁移前逐列一致**：迁移**不得**顺手"修好"一个既有行为。
4. **审计字段不进领域模型**：需要时由适配器读回并经 `Persisted<T>(aggregate, audit)` 携带。
   **但视图保留全部字段名（值为 null）**——删字段是破坏已发布契约。
   `platform-order-service.json` 快照仍列着它们。
5. **`ReservationStatus` 上的状态集合定义一次**（`preArrival`/`occupiesSlot`/`openTableEligible`/
   `impliesArrivalOnOpenTable`/`arrivedOrConverted`）——不要在服务里另写一套集合字面量。
6. **`KtvSessionApplicationService.liveEstimate` 是唯一实时估算口径**（看板/账单/结台共用）。
7. **日序列建行被并发抢先**由端口表达成 `insertFirstIfAbsent == false`（适配器把唯一键冲突翻成
   `false`）；**这不是错误**，调用方下一轮走自增分支。把异常放出去会让"重试自增"路径彻底失效。

## 6. 测试分层（本域据此组织）

| 层 | 断言什么 | 例子 |
|---|---|---|
| 适配器测试（真适配器 + mock mapper） | **SQL 形状与列保真** | `OrderRepositoryAdapterTest`(16)、`OrderItemRepositoryAdapterTest`(12)、`OrderVoidApprovalRepositoryAdapterTest`(5)、`VoidRuleConfigRepositoryAdapterTest`(4)、`DailySerialRepositoryAdapterTest`(5) |
| 服务测试（mock 端口） | **端口调用语义** | 各 `*ApplicationServiceTest` |
| 控制器测试 | **响应形状与 JSON 键集** | `*ContractTest` / `*WebTest` |
| 集成测试（真 H2） | **写路径真的落库** | `*IntegrationTest` |

**适配器测试的两个必备项**：`@BeforeAll` 里 `TableInfoHelper.initTableInfo(...)`
（没有它 lambda 列名解析是**空操作**，断言会"莫名其妙地空"）；条件值按 Java 类型装箱，
**数值比较一律走 `longValue()`**（`Integer` 与 `Long` 用 `equals` 不相等）。

**并被验证过**：把 `tokenAmount` 的 `.set(...)` 加回钱包适配器 → 该类 11 个测试中 4 个失败；
把 `markExecuted` 的 version 条件删掉 → `OrderVoidApprovalRepositoryAdapterTest` 报
「必须有 version 条件，否则两个并发执行都会成功并各触发一次作废」。
**不能被证明能抓住缺陷的测试不算证据。**

## 7. 遗留

1. **OpenAPI 快照未重新导出**：`platform-order-service.json` 的 `KtvSessionPo` schema 缺少
   `storeId` / `businessType`（这两个字段在本次迁移**之前**就已存在，属快照陈旧，非本次引入）。
   重新导出需要按 `docs/contracts/openapi/README.md` 起服务（kind 部署），
   且**明令禁止手工编造快照**。
2. `MemberLookupProviderAdapter` 是单行委派，无独立适配器测试。
3. `Order.withAmounts/withStatus` 的未使用时间戳参数已移除；`held`/`voided` 的仍在使用
   （分别写 `hold_at`/`cancelled_at`），**不要一并删掉**。

## 8. 验证命令

```powershell
# 测试（先删 target/surefire-reports 以取新鲜计数）
.\mvnw.cmd --offline -B -ntp -pl platform-services/order/platform-order-service -am test

# 全部门禁（两个分层门禁现为阻断，无豁免）
powershell -File scripts/validate/invoke-engineering-validation.ps1 -Scope Changed
```

**不要用 `-Dtest=` 搭配 `-am`**：它会在上游模块报 `No tests matching pattern`，
**根本跑不到目标测试类**，那个非零退出码什么也证明不了（本会话骗过我两次）。
