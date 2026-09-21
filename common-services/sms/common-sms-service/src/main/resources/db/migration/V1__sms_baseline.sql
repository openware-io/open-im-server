-- 短信服务基线表：渠道配置 / 模板 / 发送日志
CREATE TABLE `sms_channel_config` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `provider` varchar(32) NOT NULL COMMENT '服务商 aliyun/tencent',
  `access_key` varchar(512) NOT NULL COMMENT 'AccessKey 加密托管占位',
  `secret_key` varchar(512) NOT NULL COMMENT 'SecretKey 加密托管占位',
  `sign_name` varchar(64) NOT NULL COMMENT '短信签名',
  `enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '启用开关',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sms_channel_tenant_provider` (`tenant_id`, `provider`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='短信渠道配置';

CREATE TABLE `sms_template` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '租户ID 0=平台',
  `code` varchar(64) NOT NULL COMMENT '模板 code',
  `content` varchar(1024) NOT NULL COMMENT '模板内容',
  `approval_status` varchar(32) NOT NULL DEFAULT 'PENDING' COMMENT '审批状态 PENDING/APPROVED/REJECTED',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sms_template_tenant_code` (`tenant_id`, `code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='短信模板';

CREATE TABLE `sms_send_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `phone_digest` varchar(64) NOT NULL COMMENT '手机号 SHA-256 摘要，不落明文',
  `phone_masked` varchar(16) NOT NULL COMMENT '手机号脱敏展示',
  `template_code` varchar(64) NOT NULL COMMENT '模板 code',
  `provider` varchar(32) NOT NULL COMMENT '服务商 aliyun/tencent',
  `status` varchar(32) NOT NULL COMMENT '状态 PENDING/SUCCESS/FAILED',
  `retry_count` int NOT NULL DEFAULT 0 COMMENT '重试次数',
  `provider_message_id` varchar(128) NULL COMMENT '服务商发送流水',
  `raw_response` varchar(2048) NULL COMMENT '服务商返回原文',
  `idempotency_key` varchar(128) NOT NULL COMMENT '幂等键',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sms_send_idempotency` (`tenant_id`, `idempotency_key`),
  KEY `idx_sms_send_query` (`tenant_id`, `template_code`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='短信发送日志';
