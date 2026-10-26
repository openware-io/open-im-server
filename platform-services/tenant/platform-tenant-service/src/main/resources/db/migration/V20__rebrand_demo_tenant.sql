-- Remove the legacy A380 brand from the demo tenant data.
UPDATE tnt_tenant
SET tenant_code = 'openchat'
WHERE tenant_code = 'a380';

UPDATE tnt_store
SET name = 'OpenChat Hotel'
WHERE name = 'A380 Hotel';

UPDATE tnt_tenant_config
SET config_value = 'OpenChat币'
WHERE config_key = 'wallet_brand_name' AND config_value LIKE '%A380%';
