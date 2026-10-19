-- Rebrand the wallet display name from the legacy A380 name to OpenChat.
-- wallet_brand_name is tenant configuration, normally edited from the SaaS
-- admin console; this migration updates existing seeded rows and is idempotent
-- for fresh databases.
UPDATE `tnt_tenant_config`
SET `config_value` = 'OpenChat币',
    `updated_at` = NOW(3)
WHERE `config_key` = 'wallet_brand_name'
  AND `config_value` = 'A380币';
