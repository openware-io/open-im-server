-- 会员 / A380币储值 / 积分 基线（customer 域，cst_ 前缀）
-- 口径依据：SAAS_PLATFORM_04_DATA.md §8、SAAS_PLATFORM_05_API.md §8、KTV_BUSINESS_01_SERVICE.md §8

-- 会员档案（客户 + 会员身份合并为一张档案表）
CREATE TABLE `cst_member` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '会员档案ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `account_id` bigint unsigned NULL COMMENT '关联SaaS账号(idt_account.id)，待认领客户可为空',
  `member_no` varchar(64) NOT NULL COMMENT '会员号',
  `name_cipher` varchar(255) NULL COMMENT '姓名密文(脚手架暂存明文，真实实现加密)',
  `phone_cipher` varchar(255) NULL COMMENT '手机号密文(脚手架暂存明文，真实实现加密)',
  `phone_digest` varchar(64) NULL COMMENT '手机号摘要(唯一性/检索)',
  `level_id` bigint unsigned NULL COMMENT '会员等级',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT 'PENDING/ACTIVE/SUSPENDED/CLOSED',
  `joined_at` datetime(3) NULL COMMENT '入会时间',
  `expires_at` datetime(3) NULL COMMENT '会员有效期至',
  `version` int NOT NULL DEFAULT 0,
  `created_by` bigint unsigned NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cst_member_tenant_no` (`tenant_id`, `member_no`),
  KEY `idx_cst_member_phone_digest` (`tenant_id`, `phone_digest`),
  KEY `idx_cst_member_status` (`tenant_id`, `status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会员档案';

-- A380币储值账本（同主体同币种唯一）
CREATE TABLE `cst_wallet_account` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '储值账户ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `customer_id` bigint unsigned NOT NULL COMMENT '会员档案ID(cst_member.id)',
  `legal_entity_id` bigint unsigned NOT NULL COMMENT '商户主体ID(tnt_legal_entity.id)',
  `currency_code` char(3) NOT NULL COMMENT '币种',
  `available_amount` bigint NOT NULL DEFAULT 0 COMMENT '可用储值余额(最小货币单位整数)',
  `frozen_amount` bigint NOT NULL DEFAULT 0 COMMENT '冻结储值余额(最小货币单位整数)',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/FROZEN/CLOSED',
  `version` int NOT NULL DEFAULT 0,
  `created_by` bigint unsigned NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cst_wallet_account_scope` (`tenant_id`, `customer_id`, `legal_entity_id`, `currency_code`),
  KEY `idx_cst_wallet_account_customer` (`tenant_id`, `customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='A380币储值账本';

-- A380币储值账本流水（只追加，不更新历史流水；余额由账户行记录 + 流水校验）
CREATE TABLE `cst_wallet_ledger` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '储值流水ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `wallet_account_id` bigint unsigned NOT NULL COMMENT '储值账户ID',
  `entry_type` varchar(24) NOT NULL COMMENT 'RECHARGE/CONSUME/REFUND/HOLD/RELEASE/ADJUST',
  `amount` bigint NOT NULL COMMENT '变动金额(最小货币单位整数，正数)',
  `balance_after` bigint NOT NULL COMMENT '变动后可用余额',
  `order_id` bigint unsigned NULL COMMENT '关联订单(消费/退还)',
  `fx_quote_id` bigint unsigned NULL COMMENT '汇率报价(跨币种，首发不用)',
  `idempotency_key` varchar(64) NOT NULL COMMENT '幂等键',
  `occurred_at` datetime(3) NOT NULL COMMENT '业务发生时间',
  `created_by` bigint unsigned NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cst_wallet_ledger_idem` (`tenant_id`, `idempotency_key`),
  KEY `idx_cst_wallet_ledger_account` (`tenant_id`, `wallet_account_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='A380币储值账本流水';

-- 积分账户
CREATE TABLE `cst_point_account` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '积分账户ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `customer_id` bigint unsigned NOT NULL COMMENT '会员档案ID(cst_member.id)',
  `program_id` bigint unsigned NOT NULL COMMENT '积分计划ID',
  `available_points` bigint NOT NULL DEFAULT 0 COMMENT '可用积分',
  `frozen_points` bigint NOT NULL DEFAULT 0 COMMENT '冻结积分',
  `version` int NOT NULL DEFAULT 0,
  `created_by` bigint unsigned NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL,
  `updated_by` bigint unsigned NOT NULL DEFAULT 0,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cst_point_account_scope` (`tenant_id`, `customer_id`, `program_id`),
  KEY `idx_cst_point_account_customer` (`tenant_id`, `customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='积分账户';

-- 积分流水（只追加）
CREATE TABLE `cst_point_ledger` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '积分流水ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `account_id` bigint unsigned NOT NULL COMMENT '积分账户ID',
  `entry_type` varchar(24) NOT NULL COMMENT 'EARN/REDEEM/EXPIRE/ADJUST/REVERSE',
  `points` bigint NOT NULL COMMENT '变动积分(正数增加/负数冲减)',
  `balance_after` bigint NOT NULL COMMENT '变动后可用积分',
  `business_type` varchar(32) NULL COMMENT '业务类型',
  `business_id` bigint unsigned NULL COMMENT '业务ID',
  `idempotency_key` varchar(64) NOT NULL COMMENT '幂等键',
  `rule_snapshot_json` json NULL COMMENT '规则快照(不可查询)',
  `occurred_at` datetime(3) NOT NULL COMMENT '业务发生时间',
  `created_by` bigint unsigned NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cst_point_ledger_idem` (`tenant_id`, `idempotency_key`),
  KEY `idx_cst_point_ledger_account` (`tenant_id`, `account_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='积分流水';
