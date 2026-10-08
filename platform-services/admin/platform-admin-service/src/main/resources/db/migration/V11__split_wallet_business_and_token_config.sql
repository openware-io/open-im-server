-- 储值业务属于门店经营下的客户资产；代币名称与展示比例属于租户级配置。
UPDATE iam_menu
   SET parent_id = 203,
       sort_no = 30,
       scope_level = 'STORE',
       required_permission = 'wallet.view',
       required_grant = NULL,
       name = '储值管理',
       path = '/business/wallet',
       status = 'ACTIVE',
       updated_at = CURRENT_TIMESTAMP(3)
 WHERE id = 21
   AND code = 'wallet';

INSERT INTO iam_menu
    (id, parent_id, code, name, path, icon, scope_level, domain_code, required_permission,
     required_grant, business_type, i18n_key, sort_no, status, created_by, updated_by, created_at, updated_at)
VALUES
    (30, 101, 'tenant.wallet-token-config', '代币配置', '/admin/tenant/wallet-token-config', 'setting',
     'TENANT', 'core', 'tenant.tenant.manage', NULL, NULL, NULL, 30, 'ACTIVE', 0, 0,
     CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
ON DUPLICATE KEY UPDATE
    parent_id = VALUES(parent_id),
    name = VALUES(name),
    path = VALUES(path),
    icon = VALUES(icon),
    scope_level = VALUES(scope_level),
    domain_code = VALUES(domain_code),
    required_permission = VALUES(required_permission),
    required_grant = VALUES(required_grant),
    sort_no = VALUES(sort_no),
    status = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP(3);
