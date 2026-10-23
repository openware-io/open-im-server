-- Domain: identity; applies only to a new database without executed baseline.
-- IM OAuth 是应用级的：同一 provider 下，openId 按 app_id 隔离。
ALTER TABLE `idt_oauth_link`
  ADD COLUMN `app_id` varchar(64) NOT NULL DEFAULT 'legacy'
    COMMENT 'IM开放平台应用ID' AFTER `provider`,
  ADD COLUMN `union_id` varchar(128) NULL
    COMMENT 'IM UnionID（同一开放平台主体）' AFTER `provider_account_id`;

ALTER TABLE `idt_oauth_link`
  DROP INDEX `uk_idt_oauth_link_provider_account`,
  ADD UNIQUE KEY `uk_idt_oauth_link_provider_app_account`
    (`provider`, `app_id`, `provider_account_id`),
  ADD KEY `idx_idt_oauth_link_account_app` (`account_id`, `app_id`);
