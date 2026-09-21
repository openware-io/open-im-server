# SaaS 多业态核心数据设计

> **变更记录（v2）**：①新增 `platform-identity-service`（`idt_` 前缀，SaaS 平台账号/OAuth 绑定/资料同步），`user` 前缀归属 IM 域；②首发业态收敛为 KTV，仅建 `ord_ktv_session`，`ord_hotel_stay`/`ord_spa_session` 后续不建表。

## 1. 设计约束

本文是 `SAAS_PLATFORM_02_SERVICE` 的表级实施基线。字段命名以数据库 `snake_case` 为准，Java 使用 `camelCase`。本文件遵循 [业务微服务 DDD 工程规范](../standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)、[工程规范](../ENGINEERING_RULES.md) 和[持久层技术栈标准](../standards/21_PERSISTENCE_STACK.md)。

> **IM 与 SaaS 数据库独立**：本文只描述 SaaS 产品数据库；IM 产品数据库（现有 `gv_im`）独立演进。SaaS 内部多租户采用共享库 + 行级租户隔离（不为每个租户建独立库）。现有 `ord_` 预约数据随账号归属从 IM 库迁至 SaaS 库（见 `SAAS_PLATFORM_07_EXECUTION` E0）。

除特别说明外：

- 主键使用 `BIGINT UNSIGNED AUTO_INCREMENT`，服务端对外序列化为字符串；时间使用 `DATETIME(3)` UTC 存储，展示按门店时区转换。
- 金额使用 `DECIMAL(20,6)`，同时保存 `currency_code CHAR(3)`；支付渠道原始金额、汇率和费率必须留快照。
- 租户业务表必须含 `tenant_id BIGINT UNSIGNED NOT NULL`、`created_by BIGINT UNSIGNED NOT NULL DEFAULT 0`、`created_at DATETIME(3) NOT NULL`、`updated_by BIGINT UNSIGNED NOT NULL DEFAULT 0`、`updated_at DATETIME(3) NOT NULL`、`version INT NOT NULL DEFAULT 0`；软删除表另含 `deleted_at DATETIME(3) NULL`。平台表不带 `tenant_id`，但保留同一审计字段。
- 业务唯一键必须带租户边界；跨服务不建物理外键，只保存对方稳定 ID，并由应用层校验归属。
- `status` 使用明确字符串枚举；二值开关使用 `TINYINT UNSIGNED` 并加 `CHECK (enabled IN (0,1))`。
- 所有资金、积分、储值流水追加写入，不更新历史流水；余额由账本或汇总投影得到。
- 每张 Flyway 建表语句必须显式声明 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`，并为表、字段、索引写中文业务注释；索引统一命名为 `uk_<table>_<semantic>`、`idx_<table>_<semantic>`。
- JSON 仅允许保存不可查询的规则/快照/扩展数据；任何筛选、唯一性、对账或状态判断所需事实必须拆为结构化列或子表。

## 2. 服务与表归属

| 服务 | 表前缀 | 核心表 |
| --- | --- | --- |
| identity | `idt_` | SaaS 平台账号、登录标识、OAuth 绑定、资料同步 |
| tenant | `tnt_` | 租户、组织、门店、商户主体、终端 |
| IAM | `iam_` | 角色、权限、授权、审计 |
| resource | `res_` | 资源、排班、占用 |
| order | `ord_` | 预约、统一订单、明细、KTV 履约（首发）/酒店/足浴（后续） |
| payment | `pay_` | 支付、退款、押金、班次、日结 |
| customer | `cst_` | 租户客户、会员、积分、权益、储值 |
| marketing | `mkt_` | 活动、优惠券、发放、同意、触达任务 |
| each domain | `*_outbox` | 本服务事务 Outbox |

`ord_` 是既有订单迁移方案已经冻结的兼容前缀；不得将其复制到新域。领域与表前缀的权威登记见 [工程规范中的登记表](../ENGINEERING_RULES.md#领域与数据库表前缀登记表)，不得再创建同义前缀；既有库表不因本次规范统一而重命名。未来新增领域，须先在该登记表及其领域设计中补充前缀，再创建 Flyway 表。

## 3. 租户与授权表

### 3.1 `tnt_tenant`

| 字段 | 类型 | 约束/说明 |
| --- | --- | --- |
| `id` | BIGINT | PK |
| `tenant_code` | VARCHAR(32) | UNIQUE，全局租户编码 |
| `name` | VARCHAR(128) | NOT NULL |
| `status` | VARCHAR(24) | `PENDING/ACTIVE/SUSPENDED/CLOSED` |
| `default_locale` | VARCHAR(16) | 默认语言 |
| `default_timezone` | VARCHAR(64) | IANA 时区 |
| `created_by` | BIGINT | 平台操作者 |

索引：`uk_tenant_code`、`idx_tenant_status_created(status,created_at)`。

### 3.2 `tnt_organization`、`tnt_store`

`tnt_organization`：`id`、`tenant_id`、`code`、`name`、`status`、审计字段；唯一 `(tenant_id, code)`。

`tnt_store`：`id`、`tenant_id`、`organization_id`、`code`、`name`、`business_type VARCHAR(32)`（业态，`KTV/HOTEL/SPA/MASSAGE/RETAIL`，可注册扩展）、`country_code CHAR(2)`、`region_code VARCHAR(32)`、`timezone VARCHAR(64)`、`default_currency CHAR(3)`、`locale VARCHAR(16)`、`tax_profile_id`、`business_day_cutoff TIME`、`status`、审计字段；唯一 `(tenant_id, code)`，索引 `(tenant_id, organization_id, status)`、`(tenant_id, business_type, status)` 和 `(tenant_id, country_code, default_currency)`。

### 3.3 `tnt_legal_entity`、`tnt_merchant_account`

`tnt_legal_entity`：`id`、`tenant_id`、`registered_country`、`legal_name`、`tax_number_cipher`、`settlement_currency`、`kyc_status`、`sensitive_version`、审计字段。

`tnt_merchant_account`：`id`、`tenant_id`、`legal_entity_id`、`provider`（`CASH/ALIPAY/WECHAT/STRIPE`）、`environment`、`merchant_no_cipher`、`secret_cipher`、`status`、`last_verified_at`、`capability_snapshot_json`、审计字段；唯一 `(tenant_id, legal_entity_id, provider, environment)`。`capability_snapshot_json` 仅保存渠道原始能力快照，不作为查询条件；密钥列禁止返回 API。

`tnt_store_payment_config`：`id`、`tenant_id`、`store_id`、`merchant_account_id`、`display_name`、`sort_no`、`payment_mode`、`enabled`、`refund_enabled`、`min_amount`、`max_amount`、`currency_code`、`version`；一条记录只对应一个币种，唯一 `(tenant_id, store_id, merchant_account_id, currency_code)`。

`tnt_tenant_config`：`id`、`tenant_id`、`config_key`、`config_value`、`status`、`version`、审计字段；唯一 `(tenant_id, config_key)`。租户级 key-value 配置表，承载 `wallet_brand_name`（储值币/代币展示名，默认 `A380币`）等可自定义配置。

`tnt_business_type`：`id`、`code`（`KTV/HOTEL/SPA/MASSAGE/RETAIL`...，全局唯一）、`name`、`status`、审计字段；唯一 `code`。业态枚举表（可配），每个业态的履约聚合/资源类型/计价方案在代码注册；服务端白名单校验。

## 4. IAM 表

### 4.1 `iam_permission`、`iam_role`、`iam_role_permission`

权限：`id`、`code`、`module`、`resource`、`action`、`description`、`status`；`code` 全局唯一。

角色：`id`、`tenant_id NULL`（平台角色为空）、`code`、`name`、`role_type`（`sdk/PRESET/TENANT`）、`copy_from_role_id`、`status`；唯一 `(tenant_id,code)`。

角色权限：`role_id`、`permission_id` 联合主键。租户自定义角色只能拥有当前租户已拥有的权限。

### 4.2 `iam_user_role`

字段：`id`、`account_id`、`tenant_id`、`organization_id NULL`、`store_id NULL`、`role_id`、`scope_type`（`TENANT/ORGANIZATION/STORE/SELF`）、`effective_from`、`effective_to`、`status`、`authorization_version`、审计字段。

索引 `(account_id, status)`、`(tenant_id, organization_id, store_id, status)`。角色变更必须递增授权版本并写 `iam_audit_log`。

## 5. 资源表

### 5.1 `res_resource`

字段：`id`、`tenant_id`、`store_id`、`resource_type`（首发 `KTV_ROOM` 包厢、`KTV_SERVER` 服务人员；`HOTEL_ROOM/SPA_ROOM/THERAPIST` 为后续业态）、`resource_code`、`name`、`parent_id NULL`、`capacity`、`status`、`attributes_json`、审计字段；唯一 `(tenant_id, store_id, resource_type, resource_code)`。

### 5.2 `res_schedule`、`res_occupation`

排班：`id`、`tenant_id`、`store_id`、`resource_id`、`start_at`、`end_at`、`schedule_type`、`status`、`source`、审计字段；索引 `(tenant_id, resource_id, start_at, end_at)`。

占用：`id`、`tenant_id`、`store_id`、`resource_id`、`source_type`、`source_id`、`start_at`、`end_at`、`status`（`HELD/RESERVED/IN_USE/RELEASED/CANCELLED`）、`hold_expires_at`、`version`；索引 `(tenant_id, resource_id, start_at, end_at, status)`。事务内通过锁定同一资源的有效占用记录完成冲突判断。

## 6. 订单与履约表

### 6.1 `ord_order`

字段：`id`、`tenant_id`、`organization_id`、`store_id`、`order_no`、`business_type`（`HOTEL/KTV/SPA/RETAIL`）、`customer_id NULL`、`status`、`currency_code`、`subtotal_amount`、`discount_amount`、`tax_amount`、`total_amount`、`paid_amount`、`refundable_amount`、`created_by`、`completed_at`、`cancelled_at`、`version`、审计字段。

唯一 `(tenant_id, order_no)`；索引 `(tenant_id, store_id, status, created_at)`、`(tenant_id, customer_id, created_at)`。

**单号规则（新建单据，2026-09 起）**：`order_no = O<yyyyMMdd><当日序号>`，如 `O202609190001`。

- 前缀 `O`（订单）/ `R`（预约）区分单据类型；历史单号（`O<毫秒时间戳>`、`R<UUID 前 20 位>`）**原样保留**，不回填、不重排。
- 日期段 `yyyyMMdd` 是**门店营业日**（`Asia/Shanghai` + 04:00 营业日切点，`StoreTimeService` 是唯一实现），凌晨 04:00 前的单据归前一营业日。
- 当日序号**按租户**（不含门店）每日从 `0001` 递增，最小定宽 4 位；当日超过 9999 单**自然加宽**（`O2026091910000`），不回绕、不截断。序号由 `ord_daily_serial` 在数据库内分配（行锁 + 唯一键），并发不重号。
- 为什么序号不含门店：唯一键是 `(tenant_id, order_no)`，若各门店各自从 `0001` 开始，同租户两个门店同日会生成同一个单号并撞唯一键；要让门店可区分只能把门店编码写进单号，而本格式不含该位。
- 失败语义：序号表不可用 → 503 `DOC_NO_SEQUENCE_UNAVAILABLE`（**失败关闭**），不降级为时间戳/UUID。
- 空洞：发号与单据落库同事务（预约创建/预约开台），事务回滚时序号一并回滚；仍有两类残留空号：① 并发同 `Idempotency-Key` 重试中抢号失败的那一路（捕获唯一键冲突后返回既有预约、事务提交，已分配的号作废）；② 「快速开台」`POST /business/orders` 不是事务方法，发号先行提交，随后的订单 INSERT 失败也会作废一个号。只保证递增与唯一，不保证连续，**永不回绕复用**。

### 6.1.1 `ord_daily_serial`（单据号每日序号）

字段：`id`、`tenant_id`、`biz_type`（`ORDER`/`RESERVATION`）、`business_date`（营业日 `DATE`）、`current_seq`（该营业日已分配到的序号）、`created_at`、`updated_at`；唯一 `(tenant_id, biz_type, business_date)`。

只服务新建单据的号分配，不参与业务查询；迁移 `V27__ord_daily_serial.sql` 只建表，不 UPDATE 任何历史行。

### 6.2 `ord_order_item`

字段：`id`、`tenant_id`、`order_id`、`item_type`（`PRODUCT/SERVICE/PACKAGE/ROOM_FEE/ADD_ON/TAX/FEE`）、`catalog_item_id`、`resource_id NULL`、`name_snapshot`、`unit_price`、`quantity`、`discount_amount`、`tax_amount`、`total_amount`、`price_snapshot_json`、`status`、审计字段。唯一不强制，按业务允许重复商品明细。

### 6.3 专属履约表

首发仅建设 `ord_ktv_session`；`ord_hotel_stay`、`ord_spa_session` 为后续业态，首发不建表、不建 Flyway（仅保留领域预留）。

`ord_ktv_session`（首发）：`id`、`tenant_id`、`order_id`、`room_resource_id`、`reserved_start_at`、`reserved_end_at`、`opened_at`、`closed_at`、`billing_unit`（`HOUR/HALF_HOUR/PACKAGE`，计费单位）、`billing_start_at`（起算时间）、`free_wait_minutes`（免费等待，默认 0）、`paused_seconds`（暂停不计费时长）、`overtime_rate`（超时费率 DECIMAL，默认 1.0）、`billing_rule_snapshot_json`（计费规则快照）、`status`（`RESERVED/OPEN/PAUSED/CLOSED/CANCELLED`）、`version`。索引 `(tenant_id, room_resource_id, status)`；结台金额由 Order 结算服务按快照计算。

`ord_ktv_server_session`（首发，服务人员点单）：`id`、`tenant_id`、`order_id`、`ktv_session_id`（关联包厢会话）、`server_resource_id`（`KTV_SERVER` 资源）、`catalog_item_id`（服务目录 item）、`ordered_at`/`started_at`/`ended_at`、`billing_unit`（`HOUR/HALF_HOUR`，默认 `HOUR`）、`increment_minutes`（递增粒度，默认 30）、`rounding_direction`（`CONSUMER_FAVOR/ROUND_UP/FLOOR_BLOCK`，默认让利）、`price_per_inc`（每递增粒度单价，最小货币单位）、`duration_seconds`、`duration_minutes`、`total_amount`、`price_snapshot_json`、`status`（`ORDERED/SERVING/ENDED/CANCELLED`）、`version`；服务人员费并入订单结算。

`ord_hotel_stay`（后续）：`id`、`tenant_id`、`order_id`、`room_resource_id`、`room_type_id`、`guest_name`、`guest_document_cipher`、`check_in_at`、`planned_check_out_at`、`actual_check_out_at`、`stay_type`（`OVERNIGHT/HOURLY`）、`status`（`RESERVED/CHECKED_IN/STAYING/CHECKED_OUT/CANCELLED`）、`deposit_account_id`、`version`。

`ord_spa_session`（后续）：`id`、`tenant_id`、`order_id`、`room_resource_id`、`therapist_resource_id`、`service_start_at`、`service_end_at`、`extra_minutes`、`status`（`WAITING/ASSIGNED/SERVING/COMPLETED/CANCELLED`）。

`ord_reservation`：`id`、`tenant_id`、`store_id`、`reservation_no`、`customer_id NULL`、`business_type`、`resource_id NULL`、`start_at`、`end_at`、`party_size`、`status`（`PENDING/CONFIRMED/ARRIVED/CANCELLED/NO_SHOW/CONVERTED`）、`order_id NULL`、审计字段。预约保持独立生命周期。

唯一 `(tenant_id, reservation_no)`、唯一 `(tenant_id, idempotency_key)`（V3）。`reservation_no = R<yyyyMMdd><当日序号>`（如 `R202609190001`），口径与订单号完全一致（营业日 + 租户维度当日序号 + 失败关闭，见 §6.1）；同 Idempotency-Key 重试返回既有预约的原号，不换号也不消耗新序号。

## 7. 支付、押金与班次表

### 7.1 `pay_intent`、`pay_transaction`

意图：`id`、`tenant_id`、`store_id`、`order_id`、`merchant_account_id`、`provider`、`payment_method`、`amount`、`currency_code`、`status`（`CREATED/PROCESSING/SUCCEEDED/FAILED/CLOSED`）、`idempotency_key`、`expires_at`、审计字段；唯一 `(tenant_id, idempotency_key)`。

交易：`id`、`tenant_id`、`payment_intent_id`、`provider_transaction_no`、`provider_payload_digest`、`amount`、`currency_code`、`exchange_rate`、`fee_amount`、`status`、`occurred_at`、`raw_reference`；唯一 `(provider, provider_transaction_no)`，禁止保存完整卡号和密钥。

### 7.2 `pay_refund`、`pay_deposit_ledger`

退款：`id`、`tenant_id`、`order_id`、`payment_transaction_id`、`request_id`、`requested_amount`、`approved_amount`、`provider_refund_no`、`status`（`REQUESTED/APPROVED/PROCESSING/SUCCEEDED/FAILED/CANCELLED`）、`reason`、`requested_by`、`approved_by`、审计字段；唯一 `(tenant_id, request_id)`。

押金流水：`id`、`tenant_id`、`order_id`、`payment_transaction_id NULL`、`entry_type`（`COLLECT/APPLY/REFUND/REVERSE`）、`amount`、`currency_code`、`balance_after`、`reference_id`、`operator_id`、`occurred_at`。余额由流水汇总，不允许编辑。

### 7.3 `pay_shift`、`pay_daily_closing`

班次：`id`、`tenant_id`、`store_id`、`terminal_id`、`operator_id`、`opened_at`、`closed_at`、`opening_cash`、`expected_cash`、`actual_cash`、`difference_amount`、`status`（`OPEN/CLOSING/CLOSED/REVIEW_REQUIRED`）。

日结：`id`、`tenant_id`、`store_id`、`business_date`、`submitted_by`、`reviewed_by`、`status`（`DRAFT/SUBMITTED/REVIEWED/REOPENED`）、`summary_json`、审计字段；唯一 `(tenant_id, store_id, business_date)`。

## 8. 客户、会员与储值表

### 8.1 `cst_customer`、`cst_membership`

客户：`id`、`tenant_id`、`account_id NULL`、`customer_no`、`name_cipher`、`phone_cipher`、`phone_digest`、`email_cipher`、`status`、`marketing_consent_version`、审计字段；唯一 `(tenant_id, phone_digest)`，允许待认领客户无 `account_id`。

> **实现现状（2026-09-19）**：首发把「客户 + 会员档案」合并落在一张 `cst_member`（`id, tenant_id, account_id NULL,
> member_no, name_cipher, phone_cipher, phone_digest, level_id, status, joined_at, expires_at, version, 审计字段`），
> 因为**会员等级/权益/成长值业务尚未实现**，这张表实际存的是**客户**，后台入口统一叫「客户管理」。
> 新增 IM 绑定列：`im_account`（IM 登录标识，如 `im_71`/openId）、`im_username`（IM 用户名/昵称快照）、
> `im_bound_at`；唯一 `(tenant_id, im_account)`，`(tenant_id, account_id)` 在「存在被业务引用的重复客户」的库上
> 退化为普通索引并由应用层保证唯一（见 `V6__cst_member_dedup_cleanup.sql` 的说明）。
>
> 姓名密文（随机 IV AES-GCM）不可 LIKE，因此**姓名检索走盲索引表** `cst_member_name_token`
> （`id, tenant_id, member_id, token char(64), created_at`；唯一 `(tenant_id, member_id, token)`，索引 `(tenant_id, token)`）：
> token = 归一化姓名的 1/2/3-gram 的 SHA-256，查询要求关键词的全部 gram 命中 ⇒ 「包含」语义；客户号同样进这张表。
> 存量客户由 `MemberNameIndexRebuildJob` 回填（只处理无 token 行的客户，可重入）。

会员：`id`、`tenant_id`、`customer_id`、`program_id`、`level_id`、`joined_at`、`expires_at`、`status`；唯一 `(tenant_id, customer_id, program_id)`。

### 8.2 `cst_point_account`、`cst_point_ledger`

账户：`id`、`tenant_id`、`customer_id`、`program_id`、`available_points`、`frozen_points`、`version`；唯一 `(tenant_id, customer_id, program_id)`。

流水：`id`、`tenant_id`、`account_id`、`entry_type`（`EARN/REDEEM/EXPIRE/ADJUST/REVERSE`）、`points`、`balance_after`、`business_type`、`business_id`、`idempotency_key`、`rule_snapshot_json`、`occurred_at`；唯一 `(tenant_id, idempotency_key)`。

### 8.3 `cst_wallet_account`、`cst_wallet_ledger`

> **术语**：业务本质是「储值币/代币」，**默认展示名「A380币」**（`tnt_tenant_config.wallet_brand_name`，租户级可自定义，非硬编码）。作为默认支付方式之一，充值走账本追加（`RECHARGE`），禁止直接改余额。

账户：`id`、`tenant_id`、`customer_id`、`legal_entity_id`、`currency_code`、`available_amount`、`frozen_amount`、`status`、`version`；唯一 `(tenant_id, customer_id, legal_entity_id, currency_code)`。

流水：`id`、`tenant_id`、`wallet_account_id`、`entry_type`（`RECHARGE/CONSUME/REFUND/HOLD/RELEASE/ADJUST`）、`amount`、`balance_after`、`order_id NULL`、`fx_quote_id NULL`、`idempotency_key`、审计字段。首发只允许同币种、同主体使用。

## 9. 营销、同意、审计与 Outbox

`mkt_campaign`：`id`、`tenant_id NULL`（平台活动为空）、`campaign_type`、`name`、`start_at`、`end_at`、`budget_amount`、`funding_party`、`status`、`rule_snapshot_json`。适用范围使用 `mkt_campaign_scope` 子表，不以 JSON 查询。

`mkt_coupon`：`id`、`tenant_id`、`campaign_id`、`code_digest`、`discount_type`、`discount_value`、`min_amount`、`max_discount`、`usage_limit`、`per_customer_limit`、`status`；适用范围使用 `mkt_coupon_scope` 子表；兑换码只存摘要。

`mkt_coupon_grant`：`id`、`tenant_id`、`coupon_id`、`customer_id`、`issued_at`、`used_at`、`order_id NULL`、`status`；唯一 `(tenant_id, coupon_id, customer_id)`。

`mkt_consent`：`id`、`account_id`、`tenant_id NULL`、`consent_type`、`channel`、`policy_version`、`granted`、`source`、`occurred_at`、`withdrawn_at`；营销授权和履约通知分开。

`iam_audit_log`：`id`、`tenant_id NULL`、`operator_id`、`action`、`resource_type`、`resource_id`、`before_digest`、`after_digest`、`reason`、`request_id`、`occurred_at`。禁止修改和物理删除。

`{domain}_outbox`：`id`、`event_id`、`tenant_id`、`aggregate_type`、`aggregate_id`、`event_type`、`schema_version`、`payload_json`、`status`、`attempts`、`next_retry_at`、`published_at`；唯一 `event_id`。载荷仅用于可靠投递，不作为业务查询字段。

## 10. 默认初始化数据

租户开通事务创建：默认组织、首门店、预置角色权限、默认收银终端/班次模板、基础订单状态、首门店币种积分计划、会员基础等级、权益模板和审计配置。支付生产账户、跨币种钱包、平台积分运营、自动退款和营销自动旅程默认关闭。

## 11. 建表与验收清单

- [ ] 每张表通过租户缺失、跨租户查询、唯一键和索引测试。
- [ ] 状态枚举与状态机文档一致，未知状态拒绝写入。
- [ ] 金额、币种、汇率和快照字段通过精度与舍入测试。
- [ ] 账本流水只追加，重复幂等键不产生第二笔事实。
- [ ] Flyway 空库、重复启动、备份恢复和升级测试通过。
