# 多时区支持方案设计（Multi-Timezone Design）

> ## ⚠️ 决策更新（2026-09-19，产品口径）：**暂不实现逐门店多时区，平台统一东八区**
>
> 结论：当前所有门店一律按**平台默认 `Asia/Shanghai` + 营业日切点 04:00** 计算营业日/发号/报表，
> **不读** `tnt_store.timezone`、也不做租户 `default_timezone` 的逐门店回落。本文档其余章节保留为
> 调研与未来方案（若日后要开多时区再据此实施），**不代表当前实现**。
>
> 现状核对（2026-09-19）：
> - `StoreTimeService.DEFAULT_TIMEZONE = "Asia/Shanghai"`、`DEFAULT_BUSINESS_DAY_CUTOFF = 04:00` 是唯一口径；
> - 单据发号 `DailySerialNumberGenerator.businessDate()` 与报表时间桶 `ReportTimeBuckets` 都**直接使用**这两个常量
>   （`ReportTimeBuckets` 注释明确「没有逐门店读」），业务代码里没有 `systemDefault()`、也没有其它 `ZoneId` 字面量；
> - 因此「换机/换库」不会改变营业日口径，跨时区门店按北京时间营业日归档（如需按当地时区营业，后续再按本文档方案实施）。
>
> 若日后要开多时区：先在本文档确认实施范围（门店时区配置 → 发号/报表/账单口径 → 前端选择器），
> 再按「写路径 fail-closed、读路径降级」的既有约定落地，并同步 `docs/standards/` 相关规范。

- 日期：2026-09-18
- 状态：**只读调研 + 方案设计产出**。本文档是本次改造中**唯一被写入的文件**；未修改任何代码、配置、k8s、测试、版本号，未 push，未跑发版脚本。
- 目标仓库与基线：

| 仓库 | 分支 | HEAD | 用途 |
|---|---|---|---|
| `gv_im_server` | `develop/2.0.0-saas-20260826` | `9ef94ce8` | Java 25 / Spring Boot 4 / MyBatis-Plus / Flyway 后端 + k8s + docs |
| `gv_saas_admin` | `develop/2.0.0-saas-20260826` | `16ce48e` | Vue3 租户/平台后台（B 端后台） |
| `gv_saas_mobile` | `develop/2.0.0-saas-20260826` | `eeb742d` | Vue3 B 端 + C 端 H5（`src/b-end`、`c-end`） |

- 调研方法：`grep` 精确扫描 + 逐文件 `read` 核验（排除 `node_modules`/`dist*`/`build`/`target`/`.git`）。所有现状论断均给 `文件:行号`。
- 关联既有规范（本方案必须与它们对齐，不能另起一套口径）：
  - `docs/renovation/SAAS_PLATFORM_04_DATA.md:13` —「时间使用 `DATETIME(3)` UTC 存储，展示按门店时区转换」
  - `docs/renovation/SAAS_PLATFORM_05_API.md:50` —「时间为 RFC3339 UTC」
  - `docs/renovation/SAAS_PLATFORM_05_API.md:135` —「预约创建的 `startAt/endAt` 必须包含时区偏移；服务端转换为 UTC 后判断冲突」
  - `docs/renovation/KTV_BUSINESS_01_SERVICE.md:148` —「全部换算为秒，UTC 落库、门店时区展示」
  - `docs/renovation/SAAS_PLATFORM_01_SERVICE.md:61,125,298` —「每个门店必须配置国家地区、时区、默认币种、语言、税率和营业日切点」

> **一句话结论**：`tnt_tenant.default_timezone`、`tnt_store.timezone`、`tnt_store.business_day_cutoff` **三列已经存在**，但它们**没有任何一行业务代码读取**；同时线上实际写的是**三种互不相同的墙上时间**（预约写 +08:00 墙钟、系统时间写 JVM 默认时区墙钟、IM 保密群写 UTC 墙钟）。因此本次改造的**主要工作量不是加字段，而是统一口径 + 迁移存量 + 三端展示收敛**。

---

## 目录

- [0. 结论摘要与待拍板决策](#0-结论摘要与待拍板决策)
- [1. 问题陈述](#1-问题陈述)
- [2. 口径设计](#2-口径设计)
- [3. 营业日（business date）定义](#3-营业日business-date定义)
- [4. 改动清单](#4-改动清单)
- [5. 迁移与兼容](#5-迁移与兼容)
- [6. 测试策略](#6-测试策略)
- [7. 验收标准](#7-验收标准)
- [8. 工作量与分批建议](#8-工作量与分批建议)
- [9. 实施记录（批 1 地基）](#9-实施记录批-1-地基)
- [附录 A：证据索引](#附录-a证据索引文件行号)
- [附录 B：术语表](#附录-b术语表)

---

## 0. 结论摘要与待拍板决策

### 0.1 一页结论

| # | 结论 | 依据 |
|---|---|---|
| 1 | **时区字段已经齐全，且没有被使用**。`tnt_tenant.default_timezone`（`V1__tnt_iam_baseline.sql:8`）、`tnt_store.timezone`（`:28`）、`tnt_store.business_day_cutoff`（`:30`）都在建表基线里；`StorePo` 也映射了 `timezone`/`businessDayCutoff`（`StorePo.java:26,30`）。但全仓 Java 代码里 `timezone` 只出现在 PO 字段声明、`TenantController` 的创建入参和 `StoreController` 的原样返回里，**没有任何一处参与时间换算**。 | 见 §1.1 |
| 2 | **线上同时存在三种墙上时间口径**，且区分的边界不在数据库、不在 API，而在「哪个类调用了 `LocalDateTime.now()`」。 | 见 §1.1 |
| 3 | **预约写的是 +08:00 墙钟，系统时间写的是 JVM 默认时区墙钟**。容器与 k8s 清单都没有设 `TZ` / `-Duser.timezone`（`Dockerfile:20`、`k8s/local/saas.yaml` env 块），JDBC URL 却写 `serverTimezone=UTC`（`platform-order-service/src/main/resources/application.yml:5`）。这两者叠加会在同一个库里产生**互相不可比较的时间列**。 | 见 §1.1、§1.4 |
| 4 | **实现与既有规范直接冲突**：规范说「转为 UTC 后判断冲突」（`SAAS_PLATFORM_05_API.md:135`）、「UTC 落库、门店时区展示」（`KTV_BUSINESS_01_SERVICE.md:148`），实现却把 +08:00 硬编码进写路径与 C 端（`ReservationApplicationService.java:719-721`、`gv_saas_mobile/c-end/datetime.js:16-17`）。**本方案的第一价值是把实现拉回规范，而不是新造规范。** | 见 §1.3 |
| 5 | **报表的「业务日期」目前是服务端 JVM 日，不是门店营业日**：`ReportController.Range.of` 用 `LocalDate.now()` 构造 `[from, to)`（`ReportController.java:337,345`），SQL 用 `DATE_FORMAT(o.created_at,'%Y-%m-%d')` 直接切日期（`ReportMapper.java:93,105`）。 | 见 §1.2 场景 5 |
| 6 | **交班/日结的营业日由前端设备日期决定**：`shift.vue` 用 `new Date()` 取「今天」作为 `businessDate` 提交（`shift.vue:164,169-173`），后端只做落库（`CashierApplicationService.java:125-133`）。收银员在别的时区或用改过系统时间的设备，就能写入错误营业日。 | 见 §1.2 场景 4 |
| 7 | **预约时段与开台计时实际是脱节的**：`ord_ktv_session.reserved_start_at/reserved_end_at` 在生产代码里**从未被写入**（全仓只有测试赋值：`OrderIntegrationTest.java:274-275`），`openTable` 也不把预约窗口写进会话（`ReservationApplicationService.java:326-327`）。于是 `KtvRoomFeeCalculator.standardSeconds` 永远走 `default_session_minutes` 回退（`KtvRoomFeeCalculator.java:68-71`）。这是**先于时区就存在的功能缺口**，多时区改造会把它放大成错账。 | 见 §1.2 场景 2 |

### 0.2 三个必须由决策者拍板的点

> 这三条决定后续 80% 的工作量，请优先拍板；其余细节方案里已给推荐值。

**决策点 1：存储口径 —— 切 UTC，还是把硬编码 +08 换成 `tnt_store.timezone`？**

| 选项 | 做法 | 代价 | 结果 |
|---|---|---|---|
| **A（推荐）** | 所有业务时间列统一存 **UTC `datetime(3)`**；门店本地语义靠 `store_id -> tnt_store.timezone` 在读写边界换算；报表/日结额外物化 `business_date DATE` 列 | 大。需要 A1 存量迁移 + A2 全链路改造 + A3 全端改造；约 15~25 人日（§8） | 与 `SAAS_PLATFORM_04_DATA.md:13`、`KTV_BUSINESS_01_SERVICE.md:148` 一致；跨时区门店、DST、报表口径一次性解决；未来接海外门店零增量 |
| B | 继续存门店墙上时间，只把 `ZoneOffset.ofHours(8)` 改成 `tnt_store.timezone` | 小。约 5~8 人日 | 治标。`ord_order.created_at` 等系统时间列仍是 JVM 墙钟，与预约墙钟不可比；报表按营业日聚合仍需二次改造；换时区字段后**历史数据的解释会变**（见 §5.1），且无法支持 DST 地区（墙上时间在 DST 切换日会重复/丢失 1 小时，见 §6.4 TC-D3） |
| C | 存 epoch 毫秒 `bigint` | 大。且违反 `SAAS_PLATFORM_04_DATA.md:13` 的 `DATETIME(3)` 约定；需要改所有 PO/索引/人工排查习惯 | 不推荐：可读性差、与既有规范冲突、收益不比 A 多 |

**推荐 A。** 理由：①规范已经写死 UTC；②A 是唯一能同时解决「跨时区门店」「营业日切分」「DST」的方案；③当前业务量下迁移窗口可控（`ord_reservation` 是唯一已知的 +08 墙钟表，其余是 JVM 墙钟，两类的迁移公式不同但都可一次性算清，见 §5.1）。

**决策点 2：时区缺省值与运行期兜底 —— 缺省 `Asia/Shanghai`，还是 `UTC`，还是 fail-closed？**

| 层次 | 选项 | 代价 | 推荐 |
|---|---|---|---|
| 新建门店时预填 | 取租户 `default_timezone`；租户也为空则 `Asia/Shanghai` | 无 | **推荐 `Asia/Shanghai`**。理由：存量数据 100% 是中国门店（`V7__seed_a380_tenant.sql:4,12` 播种值即 `Asia/Shanghai`），缺省 Shanghai 可保证存量语义连续、**不需要数据回填也不需要改历史解释**；若缺省 UTC，所有历史门店的时间解释会静默平移 8 小时 |
| 运行期 `store.timezone IS NULL` | (a) 静默回落 `Asia/Shanghai`；(b) **fail-closed**：营业时间写路径报 `STORE_TIMEZONE_MISSING`，读路径按 `Asia/Shanghai` 展示并打 warn | (b) 会在灰度期暴露一批「门店没配时区」的存量数据，需要一轮数据补录 | **推荐 (b) fail-closed 于写路径**。理由与项目既有的失败关闭习惯一致（`ReservationApplicationService.java:61-62`、`ResourceStateClient.java:83`）：时间写错比拒绝服务更难回滚。写路径拒绝、读路径降级带告警，既不放行错数据也不让后台白屏 |
| 是否允许门店时区为空 | 加 `NOT NULL` 约束 + 数据回填 | 中 | 灰度期允许 NULL（配合 (b)），收口期再补 `NOT NULL` |

**决策点 3：营业日切点（`business_day_cutoff`）是否本次一并落地？**

| 选项 | 做法 | 代价 | 推荐 |
|---|---|---|---|
| **A（推荐）** | 本次一并落地：默认切点 `04:00`（KTV 通宵场次归前一营业日），门店可改；`pay_daily_closing.business_date` 改为**服务端按门店时区+切点计算**，不接受前端传入 | 中（+3~5 人日） | **推荐**。理由：`pay_daily_closing` 的唯一键是 `(tenant_id, store_id, business_date)`（`V1__pay_baseline.sql:53`），`ReportController` 的三个报表都以 `business_date` 为聚合键与排序键（`ReportController.java:88,107,126,141,159,162`）。**若不一起改，存储口径切到 UTC 之后报表的「日」会同时受切点与时区两个未定义变量的影响，无法验收** |
| B | 本次只做时区，切点用固定的 `00:00`（自然日），后续再做 | 小 | 不推荐：通宵场次会被劈到两个自然日；且上线后切点从 `00:00` 改成 `04:00` 会**回改历史营业日**，等于二次迁移 |
| C | 沿用现状（前端传 businessDate） | 零 | 不可接受：这就是 §1.2 场景 4 的错账来源 |

---

## 1. 问题陈述

### 1.1 现状取证：时间口径在系统里已经有三套

#### (1) 口径甲：门店营业本地墙上时间（+08:00 硬编码）

| 位置 | 内容 |
|---|---|
| `platform-services/order/platform-order-service/src/main/java/io/openware/platform/order/application/ReservationApplicationService.java:92-97` | 类注释原文：「`startAt/endAt` 以带时区偏移的 `OffsetDateTime` 入参，统一转换为北京时间（UTC+8）后落库，避免转 UTC 导致前端按本地时钟展示时出现 8 小时偏移」「落库的是**门店营业本地墙上时间**（+08:00 的 LocalDateTime）」 |
| `ReservationApplicationService.java:716-721` | `toBusinessLocal(OffsetDateTime)` 实现：`odt.withOffsetSameInstant(ZoneOffset.ofHours(8)).toLocalDateTime()` —— **`+08:00` 是硬编码常量** |
| `ReservationApplicationService.java:132-133` | `po.setStartAt(toBusinessLocal(cmd.startAt()))` |
| `ReservationApplicationService.java:447-448,454-455` | 超订重叠判定按 `LocalDateTime` 比较（左闭右开） |
| `platform-services/order/platform-order-service/src/main/resources/db/migration/V19__ord_reservation_time_comment.sql:10-12` | 用新迁移把 `ord_reservation.start_at/end_at` 列注释改成「门店营业本地时间（+08:00）」 |
| `.../db/migration/V2__ord_reservation.sql:12-13` | 原列注释写的是「预约开始时间（UTC）」——注释与实现不一致，已由 V19 纠正 |
| `.../api/controller/ReservationController.java:32-38,68,174-175` | 对外契约文档化为「入参必须带偏移；出参是 +08:00 的 LocalDateTime，无偏移信息」 |
| `platform-services/order/platform-order-service/src/test/java/io/openware/platform/order/application/ReservationApplicationServiceTest.java:114` | 单元测试把这个口径固化了：断言 `START_AT.withOffsetSameInstant(ZoneOffset.ofHours(8)).toLocalDateTime()` |

#### (2) 口径乙：JVM 默认时区墙上时间（`LocalDateTime.now()`）

| 位置 | 内容 |
|---|---|
| `platform-services/order/.../application/KtvSessionApplicationService.java:94,141,155,159,207,222,501-502` | 会话创建/开台/暂停/恢复/结台全部用 `LocalDateTime.now()`；`billing_start_at = now.plusMinutes(freeWait)`（`:159`） |
| `common-services/payment/.../application/CashierApplicationService.java:48,78` | `pay_shift.opened_at`/`closed_at` = `LocalDateTime.now()` |
| `common-services/payment/.../application/CollectApplicationService.java:464` | `pay_transaction.occurred_at` = `LocalDateTime.now()` |
| `platform-services/resource/.../application/OccupationApplicationService.java:57,69-70` | `res_occupation.hold_expires_at` = `LocalDateTime.now().plusMinutes(...)` |
| `platform-services/resource/.../api/controller/InternalResourceController.java:85-86` | 内部占用端点：`request.startAt() == null ? LocalDateTime.now() : request.startAt()` |
| `platform-services/admin/.../api/controller/ReportController.java:337` | 报表默认窗口 `LocalDate.now().minusDays(30)` |
| `common-services/audit/.../application/support/AuditTimeParser.java:16,62` | 审计时间按 `ZoneId.systemDefault()` 归一 —— **服务器时区直接决定审计时间** |
| `im-services/admin/.../infra/persistence/projection/AdminProjectionQueryAdapter.java:95,219,223` | 「今日新增」用 `LocalDateTime.now().toLocalDate().atStartOfDay()`，与 `ZoneId.systemDefault()` 混用 |

#### (3) 口径丙：UTC 墙上时间（少数派）

| 位置 | 内容 |
|---|---|
| `im-services/conversation/.../application/secretgroupchat/SecretGroupChatApplicationService.java:163-164` | `LocalDateTime.now(Clock.systemUTC()).plusDays(7)` —— 同一个 SaaG 平台里唯一显式用 UTC 的地方 |

#### (4) 跨服务传输：`LocalDateTime.toString()`（无偏移字面量）

| 位置 | 内容 |
|---|---|
| `platform-services/order/.../infra/client/ResourceStateClient.java:237-241` | 开台占用把 `startAt/endAt` 以 `startAt.toString()`（`2026-09-20T19:00` 这种无偏移串）POST 给资源服务 |
| `platform-services/resource/.../api/controller/InternalResourceController.java:123-124` | 接收端声明 `LocalDateTime`，**按字面量解释** |

#### (5) 时区字段存在但未被使用

| 位置 | 内容 |
|---|---|
| `platform-services/tenant/.../db/migration/V1__tnt_iam_baseline.sql:8` | `tnt_tenant.default_timezone varchar(64) NULL COMMENT '默认IANA时区'` |
| `V1__tnt_iam_baseline.sql:28` | `tnt_store.timezone varchar(64) NULL` |
| `V1__tnt_iam_baseline.sql:30` | `tnt_store.business_day_cutoff time NULL` |
| `platform-services/tenant/.../infra/persistence/po/StorePo.java:26,30` | PO 映射了 `timezone`、`businessDayCutoff` |
| `platform-services/tenant/.../api/controller/StoreController.java:25-33` | 门店只有只读 `GET /admin/tenant/stores`，**没有创建/修改门店的写接口** —— 时区字段当前只能由 SQL/种子写入 |
| `platform-services/tenant/.../api/controller/TenantController.java:41,61` | 租户创建接受 `defaultTimezone`，但**没有门店级入口** |
| `platform-services/tenant/.../db/migration/V7__seed_a380_tenant.sql:4,12` | 种子：租户 `Asia/Shanghai`、门店 `Asia/Shanghai` |
| `gv_saas_admin/src/views/tenant/stores.vue:36` | 后台门店列表已把 `timezone` 当只读列展示 |

#### (6) 基础设施层：没有任何地方钉住时区

| 位置 | 内容 |
|---|---|
| `Dockerfile:20` | `ENTRYPOINT ["java", "-jar", "app.jar"]` —— 无 `-Duser.timezone`、无 `TZ` |
| `k8s/local/saas.yaml` env 块 | 只注入 `PORT`/`MYSQL_*`/`ROCKETMQ_*` 等，**无 `TZ`** |
| `platform-services/order/.../src/main/resources/application.yml:5` | `jdbc:mysql://${MYSQL_HOST:localhost}:3306/gv_saas?...&serverTimezone=UTC` |
| 各服务 JDBC URL（不一致） | `serverTimezone=UTC`：order、payment、tenant、resource、marketing、identity、customer、audit、admin、sms、payment-channel、idaas；`serverTimezone=Asia/Shanghai`：media、user、conversation、im-admin、message |

> **要点**：`serverTimezone` 只影响 `java.sql.Timestamp`/`Date` 的转换，对 `LocalDateTime` 是字面量透传；真正决定「口径乙」的是 **JVM 默认时区**。URL 里两种值并存说明「时区」这件事在工程上从来没有被统一决策过。

### 1.2 具体错账/错时场景

> 每条给「触发条件 → 代码依据 → 业务后果」。前提：多租户多门店（`tnt_tenant`/`tnt_store`），存在或即将存在非 UTC+8 门店（`SAAS_PLATFORM_01_SERVICE.md:61` 明确首发区域含东南亚）。

**场景 1：预约时段（跨时区门店）——C 端提交的小时数被硬编码偏移污染**

- 触发：门店时区为 `Asia/Bangkok`（UTC+7），C 端用户选择「9月20日 19:30，3 小时」。
- 代码依据：`gv_saas_mobile/c-end/datetime.js:16-17` 把门店偏移写死为 `8 * 60` 分钟；`:53-61` 的 `storeOffsetDateTime` 用 `Date.UTC` 拼出 `2026-09-20T19:30:00+08:00`；后端 `ReservationApplicationService.java:719-721` 又按 `+08:00` 拆成墙钟 `2026-09-20T19:30` 落库。
- 后果：落库字符串**在这一条路径上自洽**（前端造 +08、后端按 +08 拆），所以单看预约表不会立刻出错；但一旦任何一端改用真实门店时区（或后台按门店时区重新渲染），同一条记录的**绝对时刻会平移 1 小时**。也就是说：**这个 bug 是「潜伏 + 一旦修一端就炸」的类型**，不同端会给出不同答案（B 端后台 `gv_saas_admin/src/utils/format.js:43-47` 直接切字面量 → 显示 19:30；C 端 `datetime.test.js:55-56` 显示 19:30；但一个按 `Asia/Bangkok` 渲染的新端会显示 20:30）。

**场景 2：开台计时 vs 预约时段（同一门店内两个口径不可比）**

- 触发：门店在中国（UTC+8），容器 JVM 默认时区为 UTC（无 `TZ` 注入，`Dockerfile:20`）。
- 代码依据：
  - 预约墙钟来自 `+08:00` 转换（`ReservationApplicationService.java:719-721`）；
  - 会话 `opened_at`/`billing_start_at` 来自 `LocalDateTime.now()`（`KtvSessionApplicationService.java:141,155,159`）；
  - `KtvRoomFeeCalculator.billableSeconds` 用 `Duration.between(billingStartAt, closedAt)` 且 `Math.max(0L, d)`（`KtvRoomFeeCalculator.java:58-64`）；
  - `standardSeconds`：`reservedEndAt.isAfter(billingStartAt)` 才用预约窗口，否则回退 `default_session_minutes`（`:66-72`）；
  - 而 `reserved_start_at/reserved_end_at` **在生产代码里从未被写入**（全仓仅测试赋值：`platform-services/order/.../src/test/java/io/openware/platform/order/application/OrderIntegrationTest.java:274-275`），`openTable` 也不写（`ReservationApplicationService.java:326-327`）。
- 后果（两个独立缺陷叠加）：
  1. **超时判定失效**：预约 19:00–22:00，但会话从不记 `reserved_end_at`，`standardSeconds` 恒为 120 分钟（`default_session_minutes`）。门店若把默认时长配成 180 分钟，实际开了 150 分钟也不计超时 → **少收超时费**。
  2. **一旦补写 `reserved_end_at`，就会立刻错账**：若写入的是 `+08:00` 墙钟而 `billing_start_at` 是 UTC 墙钟，`reservedEndAt.isAfter(billingStartAt)` 对 UTC JVM 恒为 false → 永远回退；反过来若是 `billing_start_at` 比 `closed_at` 大（跨口径），`billableSeconds` 被 `Math.max(0L, d)` 钳到 0 → **房费 0**。
- 结论：**多时区改造必须先把这个「预约窗口 → 会话」的链路补上，否则改完时区仍无法验收计费。**

**场景 3：交班时间窗（现金对账）**

- 触发：收银员 23:30 开班，次日 06:00 交班；或门店与服务器时区不同。
- 代码依据：`CashierApplicationService.java:48`（开班 `LocalDateTime.now()`）、`:73-93`（交班 `now = LocalDateTime.now()`）、`:114-122`（`sumCashCollected` 用 `opened_at`/`closed_at` 作为 `pay_intent` 的收款时间窗过滤：`PayIntentMapper.java:28` 的 `from`/`to`）。
- 后果：班次时间窗与 `pay_intent` 的 `created_at` 若来自**同一次 `LocalDateTime.now()` 调用族**（都在 payment 服务内），当前是自洽的；但风险有二：
  1. payment 服务一旦为了别的改造钉住 `TZ=UTC`，**历史班次与历史收款的解释不变，新班次的窗却整体平移**，跨版本班次（开班在旧版本、交班在新版本）会把现金算多/算少；
  2. `docs/renovation/KTV_BUSINESS_01_SERVICE.md:481` 要求「金额只由服务端计算并返回同一份账单快照」，班次窗口是这套口径的时间轴，轴的定义必须先冻结。

**场景 4：营业日切分（日结唯一键被设备日期污染）**

- 触发：店长用一台时区设错（或时间改过）的电脑/手机点「日结」；或门店 02:00 仍在营业。
- 代码依据：
  - 前端默认取设备本地「今天」：`gv_saas_admin/src/views/tenant/shift.vue:164`（`dailyForm = ref({ businessDate: today() })`）、`:169-173`（`function today()` 用 `new Date()` 的 `getFullYear/getMonth/getDate`）；
  - 提交后后端**原样落库**，不校验：`CashierController.java:53`、`:94`、`CashierApplicationService.java:125-133`；
  - 唯一键 `(tenant_id, store_id, business_date)`：`common-services/payment/.../db/migration/V1__pay_baseline.sql:53`。
- 后果：
  1. **同日重复日结被唯一键挡住 → 或跨日重复落库**：设备日期比门店营业日早一天，就会写入一个「本不存在的营业日」，真正的营业日反而没有日结记录；
  2. **凌晨 0:00–04:00 的通宵场次**：按自然日归属，会被劈到两天 —— 房费算在前一天、加项和收款算在后一天，班次现金与 `pay_daily_closing.summary_json`（`V1__pay_baseline.sql:50`）对不上；
  3. 门店时区为 +07/+09 时，同一时刻在不同门店属于不同营业日，但系统没有任何字段记录这个判断依据。

**场景 5：报表按日聚合（服务端 JVM 日的错）**

- 触发：门店 UTC+7，服务器 JVM UTC；店长查 9月20日 经营报表。
- 代码依据：
  - 窗口：`ReportController.java:335-347`，`Range.of` 用 `LocalDate.now()`（`:337`）与 `f.atStartOfDay()`/`t.plusDays(1).atStartOfDay()`（`:345`）构造 `[from, to)`；
  - 切日：`ReportMapper.java:93,105`（经营报表）、`:113,124`（退款）、`:132,143`（支付报表）、`:153,164`（资源利用率）全部用 `DATE_FORMAT(...,'%Y-%m-%d')` 直接对**存储值**切日期；
  - 排序/聚合键：`ReportController.java:107-109,141-143,162-163` 都按 `businessDate` 排序、`opsKey` 用 `business_date` 作分组键（`:192-195`）。
- 后果：
  1. **门店日界的 7~8 小时窗口落错日**（例如门店 20 日 00:30 = UTC 19 日 17:30，被算进 19 日）；
  2. 服务端 JVM 日与门店营业日可能相差 **1 天**，且这个差值随时区与季节变化（DST）；
  3. 多门店租户（`/admin/reports?storeId=` 为空时）会把多个门店的「同日」混在一个 `from/to` 窗口下 —— 目前 `opsKey` 已含 `store_id`（`:193`）所以不会串门店，但**每个门店的「日」定义不同却共用一个窗口**，语义已经不自洽。

**场景 6：定时任务**

- 现状：全仓 `@Scheduled` 全部是 `fixedDelayString` 间隔触发，**没有任何 cron 表达式**（`grep` 结果：`HoldExpiryScheduler.java:16`、各 `EventOutboxRelay.java:49-52`、`MediaExpiryCleanupJob.java:21`、`SelfDestructSweepJob.java:19` 等 14 处）。
- 后果：
  1. 现状**暂时**没有「每天 04:00 跑日结」这类墙钟任务，所以定时任务侧目前是安全的；
  2. 但改造后必然会新增「营业日切点触发」的任务（日结提醒、营业日滚动）。如果那时切片口径还没定，**这些任务会各自按服务器时区判断「今天」**，重演场景 4/5；
  3. `HoldExpiryScheduler`/`EventOutboxRelay` 的比较双方都来自 `LocalDateTime.now()`（同进程同钟），**切 UTC 时必须成对迁移**，否则 `hold_expires_at`（已是 +08 墙钟的存量行）与新的 UTC now 比较会出现 8 小时误差 → 占用被提前/延迟释放。

**场景 7：审计时间戳**

- 代码依据：`common-services/audit/.../AuditTimeParser.java:16,62,67` 明确按 `ZoneId.systemDefault()` 归属带偏移的入参；`im-admin` 的 `AdminProjectionQueryAdapter.java:223` 也用 `ZoneId.systemDefault()`；而跨服务签名的 `X-IM-Service-Timestamp` 要求 **UTC epoch 毫秒**（`docs/renovation/PLATFORM_SECURITY_01_GOVERNANCE.md:164`，实现见 `ResourceStateClient.java:62,95,229`）。
- 后果：审计/日志里同时存在「系统时区墙钟」与「UTC epoch」两种时间，事故复盘时**无法用一条时间轴串起网关、业务、审计三段记录**；容器基础镜像一变（例如某次升级带上 tzdata 使默认时区从 UTC 变成 UTC 之外的镜像默认值），历史审计与新审计的墙钟口径就会静默改变。

**场景 8：跨服务内部调用（墙钟字面量过网）**

- 代码依据：`ResourceStateClient.java:237-241` 用 `LocalDateTime.toString()` 传输；`InternalResourceController.java:123-124` 接收 `LocalDateTime`；`OccupationApplicationService.java:51` 用 `isBefore` 判重叠。
- 后果：两个服务的 JVM 时区不同时，**占用窗口与订单会话窗口出现静默偏移**，而重叠判定（`:51`）不会报错 —— 只是会「偶发地」认为包厢空闲，允许两单同时占用同一包厢（`KTV_BUSINESS_01_SERVICE.md` 中的重复计费风险，`ResourceStateClient.java:24-27` 已把这类风险标为必须失败关闭）。

### 1.3 与既有规范文档的冲突（本次改造的合法性来源）

| 规范 | 原文 | 实现现状 | 冲突 |
|---|---|---|---|
| `docs/renovation/SAAS_PLATFORM_04_DATA.md:13` | 「时间使用 `DATETIME(3)` **UTC 存储**，展示按门店时区转换」 | `ord_reservation.start_at` 存 +08:00 墙钟（`V19__ord_reservation_time_comment.sql:11`） | ❌ |
| `docs/renovation/SAAS_PLATFORM_05_API.md:50` | 「时间为 **RFC3339 UTC**」 | 出参是无偏移 `LocalDateTime`（`ReservationController.java:36-37`） | ❌ |
| `docs/renovation/SAAS_PLATFORM_05_API.md:135` | 「`startAt/endAt` 必须包含时区偏移；服务端**转换为 UTC 后判断冲突**」 | 转换为 **+08:00** 后判断冲突（`ReservationApplicationService.java:719-721,447-455`） | ❌ |
| `docs/renovation/KTV_BUSINESS_01_SERVICE.md:148` | 「全部换算为秒，**UTC 落库、门店时区展示**」 | `opened_at`/`billing_start_at` 存 JVM 墙钟（`KtvSessionApplicationService.java:141-159`） | ❌ |
| `docs/renovation/SAAS_PLATFORM_01_SERVICE.md:61,125` | 「每个门店必须配置国家地区、时区、默认币种、语言、税率和**营业日切点**」 | 字段有（`V1__tnt_iam_baseline.sql:28,30`），无任何读写 | ❌ |
| `docs/renovation/PLATFORM_SECURITY_01_GOVERNANCE.md:164` | `X-IM-Service-Timestamp` 用 UTC epoch 毫秒 | 一致（`ResourceStateClient.java:62`） | ✅ |
| `docs/renovation/RESERVATION_E_MIG_01_MIGRATION.md:32` | 「`reserve_date`+`reserve_time_period`→`start_at`/`end_at`（占位，**待门店时区规则**）」 | `ReservationCompatController.java:53-54` 直接把 `start_at` 的 `toLocalDate()`/`toLocalTime()` 当兼容字段回吐 | ⚠️ 兼容层已把未定的时区规则固化进旧契约 |

> **结论**：本方案的定位不是「引入新规范」，而是**让实现回到已经批准的规范**，并把规范里留白的部分（传输形态、营业日公式、DST 策略、兼容策略）补齐。

### 1.4 P0 前置调查（必须先做，1 小时内可完成）

场景 2/5/7 的严重程度取决于**容器内 JVM 默认时区**。该值目前没有任何代码/清单钉住，必须实测：

```bash
# 在任一正在运行的服务 Pod 内
kubectl exec -it deploy/platform-order-service -- sh -c 'date; ls -l /etc/localtime 2>/dev/null; echo "TZ=$TZ"'
kubectl exec -it deploy/platform-order-service -- sh -c 'java -XshowSettings:properties -version 2>&1 | grep -i -E "user.timezone|user.country"'
```

- 若输出 `user.timezone = UTC`（Alpine 基线 + 无 tzdata 的常见结果）→ 场景 2 的第 2 个缺陷**已经在线**（预约墙钟比系统墙钟快 8 小时）。
- 若输出 `user.timezone = Asia/Shanghai` → 场景 2 的缺陷尚未触发，但**这是环境的巧合而非保证**，改造中必须显式钉住。
- 无论结果如何，**改造后必须统一钉住服务端为 UTC**（`TZ=UTC` + `-Duser.timezone=UTC`），让「口径乙」从"隐式环境约定"变为"显式契约"。

同时抓一次线上数据自检（用于 §5.1 迁移公式选型）：

```sql
-- 同一门店，预约墙钟 与 订单创建墙钟 的差值分布。若均值接近 +8h，说明两种口径已混合。
SELECT r.store_id,
       COUNT(*) AS n,
       AVG(TIMESTAMPDIFF(MINUTE, o.created_at, r.created_at)) AS avg_min
FROM ord_reservation r JOIN ord_order o ON o.id = r.order_id
WHERE r.order_id IS NOT NULL
GROUP BY r.store_id ORDER BY n DESC LIMIT 20;
```

---

## 2. 口径设计

### 2.1 时区归属层级

**层级（自上而下，后者覆盖前者）**

```
平台默认（代码常量，仅当租户/门店都为空时使用）      = Asia/Shanghai
  └─ tnt_tenant.default_timezone                 （V1__tnt_iam_baseline.sql:8）
       └─ tnt_store.timezone                     （V1__tnt_iam_baseline.sql:28）  ← 权威
```

| 项 | 设计 | 理由 |
|---|---|---|
| **权威来源** | **门店级 `tnt_store.timezone`**。所有「门店本地时间」「营业日」「营业日切点」的判断一律用门店时区 | 业务事实发生在门店：预约、开台、交班、日结、报表都以门店为单位；`tnt_store` 已有该列且已有 `country_code`/`locale`/`default_currency` 同族字段（`V1__tnt_iam_baseline.sql:28-29`） |
| **租户级 `default_timezone` 的作用** | **只作为新建门店时的预填值**，不参与任何运行时换算 | 与 `default_currency`/`default_locale` 的既有语义一致；避免「运行时到底用哪一层」的二义性 |
| **门店时区为空时** | 租户 `default_timezone`；仍为空 → 平台默认 `Asia/Shanghai`。**写路径 fail-closed**（见决策点 2） | 存量门店全部为中国门店（`V7__seed_a380_tenant.sql:12`），缺省 Shanghai 保证历史解释不变 |
| **时区格式校验** | 写入时必须能被 `java.time.ZoneId.of()` 解析，落库用 IANA ID（`Asia/Shanghai`、`America/New_York`、`Asia/Bangkok`），**禁止 `+08:00` 这种偏移字面量** | DST 地区必须靠 IANA 规则；偏移字面量在 DST 切换日会错 1 小时（§6.4 TC-D3） |
| **是否需要写接口** | **需要**：新增 `PUT /admin/tenant/stores/{id}` 允许改 `timezone` / `business_day_cutoff`（当前只有 `GET`，`StoreController.java:25-33`）。属于 ADM 权限域，复用既有门店权限码 | 没有写入口就无法接入海外门店；必须先有时区才能谈多时区 |
| **改时区后的历史数据** | **不改历史解释**：时区变更只影响**变更之后**新产生记录的换算；需要可追溯时，在记录上快照 `store_timezone`（见 §2.2 选项 A2） | 与币种改造的「历史快照不重算」口径一致（`docs/standards/16_CURRENCY_CONVENTIONS.md` 在 `CashierApplicationService.java:49-51` 的落地方式：开班固化币种快照） |

### 2.2 存储口径

#### 选项对比

| 选项 | 形态 | 迁移代价 | 失败场景 |
|---|---|---|---|
| **A（推荐）** | 业务时间列一律 **UTC `datetime(3)`**；门店本地语义在读写边界用门店时区换算；报表/日结**额外物化** `business_date DATE`（+ 需要时快照 `store_timezone VARCHAR(64)`） | 大：存量需按「列的实际口径」分别迁移（§5.1），涉及 `ord_reservation`、`ord_ktv_session`、`ord_order`、`ord_ktv_server_session`、`res_occupation`、`res_schedule`、`pay_shift`、`pay_intent`、`pay_transaction`、`pay_refund`、`pay_daily_closing`，以及新增 `business_date` 的回填 | 若只改类型不改换算点 → 出现「一半 UTC 一半墙钟」；因此必须**按服务/按链路成对发布**（§4.6） |
| B | 继续存门店墙上时间（把硬编码 +08 换成 `store.timezone`） | 小 | ①系统时间列（`created_at`/`opened_at`）仍是 JVM 墙钟，与门店墙钟不同坐标系；②报表按营业日聚合仍需二次改造；③DST 地区在切换日**墙上时间会重复或不存在**（例如 02:30 出现两次），无法区分 → 该方案**物理上无法支持 DST 地区**；④`tnt_store.timezone` 一旦被修改，历史数据的解释随之改变且不可回溯 |
| C | epoch 毫秒 `bigint` | 大 | 违反 `SAAS_PLATFORM_04_DATA.md:13` 的 `DATETIME(3)` 约定；运维排查、索引与既有 SQL 全部受影响；收益不比 A 多 |
| ❌ 不作为选项：维持现状（混合） | 三种墙上时间并存 | 零 | 场景 1–8 全部保留，且**新增任何一个 `LocalDateTime.now()` 调用点都会扩大污染面** |

#### 推荐：A，并细化为 A1/A2 两条规则

**A1（存储，强制）**：所有时间戳语义的列一律存 **UTC 墙钟**（`datetime(3)`，即 `Instant` 去偏移后的字面量）。

- 覆盖：`created_at`/`updated_at`（全套）、`ord_order.completed_at`/`cancelled_at`、`ord_reservation.start_at`/`end_at`、`ord_ktv_session.reserved_start_at`/`reserved_end_at`/`opened_at`/`closed_at`/`billing_start_at`/`pause_started_at`、`ord_ktv_server_session.ordered_at`/`started_at`/`ended_at`、`res_schedule.start_at`/`end_at`、`res_occupation.start_at`/`end_at`/`hold_expires_at`、`pay_intent.expires_at`、`pay_shift.opened_at`/`closed_at`、`pay_transaction.occurred_at`、`pay_refund.*_at`。
- 类型不变（仍 `datetime(3)`），**只改写入与读出时的换算**：写 `LocalDateTime.ofInstant(instant, ZoneOffset.UTC)`，读 `instant.atZone(storeZone)`。
- `datetime(3)` 不是 `timestamp`：MySQL `TIMESTAMP` 会自动按会话时区转换，而 `DATETIME` 不会 —— **继续用 `DATETIME` 是不引入隐式转换的正确选择**（现在是 `datetime(3)`，无需改类型）。这一点必须在文档里写死，避免有人「顺手」改成 `timestamp`。

**A2（门店语义物化，推荐）**：对**以「营业日」为业务键/聚合键**的表，额外物化派生列，避免每次查询都做「UTC → 门店时区 → 减切点 → 取日期」的四步计算（这四步在 SQL 里对索引不友好，且 `tnt_store` 可能被改）。

| 表 | 新增列 | 用途 | 证据 |
|---|---|---|---|
| `pay_daily_closing` | `store_timezone VARCHAR(64) NOT NULL`（快照） | 日结的法律口径固化；时区被改后仍可复核 | 唯一键 `(tenant_id, store_id, business_date)` 见 `V1__pay_baseline.sql:53` |
| `ord_reservation` | `business_date DATE NOT NULL`、`store_timezone VARCHAR(64) NOT NULL` | 按营业日筛选/展示预约（C 端「今天的预约」）；跨零点场次归属 | 索引 `idx_ord_reservation_store_status`（`V2__ord_reservation.sql:24`）已含 `start_at`，可与 `business_date` 并存 |
| `ord_ktv_session` | `business_date DATE NULL` | 通宵场次归属到开场营业日 | `V1__ord_order_baseline.sql:34-48` 现无该列 |
| `pay_shift` | `business_date DATE NULL` | 班次跨营业日时归属 | `V1__pay_baseline.sql:34-44` |
| `pay_transaction` | `business_date DATE NULL` | 报表按营业日聚合，替代 `DATE_FORMAT(occurred_at)`（`ReportMapper.java:132`） | — |
| `ord_order` | `business_date DATE NULL` | 替代 `DATE_FORMAT(o.created_at)`（`ReportMapper.java:93`） | — |

> 物化列的维护方式：**在应用层写入时计算**（同一事务内），禁止用 `GENERATED COLUMN`（生成列不能引用另一张表 `tnt_store`），也禁止在查询期 join 计算（会破坏 `opsKey` 的索引可用性，见 `ReportMapper.java:105`）。

#### 不推荐方案的失败场景（写明以防反复）

- **继续存墙上时间（B）**：DST 地区在切换日 02:00–03:00 的墙上时间**物理上不可唯一表示**（秋季回拨时 01:30 出现两次）。此时「预约 01:30」无法判断是哪一次 → 冲突判定与计费都会错。北美/欧洲门店必然踩到。同时 `tnt_store.timezone` 被修改后，历史墙上时间的绝对时刻会静默改变，历史班次的对账结论随之改变。
- **改 `DATETIME` → `TIMESTAMP`**：MySQL `TIMESTAMP` 按**会话时区**（由 `serverTimezone` 决定，各服务当前 UTC/Shanghai 并不一致）做隐式转换 → 同一个值在不同服务读出来不一样。**明确禁止。**
- **混合（现状）**：任何一次「为了修 A 服务而钉住 TZ」的操作都会让 A 与 B 的既有数据错位，且没有字段能标记每条记录的口径。

### 2.3 传输口径

#### 选项对比

| 选项 | 形态 | 优点 | 缺点 |
|---|---|---|---|
| **A（推荐）** | **RFC3339 带偏移**：`"startAt":"2026-09-20T19:00:00+08:00"` | 与 `SAAS_PLATFORM_05_API.md:50,135` 一致；无歧义；任何语言的标准库都能解析；`OffsetDateTime` 无需额外字段 | 客户端必须理解偏移（现状前端 `formatTime` 只做字面切片，需改） |
| B | 本地时间 + 时区 ID：`{"localDateTime":"2026-09-20T19:00:00","timeZone":"Asia/Shanghai"}` | 对「墙上时间」意图表达更直白 | 两个字段必须成对校验，否则又退化为无偏移串；与既有规范 `:50` 冲突；HTTP 层校验成本更高 |
| C | epoch 毫秒：`"startAt":1758375600000` | 绝对无歧义 | 与 `SAAS_PLATFORM_05_API.md:50` 的「RFC3339 UTC」冲突；可读性差（日志/工单/手工调用痛苦） |
| ❌ 现状串 | `"startAt":"2026-09-20T19:00:00"`（无偏移） | 无 | **禁止作为入参**：无法判断是门店本地还是 UTC。作为出参也禁止（`SAAS_PLATFORM_05_API.md:50` 已要求 UTC） |

**推荐 A。**

#### 出入参规则

| 方向 | 规则 |
|---|---|
| **入参**（`POST/PUT` 的 `startAt`/`endAt`/`fromAt`/`toAt`） | 必须是带偏移的 RFC3339（`2026-09-20T19:00:00+08:00`）或 `Z` 结尾的 UTC 实例。**缺偏移 → 400 `TIME_OFFSET_REQUIRED`**（fail-closed，不要猜）。这条是现状 `ReservationController` 已经文档化但**未强制**的行为（`ReservationController.java:174`），需补校验 |
| **入参补充** | 允许只传 `businessDate`（`YYYY-MM-DD`）+ `timeSlot`（`HH:mm`）+ 门店上下文，由服务端用门店时区组装 —— **C 端场景推荐用这个形态**，因为它把「门店本地」的意图显式化，且让前端不必知道偏移。此时服务端用**请求上下文里的 `storeId` 对应的 `tnt_store.timezone`** 组装，绝不接受客户端传偏移 |
| **出参** | 一律返回带偏移的 RFC3339（`2026-09-20T19:00:00+08:00`，偏移取门店时区在**该时刻**的实际偏移，自动覆盖 DST）。同时**同时返回** `storeTimezone` 与（列表/报表场景）`businessDate`，让前端零推断 |
| **内部服务间调用** | 同样用 RFC3339 带偏移（现状 `ResourceStateClient.java:240-241` 的无偏移串必须改）。**禁止** `LocalDateTime` 跨进程 |
| **`fromAt`/`toAt` 语义** | **左闭右开 `[fromAt, toAt)`**，与既有实现一致（`ReservationApplicationService.java:443-444`、`ReconciliationApplicationService.java:37` `ge`/`lt`、`ReportMapper.java:32` `>=`/`<`）。两端都必须带偏移 |
| **`fromAt`/`toAt` 省略时** | **按门店营业日**取默认窗口：单门店查询 = 该门店「最近 N 个营业日」；`storeId` 为空的租户级查询 = **必须显式传 `fromAt`/`toAt`**，否则 400 `TIME_RANGE_REQUIRED`（因为多门店没有共同的「日」）。现状 `ReportController.Range.of` 用 `LocalDate.now()` 兜底（`:337`）必须删除 |
| **不支持** | 无偏移的 `LocalDateTime` 查询参数。现状 `ReconciliationController.java:19-21`（`@RequestParam LocalDateTime from/to`）与 `ReportController.java:43`（`LocalDate from/to` 尚可，但解释口径要改为门店营业日）需要收紧 |
| **契约缺口** | 规格已定义 `GET /api/v1/business/reservations?fromAt&toAt`（`SAAS_PLATFORM_05_API.md:128`）与订单列表的 `fromAt&toAt`（`:144`），但实现里 `ReservationController.list()` 无任何参数（`:53-57`）。本次一并补齐并按上述语义实现 |

### 2.4 展示口径（三端一致）

**目标**：**前端只做展示，不做「换算决策」**；换算的唯一真源是后端返回的带偏移串 + `storeTimezone` 字段。前端的职责只是「把带偏移串按指定时区渲染成 `YYYY-MM-DD HH:mm`」。

#### 现状问题

| 端 | 现状 | 问题 |
|---|---|---|
| 后台 B 端 | `gv_saas_admin/src/utils/format.js:27-31` `normalizeTimeText` 把 `T` 换空格；`:43-47` `formatTime` 直接 `slice(0,16)` | **对带偏移串会切出错误内容**：`"2026-09-20T19:00:00+08:00".slice(0,16)` = `"2026-09-20 19:00"` 恰好对，但 `"2026-09-20T11:00:00Z"` 会显示 `11:00` 而不是门店的 `19:00` —— **依赖「后端一定返回门店墙钟」这个隐含约定**。测试守卫 `gv_saas_admin/src/utils/admin-copy.test.js:82,92` 还规定 `function formatTime` 只能在 `format.js` 定义 |
| C 端 H5 | `gv_saas_mobile/c-end/datetime.js:16-17` 门店偏移硬编码 `8*60`；`:68-80` `formatStoreDateTime` 对带偏移串先 `+8h` 再展示 | 时区硬编码；`+8h` 后按 UTC 读字段，**只对 UTC+8 门店正确** |
| B 端 App（`gv_saas_mobile/src/b-end`） | `gv_saas_mobile/src/shared/utils/amount.js:32-35` `fmtTime` 字面量切片 | 同后台问题 |
| B 端后台订单页 | `gv_saas_admin/src/views/tenant/orders.vue:812-813,877,912-921` 用 `dateValue()`/`elapsedText()` 算「已开台时长」 | 若时间串带偏移而这里按字面量 `new Date()` 解析，**在非 UTC+8 设备上会算错时长** |

#### 三端统一做法（推荐）

1. **后端**：出参一律带偏移 RFC3339 + 同级 `storeTimezone`（见 §2.3）。这是**唯一的机器可读真源**。
2. **前端新增统一渲染函数**（各端一份实现，语义相同）：
   - 后台：`gv_saas_admin/src/utils/format.js` 新增 `formatStoreTime(value, timeZone)`，`formatTime` 保留为 `formatStoreTime` 在「无 `timeZone` 且值为无偏移串」时的兼容分支；
   - B 端 App：`gv_saas_mobile/src/shared/utils/amount.js` 的 `fmtTime` 升级为 `fmtTime(value, timeZone)`；
   - C 端 H5：`gv_saas_mobile/c-end/datetime.js` 删除 `STORE_OFFSET_MINUTES` 常量，改为 `formatStoreDateTime(value, timeZone)`，**必须支持通过门店上下文动态传入**（`c-end/context.js` 已有门店上下文的加载方式）。
   - 渲染规则：`new Date(value)` 得到绝对时刻，再用 `Intl.DateTimeFormat(..., { timeZone })` 取值。**禁止 `toISOString()` 直接展示**（既有的 C 端注释 `datetime.js:5-11` 已经总结了这条）。
3. **降级规则（必须显式写进代码，避免 silent wrong）**：
   - 值为无偏移串（历史数据/未迁移接口）→ **按门店时区解释**（而不是按设备时区）。若拿不到门店时区，按 `Asia/Shanghai` 解释并 `console.warn` 一次；
   - 值为带偏移串 → 按偏移转绝对时刻，再按**传入的门店时区**渲染。两者一致时结果相同，不一致时以门店时区为准（这才是业务想要的）。
4. **测试守卫同步升级**：`admin-copy.test.js:87-95` 的「`formatTime` 只在 `utils/format.js` 定义」守卫扩展为覆盖 `formatStoreTime`；C 端 `c-end/datetime.test.js:64` 的导出清单断言（`STORE_OFFSET_MINUTES` 被导出）需要同步改，否则测试会红——**这是一次「有意的契约变更」，必须在同一次提交里改测试**。
5. **明确不做的**：不在前端做「UTC ↔ 门店时区」以外的任何换算（不比时区、不判断营业日、不判断 DST）。营业日由后端给出的 `businessDate` 字段直接展示。

---

## 3. 营业日（business date）定义

### 3.1 配置

| 项 | 设计 | 依据 |
|---|---|---|
| 配置位置 | `tnt_store.business_day_cutoff TIME`（已存在） | `V1__tnt_iam_baseline.sql:30`；`StorePo.java:30` |
| 默认值 | **`04:00:00`** | KTV 通宵场次典型到 02:00–04:00；`04:00` 是行业常见的「作息切点」。**必须显式配置而不是留 NULL**，留 NULL 会让「营业日」退化为自然日 |
| 允许范围 | `00:00`–`12:00`（>12:00 会让「早班」归到前一营业日，业务上不合理）。超出 → 400 | — |
| 谁来配 | 门店管理员（新增的 `PUT /admin/tenant/stores/{id}`）；租户级可提供默认切点（建议放 `tnt_tenant_config`，键 `default_business_day_cutoff`，与 `wallet_brand_name` 同族，见 `V7__seed_a380_tenant.sql:20-22` 的既有用法） | — |

### 3.2 公式（唯一实现，必须由服务端提供）

```
instant            : 绝对时刻（UTC）
zone               : tnt_store.timezone（IANA）
cutoff             : tnt_store.business_day_cutoff（LocalTime）

localDateTime      = instant.atZone(zone)
businessDate       = (localDateTime.toLocalTime() < cutoff)
                       ? localDateTime.toLocalDate().minusDays(1)
                       : localDateTime.toLocalDate()
```

- **实现位置**：新增 `common-services` 或 `platform-services/common` 下的 `StoreTimeService`（Java 侧）+ 前端仅消费结果。**禁止**在 SQL 里用 `CONVERT_TZ` 复刻这套逻辑（MySQL `CONVERT_TZ` 依赖 `mysql.time_zone_name` 表，容器化 MySQL 常常没导入时区表，会返回 NULL，且 `business_day_cutoff` 是 `TIME` 列无法直接用）。
- **物化**：公式结果写入 §2.2 A2 的 `business_date` 列，查询直接按列过滤/分组/排序（保住 `ReportMapper.java:105` 的 `GROUP BY` 可用索引）。
- **变更切点的影响**：切点变更**只影响变更之后的记录**（与 §2.1「时区变更不改历史」同一口径）。变更时在门店上留一条审计（既有 `AuditClient.recordAsync` 模式，见 `CashierApplicationService.java:86-91`）。

### 3.3 与门店时区、交班/日结唯一键、报表聚合的关系

| 关联项 | 现状 | 改造后 |
|---|---|---|
| 门店时区 | 字段存在但未用（§1.1(5)） | `business_date` 的输入之一；`store_timezone` 快照进日结行 |
| 交班唯一键 | `pay_daily_closing` `(tenant_id, store_id, business_date)`（`V1__pay_baseline.sql:53`）**不变** | `business_date` 改由服务端计算；前端传入的值**必须**与服务端计算结果一致，否则 409 `BUSINESS_DATE_MISMATCH` |
| 交班时间窗 | `pay_shift.opened_at`/`closed_at` 与 `pay_intent.created_at` 比较（`CashierApplicationService.java:118-120`） | 三者同为 UTC，比较语义不变但**坐标系统一**；同时新增 `pay_shift.business_date`，让「跨营业日的班次」在报表里可归属 |
| 报表按日聚合 | `DATE_FORMAT(created_at,'%Y-%m-%d')`（`ReportMapper.java:93,113,132,153`） | `GROUP BY o.business_date`（索引友好、语义确定）；`ReportController.opsKey`（`:192-195`）保持 `store_id\|business_date\|currency_code` 三元组不变 |
| 报表窗口 | `Range.of` 用 `LocalDate.now()`（`ReportController.java:337,345`） | `from`/`to` 语义改为**营业日**；省略时按 `storeId` 对应门店的营业日兜底。`storeId` 为空时强制显式传参（§2.3） |

### 3.4 跨零点营业 / 通宵场次的归属规则（明确拍板项）

| 对象 | 归属规则 | 理由 |
|---|---|---|
| **预约**（`ord_reservation`） | 按 **`start_at` 的营业日**归属，写入 `business_date`。预约 9/20 23:00–9/21 02:00 → `business_date = 2026-09-20` | 「营业日的预约量」是排班与房态日报的输入，按开场归属符合门店直觉 |
| **KTV 会话**（`ord_ktv_session`） | 按 **`opened_at` 的营业日**归属，写入 `business_date`。**不拆分**跨营业日的场次 | 场次是原子履约单元，拆分会同时破坏「房费一笔」「超时一笔」的金额一致性与 `KtvRoomFeeCalculator` 的整段计费（`KtvRoomFeeCalculator.java:58-92`）；资源利用率/翻台率按开场归属与 `ReportMapper#selectResourceUtilization` 的 `DATE_FORMAT(ks.opened_at,...)`（会话开台时间）语义一致 |
| **收款**（`pay_transaction`） | 按 **`occurred_at` 的营业日**归属，写入 `business_date` | 现金对账必须跟「钱到账的时刻」走，否则班次长短款无法解释 |
| **班次**（`pay_shift`） | 按 **`opened_at` 的营业日**归属，写入 `business_date`；跨切点的班次归属到开班日 | 与日结的「一个营业日一次日结」对齐；跨切点班次在报表里显式标记 `cross_business_day = 1`（可选，见 §4.1） |
| **日结**（`pay_daily_closing`） | 一个 `(tenant_id, store_id, business_date)` 一行，唯一键不变 | 现有唯一键 `V1__pay_baseline.sql:53` 已经是这个语义，只是 `business_date` 的来源要改 |
| **不一致时的处理** | 若某一营业日内既有会话收入也有跨日收款，日结的 `summary_json` 按 `business_date` 汇总，**不按自然日重算** | 避免「日结与报表对不上」这类对不上账的经典问题 |

---

## 4. 改动清单

> 迁移文件编号：编写本方案时 `platform-order-service` 已用至 **V23**（`V23__ord_reservation_room_type.sql`）、`platform-tenant-service` 至 **V26**、`common-payment-service` 至 **V10**、`platform-resource-service` 至 **V7**。并发改造中这些编号可能被其他分支占用，**实施时以合并时刻实际最大编号 +1 为准**。

### 4.1 数据层（迁移）

| # | 文件 / 对象 | 改动 | 风险 | 分批 |
|---|---|---|---|---|
| D1 | `platform-services/tenant/.../db/migration/V27__tnt_store_timezone_not_null.sql`（新） | 回填 `tnt_store.timezone`（租户 `default_timezone` → `Asia/Shanghai`）、`business_day_cutoff`（默认 `04:00:00`）；`timezone` 加 `NOT NULL`；补 `CHECK`（长度与 IANA 格式不做 DB 校验，应用层校验） | **中** | 批 1 |
| D2 | `platform-services/order/.../db/migration/V24__ord_time_utc.sql`（新） | `ord_reservation`/`ord_ktv_session`/`ord_order`/`ord_ktv_server_session`：①新增 `business_date DATE NULL`、`store_timezone VARCHAR(64) NULL`；②新增 UTC 影子列（`start_at_utc` 等）用于双写；③**不删旧列**（回滚保险） | **高** | 批 2（影子列）/ 批 5（切换） |
| D3 | `common-services/payment/.../db/migration/V11__pay_time_utc.sql`（新） | `pay_shift`/`pay_daily_closing`/`pay_intent`/`pay_transaction`/`pay_refund`：同上（`business_date`、`store_timezone`、UTC 影子列） | **高** | 批 2 |
| D4 | `platform-services/resource/.../db/migration/V8__res_time_utc.sql`（新） | `res_occupation`/`res_schedule`：UTC 影子列 + `business_date` | **中** | 批 2 |
| D5 | 存量数据回填脚本（一次性 SQL，走 Flyway `V*__backfill_time_utc.sql` 或运维脚本，见 §5.1） | 按列的实际口径分别换算（**两类**:+08 墙钟 / JVM 墙钟） | **高** | 批 3 |
| D6 | H2 测试 schema：`platform-services/order/.../src/test/resources/db/test-migration/V1__ord_h2_schema.sql:62` 附近的 `ord_ktv_session` 建表 | 同步加列，否则集成测试失败 | **低** | 随 D2 |

**注意（反复强调）**：类型保持 `datetime(3)`，**禁止改 `timestamp`**（§2.2）。

### 4.2 服务层

| # | 文件 | 改动 | 风险 |
|---|---|---|---|
| S1 | `platform-services/order/.../application/ReservationApplicationService.java:92-97,132-133,447-448,716-721,735,743-744` | 删除 `toBusinessLocal`（硬编码 +08）；改为 `OffsetDateTime` → `Instant` → UTC `LocalDateTime` 落库；读路径改为按门店时区转 RFC3339 出参；重叠判定改在 UTC 上做（结果与门店时区无关，语义不变）；同时写 `business_date`/`store_timezone` | **高** |
| S2 | `ReservationApplicationService.java` 新增：预约窗口 → 会话的传递 | `openTable`（`:299-336`）把 `r.getStartAt()/getEndAt()` 转 UTC 写入会话的 `reserved_start_at`/`reserved_end_at`（**修复 §1.2 场景 2 的前置缺口**） | **高** |
| S3 | `platform-services/order/.../application/KtvSessionApplicationService.java:94,141,155,159,207,222,501-502` | `LocalDateTime.now()` → `LocalDateTime.now(Clock.systemUTC())`（统一注入 `Clock`，便于测试）；`calculateRoomFee` 的 `endAt` 同为 UTC | **高** |
| S4 | `platform-services/order/.../api/controller/ReservationController.java:32-38,53-57,68,174-175` | 出参改带偏移 RFC3339 + `storeTimezone`；`list()` 补 `fromAt`/`toAt`（§2.3）；`startAt`/`endAt` 缺偏移 → 400 `TIME_OFFSET_REQUIRED` | **中** |
| S5 | `platform-services/order/.../api/controller/ReservationCompatController.java:53-54` | 兼容层 `reserveDate`/`reserveTimePeriod` 改为按门店时区从 UTC 换算（保持旧契约字段名不变，语义修正） | **中** |
| S6 | `common-services/payment/.../application/CashierApplicationService.java:44-55,73-93,114-122,125-133` | ①统一 UTC 时钟；②`submitDailyClosing` 不再信任入参 `businessDate`：按 `storeId` 计算营业日，与入参不一致 → 409 `BUSINESS_DATE_MISMATCH`；③写 `store_timezone` 快照；④`openShift` 写 `business_date` | **高** |
| S7 | `common-services/payment/.../api/controller/CashierController.java:53,94` | 契约：`businessDate` 变为**可选**（省略则由服务端算），语义变更需在 OpenAPI 快照里体现 | **中** |
| S8 | `common-services/payment/.../application/ReconciliationApplicationService.java:34-38` + `ReconciliationController.java:19-21` | `LocalDateTime from/to` → 带偏移的 `OffsetDateTime`；语义 `[from, to)` 不变 | **中** |
| S9 | `platform-services/resource/.../api/controller/InternalResourceController.java:85-86,123-124` | 内部占用端点改用带偏移的 `OffsetDateTime`；缺偏移 → 400（内部调用同样 fail-closed） | **中** |
| S10 | `platform-services/order/.../infra/client/ResourceStateClient.java:216-257`（尤其 `:240-241`） | 传 `OffsetDateTime.toString()`（带偏移）替代 `LocalDateTime.toString()` | **中** |
| S11 | `platform-services/resource/.../application/OccupationApplicationService.java:45-70,111,146-164` | 统一 UTC 时钟；重叠判定在 UTC 上做；`releaseExpiredHolds` 与新 now 成对迁移（**必须同批发布**，见 §1.2 场景 6） | **高** |
| S12 | `platform-services/admin/.../api/controller/ReportController.java:40-44,76-80,114-118,148-152,335-347` | 删除 `LocalDate.now()` 兜底；`from`/`to` 语义改为营业日；`storeId` 为空时强制显式传参 | **中** |
| S13 | `platform-services/admin/.../infra/persistence/mapper/ReportMapper.java:24-167` | `DATE_FORMAT(x,'%Y-%m-%d')` → 直接 `GROUP BY x.business_date`；窗口过滤 `[from,to)` 不变 | **中** |
| S14 | `common-services/audit/.../application/support/AuditTimeParser.java:16-67` | `ZoneId.systemDefault()` → 显式 `ZoneOffset.UTC`（审计入库统一 UTC） | **中** |
| S15 | `im-services/admin/.../infra/persistence/projection/AdminProjectionQueryAdapter.java:95,219,223` | `LocalDateTime.now()`/`systemDefault()` → UTC（IM 侧同样口径） | **中** |
| S16 | `platform-services/tenant/.../api/controller/StoreController.java:25-33` + 新增 `StoreApplicationService` | 新增门店写接口（`timezone`/`business_day_cutoff`），做 IANA 校验 + 审计 | **中** |
| S17 | `platform-services/tenant/.../api/controller/TenantController.java:41,61` | 创建租户时校验 `defaultTimezone` 是合法 IANA；为空 → `Asia/Shanghai` | **低** |
| S18 | `im-services/conversation/.../application/secretgroupchat/SecretGroupChatApplicationService.java:163-164` | 已是 UTC，**保持不变**，但把 `Clock.systemUTC()` 改为注入 `Clock`（一致性） | **低** |
| S19 | 新增公共组件 `StoreTimeService`（计算营业日 / 换算展示） | 三处都要用（order/payment/admin），放在 `common-services` 或 `platform-common`；**唯一实现**，禁止各处自行写 | **中** |
| S20 | 基础设施 | `Dockerfile:20` 加 `ENV TZ=UTC` 与 `-Duser.timezone=UTC`；`k8s/**` 各 deployment 注入 `TZ=UTC`；**所有** JDBC URL 的 `serverTimezone` 统一为 `UTC`（当前 media/user/conversation/im-admin/message 是 `Asia/Shanghai`） | **中** |

### 4.3 网关 / 契约

| # | 对象 | 改动 | 风险 |
|---|---|---|---|
| G1 | `docs/openapi/**` 契约快照 | 时间字段类型从 `string`（无格式）改为 `string format: date-time`；新增 `storeTimezone`、`businessDate`；`GET /business/reservations` 补 `fromAt`/`toAt`。既有快照是按现网全量重导的（见 `git log` 中 `docs(openapi)` 提交），需重新导出 | **中** |
| G2 | `gateways/gateway` | 无需逻辑改动；仅确认不缓存带 `X-Tenant-Context` 的响应（跨时区门店同一路径不同结果） | **低** |
| G3 | `docs/renovation/SAAS_PLATFORM_05_API.md:50,128,135,144` | 补齐「偏移必填 + 左闭右开 + businessDate 语义」的明确条文（现条文的「UTC」与「RFC3339 UTC」在出参形态上仍留有歧义） | **低** |

### 4.4 前端

| # | 文件 | 改动 | 风险 |
|---|---|---|---|
| F1 | `gv_saas_admin/src/utils/format.js:27-31,43-47,54-60` | 新增 `formatStoreTime(value, timeZone)`；`formatTime` 保留为兼容分支并加「无偏移串按门店时区解释」策略；`normalizeTimeText` 需能识别偏移后缀 | **中** |
| F2 | `gv_saas_admin/src/utils/admin-copy.test.js:77-95` | 守卫扩展到 `formatStoreTime`（同一次提交内改，否则测试红） | **低** |
| F3 | `gv_saas_admin/src/views/tenant/reservations.vue:33,80` | `formatTime(row.startAt)` → `formatStoreTime(row.startAt, row.storeTimezone)` | **低** |
| F4 | `gv_saas_admin/src/views/tenant/shift.vue:164,169-173,254-266` | 删除 `today()` 的「设备今天」语义；`businessDate` 默认取后端返回的门店营业日；提交时允许省略由服务端计算 | **中** |
| F5 | `gv_saas_admin/src/views/tenant/orders.vue:645,812-813,877,912-921` | 时长计算（`elapsedText`）改为基于绝对时刻（带偏移解析），避免设备时区影响 | **中** |
| F6 | `gv_saas_admin/src/views/tenant/reports.vue:64,75,95` | `businessDate` 保持展示；筛选参数改传 `from`/`to`（营业日）并在多门店时强制显式 | **低** |
| F7 | `gv_saas_admin/src/views/tenant/stores.vue:36` | 时区列改为可编辑表单（依赖 S16） | **低** |
| F8 | `gv_saas_mobile/c-end/datetime.js:16-17,49-61,63-80,82-88` | 删除 `STORE_OFFSET_MINUTES`；`storeOffsetDateTime(dateStr, timeStr, addHours, timeZone)`；`formatStoreDateTime(value, timeZone)` 用 `Intl.DateTimeFormat` | **中** |
| F9 | `gv_saas_mobile/c-end/datetime.test.js:38-64` | 断言同步更新（含导出清单 `:64`）；**保留** `'...T19:30:00+08:00'` → `9月18日 19:30` 这类用例（`Asia/Shanghai` 下仍应通过） | **低** |
| F10 | `gv_saas_mobile/c-end/app.js:330-335,780,892,1196-1202` | 提交预约时改为传「门店本地日期 + 时段」或带门店真实偏移的串（依赖 §2.3 的入参形态） | **中** |
| F11 | `gv_saas_mobile/src/shared/utils/amount.js:32-35` | `fmtTime(value, timeZone)` | **低** |
| F12 | `gv_saas_mobile/src/b-end/views/Reservations.vue:31,58` | 传入门店时区 | **低** |
| F13 | `gv_saas_mobile/src/b-end/preview/saas.js:9,19,29,39` | 预览数据补 `storeTimezone`（避免预览页空白） | **低** |

### 4.5 定时任务与报表

| # | 对象 | 改动 | 风险 |
|---|---|---|---|
| J1 | `platform-services/resource/.../application/HoldExpiryScheduler.java:16-18` + `OccupationApplicationService.java:111` | 两者成对切 UTC；`OccupationMapper.java:28` 的 `hold_expires_at < #{now}` 保持不变（同为 UTC） | **高**（必须同批） |
| J2 | 各 `EventOutboxRelay.java:49-52`（payment/order/resource/marketing/user/message/conversation） | `LocalDateTime.now()` → UTC；`next_retry_at`/`published_at` 同理 | **中** |
| J3 | `common-services/media/.../MediaExpiryCleanupJob.java:21-24` + `MediaUploadSessionService.java:53,78,85,108` | 成对切 UTC | **中** |
| J4 | 新增「营业日切点」任务（本次**不新建**，仅在设计里预留） | 若未来要「切点触发日结提醒」，调度必须按 `(store.timezone, business_day_cutoff)` 计算下一次触发时刻，**禁止用 cron 固定小时** | — |
| J5 | `platform-services/admin/.../ReportController.java` + `ReportMapper.java` | 见 S12/S13 | **中** |

### 4.6 风险等级与「可分批上线」切分

| 批次 | 内容 | 是否可独立上线 | 回滚方式 |
|---|---|---|---|
| **批 1（地基，无行为变化）** | D1（门店时区/切点回填 + 非空）、S16/S17（门店写接口 + IANA 校验）、S19（`StoreTimeService` 实现 + 单测）、S20（基础设施钉 UTC）、G1 契约补 `storeTimezone` 字段 | ✅ 可。**注意**：S20 的「钉住 UTC」会让 `LocalDateTime.now()` 从「容器默认」变成「确定 UTC」，**如果 §1.4 探针显示当前不是 UTC，这一批本身就会改变行为** —— 因此批 1 必须在**业务低峰**发布，且发布前后各做一次「同门店报数对比」 | 回滚 k8s env / 回滚迁移（D1 是纯加列与非空，可逆） |
| **批 2（双写，读旧）** | D2/D3/D4（UTC 影子列）、S1/S3/S6/S11 的**写入侧**改为双写（旧列 + UTC 影子列），读路径**仍读旧列** | ✅ 可。零行为变化，纯数据积累 | 停双写即可 |
| **批 3（回填 + 校验）** | D5 存量回填（§5.1）、新增对账脚本：比对影子列与「由旧列推导的 UTC」是否一致，差异行产出报告 | ✅ 可（只读校验 + 写影子列） | 重跑回填 |
| **批 4（切读，灰度）** | 读路径切到 UTC 影子列并按门店时区出参（S1/S3/S4/S6 读侧）；前端 F1/F3/F8/F11 同步上线；**用 feature flag `timezone.read-model=legacy\|utc` 控制** | ✅ 可按租户/门店灰度 | flag 切回 `legacy`（旧列仍在，双写在跑） |
| **批 5（收口）** | 停双写、删旧列（新迁移）、`business_date` 补 `NOT NULL`、报表切 `business_date`（S12/S13）、日结营业日改服务端计算（S6/S7）、前端 F4 | ⚠️ 建议与「币种」改造的收口同一窗口 | 依赖批 4 的 flag；回滚需保留旧列快照 —— **本批建议保留旧列至下一个大版本再删** |

**双轨（dual-track）机制的具体形态**

- 表结构：`start_at`（旧，墙上时间）与 `start_at_utc`（新）**并存**，双写；
- 读：由配置 `app.timezone.model = legacy | utc` 决定读哪一列、如何出参（`legacy` = 现状口径，`utc` = 带偏移 RFC3339）；
- 出参：`utc` 模式下**额外**返回 `startAtLegacy`（旧口径墙钟串），让未升级的旧客户端继续可用（§5.3）；
- 灰度粒度：按 `tenant_id` 白名单（配置项，非硬编码），与既有 `SAAS_DEV_SESSION_*` 之类的环境开关风格一致（`k8s/local/saas.yaml:106-108`）。

---

## 5. 迁移与兼容

### 5.1 存量数据如何解释与迁移

**核心难点：存量列不是一种口径，而是两种。**

| 类别 | 列 | 实际语义 | 迁移公式（→ UTC 字面量） |
|---|---|---|---|
| **甲：+08:00 墙钟** | `ord_reservation.start_at`、`ord_reservation.end_at` | 由 `toBusinessLocal` 写入（`ReservationApplicationService.java:719-721`），已由 `V19__ord_reservation_time_comment.sql:11-12` 的注释确认 | `DATE_SUB(start_at, INTERVAL 8 HOUR)` —— 因为来源固定是 +08:00，**不需要 `CONVERT_TZ`，不依赖 MySQL 时区表** |
| **乙：JVM 默认时区墙钟** | `ord_order.created_at/updated_at/completed_at/cancelled_at`、`ord_ktv_session.opened_at/closed_at/billing_start_at/pause_started_at`、`ord_ktv_server_session.ordered_at/started_at/ended_at`、`res_occupation.*`、`res_schedule.*`、`pay_shift.opened_at/closed_at`、`pay_intent.expires_at/created_at/updated_at`、`pay_transaction.occurred_at`、`pay_refund.*_at`、`pay_daily_closing.created_at/updated_at` | `LocalDateTime.now()`，值取决于**容器 JVM 默认时区** | 若探针（§1.4）确认 JVM = UTC：**无需换算**（`created_at` 本身已是 UTC 字面量，只是没人声明过）。若 JVM = `Asia/Shanghai`：`DATE_SUB(col, INTERVAL 8 HOUR)`。若 JVM 其他时区：按该时区固定偏移换算 |
| **丙：UTC 墙钟** | `im` 库的 `SecretGroupChat` 过期时间（`SecretGroupChatApplicationService.java:163`） | 已是 UTC | 无需换算 |

**迁移步骤（务必按序）**

1. **探测（P0，必做）**：执行 §1.4 的两条命令，确定 JVM 默认时区；把结论写入迁移脚本头部的注释（**留证**）。同时对甲/乙两类各抽样若干行，与业务方确认时间点（例如「某笔订单的纸质小票时间」）。
2. **冻结写入窗口**：批 3 回填期间把相关写路径置于只读或暂停（预约创建 / 开台 / 结台 / 开班 / 交班 / 支付回调）。若不能停服，则采用「先双写、再按主键区间分批回填、最后校验差值为 0」的方式，并用 `updated_at` 水位线做增量补迁。
3. **回填**：`UPDATE ... SET col_utc = <公式> WHERE col_utc IS NULL`；**分批（例如每 5 万行）** + `LIMIT`，避免大事务与主从延迟。
4. **校验（必须，且要产出可留档的报告）**：
   - 行数校验：`col_utc IS NULL` 的行数 = 0；
   - 语义校验：`SELECT COUNT(*) FROM t WHERE ABS(TIMESTAMPDIFF(SECOND, col, DATE_ADD(col_utc, INTERVAL 8 HOUR))) > 0`（甲类应为 0）；
   - 交叉校验：同门店内 `ord_order.created_at` 与 `ord_reservation.created_at`（订单由预约开台产生，见 `ReservationApplicationService.java:299-322`）在换算后应处于同一坐标系（差值应在分钟级而非 ±8 小时）。
5. **不可逆点**：删旧列（批 5）视为不可逆。删之前保留一份 `*_legacy_backup` 表或逻辑快照，保留期建议 ≥ 1 个营业月。

**关于 `business_date` 的回填**

- 依赖 D1 完成后的 `tnt_store.timezone`/`business_day_cutoff`。回填公式见 §3.2（在应用层脚本里算，不在 SQL 里算）。
- 回填后做一次「与历史日结记录对比」：既有 `pay_daily_closing.business_date` 是人工/前端填的，与回填值不一致的行**必须人工确认**（这批行正是 §1.2 场景 4 的受害者）。产出差异清单，不要静默覆盖。

### 5.2 新旧客户端混跑：具体策略（不是「注意兼容」）

| 维度 | 策略 |
|---|---|
| **1. 出参兼容（服务端向后兼容）** | `utc` 模式下，响应**同时**返回：`startAt`（带偏移 RFC3339，新语义）、`startAtLegacy`（无偏移门店墙钟串，旧语义，与改造前完全一致）、`storeTimezone`、`businessDate`。旧客户端只读 `startAtLegacy`，新客户端只读 `startAt` + `storeTimezone`。**字段只增不改**，旧客户端无需同步发布 |
| **2. 入参兼容（服务端向前兼容）** | ① 带偏移的 `startAt` → 直接使用（新客户端）；② **无偏移**的 `startAt` → 视为「门店本地墙上时间」，用请求上下文的 `storeId` 对应门店时区解释（旧客户端）。**不允许**把它当 UTC 解释（那正是 8 小时偏移的来源）。③ 两者都记一条 `timezone.input.legacy` 的 INFO 日志 + 按租户计数，用于量化旧客户端退场进度 |
| **3. 强制拒绝的边界** | 若请求**既无偏移、又无法确定门店**（`storeId` 为空/门店无时区）→ **400 `TIME_INPUT_AMBIGUOUS`**。宁可报错，不做猜测 |
| **4. 双协议共存期** | `app.timezone.model` 只影响**内部读模型**（读旧列还是 UTC 列）；**对外契约永远双字段返回**（策略 1）。因此「客户端版本」与「服务端读模型」是两个正交的开关，避免发布耦合 |
| **5. 旧 C 端兼容读路径** | `ReservationCompatController.java:53-54` 的 `reserveDate`/`reserveTimePeriod` 必须**保持旧语义输出**（门店本地日期/时间），从 UTC 反算得到。旧 C 端不感知任何变化 |
| **6. Flutter App / 桌面端** | 本次调研范围外（`gv_chat_app`/`gv_chat_desktop`），但它们若调用同一批接口，按策略 1/2 自然兼容。**上线前必须用 `grep` 确认这两端没有任何自带时区换算**（`gv_chat_app/lib/**` 与 `gv_chat_desktop/src/**`），有则纳入批 4 |
| **7. 灰度与快速止损** | 按 `tenant_id` 白名单灰度；每个灰度租户上线后 24 小时内比对：①新老两套出参在**同一时刻**应指向同一 `Instant`（可写成自动化断言）；②该租户的日结与报表总额与上一营业日同量级（无 8 小时级断崖）。异常即把租户从白名单摘除，**不需要回滚代码** |
| **8. 回滚** | 服务端：flag 切 `legacy`（旧列与双写都还在）；前端：后台与 H5 都是独立部署，前端回滚到上一版本即可（前端在 `legacy` 模式下读 `startAtLegacy`，因此**前端回滚与服务端 flag 不需要严格同步**——这是策略 1「双字段」换来的解耦） |

### 5.3 与其它改造的协同（避免互相踩）

- **币种改造**：`pay_shift`/`pay_daily_closing`/`ord_order` 都在币种改造的白名单里（`V10__pay_currency_snapshot.sql:26,30`、`V22__ord_currency_snapshot.sql`）。两个改造都会给同一批表加列、都会改 `CashierApplicationService`（`CashierApplicationService.java:44-55,73-93,125-133`）→ **必须约定迁移编号与文件，避免同号冲突**；建议**时间与币种在同一个发布窗口内合并**，否则 `pay_daily_closing` 会出现「按币种分行但按服务端日切日」的半成品。
- **预约房型改造**：`V23__ord_reservation_room_type.sql`、`ReservationApplicationService` 的 `roomTypeId` 链路已经落地。时间改造会重写同一个类的 `create`/`openTable`（S1/S2）→ **必须等房型改造合入 `develop/2.0.0-saas-20260826` 后再动**，否则大范围冲突。
- **审计改造**：S14 会改 `AuditTimeParser`，若审计侧同期在做「i18n/异常审计」改造（见 `docs/audit-2026-09-17-exceptions-and-i18n.md`），需协调。

---

## 6. 测试策略

### 6.1 单元测试（时区换算 / 边界）

| 编号 | 用例 | 期望 |
|---|---|---|
| TC-U1 | `StoreTimeService.toUtc(OffsetDateTime.parse("2026-09-20T19:00:00+08:00"))` | `2026-09-20T11:00:00` 的 UTC `LocalDateTime` |
| TC-U2 | `StoreTimeService.businessDate(utc=2026-09-20T19:30Z, zone=Asia/Shanghai, cutoff=04:00)` | 本地为 9/21 03:30，**小于切点 04:00 → 归前一营业日** → `2026-09-20` |
| TC-U3 | `businessDate(utc=2026-09-20T20:30Z, zone=Asia/Shanghai, cutoff=04:00)` | 本地 9/21 04:30 ≥ 04:00 → `2026-09-21` |
| TC-U4 | 切点恰好等于本地时间（本地 04:00:00） | 归**当天**（`>= cutoff`，右开） |
| TC-U5 | `zone=America/New_York`，UTC `2026-03-08T07:30Z`（DST 生效瞬间 03:30 EDT） | 换算为 `2026-03-08T03:30-04:00`，**不出现 02:30** |
| TC-U6 | `zone=America/New_York`，UTC `2026-11-01T05:30Z` 与 `06:30Z`（回拨当天 01:30 出现两次） | 两个 UTC 时刻都能唯一换算；`businessDate` 均为 `2026-11-01`；**不得**因为墙上时间重复而算成同一时刻 |
| TC-U7 | `KtvRoomFeeCalculator.standardSeconds` 在 `reservedEndAt` 与 `billingStartAt` 同为 UTC 时的结果 | 等于预约窗口秒数（修复 §1.2 场景 2 的断言） |
| TC-U8 | `KtvRoomFeeCalculator.billableSeconds` 传入负区间 | 返回 0（保护不变，`KtvRoomFeeCalculator.java:63`） |
| TC-U9 | 预约重叠判定：上一场 `end=11:00Z`、下一场 `start=11:00Z` | **不重叠**（左闭右开，与 `ReservationApplicationService.java:443-444` 一致） |
| TC-U10 | 时区字符串校验：`ZoneId.of("+08:00")` | **拒绝**（禁止偏移字面量）；`ZoneId.of("Asia/Bangkok")` → 通过 |
| TC-U11 | `formatStoreTime`（后台）/ `fmtTime`（B 端）/ `formatStoreDateTime`（C 端）对同一 `"2026-09-20T11:00:00Z"` + `Asia/Shanghai` | 三端都输出 `2026-09-20 19:00`（C 端为 `9月20日 19:00`） |
| TC-U12 | 三端对**无偏移**串 `"2026-09-20T19:00"` + `Asia/Shanghai` | 三端都输出 `19:00`（按门店时区解释，**不是**按设备时区） |

### 6.2 集成测试（跨营业日 / DST / 跨时区并发）

| 编号 | 用例 | 期望 |
|---|---|---|
| TC-I1 | 门店 `Asia/Bangkok`（+07），C 端提交「9/20 19:30 + 3h」 | `ord_reservation.start_at`（UTC）= `2026-09-20T12:30:00`；`end_at` = `2026-09-20T15:30:00`；`business_date` = `2026-09-20`；`store_timezone` = `Asia/Bangkok`；出参 `startAt` = `2026-09-20T19:30:00+07:00` |
| TC-I2 | 门店 `Asia/Shanghai`、切点 `04:00`，预约 9/20 23:00–9/21 02:00 | `business_date` = `2026-09-20`；按 `businessDate=2026-09-20` 筛选能查到；按 `2026-09-21` 查不到 |
| TC-I3 | 同上，预约 9/21 03:30–05:00 | `business_date` = `2026-09-20`（**跨切点，归前一营业日**）|
| TC-I4 | 同上，预约 9/21 04:30–06:00 | `business_date` = `2026-09-21` |
| TC-I5 | 跨时区门店并发预约：`Asia/Shanghai` 门店与 `Asia/Bangkok` 门店各下 1 单，时段在同一 UTC 窗口 | 两单都成功（门店隔离，不互相超订）；各自的 `business_date` 按各自门店时区算 |
| TC-I6 | **同一门店**并发预约同一房型最后 1 间（时段重叠） | 一单成功、一单 409 `ROOM_TYPE_FULL`（超订保护不因时区改造失效） |
| TC-I7 | 开台：预约 9/20 19:00–22:00 的 `ARRIVED` 预约 → `open-table` | `ord_ktv_session.reserved_start_at/reserved_end_at` 被写入且为 UTC；`billing_start_at` 与 `opened_at` 同为 UTC；`standardSeconds` = 10800 秒（3 小时），**不再回退 120 分钟** |
| TC-I8 | 开班 → 3 笔现金收款 → 交班（跨营业日：23:30 开班，次日 05:00 交班） | `pay_shift.business_date` = 开班日营业日；`expected_cash` = `opening_cash` + 3 笔之和；`pay_daily_closing` 该营业日一行 |
| TC-I9 | 日结：前端传入的 `businessDate` 与服务端计算不一致 | 409 `BUSINESS_DATE_MISMATCH`，且**不落库** |
| TC-I10 | 报表：门店 `S2`（`Asia/Bangkok` +07，切点 `02:00`），查询 `from=2026-09-20&to=2026-09-20` | 只包含 S2 营业日 9/20 的数据，等价 UTC 窗口 `[2026-09-19T19:00Z, 2026-09-20T19:00Z)`；行数/金额与按营业日写入的 `business_date` 完全一致 |
| TC-I11 | 报表：`storeId` 为空且不传 `from`/`to` | 400 `TIME_RANGE_REQUIRED` |
| TC-I12 | DST 边界集成：`America/New_York` 门店在 2026-03-08 02:00–03:00（不存在的墙上时间）创建预约 | 按 §2.3 的入参形态①（带偏移）→ 该墙上时间**无法**对应合法偏移，返回 400 `TIME_INVALID_LOCAL_TIME`；形态②（本地时间+时区）由服务端做 DST 处理：不存在的时间向前推 1 小时（或按业务约定拒绝，**需在实现前拍板，建议拒绝并给出明确错误码**） |
| TC-I13 | 占用过期释放：`hold_expires_at` 与 `now` 同为 UTC | `HoldExpiryScheduler` 扫描结果与改造前一致（按秒级比对） |
| TC-I14 | 内部占用跨服务：order 传带偏移的 `startAt` | resource 侧 `res_occupation.start_at` 存 UTC，且与 order 侧值一致（±1 秒） |
| TC-I15 | 双写一致性：批 2 之后随机 1000 行 | `col_utc == 换算(col)` 全部成立 |

### 6.3 端到端验收脚本要点

1. **前置**：两个门店 —— `S1`（`Asia/Shanghai`，切点 `04:00`）、`S2`（`Asia/Bangkok`，切点 `02:00`）；各 1 个房型、≥2 间启用包厢；1 个收银员、1 个店长。
2. **脚本必须覆盖**（可扩展现有 `test(reservation)` 验收脚本，见 `git log` 的 `9ef94ce8`）：
   - 在**同一台机器**上分别以 `S1`/`S2` 上下文创建预约，断言两端后台展示的**当地钟点**分别等于输入；
   - 把运行脚本的机器时区改成 `America/Los_Angeles` 重跑一遍，断言**展示结果不变**（这是「前端不按设备时区渲染」的判据）；
   - 开台 → 结台 → 检查账单：`reserved_end_at` 参与超时判定；
   - 开班 → 收款 → 交班 → 日结：检查 `business_date` 归属；
   - 查三张报表：`businessDate` 分组与日结一致；
   - 跨 DST 日（用可控 `Clock` 或本地起一段跨越 `2026-11-01T05:30Z` 的会话）验证不出现 1 小时重复/丢失。
3. **必须留档**：每一步的请求/响应原文（含偏移串）+ 数据库落库值 + 三端截图，作为 §7 验收的证据。

### 6.4 可直接照做的测试用例清单（含期望结果）

| ID | 输入 | 期望输出 |
|---|---|---|
| TC-D1 | `S2`(+07) C 端提交 `9/20 19:30 + 3h` | 落库 `12:30Z`/`15:30Z`；出参 `2026-09-20T19:30:00+07:00`；两端展示 `19:30` |
| TC-D2 | `S1`(+08) 预约 `9/20 23:30 - 9/21 01:30`，切点 `04:00` | `business_date=2026-09-20` |
| TC-D3 | `America/New_York` 门店，DST 回拨日 `2026-11-01`：两笔预约分别是本地 `01:30 EDT` 与 `01:30 EST` | 两个不同的 UTC 时刻（`05:30Z` / `06:30Z`）；落库不同；**不覆盖、不去重** |
| TC-D4 | `America/New_York` 门店，DST 春进日 `2026-03-08`：本地 `02:30`（不存在） | 400，错误码 `TIME_INVALID_LOCAL_TIME`，消息说明该本地时间不存在 |
| TC-D5 | 服务器 JVM 时区为 UTC，门店 `Asia/Shanghai`，开台 3 小时后结台 | 账单时长 = 3 小时，房费 = 3/计费单位 × 单价；**不等于 0**（回归 §1.2 场景 2 的错账） |
| TC-D6 | 日结：前端传 `2026-09-20`，服务端算出 `2026-09-20` | 201 成功，`pay_daily_closing` 一行 |
| TC-D7 | 日结：前端传 `2026-09-19`，服务端算出 `2026-09-20` | 409 `BUSINESS_DATE_MISMATCH`，0 行写入 |
| TC-D8 | 报表 `from=to=2026-09-20`，门店 `S2` | 只含 S2 营业日 9/20 的行；`businessDate` 全部为 `2026-09-20` |
| TC-D9 | 报表 `storeId` 为空且缺 `from`/`to` | 400 `TIME_RANGE_REQUIRED` |
| TC-D10 | 无偏移入参 `"2026-09-20T19:00:00"`（旧客户端）+ 明确的 `storeId`（`Asia/Bangkok`） | 按 `Asia/Bangkok` 解释 → 落库 `12:00Z`；返回含 `startAtLegacy="2026-09-20T19:00:00"` |
| TC-D11 | 无偏移入参且无 `storeId` | 400 `TIME_INPUT_AMBIGUOUS` |
| TC-D12 | 带偏移入参 `"2026-09-20T11:00:00Z"`，门店 `Asia/Shanghai` | 落库 `11:00Z`；出参 `2026-09-20T19:00:00+08:00`；`startAtLegacy="2026-09-20T19:00:00"` |
| TC-D13 | 三端渲染 `"2026-09-20T11:00:00Z"` + `Asia/Shanghai`，运行设备时区设为 `America/Los_Angeles` | 三端一致显示 `2026-09-20 19:00`（C 端 `9月20日 19:00`） |
| TC-D14 | 同 TC-D1，但把设备时区改为 `UTC` 重跑 | 展示结果与 TC-D13 一致（设备时区不影响门店时间展示） |
| TC-D15 | 开班跨营业日（`23:30`→次日 `05:00`），切点 `04:00` | 班次 `business_date` = 开班日；日结 `expected_cash` = 开班现金 + 班内 ≥ 两自然日的现金收款之和 |

---

## 7. 验收标准

> 全部为可机器验证的条目；每条给出验证方式。**未全部通过不得进入批 5**。

**A. 口径一致性**

- [ ] A1：`grep -rn "ZoneOffset.ofHours(8)" --include=*.java` 在 `gv_im_server` 生产代码中**为 0 命中**（测试里允许保留历史断言）。
- [ ] A2：`grep -rn "STORE_OFFSET_MINUTES" gv_saas_mobile/c-end`（排除测试）**为 0 命中**。
- [ ] A3：所有 `@RequestParam`/`@RequestBody` 中的时间入参类型为 `OffsetDateTime`（或校验带偏移的字符串），`LocalDateTime` 入参在对外/内部 HTTP 接口中**为 0 命中**。
- [ ] A4：所有服务的 JDBC URL `serverTimezone` 均为 `UTC`（当前 5 处为 `Asia/Shanghai`，见 §1.1(6)）；所有 deployment 注入 `TZ=UTC`。
- [ ] A5：`grep -rn "DATE_FORMAT(.*created_at\|DATE_FORMAT(.*occurred_at\|DATE_FORMAT(.*start_at" --include=*.java` 在报表 Mapper 中**为 0 命中**（改按 `business_date` 分组）。

**B. 跨时区门店**

- [ ] B1：跨时区门店（`Asia/Shanghai` 与 `Asia/Bangkok`）预约 `19:00`，在后台 B 端、B 端 App、C 端 H5 **三端显示一致**，且当地钟点与门店一致（TC-D13/D14）。
- [ ] B2：同一预约在**设备时区改为 `America/Los_Angeles`** 后，三端显示不变（TC-D14）。
- [ ] B3：跨时区门店各自的 `business_date` 按各自门店时区计算，互不串扰（TC-I5）。

**C. DST**

- [ ] C1：DST 切换日**不出现 1 小时重复/丢失**：本地 `01:30 EDT` 与 `01:30 EST` 落库为两个不同 UTC 时刻（TC-D3）。
- [ ] C2：DST 春进日的「不存在本地时间」被明确拒绝，错误码 `TIME_INVALID_LOCAL_TIME`（TC-D4）。
- [ ] C3：DST 切换日的营业日归属正确（不因 25 小时/23 小时自然日而错位）。

**D. 营业日与对账**

- [ ] D1：跨零点营业（`23:00–02:00`）与通宵场次归属到**开场营业日**，且日结与报表的 `businessDate` 完全一致（TC-D2/D6/D8）。
- [ ] D2：日结的 `business_date` 由服务端计算；与前端传入不一致时 409 且不落库（TC-D7）；前端默认值来自服务端（`shift.vue:164,169-173` 的设备日期逻辑已删除）。
- [ ] D3：`pay_daily_closing` 的唯一键 `(tenant_id, store_id, business_date)` 在改造后仍能挡住重复日结（`V1__pay_baseline.sql:53` 不变）。
- [ ] D4：一个营业日的「日结总额」与三张报表（经营/支付/资源）该营业日的金额口径一致（差额为 0 或已在差异清单中人工说明）。

**E. 计费正确性（回归）**

- [ ] E1：预约窗口参与超时判定：`ord_ktv_session.reserved_end_at` 非空，`standardSeconds` = 预约窗口秒数（TC-I7）。
- [ ] E2：开台 3 小时后结台，房费 ≠ 0，且等于改造前的期望值（TC-D5）。
- [ ] E3：超订保护（`ROOM_TYPE_FULL`）在时区改造后行为不变（TC-I6）。

**F. 兼容与迁移**

- [ ] F1：`utc` 模式下响应**同时**含 `startAt`（带偏移）与 `startAtLegacy`（无偏移旧语义），旧客户端可继续工作（§5.2 策略 1）。
- [ ] F2：无偏移入参 + 明确门店 → 按门店时区解释；无偏移 + 无门店 → 400 `TIME_INPUT_AMBIGUOUS`（TC-D10/D11）。
- [ ] F3：存量回填后校验全绿：`col_utc IS NULL` 计数为 0；甲类换算差值全为 0（§5.1 步骤 4）。
- [ ] F4：`business_date` 回填差异清单已产出且已人工确认，无静默覆盖（§5.1）。
- [ ] F5：灰度期间可**不改代码**把租户摘出白名单，且旧列数据仍完整（§4.6 双轨）。

**G. 可观测性**

- [ ] G1：`timezone.input.legacy` 计数按租户可查（量化旧客户端退场进度）。
- [ ] G2：门店时区为空的写请求产生 `STORE_TIMEZONE_MISSING` 告警，读请求产生降级 `warn`（§2.1 决策点 2）。

---

## 8. 工作量与分批建议

### 8.1 工作量估算（人日，按 1 名后端 + 1 名前端并行）

| 工作包 | 内容 | 规模 | 估算 | 依赖 |
|---|---|---|---|---|
| **P1 地基** | D1、S16、S17、S19、S20、G3、§1.4 探针 | **中** | 4~6 | 无。**可与其它改造并行，必须先做** |
| **P2 存储与迁移** | D2/D3/D4、D5、§5.1 全流程、校验脚本 | **大** | 6~9 | P1 |
| **P3 写路径改造** | S1、S2、S3、S6、S11、S14、S15、S18（双写 + UTC 时钟） | **大** | 6~8 | P2 |
| **P4 读路径与契约** | S4、S5、S7、S8、S9、S10、S12、S13、G1、G2 | **中** | 5~7 | P3 |
| **P5 前端三端** | F1~F13 | **中** | 5~7 | P4（契约冻结后并行） |
| **P6 测试与验收** | §6 全部用例 + §7 全部条目 + 端到端脚本 | **大** | 6~8 | 贯穿 |
| **P7 灰度与收口** | 白名单灰度、双写停用、旧列清理、文档收口 | **中** | 3~4 | P5、P6 |
| **合计** | | | **35~49 人日**（约 7~10 周，含 1 名后端 + 1 名前端） | |

> 若采用**决策点 1 的选项 B**（只把硬编码 +08 换成 `store.timezone`），P2/P3 可压缩到 4~6 人日，但 §7 的 B3/C1/C2/D1/D4/E1 无法验收 —— 也就是说**选项 B 无法通过本方案的验收标准**。这是选项 A/B 的真正分水岭。

### 8.2 建议的实施顺序

```
P1 地基（4~6d）  →  P2 存储与迁移（6~9d）  →  P3 写路径（6~8d）
                                                   ↓
                        P6 测试（贯穿）  ←  P4 读路径与契约（5~7d）  →  P5 前端（5~7d）
                                                   ↓
                                              P7 灰度与收口（3~4d）
```

- **P1 必须最先**：没有门店时区与统一 UTC 钉住，后面的换算没有输入，且批 1 的行为变化要靠低峰发布来兜。
- **P2/P3 之间不要发布**：影子列建好但写路径没双写，会出现「新列空、旧列活」的中间态，报表容易读错。建议 **P2+P3 合并为一个发布窗口**。
- **P4 与 P5 可以并行**：P4 冻结契约（`storeTimezone`/`businessDate`/`startAtLegacy` 字段名与格式）后，前端即可并行开发。
- **P1 一旦完成，B3/D2 两条验收即可先跑通一半**，能给决策者早期信号。

### 8.3 必须与其它改造协同发布的部分

| 协同对象 | 必须同批的原因 |
|---|---|
| **币种改造** | ①两者都改 `CashierApplicationService.java:44-55,73-93,125-133` 与 `pay_shift`/`pay_daily_closing`（币种已加 `currency_code`：`V10__pay_currency_snapshot.sql:26,30`），分开做会两次改同一段代码；②`pay_daily_closing` 的日结语义是「一个门店一个营业日**一个币种**一行」（`CashierApplicationService.java:128-129`），若时间口径未定，币种分行会落在错误的「日」上；③报表 `opsKey` 已是 `store_id\|business_date\|currency_code`（`ReportController.java:192-195`），两个维度必须同时正确 |
| **预约房型改造** | 时间改造会重写 `ReservationApplicationService.create/openTable`（S1/S2）与 `ReservationController`（S4），房型改造（`V23__ord_reservation_room_type.sql`）已经占用了这些文件与 `V23` 编号；**必须等房型改造合入后**再开始 P3 |
| **审计改造**（`docs/audit-2026-09-17-exceptions-and-i18n.md`） | S14 改 `AuditTimeParser.java:16-67` 的时区归一；S15 改 `AdminProjectionQueryAdapter.java:95,219,223`。若审计侧同期在做，需同一人处理或明确先后 |
| **不必须协同** | 网关、IM 消息/会话域（除 S18 一行一致性改动）、媒体服务（J3 只是时钟统一） |

---

## 附录 A：证据索引（文件:行号）

> 相对路径以 `gv_im_server/` 为根（另有标注的三端前端以 `D:\projects\cnb\` 为根）。

**A.1 时间口径与写路径**

| 证据 | 位置 |
|---|---|
| 预约「统一转北京时间落库」注释 | `platform-services/order/platform-order-service/src/main/java/io/openware/platform/order/application/ReservationApplicationService.java:92-97` |
| `toBusinessLocal` 硬编码 +08 | `.../ReservationApplicationService.java:716-721` |
| 预约写 start/end | `.../ReservationApplicationService.java:132-133` |
| 超订重叠判定（左闭右开） | `.../ReservationApplicationService.java:437-461`（尤其 `:443-444,447-448,454-455`） |
| `create` 的 `LocalDateTime.now()` | `.../ReservationApplicationService.java:119` |
| `openTable` 用 `LocalDateTime.now()` 并创建会话 | `.../ReservationApplicationService.java:299-336` |
| 预约出参口径文档化 | `.../api/controller/ReservationController.java:32-38,68,174-175` |
| 预约列表无 `fromAt/toAt` | `.../api/controller/ReservationController.java:52-57` |
| V19 列注释纠正为 +08 墙钟 | `.../src/main/resources/db/migration/V19__ord_reservation_time_comment.sql:1-12` |
| V2 原注释写 UTC | `.../db/migration/V2__ord_reservation.sql:12-13` |
| 单测固化 +08 口径 | `.../src/test/java/io/openware/platform/order/application/ReservationApplicationServiceTest.java:56-57,114` |
| Web 层单测用 +08 串 | `.../src/test/java/io/openware/platform/order/api/controller/ReservationControllerWebTest.java:73,91,111-112` |

**A.2 会话计时与计费**

| 证据 | 位置 |
|---|---|
| 会话创建/开台/暂停/恢复/结台全部 `LocalDateTime.now()` | `.../application/KtvSessionApplicationService.java:94,141,155,159,207,222,501-502` |
| 开台即占用并传 `now, now.plusHours(24)` | `.../KtvSessionApplicationService.java:145-151` |
| `ord_ktv_session` 建表（全部时间列为 `datetime(3)`） | `.../db/migration/V1__ord_order_baseline.sql:34-48` |
| 计费时长钳零 | `.../domain/ktv/service/KtvRoomFeeCalculator.java:58-64` |
| 标准时长回退 `default_session_minutes` | `.../domain/ktv/service/KtvRoomFeeCalculator.java:66-72` |
| 计费公式 | `.../KtvRoomFeeCalculator.java:74-92` |
| `reserved_start_at/end_at` 仅测试赋值 | `.../src/test/java/io/openware/platform/order/application/OrderIntegrationTest.java:274-275` |
| 规范要求 UTC 落库/门店时区展示 | `docs/renovation/KTV_BUSINESS_01_SERVICE.md:148`；字段语义 `:143-144` |

**A.3 收银 / 交班 / 日结 / 对账**

| 证据 | 位置 |
|---|---|
| 开班/交班 `LocalDateTime.now()` | `common-services/payment/common-payment-service/src/main/java/io/openware/common/payment/application/CashierApplicationService.java:44-55,73-93` |
| 班次现金汇总时间窗 | `.../CashierApplicationService.java:114-122`；`.../infra/persistence/mapper/PayIntentMapper.java:28` |
| 日结落库不校验营业日 | `.../CashierApplicationService.java:125-133` |
| 日结请求体（businessDate 由调用方给） | `.../api/controller/CashierController.java:53,94` |
| `pay_shift` / `pay_daily_closing` 建表与唯一键 | `.../src/main/resources/db/migration/V1__pay_baseline.sql:34-44,46-54`（唯一键 `:53`） |
| 币种改造已改这两张表 | `.../db/migration/V10__pay_currency_snapshot.sql:26,30` |
| 支付时间写入 | `.../application/CollectApplicationService.java:464` |
| 对账窗口 `[from,to)` | `.../application/ReconciliationApplicationService.java:34-38`；`.../api/controller/ReconciliationController.java:19-21` |
| 前端日结默认取设备日期 | `gv_saas_admin/src/views/tenant/shift.vue:164,169-173,254-266` |

**A.4 报表**

| 证据 | 位置 |
|---|---|
| 报表四个端点签名与 `Instant.now()` 的 `dataAsOf` | `platform-services/admin/platform-admin-service/src/main/java/io/openware/platform/admin/api/controller/ReportController.java:40-44,72,76-80,110,114-118,144,148-152,164` |
| `Range.of` 用 `LocalDate.now()`（JVM 日） | `.../ReportController.java:335-347`（`:337,345`） |
| `opsKey` 以 `business_date` 为分组键 | `.../ReportController.java:192-195` |
| SQL 用 `DATE_FORMAT` 切日 | `.../infra/persistence/mapper/ReportMapper.java:93,105,113,124,132,143,153,164` |
| 窗口 `[from,to)` | `.../ReportMapper.java:32,48,64,83,103,122,141,162` |
| 前端报表展示 businessDate | `gv_saas_admin/src/views/tenant/reports.vue:64,75,95` |

**A.5 时区字段与门店**

| 证据 | 位置 |
|---|---|
| `tnt_tenant.default_timezone` | `platform-services/tenant/.../src/main/resources/db/migration/V1__tnt_iam_baseline.sql:8` |
| `tnt_store.timezone` / `business_day_cutoff` | `.../V1__tnt_iam_baseline.sql:28,30` |
| PO 映射 | `.../infra/persistence/po/StorePo.java:26,30`；`.../po/TenantPo.java:20` |
| 门店只读接口 | `.../api/controller/StoreController.java:25-33` |
| 租户创建可写 defaultTimezone | `.../api/controller/TenantController.java:41,61` |
| 种子时区 | `.../db/migration/V7__seed_a380_tenant.sql:4,12`；`V3__seed_default_init.sql:2,10` |
| 后台门店列表展示时区 | `gv_saas_admin/src/views/tenant/stores.vue:36` |
| 规范要求门店配置时区/营业日切点 | `docs/renovation/SAAS_PLATFORM_01_SERVICE.md:61,125,298`；`SAAS_PLATFORM_04_DATA.md:49,58` |

**A.6 资源 / 占用 / 内部调用**

| 证据 | 位置 |
|---|---|
| 占用建表 | `platform-services/resource/.../src/main/resources/db/migration/V1__res_resource_baseline.sql:19-39` |
| 占用重叠判定 | `.../application/OccupationApplicationService.java:45-70`（`:51`），过期释放 `:111,146-164` |
| 内部占用端点用 `LocalDateTime` | `.../api/controller/InternalResourceController.java:85-86,123-124` |
| 跨服务传无偏移字面量 | `platform-services/order/.../infra/client/ResourceStateClient.java:216-257`（`:237-241`） |
| 失败关闭口径（既有习惯） | `.../ResourceStateClient.java:20-30,83-118`；`ReservationApplicationService.java:61-62` |

**A.7 审计 / IM 侧时区**

| 证据 | 位置 |
|---|---|
| 审计按 `systemDefault` 归一 | `common-services/audit/.../application/support/AuditTimeParser.java:16,33,36,62,67` |
| IM 后台「今日」用 `LocalDateTime.now()` + `systemDefault` | `im-services/admin/.../infra/persistence/projection/AdminProjectionQueryAdapter.java:95,219,223` |
| 唯一显式 UTC 的写入点 | `im-services/conversation/.../application/secretgroupchat/SecretGroupChatApplicationService.java:163-164` |
| 内部签名时间戳要求 UTC epoch 毫秒 | `docs/renovation/PLATFORM_SECURITY_01_GOVERNANCE.md:164` |

**A.8 契约与规范**

| 证据 | 位置 |
|---|---|
| 时间为 RFC3339 UTC | `docs/renovation/SAAS_PLATFORM_05_API.md:50` |
| 预约列表 `fromAt/toAt`；订单列表 `fromAt/toAt` | `docs/renovation/SAAS_PLATFORM_05_API.md:128,144` |
| 「转换为 UTC 后判断冲突」 | `docs/renovation/SAAS_PLATFORM_05_API.md:135` |
| `DATETIME(3)` UTC 存储 | `docs/renovation/SAAS_PLATFORM_04_DATA.md:13` |
| 旧预约迁移遗留「待门店时区规则」 | `docs/renovation/RESERVATION_E_MIG_01_MIGRATION.md:32` |
| 兼容层把 start_at 直接切日期/时间 | `platform-services/order/.../api/controller/ReservationCompatController.java:48-61`（`:53-54`） |

**A.9 基础设施与前端**

| 证据 | 位置 |
|---|---|
| Dockerfile 无 TZ/JVM 时区 | `gv_im_server/Dockerfile:20` |
| k8s 无 TZ 注入 | `gv_im_server/k8s/local/saas.yaml:95-113,131-137,157-162,180-182` |
| JDBC `serverTimezone=UTC` | `platform-services/order/.../src/main/resources/application.yml:5`（各服务另见 §1.1(6)） |
| 后台 `formatTime` 字面量切片 | `gv_saas_admin/src/utils/format.js:27-31,43-47,54-60` |
| 后台守卫「formatTime 只能在 format.js 定义」 | `gv_saas_admin/src/utils/admin-copy.test.js:77-95` |
| 后台订单页时长计算 | `gv_saas_admin/src/views/tenant/orders.vue:645,812-813,877,912-921` |
| 后台预约页展示 | `gv_saas_admin/src/views/tenant/reservations.vue:33,80` |
| C 端硬编码门店偏移 | `gv_saas_mobile/c-end/datetime.js:16-17,49-61,63-80,82-88` |
| C 端单测固化 +08 行为 | `gv_saas_mobile/c-end/datetime.test.js:38-64` |
| C 端提交预约构造时间 | `gv_saas_mobile/c-end/app.js:330-335,1196-1202`；展示 `:780,892` |
| B 端 App `fmtTime` | `gv_saas_mobile/src/shared/utils/amount.js:32-35`；调用点 `gv_saas_mobile/src/b-end/views/Reservations.vue:31,58`；预览数据 `src/b-end/preview/saas.js:9,19,29,39` |
| 迁移编号现状 | order `V23__ord_reservation_room_type.sql`、tenant `V26__seed_currency_permission.sql`、payment `V10__pay_currency_snapshot.sql`、resource `V7__res_public_media_url_prefix.sql` |

---

## 附录 B：术语表

| 术语 | 定义 |
|---|---|
| **墙上时间（wall-clock time）** | 只记录「钟面读数」而不带时区/偏移的时间值（MySQL `DATETIME`、Java `LocalDateTime`）。同一串数字在不同时区代表不同绝对时刻 |
| **绝对时刻（instant）** | 带时区/偏移的确定时间点（Java `Instant`/`OffsetDateTime`，RFC3339 串） |
| **门店时区（store timezone）** | 门店所在 IANA 时区，取 `tnt_store.timezone`。本方案的**唯一权威时区来源** |
| **营业日（business date）** | 门店视角的「第几个营业日」。由「绝对时刻 → 门店时区 → 与 `business_day_cutoff` 比较」三步得出（§3.2）。**不等于自然日** |
| **营业日切点（business day cutoff）** | 营业日与自然日错位的分界时刻（默认 `04:00`）。取 `tnt_store.business_day_cutoff` |
| **双轨 / 双写（dual-track / dual-write）** | 迁移期旧列与新 UTC 列并存、写入两侧、读取由 feature flag 决定（§4.6、§5.2） |
| **左闭右开（`[fromAt, toAt)`）** | 时间区间语义：含起点、不含终点。本项目全部时间窗查询统一采用此语义 |
| **DST（夏令时）** | 部分时区每年两次调整偏移（春进 / 秋回）。秋回当日某些墙上时间出现两次，春进当日某些墙上时间不存在 —— 这是「存墙上时间」方案无法解决的根因 |
| **fail-closed** | 依赖数据缺失时拒绝服务而不是降级放行。本项目在多处采用（`ResourceStateClient.java:20-30`、`ReservationApplicationService.java:61-62`） |

---

## 9. 实施记录（批 1 地基）

> 本节由**批 1 实施提交**追加，记录实际做了什么、探针实测值、采用的回填公式与迁移编号。
> 上方 §0–§8 与附录原文**一字未改**；本节只追加事实，不覆盖设计。
>
> 批次边界：本次只做 §4.6 / §8.1 的 **P1 地基**（§1.4 探针、D1、S16、S19、S20、门店契约字段核对）；
> **P2/P3（UTC 影子列 + 写路径双写 + 存量时间回填）明确不在本批**，见 §9.6。

### 9.1 §1.4 前置探针：实测原始输出与结论

执行时间：2026-09-18 08:07–08:12（宿主机 +08:00，容器 UTC）。集群：kind `gv-im-local`。以下为命令与原始输出。

```text
$ kubectl -n gv-im-local exec deploy/platform-tenant-service -- java -XshowSettings:properties -version 2>&1 |
    Select-String -Pattern "user.timezone|user.country|file.encoding"
    file.encoding = UTF-8
    java.version = 25.0.4
    user.country = US
    （没有 user.timezone 这一行 —— 属性不存在 = 未设置）

$ kubectl -n gv-im-local exec deploy/platform-tenant-service -- java -XshowSettings:properties -version 2>&1
    Property settings:
        file.encoding = UTF-8
        ...
        user.country = US
        user.dir = /app
        user.home = /root
        user.language = en
        user.name = root
    （属性清单里同样没有 user.timezone）
```

六个 Deployment 逐一的同样探针（命中行原样列出）：

| Deployment | `user.timezone` | `user.country` | `file.encoding` |
|---|---|---|---|
| `platform-tenant-service` | **无该行（未设置）** | `US` | `UTF-8` |
| `platform-order-service` | **无该行（未设置）** | `US` | `UTF-8` |
| `common-payment-service` | **无该行（未设置）** | `US` | `UTF-8` |
| `im-message-service` | **无该行（未设置）** | `US` | `UTF-8` |
| `platform-resource-service` | **无该行（未设置）** | `US` | `UTF-8` |
| `common-audit-service` | **无该行（未设置）** | `US` | `UTF-8` |

环境侧取证（同一批 Pod）：

```text
$ kubectl -n gv-im-local exec deploy/platform-tenant-service -- date
    Fri Sep 18 00:07:29 UTC 2026            ← 三个服务一致（tenant/order/payment）
$ kubectl -n gv-im-local exec deploy/platform-tenant-service -- sh -c 'ls -l /etc/localtime; echo "TZ=[$TZ]"'
    ls: cannot access '/etc/localtime': No such file or directory
    TZ=[]                                   ← 镜像与 k8s 清单都没有注入 TZ
$ kubectl -n gv-im-local exec deploy/platform-tenant-service -- sh -c 'ls /usr/share/zoneinfo | head -3; ls /opt/java/openjdk/lib/tzdb.dat'
    Africa / America / Antarctica           ← 镜像自带 tzdata
    /opt/java/openjdk/lib/tzdb.dat          ← JDK 自带 tzdb（java.time 不依赖 OS tzdata）
$ kubectl -n gv-im-local logs deploy/platform-tenant-service --tail=2
    2026-09-17T14:43:44.746Z  INFO ...      ← 应用日志墙钟与容器 UTC 一致（order/payment 同）
```

基础设施侧对照（与 §1.1(6) 一致，本次已改，见 §9.5）：`Dockerfile:22` 原 `ENTRYPOINT` 无 `-Duser.timezone`、
`k8s/local/saas.yaml` 与 `k8s/local/applications.yaml` 的 env 块无 `TZ`；`serverTimezone` 有 4 处为
`Asia/Shanghai`（media / user / conversation / im-admin），其余 15 处为 `UTC`。

**结论（决定存量回填公式的那个结论）：当前容器 JVM 默认时区 = `UTC`（GMT，偏移 +00:00）。**
证据链：① `user.timezone` 属性未设置（6/6 服务）；② `/etc/localtime` 不存在；③ 容器 `TZ` 为空；
④ `date` 输出 UTC；⑤ 应用日志墙钟与 UTC 一致。

由此得到的两条落地口径：

1. **存量「乙类」列不需要换算**（§5.1 类别乙）：`LocalDateTime.now()` 写入的列本来就是 UTC 字面量。
   因此批 1 **不写任何存量时间回填**，存量解释与改造前完全一致 —— 满足 §4.6「批 1 可独立上线」的前提。
2. **批 1 的 S20（钉 UTC）不改变当前运行时行为**：它把「镜像恰好没有 tzdata 因而默认 UTC」这个**环境巧合**
   变成显式契约（`TZ=UTC` + `-Duser.timezone=UTC`），扫掉的是「某次基础镜像升级让默认时区漂移」的风险。
   ⚠️ 该结论只对**当前 kind/ACK 的这套基础镜像**成立；换基础镜像或换运行时（例如 JVM 参数被覆盖）后必须重跑本探针。

### 9.2 迁移编号与回填公式

| 项 | 实际值 |
|---|---|
| 文件 | `platform-services/tenant/platform-tenant-service/src/main/resources/db/migration/V27__tnt_store_timezone_cutoff.sql` |
| 编号依据 | tenant 合并时刻最大编号为 **V26**（`V26__seed_currency_permission.sql`），取 **V27**；order V24 / payment V10 / resource V7 / audit V3 的编号**未被占用**（本批不碰这些模块） |
| 回填公式（时区） | `timezone = COALESCE(NULLIF(TRIM(store.timezone),''), NULLIF(TRIM(tenant.default_timezone),''), 'Asia/Shanghai')`（门店 → 租户 `default_timezone` → 平台默认，与 §2.1 一致） |
| 回填公式（切点） | `business_day_cutoff = '04:00:00' WHERE business_day_cutoff IS NULL` |
| 非空约束 | **两列都加 `NOT NULL`**，并各带平台默认值（`Asia/Shanghai` / `04:00:00`） |
| 业务时间列 | **一行未改**（仍 `datetime(3)`，未改 `timestamp`，未加影子列） |

**加 `NOT NULL` 的理由**（写进了迁移头部注释）：§4.1 D1 明确要求 `timezone` 加非空；§3.1 明确要求切点
「必须显式配置而不是留 NULL」（留 NULL 会让营业日退化为自然日）；两条 `UPDATE` 与 `ALTER` 在**同一个迁移**
内按序执行，历史行已全部回填（NULL 与空串都覆盖），满足「加非空必须先保证无 NULL」的前提。列 `DEFAULT`
是继「门店级 → 租户级」之后的最后一层兜底：门店创建入口尚未落地，DB 默认值保证将来漏传时落到**显式的**
平台默认值而不是写入失败。

> §0.2 决策点 2 的「灰度期允许 NULL、收口期再补 NOT NULL」与 §4.1 D1 的「加 NOT NULL」在原文里并不一致；
> 批 1 按 **D1** 执行（D1 就是批 1 条目），因为本批同时把历史行全部回填完毕，不存在「灰度期还有 NULL」的窗口。
> 若后续要回到「允许 NULL」，需要一条新的迁移显式放开，不应靠修改 V27 实现（Flyway 校验和不可回改）。

### 9.3 门店写接口契约（S16）

`PUT /admin/tenant/stores/{id}`（`StoreController`；网关前缀 `/api/v1` 由网关加，与既有 `GET` 同路径族）

| 维度 | 契约 |
|---|---|
| 请求体 | `{"timezone":"Asia/Bangkok","businessDayCutoff":"02:00"}`；两个字段**都可选**，但至少传一个；未传的字段**保持原值**（不做隐式清零）。空请求体或两字段都不传 → 400 `STORE_UPDATE_EMPTY` |
| `timezone` 校验 | 必须能被 `ZoneId.of()` 解析且**不是偏移字面量**（`+08:00`、`Z` 一律拒绝，TC-U10）→ 400 `TIMEZONE_INVALID`；落库用 `ZoneId` 的规范 id |
| `businessDayCutoff` 校验 | 接受 `HH:mm` 与 `HH:mm:ss`（秒按分钟截断）；格式非法 → 400 `BUSINESS_DAY_CUTOFF_INVALID`；超出 `00:00`–`12:00` → 400 `BUSINESS_DAY_CUTOFF_OUT_OF_RANGE`；落库文本统一为 `HH:mm:ss` |
| 校验顺序 | 身份 401 → 权限 403 → 入参 400 → 门店/租户边界 403 → 不存在 404。**入参校验先于查库**：坏请求不触达数据库，也不产生任何 `UPDATE` |
| 权限 | `tenant.store.manage`（既有门店权限码，`V3__seed_default_init.sql:22` / `V11__seed_operational_iam.sql:8`，§2.1「复用既有门店权限码」）；缺失 → 403 `PERMISSION_DENIED` |
| 门店边界 | 签名上下文带门店（`TenantContext.storeId`）且与路径门店不一致 → 403 `STORE_SCOPE_DENIED`（**先于查库**） |
| 租户边界 | 门店 `tenant_id` 与签名上下文不一致 → 403 `TENANT_SCOPE_DENIED`；用新增的 `StoreMapper.selectTenantIdById`（`@InterceptorIgnore(tenantLine="true")`）区分「不存在」与「跨租户」，**拒绝把越权静默降级成 404** |
| 不存在 | 404 `STORE_NOT_FOUND` |
| 审计（成功） | 动作 `tenant.store.update`（`AuditActions` 已登记）、资源 `tnt_store`、`resourceId={id}`、`resourceName=门店名`、`detailJson={"timezone":{"before":…,"after":…},"businessDayCutoff":{"before":…,"after":…}}`（before/after 都规范成 `HH:mm:ss`，可直接比对） |
| 审计（失败） | 同一动作码 + `result=FAILURE` + `errorCode`，请求值经 JSON 转义后记录（§0.2 决策点 2 的「写失败也要留痕」） |
| 响应 | 变更后的 `StorePo`（与 `GET` 同一结构，**字段只增不改**） |

**契约字段核对（本批第 5 项）**：`GET /admin/tenant/stores` 返回的就是 `StorePo`，而 `StorePo` 已映射
`timezone`（`StorePo.java:26`）与 `businessDayCutoff`（`StorePo.java:30`），导出快照
`docs/contracts/openapi/platform-tenant-service.json` 的 `StorePo` schema 里两字段同样存在。
因此「门店响应补 `timezone` / `businessDayCutoff`」在本服务**已经是既成事实**：本批没有新增/改名任何响应字段
（避免破坏 `gv_saas_admin/src/views/tenant/stores.vue` 的既有消费），只把写入口补齐。
读出来是 `HH:mm:ss`、写入接受 `HH:mm`，两者语义相同；前端若要 `HH:mm` 自行截断即可（后续批次可考虑在
出参做统一，但那属于契约变更，需同步重新导出快照）。

✅ **冲突已定案（2026-09-18）**：`docs/renovation/SAAS_PLATFORM_05_API.md:94` 把
`PUT /api/v1/admin/tenant/stores/{id}` 的权限写成 `tenant.tenant.manage`，本方案 §2.1 原要求「复用既有门店权限码」。
两者取 **`tenant.tenant.manage`**（提交 `4bb1bbf0`）：
- **口径理由**：门店时区 + 营业日切点决定「日界」，它同时约束日结唯一键、报表按营业日聚合与交班时间窗，
  属与币种/租户配置同级的**经营配置**；若用 `tenant.store.manage`，店长（`store.manager` 持有该码、
  且不持有 `tenant.tenant.manage`）就能自行挪动日界，等于改变本店日结与报表归日；
- 两码都已在 IAM 基线登记（`V11__seed_operational_iam.sql`），**不新增权限码**，也与既有接口契约一致。

### 9.4 `StoreTimeService`（S19，唯一实现）

| 项 | 实际值 |
|---|---|
| 位置 | `sdk/infrastructure/src/main/java/io/openware/infrastructure/time/StoreTimeService.java`（`io.openware.infrastructure.time`） |
| 为什么放这里 | `infrastructure`（`sdk/infrastructure`）是 20 个可部署服务**全部**已声明的依赖，而 `StoreTimeService` 要被 order / payment / admin / tenant 共用（§4.2 S19）；放 `common-services` 会引入跨域依赖，放各服务会退化成多份实现 |
| 形态 | 无状态静态工具 + 一个 `record BusinessDayWindow`；不读库、不持配置、不重算历史（配置读取与降级策略留给调用方） |

API（最小必要面）：

| 方法 | 语义 |
|---|---|
| `requireZoneId(String)` | 严格校验 IANA 时区（写路径）；非法/偏移字面量 → 400 `TIMEZONE_INVALID` |
| `requireStoreZoneId(String)` | 门店时区为空 → 400 `STORE_TIMEZONE_MISSING`（**写路径 fail-closed**，§2.1 决策点 2） |
| `resolveZoneId(storeTz, tenantTz)` | 读路径降级：门店 → 租户 → `Asia/Shanghai`，**绝不抛异常**，整条链不可用时打 warn（§7 G2） |
| `requireBusinessDayCutoff(String)` / `resolveBusinessDayCutoff(String)` / `formatBusinessDayCutoff(LocalTime)` | 切点严格校验（写）/ 降级（读，回落 `04:00`）/ 契约文本 `HH:mm` |
| `businessDate(Instant, ZoneId, LocalTime)`、`businessDate(LocalDateTime, ZoneId, LocalTime)` | §3.2 公式的唯一实现（后者接 `DATETIME(3)` 里存的 UTC 墙钟字面量） |
| `businessDayWindow(LocalDate, ZoneId, LocalTime)` | 营业日 → 左闭右开 `[startInclusive, endExclusive)` 绝对时刻窗 |
| `toUtc(OffsetDateTime)` / `toStoreOffset(LocalDateTime, ZoneId)` | 读写边界的两个换算点（以后批次统一用它们，不再各处硬编码偏移） |

边界语义（写进类注释，并有对应用例）：

- **右开**：本地墙上时间恰好等于切点 → 归**当天**（TC-U4）。
- **DST 春进（不存在的墙上时间）**：切点落到 02:30 这类缺失时刻时，按 `ZonedDateTime.of` 的默认解析
  **前移**到 03:30 EDT，**不抛错**；营业日窗口相应为 23 小时（跨切换点那天）。
- **DST 秋回（重复的墙上时间）**：营业日按**绝对时刻**计算，本地 01:30 的两次出现（`05:30Z` / `06:30Z`）
  各自独立、不会被合并；营业日窗口相应为 25 小时。
- **跨门店**：同一绝对时刻在不同门店时区/切点下可以属于不同的营业日（用例已固化）。

测试：`sdk/infrastructure/src/test/java/io/openware/infrastructure/time/StoreTimeServiceTest.java`，**29 个用例**
（含 §6.1 的 TC-U1/U2/U3/U4/U5/U6/U10 与 §6.4 的 TC-D3）。
门店写接口测试：`platform-tenant-service/src/test/java/.../api/controller/StoreControllerTest.java`，**13 个用例**
（读写往返、字段级可选、拒绝偏移字面量、切点格式/范围、空请求、跨门店 403、跨租户 403、404、缺权限 403、
缺上下文 401、成功/失败审计）。

### 9.5 基础设施钉 UTC 清单（S20 / D5）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `Dockerfile` | 新增 `ENV TZ=UTC`；`ENTRYPOINT` 改为 `["java","-Duser.timezone=UTC","-jar","app.jar"]`（`k8s/local/rocketmq.Dockerfile` 与 `portal/Dockerfile` 非 JVM 应用，未改） |
| 2 | `k8s/local/saas.yaml`（13）/ `k8s/local/applications.yaml`（6）/ `k8s/ack/saas-common.yaml`（3）/ `k8s/ack/saas-domain.yaml`（4）/ `k8s/ack/saas-support.yaml`（2）/ `k8s/ack/platform-customer-service.yaml`（1）/ `k8s/ack/platform-identity-service.yaml`（1）/ `k8s/ack/idaas.yaml`（1）/ `k8s/ack/edge.yaml`（1）/ `k8s/ack/saas-admin.yaml`（1） | 每个**应用容器**的 `env:` 首行插入 `- { name: TZ, value: UTC }`，共 **33 处 / 10 个文件** |
| 3 | `application.yml` × 4：media / im-admin / im-conversation / im-user | `serverTimezone=Asia/Shanghai` → `serverTimezone=UTC`；**19/19** 服务的 JDBC URL 现在统一 `UTC`（§7 A4 的 URL 部分达成） |
| 4 | **未改** | `k8s/ack/infrastructure.yaml`（mysql/mongodb/minio StatefulSet）、`k8s/ack/minio-init.yaml`、`k8s/local/infrastructure.yaml`（用户 WIP，本批禁改）、三个前端 Deployment（nginx，无 `env:` 块） |

**行为变更判定**：如 §9.1 结论，探针显示当前 JVM 默认时区**本来就是 UTC**，因此这一批**不改变** kind/ACK
上现有服务的实际墙钟口径；它把「镜像没有 tzdata 所以默认 UTC」变成「显式 `TZ` + `-Duser.timezone`」的契约。
换句话说：**批 1 在本环境不是行为变更，但它消除了未来因基础镜像/参数变化而静默漂移的风险**；一旦基础镜像
换成默认非 UTC 的底座，钉住之前的行为就会变 —— 这也是 §4.6 要求「批 1 在业务低峰发布并做发布前后同门店报数对比」
的适用条件。
时间列仍是 `DATETIME(3)`：`serverTimezone` 对 `LocalDateTime` 是字面量透传，本次统一**不改变**任何已落库值
（它只影响 `Timestamp`/`Date` 的转换），但会消除「同一个值在不同服务读出来不一样」的隐患。
**本轮不部署**：k8s 清单与镜像只改文件，不 apply（按任务约束，随下一批发版上线）。

### 9.6 批 1 明确**未做**的事（交给后续批次）

| 未做项 | 归属 | 说明 |
|---|---|---|
| UTC 影子列（`*_utc`）+ 写路径双写 | §4.1 D2/D3/D4、§4.2 S1/S3/S6/S11、批 2 | 一行未碰；`ReservationApplicationService` 的 `toBusinessLocal(+08:00)` 仍在，`LocalDateTime.now()` 调用点仍在 |
| 存量业务时间回填 | §5.1 D5、批 3 | 本批**不需要**（探针结论：乙类已是 UTC；甲类 `ord_reservation.start_at/end_at` 的 `-8h` 换算属批 3） |
| `business_date` / `store_timezone` 物化列、报表与日结改按营业日 | §2.2 A2、§3.3、S6/S7/S12/S13、批 5 | 未做；`pay_daily_closing` 的 `business_date` 仍由前端传入 |
| 出入参改 RFC3339 带偏移 + `startAtLegacy` / `storeTimezone` / `businessDate` 出参 | §2.3、S4/S5/S8/S9/S10、批 4 | 未做 |
| `Appointment`/预约 → 会话窗口透传（`reserved_start_at/end_at`） | S2、批 3 | 未做（§1.2 场景 2 的功能缺口仍在） |
| 审计/IM 侧 `ZoneId.systemDefault()` → UTC | S14/S15 | 未做（`AuditTimeParser` 未改；`AuditClient`/`AuditActions` 的既有逻辑本批**未动**，只沿用了已登记的动作码） |
| 三端前端渲染改造（F1–F13） | §4.4、批 4/5 | 未做（属另一个仓库） |
| `GET /admin/tenant/stores` 出参把切点统一成 `HH:mm` | 契约变更 | 未做（保持 `HH:mm:ss`，避免破坏既有消费与快照） |
| 重新导出 `docs/contracts/openapi/tenant.json` / `platform-tenant-service.json` | §4.3 G1 | **未做**：按 `docs/contracts/openapi/README.md:3,9` 「禁止手工伪造快照，必须从运行中服务的 `/v3/api-docs` 导出」，而新 `PUT` 尚未部署到 kind；README 第 10 行对「快照不含未发布改动」已有先例。随下一批发版后重新导出 |
| 门店**创建**接口（`POST /admin/tenant/stores`）与「取租户 `default_timezone` 预填」 | §2.1、SAAS_PLATFORM_05_API.md:93 | 未做：本批只加修改接口；因此 §2.1 的租户级预填规则暂由 DB 默认值兜底 |
| 租户级默认切点 `tnt_tenant_config.default_business_day_cutoff` | §3.1 | 未做 |
| `TZ=UTC` 注入到基础设施容器（mysql/redis/minio/rocketmq）与前端 nginx | — | **有意不做**：会改变 MySQL 的 `system_time_zone`（影响 `NOW(3)` 与 TIMESTAMP 列），超出「钉 JVM 时区」的范围 |

### 9.7 本批验收对照与遗留风险

已达成：§7 A4 的「JDBC `serverTimezone` 全为 `UTC`」与「deployment 注入 `TZ=UTC`」（应用容器）；
§3.1 的「门店切点默认 `04:00`、可配置、有范围校验」；§2.1 的「门店时区权威 + 写路径 fail-closed + 读路径降级」；
§6.1 的 TC-U1~U6/U10（`StoreTimeServiceTest`）；S19 的「唯一实现」；D1 的回填与非空。

遗留风险：

1. **权限码冲突**（§9.3）：`tenant.store.manage` vs `SAAS_PLATFORM_05_API.md:94` 的 `tenant.tenant.manage`。
2. **营业日尚无消费方**：`StoreTimeService` 与 `business_day_cutoff` 在批 1 之后仍**没有业务调用点**
   （日结/报表在批 5 才切），所以本批的价值是「把输入准备好」，不是「日界已经正确」。
3. **`tnt_store.timezone` 的 DB 默认值 `Asia/Shanghai`** 可能掩盖将来海外门店创建时漏传时区的问题；
   等门店创建接口落地后，应把「取租户 `default_timezone`」做成应用层的强制预填（并考虑去掉 DB 默认值）。
4. **探针结论的时效性**：结论绑定当前基础镜像（`eclipse-temurin:25-jre-alpine` + `apk add ffmpeg`）。
   任何基础镜像/启动参数变更后必须重跑 §1.4 探针。
5. **DST 切点解析**已按「前移」实现并固化用例，但**业务上是否接受前移**尚未拍板（§6.4 TC-D4 的
   `TIME_INVALID_LOCAL_TIME` 属于预约入参口径，与本批的切点解析是两件事）。
