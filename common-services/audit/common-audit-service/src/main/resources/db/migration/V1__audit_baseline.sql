-- ============================================================================
-- 通用审计域（common-audit-service）· 老库 open_im 迁移基线
-- 仅适用于未执行过该基线的新建数据库。
--
-- 本文件是历史三个脚本叠加后的**累积最终结构**，合并为单文件（DC-10：工程未冻结，
-- 允许重建基线；老库 open_im 将被销毁重建）：
--   * V1__audit_log.sql                  -> 建 audit_log 普通表
--   * V2__unified_audit_log.sql（加列/加索引部分）-> 已折叠进 CREATE TABLE
--   * V4__audit_idempotency_ledger.sql        -> 建 audit_idempotency 幂等台账
-- 历史数据回填仍由 V3__audit_occurred_at_backfill.sql 承担（幂等，对空库是空操作），
-- 因此不在此处重复，避免「结构 + 数据」两件事混在一个版本里。
--
-- 口径：
--   * 审计记录只增不改、不物理删除；tenant_id = 0 表示平台级动作（如创建租户），
--     租户视角查询永远不会命中它；
--   * 动作码 action 是稳定契约，action_label 是中文标签（可随产品文案演进）；
--   * 老库主表保留 (tenant_id, idempotency_key) 幂等唯一键；新库（open_audit）的分区主表
--     放不下这个唯一键（分区表的每个唯一索引都必须含分区列），幂等唯一性改由
--     audit_idempotency 台账承担；
--   * result / operator_type 用字符串枚举，避免 MySQL ENUM 变更需要 DDL。
--   * 审计表由服务端批量写入（AuditLogMapper#insertBatch），写入语句不含 created_by /
--     updated_by / updated_at，故这三个审计列必须带默认值，否则严格模式下写入会报 1364。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 通用审计日志主表：谁、在哪个租户/组织/门店、做了什么、对哪个对象、结果如何、从哪来。
-- ---------------------------------------------------------------------------
CREATE TABLE `audit_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '租户ID，0=平台级动作',
  `organization_id` bigint unsigned NULL COMMENT '组织ID',
  `store_id` bigint unsigned NULL COMMENT '门店ID',
  `operator_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT '操作人账号ID',
  `operator_name` varchar(128) NULL COMMENT '操作人名称快照',
  `operator_account` varchar(128) NULL COMMENT '操作人账号（登录名/工号）',
  `operator_type` varchar(16) NOT NULL DEFAULT 'TENANT' COMMENT '操作人类型 PLATFORM/TENANT',
  `action` varchar(128) NOT NULL COMMENT '动作稳定码 如 order.settle',
  `action_label` varchar(128) NOT NULL DEFAULT '' COMMENT '动作中文标签',
  `resource_type` varchar(64) NOT NULL DEFAULT '' COMMENT '资源类型 如 ord_order',
  `resource_id` varchar(128) NULL COMMENT '资源ID',
  `resource_name` varchar(255) NULL COMMENT '资源名称快照',
  `result` varchar(16) NOT NULL DEFAULT 'SUCCESS' COMMENT '结果 SUCCESS/FAILURE',
  `error_code` varchar(64) NULL COMMENT '失败错误码',
  `ip` varchar(45) NULL COMMENT '来源IP（IPv6 兼容）',
  `user_agent` varchar(512) NULL COMMENT 'User-Agent（截断存储）',
  `request_id` varchar(64) NULL COMMENT '请求ID',
  `trace_id` varchar(64) NULL COMMENT '链路追踪ID',
  `source_service` varchar(64) NULL COMMENT '上报来源服务（内部鉴权来源）',
  `idempotency_key` varchar(128) NOT NULL COMMENT '幂等键',
  `detail_json` json NULL COMMENT '详情快照（写入前脱敏）',
  `occurred_at` datetime(3) NULL COMMENT '业务发生时间（缺省等于落库时间）',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人用户ID，0=系统自动留痕',
  `created_at` datetime(3) NOT NULL COMMENT '落库时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新人用户ID，0=系统自动留痕',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_audit_log_tenant_idempotency` (`tenant_id`, `idempotency_key`),
  KEY `idx_audit_log_tenant_time` (`tenant_id`, `created_at`),
  KEY `idx_audit_log_operator_time` (`operator_id`, `created_at`),
  KEY `idx_audit_log_action_time` (`action`, `created_at`),
  KEY `idx_audit_log_resource` (`resource_type`, `resource_id`),
  KEY `idx_audit_log_result_time` (`result`, `created_at`),
  KEY `idx_audit_log_request` (`request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通用审计日志：谁在哪个租户/组织/门店对哪个资源做了什么、结果如何、从哪来';

-- ---------------------------------------------------------------------------
-- 审计写入幂等台账：只承担重试窗口内的重复上报去重，按 created_at 定期清理即可，
-- 体量远小于审计主表。写入顺序（AuditLogRepositoryImpl#saveIfAbsent）：先抢台账，
-- 首次上报才写主表；两者在同一事务内，不存在「抢到键但没写主表」的中间态。
-- id 为代理主键，幂等唯一性仍由 (tenant_id, idempotency_key) 唯一键保证。
-- ---------------------------------------------------------------------------
CREATE TABLE `audit_idempotency` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint unsigned NOT NULL COMMENT '租户ID（0=平台级动作）',
  `idempotency_key` varchar(128) NOT NULL COMMENT '幂等键（口径见 AuditLogApplicationService）',
  `audit_id` bigint unsigned NULL COMMENT '首次上报落库的审计记录ID（首次写入后回填）',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建人用户ID，0=系统自动留痕',
  `created_at` datetime(3) NOT NULL COMMENT '首次上报时间（清理依据）',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新人用户ID，0=系统自动留痕',
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_audit_idempotency_tenant_key` (`tenant_id`, `idempotency_key`),
  KEY `idx_audit_idempotency_created` (`created_at`) COMMENT '按时间清理：幂等只需覆盖重试窗口'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审计写入幂等台账：唯一性职责从审计主表迁出';
