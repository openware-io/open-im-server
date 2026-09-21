# KTV 预约 / 订单 / 会话 / 占用 状态流程梳理与缺陷修复

> 触发背景（2026-09-18，门店反馈）：运营在客人到店前 ~1 小时把包厢提前指定好，包厢收银台却把这张预约显示成
> **「客户已到店」**并给出**「到店开台」**按钮，统计卡里还算作「已预订 1 间」。
> 用户要求：`先把预约的状态流程 订单的状态流程重新完整梳理一遍 再看哪里有问题 再优化或者修复`。
>
> 本文是**唯一口径**：① 三张状态机的完整流转（谁写、允许从哪来、到哪去、什么入口触发）；
> ② 逐条缺陷与证据；③ 本次修复内容；④ 仍存在的已知缺口（需产品决策）。

## 1. 涉及的状态机

| 载体 | 状态列 | 取值范围 | 代码位置 |
| --- | --- | --- | --- |
| 预约 | `ord_reservation.status` | `PENDING / CONFIRMED / ARRIVED / CONVERTED / CANCELLED / NO_SHOW` | `platform-order-service` · `ReservationApplicationService` |
| 订单 | `ord_order.status` | `DRAFT / SERVING / WAITING_SETTLEMENT / WAITING_PAYMENT / COMPLETED / VOIDED`（+ 4 个不可达值，见 §3.2） | `OrderController` / `KtvSessionApplicationService` / `SettlementApplicationService` / `OrderCancellationApplicationService` / payment 域 `OrderBillingMapper` |
| 包厢会话 | `ord_ktv_session.status` | `RESERVED → OPEN ⇄ PAUSED → CLOSED`，异常 `CANCELLED` | `KtvSessionApplicationService` |
| 服务人员会话 | `ord_ktv_server_session.status` | `ORDERED → SERVING → ENDED`，异常 `CANCELLED` | `KtvServerSessionApplicationService`（独立，不随结台推进） |
| 资源占用 | `res_occupation.status` | `HELD → RELEASED / CANCELLED`（`RESERVED`/`IN_USE` 无写入点，见 §3.2） | `platform-resource-service` · `OccupationApplicationService` |

## 2. 预约状态流程

### 2.1 流转图（本次修复后）

```
                创建预约(房型)                  确认预约                到店登记（写 arrived_at）
   （无） ───────────────────▶ PENDING ──────────────▶ CONFIRMED ──────────────▶ ARRIVED
                                │  │                     │  │                       │
      分配包厢（只写 resource_id，不改状态）┘              │  └── 到店开台(隐含到店) ─┤
                                │                        │                          │
                                │   取消（原因必填）      │   取消（原因必填）        │ 开台（生成订单+会话+占用）
                                └────────▶ CANCELLED ◀────┘                          ▼
                                │                                                  CONVERTED
                                └── 已过预约开始时间仍未到店 ──▶ NO_SHOW
```

- **分配包厢（`assign-room`）不改状态**：提前把包厢指定好是「锁房/备房」，不是「客人已到」。
  到店事实只由两条路径写入 `ord_reservation.arrived_at`：`arrival`（显式到店）与 `openTable`（到店即开台，隐含）。
- `CONFIRMED` 是「已预订」：门店已受理，客人在路上；`ARRIVED` 才是「客户已到店」。

### 2.2 动作与状态矩阵（修复后）

| 动作 | 入口 | 允许来源状态 | 结果 | 写 `arrived_at` | 副作用 |
| --- | --- | --- | --- | --- | --- |
| 创建 | `POST /business/reservations` | — | `PENDING` | 否 | 房型校验 + 超订软保护（不锁具体包厢） |
| 确认 | `POST …/{id}/confirm` | `PENDING` | `CONFIRMED` | 否 | 审计 `reservation.confirm` |
| 分配包厢 | `POST …/{id}/assign-room` | `PENDING/CONFIRMED/ARRIVED` | **状态不变** | 否 | 写 `resource_id`（同房型/同门店/当前可用，fail-closed）；**且该包厢在本预约时段不得与其它未取消预约重叠**（409 `ROOM_RESERVED_OVERLAP`）；审计 `reservation.assign_room` |
| 到店登记 | `POST …/{id}/arrival` | `CONFIRMED` | `ARRIVED` | **是** | 审计 `reservation.arrival` |
| 到店开台 | `POST …/{id}/open-table` | `ARRIVED` 或 `CONFIRMED`（隐含到店） | `CONVERTED` | `CONFIRMED` 来源时写 | 生成 `ord_order`(DRAFT→SERVING) + `ord_ktv_session`(RESERVED→OPEN) + `res_occupation`(HELD)；回填 `order_id`；审计 `reservation.open_table`（`arrivalImplied`） |
| 取消 | `POST …/{id}/cancel` | `PENDING/CONFIRMED`（原因必填） | `CANCELLED` | 否 | 审计 `reservation.cancel`（原因是唯一留存处） |
| 未到店 | `POST …/{id}/no-show` | `PENDING/CONFIRMED` 且 `start_at ≤ now` 且未开台 | `NO_SHOW` | 否 | 审计 `reservation.no_show` |

预约**从不写资源占用**（预约锁的是房型，不是包厢），因此取消/未到店都不需要释放占用；真实占用只由开台产生。

### 2.3 到店时间必须在营业时间内（统一规则，2026-09-19）

- **营业时间**：门店级配置，键 `tnt_tenant_config.ktv_business_hours`（值 `18:00-05:00`），
  门店行覆盖租户默认行（`store_id=0`），都没有时用代码缺省 **18:00 – 次日 05:00**（KTV 夜间业态）。
  读法：`GET /admin/tenant/business-hours?storeId=`（后台）与内部端点 `/internal/tenant/business-hours`（order 域）。
- **判定**：左闭右开 `[open, close)`；`close < open` 表示跨自然日（18:00–05:00 覆盖当天 18:00 到次日 05:00，
  凌晨到店属于**前一个营业日**的时段，不是越界）；`open == close` 视为全天营业。
- **强制点只有一处**：`ReservationApplicationService.create`（409/422 `RESERVATION_OUT_OF_BUSINESS_HOURS`），
  C 端下单、B 端/后台代客预约、BFF 代转全走它；**预约结束时间不校验**（允许最后一场跨过打烊时间）。
- **客户端只约束选择器**：`GET /business/reservations/business-hours` 与服务端校验同源，
  C 端下单页据此过滤可选的到店时刻（原先硬编码只能选 12:00–23:00，凌晨时段根本选不到），
  后台「KTV 配置 → 营业时间」两行（租户默认 / 本门店覆盖）维护配置，预约管理页展示生效值并对越界的历史预约标注「非营业时段」。
- **降级**：租户服务不可达时**跳过校验并记 WARN**（放行），不使用缺省窗口硬拦 —— 各门店营业时间可能不同，
  误拦（客人下不了单）的代价大于「本次未校验到」。
- **存量数据**：修改营业时间**不回写、不改动**已有预约；越界的存量预约只在后台标注，由人工与客人确认。

### 2.4 分配包厢候选（2026-09-19）

- **唯一数据源**：`GET /business/reservations/{id}/assignable-rooms`（后台 BFF 同名端点）。
  服务端一次算完「该房型、该门店、该时段能分配给哪些包厢」，每项带
  `assignable / reason / currentAssignment / conflictReservationNo / conflictWindow / roomState`。
- **为什么必须服务端算**：候选是否可分配取决于三件事，其中两件客户端拿不全 ——
  ① 房态运行态（启用/清洁/占用，来自资源域）；② **本预约时段的预约冲突**（预约不写资源占用，
  因此只看房态永远看不出「这个时段已被别的预约锁了」）；③ 是否就是当前已分配的包厢。
  此前前端把 `/admin/resources` 与 `/business/resources` 合并后自己过滤：任一读失败都会被吞成
  「全都可分配」，运营只能被后端 409 拒绝。
- **兜底**：`assign-room` 在房态校验之后**再查一次时段重叠**（同门店 + 同包厢 + 左闭右开重叠 +
  状态 ∈ `PENDING/CONFIRMED/ARRIVED`），命中即 409 `ROOM_RESERVED_OVERLAP`（消息带冲突预约号与时段）；
  已取消/未到店释放时段，已开台（`CONVERTED`）的时段归属由**资源占用**承担。

## 3. 订单状态流程

### 3.1 流转图

```
  快速开台 POST /business/orders ──▶ DRAFT ──(会话 open)──▶ SERVING ──(结台 close)──▶ WAITING_PAYMENT
  预约开台 open-table ─────────────┘   │                      │        （结台即结算，2026-09-19）
                                       │ 确认(confirm)         │ 该订单还有 PENDING_APPROVAL 的
                                       ▼                      ▼ 客户自助加项时（不计入应付）
                              WAITING_SETTLEMENT ◄────────────┘        │
                                       │                               │
                    取消/作废(cancel|void)│  结算(settle)                  │ 收款(足额)
                                       ▼        ▼                      ▼
                                    VOIDED   WAITING_PAYMENT ──────▶ COMPLETED
```

- **开台（开台即计费，2026-09-19）**：`ord_ktv_session` `RESERVED → OPEN`，订单 `DRAFT|WAITING_ARRIVAL → SERVING`
  （`KtvSessionApplicationService:175-179`）；**同一事务内**按会话固化的计价方案快照写入 `ROOM_FEE` 明细
  （房费 + 服务人员费）并调用 `OrderAmountApplicationService.recalculate` 重算订单金额。
  即「开台后应收 = 0」是缺陷态：开台瞬间应收就应包含房费与对应服务费。
  起步一瞬仍可能显示 `¥0`——`CONSUMER_FAVOR` 让利下不足一个整计费单位记 0 块（例如 33 秒 → 0），
  满一个单位后即为一个整块金额，属预期而非缺陷。
- **开台中房费刷新**：`KtvRoomFeeRefreshJob`（`ktv.room-fee.refresh-ms` 默认 300000、
  `ktv.room-fee.refresh-enabled` 默认 true）按 `ord_ktv_session.status IN ('OPEN','PAUSED')`
  **跨租户**扫描（`KtvSessionMapper.selectOpenSessions` 带 `@InterceptorIgnore`），
  逐会话 `TenantContextHolder` 设定该会话租户后再补写/更新 `ROOM_FEE` 明细与订单金额，
  用于修复存量「已开台但应收为 0」以及历史漏写；刷新值只是过程量，结台仍以 `closed_at` 精确重算一次，
  刷新间隔不影响最终收费。
- **结台**：会话 `OPEN|PAUSED → CLOSED` 并按 `closed_at` 重算写 `ROOM_FEE` 明细、重算订单金额，订单 `SERVING → WAITING_PAYMENT`
  （**结台即结算**：结台收尾时在同一事务内复用 `SettlementApplicationService` 自动结算）。
  唯一例外：该订单还有 `PENDING_APPROVAL` 的客户自助加项时不自动结算（这类加项不计入应付，
  自动结算会漏收），保持 `WAITING_SETTLEMENT`，由门店先确认/拒绝再点「结算」。
- **结算**：`WAITING_SETTLEMENT → WAITING_PAYMENT`（`SettlementApplicationService:46-50`，需 version 匹配）；
  没有待确认加项时由结台自动调用，门店不需要多点一次。
- **收款**：payment 域 `OrderBillingMapper.markPaid` 足额时置 `COMPLETED`（跨库直改）。
- **取消/作废**：未完成订单 → `VOIDED`，同事务终结活动会话并释放占用（`OrderCancellationApplicationService:183-189`）。

### 3.2 订单状态机里的死值（本次只记录，未改动）

| 值 | 状况 |
| --- | --- |
| `CANCELLED` | 全仓无写入点：取消订单实际写 `VOIDED`。前端词表仍有「已取消」，属于历史遗留展示。 |
| `WAITING_ARRIVAL` | 只被 `KtvSessionApplicationService:175` 读，无写入点（死分支）。 |
| `REFUNDED` / `PARTIAL_REFUNDED` | 只在取消的终态集合里被引用，无写入点。 |
| `res_occupation.RESERVED / IN_USE` | 无写入点：占用直接从 `HELD` 到 `RELEASED`，`ResourceStateApplicationService` 的可用性判定却按 `HELD/RESERVED/IN_USE` 三态读。 |

## 4. 缺陷清单与本次修复

### 4.1 【P0，门店反馈的直接原因】分配包厢把预约硬置 `ARRIVED`

- 证据：`ReservationApplicationService.writeAssignment` 原实现一次乐观更新同时写
  `resource_id` **与** `status = 'ARRIVED'`，且允许 `PENDING` 直接跳级。
- 后果：① 客人未到就显示「客户已到店」并给出「到店开台」；② `arrival`（到店登记）被架空；
  ③ 看板把该包厢计入「已预订」，但卡片文案是「客户已到店」，同一张卡片两个口径。
- 修复：`writeAssignment` 只写 `resource_id`，**状态保持原值**；新增单测锁死 `sqlSet` 不含 `status`。
- 规格同步：`KTV_RESERVATION_ROOM_TYPE.md` §3「到店分配包厢」行的「状态 → `ARRIVED`」已改为「状态不变」。

### 4.2 【P0】「到店时间」列展示的是预约开始时间

- 证据：后台预约列表 `<el-table-column label="到店时间">` 渲染 `formatTime(row.startAt)`，
  而 `startAt` 是客户约的时段（`ord_reservation.start_at`）。
- 修复：新增列语义拆分——「预约时间」= `startAt`，「创建时间」= `createdAt`，「到店时间」= `arrivedAt`；
  新增 V25 迁移 `ord_reservation.arrived_at`，`arrival` 与「到店开台」写入真实到店时间。

### 4.3 【P0】开台幂等分支不可达（重复点「到店开台」返回 409）

- 证据：`doOpenTable` 先做 `status == ARRIVED` 校验，再判断 `order_id != null` 走幂等；
  而开台成功后状态是 `CONVERTED`，重复提交在第一道校验就被拒。
- 修复：幂等判断前移到状态校验之前；已开台返回既有订单（含 `sessionId`），不重复建单/建房态会话。

### 4.4 【P1】`NO_SHOW` 状态没有任何入口

- 证据：`ReservationStatus.NO_SHOW` 存在，但全仓无写入点、无定时任务；客户放鸽子后预约永远停在
  `PENDING/CONFIRMED`，后台「已预订」的包厢既不会释放也不提示。
- 修复：新增 `POST /business/reservations/{id}/no-show`（BFF `/admin/reservations/{id}/no-show`），
  限「到店前 + 已过预约开始时间 + 未开台」；后台列表与房态看板在超时未到时给出「未到店」按钮。

### 4.5 【P1】开台占用 15 分钟后被自动释放，订单仍在计时

- 证据：`ResourceStateClient.occupy` 的请求体只传 `sourceType/sourceId/startAt/endAt`，
  从不传 `holdExpiresAt`；资源侧 `OccupationApplicationService` 对缺省值用 `now + 15 分钟`，
  由 `HoldExpiryScheduler` 每 60s 扫描并自动 `RELEASED`。
- 后果：一场超过 15 分钟的 KTV，房态显示「空闲」而订单/会话仍是 `SERVING/OPEN` ⇒ 可被重复开台、重复计费。
- 修复：`occupy` 显式传 `holdExpiresAt = endAt`（开台/转台均传 `now + 24h`），占用存活到业务结束。

### 4.6 展示与文案对齐（本次一并处理）

- 看板预约卡片：`CONFIRMED + 已分配包厢` 的主按钮改为「到店开台」（一次点击，后端隐含登记到店时间）；
  卡片新增「已留房 / 到店时间」两行，明确「锁房」与「到店」的区别。
- 后台预约列表：筛选新增「已开台 / 未到店」；操作列在 `ARRIVED` 或 `CONFIRMED+已分配` 时给「开台生成订单」。
- B 端 H5：`CONFIRMED + 已分配包厢` 直接「立即开台」；超时未到给「未到店」；
  `NO_SHOW`/`CONVERTED` 进入状态词表（`src/shared/utils/status.js` 与 C 端 `app.js`）。

## 5. 测试与验证

- 后端 `platform-order-service`：`mvn -pl platform-services/order/platform-order-service -am test` → **366 passed**，
  其中预约用例 47 个，新增/改写：分配包厢不改状态（含 `PENDING` 场景）、`CONFIRMED` 开台隐含到店、
  已开台重复提交幂等、`NO_SHOW` 三条（成功/未到点/已到店）、控制器 `no-show` 转发。
- 后端 `platform-admin-service`：BFF 新增 `/admin/reservations/{id}/no-show` 转发 + 审计动作码 `reservation.no_show`。
- 后台 `gv_saas_admin`：`npx vitest run` → **335 passed**（新增开台可见性、超时未到、时间列语义、`no-show` 接口守卫）。
- B 端/C 端 `gv_saas_mobile`：`npx vitest run` → **111 passed**（新增 preview 的 `NO_SHOW`/`CONVERTED` 口径守卫）。

## 6. 已知缺口（未在本次修改，需产品决策）

1. **收款可跳过结算**：`OrderBillingMapper.markPaid` 只排除 `CANCELLED/VOIDED/COMPLETED`，
   `WAITING_PAYMENT` 不在约束里，收款可直接把 `DRAFT/SERVING` 推进到 `COMPLETED`。
   若门店确实支持「未结台直接收款」，现状即正确；否则应加 `AND status = 'WAITING_PAYMENT'`。
2. **服务人员会话不随结台推进**：结台不处理 `ord_ktv_server_session`，只有显式 `/end` 才写 `ENDED` 与服务费明细 ⇒ 可能漏结服务费。
3. **转台换占用非原子**：先 `release` 旧占用再 `occupy` 新包厢，且 `ifPresent` 吞掉失败，
   资源服务抖动时会留下「旧占用已释放、新包厢无占用、订单继续计时」的中间态；也未校验目标包厢可用性。
4. **`res_occupation` 的 `RESERVED/IN_USE`** 无写入点，与资源可用性判定读取的三态不一致（语义冗余）。
5. **订单状态死值**：`CANCELLED/WAITING_ARRIVAL/REFUNDED/PARTIAL_REFUNDED` 无写入点（§3.2），
   要么补齐写入路径，要么从前端词表/终态集合中清理。

## 7. 时间口径提醒

`ord_reservation.start_at/end_at/arrived_at` 与 `ord_order` 的时间列都是**门店营业本地墙上时间**（+08:00，无偏移），
前端按字面量渲染，不得再做一次时区换算（见 `V19__ord_reservation_time_comment.sql` 与
`MULTI_TIMEZONE_DESIGN.md`）。`arrived_at` 由服务端 `LocalDateTime.now()` 写入，口径与其它业务时间一致。

---

# 第二轮：包厢房态生命周期与配套修复（门店反馈 2）

> 门店反馈：① 分配包厢必须过滤可用状态（清洁中/使用中不能分配、不能开台）；
> ② 点单/加项页的加减号不应每次调接口锁库存，应「确认后才提交」，但要查库存避免超卖；
> ③ 清洁完成后包厢要回到空闲、能被开台/分配，并把订单与包厢状态周期再梳理一遍防遗漏。

## 8. 包厢房态生命周期（订单 × 会话 × 占用 × 清洁）

房态由**两块事实**共同决定（`platform-resource-service` · `ResourceStateApplicationService`）：

| 维度 | 列/来源 | 取值 | 谁写 |
| --- | --- | --- | --- |
| 启停 | `res_resource.status` | `ENABLED / DISABLED / MAINTENANCE` | 资源管理页 |
| 清洁 | `res_resource.cleaning_status`、`cleaning_started_at` | `IDLE / CLEANING` | 结台自动置 `CLEANING`；门店点「清洁完成」置 `IDLE` |
| 占用 | `res_occupation.status` | `HELD → RELEASED / CANCELLED` | 开台 `occupy`；结台 `release`；取消未开台会话 `cancel`；定时任务兜底 |

派生展示：`state = CLEANING ? CLEANING : occupied ? OCCUPIED : IDLE`；
`available = ENABLED && !occupied && !CLEANING`（业务视图 `/business/resources`）。

一个包厢的完整状态周期：

```
 空闲(IDLE,无占用,非清洁)
   │ 预约分配包厢（只写 resource_id，不占占用、不锁房态）
   │ 开台 open → occupy(HELD) ─────────────► 使用中(OCCUPIED, available=false)
   │                                            │ 结台 close：release 占用 + 置 CLEANING
   │                                            ▼
   │                                    清洁中(CLEANING) + 订单已进入待支付（结台即结算）
   │                                            │ 收银 → COMPLETED（有待确认客户加项时先「结算」）
   │                                            │ 门店「清洁完成」setCleaning(false)
   └──────────────── 空闲(IDLE, 可再次开台/分配) ◄┘
```

必须遵守的三条不变量（本轮修复的三处遗漏）：

1. **开台必须过房态门禁**：`CLEANING/OCCUPIED/DISABLED` 一律拒绝。占用冲突由 `occupy` 拦住，
   但**清洁中没有占用记录**，只靠 `occupy` 会漏；因此所有开台入口（快速开台、`/ktv/sessions/{id}/open`、
   预约开台）共用 `KtvSessionApplicationService.requireRoomAvailable`。
2. **分配包厢的候选只列可用包厢**：`/admin/resources` 没有占用列，必须与 `/business/resources`
   运行态合并后再过滤（后台 `mergeRoomState` + `filterAssignableRooms`），否则「使用中」的包厢会出现在下拉里。
3. **清洁完成 = 回到空闲**：门禁放行依赖 `available`，而 `available` 取决于占用是否已释放。
   结台/取消/转台的释放调用是「失败只记录」的降级路径，一旦漏掉，房态会永久卡在「使用中」；
   因此新增定时兜底：`end_at` 已过的有效占用自动 `RELEASED`（`HoldExpiryScheduler` + `selectEndedOccupations`）。

## 9. 第二轮缺陷与修复

| # | 缺陷 | 证据 | 修复 |
| --- | --- | --- | --- |
| 9.1 | 清洁中的包厢能从 `/ktv/sessions/{id}/open` 与「预约开台」开出去（只有快速开台做了房态校验） | `KtvSessionApplicationService.doOpen` 原仅 `occupy` | 新增 `requireRoomAvailable`（409 `ROOM_UNAVAILABLE`，房态服务不可达 fail-closed），三条开台路径共用 |
| 9.2 | 分配包厢候选只看管理端资源列表，**含使用中/已预订包厢** | `openAssignDialog` 只调 `/admin/resources` | 并行取 `/business/resources` 运行态并 `mergeRoomState` 合并；弹窗提示被排除原因（使用中/清洁中/已停用 N 间） |
| 9.3 | 结台后包厢进入清洁中，**看板把订单折叠成「清洁中」**，结算/收银入口消失 | `resourceBoardStatus` 先判清洁后判订单 | 待结账/待支付优先于清洁中；待结账卡片同时给「清洁完成」 |
| 9.4 | 占用释放失败会永久卡「使用中」 | `ResourceStateClient.release/cancel` 失败只记录 | 资源域新增 `end_at` 到点自动释放的定时兜底 |
| 9.5 | 点单/加项页**每点一次加号就调接口锁库存**，减号退不回来；且菜单是写死的演示 itemId（服务端 `CATALOG_ITEM_REQUIRED`） | App `ktv_timing_screen._addItem` → `addItems` per tap | App 改为本地购物车 + 加减号（`_increase/_decrease`）+「确认加项」一次提交；菜单改取 `GET /business/catalog/items` |
| 9.6 | 加号没有库存上限：可选到远超库存，提交时才被 `INVENTORY_INSUFFICIENT` 驳回 | 目录响应只有 `available` 布尔 | 目录响应新增 `availableQuantity`（非持久化）；App/后台/B 端 H5 的加号与数量输入按它限流（服务端原子扣减仍是最终事实） |

测试（第二轮）：

- `platform-order-service`：开台房态门禁 4 例（清洁中/占用中/房态不可达/正常）、目录 `availableQuantity` 2 例。
- `platform-resource-service`：`end_at` 到点兜底释放 2 例。
- 后台 `gv_saas_admin`：分配候选运行态过滤、排除原因汇总、合并与源码守卫（340 passed）。
- B 端 H5 `gv_saas_mobile`：数量上限提示与提交前校验（111 passed）。
- App `gv_chat_app`：`KtvCatalogItem` 库存解析与加号上限（`flutter test` 5 例新增，`flutter analyze` 0 error）。

---

# 第三轮：客户自助加项「待确认」提醒与处理（P0）

> 门店反馈：客户在 C 端自助加项后落 `PENDING_APPROVAL`（待服务人员确认、不计入应收），
> 但**后台、收银台、App 都没有任何提醒** —— 运营不知道客户加了什么；更糟的是结台时因为还有待确认项，
> 订单会**静默**停在「待结算」，前台也不知道原因。

## 10. 设计（一个数据源 + 三处提示 + 一个集中处理口）

**唯一数据源**：`GET /business/orders/pending-approval`（权限 `order.add_item`）返回按订单分组的聚合视图：
`{ pendingCount, pendingAmount, currencyCode, mixedCurrency, revision, serverTimeMillis, orders[{ orderId, orderNo,
roomName, roomCode, sessionStatus, orderElapsedSeconds, pendingCount, pendingAmount, items[…] }] }`。
**一次拿全**，不让每个客户端各自扫订单+明细（N+1，且多端计数口径会漂移）。

**三处提示 + 一个处理口**：
1. 侧边菜单「收银台」角标（`SidebarMenuItem`，红点+数字）；
2. 收银台房态卡片标记 `待确认加项 ×N`（点开即处理抽屉）；
3. 订单管理列表「待确认加项」列 + 「只看待确认」筛选 + 头部入口；
4. 全局「待确认加项」抽屉（`PendingApprovalDrawer`）：按订单分组、逐条确认/拒绝、「本单全部确认」；
   另有头部徽标入口，任何页面都能进。

**结台联动**：结台确认框直接说明「还有 N 条客户加项待确认，结台后会停在待结算」，不再静默。

## 11. 并发、实时性与一致性口径（本轮实现要点）

| 关注点 | 做法 |
| --- | --- |
| 并发（多人同时确认/拒绝） | 状态流转改走 `OrderItemMapper.markApproval` 的**原子条件更新**（`WHERE status='PENDING_APPROVAL'`）；影响 0 行视为「已被别人处理」→ 409 且不重复扣库存；重复确认/拒绝（已是目标状态）**幂等返回**，不重复留痕 |
| 一致性（金额与应收） | 确认成功后仍走既有 `recalculateOrderAmounts`（待确认项不计入应收，确认后才计入）；缓存只影响角标数字，**永不参与业务判定** |
| 实时性 | 服务端聚合读走进程内 TTL 缓存（默认 3s，`platform.order.pending-approval.cache-ttl-ms`）；**写路径（客户提交 / 确认 / 拒绝）事务后立即失效**，本实例角标秒级更新，多实例最坏等一个 TTL |
| 前端轮询 | 15s（`PENDING_APPROVAL_POLL_MS`），页面隐藏暂停、回到前台立即拉一次；服务端返回 `revision`（本门店命中行最大 id），未变化不重渲染（防角标闪烁）；确认/拒绝做**乐观移除** + 立即刷新，409 静默以服务端为准 |
| 多币种 | 金额合计只在命中行同币种时给出 `currencyCode`，混币种 `mixedCurrency=true` 且不给单一币种（规范 16 §3，禁止跨币种相加） |

## 12. 未来「消息中心」的接入点（架构预留）

- 读侧：报错与提示只有一个入口（`PendingApprovalApplicationService` + `/business/orders/pending-approval`）；
  接入消息中心后，客户端把「轮询」换成「订阅推送」，本接口保留为**兜底快照**，各端口径不用改。
- 写侧：客户提交加项、运营确认/拒绝三处都已有**单一收口**（`OrderItemController` + `PendingApprovalCache.invalidate`）；
  消息中心上线时，在这三个位置发布领域事件即可（`PendingApprovalCache` 可整体替换为 Redis/消息驱动实现）。
- 通知渠道（App 推送 / 提示音 / 站内消息）属于 P1/P2，本轮不引入，避免半套通知机制。

## 13. 测试与端到端验证

- `platform-order-service`：新增 `PendingApprovalApplicationServiceTest`（门店隔离/计数金额/混币种/revision/
  缓存命中与写后失效）+ `OrderItemControllerAuditTest` 增补幂等与并发输家用例 → 域内 `clean verify` **502 tests** 全绿。
- `gv_saas_admin`：新增 `src/utils/pendingApprovalWiring.test.js`（数据源唯一性、三处提示与处理口接线）→ **612 tests** 全绿。
- `gv_saas_mobile`（B 端 H5）：新增 `src/b-end/views/pendingApprovalWiring.test.js` + 纯函数用例，
  **143 tests** 全绿；`npm run build:b` 成功。
- `gv_chat_app`（A380 收银端）：新增 `test/models/ktv_pending_approval_test.dart`（解析/混币种/乐观移除）、
  `test/providers/pending_approval_provider_test.dart`（快照去重/409 收敛/引用计数启停/前台补拉）、
  `test/screens/business/ktv_pending_approval_wiring_test.dart`（接线守卫）→
  `flutter test --dart-define=APP_ENV=prod` **318 tests 全绿**（含既有用例）。
- ACK dev 实测（`20260919T130840Z.e14de0ca`，order `2.0.20-SNAPSHOT` + saas-admin `2.1.25-SNAPSHOT`，
  按 digest 校验）：
  - `GET /api/v1/business/orders/pending-approval` → **200**，`pendingCount=5`、`revision=77`、
    混币种（CNY+USD）→ `mixedCurrency=true`、`currencyCode` 为空；
  - 老路径 `/api/v1/business/order-items/pending-approval` → **404**（回归守卫成立）；
  - 部署的 saas-admin 前端产物内含新路径、不含老路径（容器内 grep 校验）。

## 14. 三端提示的落地形态（本轮补齐）

| 端 | 提示位 | 处理口 |
| --- | --- | --- |
| Web 后台 / 收银台（`gv_saas_admin`） | 侧边「收银台」角标、收银房态卡片 `待确认加项 ×N`、订单管理列 + 「只看待确认」、头部铃铛 | `PendingApprovalDrawer`（按单分组、逐条确认/拒绝、本单全部确认） |
| B 端商户 H5（`gv_saas_mobile`） | 顶部入口条 `待确认加项 ×N｜<金额或「多币种」>｜集中处理`、列表卡片角标 | 集中处理面板 + 详情「待确认加项」区（数据源与角标同一份聚合视图） |
| A380 收银端 App（`gv_chat_app`） | 「收银」页签角标（工作台三页都在）、看板横幅、计时加项页横幅、收银列表行 `待确认加项 ×N`、收款详情横幅 | `KtvPendingApprovalScreen`（按单分组、逐条确认/拒绝、本单全部确认） |

三端共用同一聚合读接口与同一处理语义（原子条件更新 + 409 收敛），只有展示形态不同：
- App 端提醒态是**可缺省**依赖（`PendingApprovalController?`）：提醒装配缺失不得让收银/结台主流程崩掉，
  装配正确性由接线守卫测试兜住（`ktv_pending_approval_wiring_test.dart`）。
- 轮询启停用**引用计数**：工作台三个一级页切页时不能互相关掉轮询（看板 → 收银 → 交班都在同一份轮询上）。
- 结台/结算/收款前的阻塞语义在三端都点出「还有 N 条待确认加项，处理前不计入本单应收」，不再静默停在待结算。

## 15. 订单收款明细（组合支付看得全）+ 订单详情端点补缺（本轮）

### 15.1 问题
后台订单管理只展示账单的「现金 / A380币 / 积分」三个**汇总桶**（`BillResult.Collected`）：线上渠道被并进现金，
同一订单的**多次收款**、每笔的**分腿构成**、渠道交易号与退款都看不到 —— 组合支付在页面上只剩一个「已收」数字。

### 15.2 读接口（唯一来源）
`GET /api/v1/business/payments/order-collections?orderIds=1,2,3`（common-payment-service，网关 `/business/payments/**` 白名单内）：

| 字段 | 说明 |
| --- | --- |
| `collections[]` | 每一笔**已确认**收款：`collectNo` / `currencyCode` / `payable`（本次应收）/ `collectedAt` / `customerId` / `combined`（分腿 > 1 = 组合支付）/ `legs[]`（**全部分腿**：POINT、WALLET、CASH、ALIPAY、WECHAT、STRIPE 各自一行，不合并） |
| `transactions[]` | 渠道支付流水：`provider` / `providerTransactionNo`（线上对账凭据）/ `amount` / `status` / `occurredAt` |
| `refunds[]` + `refundedAmount` | 退款全过程（含 REJECTED），只有 `REFUNDED` 的钱计入已退款 |
| `currencyCode` / `mixedCurrency` | 命中数据币种一致才给单一币种；混币种只给标记（禁止跨币种相加，规范 16 §3） |

口径与边界：
- **只认 `CONFIRMED`**：INIT/HOLD/FAILED 是收款尝试，不是收款明细；
- **没有收款数据的订单不出现在结果里**：调用方据此显示「未收款」，不得把缺失当 ¥0.00；
- 单条脏快照（`response_json` 解析不了）只跳过自身，不影响同订单其它收款与其它订单；
- 权限 `order.view`（挡住 C 端消费者令牌凭 orderId 猜读他人收款明细）+ 显式 `tenant_id` 条件；
- `orderIds` 非空且 ≤ 100，超限 400 且**不查库**（避免一次拉全租户资金明细）。

前端（`gv_saas_admin` 订单管理）：列表新增「收款方式」列（按抵扣顺序去重，组合支付打「组合」标签，悬浮给分腿金额），
详情抽屉新增「收款明细」区（逐笔收款 + 分腿 + 渠道流水 + 退款 + 已退款合计）；按当前页批量取，**无 N+1**。

### 15.3 顺带修掉的端点缺失
A380 收银端 App 的计时/结算页一直调 `GET /business/orders/{id}`，但 order 服务**从未实现**该路径
（只有列表、`/session`、`/bill`），线上实测 **404** → 页面拿不到包厢/计时/状态，只能落到错误态。
已补 `GET /business/orders/{id}`，与列表**同一授权口径**（商户 `order.view` 不限 / 消费者按 `customer_id` 收窄 /
非会员 403 / 不存在 404），并与列表共用 `fillSessionProjection` 的会话投影。

### 15.4 ACK dev 实测与发布记录
| 服务/端 | 版本 | 发布记录 |
| --- | --- | --- |
| common-payment-service | 2.0.6-SNAPSHOT | `.outputs/releases/ack-saas-development.20260919T143332Z.942b9dcd.json` |
| saas-admin | 2.1.26-SNAPSHOT | 同上 |
| platform-order-service | 2.0.21-SNAPSHOT | `.outputs/releases/ack-saas-development.20260919T144454Z.6d8b8077.json` |
| saas-mobile | 1.5.21-SNAPSHOT | `.outputs/releases/ack-saas-development.20260919T134417Z.77146a75.json` |
| Android App | 2.0.34+119 | 客户端发布记录 id=99（released） |

实测（`api.dev.example.com`，全部按 digest 校验）：
- 组合支付样例单 **订单 61**（豪华包 XL02，USD）：`POINT 5 + CASH 154795` → 接口回读
  `combined=true`、`legs=[POINT:5,CASH:154795]`、1 条渠道流水；单腿单（68/63）回读正常；
- `GET /business/orders/61` → **200**（修复前 404），带包厢与会话状态；
- `orderIds` 空 / 超 100 / 缺参 → **400**；saas-admin 部署产物内含两个新路径（容器内 grep 校验）。

### 15.5 遗留说明
- App 端 2.0.34+119 的**真机可见性**未验证（本机 adb 无法绑定 5037），需在设备上装包确认角标/横幅/处理页；
- 储值（WALLET）分腿在 dev 被 `CURRENCY_CORRIDOR_DISABLED` 拒绝：会员储值账户币种与 USD 订单不同走廊，
  这是既有的跨币种禁用规则（`WalletApplicationService.consume`），不是本轮改动引入；
- 订单详情端点的补齐意味着 App 无需改代码即可恢复计时页取单（接口契约与原调用一致）。

## 16. 线上故障修复：确认加项回 500（写路径无限递归）+ 前后端结果不一致

### 16.1 现象
后台「订单管理 → 待确认加项」抽屉里点「确认」（或「拒绝」、「本单全部确认」），界面弹红条
**「服务内部错误，请稍后重试」**（order 服务的兜底 advice：`500 INTERNAL_ERROR`），而同一时刻前端已经
把该条**乐观移除**并按成功处理 —— 前端说成功、后端说 500，刷新后待确认项原样回来。

### 16.2 根因（后端）
`OrderItemController#invalidatePendingApproval()` 被误写成**调用自身**：

```java
private void invalidatePendingApproval() {
    if (pendingApprovalService != null) {
        invalidatePendingApproval();   // ← 自递归，必然 StackOverflowError
    }
}
```

调用点在三条写路径上（客户提交加项 / 确认 / 拒绝），因此三条路径**全部**变成无条件无限递归：
`StackOverflowError` → Spring 把 Error 包成 `ServletException` 交给 `@ExceptionHandler(Exception.class)`
→ 500 `INTERNAL_ERROR 服务内部错误，请稍后重试`（与前端截图一致）。

**为什么既有测试没发现**：`OrderItemControllerAuditTest` / `OrderItemControllerWebTest` 的装配一律把
`pendingApprovalService` 传 `null`，null 分支直接返回，递归进不去；只有生产（Spring 注入真实 Bean）才命中。

### 16.3 根因（前端，两处「结果不一致」）
1. **把失败当成功**：`stores/pendingApproval.js` 的 `mutate()` 把异常吞掉返回 `false`，
   而 `PendingApprovalDrawer` 不检查返回值就 `ElMessage.success('已确认…')` → 后端 500 也报成功。
2. **回滚被去抖吃掉**：`refresh()` 以 `revision`（本门店命中行最大 id）去抖。写失败时服务端那条**还在**，
   `revision` 不变 → 回滚刷新被跳过 → 乐观移除的条目**永远**从抽屉与角标上消失（少算待确认），
   而服务端仍认为它待确认。

### 16.4 修复
| 位置 | 改动 |
| --- | --- |
| `OrderItemController#invalidatePendingApproval` | 真正调用 `pendingApprovalService.invalidateCurrentStore()` |
| `GlobalExceptionHandler#httpStatusFor` | `ORDER_ITEM_STATUS_INVALID`（并发输家/状态不符）由 default 的 **400 改为 409**，与 §11 表格、以及三端「409 即已被别人处理」的收敛口径一致（`gv_chat_app` 判 `_statusCodeOf(e) == 409`、`gv_saas_mobile` 判 `409`、Web 后台本次补齐） |
| `stores/pendingApproval.js` | `confirm/reject` 返回 `{ ok, error, alreadyProcessed }`，`confirmOrder` 返回 `{ ok, total, failed, alreadyProcessed }`；失败回滚改为 `refresh({ force: true })` 绕过 revision 去抖 |
| `PendingApprovalDrawer.vue` | 结果提示收口到 `reportDecision()`：`ok` 才报成功；`alreadyProcessed` 轻提示「已被处理，已为你刷新」；其它失败交给响应拦截器的中文提示，不重复弹；批量确认在 `failed>0` 时不再谎报「全部确认」 |
| `views/tenant/orders.vue` | 点单/加项弹窗的单条确认/拒绝收口为 `decideItemRow()`：同样只在成功时提示，并顺手刷新聚合视图（角标与收银台同屏一致） |

### 16.5 回归守卫（先证明能抓住 bug）
- 新增 `OrderItemControllerPendingApprovalTest`（**注入真实 `PendingApprovalApplicationService` + 真实
  `PendingApprovalCache`**，只 mock 持久层）：三条写路径必须不爆栈、且写完**立即失效本门店缓存**
  （下一次读回源）；并断言并发输家 HTTP **409** + `ORDER_ITEM_STATUS_INVALID`。
  把修复临时改回自递归后，该测试立刻以 `StackOverflowError` 失败（3 个用例），证明守卫有效。
- 新增 `stores/pendingApproval.test.js`（8 例）：500 时 `ok=false` 且条目按服务端状态回到列表（角标不少算）、
  409 时 `alreadyProcessed=true`、批量确认失败计数。把 `force: true` 去掉后 2 个用例失败（角标 1 ≠ 2），
  证明「去抖吃掉回滚」这条也被钉住。
- `src/utils/pendingApprovalWiring.test.js` 增补接线守卫：store 必须保留 `refresh({ force: true })` 与
  `alreadyProcessed`；抽屉必须走 `reportDecision`/`result.ok`，不得再出现「await store.confirm 后无条件弹成功」。

### 16.6 验证
| 范围 | 命令 | 结果 |
| --- | --- | --- |
| order 域 | `mvnw -pl platform-services/order/platform-order-service -am test` | **512 tests, 0 failures**（含新增 4 例） |
| Web 后台 | `npm test`（vitest） | **643 tests, 0 failures**（含新增 8 例） |
| Web 后台 | `npm run build` | 成功 |

> 未做：ACK dev 实测与发布（本轮只改代码与测试）。上线前需按既有流程发
> `platform-order-service` + `saas-admin` 并按 digest 校验，再实测一次「确认加项」。

## 17. 「待确认加项」展示包厢（三端）+ 按规范发版与真机验收

> 门店反馈：待确认加项里**看不清是哪间包厢**的需求（截图上只有订单号与细灰字）。

### 17.1 根因
包厢名/编码只是**开台那一刻**从资源服务取的快照（`KtvSessionApplicationService.applyRoomSnapshot`）。
开台时资源服务不可达（或历史数据）→ 会话只剩 `roomResourceId`，`PendingApprovalView.roomName` 为 null；
三端各自「有就渲染、没有就留空」：Web 抽屉整块不渲染、H5 显示 `—`、App 渲染成「包厢 」（空标签）。

### 17.2 修复
| 层 | 改动 |
| --- | --- |
| 后端 | `KtvSessionApplicationService.roomNameFromResource(roomResourceId)`：按资源 ID 回源取展示名（名称 → 编码），资源服务不可达返回 null（读路径降级，不打断提醒链路）。`PendingApprovalApplicationService.roomNameOf`：会话名称快照 → 会话编码快照 → 资源回源 → null。 |
| Web 后台 | `PendingApprovalDrawer`：分组头改为「包厢 X」加粗主色在前、订单号退为次要小字；`roomLabel = roomName \|\| roomCode \|\| 「未关联包厢」`。 |
| B 端 H5 | `shared/utils/pending-approval.js#pendingRoomLabel`（纯函数，空白/脏数据按缺失处理）；集中处理面板标题「包厢 X」为主、详情待确认区标题带 `detailRoomLabel`。 |
| A380 App | `KtvPendingOrder.roomLabel` 补「未关联包厢」兜底（集中处理页标题已是 `包厢 X`）。 |

### 17.3 测试
- order 域：`PendingApprovalApplicationServiceTest` +3（编码兜底 / 资源回源兜底 / 无会话与回源失败返回 null）→ **514 tests**。
- Web 后台：`pendingApprovalWiring.test.js` +2（包厢为主识别信息、文案与兜底）→ **644 tests**，`npm run build` 成功。
- B 端 H5：`pendingRoomLabel` 纯函数用例 + 接线守卫 → **145 tests**，`npm run build:b` 成功。
- A380 App：模型兜底用例（名称/编码/都空）+ 集中处理页接线守卫 → `flutter test --dart-define=APP_ENV=prod` **320 tests**。

### 17.4 发版（同一份清单先 Kind 回归、再 ACK）
| 制品 | 版本/tag | digest | 源修订 |
| --- | --- | --- | --- |
| platform-order-service | 2.0.22-SNAPSHOT | `sha256:6120382ce83d74491bb3d8a2b08f39927b68729c8c5d5e825d9ca587367c6c5e` | 90a278a4 |
| saas-admin | 2.1.27-SNAPSHOT | `sha256:be5cfe2b12cc4b97ee5a31e24ea49782450f8ab5477a34d707ff5e40f0f3b41d` | b8c4a64 |
| saas-mobile | 1.5.22-SNAPSHOT | `sha256:8a30cbc6118235954441397fb310fe49f5ed832d9b5dbd0827256aaf7b45654e` | f68de4a |
| A380 App（APK） | 2.0.35+120 | `sha256:4e0a656059a472bcb2155132d6165fc86ea73fe4ac634e401f17f63451cd408e`（175181202 B） | 21cc2d8 |

- 发布清单/记录：Kind `local-scoped-development.20260920T011723Z.90a278a4.json`；ACK `ack-saas-development.20260920T011723Z.90a278a4.json`（`deployedAt=2026-09-20T01:23:03Z`，三服务均「按 digest 校验通过」）。
- 版本 bump：platform-order 2.0.21 → 2.0.22（`platform-order-api` 继承、`platform-order-service` 2.0.22-SNAPSHOT，
  `validate-maven-version-ownership` 通过）；saas-admin 2.1.26 → 2.1.27；saas-mobile 1.5.21 → 1.5.22（VERSION/package.json/lock 三处对齐）；
  App `pubspec.yaml` 2.0.34+119 → 2.0.35+120（`tools/release.ps1` 自动 bump + 生产签名 + 对象存储 + 记录 id=100 → `released`，channel=stable）。
- 客户端签名：`apksigner verify --print-certs` 证书 SHA-256 = `27cdc427d6152d0db97b78819d1ac5ba732300e5cc48bdb8820a9ce909fee10d`（规范要求的生产密钥）。

### 17.5 验收证据
- **Kind（真实接口链路）**：`source=CUSTOMER` 造待确认加项 → 聚合接口 `pendingCount=1, roomName=大包 K12, roomCode=K12`；
  `POST /items/{id}/confirm` → **200 / ACTIVE**（修复前是 StackOverflowError → 500）；确认后 `pendingCount=0`（写后缓存立即失效）；
  「先拒绝再确认」→ **409 `ORDER_ITEM_STATUS_INVALID`**。
- **ACK dev**：`pendingCount=6` 时两组（`小包 K02`、`小包 S03`）**都带包厢**；confirm → **200 / ACTIVE**，计数 6 → 5；
  对 ACTIVE 明细 reject → **409 `ORDER_ITEM_STATUS_INVALID`**（状态冲突口径在 ACK 上实测，失败调用产生的审计留痕已删除）。
- **部署产物**：`saas-admin` 容器内 `assets/*.js` 命中「未关联包厢」（AdminLayout/order-management/orders 三个 chunk）；
  `saas-mobile` 容器内 `b/assets/index.b-*.js` 命中；`miniservice.dev.example.com/b/` 与 `saas-admin.dev.example.com` 均 **200**。
- **真机（vivo V2520 / Android 16 与 小米 MI 9，运行的是发布包 2.0.35+120、打的是 ACK dev 后端）**：
  - 装包与版本：两台都 `versionCode=120 / versionName=2.0.35`；vivo 上 `qian001` 登录进 IM 主界面；
  - **包厢展示（两台都验过）**：打开「待确认加项」抽屉，DOM 取证 `rooms=["包厢 小包 K02","包厢 小包 S03"]`（vivo 5 条 / 小米 3 条），
    截图可见两个分组都以「包厢 X」加粗主色为主标题、订单号退为次要小字；
  - **确认加项（原 500）**：vivo 上造一条本人待确认项（服务项、不扣库存）→ 抽屉里点「确认」→ 绿色提示 **「已确认「服务员点歌」」**，
    卡片计数同步收敛（截图留档）。该测试项随后已精准删除（见 §17.6）。
  - 取证通道（手机 DNS 解析不了 `saas-admin.dev`，故走 USB）：`adb reverse tcp:18080 tcp:18080` + 本机反向代理（把 `Origin/Referer` 重写为目标站，否则网关对状态变更请求回 403）+ CDP（Chrome/Edge 远程调试）填表/点击/读 DOM。
  - 已脚本化：`.agents/notes/device-acceptance/`（`README.md` 说明 + `accept-device.ps1` 一条命令完成「装包 → 隧道 → 开后台 → 抽屉断言包厢 → 截图」）；
    两台实测均输出 `ACCEPTANCE_OK`，截图 `pending-approval-<serial>-drawer-rooms.png`（vivo `10AF9Y31YG002M3`、小米 `4d4fc229`）。
  - **小米接入时踩的坑（已在 README 记录）**：Windows 侧一度出现 **Code 43「未知 USB 设备(配置描述符请求失败)」**（`USB\VID_0000&PID_0003\…`），
    adb 侧表现为 `offline`/不出现。修法：`pnputil /remove-device` 删掉坏节点与历史残留 → `/scan-devices` → 确认该根集线器下没有其它设备后，
    对该端口 `Disable-PnpDevice` → `Enable-PnpDevice` 强制重枚举（恢复为 `MI 9` + WinUSB），再由手机侧重启 adbd（重启手机）后 `adb devices` 即为 `device`。
    另注：**ADB 协议层序列号是 `4d4fc229`（手册值正确），USB 实例串是 `c4c90621`**，两者不同，排查时不要混。

### 17.6 本轮测试数据已精准清理
| 环境 | 我创建的 | 已还原 |
| --- | --- | --- |
| ACK（order 69） | 明细 78（确认→ACTIVE）、79（留待确认）；库存物料 1 消耗 1 | 明细 78/79 删除；订单金额 48200 → **48000**；库存 93 → **94**（version 6 → 5）；库存流水 id=20 删除；审计 3 条删除；聚合回到基线 `pendingCount=5 / revision=77` |
| ACK 真机验收（order 69） | 明细 80（服务员点歌，服务项，确认→ACTIVE） | 明细 80 删除；订单金额 49000 → **48000**；审计 2 条删除；聚合再次回到基线 `pendingCount=5 / revision=77`（两组仍带包厢） |
| ACK 409 校验 | 对 ACTIVE 明细 72 的 reject（失败调用） | 失败审计 1 条删除 |
| Kind（order 1） | 明细 2（确认→ACTIVE）、3（拒绝） | 明细 2/3 删除；订单金额 339200 → **338200**；审计 5 条删除；本店待确认归零 |

> 按用户要求，本地 Kind 集群**已停掉**（`docker stop gv-im-local-control-plane gv-im-local-worker`），后续验收一律以 **ACK dev** 为准；
> 上表的 Kind 行只是该指令之前「先 Kind 回归、再上 ACK」那一步的历史记录。

### 17.7 本轮未完成 / 已知限制
- **真机验收（vivo + 小米）已完成**，见 §17.5；小米接入过程中的 USB Code 43 与序列号差异已写进 `.agents/notes/device-acceptance/README.md`。
- A380 App 原生 B 端（收银工作台）在本包内**只有占位登录路由** `/business/login`（`_submit()` 直接 `go(ktvDashboard)`，无真实鉴权、也无 UI 入口），
  App 上点不到「待确认加项」页；App 侧的本轮改动由模型/接线守卫测试覆盖，B 端功能验收在 Web 后台真机上完成（同一份聚合接口、同一套文案）。
- 手机浏览器直接打开 B 端 H5 会走 OIDC，IdP 对无会话的浏览器回 `401 {"code":"unauthorized"}`（`/a380/` 同样）；ACK **未开** `SAAS_DEV_*` 调试放权（已查 deployment 环境变量），
  因此 H5 端要真机验收需先有人在手机上完成交互式登录。
- ACK dev 是共享环境：本轮验收后 74/75 两条明细被别人确认，因此 order 69 金额由我恢复的 48000 变为 48400（属于他人正常操作，非本轮残留；
  验收用的 78/79/80 明细、库存流水与审计留痕均已按 §17.6 清零）。




