-- 开放平台第三方接入：审核监管流程。
-- 1) app_secret_hash 改为可空：申请时 PENDING 尚未分配密钥，审核通过时才生成并落哈希。
-- 2) 新增驳回原因与审核审计字段。
ALTER TABLE `open_application`
  MODIFY COLUMN `app_secret_hash` varchar(64) NULL COMMENT '应用密钥 SHA-256 哈希（审核通过后分配）',
  ADD COLUMN `reject_reason` varchar(512) NULL COMMENT '驳回原因' AFTER `status`,
  ADD COLUMN `reviewed_by` bigint unsigned NOT NULL DEFAULT '0' COMMENT '审核人 ID，0 表示系统' AFTER `reject_reason`,
  ADD COLUMN `reviewed_at` datetime(3) NULL COMMENT '审核时间' AFTER `reviewed_by`;
