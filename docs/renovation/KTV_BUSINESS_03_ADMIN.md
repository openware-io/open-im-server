# KTV 业务 PC 后台（Admin）配置方案

> **变更记录（v1）**
> - 首发创建：PC 后台的 KTV 配置细化到可派工，覆盖计价方案、服务人员/资源、支付开关、A380币充值/退还、退款/作废审批、日结复核，对齐 [KTV_BUSINESS_01_SERVICE](KTV_BUSINESS_01_SERVICE.md) 的字段与权限码。

## 0. 方案集声明

| 项目 | 内容 |
| --- | --- |
| 方案集 | `KTV_BUSINESS` |
| 顺序号 | `03` |
| 实施边界 | `ADMIN`（SaaS 租户后台 / PC 管理端） |
| 前置方案 | [KTV_BUSINESS_01_SERVICE](KTV_BUSINESS_01_SERVICE.md)、[SAAS_PLATFORM_02_SERVICE](SAAS_PLATFORM_02_SERVICE.md)（§9 Admin BFF） |
| 后置方案 | [KTV_BUSINESS_04_TEST](KTV_BUSINESS_04_TEST.md) |
| 目标服务 | `platform-admin-service`（SaaS 后台 BFF），不拥有领域权威表 |

## 1. 范围与原则

PC 后台承担「配置 + 审批 + 复核」，不直接写交易/资金权威表；所有写操作经领域服务 API，携带 `Idempotency-Key`，高风险动作写 `iam_audit_log`。

后台按权限区分平台运营后台与租户后台；KTV 配置属**租户后台 / 门店后台**范围（SAAS_PLATFORM_02 §9.1）。

## 2. 菜单与页面清单

| 模块 | 页面 | 权限 | 领域 API |
| --- | --- | --- | --- |
| 商品服务 | 目录/价目 | `catalog.item.manage`/`catalog.price.manage` | `POST /admin/catalog/items`、`POST /admin/catalog/prices` |
| 资源 | 包厢/服务人员 | `resource.manage`/`resource.schedule.manage` | `POST /admin/resources`、`PUT /admin/resources/{id}` |
| 计价方案 | 包厢计价/套餐 | `catalog.price.manage`（**新增**扩展） | 见 §4 |
| 支付 | 支付开关 | `tenant.payment_channel.configure` | `tnt_store_payment_config` |
| 资金 | A380币充值/退还/流水 | `wallet.recharge`/`wallet.refund`/`wallet.view` | `POST /admin/wallets/recharge|refund`（**「储值管理」页**，租户级；不在 KTV 配置页，见 §6） |
| 租户设置 | 储值品牌展示名（配置表项） | `tenant.tenant.manage` | `PUT /admin/tenant/config`（**新增**） |
| 审批 | 退款/作废/暂停修正 | `payment.refund.approve`、`order.void`、`ktv.session.correct_pause` | 见 §7 |
| 日结 | 日结提交/复核 | `daily_close.submit`/`daily_close.review` | `POST /admin/daily-closings/{id}/submit|review` |
| 审计 | 审计查询 | `audit.view` | `GET /admin/audits` |

## 3. 服务人员/资源管理

| 项 | 规格 |
| --- | --- |
| 创建服务人员 | `POST /admin/resources`（`resourceType=KTV_SERVER, resourceCode, name, attributes.catalog_item_id`） |
| 关联目录 | 服务人员费目录 item（`item_type=SERVICE`），配每递增粒度单价/计费单位/递增粒度/舍入方向/`participate_promotion` |
| 停用 | `PUT /admin/resources/{id}/status`（`DISABLED/MAINTENANCE`），需无有效占用 |

> `participate_promotion`（**新增** catalog item 字段）：服务人员费是否参与优惠，对应 01 §4 结论（原 D-5）。

## 4. 计价方案配置

门店计价方案（**新增**配置模型，对应 01 §2）：

| 配置项 | 字段 | 默认 |
| --- | --- | --- |
| 计费单位 | `billing_unit` | `HOUR` |
| 包厢单价 | `unit_price`（按包厢类型/时段分档） | 门店必填 |
| 免费等待 | `free_wait_minutes` | 0 |
| 超时费率 | `overtime_rate` | 1.0 |
| 标准时长 | `default_session_minutes` | 120 |
| 舍入规则 | `rounding_mode`（向上取整到分钟） | 固定 |
| 套餐 | `packages[]`（固定时长固定价，超出按标准单价续费） | 可空 |
| 暂停启用 | `pause_enabled` | false |
| 服务人员计费单位 | `server_billing_unit`（`HOUR`/`HALF_HOUR`） | `HOUR` |
| 服务人员递增粒度 | `server_increment_minutes`（15/30/60） | 30 |
| 服务人员舍入方向 | `server_rounding_direction`（`CONSUMER_FAVOR`/`ROUND_UP`/`FLOOR_BLOCK`） | `CONSUMER_FAVOR` |
| 服务人员单价 | `server_price_per_inc`（每递增粒度单价，最小货币单位整数） | 门店必填 |

## 5. 支付开关配置

| 项 | 规格 |
| --- | --- |
| 数据 | `tnt_store_payment_config`（`merchant_account_id, enabled, refund_enabled, min/max_amount, currency_code`） |
| 默认 | 支付宝/微信/Stripe `enabled=0`（默认关闭）；现金/A380币/积分不依赖渠道配置 |
| 关闭效果 | `available-methods` 不返回线上渠道；请求线上支付返回 `PAYMENT_CHANNEL_DISABLED` |

## 5.1 营业时间配置（**新增，2026-09-19**）

| 项 | 规格 |
| --- | --- |
| 数据 | `tnt_tenant_config`，键 `ktv_business_hours`，值 `HH:mm-HH:mm`（如 `18:00-05:00`）；`store_id=0` 是租户默认，门店行覆盖它 |
| 缺省 | **18:00 – 次日 05:00**（KTV 夜间业态）；`open == close` 表示全天营业 |
| 读 | `GET /admin/tenant/business-hours?storeId=`（返回生效值 + `source=STORE/TENANT/DEFAULT` + `crossesMidnight/allDay/displayText`） |
| 写 | `PUT /admin/tenant/business-hours` body `{storeId, openTime, closeTime}`；门店可不同，权限 `tenant.tenant.manage`，审计 `tenant.business_hours.update` |
| 规则 | 预约**到店时间**必须落在营业时段内（左闭右开、允许跨自然日），由 order 域创建预约时统一校验（422 `RESERVATION_OUT_OF_BUSINESS_HOURS`）；C 端/B 端/后台都走同一创建接口与同一份配置 |
| 页面 | 「KTV 配置 → 营业时间」两行：租户默认 / 当前门店覆盖；「预约管理」展示生效值，越界的历史预约标「非营业时段」 |
| 不改存量 | 修改营业时间不回写已有预约；越界的存量预约只标注，人工确认改期 |

## 6. A380币充值/退还页（租户级「储值管理」，不在 KTV 配置页）

> **入口唯一（2026-09-19 合并）**：储值账户按 `(tenant_id, customer_id, legal_entity_id, currency_code)`
> 唯一，是**租户级**资产，**跨门店共用**同一账户，与门店/ KTV 计价配置无关。因此后台只保留一个入口：
> 租户后台「储值管理」页（`/business/wallet`，菜单项 `wallet`，授权 `payment.method.wallet`），
> 它承载会员储值列表（一次分页 + 批量余额）、充值、退还、真实流水（`cst_wallet_ledger` 逐笔）、代币配置。
> KTV 配置页原先自带的「A380币充值」页签（要求先选门店 + 手填客户 ID，且「流水」是用余额拼出来的一行）
> 与 BFF 端点 `/admin/ktv/wallet-recharge` 已删除，页面只留一个跳转入口 —— 同一功能不再有两套入口与两套口径。

> **品牌展示名可配置**：「A380币」是储值的默认品牌展示名，存租户配置表 `tnt_tenant_config` 的 `wallet_brand_name` 项（默认「A380币」），可自定义（如「皇冠币」），非硬编码；储值管理页标题与文案统一使用该配置值。配置接口 `PUT /admin/tenant/config`（**新增**）读写该表，权限 `tenant.tenant.manage`。

> **账户懒初始化（2026-09-19）**：储值账户**只在首次充值**时创建（`POST /admin/wallets/recharge`）。
> 读路径（会员余额、储值流水、储值管理列表、C 端「我的资产」）对没有账户的会员返回
> **零额只读视图 / 空流水**（`accountOpened=false`，界面显示「未开立」），**不落库、不报 404**：
> 绝大多数会员没有储值，不该被批量建出空账户，也不该让「储值管理」列表逐个查余额时报
> `WALLET_ACCOUNT_NOT_FOUND 储值账户不存在`。扣减类操作（消费/退还）对没有账户的会员按
> `LEDGER_INSUFFICIENT`（余额 0）拒绝，补偿类释放（RELEASE）为无副作用空操作。
> 迁移 `V3__cst_wallet_account_lazy_init_cleanup.sql` 清理历史遗留的空账户
> （可用余额与冻结余额均为 0 **且**没有任何账本流水；有流水或余额的账户一律保留）。

租户配置表 `tnt_tenant_config`（key-value）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `tenant_id` | BIGINT | NOT NULL |
| `config_key` | VARCHAR(64) | `wallet_brand_name` 等 |
| `config_value` | VARCHAR(255) | 值，`wallet_brand_name` 默认 `A380币` |
| `status` | VARCHAR(16) | `ENABLED/DISABLED` |
| `version` | INT | 乐观锁 |
| 审计字段 | — | `created_by/created_at/updated_by/updated_at` |

唯一 `(tenant_id, config_key)`。

| 动作 | API | 说明 |
| --- | --- | --- |
| 充值 | `POST /admin/wallets/recharge` | 现金/线下转账收储值，`cst_wallet_ledger RECHARGE`，禁止改余额；**首次充值即开立账户（懒初始化）** |
| 退还 | `POST /admin/wallets/refund` | `cst_wallet_ledger REFUND`，财务权限 + 审计；没有账户/余额不足回 `LEDGER_INSUFFICIENT` |
| 会员余额 | `GET /business/members/{id}/wallet` | 只读；没有账户返回零额视图（`accountOpened=false`），不报 404 |
| 储值列表 | `GET /business/members/wallets?page&pageSize&keyword` | 「储值管理」列表：一次分页 + 一次批量余额查询，未开立账户的会员余额 0 |
| 储值流水 | `GET /business/members/{id}/wallet/ledger` | 真实账本分页（RECHARGE/CONSUME/REFUND/HOLD/RELEASE/ADJUST + 变动后余额）；没有账户返回空页 |
| ~~KTV BFF 代理~~ | ~~`/admin/ktv/wallet-recharge`~~ | **已删除（2026-09-19）**：与储值管理页重复，且「流水」是用余额拼出来的假流水 |

## 7. 审批与日结复核

| 类型 | API | 说明 |
| --- | --- | --- |
| 退款审批 | `POST /admin/refund-requests/{id}/approve|reject` | 店长/财务；显示申请/可审批金额 |
| 作废 | `POST /admin/orders/{id}/void` | 高风险审批后执行 |
| 暂停修正 | `POST /ktv/sessions/{id}/correct-pause` | 店长/财务；修正 + 审计 |
| 日结 | `POST /admin/daily-closings/{id}/submit|review` | 财务复核；`summary_json` 含现金/A380币/积分分项 |

## 8. 交付物清单

- [ ] 计价方案配置页（包厢单价/计费单位/免费等待/超时费率/标准时长/套餐/暂停开关）。
- [ ] 服务人员资源管理页 + 服务人员费目录/单价/参与优惠开关。
- [ ] 支付开关页（线上渠道默认关闭）。
- [ ] 租户级「储值管理」页：会员储值列表（未开立账户显示「未开立」）+ 充值/退还 + 真实储值流水 + 代币配置（文案用 `wallet_brand_name`）。
- [ ] 储值品牌展示名配置页（租户级，默认「A380币」，可自定义）。
- [ ] 退款/作废/暂停修正审批页 + 日结复核页。

## 9. 变更清单

- [ ] 新增计价方案配置模型与 Flyway（01 §17.2）。
- [ ] catalog item 新增 `participate_promotion` 字段。
- [ ] 新增权限码 `wallet.recharge/refund/view`、`ktv.session.correct_pause`、`payment.refund.offline` 注册。
- [ ] 新增 `tnt_tenant_config` 配置表（`wallet_brand_name` 项，默认「A380币」）+ `PUT /admin/tenant/config` 配置接口。
- [ ] 与 B 端 App（02）联调：价目/服务人员/支付开关生效。