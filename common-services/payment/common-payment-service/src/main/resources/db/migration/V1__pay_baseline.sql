-- 支付与收银基线（基线重建批次 2：原 V1~V15 合并为唯一 V1 的累计最终态）
--
-- 合并口径说明（对照原 V1~V15 逐条核对）：
--   V1  pay_baseline                —— pay_intent / pay_transaction / pay_refund / pay_shift / pay_daily_closing
--   V2  pay_channel_config          —— pay_channel_config
--   V3  pay_transaction_provider    —— pay_transaction.provider（已折进 CREATE TABLE）
--   V4  pay_collect_idempotency     —— pay_collect
--   V5  pay_event_outbox            —— pay_event_outbox
--   V6  pay_event_consumed          —— pay_event_consumed
--   V7  pay_refund_status_pending   —— pay_refund.status 默认值 REQUESTED→PENDING（已折进 CREATE TABLE；
--                                      原 ALTER 后的 UPDATE `status`='PENDING' WHERE 'REQUESTED' 在清库重建下无行可改，故省略）
--   V8  seed_a380_payment_config    —— pay_channel_config 种子（保留在本文件末尾）
--   V9  tenant_payment_method       —— 表名改为已登记前缀 `tnt_payment_method`（原 tenant_ 前缀未登记）
--   V10 pay_currency_snapshot       —— 各表 currency_code 快照列 + 历史行回填 USD（已折进 CREATE TABLE；
--                                      回填 UPDATE 在清库重建下无行可改，故省略）
--   V11 pay_store_attribution       —— pay_collect/pay_transaction/pay_refund 的 store_id + 组合索引
--   V12 pay_channel_scope           —— pay_channel_config 的 business_type/version/idempotency_key
--                                      （原 DROP KEY uk_pay_channel_tenant_store_channel 已并入最终唯一键集合）
--   V13 pay_refund_rule_config      —— pay_refund.business_type + pay_refund_rule_config
--   V14 pay_daily_closing_rule_config —— pay_daily_closing_rule_config
--   V15 pay_fact_business_attribution —— pay_collect/pay_transaction 的 business_type + 组合索引
--
-- 本次同时执行的规范修正：
--   1. 补齐 4 个审计字段（created_by / updated_by / created_at / updated_at）——原 pay_transaction、
--      pay_collect、pay_event_outbox、pay_event_consumed、tenant_payment_method 缺失；
--   2. 索引名统一为 `<前缀>_<完整表名>_<语义>`（原 uk_pay_refund_rule_* / uk_pay_daily_closing_rule_* 等
--      未含完整表名；uk_pay_daily_tenant_store_date → uk_pay_daily_closing_tenant_store_date；
--      uk_pay_collect_idem → uk_pay_collect_tenant_idem；uk_pay_channel_tenant_store_type →
--      uk_pay_channel_config_tenant_store_type；uk_pay_channel_idempotency →
--      uk_pay_channel_config_idempotency）；
--   3. 标识符统一加反引号；列/表 COMMENT 补齐；
--   4. `CREATE TABLE IF NOT EXISTS` 去除（基线只执行一次，IF NOT EXISTS 会掩盖半建表状态）。

-- ---------------------------------------------------------------------------
-- 支付意图
-- ---------------------------------------------------------------------------
CREATE TABLE `pay_intent` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `order_id` bigint unsigned NOT NULL COMMENT '订单ID',
  `merchant_account_id` bigint unsigned NULL COMMENT '商户账户ID',
  `provider` varchar(16) NOT NULL COMMENT 'CASH/WALLET/POINT/ALIPAY/WECHAT/STRIPE',
  `payment_method` varchar(32) NOT NULL COMMENT '支付方式',
  `amount` decimal(20,6) NOT NULL COMMENT '支付金额',
  `currency_code` char(3) NOT NULL COMMENT '币种（ISO 4217，收款币种必须等于订单币种）',
  `status` varchar(24) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/PROCESSING/SUCCEEDED/FAILED/CLOSED',
  `idempotency_key` varchar(64) NOT NULL COMMENT '幂等键（租户内分腿唯一）',
  `expires_at` datetime(3) NULL COMMENT '过期时间',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_intent_tenant_idem` (`tenant_id`, `idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付意图';

-- ---------------------------------------------------------------------------
-- 支付交易（V3 的 provider、V11 的 store_id、V15 的 business_type 已折进本表）
-- ---------------------------------------------------------------------------
CREATE TABLE `pay_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NULL COMMENT '交易发生门店；与支付意图上下文一致',
  `business_type` varchar(32) NULL COMMENT '交易发生业态快照',
  `payment_intent_id` bigint unsigned NOT NULL COMMENT '支付意图ID',
  `provider` varchar(16) NULL COMMENT '支付渠道',
  `provider_transaction_no` varchar(128) NULL COMMENT '渠道交易流水号',
  `provider_payload_digest` varchar(128) NULL COMMENT '渠道回执摘要（不落敏感原文）',
  `amount` decimal(20,6) NOT NULL COMMENT '交易金额',
  `currency_code` char(3) NOT NULL COMMENT '币种（ISO 4217）',
  `exchange_rate` decimal(20,6) NULL COMMENT '汇率',
  `fee_amount` decimal(20,6) NULL COMMENT '手续费金额',
  `status` varchar(24) NOT NULL COMMENT 'SUCCEEDED/FAILED/REFUNDED',
  `occurred_at` datetime(3) NOT NULL COMMENT '交易发生时间',
  `raw_reference` varchar(255) NULL COMMENT '渠道原始引用（脱敏后）',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_transaction_provider_no` (`provider_transaction_no`),
  KEY `idx_pay_transaction_tenant_store_occurred` (`tenant_id`, `store_id`, `occurred_at`),
  KEY `idx_pay_transaction_tenant_store_type_occurred` (`tenant_id`, `store_id`, `business_type`, `occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付交易';

-- ---------------------------------------------------------------------------
-- 退款（V7 的状态默认值、V10 的 currency_code、V11 的 store_id、V13 的 business_type 已折进本表）
-- ---------------------------------------------------------------------------
CREATE TABLE `pay_refund` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NULL COMMENT '退款申请所属门店；历史不可判定时为空',
  `business_type` varchar(32) NOT NULL DEFAULT '' COMMENT '退款申请业态编码，空字符串表示未指定',
  `order_id` bigint unsigned NOT NULL COMMENT '订单ID',
  `payment_transaction_id` bigint unsigned NULL COMMENT '原支付交易ID',
  `request_id` varchar(64) NOT NULL COMMENT '退款申请号（租户内唯一）',
  `requested_amount` decimal(20,6) NOT NULL COMMENT '申请退款金额',
  `approved_amount` decimal(20,6) NULL COMMENT '审批通过金额',
  `currency_code` varchar(3) NOT NULL DEFAULT 'USD' COMMENT '币种快照（退款退原币种：取原订单/原支付流水币种，缺省 USD）',
  `provider_refund_no` varchar(128) NULL COMMENT '渠道退款流水号',
  `status` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/REFUNDED',
  `reason` varchar(255) NULL COMMENT '退款原因',
  `requested_by` bigint unsigned NULL COMMENT '申请人',
  `approved_by` bigint unsigned NULL COMMENT '审批人',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_refund_tenant_req` (`tenant_id`, `request_id`),
  KEY `idx_pay_refund_tenant_store_created` (`tenant_id`, `store_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='退款';

-- ---------------------------------------------------------------------------
-- 收银班次（V10 的 currency_code 已折进本表）
-- ---------------------------------------------------------------------------
CREATE TABLE `pay_shift` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `terminal_id` bigint unsigned NOT NULL COMMENT '终端ID',
  `operator_id` bigint unsigned NOT NULL COMMENT '收银员ID',
  `opened_at` datetime(3) NOT NULL COMMENT '开班时间',
  `closed_at` datetime(3) NULL COMMENT '交班时间',
  `opening_cash` decimal(20,6) NOT NULL DEFAULT 0 COMMENT '备用金',
  `expected_cash` decimal(20,6) NOT NULL DEFAULT 0 COMMENT '应有现金',
  `actual_cash` decimal(20,6) NULL COMMENT '实盘现金',
  `difference_amount` decimal(20,6) NULL COMMENT '长短款金额',
  `currency_code` varchar(3) NOT NULL DEFAULT 'USD' COMMENT '币种快照（本班次现金盘点币种；不同币种不得合并交班）',
  `status` varchar(24) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/CLOSING/CLOSED/REVIEW_REQUIRED',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_pay_shift_store` (`tenant_id`, `store_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='收银班次';

-- ---------------------------------------------------------------------------
-- 日结（V10 的 currency_code 已折进本表）
-- ---------------------------------------------------------------------------
CREATE TABLE `pay_daily_closing` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NOT NULL COMMENT '门店ID',
  `business_date` date NOT NULL COMMENT '营业日',
  `submitted_by` bigint unsigned NULL COMMENT '提交人',
  `reviewed_by` bigint unsigned NULL COMMENT '复核人',
  `status` varchar(24) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/SUBMITTED/REVIEWED/REOPENED',
  `summary_json` json NULL COMMENT '日结汇总快照',
  `currency_code` varchar(3) NOT NULL DEFAULT 'USD' COMMENT '币种快照（日结出具币种；跨币种需分币种日结）',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_daily_closing_tenant_store_date` (`tenant_id`, `store_id`, `business_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='日结';

-- ---------------------------------------------------------------------------
-- 线上支付渠道配置（V12 的 business_type/version/idempotency_key 已折进本表；
-- 原 uk_pay_channel_tenant_store_channel 已被 V12 DROP，最终只保留下列两个唯一键）
-- ---------------------------------------------------------------------------
CREATE TABLE `pay_channel_config` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NULL COMMENT 'NULL=租户级',
  `business_type` varchar(32) NOT NULL DEFAULT '' COMMENT '业态编码，空字符串=租户默认',
  `channel` varchar(16) NOT NULL COMMENT 'ALIPAY/WECHAT/STRIPE',
  `enabled` tinyint NOT NULL DEFAULT 0 COMMENT '0=关 1=开',
  `merchant_id` varchar(64) NULL COMMENT '渠道商户号',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `idempotency_key` varchar(96) NULL COMMENT '配置写入幂等键',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_channel_config_tenant_store_type` (`tenant_id`, `business_type`, `store_id`, `channel`),
  UNIQUE KEY `uk_pay_channel_config_idempotency` (`tenant_id`, `idempotency_key`, `channel`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='线上支付渠道配置';

-- ---------------------------------------------------------------------------
-- 组合收款幂等（V10 的 currency_code、V11 的 store_id、V15 的 business_type 已折进本表）
-- ---------------------------------------------------------------------------
CREATE TABLE `pay_collect` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NULL COMMENT '收款发生门店；历史不可判定时为空',
  `business_type` varchar(32) NULL COMMENT '收款发生业态快照',
  `collect_no` varchar(64) NOT NULL COMMENT '组合收款单号',
  `order_id` bigint unsigned NOT NULL COMMENT '订单ID',
  `idempotency_key` varchar(64) NOT NULL COMMENT '幂等键(Idempotency-Key)',
  `state` varchar(24) NOT NULL DEFAULT 'INIT' COMMENT 'INIT/HOLD/CONFIRMED/FAILED',
  `currency_code` varchar(3) NOT NULL DEFAULT 'USD' COMMENT '币种快照（本次组合收款/支付分腿与找零的币种）',
  `request_json` json NULL COMMENT '首次请求快照',
  `response_json` json NULL COMMENT '首次响应快照(幂等重放返回)',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_collect_tenant_idem` (`tenant_id`, `idempotency_key`),
  KEY `idx_pay_collect_tenant_order` (`tenant_id`, `order_id`),
  KEY `idx_pay_collect_tenant_no` (`tenant_id`, `collect_no`),
  KEY `idx_pay_collect_tenant_store_created` (`tenant_id`, `store_id`, `created_at`),
  KEY `idx_pay_collect_tenant_store_type_created` (`tenant_id`, `store_id`, `business_type`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='组合收款幂等';

-- ---------------------------------------------------------------------------
-- 支付事件 Outbox（占位：业务事务与 Outbox 同库提交，Relay 按 PENDING→PUBLISHED/FAILED 投递 RocketMQ）
-- 口径依据：SAAS_PLATFORM_06_TECHNICAL.md §8；事件载荷最小化，带租户上下文，不含敏感原文
-- ---------------------------------------------------------------------------
CREATE TABLE `pay_event_outbox` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'Outbox记录ID',
  `tenant_id` bigint unsigned NULL COMMENT '租户ID，平台级事件为空',
  `event_id` varchar(64) NOT NULL COMMENT '事件ID(全局唯一，消费幂等)',
  `event_type` varchar(64) NOT NULL COMMENT '事件类型(如 payment.collect.confirmed/payment.refund.requested)',
  `aggregate_type` varchar(32) NOT NULL COMMENT '聚合类型(如 collect/refund)',
  `aggregate_id` varchar(64) NOT NULL COMMENT '聚合ID',
  `payload_json` json NOT NULL COMMENT '事件载荷(最小化，含租户上下文)',
  `status` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PUBLISHED/FAILED',
  `retry_count` int NOT NULL DEFAULT 0 COMMENT '已重试次数',
  `next_retry_at` datetime(3) NULL COMMENT '下次重试时间(指数退避)',
  `published_at` datetime(3) NULL COMMENT '投递成功时间',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_event_outbox_event` (`event_id`),
  KEY `idx_pay_event_outbox_pending` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付事件 Outbox';

-- ---------------------------------------------------------------------------
-- 支付事件消费去重表：event_id 唯一键兜底消费幂等（SAAS_PLATFORM_06_TECHNICAL.md §8）
-- Relay 以 eventId 为幂等键至少一次投递，消费者以此表去重，重复消费直接跳过。
-- ---------------------------------------------------------------------------
CREATE TABLE `pay_event_consumed` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '去重记录ID',
  `tenant_id` bigint unsigned NULL COMMENT '租户ID，平台级事件为空',
  `event_id` varchar(64) NOT NULL COMMENT '事件ID(消费幂等键)',
  `event_type` varchar(64) NOT NULL COMMENT '事件类型(如 payment.collect.confirmed)',
  `aggregate_type` varchar(32) NOT NULL COMMENT '聚合类型(如 collect)',
  `aggregate_id` varchar(64) NOT NULL COMMENT '聚合ID',
  `consumed_at` datetime(3) NOT NULL COMMENT '消费时间',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_event_consumed_event` (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付事件消费去重表';

-- ---------------------------------------------------------------------------
-- 租户支付方式（原 `tenant_payment_method`；`tenant_` 未登记前缀，改用已登记的 `tnt_`。
-- 同步改动：payment 侧 TenantPaymentMethodPo 与 platform-admin 侧 TenantPaymentMethodPo 的 @TableName。）
-- 平台授权（granted）+ 租户向用户开放开关（user_enabled，默认开）；
-- 现金为通用兜底，永远可用，无需授权记录；储值/积分/线上默认不授权，平台显式授权后租户即可用。
-- ---------------------------------------------------------------------------
CREATE TABLE `tnt_payment_method` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `method` varchar(32) NOT NULL COMMENT '支付方式编码（CASH/WALLET/POINT/ALIPAY/WECHAT/STRIPE）',
  `granted` tinyint NOT NULL DEFAULT 0 COMMENT '平台是否授权该租户使用：0=否 1=是',
  `user_enabled` tinyint NOT NULL DEFAULT 1 COMMENT '租户是否向用户开放：0=关 1=开',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tnt_payment_method_tenant_method` (`tenant_id`, `method`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户支付方式授权与开关';

-- ---------------------------------------------------------------------------
-- 退款规则配置：审批阈值支持租户/业态/门店，线下退款开关支持租户/业态。
-- ---------------------------------------------------------------------------
CREATE TABLE `pay_refund_rule_config` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户标识',
  `business_type` varchar(32) NOT NULL DEFAULT '' COMMENT '业态编码，空字符串表示租户默认',
  `store_id` bigint unsigned NULL COMMENT '门店标识，空值表示业态/租户默认',
  `approval_threshold` decimal(20,6) NOT NULL DEFAULT 0 COMMENT '需要退款审批的金额阈值，使用租户币种',
  `offline_refund_enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否允许线下退款，只有租户/业态层生效',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `idempotency_key` varchar(96) NULL COMMENT '配置写入幂等键',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_refund_rule_config_scope` (`tenant_id`, `business_type`, `store_id`),
  UNIQUE KEY `uk_pay_refund_rule_config_idempotency` (`tenant_id`, `idempotency_key`),
  KEY `idx_pay_refund_rule_config_lookup` (`tenant_id`, `business_type`, `store_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='退款审批与线下退款规则配置';

-- ---------------------------------------------------------------------------
-- 日结规则配置：日结切点仅支持门店级，值为门店本地日内分钟数（0-1439）。
-- ---------------------------------------------------------------------------
CREATE TABLE `pay_daily_closing_rule_config` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户标识',
  `store_id` bigint unsigned NOT NULL COMMENT '门店标识',
  `closing_minute` smallint unsigned NOT NULL DEFAULT 0 COMMENT '日结切点，门店本地时间距 00:00 的分钟数',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `idempotency_key` varchar(96) NULL COMMENT '配置写入幂等键',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '更新人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_daily_closing_rule_config_scope` (`tenant_id`, `store_id`),
  UNIQUE KEY `uk_pay_daily_closing_rule_config_idempotency` (`tenant_id`, `idempotency_key`),
  KEY `idx_pay_daily_closing_rule_config_lookup` (`tenant_id`, `store_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='门店日结切点配置';

-- ---------------------------------------------------------------------------
-- 种子数据：A380 线上支付渠道（默认关闭，现金/A380币/积分不依赖渠道配置）
-- 原 V8__seed_a380_payment_config.sql，幂等语义（ON DUPLICATE KEY UPDATE）保持不变。
-- ---------------------------------------------------------------------------
INSERT INTO `pay_channel_config` (`tenant_id`, `store_id`, `channel`, `enabled`, `status`, `created_at`, `updated_at`) VALUES
(100, 100, 'ALIPAY', 0, 'ACTIVE', NOW(3), NOW(3)),
(100, 100, 'WECHAT', 0, 'ACTIVE', NOW(3), NOW(3)),
(100, 100, 'STRIPE', 0, 'ACTIVE', NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE `updated_at` = `updated_at`;
