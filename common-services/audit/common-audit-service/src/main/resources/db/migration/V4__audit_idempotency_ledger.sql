-- 审计写入幂等台账（方案 docs/renovation/AUDIT_STORAGE_01_SERVICE.md §2.2）。
--
-- 背景：审计主表要按 occurred_at 做月分区，而 MySQL 要求**分区表的每个唯一索引都必须包含分区列**。
-- 现唯一键 (tenant_id, idempotency_key) 一旦被迫补上时间列，重试晚 1 毫秒就不再冲突
-- —— 幂等会静默失效，BFF 拦截器与领域服务双写同一次操作会变成两条记录。
-- 因此把「唯一性」职责从主表迁到本台账：台账只承担**重试窗口内的重复上报去重**，
-- 按 created_at 定期清理即可，体量远小于主表。
--
-- 写入顺序（见 AuditLogRepositoryImpl.saveIfAbsent）：先抢台账 → 首次上报才写主表 → 回填 audit_id。
-- 台账与主表写入在同一事务内，因此不存在「抢到键但没写主表」的中间态。
CREATE TABLE `iam_audit_idempotency` (
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID（0=平台级动作）',
  `idempotency_key` varchar(128) NOT NULL COMMENT '幂等键（口径见 AuditLogApplicationService）',
  `audit_id` bigint unsigned NULL COMMENT '首次上报落库的审计记录ID（首次写入后回填）',
  `created_at` datetime(3) NOT NULL COMMENT '首次上报时间（清理依据）',
  PRIMARY KEY (`tenant_id`, `idempotency_key`),
  KEY `idx_iam_audit_idem_created` (`created_at`) COMMENT '按时间清理：幂等只需覆盖重试窗口'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审计写入幂等台账：唯一性职责从审计主表迁出';
