-- 审计冷归档清单（方案 docs/renovation/AUDIT_STORAGE_01_SERVICE.md §2.3）。
--
-- 用途：作为「哪些月份已归档、是否允许删除」的**唯一可追溯证据**。本阶段只写清单并标记 ARCHIVED
-- （把该月从可查范围排除），**不投递对象存储、不 DROP PARTITION**；投递与删除待后续安排，
-- 届时的硬约束是：清单存在 + 校验和复核通过，才允许 DROP PARTITION。
--
-- 每个 (period, partition_name) 唯一：同一个月重复执行归档只会留一条，归档任务可安全重跑。
CREATE TABLE `iam_audit_archive_manifest` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `period` char(7) NOT NULL COMMENT '归档月份 YYYY-MM',
  `partition_name` varchar(64) NOT NULL COMMENT '被归档的分区名（如 p202508）',
  `object_path` varchar(512) NULL COMMENT '归档对象路径（本阶段未投递时为空）',
  `row_count` bigint unsigned NOT NULL DEFAULT 0 COMMENT '归档行数（导出时统计）',
  `checksum_sha256` char(64) NULL COMMENT '归档文件校验和（删除前必须复核）',
  `state` varchar(16) NOT NULL COMMENT 'ARCHIVED=已归档（超出可查范围）/DROPPED=分区已删/HELD=法律保留不动',
  `operator_id` bigint unsigned NULL COMMENT '执行归档/删除的操作人ID',
  `created_at` datetime(3) NOT NULL COMMENT '归档时间',
  `dropped_at` datetime(3) NULL COMMENT '分区删除时间（未删除为空）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iam_audit_archive_period` (`period`, `partition_name`) COMMENT '归档幂等：同月重复执行只留一条',
  KEY `idx_iam_audit_archive_state` (`state`, `period`) COMMENT '按状态取可查范围下限与待处理月份'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审计冷归档清单：归档与删除的可追溯证据';
