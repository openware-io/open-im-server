-- 客户域（customer，cst_ 前缀）迁移基线
-- 基线重建（批次 2）：本文件是原 V1~V13 的**累积终态**，库清空后由 Flyway 从 V1 一次性重建；下一个变更从 V2 起。
-- 口径依据：SAAS_PLATFORM_04_DATA.md §8、SAAS_PLATFORM_05_API.md §8、docs/standards/16_CURRENCY_CONVENTIONS.md §2.1/§5。
--
-- 合并来源与逐条处理：
--   V1  会员档案 / 储值账户 / 储值流水 / 积分账户 / 积分流水 —— 五张基础表。
--   V2  币种快照 currency_code（cst_wallet_ledger / cst_point_ledger）→ 并入建表定义；
--       原文件的「历史行回填 USD」UPDATE 保留在文件末尾（幂等，空库匹配 0 行）。
--   V3  懒初始化空储值账户清理 → 保留为文件末尾的幂等 DELETE（新库无行，等价于无操作）。
--   V4  客户 IM 绑定三列 im_account / im_username / im_bound_at → 并入 cst_member 建表。
--   V5  客户姓名/客户号盲索引表 cst_member_name_token → 并入建表；原先的 CREATE TABLE IF NOT EXISTS
--       改写为普通 CREATE TABLE，并按 DC-11 补齐四个审计列。
--   V6  历史脏数据「去重 → 清理 → 建索引」：原文件用 PREPARE/EXECUTE 动态 SQL 探测 order 域表是否存在，
--       是一次性数据修复；其**终态**是给 cst_member 补出 uk_cst_member_tenant_im (tenant_id, im_account)，
--       已并入建表。新库没有任何历史行，去重与清理在空库上恒为空操作，故动态 SQL、临时表与 DELETE 全部不并入。
--   V7  IM 绑定三列的历史回填（读 identity 域 idt_login_identity / idt_profile_sync_record）→ **不并入**：
--       清库重建后没有历史数据可回填；且跨域表由兄弟服务自己的 Flyway 建立，在基线里直接 JOIN 会把
--       「服务启动顺序」变成迁移能否执行的前提。需要时的做法与 identity 基线一致：放进 scripts/migration
--       下的显式回填脚本，按 git 历史里的原 V7 语句执行。
--   V8  同一账号唯一：原文件用 PREPARE/EXECUTE 在「非唯一索引 idx_cst_member_tenant_account」与
--       「唯一索引 uk_cst_member_tenant_account」之间二选一；V6 之后重复行已清空，终态是唯一索引，
--       故基线直接写 UNIQUE KEY uk_cst_member_tenant_account (tenant_id, account_id)，动态脚手架不并入。
--   V9  首次建档门店 origin_store_id 与三张流水表的 store_id / 门店归因索引 → 并入建表。
--   V10 事实事件 Outbox cst_event_outbox → 并入建表，并补齐 created_by / updated_by 两个审计列。
--   V11 积分经营规则 cst_point_rule_config → 并入建表；索引名补全表名（uk_/idx_cst_point_rule_config_*）。
--   V12 积分有效期 expires_at 与归属索引 → 并入 cst_point_ledger 建表。
--   V13 事件消费幂等 cst_event_consumed → 并入建表，并补齐四个审计列与全部列注释。
--
-- 审计列口径（DC-11：全表强制四列）：created_by / updated_by 为 bigint unsigned NOT NULL DEFAULT 0；
-- created_at / updated_at 为 datetime(3) NOT NULL。其中写入路径（MyBatis-Plus PO）没有映射 updated_*
-- 的四张表（cst_wallet_ledger、cst_point_ledger、cst_member_name_token、cst_event_consumed），以及
-- 连 created_* 也没有映射的 cst_event_consumed，其 updated_at / created_at 带 DEFAULT CURRENT_TIMESTAMP(3)：
-- 未显式写入时取落库时间，避免 NOT NULL 列在 INSERT 时缺值（与 common-audit 基线同一写法）。

-- ---------------------------------------------------------------------------
-- 会员档案（客户 + 会员身份合并为一张档案表）
-- V4 并入 IM 绑定三列；V9 并入首次建档门店；V6/V8 的唯一键终态：
-- 同一账号、同一 IM 账号各只允许一条客户档案（NULL 可重复 = 待认领 / 未绑定）。
-- ---------------------------------------------------------------------------
CREATE TABLE `cst_member` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '会员档案ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `origin_store_id` bigint unsigned NULL COMMENT '首次新增客户的门店；总部创建或历史不可判定时为空',
  `account_id` bigint unsigned NULL COMMENT '关联SaaS账号(idt_account.id)，待认领客户可为空',
  `im_account` varchar(64) NULL COMMENT 'IM 登录标识(如 im_71 / openId)',
  `im_username` varchar(128) NULL COMMENT 'IM 用户名/昵称快照',
  `im_bound_at` datetime(3) NULL COMMENT 'IM 绑定时间',
  `member_no` varchar(64) NOT NULL COMMENT '会员号',
  `name_cipher` varchar(255) NULL COMMENT '姓名密文(脚手架暂存明文，真实实现加密)',
  `phone_cipher` varchar(255) NULL COMMENT '手机号密文(脚手架暂存明文，真实实现加密)',
  `phone_digest` varchar(64) NULL COMMENT '手机号摘要(唯一性/检索)',
  `level_id` bigint unsigned NULL COMMENT '会员等级',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT 'PENDING/ACTIVE/SUSPENDED/CLOSED',
  `joined_at` datetime(3) NULL COMMENT '入会时间',
  `expires_at` datetime(3) NULL COMMENT '会员有效期至',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人(0=系统)',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人(0=系统)',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cst_member_tenant_no` (`tenant_id`, `member_no`),
  UNIQUE KEY `uk_cst_member_tenant_account` (`tenant_id`, `account_id`),
  UNIQUE KEY `uk_cst_member_tenant_im` (`tenant_id`, `im_account`),
  KEY `idx_cst_member_phone_digest` (`tenant_id`, `phone_digest`),
  KEY `idx_cst_member_status` (`tenant_id`, `status`, `created_at`),
  KEY `idx_cst_member_tenant_origin_store` (`tenant_id`, `origin_store_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会员档案';

-- ---------------------------------------------------------------------------
-- A380币储值账本（同主体同币种唯一）
-- ---------------------------------------------------------------------------
CREATE TABLE `cst_wallet_account` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '储值账户ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `customer_id` bigint unsigned NOT NULL COMMENT '会员档案ID(cst_member.id)',
  `legal_entity_id` bigint unsigned NOT NULL COMMENT '商户主体ID(tnt_legal_entity.id)',
  `currency_code` char(3) NOT NULL COMMENT '币种',
  `available_amount` bigint NOT NULL DEFAULT 0 COMMENT '可用储值余额(最小货币单位整数)',
  `frozen_amount` bigint NOT NULL DEFAULT 0 COMMENT '冻结储值余额(最小货币单位整数)',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/FROZEN/CLOSED',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人(0=系统)',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人(0=系统)',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cst_wallet_account_scope` (`tenant_id`, `customer_id`, `legal_entity_id`, `currency_code`),
  KEY `idx_cst_wallet_account_customer` (`tenant_id`, `customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='A380币储值账本';

-- ---------------------------------------------------------------------------
-- A380币储值账本流水（只追加，不更新历史流水；余额由账户行记录 + 流水校验）
-- V2 并入 currency_code（账本自证币种）；V9 并入 store_id（本笔业务发生门店）；
-- updated_by / updated_at 为 DC-11 补齐的审计列（写路径不赋值，故 updated_at 带默认值）。
-- ---------------------------------------------------------------------------
CREATE TABLE `cst_wallet_ledger` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '储值流水ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NULL COMMENT '本笔储值业务发生门店；历史不可判定时为空',
  `wallet_account_id` bigint unsigned NOT NULL COMMENT '储值账户ID',
  `entry_type` varchar(24) NOT NULL COMMENT 'RECHARGE/CONSUME/REFUND/HOLD/RELEASE/ADJUST',
  `amount` bigint NOT NULL COMMENT '变动金额(最小货币单位整数，正数)',
  `balance_after` bigint NOT NULL COMMENT '变动后可用余额',
  `currency_code` varchar(3) NOT NULL DEFAULT 'USD' COMMENT '币种快照（与钱包账户币种一致；账本自证币种，改租户设置不改历史流水）',
  `order_id` bigint unsigned NULL COMMENT '关联订单(消费/退还)',
  `fx_quote_id` bigint unsigned NULL COMMENT '汇率报价(跨币种，首发不用)',
  `idempotency_key` varchar(64) NOT NULL COMMENT '幂等键',
  `occurred_at` datetime(3) NOT NULL COMMENT '业务发生时间',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人(0=系统)',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人(0=系统)',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间(写路径未显式赋值时取落库时间)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cst_wallet_ledger_idem` (`tenant_id`, `idempotency_key`),
  KEY `idx_cst_wallet_ledger_account` (`tenant_id`, `wallet_account_id`, `created_at`),
  KEY `idx_cst_wallet_ledger_tenant_store_created` (`tenant_id`, `store_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='A380币储值账本流水';

-- ---------------------------------------------------------------------------
-- 积分账户（租户内同一会员同一计划唯一）
-- ---------------------------------------------------------------------------
CREATE TABLE `cst_point_account` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '积分账户ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `customer_id` bigint unsigned NOT NULL COMMENT '会员档案ID(cst_member.id)',
  `program_id` bigint unsigned NOT NULL COMMENT '积分计划ID',
  `available_points` bigint NOT NULL DEFAULT 0 COMMENT '可用积分',
  `frozen_points` bigint NOT NULL DEFAULT 0 COMMENT '冻结积分',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人(0=系统)',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人(0=系统)',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cst_point_account_scope` (`tenant_id`, `customer_id`, `program_id`),
  KEY `idx_cst_point_account_customer` (`tenant_id`, `customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='积分账户';

-- ---------------------------------------------------------------------------
-- 积分流水（只追加）
-- V2 并入 currency_code（积分调整所依据的租户币种口径）；V9 并入 store_id；
-- V12 并入 expires_at（EARN 流水的失效时间），updated_by / updated_at 为 DC-11 补齐的审计列。
-- ---------------------------------------------------------------------------
CREATE TABLE `cst_point_ledger` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '积分流水ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NULL COMMENT '本笔积分业务发生门店；历史不可判定时为空',
  `account_id` bigint unsigned NOT NULL COMMENT '积分账户ID',
  `entry_type` varchar(24) NOT NULL COMMENT 'EARN/REDEEM/EXPIRE/ADJUST/REVERSE',
  `points` bigint NOT NULL COMMENT '变动积分(正数增加/负数冲减)',
  `currency_code` varchar(3) NOT NULL DEFAULT 'USD' COMMENT '币种快照（积分调整所依据的租户币种口径；积分本身非货币）',
  `balance_after` bigint NOT NULL COMMENT '变动后可用积分',
  `business_type` varchar(32) NULL COMMENT '业务类型',
  `business_id` bigint unsigned NULL COMMENT '业务ID',
  `idempotency_key` varchar(64) NOT NULL COMMENT '幂等键',
  `rule_snapshot_json` json NULL COMMENT '规则快照(不可查询)',
  `expires_at` datetime(3) NULL COMMENT 'EARN 流水失效时间，NULL=不失效',
  `occurred_at` datetime(3) NOT NULL COMMENT '业务发生时间',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人(0=系统)',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人(0=系统)',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间(写路径未显式赋值时取落库时间)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cst_point_ledger_idem` (`tenant_id`, `idempotency_key`),
  KEY `idx_cst_point_ledger_account` (`tenant_id`, `account_id`, `created_at`),
  KEY `idx_cst_point_ledger_tenant_store_created` (`tenant_id`, `store_id`, `created_at`),
  KEY `idx_cst_point_ledger_expiry` (`tenant_id`, `account_id`, `expires_at`, `entry_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='积分流水';

-- ---------------------------------------------------------------------------
-- 客户姓名/客户号盲索引 token（V5 并入；原 CREATE TABLE IF NOT EXISTS 改为普通 CREATE TABLE）
-- token = 归一化文本 1/2/3-gram 的 SHA-256 十六进制（定长 64）；检索要求关键词的所有 token 都命中。
-- 同一 (tenant_id, member_id, token) 唯一 → 重建 token 集合是「先删后插」，天然幂等。
-- 既有客户的 token 回填不在迁移里做（密文解密必须在应用层），见 MemberApplicationService
-- 的 rebuildNameIndexForTenant / MemberNameIndexRebuildJob / POST /business/members/name-index/rebuild。
-- ---------------------------------------------------------------------------
CREATE TABLE `cst_member_name_token` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `member_id` bigint unsigned NOT NULL COMMENT '客户档案ID(cst_member.id)',
  `token` char(64) NOT NULL COMMENT '归一化 1/2/3-gram 的 SHA-256 十六进制（姓名 + 客户号）',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人(0=系统)',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人(0=系统)',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间(写路径未显式赋值时取落库时间)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cst_member_name_token_scope` (`tenant_id`, `member_id`, `token`),
  KEY `idx_cst_member_name_token_token` (`tenant_id`, `token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户姓名/客户号盲索引 token（密文不可 LIKE）';

-- ---------------------------------------------------------------------------
-- Customer 事实事件 Outbox（V10 并入，并补齐 created_by / updated_by）
-- 业务记账与事件行在同一个本地事务中提交，由 Relay 可靠投递。
-- ---------------------------------------------------------------------------
CREATE TABLE `cst_event_outbox` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'Outbox记录ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `event_id` varchar(64) NOT NULL COMMENT '事件ID(全局唯一，消费幂等)',
  `event_type` varchar(64) NOT NULL COMMENT '客户资产事实类型',
  `aggregate_type` varchar(32) NOT NULL COMMENT '聚合类型',
  `aggregate_id` varchar(64) NOT NULL COMMENT '聚合ID',
  `payload_json` json NOT NULL COMMENT '最小化事实载荷，不含PII',
  `status` varchar(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PUBLISHED/FAILED',
  `retry_count` int NOT NULL DEFAULT 0 COMMENT '已重试次数',
  `next_retry_at` datetime(3) NULL COMMENT '下次重试时间',
  `published_at` datetime(3) NULL COMMENT '投递成功时间',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人(0=系统)',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人(0=系统)',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cst_event_outbox_event` (`event_id`),
  KEY `idx_cst_event_outbox_pending` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Customer事实事件Outbox';

-- ---------------------------------------------------------------------------
-- 客户域积分经营规则（V11 并入；索引名由 uk_cst_point_rule_scope / uk_cst_point_rule_idem /
-- idx_cst_point_rule_resolve 补全表名为 uk_/idx_cst_point_rule_config_*，列全部加反引号与注释）
-- 租户/业态/门店作用域由业务校验约束。
-- ---------------------------------------------------------------------------
CREATE TABLE `cst_point_rule_config` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '积分规则ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `business_type` varchar(32) NOT NULL DEFAULT '' COMMENT '空字符串=租户默认',
  `store_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '0=租户/业态默认，大于0=门店覆盖',
  `earn_rate` decimal(12,4) NOT NULL DEFAULT 1.0000 COMMENT '每个计价货币最小单位获得积分倍率',
  `redeem_rate` decimal(12,4) NOT NULL DEFAULT 1.0000 COMMENT '积分抵扣规则倍率',
  `expiry_days` int unsigned NOT NULL DEFAULT 0 COMMENT '0=不失效；仅租户/业态层',
  `redeem_cap_points` bigint unsigned NOT NULL DEFAULT 0 COMMENT '每笔最大抵扣积分；0=不限制',
  `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `idempotency_key` varchar(128) DEFAULT NULL COMMENT '幂等键(租户内唯一)',
  `status` varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人(0=系统)',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人(0=系统)',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cst_point_rule_config_scope` (`tenant_id`, `business_type`, `store_id`),
  UNIQUE KEY `uk_cst_point_rule_config_idem` (`tenant_id`, `idempotency_key`),
  KEY `idx_cst_point_rule_config_resolve` (`tenant_id`, `business_type`, `store_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户域积分经营规则';

-- ---------------------------------------------------------------------------
-- Customer 消费支付收款确认事件的幂等记录（V13 并入，并补齐四个审计列与全部列注释）
-- 仅在积分获得成功后写入。
-- ---------------------------------------------------------------------------
CREATE TABLE `cst_event_consumed` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '事件消费记录ID',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `event_id` varchar(64) NOT NULL COMMENT '事件ID(全局唯一，消费幂等)',
  `event_type` varchar(64) NOT NULL COMMENT '事件类型',
  `aggregate_type` varchar(32) NOT NULL COMMENT '聚合类型',
  `aggregate_id` varchar(64) NOT NULL COMMENT '聚合ID',
  `consumed_at` datetime(3) NOT NULL COMMENT '消费时间',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人(0=系统)',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间(写路径未显式赋值时取落库时间)',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人(0=系统)',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '更新时间(写路径未显式赋值时取落库时间)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cst_event_consumed_event` (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Customer事件消费幂等记录';

-- ---------------------------------------------------------------------------
-- 数据语句（V2 / V3 的原语义，全部幂等；新库为空，执行结果等价于无操作）
-- ---------------------------------------------------------------------------

-- V2：历史流水币种一律回填 USD（新列已经带 DEFAULT 'USD'，此处显式声明口径并兜住空串脏值）。
UPDATE `cst_wallet_ledger` SET `currency_code` = 'USD' WHERE `currency_code` IS NULL OR `currency_code` = '';
UPDATE `cst_point_ledger` SET `currency_code` = 'USD' WHERE `currency_code` IS NULL OR `currency_code` = '';

-- V3：删除「余额为 0 且没有任何账本流水」的无用储值账户。
-- 背景：此前读路径会懒创建空账户；变更后账户只在首次充值（会写 RECHARGE 流水）时创建。
-- 安全边界：有过任何流水的账户一律保留（流水是财务事实，即使余额已归零）。
-- 幂等：删完后重复执行匹配 0 行；空库上等价于无操作。
DELETE a FROM `cst_wallet_account` a
  LEFT JOIN `cst_wallet_ledger` l ON l.wallet_account_id = a.id
 WHERE a.available_amount = 0
   AND a.frozen_amount = 0
   AND l.id IS NULL;