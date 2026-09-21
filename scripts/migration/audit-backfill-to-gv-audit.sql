-- =============================================================================
-- 审计日志迁移：Backfill + Verify + Switch + Rollback
-- 目标库：gv_audit（新，按 occurred_at 月分区 + 幂等台账）；源库：gv_saas（旧，普通表）
-- 说明：两个库在同一 MySQL 实例，本脚本在任一库的会话内执行，跨库读写（需同时具备两库权限）。
--       建表由 Flyway 在 gv_audit 里完成（db/migration-audit：V1 台账、V2 分区主表、V3 归档清单），
--       本脚本只负责**搬数据**。方案：docs/renovation/AUDIT_STORAGE_01_SERVICE.md §5 批次 3。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 0) 执行前准备
-- -----------------------------------------------------------------------------
-- 【前置】已执行 scripts/migration/audit-schema-bootstrap.sql（建库 + 授权）；
--         已按 audit-schema profile 发布 common-audit-service（Flyway 建表并开始写新表）。
-- 【必填】__PERIOD_START__ / __PERIOD_END__：本次回填的月份区间，左闭右开。
--   例：__PERIOD_START__ = '2026-09-01 00:00:00'，__PERIOD_END__ = '2026-10-01 00:00:00'
-- 【保留下限】只回填 occurred_at >= 保留下限（热存 24 个月）的月份；更早的记录留在旧表，
--   不搬进新表——否则它们会落在 pmin 里永远无法随保留策略清理（pmin 非空会被巡检告警）。
--   下限取值示例：SELECT DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 24 MONTH), '%Y-%m-01');
-- 【执行方式】按月循环执行下面 [Backfill] 一段（25 个月 ≈ 25 次），每次一条语句、
--   一份可控大小的事务；不要试图用一条语句搬完所有历史（长事务会顶爆 undo/主从延迟）。
-- 【幂等】新表主键 (id, occurred_at)：重复执行同一月份不会产生重复行，脚本可安全重跑。

-- -----------------------------------------------------------------------------
-- [Backfill] 单月回填（替换变量后执行；重复执行同一区间安全）
-- 字段映射：一一对应（两表列名相同），仅做 NOT NULL 兜底：
--   occurred_at：旧表允许 NULL（V3 已回填，仍兜底为 created_at，新表是 NOT NULL 且是分区键）
--   operator_type / action_label / resource_type / result / idempotency_key：新表 NOT NULL，
--   旧表虽有默认值，这里仍显式 COALESCE，避免历史上被写入过 NULL 的行搬不过去。
-- 冲突处理：ON DUPLICATE KEY UPDATE id = id 是**显式无操作**——只忽略主键重复，
--   其它约束错误（如 NOT NULL）照常报错；不用 INSERT IGNORE，避免把真实错误一起吞掉。
-- -----------------------------------------------------------------------------
INSERT INTO gv_audit.iam_audit_log
  (id, tenant_id, organization_id, store_id, operator_id, operator_name, operator_account,
   operator_type, action, action_label, resource_type, resource_id, resource_name, result,
   error_code, ip, user_agent, request_id, trace_id, source_service, idempotency_key,
   detail_json, occurred_at, created_at)
SELECT
   id, tenant_id, organization_id, store_id, operator_id, operator_name, operator_account,
   COALESCE(operator_type, 'TENANT'), action, COALESCE(action_label, action),
   COALESCE(resource_type, ''), resource_id, resource_name, COALESCE(result, 'SUCCESS'),
   error_code, ip, user_agent, request_id, trace_id, source_service, COALESCE(idempotency_key, ''),
   detail_json,
   COALESCE(occurred_at, created_at) AS occurred_at, created_at
FROM gv_saas.iam_audit_log
WHERE COALESCE(occurred_at, created_at) >= __PERIOD_START__
  AND COALESCE(occurred_at, created_at) <  __PERIOD_END__
-- 显式无操作：只忽略主键 (id, occurred_at) 重复，其它约束错误照常报错。
-- 没有这一句时重跑同一月份会直接报 Duplicate entry（与「脚本可安全重跑」的口径不符）。
-- 必须写成 `<库>.<表>.id`：INSERT ... SELECT 的源表同名列会让裸写 id 判为 ambiguous（ERROR 1052）。
ON DUPLICATE KEY UPDATE gv_audit.iam_audit_log.id = gv_audit.iam_audit_log.id;

-- -----------------------------------------------------------------------------
-- [Verify] 逐月对账：两库同区间的行数必须一致（回填后执行）
-- -----------------------------------------------------------------------------
SELECT 'gv_saas(旧)' AS source, COUNT(*) AS rows_in_period
FROM gv_saas.iam_audit_log
WHERE COALESCE(occurred_at, created_at) >= __PERIOD_START__
  AND COALESCE(occurred_at, created_at) <  __PERIOD_END__
UNION ALL
SELECT 'gv_audit(新)' AS source, COUNT(*) AS rows_in_period
FROM gv_audit.iam_audit_log
WHERE occurred_at >= __PERIOD_START__ AND occurred_at < __PERIOD_END__;

-- 抽样核对（各取 3 条最新，逐字段比对关键列；行数一致也要确认内容真的搬过来了）
SELECT id, action, action_label, result, occurred_at, created_at
FROM gv_saas.iam_audit_log
WHERE COALESCE(occurred_at, created_at) >= __PERIOD_START__
  AND COALESCE(occurred_at, created_at) <  __PERIOD_END__
ORDER BY id DESC LIMIT 3;
SELECT id, action, action_label, result, occurred_at, created_at
FROM gv_audit.iam_audit_log
WHERE occurred_at >= __PERIOD_START__ AND occurred_at < __PERIOD_END__
ORDER BY id DESC LIMIT 3;

-- 全局体检：兜底分区不得有业务数据（非 0 说明分区预建坏了 → 审计会落在永不清理的分区里）
SELECT 'pmin' AS partition_name, COUNT(*) AS rows_in_partition
FROM gv_audit.iam_audit_log PARTITION (pmin)
UNION ALL
SELECT 'pmax' AS partition_name, COUNT(*) AS rows_in_partition
FROM gv_audit.iam_audit_log PARTITION (pmax);

-- -----------------------------------------------------------------------------
-- [Switch] 单写切换
-- -----------------------------------------------------------------------------
-- 切换不是本脚本的动作，也不做 RENAME：切库与切迁移目录由 SPRING_PROFILES_ACTIVE=audit-schema
-- 一次性完成（见 application-audit-schema.yml）。因此**不需要写入冻结窗口**：
-- 加 profile 前写入仍落旧表，加 profile 后写入落新表；回填只读旧表，不会与新写入冲突。

-- -----------------------------------------------------------------------------
-- [Rollback] 回退（回退窗口 = 1 个发布周期）
-- -----------------------------------------------------------------------------
-- 1) 去掉 audit-schema profile 并重启 common-audit-service：读写立刻回到 gv_saas.iam_audit_log；
-- 2) 旧表在回退窗口内**不得删除、不得改动**（本脚本对其只读）；
-- 3) 新库可原样保留（不影响旧表）；确认稳定一个发布周期后，再决定旧表归档或删除。
-- 注意：回退期间若有新写入落在新表（含回填之后产生的记录），回退后这些记录不会出现在旧表里——
-- 因此回退前的写入窗口应记录在变更单上，必要时按 id 从 gv_audit 反向补回旧表。
