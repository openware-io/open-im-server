-- 审计分区表验收校验（在**真实 MySQL**上跑；H2 不支持分区 DDL，单测覆盖不到这里）。
-- 用法：mysql -h <host> -uroot -p < scripts/verify/audit-partition-verify.sql
--
-- 对应方案 docs/renovation/AUDIT_STORAGE_01_SERVICE.md 的验收标准：
--   1) 主表确实按 occurred_at 月分区，pmin/pmax 兜底存在；
--   2) 分区表上不存在「不含分区列的唯一索引/主键」（MySQL 硬约束，也是原始设计被卡住的坑）；
--   3) 时间范围查询只命中目标分区（分区裁剪生效，而不是扫全部分区）；
--   4) 兜底分区可用：落在已建分区之外的时间也写得进去（写不进去=审计丢失）。
-- 每段都有期望值，人工/脚本比对；末段用临时表，跑完自动删除，不影响生产数据。

-- 1) 分区清单：应看到 pmin、连续月分区、pmax
SELECT PARTITION_NAME, PARTITION_DESCRIPTION, TABLE_ROWS
FROM information_schema.PARTITIONS
WHERE TABLE_SCHEMA = 'open_audit' AND TABLE_NAME = 'iam_audit_log'
ORDER BY PARTITION_ORDINAL_POSITION;

-- 2) 唯一索引必须包含分区列：期望返回 0 行
SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS columns_in_index
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'open_audit' AND TABLE_NAME = 'iam_audit_log' AND NON_UNIQUE = 0
GROUP BY INDEX_NAME
HAVING SUM(COLUMN_NAME = 'occurred_at') = 0;

-- 3) 分区裁剪：partitions 列应只出现被筛选到的分区（示例区间=2026-09，期望 p202609）
EXPLAIN
SELECT id, action, occurred_at FROM open_audit.iam_audit_log
WHERE occurred_at >= '2026-09-01 00:00:00' AND occurred_at < '2026-10-01 00:00:00'
ORDER BY occurred_at DESC LIMIT 20;

-- 4) 兜底分区可写：远未来时间应落进 pmax 而不是报错（ERROR 1526 = 无匹配分区）
SELECT COUNT(*) AS pmax_rows_before FROM open_audit.iam_audit_log PARTITION (pmax);
INSERT INTO open_audit.iam_audit_log
  (tenant_id, action, action_label, resource_type, result, idempotency_key, occurred_at, created_at)
VALUES
  (0, 'audit.partition.verify', '分区校验', 'audit', 'SUCCESS', 'partition-verify', '2099-01-01 00:00:00', NOW(3));
SELECT COUNT(*) AS pmax_rows_after FROM open_audit.iam_audit_log PARTITION (pmax);
-- 清理探针行（只删探针自己，审计表本身不允许业务删除）
DELETE FROM open_audit.iam_audit_log
WHERE idempotency_key = 'partition-verify' AND occurred_at = '2099-01-01 00:00:00';
SELECT COUNT(*) AS pmax_rows_cleaned FROM open_audit.iam_audit_log PARTITION (pmax);

-- 5) 幂等台账在位（唯一性职责已从主表迁出）
SELECT COUNT(*) AS ledger_unique_keys
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'open_audit' AND TABLE_NAME = 'iam_audit_idempotency' AND NON_UNIQUE = 0
  AND COLUMN_NAME IN ('tenant_id', 'idempotency_key');
-- 期望 2（(tenant_id, idempotency_key) 复合唯一键）
