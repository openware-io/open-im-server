-- 审计写入幂等台账（方案 docs/renovation/AUDIT_STORAGE_01_SERVICE.md §2.2）。
--
-- 本目录（db/migration-audit）是**独立 schema gv_audit 的全新基线**，与 db/migration（老库 gv_saas 的历史）
-- 互不复用版本号：审计表迁到新库时物理设计已变（分区主表 + 台账），沿用老库 V1+V5 会在新库先建普通表、
-- 再建分区表而冲突；迁移规范也允许「确认不存在历史库时重建 V1 基线」，新库正是这种情况。
-- 两个目录由 application-audit-schema.yml 按 profile 二选一（见方案 §5 批次 2c）。
--
-- 背景：审计主表要按 occurred_at 做月分区，而 MySQL 要求**分区表的每个唯一索引都必须包含分区列**。
-- 现唯一键 (tenant_id, idempotency_key) 一旦被迫补上时间列，重试晚 1 毫秒就不再冲突
-- —— 幂等会静默失效，BFF 拦截器与领域服务双写同一次操作会变成两条记录。
-- 因此把「唯一性」职责从主表迁到本台账：台账只承担**重试窗口内的重复上报去重**，
-- 按 created_at 定期清理即可，体量远小于主表。
--
-- 写入顺序（见 AuditLogRepositoryImpl.saveIfAbsent）：先抢台账 → 首次上报才写主表。
-- audit_id 在抢占时就写入（主键由应用侧生成，抢占前已知），因此不需要「先插主表再回填」的第二步；
-- 该列保留 NULL 只为一处历史残留兜底：旧版本「先插主表再回填」中断时留下的空值行，
-- 恢复路径会在补写主表后把它补回填（只补空值，不覆盖既有 ID）。
-- 台账与主表写入在同一事务内，因此不存在「抢到键但没写主表」的中间态。
CREATE TABLE `iam_audit_idempotency` (
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID（0=平台级动作）',
  `idempotency_key` varchar(128) NOT NULL COMMENT '幂等键（口径见 AuditLogApplicationService）',
  `audit_id` bigint unsigned NULL COMMENT '首次上报落库的审计记录ID（首次写入后回填）',
  `created_at` datetime(3) NOT NULL COMMENT '首次上报时间（清理依据）',
  PRIMARY KEY (`tenant_id`, `idempotency_key`),
  KEY `idx_iam_audit_idem_created` (`created_at`) COMMENT '按时间清理：幂等只需覆盖重试窗口'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审计写入幂等台账：唯一性职责从审计主表迁出';
