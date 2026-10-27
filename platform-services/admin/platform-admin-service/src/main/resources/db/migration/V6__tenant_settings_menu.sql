-- 租户设置：租户级品牌配置入口；原菜单只有名称没有 path。
UPDATE `iam_menu`
SET `path` = '/admin/tenant/settings',
    `updated_at` = NOW(3)
WHERE `code` = 'tenant.settings';
