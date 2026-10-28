-- Domain: identity; incremental migration.
-- idx_idt_oauth_link_account(account_id) is fully covered by idx_idt_oauth_link_account_app(account_id, app_id).
ALTER TABLE `idt_oauth_link`
  DROP INDEX `idx_idt_oauth_link_account`;
