-- 门店设置是门店级页面；原菜单只有名称没有 path，点击后不会进入任何路由。
UPDATE `iam_menu`
SET `path` = '/admin/store/settings',
    `updated_at` = NOW(3)
WHERE `code` = 'store.settings';
