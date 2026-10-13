-- 集团 IDaaS 基线（基线重建批次 2：原 V1__group_account_baseline.sql + V2__seed_default_admin.sql 合并为唯一 V1）
--
-- 合并口径说明：
--   V1 group_account_baseline —— group_account / group_org / idaas_client 三张表
--   V2 seed_default_admin     —— 默认管理员 / 默认组织「集团」/ 两个接入方（保留在本文件末尾，INSERT IGNORE 幂等语义不变）
--
-- 本次执行的规范修正（表名保持不变）：
--   1. 补齐 4 个审计字段 —— group_org 原本一个审计字段都没有；group_account / idaas_client 原有
--      created_at / updated_at，补 created_by / updated_by；
--   2. `group_` / `idaas_` 前缀**故意不改**：该前缀属挂起的前缀决策（DC-02 集团业务领域登记，前缀待定），
--      登记表 scripts/validate/table-prefixes.txt 与 docs/ENGINEERING_RULES.md 本轮不动，
--      因此门禁的 `unregistered table prefix 'group' / 'idaas'` 条目是**已知且接受**的。
--
-- 审计列的写入方与取值策略（原表写入方未写操作人列，为不改变行为，操作人列带 DEFAULT 0 兜底）：
--   group_account —— AuthApplicationService 只读；写入仅 V2 种子（本文件末尾）→ 种子显式写 created_at/updated_at，
--                    操作人列取默认 0；
--   group_org     —— 无 Java 写入方（GroupOrgMapper 只读）；写入仅 V2 种子 → 种子显式写 created_at/updated_at，
--                    操作人列取默认 0；
--   idaas_client  —— ClientApplicationService.create() 显式写 created_at/updated_at（PO 已补 createdBy/updatedBy
--                    字段，未赋值时 MyBatis-Plus 不拼该列，取 DEFAULT 0）。

-- ---------------------------------------------------------------------------
-- 集团管理员账号
-- ---------------------------------------------------------------------------
CREATE TABLE `group_account` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '账号ID',
  `username` varchar(64) NOT NULL COMMENT '用户名（唯一）',
  `password` varchar(100) NOT NULL COMMENT 'BCrypt 密码哈希',
  `display_name` varchar(64) NULL COMMENT '显示名',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED' COMMENT '状态 ENABLED/DISABLED',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_account_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='集团管理员账号';

-- ---------------------------------------------------------------------------
-- 集团组织
-- ---------------------------------------------------------------------------
CREATE TABLE `group_org` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '组织ID',
  `name` varchar(64) NOT NULL COMMENT '组织名称',
  `code` varchar(64) NOT NULL COMMENT '组织编码',
  `parent_id` bigint unsigned NULL COMMENT '父组织ID（当前仅一级，暂不使用）',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED' COMMENT '状态 ENABLED/DISABLED',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_org_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='集团组织';

-- ---------------------------------------------------------------------------
-- IDaaS 接入方
-- ---------------------------------------------------------------------------
CREATE TABLE `idaas_client` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '接入方ID',
  `name` varchar(64) NOT NULL COMMENT '接入方名称',
  `client_id` varchar(64) NOT NULL COMMENT '客户端ID（唯一）',
  `client_secret` varchar(128) NOT NULL COMMENT '客户端密钥',
  `redirect_uris` varchar(512) NULL COMMENT '逗号分隔的重定向 URI',
  `status` varchar(16) NOT NULL DEFAULT 'ENABLED' COMMENT '状态 ENABLED/DISABLED',
  `created_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '创建操作人',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  `updated_by` bigint unsigned NOT NULL DEFAULT 0 COMMENT '最后更新操作人',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idaas_client_client_id` (`client_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='IDaaS 接入方';

-- ---------------------------------------------------------------------------
-- 种子数据（原 V2__seed_default_admin.sql，逐字节保留原值，行为不变）：
-- 默认管理员 admin、默认组织「集团」、两个接入方（im-admin / saas-admin）。
--
-- ⚠️ 历史凭据说明（不静默清洗，按交接书 §2 步骤 1 的 secret-exposure 口径如实报告）：
--   group_account.password 是 admin 账号的 BCrypt 哈希（历史默认口令，已在运行环境轮换；
--   `.outputs/quality/secret-exposure-report.txt` 列为 "Historical (rotated) credential value"）。
--   idaas_client.client_secret 的 'im-admin-secret-placeholder-7f3a9c1d' /
--   'saas-admin-secret-placeholder-9b2e4f7a' 是接入方占位密钥字面量。
--   本基线**保持原值不变**（改值会改变登录/SSO 行为），仅在此注明来源与性质。
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO `group_account` (`username`, `password`, `display_name`, `status`, `created_at`, `updated_at`)
VALUES ('admin', '$2b$10$uj5EtFLiaUGBRWv5gbSFfeC85AT5OTIJuTzYUZ7PUD/StOSsCeXtS', '集团管理员', 'ENABLED', NOW(3), NOW(3));

INSERT IGNORE INTO `group_org` (`name`, `code`, `parent_id`, `status`, `created_at`, `updated_at`)
VALUES ('集团', 'group', NULL, 'ENABLED', NOW(3), NOW(3));

INSERT IGNORE INTO `idaas_client` (`name`, `client_id`, `client_secret`, `redirect_uris`, `status`, `created_at`, `updated_at`)
VALUES ('IM 后台', 'im-admin', 'im-admin-secret-placeholder-7f3a9c1d', 'http://localhost:3000/oauth/callback', 'ENABLED', NOW(3), NOW(3));

INSERT IGNORE INTO `idaas_client` (`name`, `client_id`, `client_secret`, `redirect_uris`, `status`, `created_at`, `updated_at`)
VALUES ('SaaS 后台', 'saas-admin', 'saas-admin-secret-placeholder-9b2e4f7a', 'http://localhost:4100/oauth/callback', 'ENABLED', NOW(3), NOW(3));
