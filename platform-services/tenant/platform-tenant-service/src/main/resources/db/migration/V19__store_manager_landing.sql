-- 门店经理登录后应进入门店工作台，而不是无权限的租户总部经营总览。
UPDATE iam_role_profile
SET landing_route = '/store'
WHERE role_code = 'store.manager' AND landing_route = '/admin/tenant/overview';
