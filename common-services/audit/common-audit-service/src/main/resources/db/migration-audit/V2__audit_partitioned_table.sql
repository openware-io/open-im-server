-- 审计主表（独立 schema open_audit）：按业务发生时间 occurred_at 做月分区。
-- 方案与取舍：docs/renovation/AUDIT_STORAGE_01_SERVICE.md（§2.1 结构、§3 为什么用原生分区、§3 为什么按 occurred_at 分区）。
-- 本目录是 open_audit 的全新基线（版本号与 db/migration 的老库历史互不复用，见 V1 文件头说明）。
--
-- 关键口径（改动前请先读方案）：
--   * 分区列必须是 occurred_at：前端的时间筛选与排序都用它；若按 created_at 分区，查询谓词无法裁剪分区，
--     分区等于白做。pmin / pmax 兜底保证**任何时间都写得进去**（写入失败=审计丢失=合规事故）。
--   * 本表**没有唯一键**：MySQL 要求分区表的每个唯一索引都包含分区列，而幂等键
--     (tenant_id, idempotency_key) 一旦被迫带上时间列，重试晚 1 毫秒就不再冲突、幂等静默失效。
--     幂等唯一性已迁到 iam_audit_idempotency（V4），本表只负责留痕。
--   * occurred_at 收紧为 NOT NULL：V3 已回填历史空值、写路径已保证非空，排序与时间过滤收敛到单列，
--     不再需要 COALESCE(occurred_at, created_at) 这种无法走索引的表达式。
--   * 索引的时间列全部对齐 occurred_at（改造前是 created_at，与真实查询谓词不一致、几乎用不上）；
--     并删掉 idx_iam_audit_query(tenant_id,action,created_at)——它与 (tenant_id,action,occurred_at) 完全同义。
--   * 分区覆盖到 2026-12，之后由 AuditRetentionJob 用 REORGANIZE pmax 按月追加（方案 §5 批次 4）。
--     换机器/换环境部署晚于 2026-12 也不会丢数据：新记录落 pmax，由 job 补分区；pmax 非空会告警。
CREATE TABLE `iam_audit_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键（应用侧/自增均可，分区表要求它在主键首列）',
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
  `idempotency_key` varchar(128) NOT NULL COMMENT '幂等键快照（唯一性在 iam_audit_idempotency，本列供水位排查）',
  `detail_json` json NULL COMMENT '详情快照（写入前脱敏）',
  `occurred_at` datetime(3) NOT NULL COMMENT '业务发生时间（分区键，服务端收敛，不接受空值）',
  `created_at` datetime(3) NOT NULL COMMENT '落库时间',
  PRIMARY KEY (`id`, `occurred_at`),
  KEY `idx_iam_audit_time` (`occurred_at`) COMMENT '平台视角按发生时间排序分页，以及 pmin/pmax 健康检查',
  KEY `idx_iam_audit_tenant_time` (`tenant_id`, `occurred_at`) COMMENT '租户视角按发生时间',
  KEY `idx_iam_audit_tenant_action_time` (`tenant_id`, `action`, `occurred_at`) COMMENT '租户视角按动作+时间',
  KEY `idx_iam_audit_action_time` (`action`, `occurred_at`) COMMENT '平台视角按动作+时间（审计页操作类型筛选）',
  KEY `idx_iam_audit_operator_time` (`operator_id`, `occurred_at`) COMMENT '按操作人追溯',
  KEY `idx_iam_audit_result_time` (`result`, `occurred_at`) COMMENT '只看失败（FAILURE 为少数，选择性可用）',
  KEY `idx_iam_audit_resource` (`resource_type`, `resource_id`) COMMENT '按资源对象追溯',
  KEY `idx_iam_audit_request` (`request_id`) COMMENT '按请求ID串联同一次操作'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='通用审计日志（按 occurred_at 月分区；只增不改）'
  PARTITION BY RANGE COLUMNS(`occurred_at`) (
    PARTITION `pmin`    VALUES LESS THAN ('2024-09-01 00:00:00'),
    PARTITION `p202409` VALUES LESS THAN ('2024-10-01 00:00:00'),
    PARTITION `p202410` VALUES LESS THAN ('2024-11-01 00:00:00'),
    PARTITION `p202411` VALUES LESS THAN ('2024-12-01 00:00:00'),
    PARTITION `p202412` VALUES LESS THAN ('2025-01-01 00:00:00'),
    PARTITION `p202501` VALUES LESS THAN ('2025-02-01 00:00:00'),
    PARTITION `p202502` VALUES LESS THAN ('2025-03-01 00:00:00'),
    PARTITION `p202503` VALUES LESS THAN ('2025-04-01 00:00:00'),
    PARTITION `p202504` VALUES LESS THAN ('2025-05-01 00:00:00'),
    PARTITION `p202505` VALUES LESS THAN ('2025-06-01 00:00:00'),
    PARTITION `p202506` VALUES LESS THAN ('2025-07-01 00:00:00'),
    PARTITION `p202507` VALUES LESS THAN ('2025-08-01 00:00:00'),
    PARTITION `p202508` VALUES LESS THAN ('2025-09-01 00:00:00'),
    PARTITION `p202509` VALUES LESS THAN ('2025-10-01 00:00:00'),
    PARTITION `p202510` VALUES LESS THAN ('2025-11-01 00:00:00'),
    PARTITION `p202511` VALUES LESS THAN ('2025-12-01 00:00:00'),
    PARTITION `p202512` VALUES LESS THAN ('2026-01-01 00:00:00'),
    PARTITION `p202601` VALUES LESS THAN ('2026-02-01 00:00:00'),
    PARTITION `p202602` VALUES LESS THAN ('2026-03-01 00:00:00'),
    PARTITION `p202603` VALUES LESS THAN ('2026-04-01 00:00:00'),
    PARTITION `p202604` VALUES LESS THAN ('2026-05-01 00:00:00'),
    PARTITION `p202605` VALUES LESS THAN ('2026-06-01 00:00:00'),
    PARTITION `p202606` VALUES LESS THAN ('2026-07-01 00:00:00'),
    PARTITION `p202607` VALUES LESS THAN ('2026-08-01 00:00:00'),
    PARTITION `p202608` VALUES LESS THAN ('2026-09-01 00:00:00'),
    PARTITION `p202609` VALUES LESS THAN ('2026-10-01 00:00:00'),
    PARTITION `p202610` VALUES LESS THAN ('2026-11-01 00:00:00'),
    PARTITION `p202611` VALUES LESS THAN ('2026-12-01 00:00:00'),
    PARTITION `p202612` VALUES LESS THAN ('2027-01-01 00:00:00'),
    PARTITION `pmax`    VALUES LESS THAN (MAXVALUE)
  );
