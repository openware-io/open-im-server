-- Domain: identity; incremental migration.
-- IM 当前 openId 是全局 im_<userId>；app_id 是 OAuth client 维度，C/B 共享同一 SaaS account。
ALTER TABLE `idt_oauth_link`
  ADD COLUMN `app_id` varchar(64) NOT NULL DEFAULT 'legacy'
    COMMENT 'IM开放平台应用ID' AFTER `provider`;

ALTER TABLE `idt_oauth_link`
  DROP INDEX `uk_idt_oauth_link_provider_account`,
  ADD UNIQUE KEY `uk_idt_oauth_link_provider_app_account`
    (`provider`, `app_id`, `provider_account_id`),
  ADD KEY `idx_idt_oauth_link_account_app` (`account_id`, `app_id`);
