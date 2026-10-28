-- OpenChat is the IM product; the SaaS demo tenant must use SaaS naming.
UPDATE tnt_tenant
SET tenant_code = 'saas-demo', name = 'SaaS 生活服务'
WHERE tenant_code = 'openchat';

UPDATE tnt_store
SET name = CASE
    WHEN name = 'OpenChat Hotel' THEN 'SaaS Hotel'
    WHEN name = 'OpenChat KTV 旗舰店' THEN 'SaaS KTV 旗舰店'
    ELSE name
END
WHERE tenant_id = 100;

UPDATE tnt_tenant_config
SET config_value = 'SaaS 币'
WHERE config_key = 'wallet_brand_name' AND config_value = 'OpenChat币';
