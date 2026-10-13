-- 支付渠道服务基线：渠道配置 / 渠道交易
-- 仅适用于未执行该基线的新建数据库：本文件是原 V1 + V2 的累积终态，清库后由 Flyway 从 V1 一次性重建。
-- 合并来源：原 V2 的回调事件 ID（pay_channel_transaction.callback_event_id）与其唯一键，已内联进建表语句，不再使用 ALTER TABLE。
-- 基线重建（批次 2）：每表补齐 4 个审计字段（pay_channel_transaction 原缺 created_by / updated_by）；
--                   索引名统一为 <前缀>_<完整表名>_<语义>（表名不缩写）；标识符统一加反引号。
-- 表名保持单数，沿用既有表名与已登记前缀 pay_channel_（本模块未列入前缀改登记清单）。

-- 支付渠道配置（微信/支付宝/Stripe，租户/门店开关，密钥加密托管占位）。
CREATE TABLE `pay_channel_provider` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `store_id` bigint unsigned NULL COMMENT 'NULL=租户级开关',
  `provider` varchar(16) NOT NULL COMMENT 'WECHAT/ALIPAY/STRIPE',
  `enabled` tinyint NOT NULL DEFAULT 0 COMMENT '0=关 1=开',
  `merchant_id` varchar(64) NULL COMMENT '渠道商户号',
  `secret_encrypted` text NULL COMMENT '渠道密钥密文占位（平台加密托管）',
  `secret_key_ref` varchar(128) NULL COMMENT '密钥托管引用占位（KMS/Vault 密钥 ID）',
  `status` varchar(24) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_channel_provider_tenant_store_provider` (`tenant_id`, `store_id`, `provider`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付渠道配置（密钥加密托管占位）';

-- 渠道交易（仅记录渠道侧交易与回调原始报文，不拥有业务资金账本）。
-- 回调事件幂等去重：Stripe event id / 微信 v3 通知 id / 支付宝 notify_id，重放防护。
CREATE TABLE `pay_channel_transaction` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `transaction_id` varchar(64) NOT NULL COMMENT '渠道交易号 provider transaction id',
  `callback_event_id` varchar(64) NULL COMMENT '回调事件 ID（重放去重）',
  `provider` varchar(16) NOT NULL COMMENT 'WECHAT/ALIPAY/STRIPE',
  `order_id` varchar(64) NULL COMMENT '业务订单号',
  `amount` bigint NOT NULL COMMENT '金额最小货币单位整数（分）',
  `currency` varchar(8) NOT NULL COMMENT '币种 ISO 4217',
  `status` varchar(24) NOT NULL COMMENT 'CREATED/PROCESSING/SUCCEEDED/FAILED/CLOSED',
  `callback_raw` mediumtext NULL COMMENT '回调原始报文（受限保存，禁止输出密钥/卡号）',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_channel_transaction_provider_transaction` (`provider`, `transaction_id`),
  UNIQUE KEY `uk_pay_channel_transaction_provider_event` (`provider`, `callback_event_id`),
  KEY `idx_pay_channel_transaction_tenant_order` (`tenant_id`, `order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付渠道交易（回调原始报文）';
