-- 通用审计日志表
CREATE TABLE `iam_audit_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID',
  `operator_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '操作人账号ID',
  `action` varchar(128) NOT NULL COMMENT '动作 如 ktv.session.close',
  `resource_type` varchar(64) NOT NULL COMMENT '资源类型',
  `resource_id` varchar(128) NULL COMMENT '资源ID',
  `request_id` varchar(64) NULL COMMENT '请求ID',
  `idempotency_key` varchar(128) NOT NULL COMMENT '幂等键',
  `detail_json` json NULL COMMENT '详情快照',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iam_audit_idempotency` (`tenant_id`, `idempotency_key`),
  KEY `idx_iam_audit_query` (`tenant_id`, `action`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通用审计日志';
