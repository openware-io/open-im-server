-- Domain: customer; adds tenant membership linkage without creating a second customer master.
ALTER TABLE `cst_member`
  ADD COLUMN `membership_id` bigint unsigned NULL
    COMMENT '租户成员身份ID（tnt_membership.id，仅标识）' AFTER `account_id`,
  ADD UNIQUE KEY `uk_cst_member_membership` (`membership_id`),
  ADD KEY `idx_cst_member_tenant_membership` (`tenant_id`, `membership_id`);
