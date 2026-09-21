# 现金与币种全场景影响面清单（CNY / USD，默认 USD）

- 日期：2026-09-18
- 状态：**只读调研产出**。本文档是唯一被写入的文件；未修改任何代码、配置、k8s、测试，未改版本号，未 push。
- 调研范围（本地 6 个仓库，均 `develop/2.0.0-saas-20260826` 分支）：

| 仓库 | HEAD | 用途 |
|---|---|---|
| `gv_im_server` | `dd17336d` | Java 25 / Spring Boot 4 / MyBatis-Plus / Flyway 后端 + k8s + docs |
| `gv_saas_admin` | `b29bd7b` | Vue3 租户/平台后台 |
| `gv_saas_mobile` | `2b66dab` | Vue3 B 端 + C 端 H5（`src/b-end`、`c-end`） |
| `gv_chat_app` | `a09d5ee` | Flutter IM 客户端（含 B 端 KTV 收银/结账/班次页） |
| `gv_chat_desktop` | `6e0608e` | Electron + TS 桌面端 |
| `gv_chat_admin` | `d259b14` | Vue3 IM 运营后台 |

- 调研方法：`git -C <repo> grep -n` 精确扫描（`node_modules` / `dist*` / `build` / `out` / `.git` / `target` 全部排除），配合逐文件 `read` 核验。所有结论都给出 `文件:行号`。
- 金额口径基线（来自仓库内既有规范，改造必须遵守）：
  - `docs/renovation/KTV_BUSINESS_01_SERVICE.md:28` —「金额只由服务端计算并返回「最小单位 + 币种字符串」，各端展示同一份账单快照，不自行计算」。
  - `docs/renovation/KTV_BUSINESS_01_SERVICE.md:481` —「金额只由服务端计算并返回「最小单位 + 币种字符串」，各端展示**同一份账单快照**，不自行计算税费/折扣/抵扣/找零」。
  - `docs/renovation/SAAS_PLATFORM_02_SERVICE.md:153` —「金额使用最小货币单位或高精度 decimal，禁止浮点；订单固化币种、汇率、税率和价格快照」。
  - `docs/renovation/SAAS_PLATFORM_03_APP.md:136` —「服务端返回字符串或最小单位与币种；App 使用货币格式化组件…跨币种只展示服务端结果和汇率快照，不在客户端兑换」。
  - `docs/renovation/SAAS_PLATFORM_01_SERVICE.md:241` —「首发只允许同币种储值消费。跨币种或跨国抵扣虽然预留为平台能力，但默认关闭」。

> **重要前置结论（会改变改造方案，请先读）**
> 1. 后端**已经部分支持多币种**：`ord_order.currency_code`、`pay_intent.currency_code`、`pay_transaction.currency_code`、`cst_wallet_account.currency_code`、`pay_channel_transaction.currency`、`tnt_store.default_currency`、`tnt_legal_entity.currency_code` 都已存在（§1）。真正缺的是**单一来源 + 默认值 + 全链路透传**。
> 2. **前端不存在任何「币种选择」入口**：`gv_saas_admin` 全仓 `币种` 仅 7 处且全是只读展示（`src/utils/format.js:5,87`、`src/views/tenant/members.vue:110`、`src/views/tenant/orders.vue:309`、`src/views/tenant/stores.vue:26` 等）。「修改币种时各业务界面统一更改」目前**没有可挂载的锚点**，需要新建。
> 3. `gv_chat_admin` **完全没有任何金额/计费/币种代码**（§3.4），是纯 greenfield。
> 4. `gv_chat_desktop` **同样没有任何金额代码**（§3.5）。
> 5. 金额符号在**三套互不相通的前端**里各写各的：`gv_saas_admin` 用 `'¥ '`（前缀+空格）、`gv_saas_mobile` 用 `'¥ '`+空格、`gv_chat_app` 用 `'¥'`（无空格，且带完整多币种符号表）。**改币种必须三处同改，否则同一笔钱在不同端显示不同符号。**
> 6. `gv_chat_app/lib/models/ktv_models.dart:38-96` 已经实现了**正确的多币种格式化参考实现**（符号表 + 小数位表 + 千分位），是本次改造最应该被复用而非重写的资产（§3.3）。

---

## 1. 存储层：所有金额/价格/费率字段与 Flyway 迁移

### 1.1 订单域（`platform-order-service`）

| 表 | 列 | 类型 | 单位口径（注释原文） | 证据 |
|---|---|---|---|---|
| `ord_order` | `currency_code` | `char(3) NOT NULL` | `'币种'` | `platform-services/order/platform-order-service/src/main/resources/db/migration/V1__ord_order_baseline.sql:9` |
| `ord_order` | `subtotal_amount` / `discount_amount` | `decimal(20,6)` | 无注释（口径见 §5.1） | 同上 `:10` |
| `ord_order` | `tax_amount` / `total_amount` | `decimal(20,6)` | 无注释 | 同上 `:11` |
| `ord_order` | `paid_amount` / `refundable_amount` | `decimal(20,6)` | 无注释 | 同上 `:12` |
| `ord_order_item` | `unit_price` / `quantity` | `decimal(20,6)` | 无注释 | 同上 `:26` |
| `ord_order_item` | `discount_amount` / `tax_amount` / `total_amount` | `decimal(20,6)` | 无注释 | 同上 `:27` |
| `ord_order_item` | `price_snapshot_json` | `json` | 价格快照 | 同上 `:28` |
| `ord_order_item` | `item_type` | `varchar(32)` | `'PRODUCT/SERVICE/PACKAGE/ROOM_FEE/ADD_ON/TAX/FEE'` | 同上 `:23` |
| `ord_ktv_session` | `overtime_rate` | `decimal(20,6) DEFAULT 1.0` | 超时费率 | 同上 `:42` |
| `ord_ktv_session` | `billing_rule_snapshot_json` | `json` | 计价规则快照（开台时固化） | 同上 `:43` |
| `ord_ktv_server_session` | `price_per_inc` | `bigint NOT NULL` | `'每递增粒度单价（最小货币单位）'` | 同上 `:58` |
| `ord_ktv_server_session` | `total_amount` | `decimal(20,6)` | 无注释 | 同上 `:60` |
| `ord_ktv_server_session` | `price_snapshot_json` | `json` | 价格快照 | 同上 `:60` |
| `ord_catalog_item` | `unit_price` | `decimal(20,6) NOT NULL` | `'单价（最小货币单位：分）'` | `.../db/migration/V9__ord_catalog_item.sql:10` |
| `ord_catalog_item` | `unit` | `varchar(32) DEFAULT '份'` | `'单位 瓶/份/小时/次/套'` | 同上 `:9` |
| `ord_product` | `sale_price` | `decimal(20,6) NOT NULL` | `'销售单价（分）'` | `.../db/migration/V11__ord_inventory_product.sql:68` |
| `ord_inventory_material` | `purchase_price` | `decimal(20,6) NULL` | `'采购价（最小货币单位：分，每计量单位）'` | `.../db/migration/V20__ord_inventory_material_purchase_price.sql:15` |
| `ord_reservation` | — | — | **无任何金额列**（预约不收款、无预授权） | `.../db/migration/V2__ord_reservation.sql:4-28`（全表逐列核对，仅有 `party_size`/`status`/`order_id`） |

种子数据（口径自证：整数「分」）：
- `.../V9__ord_catalog_item.sql:21` — `-- A380 KTV（tenant_id=100, store_id=100）种子目录（单价单位：分）`
- `.../V9__ord_catalog_item.sql:23` — `(1, 100, 100, '酒水', 'PRODUCT', '百威啤酒', '瓶', 1500, ...)` → 1500 分 = ¥15.00
- `.../V9__ord_catalog_item.sql:30` — `'加钟 1 小时', '小时', 10000` → 10000 分 = ¥100.00
- `.../V9__ord_catalog_item.sql:32` — `'欢唱套餐', '套', 28800` → 28800 分 = ¥288.00
- `.../V20__ord_inventory_material_purchase_price.sql:1` — `-- 仓库管理需要增加「采购价」：ord_inventory_material 增加 purchase_price 列。`
- `.../V20__ord_inventory_material_purchase_price.sql:3` — `-- 列形态与仓库/订单其它金额列一致（ord_product.sale_price、ord_catalog_item.unit_price）：`
- `.../V20__ord_inventory_material_purchase_price.sql:11` — `--   > 0  = 实际采购价，为负或超过上限一律 400 PURCHASE_PRICE_INVALID。`

### 1.2 资源 / 包厢计价域（`platform-resource-service` + `platform-tenant-service`）

| 表 | 列 | 类型 | 单位口径（注释原文） | 证据 |
|---|---|---|---|---|
| `res_room_type` | `unit_price` | `bigint NULL` | `'房型单价（房费，最小货币单位/计费单位；NULL 回退门店级单价）'` | `platform-services/resource/platform-resource-service/src/main/resources/db/migration/V6__res_room_type_and_area.sql:23` |
| `res_room_type` | `server_unit_price` | `bigint NULL` | `'房型服务人员单价（最小货币单位/计费单位；NULL 回退门店级服务人员单价）'` | 同上 `:24` |
| `tnt_pricing_plan` | `price_per_unit` | `bigint NOT NULL` | `'每单位单价（最小货币单位）'` | `platform-services/tenant/platform-tenant-service/src/main/resources/db/migration/V2__tnt_pricing_plan.sql:9` |
| `tnt_pricing_plan` | `overtime_rate` | `decimal(20,6) DEFAULT 1.0` | `'超时费率'` | 同上 `:11` |
| `tnt_pricing_plan` | `billing_unit` / `increment_minutes` / `rounding_direction` | — | 计费单位/递增/舍入 | 同上 `:8,10,12`（见文件） |
| `res_resource` | `area_name` / `room_type_id` | — | 房型引用（决定用哪个单价） | `.../V6__res_room_type_and_area.sql:36-37` |

口径声明（同文件）：
- `.../V6__res_room_type_and_area.sql:12` — `--    金额一律最小货币单位整数，与 tnt_pricing_plan.price_per_unit 同口径。`
- `.../V6__res_room_type_and_area.sql:10-11` — 房型字典承载「房型单价」「房型服务人员单价」，NULL 或 <=0 回退门店级。
- 种子：`platform-services/tenant/.../V7__seed_a380_tenant.sql:26`（`INSERT INTO tnt_pricing_plan ... price_per_unit ...`）

### 1.3 租户 / 门店 / 主体币种配置（多币种的「本应唯一来源」）

| 表 | 列 | 类型 | 证据 |
|---|---|---|---|
| `tnt_store` | `default_currency` `char(3) NULL` | **门店默认币种** | `platform-services/tenant/platform-tenant-service/src/main/resources/db/migration/V1__tnt_iam_baseline.sql:29` |
| `tnt_store` | `locale` `varchar(16) NULL` / `country_code` / `timezone` / `tax_profile_id` / `business_day_cutoff` | 区域化配置 | 同上 `:28-30` |
| `tnt_legal_entity` | `currency_code` `char(3) NULL DEFAULT 'CNY'` | `'结算币种'` | `platform-services/tenant/.../db/migration/V6__tnt_legal_entity.sql:8` |
| `tnt_tenant_config` | `config_key` / `config_value`（KV） | 承载 `wallet_brand_name` / `wallet_ratio` | `.../V1__tnt_iam_baseline.sql:38`（建表）；键名见 `platform-services/tenant/.../api/controller/TenantConfigController.java:27-30` |

种子（**硬编码 CNY**）：
- `platform-services/tenant/.../V3__seed_default_init.sql:10-11` — `INSERT INTO tnt_store (..., default_currency, locale, ...) ... 'CNY', 'zh-CN', ...`
- `platform-services/tenant/.../V7__seed_a380_tenant.sql:11-12` — `... 'CNY', 'zh-CN', 'ACTIVE'`
- `platform-services/tenant/.../V7__seed_a380_tenant.sql:15-16` — `INSERT INTO tnt_legal_entity (..., currency_code, ...) VALUES (..., 'CNY', ...)`

> **证据性缺口**：`tnt_store.default_currency` 在**整个 Java 后端没有任何读写**。`git grep -n -iE "defaultCurrency|default_currency" -- "*.java"` 在 `gv_im_server` 返回 0 条业务命中（仅 `Locale.ROOT` 等无关命中）。也就是说「门店默认币种」这一列目前是**死列**；而前端 `gv_saas_mobile/src/b-end/views/Stores.vue:7` 已经在展示 `s.defaultCurrency`（§3.2），`gv_saas_admin/src/views/tenant/stores.vue:28` 用 `currencyText(row.defaultCurrency)` 展示（§3.1）。

### 1.4 会员资产域（`platform-customer-service`）

| 表 | 列 | 类型 | 单位口径（注释原文） | 证据 |
|---|---|---|---|---|
| `cst_wallet_account` | `currency_code` | `char(3) NOT NULL` | `'币种'` | `platform-services/customer/platform-customer-service/src/main/resources/db/migration/V1__cst_customer_baseline.sql:34` |
| `cst_wallet_account` | `available_amount` | `bigint DEFAULT 0` | `'可用储值余额(最小货币单位整数)'` | 同上 `:35` |
| `cst_wallet_account` | `frozen_amount` | `bigint DEFAULT 0` | `'冻结储值余额(最小货币单位整数)'` | 同上 `:36` |
| `cst_wallet_account` | 唯一键 `uk_cst_wallet_account_scope` | — | `(tenant_id, customer_id, legal_entity_id, currency_code)` — **同主体同币种唯一** | 同上 `:44` |
| `cst_wallet_account` | `legal_entity_id` | `bigint` | 商户主体（与币种共同决定账户） | 同上 `:33` |
| `cst_wallet_ledger` | `entry_type` | `varchar(24)` | `'RECHARGE/CONSUME/REFUND/HOLD/RELEASE/ADJUST'` | 同上 `:53` |
| `cst_wallet_ledger` | `amount` | `bigint NOT NULL` | `'变动金额(最小货币单位整数，正数)'` | 同上 `:54` |
| `cst_wallet_ledger` | `balance_after` | `bigint NOT NULL` | `'变动后可用余额'` | 同上 `:55` |
| `cst_wallet_ledger` | `fx_quote_id` | `bigint unsigned NULL` | `'汇率报价(跨币种，首发不用)'` — **本库无对应 `fx_quote` 表** | 同上 `:57` |
| `cst_point_account` | `available_points` / `frozen_points` | `bigint` | 积分（非货币） | 同上 `:73-74` |
| `cst_point_ledger` | `points` / `balance_after` / `rule_snapshot_json` | `bigint` / `json` | 积分（非货币） | 同上 `:91-92,96` |

**注意**：`cst_wallet_ledger` **没有 `currency_code` 列**——币种只能通过 `wallet_account_id` 回查 `cst_wallet_account`（§5.4 风险）。
**「A380币」是储值品牌展示名，不是币种**：`docs/renovation/KTV_BUSINESS_01_SERVICE.md:34` —「**A380币** | 储值的**品牌展示名**（默认「A380币」，存租户配置表 `tnt_tenant_config` 的 `wallet_brand_name` 项，可自定义，非硬编码）」。

### 1.5 营销域（`platform-marketing-service`）

| 表 | 列 | 类型 | 单位口径（注释原文） | 证据 |
|---|---|---|---|---|
| `mkt_campaign` | `campaign_type` | `varchar(32)` | `'活动类型 COUPON/POINT/DISCOUNT'` | `platform-services/marketing/platform-marketing-service/src/main/resources/db/migration/V1__mkt_marketing_baseline.sql:8` |
| `mkt_campaign` | `budget_amount` | `bigint DEFAULT 0` | `'活动预算(最小货币单位整数)'` | 同上 `:12` |
| `mkt_coupon` | `discount_type` | `varchar(24)` | `'FIXED_AMOUNT/PERCENTAGE'` | 同上 `:31` |
| `mkt_coupon` | `discount_value` | `bigint NOT NULL` | `'优惠值(最小货币单位整数或万分数)'` — **同一列两种口径** | 同上 `:32` |
| `mkt_coupon` | `min_amount` | `bigint DEFAULT 0` | `'门槛金额(最小货币单位整数)'` | 同上 `:33` |
| `mkt_coupon` | `max_discount` | `bigint DEFAULT 0` | `'封顶金额(最小货币单位整数，0=不封顶)'` | 同上 `:34` |
| `mkt_coupon_issuance` | `order_id` / `status` | — | `'ISSUED/USED/CANCELLED'`（无金额列） | 同上 `:56-57` |
| `mkt_coupon_redemption` | `amount` | `bigint NOT NULL` | `'订单金额(最小货币单位整数)'` — **无币种列** | `.../db/migration/V2__mkt_coupon_redemption.sql:11` |
| `mkt_coupon_redemption` | `discount_amount` | `bigint NOT NULL` | `'核销优惠金额(最小货币单位整数)'` — **无币种列** | 同上 `:12` |
| `mkt_coupon` | 无 `currency_code` | — | 券本身不绑币种 | 同上 `V1:26-46` 全表核对 |

### 1.6 支付域（`common-payment-service`，**唯一已有完整币种列的业务域**）

`common-services/payment/common-payment-service/src/main/resources/db/migration/V1__pay_baseline.sql`：

| 表 | 列 | 类型 | 证据 |
|---|---|---|---|
| `pay_intent` | `provider` | `varchar(16) 'CASH/WALLET/POINT/ALIPAY/WECHAT/STRIPE'` | `:5` |
| `pay_intent` | `amount` | `decimal(20,6) NOT NULL` — **无单位注释** | `:6` |
| `pay_intent` | `currency_code` | `char(3) NOT NULL` | `:6` |
| `pay_transaction` | `amount` | `decimal(20,6) NOT NULL` | `:17` |
| `pay_transaction` | `currency_code` | `char(3) NOT NULL` | `:17` |
| `pay_transaction` | `exchange_rate` | `decimal(20,6) NULL` — **有列无逻辑** | `:18` |
| `pay_transaction` | `fee_amount` | `decimal(20,6) NULL` | `:18` |
| `pay_refund` | `requested_amount` | `decimal(20,6) NOT NULL` — **无币种列** | `:26` |
| `pay_refund` | `approved_amount` | `decimal(20,6) NULL` — **无币种列** | `:26` |
| `pay_shift` | `opening_cash` / `expected_cash` | `decimal(20,6) DEFAULT 0` — **无币种列** | `:38` |
| `pay_shift` | `actual_cash` / `difference_amount` | `decimal(20,6) NULL` — **无币种列** | `:39` |
| `pay_daily_closing` | `summary_json` | `json NULL` — 汇总从未被写入（§5.3） | `:50` |
| `pay_daily_closing` | 无 `currency_code` | — | `:46-54` 全表核对 |
| `pay_collect` | `request_json` / `response_json` | `json` — 币种只存在于 JSON 快照内 | `.../db/migration/V4__pay_collect_idempotency.sql:10-11` |

`common-services/payment-channel/common-payment-channel-service/src/main/resources/db/migration/V1__pay_channel_baseline.sql`：
- `:27` — `amount bigint NOT NULL COMMENT '金额最小货币单位整数（分）'`
- `:28` — `currency varchar(8) NOT NULL COMMENT '币种 ISO 4217'`

H2 测试基线（口径镜像，改造时须同步）：
- `common-services/payment/common-payment-service/src/test/resources/db/test-migration/V1__pay_h2_schema.sql:29`、`:51` — `currency_code CHAR(3) NOT NULL`
- `platform-services/order/platform-order-service/src/test/resources/db/test-migration/V1__ord_h2_schema.sql:14` — `currency_code CHAR(3) NOT NULL`
- `platform-services/customer/platform-customer-service/src/test/resources/db/test-migration/V1__cst_h2_schema.sql:9` — `currency_code CHAR(3) NOT NULL`

### 1.7 审计日志（金额以 JSON 字符串落库）

| 表 | 列 | 证据 |
|---|---|---|
| `iam_audit_log` | `action` / `resource_type` / `resource_id` / `detail_json` / `idempotency_key` | `common-services/audit/common-audit-service/src/main/resources/db/migration/V1__iam_audit_log.sql:6-11` |

金额以 **字符串拼进 `detail_json`**（无币种、无单位），见 §5.2。

### 1.8 IM 域遗留资产（**已删除，勿重复清理**）

- `im-services/user/im-user-service/src/main/resources/db/migration/V10__init_coin.sql:2,15` — `user_coin_account` / `user_coin_ledger`（`balance bigint`、`amount bigint`）
- `im-services/user/im-user-service/src/main/resources/db/migration/V1__init.sql:77,81,90,95` — `user_point_account` / `user_point_ledger`（`balance int unsigned '当前可用积分'`）
- `im-services/user/im-user-service/src/main/resources/db/migration/V12__drop_coin_points.sql:1-5` — **已删除上述 4 张表**：注释明确「A380币/积分已迁移至 SaaS `cst_wallet_*`/`cst_point_*`」

### 1.9 存储层证据统计

- 迁移文件中共 **113 行**命中金额关键字（`amount|price|fee|cost|money|balance|payable|refund|deposit|discount|coin|rate`，限定 `*/db/migration/*.sql`）。
- 逐条列出金额列 **40 列**，分布 15 张表、12 个迁移文件；其中 **带 `currency_code` 的 7 列**、**明确注释「分/最小货币单位」的 20 列**、**无任何单位注释的 13 列**（`ord_order*` 的 6 列 + `pay_*` 的 8 列等）。

---

## 2. 服务端接口：DTO / 端点 / 硬编码币种文案

### 2.1 已带币种的 DTO / 端点（改造的正向基础）

| 模块 | 类 / 方法 / 路径 | 币种字段 | 证据 |
|---|---|---|---|
| order | `OrderPo`（`GET /business/orders`、`GET /me/orders` 直接返回 PO） | `currencyCode` | `platform-services/order/platform-order-service/src/main/java/com/gvchat/platform/order/infra/persistence/po/OrderPo.java:55` |
| order | `POST /business/orders` → `CreateOrderRequest` | `currencyCode`（**由客户端传入，无服务端默认值/校验**） | `.../api/controller/OrderController.java:295`（record）、`:120`（`po.setCurrencyCode(req.currencyCode())`） |
| order | `GET /business/orders/{orderId}/bill` → `BillResult` | `currencyCode` | `.../application/dto/BillResult.java:11`；控制器 `.../api/controller/OrderBillController.java:22-25`；装配 `.../application/BillApplicationService.java:76` |
| order | `GET /business/ktv/pricing` → `PricingView` | **无币种字段**（但 `displayText` 内嵌 `¥`） | `.../api/controller/KtvPricingController.java:144`（record 定义）、`:91-124`（文案与符号） |
| payment | `POST /business/orders/{orderId}/collect` → `CollectRequest` | `currencyCode`（**客户端传入，未与订单币种比对**） | `common-services/payment/common-payment-service/src/main/java/com/gvchat/common/payment/api/controller/CollectController.java:55`；`:46` 透传；应用层 `.../application/CollectApplicationService.java:97`、`:397`、`:409` |
| payment | `PayIntentDto` | `currencyCode` | `.../application/PayIntentDto.java:10` |
| payment | `GET /internal/payment/orders/{orderId}/collected` | — | `.../api/controller/InternalPaymentQueryController.java:40` |
| customer | `GET /business/members/{id}/wallet` → `CstWalletAccountPo` | `currencyCode` | `.../infra/persistence/po/CstWalletAccountPo.java:23`；控制器 `.../api/controller/MemberController.java:90` |
| customer | `GET /me/wallet` → `CstWalletAccountPo` | `currencyCode` | `.../api/controller/MyAssetsController.java:34-38` |
| customer | `GET /internal/customer/wallets/{customerId}` → `WalletBalanceResponse` | `currencyCode` | `.../api/controller/InternalCustomerController.java:140`；查询 `:82` |
| customer | `POST /internal/customer/wallets/deduct` → `WalletDeductRequest` | `currency` | 同上 `:128`；金额校验 `.../application/WalletApplicationService.java:89` |
| customer | `POST /internal/customer/wallets/release` → `WalletReleaseRequest` | `currency` | 同上 `:132` |
| customer | `POST /admin/wallets/recharge` → `RechargeRequest` | `currency` | `.../api/controller/WalletAdminController.java:34`；`:24` |
| admin(BFF) | `GET/POST/PUT /admin/ktv/wallet-recharge` → `WalletRechargeRequest/Result` | `currencyCode` | `platform-services/admin/platform-admin-api/src/main/java/com/gvchat/platform/admin/api/ktv/WalletRechargeRequest.java:11`；`WalletRechargeResult.java:12` |
| admin(BFF) | `GET/POST /admin/ktv/payment-switches` → `PaymentSwitchConfig` | `currencyCode` | `.../api/ktv/PaymentSwitchConfig.java:15` |
| payment-channel | `CreatePaymentIntentRequest` / `QueryOrderResult` / `VerifyCallbackResult` | `currency` | `common-services/payment-channel/common-payment-channel-api/src/main/java/com/gvchat/common/payment/channel/spi/CreatePaymentIntentRequest.java:9`、`QueryOrderResult.java:10`、`VerifyCallbackResult.java:9` |
| order(customer) | `CustomerClient.WalletBalanceResponse` / `WalletDeductRequest` / `WalletReleaseRequest` | `currencyCode` / `currency` | `common-services/payment/.../infra/client/CustomerClient.java:171,174,177,180,183` |
| admin(customer) | `CustomerServiceClient.RechargeRequest` / `WalletAccountView` | `currency` / `currencyCode` | `platform-services/admin/.../infra/CustomerServiceClient.java:116,120,95` |
| tenant | `POST/PUT /admin/tenant/config` → `WalletTokenConfig` | **无币种**（只有 `brandName` + `ratio`） | `platform-services/tenant/.../api/controller/TenantConfigController.java:117` |

OpenAPI 契约快照中已含 `currencyCode`：
- `docs/contracts/openapi/common-payment-service.json:511`
- `docs/contracts/openapi/platform-admin-service.json:500,539,740`
- `docs/contracts/openapi/platform-customer-service.json:539,586,854,927`
- `docs/contracts/openapi/platform-order-service.json:822,859,1222`

### 2.2 返回/接收金额但**完全没有币种**的 DTO / 端点（改造必改）

| 模块 | 类 / 端点 | 金额字段 | 证据 |
|---|---|---|---|
| payment | `CashierController` `POST /business/shifts/open` → `OpenShiftRequest` | `BigDecimal openingCash` | `common-services/payment/common-payment-service/src/main/java/com/gvchat/common/payment/api/controller/CashierController.java:63` |
| payment | `POST /business/shifts/{id}/close` → `CloseShiftRequest` | `BigDecimal actualCash` | 同上 `:64`；`ShiftDto` `.../application/ShiftDto.java:11`（`expectedCash/actualCash/differenceAmount`，**无币种**） |
| payment | `POST /business/refund-requests` → `RefundRequest` | `BigDecimal amount` | 同上 `:66`；`RefundDto` `.../application/RefundDto.java:10`（`requestedAmount/approvedAmount`，**无币种**） |
| payment | `POST /admin/refund-requests/{id}/approve` → `ApproveRefundRequest` | `BigDecimal approvedAmount` | 同上 `:67` |
| payment | `POST /admin/daily-closings/{id}/submit` → `DailyClosingRequest` | 无金额（`summary_json` 从不写入） | 同上 `:65`；`.../application/DailyClosingDto.java` |
| payment | `GET /admin/reconciliations/summary` → `ReconciliationSummary.ProviderLine` | `grossAmount/feeAmount/netAmount` | `.../application/ReconciliationApplicationService.java:52`；控制器 `.../api/controller/ReconciliationController.java:18-22` |
| order | `POST /business/orders/{orderId}/items` → `AddItemRequest` | `BigDecimal unitPrice`（**服务端用目录价覆盖**，客户端值被忽略） | `.../api/controller/OrderItemController.java:311`（record）；`:95-97,115-116,130`（覆盖逻辑） |
| order | `POST /admin/products` / `PUT /admin/products/{id}` → `ProductRequest` | `BigDecimal salePrice` | `.../api/controller/ProductController.java:21` |
| order | `GET/POST/PUT /admin/inventory/materials` | `purchasePrice` | `.../api/controller/InventoryController.java:19,26,32`；校验 `.../application/InventoryApplicationService.java:51-54` |
| order | `POST/PUT /business/catalog/items` → `CatalogItemRequest` | `BigDecimal unitPrice` | `.../api/controller/CatalogController.java:294` |
| order | `GET /business/ktv/pricing` → `PricingView` | `roomUnitPrice` / `serverUnitPrice` / `combinedUnitPrice` / `serverPricePerInc` / `unitPriceByRoomType` | `.../api/controller/KtvPricingController.java:144-150` |
| order | `GET /reports/store-operation` / `/employee-performance` | `receivable` / `collected`（`Map<String,Object>`，**无币种**） | `.../api/controller/ReportController.java:56-59`、`:119-122` |
| admin | `GET /admin/reports/operations` → `OperationsReport.Row` | `receivableAmount/paidAmount/refundAmount/discountAmount/averageTicketAmount` | `platform-services/admin/.../api/controller/ReportController.java:323-327` |
| admin | `GET /admin/reports/payments` → `PaymentsReport.Row` | `collectedAmount/refundAmount` | 同上 `:329-332` |
| admin | `GET /admin/reports/employee-performance` → `EmployeePerformanceReport.Row` | `collectedAmount` | 同上 `:318-321` |
| marketing | `POST /business/coupons` → `CreateCouponRequest` | `discountValue/minAmount/maxDiscount` | `platform-services/marketing/.../api/controller/CouponController.java:58-59` |
| marketing | `POST /business/coupons/{id}/redeem` → `RedeemCouponRequest` | `Long amount` | 同上 `:62` |
| tenant | `GET/POST/PUT /internal/pricing-plans` → `pricePerUnit` | `Long pricePerUnit` | `platform-services/tenant/.../api/controller/InternalPricingPlanController.java:70`；`PricingPlanController.java:38` |
| resource | `POST/PUT` 房型 → `RoomTypeRequest` | `unitPrice` / `serverUnitPrice` | `platform-services/resource/.../api/controller/ResourceTypeController.java:70` |
| admin(BFF) | `PricingPlan` / `PricingPackage` / `ServerCatalogItem` / `PaymentChannelSwitch` | `roomPricePerUnit` / `price` / `pricePerIncrement` / `minAmount` / `maxAmount` | `platform-services/admin/platform-admin-api/src/main/java/com/gvchat/platform/admin/api/ktv/PricingPlan.java:16,36`、`ServerCatalogItem.java:18`、`PaymentSwitchConfig.java` |

### 2.3 硬编码「¥」「元」「CNY」「分」的位置（逐条）

**后端 Java（非测试）：**

| 位置 | 原文性质 | 证据 |
|---|---|---|
| **硬编码 `¥` 拼进接口返回文案** | `return "¥" + minor / 100 + "." + String.format("%02d", minor % 100);` | `platform-services/order/platform-order-service/src/main/java/com/gvchat/platform/order/api/controller/KtvPricingController.java:123` |
| 同上方法的 Javadoc | `/** 最小货币单位（分）→ 「¥188.00」：金额一律「元」两位小数（前端不得再自行换算）。 */` | 同上 `:121` |
| `displayText` 文案模板 | `例：{@code ¥238.00/小时（房型 ¥188.00 + 服务 ¥50.00）· 30 分钟递增 · 标准 2.0 小时}。` | 同上 `:85` |
| 同上 | `服务单价为 0 时不出现「+ ¥0.00」，首段与改造前完全一致。` | 同上 `:86` |
| 同上 | `「每 N 分钟 ¥X」必须与实际计费单价同源…` | 同上 `:87` |
| 单位后缀拼接 | `case HOUR -> "/小时"; case HALF_HOUR -> "/半小时"; case PACKAGE -> "/套餐";` | 同上 `:92-96` |
| 拼装 | `text.append("（房型 ")...append(" + 服务 ")...` | 同上 `:99-100` |
| **硬编码 `CNY`（订单创建）** | `order.setCurrencyCode("CNY");` — 到店预约开台 | `platform-services/order/platform-order-service/src/main/java/com/gvchat/platform/order/application/ReservationApplicationService.java:174` |
| 硬编码 `CNY`（BFF 计价方案） | `return code == null \|\| code.isBlank() ? "CNY" : code;` | `platform-services/admin/platform-admin-service/src/main/java/com/gvchat/platform/admin/application/KtvConfigApplicationService.java:102-104` |
| 硬编码 `CNY`（BFF 骨架数据） | `new PaymentSwitchConfig(1L, storeId, null, null, "CNY", channels)` | `platform-services/admin/platform-admin-service/src/main/java/com/gvchat/platform/admin/infra/RestKtvConfigDomainClient.java:130` |
| 同上 | `new PaymentSwitchConfig(1L, storeId, "星光 KTV · 朝阳店", 1001L, "CNY", ...)` | 同上 `:315` |
| 硬编码 `CNY`（C 端钱包） | `walletService.ensureAccount(member.getId(), "CNY")` | `platform-services/customer/platform-customer-service/src/main/java/com/gvchat/platform/customer/api/controller/MemberController.java:62` |
| 同上 | `return walletService.ensureAccount(member.getId(), "CNY");` — `GET /me/wallet` | `.../api/controller/MyAssetsController.java:37` |
| 硬编码 `CNY` 默认（钱包域） | `return findOrCreate(customerId, currency == null \|\| currency.isBlank() ? "CNY" : currency);` | `.../application/WalletApplicationService.java:134` |
| 同上注释 | `/** 确保会员储值账户存在（C 端懒创建），默认币种 CNY。 */` | 同上 `:129` |
| 硬编码 `CNY`（微信渠道回退） | `return (currency == null \|\| currency.isBlank()) ? "CNY" : currency.toUpperCase(Locale.ROOT);` | `common-services/payment-channel/common-payment-channel-service/src/main/java/com/gvchat/common/payment/channel/infra/provider/WechatChannel.java:502-503` |
| 同上 | `params.getOrDefault("fee_type", "CNY")` | 同上 `:168`、`:216` |
| 同上 | `new QueryOrderResult("wechat", "", request.orderId(), "FAILED", 0L, "CNY")` | 同上 `:218`、`:356` |
| 同上 | `amount.put("currency", defaultCurrency(request.currency()));` | 同上 `:247` |
| 硬编码 `CNY`（支付宝渠道回退） | `return (currency == null \|\| currency.isBlank()) ? "CNY" : currency.toUpperCase(Locale.ROOT);` | `.../infra/provider/AlipayChannel.java:310-311` |
| 同上 | `params.getOrDefault("currency", "CNY")` / `defaultCurrency("CNY")` | 同上 `:122`、`:167` |
| Stripe 默认 `usd`（**唯一非 CNY 默认**） | `? "usd" : request.currency().toLowerCase(Locale.ROOT)` | `.../infra/provider/StripeChannel.java:66-67` |
| 渠道交易落库回退 `CNY` | `po.setCurrency(currency == null \|\| currency.isBlank() ? "CNY" : currency);` | `.../infra/persistence/PaymentChannelTransactionRecorder.java:60` |
| `@Schema`/Javadoc 内的「默认 CNY」 | `String currencyCode,               // 默认 CNY` | `platform-services/admin/platform-admin-api/.../api/ktv/PaymentSwitchConfig.java:15`；`WalletRechargeRequest.java:11` |
| Javadoc 内「元」换算约定 | `// 包厢单价（最小货币单位/计费单位；元↔分换算由前端做）` | `.../api/ktv/PricingPlan.java:16` |
| Javadoc 内「元」 | `金额一律用最小货币单位整数（前端用「元」输入并换算），tenant 侧按 (store, resourceType) upsert。` | `platform-services/admin/.../infra/RestKtvConfigDomainClient.java:83` |
| Javadoc 内「元」 | `后台表单按「元」录入，提交前由前端换算成分。` | `platform-services/order/.../api/controller/InventoryController.java:70` |
| Javadoc 内「元」 | `采购价上限：最小货币单位（分），10^13 分 = 1000 亿元／单位。` + `超过该值几乎必然是把「元」当「分」提交之类的误填…` | `platform-services/order/.../application/InventoryApplicationService.java:51-52` |
| Javadoc 内「元」 | `注意 0 分本身没有业务含义（采购价 0 元 = 未维护）…` | 同上 `:423` |
| Javadoc 内「元」 | `// M 与 n 走与包厢房费同一套舍入算法（默认让利）：33 秒 → M=0 → n=0 → 0 元，不会按整块多收。` | `.../application/KtvServerSessionApplicationService.java:152` |
| Javadoc 内「预估 ¥X」 | `开台中的包厢计时费实时估算（订单列表/会话详情展示「包厢计时中 · 预估 ¥X」）。` | `.../application/KtvSessionApplicationService.java:429` |
| Javadoc 内 `¥` | `例：¥100/小时（10000）× 30 分钟递增 → 每 30 分钟 5000 分；整数截断，零头让给消费者。` | `.../domain/ktv/model/KtvPricingPlan.java:199` |
| 同上 | `金额一律为最小货币单位整数（如 CNY 分），避免浮点小数。` | 同上 `:9` |
| Javadoc 内 `¥` | `计费口径（与页面文案「¥100.00/小时 · 30 分钟递增」一致）：` 与 `因此 33 秒 → 0 分钟 → 0 块 → 0 元…` | `.../domain/ktv/service/KtvRoomFeeCalculator.java:14`、`:29` |
| 配置默认值注释带 `¥` | `首发占位默认（KTV_BUSINESS_01 §5.3）：包厢 100 元/时、服务人员 50 元/时、标准时长 120 分钟。` | `.../infra/config/KtvPricingProperties.java:11` |
| 同上 | `/** 包厢每计费单位单价（最小货币单位，默认 10000 = ¥100）。 */` → `private long roomUnitPrice = 10000L;` | 同上 `:23-24` |
| 同上 | `/** 服务人员小时单价（最小货币单位，默认 5000 = ¥50/时）。 */` → `private long serverPricePerHour = 5000L;` | 同上 `:41-42` |
| yml 配置（无币种） | `ktv.pricing.room-unit-price: 10000` | `platform-services/order/platform-order-service/src/main/resources/application.yml:21` |
| Javadoc 内「元」 | `代币名称 + 金额与代币的比例（默认 1:100，即 1 元 = 100 代币）。` | `platform-services/tenant/.../api/controller/TenantConfigController.java:18` |
| Javadoc 内「元」 | `金额 BigDecimal → 最小货币单位 long。订单域金额已统一按「分」存储，此处仅做类型收敛，不做分/元换算。` | `platform-services/order/.../application/BillApplicationService.java:208` |
| Javadoc 内「元」**（与上一条矛盾）** | `金额按最小货币单位约定（PO 内 decimal(20,6) 单位元），返回保留原值；数据时点以查询时刻为准。` | `.../api/controller/ReportController.java:26` |

**后端资源文件（i18n / 模板 / 导出）的「负向证据」（重要）**：
- 全仓 `*.properties` 仅 `.mvn/wrapper/maven-wrapper.properties`（`Get-ChildItem -Recurse -Include *.properties` 结果）。
- 全仓 `*.ftl` / `*.html`（除 `portal/index.html`）为 0。
- 无 EasyExcel / Apache POI / CSV 导出：`git grep -n -iE "easyexcel|apache.poi|XSSFWorkbook|SXSSFWorkbook|CsvWriter|\.csv" -- "*.java" "pom.xml"` → 0 条业务命中。
- 无打小票/打印逻辑：`git grep -n -iE "小票|打印|receipt|print" -- "*.java"` 全为「不打印密钥」注释与 `readReceipt` 已读回执（与收银无关）。
- 短信/邮件模板不含金额：`git grep -n -iE "amount|price|金额|元" -- "common-services/sms/*" "common-services/mail/*"` → 0 条。
- `@Schema` 中含金额/元/分/币/价 的注解：0 条（`git grep -n -E "@Schema" -- "*.java"` 交叉过滤后为空）。

### 2.4 审计日志里的金额字符串（硬编码拼接，逐条）

| 位置 | 拼接内容 | 证据 |
|---|---|---|
| 组合收款 | `"{\"orderId\":" + orderId + ",\"payable\":" + payable + ",\"collected\":" + payable + "}"` | `common-services/payment/.../application/CollectApplicationService.java:200-203` |
| 交班 | `"{\"actualCash\":" + actualCash + ",\"expectedCash\":" + po.getExpectedCash() + ",\"difference\":" + po.getDifferenceAmount() + "}"` | `.../application/CashierApplicationService.java:58-62` |
| 退款申请 | `"{\"orderId\":" + orderId + ",\"amount\":\"" + amount + "\"}"` | `.../application/RefundApplicationService.java:46` |
| 退款审批 | `"{\"approvedAmount\":\"" + approvedAmount + "\"}"` | 同上 `:69` |
| 结算 | `"{\"totalAmount\":" + order.getTotalAmount() + ",\"discount\":" + order.getDiscountAmount() + ",\"tax\":" + order.getTaxAmount() + "}"` | `platform-services/order/.../application/SettlementApplicationService.java:50-54` |
| 租户代币配置 | `"{\"brandName\":\"" + req.brandName() + "\",\"ratio\":" + req.ratio() + "}"` | `platform-services/tenant/.../api/controller/TenantConfigController.java:58-64` |
| 钱包充值（内联 JSON） | `"{\"customerId\":" + customerId + ",\"amount\":" + amount + ",\"currency\":\"" + currency + "\"}"` | `platform-services/customer/.../application/WalletApplicationService.java:58` |
| 审计敏感字段清洗 | `body.put("detailJson", "{\"password\":\"p@ss\",\"amount\":\"10.00\"}")`（测试证明 amount **不被脱敏**） | `common-services/audit/common-audit-service/src/test/java/com/gvchat/common/audit/application/service/AuditLogApplicationServiceTest.java:40,52` |

审计动作注册表（与资金相关的动作码）：
- `sdk/infrastructure/src/main/java/com/gvchat/infrastructure/audit/AuditActions.java:130-133` — `payment.refund.request/approve/reject/offline`
- 同上 `:156-157` — `wallet.recharge` / `wallet.refund`
- 同上 `:126` — `inventory.receipt.create`

### 2.5 服务端证据统计

- 已带币种字段的 DTO/端点/契约点：**22 条证据**（覆盖 18 个类/端点 + 4 个 OpenAPI 快照位置）。
- 缺币种的金额 DTO/端点：**22 条证据**（18 个 record/类 + 4 处 H2 基线）。
- 硬编码 `¥`/`元`/`CNY`/`分` 的后端位置：**44 条证据**（其中生产代码里的 `¥` 实际输出 2 处：`KtvPricingController.java:123` 的 `money()` 与 `:97-118` 的 `displayText()` 拼接；`CNY` 硬编码 22 处）。
- 反向证据（无导出/无打印/无金额短信模板/无 `@Schema` 金额文案）：**6 条**。

---

## 3. 前端：格式化函数、术语常量、硬编码符号、守卫测试

### 3.1 `gv_saas_admin`（Vue3，金额改造面最大）

**3.1.1 唯一的金额格式化出口 `src/utils/format.js`（101 行）**

| 函数 | 行号 | 是否硬编码符号 / 换算 |
|---|---|---|
| `fenToYuan(minor)` | `src/utils/format.js:49-52` | `number / 100`（`:51`，全仓**唯一** ÷100） |
| `yuanToFen(yuan)` | `src/utils/format.js:55-58` | `Math.round(number * 100)`（`:57`，全仓**唯一** ×100） |
| `formatYuan(minor)` | `src/utils/format.js:61-66` | `return '¥ ' + fenToYuan(number).toFixed(2)`（`:65`） |
| `formatYuanCompact(minor)` | `src/utils/format.js:72-77` | `'¥ ' + ...toLocaleString('zh-CN', {...})`（`:76`） |
| `formatYuanValue(yuan)` | `src/utils/format.js:80-85` | `'¥ ' + number.toFixed(2)`（`:84`） |
| `currencyText(code)` | `src/utils/format.js:88-91` | `String(code \|\| 'CNY')` + `{ CNY: '人民币（元）', RMB: '人民币（元）' }[normalized] \|\| normalized`（`:89-90`） |

文件头契约：
- `src/utils/format.js:3` — `*  - 金额一律「元」展示（分只落库，进入本文件前必须是最小货币单位整数）；`
- `src/utils/format.js:5` — `*  - 币种、优惠类型等枚举一律转简体中文，界面上不出现英文枚举原文。`

**3.1.2 术语常量 `src/constants/terms.js`**

| 行号 | 常量 / 函数 | 值 |
|---|---|---|
| `:48-57` | `MONEY_FIELD_TEXT` | `{ payableAmount:'应收', receivableAmount:'应收', paidAmount:'已收', refundableAmount:'可退', refundAmount:'退款', discountAmount:'优惠', averageTicketAmount:'客单价', collectedAmount:'收款' }` |
| `:59-60` | `MONEY_UNIT` | `'元'`（注释：`金额单位：界面一律「元」（输入与展示），最小货币单位（分）只落库。`） |
| `:62-65` | `withYuanUnit(label)` | `` `${label}（${MONEY_UNIT}）` `` |
| `:67-70` | `moneyColumnLabel(field)` | `withYuanUnit(MONEY_FIELD_TEXT[field] \|\| field)` |
| `:72-80` | `DURATION_LABELS` | `{ minutes:'分钟', incrementMinutes:'递增粒度（分钟）', defaultSessionMinutes:'标准时长（分钟）' }` |
| `:83-87` | `WALLET_BRAND_NAME_DEFAULT` | `'A380币'`（**储值品牌名，非币种**） |
| `:89-93` | `resolveWalletBrandName(config)` | 配置优先 |
| `:223` | `BILLING_UNIT_TEXT` | `{ HOUR:'按小时', HALF_HOUR:'按半小时', PACKAGE:'套餐' }` |
| `:224-225` | `BILLING_UNIT_SHORT_TEXT` | `{ HOUR:'小时', HALF_HOUR:'半小时', PACKAGE:'套餐' }`；注释 `/** 计费单位短写法：用于「¥ 100.00/小时」这类拼接，不出现英文枚举。 */` |
| `:270-275` | `SHIFT_CASH_TEXT` | `{ opening:'备用金', expected:'应收现金', actual:'实收现金', difference:'长短款' }` |

**3.1.3 硬编码符号统计（已限定 `-- src docs`，排除被跟踪的 `.npm-cache/`）**

| pattern | 命中 | 分布 |
|---|---|---|
| `¥` U+00A5 | **34** | `src/utils/format.js` 6（`:60,65,69,76,79,84`）、`src/utils/format.test.js` 7（`:44,45,48,56,62,63,64`）、`src/utils/inventory-purchase-price.test.js` 3（`:27,28,68`）、`src/utils/roomPricing.test.js` 4（`:71,78,82,88`）、`src/utils/wallet-amount-contract.test.js` 2（`:27,28`）、`src/constants/terms.js:224`、`src/constants/terms.test.js:113`、`src/views/platform/pricing-plans.vue:66`（注释）、`docs/*` 10 |
| `￥` U+FFE5 | **0** | — |
| `元` | **91**（src 89 + docs 2） | 见下 |
| `USD\|CNY\|RMB` | **12** | `src/utils/format.js:89,90`；`src/utils/format.test.js:95,96,101`；`src/utils/adminErrorMessage.test.js:32`；**请求体硬编码 6 处**：`src/views/tenant/ktv-config.vue:459,527`、`src/views/tenant/orders.vue:1098,1418`、`src/views/tenant/payments.vue:285`、`src/views/tenant/wallet.vue:205` |
| `$` 作为货币符号 | **0** | `$` 命中全为 `${...}` 插值/正则/CSS |
| `美元\|dollar\|Dollar` | **0** | — |

`元` 的逐文件分布：`src/api/resource.js:20`、`src/constants/terms.js` 6、`src/constants/terms.test.js` 7、`src/utils/adminErrorMessage.js:21`、`src/utils/format.js` 5、`src/utils/format.test.js` 6、`src/utils/inventory-purchase-price.test.js` 6、`src/utils/product-material-autofill.js` 3、`src/utils/product-material-autofill.test.js` 2、`src/utils/resource-room-type.test.js` 2、`src/utils/roomPricing.js:106`、`src/utils/roomPricing.test.js` 3、`src/utils/wallet-amount-contract.test.js` 7、`src/views/platform/pricing-plans.vue` 2、`src/views/tenant/inventory.vue` 3、`src/views/tenant/ktv-config.vue` **21**、`src/views/tenant/members.vue` 1、`src/views/tenant/orders.vue` 2、`src/views/tenant/products.vue` 3、`src/views/tenant/resources.vue` 3、`src/views/tenant/wallet.vue` 4。

**用户可见的静态「（元）」标签（绕过 `withYuanUnit`，改币种不会跟着变）**：
- `src/views/platform/pricing-plans.vue:20` — `<el-table-column label="价格（元/月）" width="140" align="right">`
- `src/views/platform/pricing-plans.vue:46` — `<el-form-item label="价格（元/月）">`
- `src/views/tenant/ktv-config.vue:23` — `<el-table-column label="包厢单价（元/计费单位）" min-width="180" align="right">`
- `src/views/tenant/ktv-config.vue:34` — `label="服务人员单价（元/递增）"`
- `src/views/tenant/ktv-config.vue:71` — `label="单价（元/递增）"`
- `src/views/tenant/ktv-config.vue:107` — `label="最小单笔（元）"`
- `src/views/tenant/ktv-config.vue:112` — `label="最大单笔（元）"`
- `src/views/tenant/ktv-config.vue:131` — `<el-form-item label="金额（元）">`
- `src/views/tenant/ktv-config.vue:163` — `label="金额（元）"`
- `src/views/tenant/ktv-config.vue:191` — `label="包厢单价（元/计费单位）"`
- `src/views/tenant/ktv-config.vue:210` — `label="服务人员单价（元/递增）"`
- `src/views/tenant/ktv-config.vue:213` — `<el-alert ... title="价格按「元」填写，例如 100 元/小时就填 100；系统内部会换算成最小货币单位。" />`
- `src/views/tenant/ktv-config.vue:247` — `label="单价（元/递增）"`
- `src/views/tenant/wallet.vue:85` — `<span>1 元 = </span>`

`withYuanUnit` / `moneyColumnLabel` 的调用点（改这一处即全局改）：
- `src/views/tenant/inventory.vue:43,118`、`src/views/tenant/products.vue:37,72`、`src/views/tenant/resources.vue:175,181,221,224`、`src/views/tenant/shift.vue:90,103`、`src/views/tenant/wallet.vue:42,68`、`src/views/tenant/payments.vue:45`、`src/views/tenant/reports.vue:64,65,66,67,68,74,76,85`

**3.1.4 金额格式化调用点（`formatYuan` 48 处，节选关键）**

- `src/views/tenant/orders.vue:48` — `{{ formatYuanCompact(roomSummary.currentAmount) }}`
- `src/views/tenant/orders.vue:158` — `{{ formatYuan(room.amount) }}`
- `src/views/tenant/orders.vue:236-238` — `formatYuan(detailBill.roomFee.amount)` / `formatYuan(it.amount)` / `formatYuan(server.amount)`
- `src/views/tenant/orders.vue:312,315,318,321,325` — 账单明细与合计
- `src/views/tenant/orders.vue:327-328` — `<div v-if="bill.payableAmount != null" ...>{{ formatYuan(bill.payableAmount) }}`
- `src/views/tenant/orders.vue:330-333` — `formatYuan(bill.collected.cash)` / `.wallet` / `.points`
- `src/views/tenant/orders.vue:380,404` — 目录单价
- `src/views/tenant/orders.vue:434-435` — 收银应收
- `src/views/tenant/orders.vue:452-453` — `formatYuan(filledMinor)` / `formatYuan(payableMinor)`
- `src/views/tenant/orders.vue:1423` — `methodLabel(...) + ' ' + formatYuan(c.amount)`
- `src/views/tenant/payments.vue:27,46,69,70,290`
- `src/views/tenant/products.vue:38`、`src/views/tenant/inventory.vue:44`、`src/views/tenant/resources.vue:177,183`
- `src/views/tenant/ktv-config.vue:24,35,72,164`
- `src/views/tenant/members.vue:147`、`src/views/tenant/reservations.vue:78`、`src/views/tenant/reports.vue:118`、`src/views/tenant/shift.vue:24,27,30,33`、`src/views/platform/pricing-plans.vue:21`
- `src/utils/roomPricing.js:114,115` — 模板串内联

**3.1.5 币种的只读展示点（无任何可编辑入口）**
- `src/views/tenant/members.vue:110` — `label="币种" → {{ currencyText(wallet.currencyCode) }}`
- `src/views/tenant/orders.vue:309` — `<span>币种</span><b>{{ currencyText(bill.currencyCode) }}</b>`
- `src/views/tenant/stores.vue:26-28` — `<el-table-column label="币种" ...>{{ currencyText(row.defaultCurrency) }}`
- `src/utils/admin-copy.test.js:79` — `expect(source).toContain('currencyText(bill.currencyCode)')`（锁定该调用）

**3.1.6 守卫测试当前断言（改默认 USD 后的回归面）**

`src/constants/terms.test.js`（源码级守卫，命中即失败）：
- `:98-126` `RULES` 定义，关键 4 条：
  - `:106-110` `money-arithmetic`：`pattern: /[/\s]\/\s*100\b|[/\s]\*\s*100\b/`，message `'页内自行做分/元换算，应改走 utils/format 的 fenToYuan / yuanToFen'`
  - `:111-115` `currency-symbol`：`pattern: /¥/`，message `'页内硬编码货币符号，应改走 utils/format 的 formatYuan / formatYuanValue'`
  - `:116-120` `local-money-helper`：`pattern: /function\s+(fenToYuan|yuanToFen|yuanToMinor|minorToYuan|formatYuan)\s*\(|const\s+(fmtCents|fmtYuan)\s*=/`
  - `:121-125` `date-format`：`pattern: /toLocaleString\(|toLocaleTimeString\(|toLocaleDateString\(/`
- `:324-326` — `it('页面不再硬编码货币符号或自行做分/元换算', async () => { expect(await findOffenders(['views'], ['.vue', '.js'])).toEqual([]) })`
- `:162` — `expect(MONEY_UNIT).toBe('元')`
- `:163` — `expect(moneyColumnLabel('paidAmount')).toBe('已收（元）')`
- `:164` — `expect(withYuanUnit('售价')).toBe('售价（元）')`

`src/utils/format.test.js`：
- `:44` — `expect(formatYuan(12345)).toBe('¥ 123.45')`
- `:45` — `expect(formatYuan(0)).toBe('¥ 0.00')`
- `:48` — `it('空值返回占位符，避免出现 ¥ NaN', ...)`
- `:56` — `expect(formatYuanValue(123.45)).toBe('¥ 123.45')`
- `:62` — `expect(formatYuanCompact(123456789)).toBe('¥ 1,234,567.89')`
- `:63` — `expect(formatYuanCompact(12345)).toBe('¥ 123.45')`
- `:64` — `expect(formatYuanCompact(0)).toBe('¥ 0.00')`
- `:72-76` — `expect(fenToYuan(12345)).toBe(123.45)` 等
- `:80-84` — `expect(yuanToFen(123.45)).toBe(12345)` 等
- `:89` — `expect(yuanToFen(fenToYuan(minor))).toBe(minor)`
- `:96` — `expect(currencyText('CNY')).toBe('人民币（元）')`
- `:97` — `expect(currencyText('')).toBe('人民币（元）')` ← **默认币种断言，改 USD 后语义反转**
- `:101` — `expect(currencyText('USD')).toBe('USD')` ← **必须改为美元中文名**

`src/utils/wallet-amount-contract.test.js`：
- `:24` — `expect(yuanToFen(100)).toBe(10000)`
- `:25` — `expect(yuanToFen(123.45)).toBe(12345)`
- `:26` — `expect(fenToYuan(10000)).toBe(100)`
- `:27` — `expect(formatYuan(yuanToFen(123.45))).toBe('¥ 123.45')`
- `:28` — `expect(formatYuan(10000)).toBe('¥ 100.00')`
- `:34` — `expect(ktvConfig).toContain('amount: yuanToFen(')`
- `:35` — `expect(wallet).toContain('amount: yuanToFen(')`
- `:36` — `expect(ktvConfig).not.toMatch(/amount:\s*walletForm\.value\.amount\b/)`
- `:37` — `expect(wallet).not.toContain('amount: tokens')`
- `:43-46` — `expect(ktvConfig).toContain('minAmount: fenToYuan(')` / `('maxAmount: fenToYuan(')` / `('minAmount: yuanToFen(')` / `('maxAmount: yuanToFen(')`

`src/utils/inventory-purchase-price.test.js`：
- `:27` — `expect(formatYuan(yuanToFen(3.5))).toBe('¥ 3.50')`
- `:28` — `expect(formatYuan(350)).toBe('¥ 3.50')`
- `:32-34` — `expect(formatYuan(null)).toBe('—')` / `(undefined)` / `('')`
- `:49` — `expect(source).toContain("withYuanUnit('采购价')")`
- `:58` — `expect(source).toContain('purchasePrice: purchasePriceFen(purchasePriceYuan)')`
- `:60` — `expect(source).toContain('fenToYuan(row.purchasePrice)')`
- `:61` — `expect(source).toContain('formatYuan(row.purchasePrice)')`
- `:68` — `expect(source).not.toMatch(/¥/)`
- `:69` — `expect(source).not.toMatch(/\/\s*100\b/)`
- `:70` — `expect(source).not.toMatch(/\*\s*100\b/)`

`src/utils/roomPricing.test.js`：
- `:71` — `expect(text).toBe('基础房费 ¥ 300.00/小时 · 房型「豪华包」生效单价')`
- `:78` — `expect(text).toBe('基础房费 ¥ 100.00/小时 · 房型「小包」未定价，回退门店单价')`
- `:82` — `expect(roomBasePriceText(resolveRoomPrice({ plan: storePlan }))).toBe('基础房费 ¥ 100.00/小时')`
- `:87` — `expect(BILLING_UNIT_LABEL.HOUR).toBe('/小时')`
- `:88` — `expect(roomBasePriceText({ unitPrice: 10000, billingUnit: 'HALF_HOUR' })).toBe('基础房费 ¥ 100.00/半小时')`
- `:94-96` — `expect(yuanToFen(150)).toBe(15000)` / `(99.99)).toBe(9999)` / `(0)).toBe(0)`
- `:100-101` — `expect(fenToYuan(15000)).toBe(150)` / `(null)).toBe(0)`

`src/utils/resource-room-type.test.js`：
- `:48` — `expect(source).toContain('unitPrice: yuanToFen(draft.unitPriceYuan)')`
- `:49` — `expect(source).toContain('serverUnitPrice: yuanToFen(draft.serverUnitPriceYuan)')`
- `:50` — `expect(source).toContain('fenToYuan(row.unitPrice)')`
- `:51` — `expect(source).toContain('formatYuan(row.unitPrice)')`
- `:52` — `expect(source).toContain('withYuanUnit')`
- `:54` — `expect(source).not.toMatch(/\/\s*100\b|\*\s*100\b/)`
- `:103` — `expect(source).not.toMatch(/\/\s*100\b|\*\s*100\b/)`（`orders.vue`）

`src/utils/product-material-autofill.test.js`：
- `:48` / `:72` — `salePriceYuan: 3.5`（采购价 350 分 → 售价 3.5 元）
- `:83` — `expect(materialAutofillKeptHint(kept)).toBe('已带出仓库商品信息，保留你手动填写的：名称、售价（元）')`

`src/utils/adminErrorMessage.test.js`：
- `:32` — `expect(/[A-Za-z]/.test(message.replace(/HTTP|CNY/g, ''))).toBe(false)`

### 3.2 `gv_saas_mobile`（Vue3，B 端 + C 端；`c-end` 与 `src/` 两套物理隔离的实现）

**3.2.1 两套平行的金额实现（函数同名、返回类型不同、符号表各存一份）**

B 端 `src/shared/utils/amount.js`：
| 行号 | 函数 | 逻辑 |
|---|---|---|
| `:4` | `CURRENCY_SYMBOLS` | `{ CNY: '¥', USD: '$', HKD: 'HK$', EUR: '€' }` |
| `:7-12` | `fenToYuan(v)` | `(n / 100).toFixed(2)`（`:11`），返回**字符串** |
| `:15-18` | `minorToYuan(v)` | `n / 100`（`:17`） |
| `:21-25` | `yuanToFen(v)` | `Math.round(n * 100)`（`:24`） |
| `:28-33` | `formatMoney(v, currencyCode = 'CNY')` | `:28` 默认参数写死 CNY；`:31` `const symbol = CURRENCY_SYMBOLS[currencyCode] \|\| currencyCode`；`:32` ``return `${text.startsWith('-') ? '-' : ''}${symbol} ${text.replace('-', '')}` `` |
| `:35-38` | `fmtTime` | 时间（非金额） |

C 端 `c-end/saas.js`：
| 行号 | 函数 | 逻辑 |
|---|---|---|
| `:107-108` | 口径注释 | `// —— 金额口径：后端金额一律最小货币单位「分」…前端展示一律「元」，且只允许走本文件这四个函数，不得再散落 /100、*100。——` |
| `:110-114` | `fenToYuan(v)` | `n / 100`（`:113`），返回**数字**（与 B 端同名函数返回字符串不一致） |
| `:117-120` | `yuanToFen(v)` | `Math.round(n * 100)`（`:119`） |
| `:122` | `CURRENCY_SYMBOLS` | 与 B 端完全重复 |
| `:125-131` | `formatAmount(value, currencyCode)` | `:128` `var code = currencyCode \|\| 'CNY';`；`:130` `return sign + (CURRENCY_SYMBOLS[code] \|\| code) + ' ' + Math.abs(n).toFixed(2);` |
| `:134-136` | `formatFen(v, currencyCode)` | `return formatAmount(fenToYuan(v), currencyCode);` |
| `:139-141` | `formatYuan(v, currencyCode)` | `return formatAmount(v, currencyCode);` |
| `:229` | `formatKtvRoomPrice` | `return formatFen(unitPrice) + '/' + ktvBillingUnitLabel(pricing.billingUnit);` ← **未传币种** |
| `:253-254` | `formatKtvRoomPriceBreakdown` | `'房型 ' + formatFen(room) + unit + ' + 服务 ' + formatFen(server) + unit + ' = ' + formatFen(combined) + unit` ← **三次均未传币种** |
| `:265` | `estimateKtvRoomFee` | `return Math.round(unitPrice * duration);` |
| `:409-413` | 导出注释 | `/** 金额口径：后端一律「分」，展示一律「元」，全端只允许走这四个函数。 */` |

**3.2.2 B 端调用点（`formatMoney` 20 处，**全部单参数 → 恒为 CNY**）**
- `src/b-end/views/Orders.vue:45,46,50,51,55,57,58,111,112,126,136,155,156,158,162,176,187,322,534`
- `src/b-end/views/Reservations.vue:84` — `return formatMoney(pricing.roomUnitPrice) + '/' + unit`
- 导入：`src/b-end/views/Orders.vue:203`、`src/b-end/views/Reservations.vue:57`
- `yuanToFen` / `minorToYuan` 调用：`src/b-end/views/Orders.vue:442,484,498,500,501,530`

**3.2.3 C 端调用点（币种传参不完整）**
- 传了币种：`c-end/app.js:839`（`o.currencyCode`）、`:887`（`it.currencyCode`）、`:932`、`:966`（`bill.currencyCode`，取值于 `:940`）
- **未传币种**：`c-end/app.js:200`、`:222`、`:247`、`:463`、`:665`、`:783`、`:906`
- 元口径 mock 渲染器：`c-end/app.js:37` `mockMoney` → `SAAS.formatYuan`；调用点 `:545,560,571,583,594,626,628,636,637,645,646,687,689,693,705,713,721,724,731,738`
- 分口径：`c-end/app.js:39` `minorMoney` → `SAAS.formatFen`

**3.2.4 硬编码币种写请求体（改造必漏点）**
- `src/b-end/views/Orders.vue:391` — `const order = await createOrder({ businessType: 'KTV', currencyCode: 'CNY', resourceId: createForm.value.resourceId })`
- `src/b-end/views/Orders.vue:546` — `currencyCode: 'CNY',`（`collect` 请求体）
- `c-end/saas.js:675` — `currencyCode: 'CNY',`（`collectPayment` 请求体）

**3.2.5 用户可见的单位标签**
- `src/b-end/views/Orders.vue:132` — `{{ methodLabel(method.method) }}（元）` ← **收银逐笔输入框的单位标签写死「元」，与符号无关，改币种必须改**

**3.2.6 硬编码符号统计**

| pattern | 命中 | 分布 |
|---|---|---|
| `¥` | **77**（文本，排除图片二进制） | `c-end/app.js` 10、`c-end/ktv-pricing.test.js` 31、`c-end/money.test.js` 8、`c-end/saas.js` 8、`docs/b-end-ui-design.html` 13、`src/shared/utils/amount.js` 2、`src/shared/utils/amount.test.js` 5 |
| `元` | **28** | 含用户可见的 `src/b-end/views/Orders.vue:132`、`README.md:37` |
| `￥` / `RMB` / `人民币` / `币种` | **0** | — |
| `USD` | **2** | `src/shared/utils/amount.js:4`、`c-end/saas.js:122`（仅符号表） |
| `CNY` | **19** | `src/shared/utils/amount.js:4,28`；`c-end/saas.js:122,128,157,181,501,675`；`c-end/app.js:940`；`src/b-end/preview/saas.js:112,113,254`；`src/b-end/views/Orders.vue:391,546` |
| `元/小时` / `元/㎡` | **0**（实际形式是 `¥X/小时`、`/半小时`、`/套餐`） | `c-end/saas.js:208-212`、`src/b-end/views/Reservations.vue:83` |

**3.2.7 门店币种已下发但未参与计算**
- `src/b-end/views/Stores.vue:7` — `<div class="card-sub">{{ s.businessType || '' }} · {{ s.defaultCurrency || '' }} · {{ s.status || '' }}</div>`
- `src/b-end/preview/saas.js:112-113` — `defaultCurrency: 'CNY'`
- `src/shared/api/request.js:30` — `config.headers['Accept-Language'] = 'zh'`（**唯一的区域声明，无币种声明**）

**3.2.8 守卫测试当前断言（改默认 USD 后的回归面）**

`src/shared/utils/amount.test.js`：
- `:6` — `expect(fenToYuan(12345)).toBe('123.45')`
- `:7` — `expect(fenToYuan(0)).toBe('0.00')`
- `:8` — `expect(fenToYuan(null)).toBe('—')`
- `:10` — `expect(minorToYuan(12345)).toBe(123.45)`
- `:11` — `expect(minorToYuan('2500')).toBe(25)`
- `:14` — `expect(yuanToFen('12.34')).toBe(1234)`
- `:15` — `expect(yuanToFen(0.1)).toBe(10)`
- `:18` — `expect(formatMoney(12345)).toBe('¥ 123.45')` ← **必挂**
- `:19` — `expect(formatMoney(0)).toBe('¥ 0.00')`
- `:20` — `expect(formatMoney(888000)).toBe('¥ 8880.00')`
- `:21` — `expect(formatMoney(-500)).toBe('-¥ 5.00')`
- `:22` — `expect(formatMoney(null)).toBe('—')`
- `:4` — 用例名 `test('金额尺度：分↔元换算与展示统一为「¥ 12.00」', ...)`

`c-end/money.test.js`：
- `:33` — `expect(saas.fenToYuan('888000')).toBe(8880)`
- `:34` — `expect(saas.fenToYuan(12800)).toBe(128)`
- `:37` — `expect(saas.yuanToFen('12.34')).toBe(1234)`
- `:38` — `expect(saas.yuanToFen('0.1')).toBe(10)`
- `:39` — `// 展示：¥ 与数字之间一个空格 + 两位小数`
- `:40` — `expect(saas.formatFen(12345)).toBe('¥ 123.45')` ← **必挂**
- `:41` — `expect(saas.formatFen('888000')).toBe('¥ 8880.00')`
- `:42` — `expect(saas.formatFen(0)).toBe('¥ 0.00')`
- `:43` — `expect(saas.formatFen(-500)).toBe('-¥ 5.00')`
- `:45` — `expect(saas.formatYuan(368)).toBe('¥ 368.00')`
- `:55` — `expect(wallet.availableAmount).toBe('888000')`
- `:56` — `expect(saas.formatFen(wallet.availableAmount)).toBe('¥ 8880.00')`
- `:57` — `// 8880 元是演示值；若 mock 写成元形状（8880），展示会变成 ¥ 88.80，正是被掩盖的 100 倍错误`
- `:58` — `expect(saas.fenToYuan(wallet.availableAmount)).toBe(8880)`
- `:65` — `expect(source).toContain('minorMoney(it.totalAmount')`
- `:66` — `expect(source).toContain('minorMoney(estimate)')`
- `:67-70` — `expect(source).not.toContain('mockMoney(it.totalAmount')` / `('mockMoney(o.totalAmount')` / `('mockMoney(bill.')` / `('mockMoney(estimate)')`
- `:72` — `expect(source).not.toMatch(/\/\s*100|\*\s*100/)` ← **会锁死任何在 `c-end/app.js` 内新增 /100 或 *100 的写法**
- `:74` — `expect(source).not.toMatch(/\.toISOString\(/)`

`c-end/ktv-pricing.test.js`（约 24 条断言必挂）：
- `:22` — `displayText: '¥100.00/小时 · 30 分钟递增 · 每 30 分钟 ¥50.00 · 标准 2.0 小时',`
- `:40` — `displayText: \`¥${(price / 100).toFixed(2)}/小时 · 房型「${ROOM_TYPE_NAME[code]}」生效单价\`,`
- `:53` — `displayText: '¥238.00/小时（房型 ¥188.00 + 服务 ¥50.00）· 30 分钟递增 · 标准 2.0 小时',`
- `:82` — `expect(saas.formatKtvRoomPriceLabel(pricing[1004])).toBe('¥ 188.00/小时')`
- `:83` — `expect(saas.formatKtvRoomPriceLabel(pricing[1005])).toBe('¥ 288.00/小时')`
- `:85` — `expect(saas.formatKtvRoomPriceLabel(pricing[1003])).toBe('¥ 100.00/小时 · 门店统一价')`
- `:110` — `expect(saas.formatKtvRoomPrice(pricing[1104])).toBe('¥ 188.00/小时')`
- `:111` — `expect(saas.formatKtvRoomPrice(pricing[1105])).toBe('¥ 288.00/小时')`
- `:116` — `expect(saas.formatKtvRoomPrice(again[1204])).toBe('¥ 188.00/小时')`
- `:132-133` — `expect(saas.formatKtvRoomPrice(pricing[2001])).toBe('¥ 188.00/小时')` / `(pricing[2002])).toBe('¥ 288.00/小时')`
- `:140-143` — `expect(...).toBe('¥ 128.00/小时')` / `'¥ 188.00/小时'` / `'¥ 128.00/半小时'` / `'¥ 1280.00/套餐'`
- `:144-145` — `expect(...).toBe('')`
- `:148-151` — `expect(saas.formatKtvRoomPriceLabel({ roomUnitPrice: 10000, ... })).toBe('¥ 100.00/小时 · 门店统一价')` / `(18800, ...)).toBe('¥ 188.00/小时')`
- `:162` — `expect(saas.formatKtvRoomPrice(zeroServer)).toBe('¥ 188.00/小时')`
- `:163` — `expect(saas.formatKtvRoomPriceBreakdown(zeroServer)).toBe('')`
- `:164` — `expect(saas.formatKtvRoomPriceLabel(zeroServer)).not.toContain('+')`
- `:184-185` — `expect(saas.formatKtvRoomPrice(pricing[3004])).toBe('¥ 238.00/小时')` / `formatKtvRoomPriceLabel(...)).toBe('¥ 238.00/小时')`
- `:196` — `expect(saas.formatKtvRoomPriceBreakdown({...})).toBe('房型 ¥ 188.00/小时 + 服务 ¥ 50.00/小时 = ¥ 238.00/小时')`
- `:199` — `expect(saas.formatKtvRoomPriceBreakdown({...})).toBe('房型 ¥ 100.00/半小时 + 服务 ¥ 25.00/半小时 = ¥ 125.00/半小时')`
- `:201-202` — `expect(...).toBe('')`
- `:210` — `expect(saas.estimateKtvRoomFee(pricing, 3)).toBe(71400)`
- `:211` — `expect(saas.estimateKtvRoomFee(pricing, 1.5)).toBe(35700)`
- `:213` — `expect(saas.estimateKtvRoomFee({ roomUnitPrice: 18800, combinedUnitPrice: 18800 }, 2)).toBe(37600)`
- `:229` — `expect(source).not.toContain('Math.round(unitPrice * hours)')`
- `:241-242` — `expect(saas.formatKtvRoomPrice(pricing[4001])).toBe('¥ 238.00/小时')` / `expect(saas.formatKtvRoomPriceBreakdown(pricing[4001])).toBe('房型 ¥ 188.00/小时 + 服务 ¥ 50.00/小时 = ¥ 238.00/小时')`
- `:253` — `expect(saas.formatKtvRoomPriceLabel(pricing[1004])).toBe('')`
- `:273` — `expect(saas.formatKtvRoomPrice(pricing[1004])).toBe('¥ 188.00/小时')`

其他：`c-end/context.test.js:13-17` — 锁死「C 端加项页面不得暴露自由价格输入」（`not.toContain('id="item-price"')` / `not.toContain('自定义加项')`）。

### 3.3 `gv_chat_app`（Flutter，**已有正确的多币种参考实现**）

**3.3.1 参考实现：`lib/models/ktv_models.dart`**

| 行号 | 内容 |
|---|---|
| `:4-15` | 文件头契约：`金额在响应里以「最小货币单位整数」的字符串形式返回（如 CNY 的 100 分 = "100"），同时返回 currency 币种。客户端仅做「最小单位 -> 展示字符串」的格式化，不做任何金额运算。` |
| `:38-46` | `const Map<String, String> _currencySymbols = { 'CNY': '¥', 'JPY': '¥', 'KRW': '₩', 'USD': '\u0024', 'EUR': '€', 'HKD': 'HK\u0024' };` |
| `:48-52` | `const Map<String, int> _currencyDecimals = { 'JPY': 0, 'KRW': 0 };` |
| `:55-73` | `class KtvMoney { final int minorUnits; final String currency; ... String get formatted => formatKtvAmount(minorUnits, currency); }` |
| `:75-96` | `String formatKtvAmount(int minorUnits, String currency)` — 符号 + 小数位 + 千分位，`decimals <= 0` 时不输出小数（`:85-87`） |
| `:106-110` | `_withThousandsSeparator(int value)` |

**3.3.2 已建模的金额字段（全部通过 `KtvMoney.parse(json[...], currency)` 带上币种）**
- `:207-208` — `totalAmount` / `paidAmount`
- `:295,297` — `unitPrice` / `amount`
- `:315` — `amount`
- `:358-364` — `subtotalAmount` / `discountAmount` / `taxAmount` / `totalAmount` / `paidAmount` / `paidByMethod` / `changeAmount`（**含找零字段**）
- `:398` — `balance`（钱包）
- `:423,429` — `remainingAmount` / 明细 `amount`

**3.3.3 消费方（B 端 KTV 业务页，全部 import `ktv_models.dart`）**
- `lib/app_router.dart`、`lib/repositories/business/ktv_api_client.dart`
- `lib/screens/business/ktv_cashier_screen.dart`（`:26` `KtvBill? _bill;`）
- `lib/screens/business/ktv_settle_screen.dart`（`:25`、`:134` `_billCard`）
- `lib/screens/business/ktv_shift_screen.dart`
- `lib/screens/business/ktv_dashboard_screen.dart`、`ktv_quick_open_screen.dart`、`ktv_timing_screen.dart`

**3.3.4 绕过币种小数的本地实现（改造点，共 3 份平行实现）**
- `lib/screens/business/ktv_cashier_screen.dart:206-213` — `String _plainAmount(KtvMoney m)` 使用 `_decimalsOf(m.currency)`（**币种感知，正确**）
- `lib/screens/business/ktv_cashier_screen.dart:177-180` — `int _decimalsOf(String currency) { final c = currency.toUpperCase(); return (c == 'JPY' || c == 'KRW') ? 0 : 2; }` ← **与 `lib/models/ktv_models.dart:48-52` 的 `_currencyDecimals` 重复定义**，两处口径已存在分叉（表里只声明 `JPY/KRW`，函数里也含 `JPY/KRW`，机制上是双写）
- `lib/screens/business/ktv_cashier_screen.dart:182-188` — `_pow10(int n)` ← 与 `lib/models/ktv_models.dart:98-104` 的 `_pow10` **重复定义**
- `lib/screens/business/ktv_cashier_screen.dart:190-204` — `_toMinor(String text, String currency)` 输入换算（带 currency，**正确**）
- `lib/screens/business/ktv_shift_screen.dart:55-59` — `String _plain(KtvMoney m) { final major = m.minorUnits ~/ 100; final frac = (m.minorUnits % 100).toString().padLeft(2, '0'); return major.toString() + '.' + frac; }` ← **写死 /100 与 2 位小数，完全忽略 `m.currency`**
- `lib/screens/business/ktv_shift_screen.dart:43-53` — `int _toMinor(String text) { ... return major * 100 + minor; }` ← **写死 ×100，无 currency 参数**
- `lib/screens/business/ktv_shift_screen.dart:69` — `openingCash: _toMinor(_openingCash.text).toString()`

**3.3.4b 币种以裸 ASCII 码展示给用户（应展示符号/中文名）**
- `lib/screens/business/ktv_cashier_screen.dart:343` — `Text('已收 ' + bill.paidAmount.formatted + ' · 币种 ' + bill.currency,` ← 用户看到 `CNY` 而非「人民币」
- `lib/screens/business/ktv_settle_screen.dart:173` — `Text('账单快照（' + bill.currency + '）',`

**3.3.4c 金额格式化调用点（共 12 处 `.formatted`，全部集中在此）**
- `lib/screens/business/ktv_cashier_screen.dart:158` — `: '，找零 ' + result.changeAmount.formatted;`
- `lib/screens/business/ktv_cashier_screen.dart:161` — `content: Text('收款成功，剩余应收 ' + result.remainingAmount.formatted + change,`
- `lib/screens/business/ktv_cashier_screen.dart:301`、`:340`、`:343`、`:372`
- `lib/screens/business/ktv_settle_screen.dart:139,143,146,149,152,154,157` — `_row(...bill.roomFee.amount.formatted...)` / `_row('应收合计', bill.totalAmount.formatted, ...)` / `_row('找零', bill.changeAmount.formatted, secondary)`
- `lib/screens/business/ktv_shift_screen.dart:229`、`:261-265`（`备用金/应缴现金/实收现金/差额`）
- `lib/screens/business/ktv_timing_screen.dart:322` — `Text(order.totalAmount.formatted,`

**3.3.4d 收款上行不带币种（服务端只能从订单推断）**
- `lib/repositories/business/ktv_api_client.dart:695-696` — `Map<String, dynamic> ktvPaymentEntry({required String method, required int amount}) => {'method': method, 'amount': amount};` ← **只有 amount，无 currency**
- 对照 `lib/repositories/business/ktv_api_client.dart:333-344` — `if (currency != null && currency.isNotEmpty) 'currency': currency,` ← `availableMethods` 支持传 currency，`collect` 不支持 → **两个接口口径不对称**
- `lib/repositories/business/ktv_api_client.dart:694` — `/// 组合收款单笔拆分入参构造（method 取值 CASH/WALLET/POINT，amount 为最小单位）。`

**3.3.4e Mock 数据把 CNY 写死（20 处，会掩盖默认 USD 的改造缺失）**
- `lib/repositories/business/ktv_api_client.dart:541,542,543` — `totalAmount: const KtvMoney(minorUnits: 32400, currency: 'CNY'), paidAmount: ..., currency: 'CNY',`
- `lib/repositories/business/ktv_api_client.dart:559,560,561` — Mock 订单列表
- `lib/repositories/business/ktv_api_client.dart:590` — `KtvBill _mockBill({required String orderId}) { const currency = 'CNY';`
- `lib/repositories/business/ktv_api_client.dart:641,647,653` — Mock 支付方式余额（现金/A380币/积分）
- `lib/repositories/business/ktv_api_client.dart:664,668,669,671` — Mock 收款结果（含 `changeAmount`）
- `lib/repositories/business/ktv_api_client.dart:679-685` — Mock 班次（`openingCash/expectedCash/...`）
- 后果：`KtvApiClient` 有 `_guard(..., mock:)` 回退路径，**无后端环境时全链路显示 `¥`，币种切换在 mock 下永远「看起来正常」**

**3.3.4f 字段名把「元」固化进模型**
- `lib/screens/mock_venue_flow_screens.dart:1659` — `this.priceYuan = 0,`
- `lib/screens/mock_venue_flow_screens.dart:1665` — `final int priceYuan;`
- 引用点：`lib/screens/mock_venue_flow_screens.dart:290,1615,1659,1665`

**3.3.4g 已存在但完全未接线的现金/资产能力（不属本次业务面，但不要混淆）**
- `lib/services/generated_im_api_client.dart:494-516` — `@GET('/points/balance')` / `@GET('/coins/info')` / `@GET('/coins/balance')` / `@GET('/coins/ledger')`（**无任何 UI/provider 调用**）
- `packages/gv_core/lib/src/models/points_models.dart:23,30-31,44` — `PointsLedgerItem` / `int amount` / `int balanceAfter` / `PointsLedgerPage`（**只被自身与 `gv_core.dart:15` 的 export 引用**）
- `lib/l10n/app_en.arb:436-488` — `pointsMyPoints` / `pointsCurrentTotal` / `pointsBalanceAfter` / `coinDefaultName` / `coinCurrentTotal` / `coinBalanceAfter` / `servicesPointsBalance`（**全部为无调用点的死键**）
- 结论：**积分/代币/预约三套 API 与 l10n 已存在但完全未接线**，不应计入现有业务面；且**积分（`POINT`）是支付方式之一**，必须排除在币种格式化之外 —— `lib/models/ktv_models.dart:406-408` — `bool get isCash => method == 'CASH'; bool get isWallet => method == 'WALLET'; bool get isPoint => method == 'POINT';`

**3.3.5 i18n 里硬编码 `¥`（l10n 层，改币种必改）**
- `lib/l10n/app_zh.arb:527` — `"serviceDemoCouponAmount": "¥{amount}"`
- `lib/l10n/app_zh.arb:566` — `"serviceBookingPrice": "¥{price}"`
- `lib/l10n/app_zh.arb:568` — `"serviceBookingCashPrice": "现金 ¥{price}"`
- `lib/l10n/app_zh.arb:949` — `"reservationCurrency": "¥{amount}"`
- `lib/l10n/app_en.arb:527` — `"serviceDemoCouponAmount": "¥{amount}"`
- `lib/l10n/app_en.arb:545` — `"serviceDemoCouponThreshold": "Valid on ¥{amount}+"`
- `lib/l10n/app_en.arb:566` — `"serviceBookingPrice": "¥{price}"`
- `lib/l10n/app_en.arb:568` — `"serviceBookingCashPrice": "Cash ¥{price}"`
- `lib/l10n/app_en.arb:949` — `"reservationCurrency": "¥{amount}"`
- 生成产物（改造时需重新 `flutter gen-l10n`，勿手改但须纳入检查）：
  - `lib/l10n/app_localizations_zh.dart:1282` — `return '¥$amount';`
  - `lib/l10n/app_localizations_zh.dart:1391` — `return '¥$price';`
  - `lib/l10n/app_localizations_zh.dart:1396` — `return '现金 ¥$price';`
  - `lib/l10n/app_localizations_zh.dart:2419` — `return '¥$amount';`
  - `lib/l10n/app_localizations_en.dart:1316` — `return '¥$amount';`
  - `lib/l10n/app_localizations_en.dart:1367` — `return 'Valid on ¥$amount+';`
  - `lib/l10n/app_localizations_en.dart:1425` — `return '¥$price';`
  - `lib/l10n/app_localizations_en.dart:1430` — `return 'Cash ¥$price';`
  - `lib/l10n/app_localizations_en.dart:2493` — `return '¥$amount';`
  - `lib/l10n/app_localizations.dart:2498` / `:2588` / `:2684` / `:2690` / `:4664` — 文档注释里的 `¥`

**3.3.6 演示数据里硬编码 `¥`**
- `lib/screens/mock_travel_flow_screens.dart:14-15` — `String _travelPriceLabel(AppLocalizations l10n, int price) => price == 0 ? l10n.serviceDemoFree : '¥$price';`（调用点 `:684,881,970,1082,1181`）
- `lib/screens/mock_travel_flow_screens.dart:467,477,487,497,509,519,529` — `price: 0` 演示数据

**3.3.7 守卫测试**
- `test/screens/mock_booking_flow_test.dart:24` — `expect(find.textContaining('¥'), findsNothing);`（上下文 `:21-25`：`expect(find.text('房型与价格'), findsOneWidget); ... expect(find.textContaining('¥'), findsNothing); expect(find.textContaining('积分兑换'), findsNothing);`）
- `test/screens/mock_booking_flow_test.dart:51` — 同上
- `test/screens/mock_travel_flow_test.dart:24` — 同上（上下文 `:22-24`：`expect(find.text('航班列表'), findsOneWidget); expect(find.text('免费'), findsWidgets); expect(find.textContaining('¥'), findsNothing);`）
- `test/screens/mock_travel_flow_test.dart:59` — 同上

**3.3.8 `gv_chat_app` 测试覆盖缺口（重要）**
- `formatKtvAmount` / `KtvMoney` / `minorUnits` 在 `test/` + `integration_test/` **零覆盖**（搜索仅命中上述 4 条 `¥` 断言）。
- `lib/screens/business/` 的 `_toMinor` / `_plainAmount` / `_plain` **全部无单元测试**。
- 唯一 msgType 断言：`integration_test/reviewer_flow_test.dart:184` — `widget.msg.msgType == 'text' &&`（无金额）。
- 其他相关但无金额：`test/screens/mock_service_screen_test.dart:36,85`（`find.text('Free')`）、`:42`（`find.text('Confirm demo')`）、`test/screens/mock_travel_flow_test.dart:64`（`tester.tap(find.textContaining('订 · 免费'))`）。

### 3.4 `gv_chat_admin`（Vue3 IM 运营后台）—— **零金额代码**

`git grep -c` 在 `src/`（60 个跟踪文件）全部为 0：
- `amount` / `price` / `money` / `balance` / `wallet` / `recharge` / `billing` / `fee` / `cost` / `plan` / `subscri` / `integral` / `score` / `currency` / `invoice` → **0**
- 中文 `套餐` / `计费` / `余额` / `充值` / `金额` / `支付` / `价格` / `币种` / `货币` / `人民币` / `美元` → **0**
- `¥` / `￥` / `CNY` / `RMB` / `USD` → **0**
- `formatMoney` / `formatAmount` / `fenToYuan` / `Intl.NumberFormat` → **0**
- `元` → `src/` **0**；全仓 3 处全是「单元 / 统一」，非货币：`docs/FRONTEND_CONVENTIONS.md:66,81,93`
- `order` 81 行 = `border` 64 + `sortOrder` 16 + `orderNo` 1；`pay` 13 行全是 `payload`
- **不存在 `src/constants/` 目录**，无任何金额/币种常量
- 无任何测试（`package.json` scripts 仅 `dev / dev:force / build / validate:error-messages / preview`，无 vitest/jest）

唯一与「订单」沾边的残留（**被注释掉的死代码**）：
- `src/layout/AdminLayout.vue:158` — `/* realtime notifications are intentionally not enabled for the REST-only admin v1 client */`
- `src/layout/AdminLayout.vue:163` — `socket.on('reservation:created', (payload) => {`
- `src/layout/AdminLayout.vue:169` — `onClick: () => router.push({ path: '/reservations/orders', query: { orderNo: payload.orderNo } }),`（**`/reservations/orders` 路由不存在**）
- `src/router/index.js`（19 条路由）中无 `/reservations/orders`

> **结论：`gv_chat_admin` 是纯新增（greenfield），不存在「把 ¥ 改成 $」的替换面。** 若产品预期「IM 后台也能看到/切换币种」，需先确认该后台是否应当承载资金域。

### 3.5 `gv_chat_desktop`（Electron + TS）—— **零金额代码**

`git grep -c` 在 `src/`（84 个跟踪文件）：
- `amount` / `Amount` / `price` / `Price` / `money` / `Money` / `balance` / `wallet` / `redPacket` / `红包` / `Order` / `currency` → **全部 0**
- `formatMoney` / `formatCurrency` / `formatAmount` / `toFixed` / `Intl.NumberFormat` / `minorUnits` / `fen` → **全部 0**
- `¥` / `￥` / `元` / `CNY` / `RMB` / `USD` → **0**（`¥` 仅 `src/renderer/src/assets/wv-chat-icon.png` 二进制误命中）
- `$` 作为货币：用 `\$[0-9]` 精确匹配 → **0**（`$` 全为 TS 模板插值 `${...}`）
- `recharge` / `topup` / `top-up` / `shop` / `mall` / `coupon` / `coin` / `points` / `vip` / `purchase` / `bill` / `invoice` → **0**（`fee` 1 条是 `BlacklistView.vue:48` 的 `feedback`）
- `order` 21 个文件命中全为 SQL `ORDER BY`：`src/main/db/database.ts:100`（`ORDER BY`）、`:124`（`... ORDER BY pinned DESC, last_time DESC`）等（`git grep -F "order" | Where-Object { $_ -notmatch 'border' }` → 0 条）
- `member` 277 条命中全为**群成员**：`src/renderer/src/components/GroupInfoDialog.vue:19`（`v-for="member in displayedMembers"`）、`ChatHistoryDialog.vue:117`（`group.members.find(...)`）
- `card` 17 个文件均为 UI 卡片容器
- 唯一 `Intl` 用法与金额无关：`src/renderer/src/components/ChatHistoryDialog.vue:122` — `return new Intl.DateTimeFormat(locale.value === 'zh-CN' ? 'zh-CN' : 'en', {`

消息模型里也**没有金额/币种字段**：
- `src/renderer/src/models/message.ts:28-46` — `export interface MessageResponse { id: string; msgId: string; ... msgType: MsgType; content: string; ... }`
- `src/shared/db.ts:23-41` — `export interface MessageRow { scopeId: string; msgId: string; ... msgType: MsgType; content: string; timestamp: number; ... }`
- `src/renderer/src/models/content.ts:5-28` — 全部 content 结构（`ImageContent` / `FileContent` / `NamecardContent` / `CallTraceDisplay`）**均无金额**

**消息类型是封闭 TS 联合类型（11 种，新增类型需同步 4 处）**：
- `src/renderer/src/models/message.ts:3-14` — `export type MsgType = 'text' | 'image' | 'file' | 'voice' | 'video' | 'location' | 'namecard' | 'call' | 'system' | 'recall' | 'emoji'`
- `src/shared/db.ts:4-6` — 同一份清单的副本（**主进程/渲染进程共享，`MessageRow.msgType: MsgType` 强类型，漏改会导致 SQLite 落库失败**）
- `src/renderer/src/components/ChatPanel.vue:67,80,94,97,102` — 渲染分支（`image/video/voice/file/call`）
- `src/renderer/src/models/content.ts:107-127` — `messagePreview(msgType, content)` 的 switch（含 `default: return content`）；**注意 `location` 在 union 里但 `messagePreview` 无对应分支**
- 附注：`src/renderer/src/components/ChatPanel.vue:129,134,1114,1123` 的 `transfer-state` 是**消息发送状态样式类**（`sending`/`failed`），与「转账」无关。

> **结论：`gv_chat_desktop` 无任何金额改造面**，也没有可复用的 formatter；若未来加订单卡片/红包消息，桌面端是**封闭 union**，必须同步改上面 4 处。

### 3.6 前端证据统计

| 前端 | 证据条数 | 说明 |
|---|---|---|
| `gv_saas_admin` | **约 190 条** | 格式化函数 6 + 调用点 48 + 常量 10 + 硬编码符号 34 +「元」标签 14 + 守卫断言 41 + 接口字段 30 + 只读币种 3 + 风险 12 |
| `gv_saas_mobile` | **约 150 条** | B 端 6 函数 + 20 调用点；C 端 9 函数 + 8 调用点 + 22 mock 调用点；符号 77+28+19+2+2；守卫断言 45+ |
| `gv_chat_app` | **约 105 条** | 参考实现 8 + 建模字段 10 + 消费方 7 + 12 处 `.formatted` 调用 + 3 份平行换算实现 7 + 裸币种码 2 + i18n 20 + mock CNY 20 + 死键 8 + 字段名固化 3 + 守卫 4 + 覆盖缺口 6 |
| `gv_chat_desktop` | **约 12 条（负向结论）** | 逐 pattern 计数为 0（12 个 pattern）+ MsgType 4 处同步点 + DB schema |
| `gv_chat_admin` | **约 8 条（负向结论）** | 逐 pattern 计数为 0 + 4 条死代码残留证据 |

---

## 4. 业务场景清单：会产生或展示现金的完整链路

> 每条链路格式：**触发界面 → 接口 → 落库字段 → 展示位置 → 二次消费点（打印/导出/对账/退款/日结）**。

### S1. 开台（建单）
- **触发界面**：`gv_saas_admin` 房态看板 / `gv_chat_app` `ktv_quick_open_screen.dart` / `gv_saas_mobile` B 端开台
  - `gv_saas_mobile`：`src/b-end/views/Orders.vue:391` — `createOrder({ businessType: 'KTV', currencyCode: 'CNY', resourceId: ... })` ← **币种硬编码**
  - `gv_saas_admin`：`src/views/tenant/orders.vue:1098` — `currencyCode: 'CNY',`
- **接口**：`POST /api/v1/business/orders` → `OrderController.CreateOrderRequest`（`platform-services/order/.../api/controller/OrderController.java:295`）
- **落库**：`ord_order.currency_code` + 6 个金额字段置 0（`:120-126`）
- **另一条路径（预约到店开台，币种**硬编码**在服务端）**：`POST /admin/reservations/{id}/open-table` → `ReservationApplicationService.openTable` → `order.setCurrencyCode("CNY")`（`platform-services/order/.../application/ReservationApplicationService.java:174`）
- **展示**：订单列表 / 房态卡片（`gv_saas_admin/src/views/tenant/orders.vue:158`、`:830-832` `orderAmount()`）
- **二次消费点**：无（开台不产生金额），但 `currency_code` 一旦落库即**永久决定该单的展示币种**（§6.3 快照固化讨论）

### S2. 点单 / 加项
- **触发界面**：B 端点单页 `gv_saas_mobile/src/b-end/views/Orders.vue:187`；C 端自助加项 `c-end/app.js:906`；`gv_chat_app` `ktv_timing_screen.dart`
- **接口**：`POST /business/orders/{orderId}/items` → `AddItemRequest`（`.../api/controller/OrderItemController.java:311`）
- **落库**：`ord_order_item.unit_price / quantity / total_amount`（`:130,131,134`）；**服务端用目录价覆盖客户端传值**（`:95-97,115-116`）
- **展示**：`gv_saas_admin/src/views/tenant/orders.vue:237,315,348`；`gv_saas_mobile/src/b-end/views/Orders.vue:50,51,162,176`；`c-end/app.js:887`
- **二次消费点**：结算 `OrderAmountApplicationService.recalculate`（`.../application/OrderAmountApplicationService.java:58-70`）会把它汇总进订单金额 → 进而影响收银/账单/报表

### S3. 包厢计价（房型单价 + 服务单价，**刚改成合计口径**）
- **触发界面**：`gv_saas_admin/src/views/tenant/ktv-config.vue:23,34,191,210`（配置）；`gv_saas_admin/src/views/tenant/resources.vue:175,181,221,224`（房型）；预约页展示 `gv_saas_admin/src/views/tenant/reservations.vue:18,24`、`gv_saas_mobile/src/b-end/views/Reservations.vue:4,32`、`c-end/app.js:494,500`
- **接口**：`GET /business/ktv/pricing` → `KtvPricingController`（`.../api/controller/KtvPricingController.java:45`）；写侧 `POST/PUT /internal/pricing-plans`
- **落库**：`tnt_pricing_plan.price_per_unit`（`.../V2__tnt_pricing_plan.sql:9`）、`res_room_type.unit_price / server_unit_price`（`.../V6__res_room_type_and_area.sql:23-24`）、开台时快照进 `ord_ktv_session.billing_rule_snapshot_json`（`.../V1__ord_order_baseline.sql:43`）
- **合计口径的单一来源**：`KtvPricingPlan.combinedUnitPrice()` = `roomUnitPrice + serverUnitPrice`（`.../domain/ktv/model/KtvPricingPlan.java:20-22` 注释；`:76-80` `withSnapshotPricing`）
- **展示**：**服务端返回的 `displayText` 里已含 `¥`**
  - 生成处：`KtvPricingController.displayText()`（`:91-119`）+ `money()`（`:121-124`）
  - 消费处：`gv_saas_admin/src/views/tenant/reservations.vue:73`（`pricing.value?.displayText || ''`）+ `:18` 直接渲染
  - 消费处：`gv_saas_mobile/src/b-end/views/Reservations.vue:79` + `:4`
  - 消费处：`c-end/app.js:447` — `return pricing.displayText || SAAS.formatKtvRoomPrice(pricing);`
- **二次消费点**：结台账单房费（`BillApplicationService.buildRoomFee`，`.../application/BillApplicationService.java:80-113`，**必须优先用会话快照**，`:100-104`）、开台中实时预估（`KtvSessionApplicationService.fillLiveEstimate`，`:429-439`）

### S4. 开台中的实时预估房费
- **触发界面**：订单列表/会话详情（`gv_saas_admin/src/views/tenant/orders.vue:229` 基础房费文案、`gv_saas_mobile/src/b-end/views/Orders.vue:322` 预估包厢费）
- **接口**：`GET /business/orders/{id}/session`、`GET /business/orders`
- **字段**：`KtvSessionPo.estimatedRoomFee`（`@TableField(exist = false)`，`.../infra/persistence/po/KtvSessionPo.java:51-55`）
- **展示**：`formatYuan(order.roomEstimatedFee)`；文案注释「包厢计时中 · 预估 ¥X」见 `KtvSessionApplicationService.java:429`
- **二次消费点**：与结台账单**必须同源** —— 唯一计算入口 `calculateRoomFee`（`KtvSessionApplicationService.java:338-347`），注释明确禁止两处各写一套（`:302`）

### S5. 结台 / 结算 / 账单
- **触发界面**：`gv_saas_admin/src/views/tenant/orders.vue:1443`；`gv_chat_app` `ktv_settle_screen.dart`
- **接口**：`POST /business/orders/{id}/settle`（`.../api/controller/OrderItemController.java:304`）；账单只读 `GET /business/orders/{orderId}/bill`（`.../api/controller/OrderBillController.java:22`）
- **落库**：`ord_order.total_amount / discount_amount / tax_amount / paid_amount / refundable_amount`（`.../application/OrderAmountApplicationService.java:58-79`）；`ord_order_item`（ROOM_FEE 明细，`KtvSessionApplicationService.writeRoomFeeItem`，`:306-335`）
- **返回 DTO**：`BillResult`（`.../application/dto/BillResult.java:9-36`）— 含 `currencyCode`、`roomFee.amount`、`items[].amount`、`servers[].amount`、`promotions[].amount`、`totalAmount`、`paidAmount`、`payableAmount`、`collected.{cash,wallet,points}`、**`changeAmount`（找零）**
- **找零计算**：`.../application/BillApplicationService.java:74` — `long change = Math.max(0L, collected.cash() - totalMinor);`
- **展示**：`gv_saas_admin/src/views/tenant/orders.vue:309,312,315,318,321,325,327-333`；`gv_saas_mobile/src/b-end/views/Orders.vue:110-112,155,156,158`；`c-end/app.js:931-966`
- **二次消费点**：支付收款（S8）、报表（S14）、审计（S16）

### S6. 预约（**无金额、无预授权**）
- **触发界面**：`gv_saas_mobile/src/b-end/views/Reservations.vue:36-41`；`c-end/app.js:453-464`（预约确认页预估）
- **接口**：`POST /business/reservations`（`.../api/controller/ReservationController.java:73`）、`POST /admin/reservations/{id}/open-table`（`.../api/controller/ReservationController.java:103`）
- **落库**：`ord_reservation` **无任何金额列**（`.../V2__ord_reservation.sql:4-28` 全表核对）
- **展示**：`c-end/app.js:486,494,500`（预估与「无需在线支付」文案）、`:821`（我的预约「包厢价格」）
- **二次消费点**：无预授权/押金表。**注意**：`gv_saas_admin/docs/ktv-room-board-prototype.html:451` 的设计稿里有「订金」，但代码中不存在对应实现 → 若产品要求预授权，属于**新增能力**而非改造。

### S7. 库存采购 / 入库 / 成本
- **触发界面**：`gv_saas_admin/src/views/tenant/inventory.vue:118-127`（采购价表单）、`:43-45`（列）
- **接口**：`POST/PUT /admin/inventory/materials`（`.../api/controller/InventoryController.java:26,32`）；入库 `POST /admin/inventory/receipts`（`:38`）
- **落库**：`ord_inventory_material.purchase_price`（`.../V20__...sql:15`）；库存数量 `ord_inventory_stock.on_hand_qty`（`.../V11__...sql:27`，**是数量不是金额**）
- **单位口径**：`gv_saas_admin/src/api/resource.js:20` — `// 金额字段（unitPrice/serverUnitPrice）是「最小货币单位/计费单位」，页面用元输入、提交前 yuanToFen。`；前端 `src/views/tenant/inventory.vue:195,200-203,301-304,318-320`
- **校验上限**：`.../application/InventoryApplicationService.java:51-54` — `MAX_PURCHASE_PRICE = 10000000000000`（分）
- **二次消费点**：商品售价自动带出（`gv_saas_admin/src/utils/product-material-autofill.js:54-55` — `fenToYuan(material.purchasePrice)` → `salePriceYuan`）；`ord_product.sale_price`（`.../V11__...sql:68`）
- **注意**：后端**没有采购单/成本核算/毛利报表**（`git grep` 无对应表与接口）→ 成本只停在「每计量单位采购价」这一个字段。

### S8. 混合支付（现金 + 储值 + 积分 + 第三方）/ 找零
- **触发界面**：
  - `gv_saas_admin/src/views/tenant/orders.vue:433-453`（收银台，输入框 `payByYuan`）；独立页 `src/views/tenant/payments.vue:45-70`
  - `gv_saas_mobile/src/b-end/views/Orders.vue:129-136`（**输入框单位标签写死「（元）」**）；`c-end/app.js:650-668`
  - `gv_chat_app` `ktv_cashier_screen.dart`
- **接口**：`POST /business/orders/{orderId}/collect` → `CollectController`（`.../api/controller/CollectController.java:29`）
- **落库**：
  - `pay_collect.request_json`（含 `currencyCode` 快照，`CollectApplicationService.java:317`）
  - `pay_intent.amount + currency_code`（`:396-397`）
  - `pay_transaction.amount + currency_code`（`:408-409`）
  - `ord_order.paid_amount`（`:192` `markPaid`）
  - 储值扣减 `cst_wallet_ledger`（经 `CustomerClient.deductWallet`，`:378`）
  - 积分抵扣 `cst_point_ledger`（`:169`）
- **抵扣顺序**：`.../application/CollectApplicationService.java:34` — `抵扣顺序「优惠→积分→储值→现金」`；前端 `gv_saas_admin/src/views/tenant/payments.vue:59`、`gv_saas_admin/src/views/tenant/orders.vue` 的 `DEDUCTION_PRIORITY`
- **找零**：后端 `BillApplicationService.java:74` 计算 `changeAmount`；`gv_chat_app/lib/models/ktv_models.dart:364` 已建模 `changeAmount`；**前端 B 端/C 端均无找零输入或展示**（`gv_saas_mobile` 无 `找零` 命中；`gv_saas_admin` 无找零字段）
- **二次消费点**：班结（S12）、日报表（S14）、退款（S11）、审计（S16）

### S9. 会员钱包：充值 / 赠送 / 消费 / 退款
- **触发界面**：
  - 充值：`gv_saas_admin/src/views/tenant/ktv-config.vue:131,527,545-552`（金额（元）表单）；`src/views/tenant/wallet.vue:42,197-205`
  - 退还：`gv_saas_admin/src/views/tenant/wallet.vue:68-70,220-225`
  - C 端余额：`c-end/app.js:200,222,783`
- **接口**：
  - `POST /admin/wallets/recharge` → `WalletAdminController.RechargeRequest`（`platform-services/customer/.../api/controller/WalletAdminController.java:21,34`）
  - `POST /admin/wallets/refund`（`:28`）
  - BFF：`GET/POST/PUT /admin/ktv/wallet-recharge`（`platform-services/admin/.../api/controller/KtvConfigController.java:85,90,95`）
- **落库**：`cst_wallet_account.available_amount`（`.../V1__cst_customer_baseline.sql:35`）+ `cst_wallet_ledger.amount/balance_after`（`:54-55`）；账户按 `(tenant, customer, legal_entity, currency_code)` 唯一（`:44`）
- **充值赠送**：**无赠送字段/无赠送逻辑**。`cst_wallet_ledger.entry_type` 只有 `RECHARGE/CONSUME/REFUND/HOLD/RELEASE/ADJUST`（`:53`）；`gv_saas_admin/src/views/tenant/wallet.vue:133` 的 `rechargeTokens` 是「到账代币」展示值（按 `wallet_ratio` 折算），**不是赠送**。
- **币种默认**：`WalletApplicationService.java:134` 硬编码 `"CNY"`；C 端 `MyAssetsController.java:37` 硬编码 `"CNY"`；`MemberController.java:62` 硬编码 `"CNY"`
- **展示**：
  - `gv_saas_admin/src/views/tenant/members.vue:108-110`（`money(wallet.availableAmount)` + `currencyText(wallet.currencyCode)`）
  - `gv_saas_admin/src/views/tenant/wallet.vue:26-28`（`fmtTokens(row.balance)`，**代币口径**）
  - **`gv_saas_admin/src/views/tenant/orders.vue:444` 与 `payments.vue:56` 直接渲染原始最小单位整数（未走 `formatYuan`）** → 同一 `availableAmount` 在三个页面三种表现（§5.4）
  - `gv_saas_mobile/src/b-end/views/Orders.vue:126`（`formatMoney(memberWalletMinor)`）
  - `c-end/app.js:200,222,247,783`（`SAAS.formatFen(w.availableAmount)`，**未传币种**）
- **二次消费点**：储值消费走 S8 的 `deductWallet`；退还写 `cst_wallet_ledger` + 审计 `wallet.refund`

### S10. 积分 / 代币
- **积分**（非货币，但同屏与金额并排）
  - 接口：`GET /business/members/points`、`GET /business/members/{id}/points`（`.../api/controller/MemberController.java:68,76`）、`POST /business/members/{id}/points/adjust`（`:84`）、`GET /me/points`（`.../api/controller/MyAssetsController.java:46`）
  - 落库：`cst_point_account.available_points/frozen_points`、`cst_point_ledger.points/balance_after`（`.../V1__cst_customer_baseline.sql:73-74,91-92`）
  - 展示：`gv_saas_admin/src/views/tenant/points.vue`、`gv_saas_admin/src/views/tenant/orders.vue:333`（`formatYuan(bill.collected.points)` ← **积分被当金额格式化**）、`c-end/app.js:204,227,246`
- **代币（A380币 = 储值品牌名）**
  - 展示名与比例：`GET/PUT /admin/tenant/config` → `TenantConfigController.WalletTokenConfig(brandName, ratio)`（`platform-services/tenant/.../api/controller/TenantConfigController.java:41-47,50-66,117`）
  - 落库：`tnt_tenant_config` 的 `wallet_brand_name` / `wallet_ratio`（键名 `:27-30`，默认 `A380币` / `100`）
  - 前端折算：`gv_saas_admin/src/views/tenant/wallet.vue:133-134,146`（`Math.round(amountYuan * tokenRatio)` / `tokens / tokenRatio`）；`gv_saas_mobile/src/b-end/views/Orders.vue:450-456,234`
  - **注意**：`ratio = 100` 与「分/元 = 100」数值巧合（§5.5）
- **二次消费点**：S8 组合支付的积分/储值分腿

### S11. 退款（全额 / 部分）
- **触发界面**：`gv_saas_admin` 支付流水页（退款申请/审批/登记）；`gv_saas_mobile` 无退款界面
- **接口**：
  - `POST /business/refund-requests` → `CashierController.RefundRequest`（`common-services/payment/.../api/controller/CashierController.java:40,66`）
  - `POST /admin/refund-requests/{id}/approve`（`:45,67`）
  - `POST /admin/refund-requests/{id}/reject`（`:51,68`）
  - `POST /admin/refund-requests/{id}/refund`（`:57,69`）
- **落库**：`pay_refund.requested_amount / approved_amount / status / provider_refund_no`（`.../V1__pay_baseline.sql:26-27`；状态机 `.../V7__pay_refund_status_pending.sql:1-5`）
- **校验**：`.../application/RefundApplicationService.java:55-59`（`approvedAmount > 0`、`approvedAmount <= requestedAmount`）
- **DTO 无币种**：`RefundDto`（`.../application/RefundDto.java:10`）
- **展示/二次消费点**：`ord_order.refundable_amount`、报表 `selectRefunds`（`platform-services/admin/.../infra/persistence/mapper/ReportMapper.java:105` — `SUM(COALESCE(r.approved_amount, 0))`，**无币种分组**）、审计 `payment.refund.*`

### S12. 收银班次 / 日结 / 交班现金对账
- **触发界面**：`gv_saas_admin/src/views/tenant/shift.vue:23-34`（备用金/应收现金/实收现金/长短款）、`:90,103`（开班/交班表单）；`gv_chat_app` `ktv_shift_screen.dart`
- **接口**：`POST /business/shifts/open`（`.../api/controller/CashierController.java:25,63`）、`POST /business/shifts/{id}/close`（`:30,64`）、`POST /admin/daily-closings/{id}/submit`（`:35,65`）、`GET /business/shifts`（`.../api/controller/PaymentQueryController.java:27`）、`GET /admin/daily-closings`（`:39`）
- **落库**：`pay_shift.opening_cash / expected_cash / actual_cash / difference_amount`（`.../V1__pay_baseline.sql:38-39`，**无币种列**）；`pay_daily_closing.summary_json`（`:50`，**从未被写入**，见 §5.3）
- **计算**：`.../application/CashierApplicationService.java:52-55` — `expectedCash = openingCash + sumCashCollected(...)`；`differenceAmount = actualCash − expectedCash`
- **SQL 无币种过滤**：`.../infra/persistence/mapper/PayIntentMapper.java:15-17` — `WHERE tenant_id = ... AND store_id = ... AND provider = 'CASH' AND status = 'SUCCEEDED' AND created_at >= ... AND created_at < ...`（**无 `currency_code` 条件**）
- **二次消费点**：审计 `cashier.shift.close`（`CashierApplicationService.java:58-62`，金额以字符串拼 JSON）

### S13. 支付流水与对账
- **触发界面**：`gv_saas_admin/src/views/tenant/payments.vue:26-28`；对账页（`ReconciliationController`）
- **接口**：`GET /business/payments`（`.../api/controller/PaymentQueryController.java:33`）、`GET /admin/reconciliations/summary`（`.../api/controller/ReconciliationController.java:18`）
- **落库**：`pay_intent` / `pay_transaction` / `pay_channel_transaction`（含 `currency` `varchar(8)`，`.../pay_channel_baseline.sql:28`）
- **DTO 无币种**：`ReconciliationApplicationService.ProviderLine(provider, count, grossAmount, feeAmount, netAmount)`（`.../application/ReconciliationApplicationService.java:52`）— **按 provider 聚合，无币种**
- **二次消费点**：报表 S14

### S14. 报表 / 看板 / 日结报表
- **触发界面**：`gv_saas_admin/src/views/tenant/reports.vue:64-85`（应收/已收/退款/优惠/客单价/收款）；订单报表 `src/views/tenant/orders.vue`；`gv_chat_app` `ktv_dashboard_screen.dart`
- **接口**：
  - `GET /admin/reports/operations` / `/payments` / `/employee-performance` / `/resources`（`platform-services/admin/.../api/controller/ReportController.java:39,75,114,149`）
  - `GET /reports/store-operation` / `/employee-performance` / `/resource-utilization`（`platform-services/order/.../api/controller/ReportController.java:40,102,68`）
- **SQL（**无币种分组**，直接 SUM 不同口径字段）**：
  - `platform-services/admin/.../infra/persistence/mapper/ReportMapper.java:35` — `SUM(t.amount) AS collected_amount`（`pay_transaction`，**无 currency 分组**）
  - `ReportMapper.java:86-88` — `SUM(o.total_amount) AS receivable_amount`, `SUM(o.paid_amount) AS paid_amount`, `SUM(o.discount_amount) AS discount_amount`
  - `ReportMapper.java:95` — `GROUP BY o.store_id, s.name, DATE_FORMAT(o.created_at, '%Y-%m-%d')`（**无 currency_code**）
  - `ReportMapper.java:105` — `SUM(COALESCE(r.approved_amount, 0)) AS refund_amount`（`pay_refund`，**该表本身无币种**）
  - `ReportMapper.java:123` — `SUM(t.amount) AS collected_amount`
  - `platform-services/order/.../api/controller/ReportController.java:52-53` — `byStoreTotal.merge(o.getStoreId(), nz(o.getTotalAmount()), BigDecimal::add)`（Java 侧跨订单求和，**无币种维度**）
  - `.../api/controller/ReportController.java:115-116` — 同上（员工业绩）
- **聚合键无币种**：`platform-services/admin/.../api/controller/ReportController.java:188-190` — `opsKey(m) = store_id + "|" + business_date`（**币种未参与聚合键**）
- **展示**：`gv_saas_admin/src/views/tenant/reports.vue:118` — `if (c.kind === 'money') return formatYuan(v)`
- **二次消费点**：无导出（后端无 Excel/CSV 能力，§2.3）

### S15. 营销：优惠券 / 满减 / 折扣
- **触发界面**：**无独立营销管理页**。`gv_saas_admin` 唯一展示点是账单优惠行 `src/views/tenant/orders.vue:320-322`；`gv_saas_mobile/c-end/app.js:948-950` 渲染 `bill.promotions`
- **接口**：`GET/POST /business/coupons`、`POST /business/coupons/{id}/issue`、`POST /business/coupons/{id}/redeem`（`platform-services/marketing/.../api/controller/CouponController.java:27,35,45,53`）
- **落库**：`mkt_coupon.discount_value / min_amount / max_discount`（`.../V1__mkt_marketing_baseline.sql:32-34`）；`mkt_coupon_redemption.amount / discount_amount`（`.../V2__mkt_coupon_redemption.sql:11-12`）
- **DTO**：`CreateCouponRequest(campaignId, discountType, discountValue, minAmount, maxDiscount, ...)`（`CouponController.java:58`）、`RedeemCouponRequest(customerId, orderId, amount, expectedVersion)`（`:62`）— **均无币种**
- **结算接入状态**：`.../application/BillApplicationService.java:179` — `// 优惠逐项：首发仅整单折扣；券/满减/会员价在接入 mkt_* 后按类型拆分（KTV_BUSINESS_01 §6）。`
- **二次消费点**：`ord_order.discount_amount` → 报表 SUM（S14）

### S16. 审计日志（金额字符串）
- **触发界面**：`gv_saas_admin/src/views/tenant/audits.vue:157`（`prop="value"` 直接渲染）、`:221`（`flattenAuditDetail(detail.value?.detailJson)`）
- **接口**：`GET /admin/audit-logs`（`common-services/audit/.../api/controller/AuditController.java`）
- **落库**：`iam_audit_log.detail_json`（`.../V1__iam_audit_log.sql:11`）
- **金额来源**：§2.4 的 7 处硬编码 JSON 拼接
- **前端零换算**：`gv_saas_admin/src/utils/audit.js:150-183` 的 `AUDIT_DETAIL_LABEL_TEXT` **未登记任何金额键**；`:227-238` `displayDetailScalar` 对未知键原样输出 → `amount: 10000` 显示为 `10000`
- **二次消费点**：无

### S17. C 端报价与「我的预约」
- **触发界面**：`c-end/app.js:439-452`（房间价）、`:453-464`（预估）、`:486,494,500`（预约确认页）、`:816-821`（我的预约）
- **接口**：`GET /api/v1/business/ktv/pricing`（经 `c-end/saas.js:114-119`）、`GET /me/reservations`（`platform-services/order/.../api/controller/MyOrderController.java:38`）、`GET /reservations/me/{reservationNo}`（`.../api/controller/ReservationCompatController.java:31`）
- **币种不全**：`c-end/saas.js:229,253-254` 的 `formatFen` 未传币种；`:269-281` `normalizeKtvPricing` 白名单里**没有 `currencyCode`**
- **二次消费点**：到店开台（S1 的第二条路径，币种在服务端硬编码 `CNY`）

### S18. IM 消息里的订单卡片 / 红包
- **结论：不存在。** 证据：
  - 消息类型枚举 `sdk/common/src/main/java/com/gvchat/common/enums/MsgType.java:9-23` 只有 `TEXT/IMAGE/FILE/VOICE/VIDEO/LOCATION/NAMECARD/CALL/SYSTEM/RECALL/EMOJI` — **无 `ORDER_CARD` / `RED_PACKET` / `TRANSFER`**
  - `git grep -n -iE "order_?card|orderCard|redpacket|red_packet|红包" -- "*.java" "*.sql" "*.yml"` 在 `gv_im_server` → **0 条相关命中**（仅「余额不足」错误文案等）
  - `gv_chat_app`：`redPacket` / `red_packet` / `红包` → **0 条**（`orderCard` 1 个文件命中实为 `lib/screens/business/ktv_timing_screen.dart:238,304` 的私有 Widget 方法名 `_orderCard(KtvOrder order, ...)`，不是 IM 消息类型）
  - `gv_chat_app` 消息渲染 switch 无订单/红包分支：`lib/screens/chat_room/chat_room_message_tile.dart:413-671`（8 个 case：`text/emoji/image/video/file/voice/call/namecard`）；预览 `lib/core/message_preview.dart:22-46` 同样无
  - `gv_chat_app` 的 `redPacket|红包|transfer|转账|orderCard|gift` 共 18 条命中**全为噪声**：`lib/l10n/app_en.arb:1130`（`"travelTransfer": "Transfer"` = 机票中转）、`:1198`（聊天记录备份迁移）、`lib/services/api_message_localizer.dart:125`（群主转让）
  - `gv_chat_desktop`：`src/renderer/src/models/message.ts:3-14` 的 `MsgType` union 11 种**无订单/红包**；`redPacket|红包|transfer|转账|orderCard|gift` 4 条命中全为 `ChatPanel.vue:129,134,1114,1123` 的 `transfer-state` CSS 类（消息发送状态）
  - `gv_chat_admin`：`红包` / `套餐` / `余额` → **0 条**
- **风险**：两个客户端的消息类型都是**封闭枚举**（Dart 靠 `switch` + 字符串约定，TS 靠 union type）。若未来新增订单卡片/红包，需同步改：Dart 的 `chat_room_message_tile.dart:413` switch + `lib/core/message_preview.dart:22-46`；TS 的 `models/message.ts:3` + `shared/db.ts:4` + `content.ts:107` + `ChatPanel.vue` 模板。
- **但 IM 客户端内已有 KTV 收银业务屏**（`gv_chat_app/lib/screens/business/` 7 个文件，全部 `import ktv_models.dart`），其金额走 S1–S13 的同一批接口 → 币种改造**必须覆盖 IM 客户端**。
- **IM 客户端内唯一的现金相关入口**：`gv_chat_app/lib/screens/business/ktv_cashier_screen.dart:372` — `final balance = m.balance.isZero ? '' : '，余额 ' + m.balance.formatted;`（数据源是 `KtvPaymentMethod.balance`，非用户钱包）。`lib/screens/services_screen.dart`（全文 1124 行）搜 `points|coin|coupon|wallet|balance|¥` → **0 命中**。

### S19. 会员积分管理 / 会员列表
- **触发界面**：`gv_saas_admin/src/views/tenant/members.vue:108-110`
- **接口**：`GET /business/members`（`.../api/controller/MemberController.java:38`）、`GET /business/members/{id}/wallet`（`:90`）、`GET /business/members/{id}/wallet/ledger`（`:96`）、`GET /business/members/{id}/points`（`:76`）
- **注意**：`GET /business/members/{id}/wallet/ledger` 返回 `Page<CstWalletLedgerPo>`（`.../application/WalletApplicationService.java:ledger(...)`）— **`cst_wallet_ledger` 无 `currency_code` 列**，账本行无法自带币种。

### 场景证据统计

- 覆盖链路 **19 条**（S1–S19），每条 4–8 条 `文件:行号` 证据，合计 **约 120 条证据**。
- 其中**明确无实现**的场景 5 条：预约预授权/押金（S6）、充值赠送（S9）、找零输入与展示（S8 前端）、采购单/成本核算（S7 扩展）、订单卡片/红包消息（S18）。
- 端点/接口引用 **约 60 个**。

---

## 5. 风险与遗漏点（逐条给证据）

### 5.1 【高危】`decimal(20,6)` 存「分」——同一列在不同服务的单位口径不一致

- 声明「按分存」：`platform-services/order/.../application/BillApplicationService.java:208` — `/** 金额 BigDecimal → 最小货币单位 long。订单域金额已统一按「分」存储，此处仅做类型收敛，不做分/元换算。 */`，实现 `:209-213` `amount.setScale(0, RoundingMode.HALF_UP).longValueExact()`
- 声明「单位是元」：`platform-services/order/.../api/controller/ReportController.java:26` — `金额按最小货币单位约定（PO 内 decimal(20,6) 单位元），返回保留原值；数据时点以查询时刻为准。` ← **同一仓库同一域内自相矛盾**
- `decimal(20,6)` 保留 6 位小数本身暗示「元」形态，但列注释写「分」：`.../V9__ord_catalog_item.sql:10`（`'单价（最小货币单位：分）'`）、`.../V11__ord_inventory_product.sql:68`（`'销售单价（分）'`）
- 与 payment 域对比：`pay_intent.amount decimal(20,6)` **无单位注释**（`.../V1__pay_baseline.sql:6`），而 `pay_channel_transaction.amount bigint` 注释是 `'金额最小货币单位整数（分）'`（`.../pay_channel_baseline.sql:27`）
- 测试使用了**带角分的「元」形态值**，进一步放大歧义：`common-services/payment/.../src/test/java/com/gvchat/common/payment/application/CashierApplicationServiceTest.java:49` — `thenReturn(new BigDecimal("500.00"))`；`:69-74` — `new BigDecimal("620.50")` / `expectedCash` `"600.00"` / `differenceAmount` `"20.50"`
- 结论：**「分」还是「元」目前没有单一权威定义**。币种改造必须先冻结这一条，否则换 USD 时（USD 也有 100 分）看不出问题，但一旦引入 JPY（0 位小数）或任何报表口径调整就会爆发。

### 5.2 【高危】金额被当字符串拼进文案 / 审计 JSON

后端：
- `platform-services/order/.../api/controller/KtvPricingController.java:123` — `return "¥" + minor / 100 + "." + String.format("%02d", minor % 100);`（**符号 + 手工小数拼接，负数会产出 `¥-1.00` 之类**）
- `platform-services/order/.../application/SettlementApplicationService.java:53-54` — `"{\"totalAmount\":" + order.getTotalAmount() + ...`
- `common-services/payment/.../application/CollectApplicationService.java:203` — `"{\"orderId\":" + orderId + ",\"payable\":" + payable + ...`
- `common-services/payment/.../application/CashierApplicationService.java:61-62` — `"{\"actualCash\":" + actualCash + ...`
- `common-services/payment/.../application/RefundApplicationService.java:46` — `"{\"orderId\":" + orderId + ",\"amount\":\"" + amount + "\"}"`（**这里是字符串类型，别处是数字类型 → 审计 JSON 类型不稳定**）
- `common-services/payment/.../application/RefundApplicationService.java:69` — `"{\"approvedAmount\":\"" + approvedAmount + "\"}"`
- `platform-services/customer/.../application/WalletApplicationService.java:58` — `"{\"customerId\":" + customerId + ",\"amount\":" + amount + ",\"currency\":\"" + currency + "\"}"`
- `platform-services/tenant/.../api/controller/TenantConfigController.java:63` — `"{\"brandName\":\"" + req.brandName() + "\",\"ratio\":" + req.ratio() + "}"`

前端：
- `gv_saas_admin/src/utils/roomPricing.js:114` — `` `基础房费 房型 ${formatYuan(resolved.unitPrice)} + 服务 ${formatYuan(server)} = ${formatYuan(combined)}${unit}` ``
- `gv_saas_admin/src/views/tenant/orders.vue:321` — `-{{ formatYuan(p.amount) }}`（模板里用字符串 `-` 前缀）
- `gv_saas_admin/src/views/tenant/orders.vue:435` — `订单合计 {{ formatYuan(...) }} − 已收 {{ formatYuan(...) }}`
- `gv_saas_mobile/src/b-end/views/Orders.vue:322` — `` parts.push(`预估包厢费 ${formatMoney(order.roomEstimatedFee)}`) ``
- `gv_saas_mobile/src/b-end/views/Orders.vue:534` — `` collectError.value = `已填合计 ${formatMoney(total)} 与应收 ${formatMoney(payable)} 不一致` ``
- `gv_saas_mobile/c-end/saas.js:253-254` — `'房型 ' + formatFen(room) + unit + ' + 服务 ' + formatFen(server) + unit + ' = ' + formatFen(combined) + unit`
- `gv_chat_app/lib/l10n/app_zh.arb:527,566,568,949` 与 `app_en.arb:527,545,566,568,949` — i18n 串里写死 `¥`
- `gv_chat_app/lib/screens/mock_travel_flow_screens.dart:15` — `'¥$price'`

### 5.3 【中危】日结汇总从未计算 / 汇率快照有列无逻辑

- `pay_daily_closing.summary_json` 建表存在（`.../V1__pay_baseline.sql:50`），但 `submitDailyClosing` 只插入 `tenantId/storeId/businessDate/submittedBy/status`，**从不写 summary**：`.../application/CashierApplicationService.java:75-82`
- `pay_transaction.exchange_rate` 有列（`.../V1__pay_baseline.sql:18`），`cst_wallet_ledger.fx_quote_id` 有列（`.../V1__cst_customer_baseline.sql:57` 注释「跨币种，首发不用」），但**全仓无 `fx_quote` 表、无任何读写 `exchangeRate` 的代码**（`git grep -n "exchangeRate\|ExchangeRate\|fx_quote\|FxQuote"` → 仅 `CollectApplicationService` 的 `currencyCode` 传参与 PO 字段定义）
- 后果：币种切换后**没有汇率基准**，跨币种核对/折算无处落地。

### 5.4 【高危】跨服务与跨页面的金额口径不一致

前端同一字段三种表现（会员可用余额）：
- `gv_saas_admin/src/views/tenant/members.vue:108` — `{{ money(wallet.availableAmount) }}`（走 `formatYuan`，`members.vue:147`）
- `gv_saas_admin/src/views/tenant/orders.vue:444` — `{{ walletBrand }} {{ memberWalletMinor }} · 积分 {{ memberPoints }}`（**原始最小单位整数**）
- `gv_saas_admin/src/views/tenant/payments.vue:56` — 同上（**原始最小单位整数**）
- `gv_saas_admin/src/views/tenant/wallet.vue:26-28` — `{{ fmtTokens(row.balance) }}`（**代币口径**，`wallet.vue:136-139`）

应收口径三种算法：
- `gv_saas_admin/src/views/tenant/orders.vue:1287-1291` — 优先 `payableAmount`，缺省 `totalAmount − paidAmount`
- `gv_saas_admin/src/views/tenant/payments.vue:130-133` — 优先服务端 `payableAmount`，缺省 `billed − paid`
- `gv_saas_admin/src/views/tenant/orders.vue:327` — `v-if="bill.payableAmount != null"`，缺省**不展示**

后端跨服务：
- 订单币种由客户端给（`OrderController.java:120`），收款币种也由客户端给（`CollectController.java:46`），**两者之间没有任何一致性校验**（`CollectApplicationService.java:117-131` 只校验金额，未比对 `billing` 的币种）
- 钱包账户币种硬编码 `CNY`（`WalletApplicationService.java:134`、`MyAssetsController.java:37`、`MemberController.java:62`），而订单币种可由客户端指定 → **可能出现「USD 订单 + CNY 钱包」的静默错配**
- 钱包扣减确实会校验币种：`platform-services/customer/.../application/WalletApplicationService.java:89` — `if (currency != null && !currency.isBlank() && !currency.equals(account.getCurrencyCode()))` → 抛错；但因为账户被硬编码建成 CNY，USD 订单用储值支付会**直接失败**而非走汇率

报表跨口径 SUM（见 S14）：
- `platform-services/admin/.../ReportMapper.java:35`、`:86-88`、`:105`、`:123` 全部无 `currency_code` 条件或分组
- `platform-services/admin/.../ReportController.java:188-190` 聚合键不含币种
- `platform-services/order/.../ReportController.java:52-53`、`:115-116` Java 侧直接跨订单累加

### 5.5 【中危】前端本地 ×100 / ÷100，且与「代币比例 100」数值巧合

- `gv_saas_admin/src/utils/format.js:51`（`number / 100`）、`:57`（`Math.round(number * 100)`）— 全仓唯一换算点，**无币种小数位抽象**
- `gv_saas_admin/src/views/tenant/wallet.vue:133` — `Math.round((rechargeForm.value.amountYuan || 0) * tokenRatio.value)`；`:134` — `(refundForm.value.tokens || 0) / tokenRatio.value`；`:119,146` — `tokenRatio = ref(100)` / `Number(cfg.ratio || 100)`
- `gv_saas_mobile/src/shared/utils/amount.js:11,17,24` 与 `c-end/saas.js:113,119` — 无条件按 100 换算
- `gv_chat_app/lib/screens/business/ktv_shift_screen.dart:55-59` — `_plain(KtvMoney m)` 写死 `~/ 100` 与 2 位小数（**忽略 `_currencyDecimals`**，而同一文件同类 `ktv_cashier_screen.dart:206-213` 是币种感知的）
- `gv_chat_app/lib/models/ktv_models.dart:48-52` — 唯一正确的币种小数位表（`JPY: 0, KRW: 0`）
- 结论：CNY/USD 都是 2 位小数，改造**不会被现有测试发现**；但任何 0 位小数币种都会立即错。USD 的 `$` 符号与 2 位小数与 CNY 完全同构，因此**「默认 USD」在功能上不会暴露这一层缺陷**。

### 5.5b 【中危】IM 客户端把币种代码当用户可见文案（`CNY` 直出）

- `gv_chat_app/lib/screens/business/ktv_cashier_screen.dart:343` — `Text('已收 ' + bill.paidAmount.formatted + ' · 币种 ' + bill.currency,` ← 用户看到 `CNY`，而 `gv_chat_app/lib/models/ktv_models.dart:40-46` 已有符号表可用于渲染
- `gv_chat_app/lib/screens/business/ktv_settle_screen.dart:173` — `Text('账单快照（' + bill.currency + '）',`
- 对照 `gv_saas_admin` 已有正确做法：`gv_saas_admin/src/utils/format.js:88-91` 的 `currencyText(code)`（`members.vue:110`、`orders.vue:309`、`stores.vue:28` 调用），以及 `gv_saas_admin/src/utils/format.test.js:95` — `it('人民币代码转中文，不出现 CNY 裸露在界面', ...)`
- 附带缺口：`gv_chat_app/lib/models/ktv_models.dart:80` — `final symbol = _currencySymbols[code] ?? '';` **未知币种静默降级为空字符串**（无日志、无兜底）；`:56` 构造器不校验 currency，`:390` 用 `const KtvMoney(minorUnits: 0, currency: '')` 作默认值 → 会静默产出无符号金额。

### 5.5c 【中危】Mock 数据写死 `CNY`，使「默认 USD」在无后端时永远「看起来正常」

- `gv_chat_app/lib/repositories/business/ktv_api_client.dart:541,542,543,559,560,561,590,641,647,653,664,668,669,671,679,680,682,683,684,685` — 共 **20 处 `currency: 'CNY'`**
- `gv_chat_app/lib/repositories/business/ktv_api_client.dart:590` — `KtvBill _mockBill({required String orderId}) { const currency = 'CNY';`
- `gv_saas_mobile/src/b-end/preview/saas.js:112,113,254` — `defaultCurrency: 'CNY'` / `currencyCode: 'CNY'`
- `gv_saas_mobile/c-end/saas.js:181` — `var MOCK_WALLET = { availableAmount: '888000', frozenAmount: '0', currencyCode: 'CNY' };`
- `gv_saas_admin/src/views/platform/pricing-plans.vue:66` — `// 占位数据（接口未接入）：金额同样按最小货币单位（分）存…`
- 后果：改造若只改默认值而漏改 mock，本地/无后端环境下**看不到任何回归**。

### 5.5d 【中危】i18n key 名与值语义冲突，且死键会误导改造范围

- `gv_chat_app/lib/l10n/app_en.arb:949` — `"reservationCurrency": "¥{amount}",` ← **key 叫 `currency`，值却硬编码 `¥`**
- 同 key 的中文版：`gv_chat_app/lib/l10n/app_zh.arb:949` — `"reservationCurrency": "¥{amount}",`
- 生成物：`lib/l10n/app_localizations_en.dart:2492-2493`、`lib/l10n/app_localizations_zh.dart:2418-2419` → 均 `return '¥$amount';`
- **`reservationCurrency` 及 `reservationDetailTitle` / `reservationConsumeAmount` / `reservationOrderNo` / `reservationSettlementInfo` 等 20+ 个 key 在 `lib/screens`、`lib/widgets`、`lib/providers` 中 0 调用点**（预约界面不存在，只有未接线的 API `lib/services/generated_im_api_client.dart:518-543`）
- 同样情况：`lib/l10n/app_en.arb:436-488` 的 `points*` / `coin*` / `servicesPointsBalance` 全为死键（`lib/screens/`、`lib/widgets/` 零引用）
- 后果：按「l10n 里搜 currency」定位会把大量无效文件纳入改动；只按「代码调用点」定位又会漏掉将来会启用的文案。

### 5.6 【高危】已结算/已落库历史单据的币种不可被后改设置改写

- 账单币种来自订单行：`.../application/BillApplicationService.java:76` — `order.getCurrencyCode()`
- 订单币种在开台时写入：`.../api/controller/OrderController.java:120`（客户端值）与 `.../application/ReservationApplicationService.java:174`（硬编码 `CNY`）
- 会话计价快照：`ord_ktv_session.billing_rule_snapshot_json`（`.../V1__ord_order_baseline.sql:43`）+ 明细快照 `ord_order_item.price_snapshot_json`（`:28`）
- 结台后账单**不回算**：`.../application/BillApplicationService.java:94-97` — `// 结台已固化 ROOM_FEE 明细：账单直接取快照金额，不重新计算（改规则不影响历史账单）。`
- 支付侧币种快照：`pay_collect.request_json`（`CollectApplicationService.java:317`）、`pay_intent.currency_code`（`:397`）
- **缺口**：`pay_refund`（`.../V1__pay_baseline.sql:26`）、`pay_shift`（`:38-39`）、`pay_daily_closing`（`:46-54`）、`cst_wallet_ledger`（`.../V1__cst_customer_baseline.sql:49-65`）、`mkt_coupon_redemption`（`.../V2__mkt_coupon_redemption.sql:4-20`）、`res_room_type`（`.../V6__res_room_type_and_area.sql:23-24`）、`tnt_pricing_plan`（`.../V2__tnt_pricing_plan.sql:9`）**都没有币种列** → 如果币种是「租户/门店级设置」，改设置后这些历史记录会被**按新币种解读**。
- 前端同样固化：`gv_saas_admin/src/utils/format.js:65,76,84` 输出符号与数据无关；`gv_saas_mobile/c-end/app.js:940` `bill.currencyCode || 'CNY'`。

### 5.7 【中危】源码守卫测试会把「正确的多币种实现」判为回退

- `gv_saas_admin/src/constants/terms.test.js:113` — `pattern: /¥/`（`currency-symbol`）→ 换成 `$` 后守卫失效，需重写为「符号必须来自 `utils/format`」
- `gv_saas_admin/src/constants/terms.test.js:108` — `pattern: /[/\s]\/\s*100\b|[/\s]\*\s*100\b/`（`money-arithmetic`）→ 按币种小数位动态换算后可能合法出现
- `gv_saas_admin/src/constants/terms.test.js:118` — `local-money-helper` 正则只覆盖 `fenToYuan|yuanToFen|yuanToMinor|minorToYuan|formatYuan|fmtCents|fmtYuan`，**以下页内影子实现不会被拦**：
  - `gv_saas_admin/src/views/tenant/inventory.vue:200-203` — `function purchasePriceFen(yuan)`
  - `gv_saas_admin/src/views/tenant/members.vue:146-148` — `function money(v) { return formatYuan(v) }`
  - `gv_saas_admin/src/views/tenant/wallet.vue:136-139` — `function fmtTokens(v)`
- `gv_saas_mobile/c-end/money.test.js:72` — `expect(source).not.toMatch(/\/\s*100|\*\s*100/)` → **锁死任何在 `c-end/app.js` 内新增 /100 或 \*100 的写法**（含为多币种小数位做换算）
- `gv_saas_mobile/c-end/money.test.js:65-66` — `expect(source).toContain('minorMoney(it.totalAmount')` / `('minorMoney(estimate)')` → 锁死调用文本

### 5.8 【中危】后端 `displayText` 绕过前端格式化（同页两种来源）

- 生成：`platform-services/order/.../api/controller/KtvPricingController.java:91-119`（含 `¥`）
- 三处消费：`gv_saas_admin/src/views/tenant/reservations.vue:73,18`；`gv_saas_mobile/src/b-end/views/Reservations.vue:79,4`；`gv_saas_mobile/c-end/app.js:447`
- 同页并存的第二种来源：`gv_saas_admin/src/views/tenant/reservations.vue:78` — `formatYuan(pricing.roomUnitPrice ?? 0) + '/' + billingUnitShortText(...)`（前端格式化）
- 后果：改币种后同一页会出现 `$`（前端）与 `¥`（服务端）并存。

### 5.9 【中危】金额以字符串承载，跨端类型契约不同

- `gv_saas_mobile/c-end/saas.js:97-100` — `asString(v, fallback)`；`:155-156`、`:499-500` — 金额全程字符串；`c-end/money.test.js:55` — `expect(wallet.availableAmount).toBe('888000')`
- `gv_saas_mobile/src/b-end/views/Orders.vue:471` — `numberOf(...)` 转数字
- `gv_saas_admin/src/utils/format.js:50,56` — `Number(minor)`（接受字符串）
- 相等的分整数比较：`gv_saas_mobile/src/b-end/views/Orders.vue:533` — `if (total !== payable)`；`gv_saas_admin/src/views/tenant/orders.vue:1411` — `if (total !== payable)`（元↔分往返靠 `Math.round` 兜底，多次 `clampLeg`/`autoFill` 往返后存在分位漂移风险）

### 5.10 【低危但易漏】前端请求体硬编码币种（**写路径**，不是读路径）

- `gv_saas_admin`：`src/views/tenant/orders.vue:1098`（建单）、`:1418`（收款）、`src/views/tenant/payments.vue:285`（收款）、`src/views/tenant/ktv-config.vue:459`（支付开关表单）、`:527`（储值表单）、`src/views/tenant/wallet.vue:205`（充值，字段名是 `currency` 而非 `currencyCode`）
- `gv_saas_mobile`：`src/b-end/views/Orders.vue:391`（建单）、`:546`（收款）、`c-end/saas.js:675`（收款）
- 服务端**没有兜底默认值**：`platform-services/order/.../api/controller/OrderController.java:120` 直接把 `req.currencyCode()` 写入 `NOT NULL` 列；若前端漏改，服务端不会报错但会落旧币种。

### 5.11 【低危】IM 侧遗留表已删除，但与文档/规范不一致

- 已删：`im-services/user/.../V12__drop_coin_points.sql:1-5`（`user_coin_account`、`user_coin_ledger`、`user_point_account`、`user_point_ledger`）
- 但创建脚本仍在历史迁移里：`.../V10__init_coin.sql:2,15`、`.../V1__init.sql:77,90`
- `docs/renovation/KTV_BUSINESS_01_SERVICE.md:34` 仍以「A380币」作为储值品牌名 → **不要**把品牌名当成币种字段

### 5.12 风险点证据统计

- 共 **15 类风险**（5.1–5.11 + 5.5b/5.5c/5.5d），逐条证据合计 **约 135 条**。
- 其中被判定为**高危**的 4 类（5.1 单位口径矛盾、5.2 金额字符串、5.4 跨服务口径不一致、5.6 历史单据币种固化缺口）。

---

## 6. 改造影响面建议（只做分类与建议，不写代码）

### 6.1 必须由**后端单一来源**提供币种的点

| 点 | 理由 | 证据 |
|---|---|---|
| **订单币种** | 订单是全链路金额的根；账单/支付/退款/报表全部以订单为锚 | `.../infra/persistence/po/OrderPo.java:55`；`.../application/BillApplicationService.java:76` |
| **账单（`BillResult.currencyCode`）** | 已是「最小单位 + 币种」契约的现成载体，前端应只读它 | `.../application/dto/BillResult.java:11` |
| **门店/租户默认币种** | 必须是「改一处、全局变」的唯一来源；现有 `tnt_store.default_currency` 是死列，应激活 | `.../V1__tnt_iam_baseline.sql:29`；`gv_saas_admin/src/views/tenant/stores.vue:28`（前端已在展示） |
| **钱包账户币种** | 储值必须按币种隔离（唯一键已含 `currency_code`） | `.../V1__cst_customer_baseline.sql:44`；`.../application/WalletApplicationService.java:89` |
| **支付/退款/班次币种** | `pay_intent`/`pay_transaction` 已有；`pay_refund`/`pay_shift`/`pay_daily_closing` 需补列 | `.../V1__pay_baseline.sql:6,17`（有）vs `:26,38-39,46-54`（无） |
| **计价方案与房型单价币种** | 单价是「金额」，必须与订单币种同源，否则改币种后旧单价含义漂移 | `.../V2__tnt_pricing_plan.sql:9`；`.../V6__res_room_type_and_area.sql:23-24` |
| **优惠券面额币种** | `discount_value` 同一列承载「金额/万分数」两种口径，再加币种维会更乱 | `.../V1__mkt_marketing_baseline.sql:32` |
| **报表汇总币种** | 报表 SQL 目前跨币种 SUM，必须由后端按币种分组或拒绝混合 | `.../ReportMapper.java:35,86-88,105,123`；`.../ReportController.java:188-190` |
| **`displayText`** | 服务端生成的展示文案，前端无法本地化 | `KtvPricingController.java:91-124` |
| **找零（`changeAmount`）** | 现金找零天然与币种/最小单位绑定 | `BillApplicationService.java:74`；`gv_chat_app/lib/models/ktv_models.dart:364` |

### 6.2 可以（且应该）由**前端统一格式化**的点

| 点 | 建议归属 | 证据 |
|---|---|---|
| `gv_saas_admin` 全部金额展示 | 收敛到 `src/utils/format.js`，给 `formatYuan*` 增加 currency 参数或引入全局当前币种 | `src/utils/format.js:61,72,80`；48 个调用点（§3.1.4） |
| `gv_saas_admin` 标签里的「（元）」 | `withYuanUnit` / `moneyColumnLabel` 是唯一应改点；14 处静态「（元）」应并入 | `src/constants/terms.js:60,63,68`；静态标签 `ktv-config.vue:23,34,71,107,112,131,163,191,210,247`、`pricing-plans.vue:20,46`、`wallet.vue:85` |
| `gv_saas_mobile` B 端 | `src/shared/utils/amount.js:28` 的默认值 + 20 个调用点补传币种（账户/账单响应已带 `currencyCode`） | `src/shared/utils/amount.js:4,28` |
| `gv_saas_mobile` C 端 | `c-end/saas.js:122,128` 的符号表与回退 + 8 处漏传调用点 | `c-end/saas.js:128`；`c-end/app.js:200,222,247,463,665,783,906` |
| `gv_chat_app` | **直接复用 `formatKtvAmount`**，删除 `ktv_shift_screen.dart:43-59` 的本地 ×100//100 实现；把 `ktv_cashier_screen.dart:343`、`ktv_settle_screen.dart:173` 的裸 `bill.currency` 改走符号/中文名 | `lib/models/ktv_models.dart:38-96` |
| `gv_chat_app` i18n | 把 `¥{amount}` 改为「符号由代码注入、ARB 只留占位」；同时清理 20+ 死键避免误导范围 | `lib/l10n/app_zh.arb:527,566,568,949`；`app_en.arb:527,545,566,568,949`；死键 `app_en.arb:436-488,949` |
| `gv_chat_app` mock | 20 处 `currency: 'CNY'` 应从服务端/统一常量取，否则无后端时看不出回归 | `lib/repositories/business/ktv_api_client.dart:541-543,559-561,590,641,647,653,664,668-671,679-685` |
| `gv_chat_admin` / `gv_chat_desktop` | 无改造面；若产品要求承载资金域，属于**新增功能**，应先立项 | §3.4 / §3.5 |

**关键建议**：币种**不能**只做成「前端全局变量 + 格式化函数参数」——因为 (a) 后端 `displayText` 会与前端符号打架（§5.8），(b) 写请求体目前硬编码（§5.10），(c) 报表在后端 SUM（§5.4）。因此必须是「后端提供当前币种 + 后端在响应里回带每笔金额的币种 + 前端只负责渲染符号/小数位」。

### 6.3 必须做**快照固化**的点

| 点 | 为什么必须固化 | 现状 | 证据 |
|---|---|---|---|
| **订单币种** | 已结算订单的展示币种不能被后改的门店设置改写 | **已有** `ord_order.currency_code` | `.../V1__ord_order_baseline.sql:9` |
| **订单金额字段** | 已结算金额不能被后改的价格重算 | **已有**（结台写 `ord_order_item`，账单读快照） | `.../application/BillApplicationService.java:94-97` |
| **包厢计价规则** | 改价不能影响在途/历史会话 | **已有** `ord_ktv_session.billing_rule_snapshot_json` | `.../V1__ord_order_baseline.sql:43`；`.../api/controller/KtvPricingController.java:100-104`（`planFromSnapshot`） |
| **明细价格快照** | 加项单价不能被后改的目录价改写 | **已有** `ord_order_item.price_snapshot_json` + `name_snapshot` | `.../V1__ord_order_baseline.sql:25,28` |
| **支付意图币种** | 收款时的币种不能被后改设置改写 | **已有** `pay_intent.currency_code` | `.../V1__pay_baseline.sql:6` |
| **支付交易币种 + 汇率** | 对账必须能复算 | `currency_code` **已有**，`exchange_rate` **有列无逻辑** | `.../V1__pay_baseline.sql:17-18` |
| **退款币种** | 退款必须退原币种；跨币种退款是合规禁区 | **缺**（`pay_refund` 无币种列） | `.../V1__pay_baseline.sql:23-32`；`docs/renovation/KTV_BUSINESS_01_SERVICE.md:599`（`CURRENCY_CORRIDOR_DISABLED`） |
| **班次现金币种** | 交班长短款必须按币种分别盘点 | **缺**（`pay_shift` 无币种列） | `.../V1__pay_baseline.sql:34-44` |
| **日结汇总币种** | 日结必须能按币种出具 | **缺**（`pay_daily_closing` 无币种列 + `summary_json` 从不写入） | `.../V1__pay_baseline.sql:46-54`；`.../application/CashierApplicationService.java:75-82` |
| **钱包流水币种** | 账本必须自证币种（现在只能回查账户） | **缺**（`cst_wallet_ledger` 无币种列） | `.../V1__cst_customer_baseline.sql:49-65` |
| **券核销币种** | 券的面额必须与订单币种一致才能核销 | **缺**（`mkt_coupon_redemption` 无币种列） | `.../V2__mkt_coupon_redemption.sql:4-20` |
| **报表快照** | 报表若按当前币种重新解释历史 = 篡改 | **缺**（SQL 无币种维度） | `.../ReportMapper.java:35,86-88,105,123` |
| **审计日志金额币种** | 审计必须可复算原始事实 | **缺**（金额是裸字符串） | `.../V1__iam_audit_log.sql:11`；§2.4 的 7 处拼接 |

**「快照固化」与「默认 USD」的直接冲突（需产品拍板）**：
- 现有种子数据全部是 `CNY`（`platform-services/tenant/.../V3__seed_default_init.sql:11`、`V7__seed_a380_tenant.sql:12,16`）。
- 已有订单的 `currency_code` 也是 `CNY`（含硬编码路径 `ReservationApplicationService.java:174`）。
- 若「默认 USD」是**新建租户/门店的默认值**，则历史 `CNY` 数据必须保持 `CNY`（快照语义）。
- 若「默认 USD」是**全平台显示默认**（含历史数据），则与「已结算单据不可改写」直接冲突 → 需要明确「历史数据是否统一改写/是否需要汇率折算」。

### 6.4 建议的改造分层（顺序建议）

1. **先冻结口径**（§5.1）：明确 `decimal(20,6)` 的金额列到底是「分」还是「元」，写进 `docs/standards/`。这是所有后续工作的前提，且是纯文档动作。
2. **激活单一来源**：把 `tnt_store.default_currency` 接上读写（含门店创建/编辑接口），并在网关或 tenant 上下文里把当前币种下发给所有服务。现证据显示该列是死列（§1.3）。
3. **补齐快照列**：按 §6.3 的清单补 `pay_refund` / `pay_shift` / `pay_daily_closing` / `cst_wallet_ledger` / `mkt_coupon_redemption` 的币种列（**新增 Flyway 迁移，不改历史迁移**，遵守 `docs/DATABASE_MIGRATION_STANDARD.md`）。
4. **后端补默认值与校验**：`ReservationApplicationService.java:174`、`WalletApplicationService.java:134`、`MyAssetsController.java:37`、`MemberController.java:62`、`KtvConfigApplicationService.java:102-104`、`RestKtvConfigDomainClient.java:130,315`、`KtvPricingController.java:121-124`（`money()` 改为按币种渲染或改为返回数值 + 由前端渲染）；并校验 `collect` 的 `currencyCode` 必须等于订单币种（当前无校验，`CollectApplicationService.java:117-131`）。
5. **报表加币种维度**：`ReportMapper` 的 4 条聚合 SQL 加 `currency_code` 分组/过滤；`ReportController.java:188-190` 的 `opsKey` 加入币种；`platform-services/order/.../ReportController.java:52-53,115-116` 的 Java 聚合加币种 key。
6. **前端统一格式化**：三端各自收敛到一个函数（§6.2），并同步改造 §3 列出的守卫测试。
7. **测试**：把 §3.1.6 / §3.2.8 / §3.3.7 的断言改为「按币种参数化」，而不是删除。

---

## 7. 证据数量统计汇总

| 章节 | 证据条数（`文件:行号`） | 备注 |
|---|---|---|
| 1. 存储层 | **约 70 条** | 40 个金额列 + 20 条单位注释/种子 + 10 条测试基线/遗留表 |
| 2. 服务端接口 | **约 94 条** | 22 条已有币种 + 22 条缺币种 + 44 条硬编码文案 + 6 条负向证据 |
| 3. 前端 | **约 465 条** | admin ~190 / mobile ~150 / chat_app ~105 / desktop ~12（负向）/ chat_admin ~8（负向） |
| 4. 业务场景 | **约 125 条**（19 条链路） | 每条链路 4–8 条证据 |
| 5. 风险 | **约 135 条**（15 类） | 高危 4 类 |
| 6. 建议 | **约 65 条** | 复用 §1–§5 的证据 |
| **合计** | **约 920 条 `文件:行号` 证据** | — |

扫描覆盖说明（自查）：
- **跑了哪些 pattern**：`amount|price|fee|cost|money|balance|payable|paid|refund|deposit|discount|point|coin|rate|unit_price|subtotal|total`（SQL）；`currency|currencyCode|defaultCurrency|default_currency|CNY|RMB|USD|¥|￥|元|人民币|币种|金额|单价|价格|费率`（全语言）；`formatMoney|formatAmount|fenToYuan|yuanToFen|formatYuan|Intl.NumberFormat|toFixed|toLocaleString|\*100|/100`（前端）；`@Schema|easyexcel|poi|XSSFWorkbook|CsvWriter|小票|打印|receipt`（导出/打印/模板）；`order_?card|redpacket|red_packet|红包|红包|transfer`（IM 消息类型）；`exchangeRate|fx_quote`（汇率）。
- **是否截断**：`gv_im_server` 的 `元|¥` 在 `*.java` 上命中 43 行（已全列）；`gv_saas_admin` 的 `元` 命中 91、`¥` 命中 34（已全列）；`gv_saas_mobile` 的 `¥` 命中 77、`元` 命中 28、`CNY` 命中 19（已全列）。**无截断。**
- **已知污染源**：`gv_saas_admin/.npm-cache/`（347 个文件）被 git 跟踪却未列入 `.gitignore`，会污染全仓 `git grep`；本清单所有 admin 侧统计均已限定 `-- src docs`。
- **未覆盖/受限**：`gv_im_server` 的 `docs/renovation/*`（业务规范，仅按需引用）；各仓库的构建产物（按要求排除）；后端 `displayText` 的实际运行输出（只能通过前端测试夹具 `gv_saas_mobile/c-end/ktv-pricing.test.js:22,53` 间接验证）。

---

## 8. 需要产品/用户拍板的问题（不带猜测结论）

1. **「默认 USD」的作用域是什么？** 是「新建租户/门店的默认值」，还是「全平台界面显示默认（含历史数据）」？前者不影响历史单据，后者与「已结算账单币种不可被后改设置改写」直接冲突。
2. **已有 `CNY` 历史数据如何处理？** 保持 `CNY` 展示（快照语义）／统一改写为 `USD` 显示（会改变历史账面）／按汇率折算后展示？——注意当前仓库**没有任何汇率表与汇率逻辑**（`pay_transaction.exchange_rate` 有列无实现，`cst_wallet_ledger.fx_quote_id` 指向不存在的表）。
3. **`decimal(20,6)` 到底存「分」还是「元」？** 代码注释与实现自相矛盾（`BillApplicationService.java:208` 说「按分存储、不做换算」vs `ReportController.java:26` 说「单位元」），而 `pay_transaction` 的测试用的是带角分的元形态值（`CashierApplicationServiceTest.java:49,69-74`）。需要确认后统一，否则后补的币种列会继承这个歧义。
4. **跨币种是否允许？** 规范写「跨币种默认关闭」并预留 `CURRENCY_CORRIDOR_DISABLED`（`docs/renovation/KTV_BUSINESS_01_SERVICE.md:599`、`SAAS_PLATFORM_01_SERVICE.md:241`）。若关闭，则「订单币种 / 钱包币种 / 门店币种」三者不一致时必须**报错**而不是折算——目前后端**没有任何一致性校验**（`CollectApplicationService.java:117-131`）。
5. **储值账户在切换币种时怎么办？** 现有唯一键是 `(tenant_id, customer_id, legal_entity_id, currency_code)`（`.../V1__cst_customer_baseline.sql:44`），意味着同一会员可以同时拥有 CNY 与 USD 两个钱包。产品是否接受「同一会员两个币种钱包并存」？C 端一次只展示一个还是并列？（当前 C 端硬编码只取 `CNY`：`MyAssetsController.java:37`。）
6. **交班/日结是否必须按币种分别出具？** `pay_shift` 与 `pay_daily_closing` 都没有币种列，且 `expected_cash` 的 SQL 不带币种过滤（`PayIntentMapper.java:15-17`）。如果一天内出现多币种现金收款，现有模型无法区分长短款。
7. **币种由谁切换、在哪切换？** 目前**前端没有任何币种选择入口**（§3.1.5），后端也只有只读字段。是「租户管理员在后台设置里切换」／「平台运营按门店设置」／「用户在自己账号里切换显示币种」？三者对快照固化的要求完全不同。
8. **`gv_chat_admin`（IM 运营后台）与 `gv_chat_desktop` 是否纳入币种范围？** 二者目前**零金额代码**（§3.4/§3.5）。如果产品预期「各业务界面统一更改」包含它们，那么这些界面需要**从零新增**金额能力，而不是改造。
9. **IM 客户端是否要承载订单卡片/红包消息？** 当前消息类型枚举无 `ORDER_CARD`/`RED_PACKET`（`MsgType.java:9-23`），`gv_im_server` 也没有对应表与接口。若未来要做，币种需要在消息体里固化（消息是历史不可变数据）。
10. **金额展示格式的最终形态？** 现在三端三种写法：`'¥ 123.45'`（admin，前缀 + 空格）、`'¥ 123.45'`（mobile）、`'¥123.45'`（chat_app，无空格）。USD 对应是 `'$ 123.45'` / `'$123.45'` / `'US$'`? 千分位是否保留（`formatYuanCompact` vs `formatYuan` 已在同一页面并存）？
11. **`displayText` 是否保留？** 它是当前唯一「服务端直接返回带符号文案」的字段（`KtvPricingController.java:91-124`）。保留则必须后端按币种渲染；废弃则需前端三端各实现一遍分项文案（当前 `c-end/saas.js:246-255` 已有实现，可作为参考）。
12. **`mkt_coupon.discount_value` 的双口径（「金额」或「万分数」）是否要在本次改造中一并拆列？** 证据：`.../V1__mkt_marketing_baseline.sql:32` — `'优惠值(最小货币单位整数或万分数)'`。加币种后会变成「金额 + 币种」与「万分数」的三态，更易错。
13. **各端的 Mock/占位数据是否纳入本次改造范围？** 涉及：`gv_chat_app/lib/repositories/business/ktv_api_client.dart`（20 处 CNY）、`gv_saas_mobile/src/b-end/preview/saas.js:112,113,254`、`gv_saas_mobile/c-end/saas.js:181`、`gv_saas_admin/src/views/platform/pricing-plans.vue:66-71`（占位 `priceMinor 99900/299900/0`）、`gv_saas_admin/docs/ktv-room-board-prototype.html:377-504`（设计稿硬编码 `¥`）。若不纳入，无后端环境下**看不到任何币种回归**。
14. **「币种代码」是否允许直接展示给用户？** 现状有两处会把 `CNY` 原样显示：`gv_chat_app/lib/screens/business/ktv_cashier_screen.dart:343`、`ktv_settle_screen.dart:173`。而 `gv_saas_admin/src/utils/format.test.js:95` 的用例名明确要求「不出现 CNY 裸露在界面」。两端口径需要一致化。
15. **`gv_chat_app` 的 B 端 KTV 收银/结台/班次屏与 `gv_saas_admin` 的同名功能是什么关系？** 前者是实现完整的独立客户端（`lib/screens/business/` 7 个文件、12 处金额渲染），后者是 Web 后台。二者共用同一批后端接口，但**前端格式化实现完全不同**（前者多币种符号表 + 手写千分位，后者单币种 `'¥ '` 前缀）。币种切换是否要求二者行为逐像素一致？
